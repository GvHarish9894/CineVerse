// Run after :composeApp:checkWasmHasNoSecrets with Node 22+ and CHROME_BIN set.
// This read-only loopback FIXTURE tests the actual production document, not a mocked Kotlin entrypoint.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const {spawn} = require('node:child_process');

const dist = path.resolve(__dirname, '../../../../../../../../build/dist/wasmJs/productionExecutable');
const artifacts = fs.mkdtempSync(path.join(os.tmpdir(), 'w1-bootstrap-FIXTURE-'));
const report = {fixture: true, distribution: dist, artifacts, steps: []};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const mime = {'.html': 'text/html', '.js': 'application/javascript', '.wasm': 'application/wasm', '.ttf': 'font/ttf', '.json': 'application/json'};

function serve(request, response) {
    const pathname = new URL(request.url, 'http://localhost').pathname;
    const json = (status, body) => { response.writeHead(status, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'}); response.end(JSON.stringify(body)); };
    if (request.method !== 'GET') return json(405, {error: 'read_only_fixture'});
    if (pathname.startsWith('/api/')) {
        if (pathname === '/api/session') return json(200, {signedIn: false, accountObjectId: null, displayName: null, sessionGeneration: '0'});
        if (pathname.startsWith('/api/tmdb/genre/')) return json(200, {genres: []});
        if (pathname.startsWith('/api/tmdb/')) return json(200, {page: 1, results: [], total_pages: 1, total_results: 0});
        return json(404, {error: 'not_a_fixture_endpoint'});
    }
    if (pathname === '/favicon.ico') { response.writeHead(204); return response.end(); }
    const relative = decodeURIComponent(pathname).replace(/^\/+/, '');
    let file = path.resolve(dist, relative);
    if (!file.startsWith(dist + path.sep) && file !== dist) { response.writeHead(404); return response.end(); }
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
        // SPA fallback is for documents only. A wrong asset path must remain a real failure.
        if ((request.headers.accept || '').includes('text/html')) file = path.join(dist, 'index.html');
        else { response.writeHead(404); return response.end(); }
    }
    response.writeHead(200, {'Content-Type': mime[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store'});
    fs.createReadStream(file).pipe(response);
}

async function until(condition, description, milliseconds = 15000) {
    const deadline = Date.now() + milliseconds;
    do { const result = await condition(); if (result) return result; await sleep(100); } while (Date.now() < deadline);
    throw Error('Timed out: ' + description);
}

class Cdp {
    constructor(socket) {
        this.socket = socket; this.serial = 0; this.pending = new Map(); this.events = [];
        socket.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            if (message.id) {
                const pending = this.pending.get(message.id); this.pending.delete(message.id);
                if (message.error) pending.reject(Error(JSON.stringify(message.error))); else pending.resolve(message.result);
            } else this.events.push(message);
        });
    }
    static async connect(url) {
        const socket = new WebSocket(url);
        await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, {once: true}); socket.addEventListener('error', reject, {once: true}); });
        return new Cdp(socket);
    }
    call(method, params = {}) {
        const id = ++this.serial;
        return new Promise((resolve, reject) => { this.pending.set(id, {resolve, reject}); this.socket.send(JSON.stringify({id, method, params})); });
    }
    close() { this.socket.close(); }
}

function bootstrapFailures(events) {
    return events.filter(event => event.method === 'Runtime.exceptionThrown' ||
        // Reload cancels the preceding document's catalog/font Fetch requests; missing assets still fail.
        (event.method === 'Network.loadingFailed' && !(event.params.canceled && event.params.type === 'Fetch')) ||
        (event.method === 'Network.responseReceived' && event.params.response.status >= 400));
}

async function checkDocument(cdp, origin, label, expected, navigate, bootstrapRequired = true) {
    const step = {label, expected, responses: []}; report.steps.push(step);
    cdp.events = [];
    await navigate();
    const tree = await until(async () => {
        assert.deepEqual(bootstrapFailures(cdp.events), [], label + ': resource/runtime failure');
        const tree = await cdp.call('Accessibility.getFullAXTree');
        const names = tree.nodes.filter(node => !node.ignored).map(node => node.name?.value);
        if (!names.includes(expected) || !names.includes('Sign in')) return false;
        const responses = cdp.events.filter(event => event.method === 'Network.responseReceived').map(event => event.params.response);
        const fonts = responses.filter(response => response.url.includes('/composeResources/') && response.url.endsWith('.ttf'));
        return !bootstrapRequired || (fonts.some(font => font.url.endsWith('/inter_regular.ttf')) && fonts.some(font => font.url.endsWith('/bricolage_grotesque_extrabold.ttf'))) ? tree : false;
    }, label + ': Kotlin route and bundled fonts');
    const document = (await cdp.call('Runtime.evaluate', {expression: 'JSON.stringify({url:location.href,base:document.baseURI,script:document.querySelector("script[src]").src})', returnByValue: true})).result.value;
    step.document = JSON.parse(document);
    assert.equal(step.document.base, origin + '/');
    assert.equal(step.document.script, origin + '/composeApp.js');
    step.responses = cdp.events.filter(event => event.method === 'Network.responseReceived').map(event => ({url: event.params.response.url, status: event.params.response.status, mime: event.params.response.mimeType}));
    const script = step.responses.find(response => response.url === origin + '/composeApp.js');
    if (bootstrapRequired) assert.ok(script && script.status === 200 && script.mime.includes('javascript'), label + ': real JS, not fallback HTML');
    const wasm = step.responses.filter(response => response.url.endsWith('.wasm'));
    if (bootstrapRequired) assert.equal(wasm.length, 2, label + ': Kotlin and Skiko Wasm');
    assert.ok(wasm.every(response => response.status === 200 && response.mime === 'application/wasm'));
    assert.ok(step.responses.filter(response => response.url.includes('/composeResources/')).every(response => response.url.startsWith(origin + '/composeResources/') && response.status === 200 && response.mime !== 'text/html'));
    assert.deepEqual(bootstrapFailures(cdp.events), []);
    fs.writeFileSync(path.join(artifacts, label + '-ax.json'), JSON.stringify(tree, null, 2));
    fs.writeFileSync(path.join(artifacts, label + '-events.json'), JSON.stringify(cdp.events, null, 2));
    const screenshot = await cdp.call('Page.captureScreenshot', {format: 'png'});
    fs.writeFileSync(path.join(artifacts, label + '-FIXTURE.png'), Buffer.from(screenshot.data, 'base64'));
    step.passed = true;
}

(async () => {
    assert.ok(fs.existsSync(path.join(dist, 'index.html')), 'Build the production distribution first');
    assert.ok(process.env.CHROME_BIN, 'Set CHROME_BIN to the leased Chrome executable');
    const server = http.createServer(serve);
    let chrome; const pages = [];
    try {
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        const origin = 'http://127.0.0.1:' + server.address().port;
        const forbidden = await fetch(origin + '/api/not-a-fixture-endpoint');
        assert.equal(forbidden.status, 404); assert.match(forbidden.headers.get('content-type'), /application\/json/);
        assert.equal((await fetch(origin + '/api/auth/start', {method: 'POST'})).status, 405);
        const profile = path.join(artifacts, 'isolated-profile');
        const stderr = fs.openSync(path.join(artifacts, 'chrome.log'), 'w');
        chrome = spawn(process.env.CHROME_BIN, ['--headless', '--remote-debugging-port=0', '--user-data-dir=' + profile, '--no-first-run', '--no-default-browser-check', '--disable-background-networking', 'about:blank'], {stdio: ['ignore', 'ignore', stderr]});
        fs.closeSync(stderr);
        report.chromePid = chrome.pid;
        const activePort = path.join(profile, 'DevToolsActivePort');
        await until(() => { if (chrome.exitCode !== null) throw Error('Isolated Chrome exited'); return fs.existsSync(activePort); }, 'isolated Chrome startup');
        const port = fs.readFileSync(activePort, 'utf8').split('\n')[0];
        const debugging = 'http://127.0.0.1:' + port;
        const cases = [['root', '/', 'Discover your next favourite'], ['media', '/movie/155', 'movie · 155'], ['person', '/person/287', 'Person · 287'], ['search', '/search?q=long%20web%20%26%20query', 'Search · long web & query']];
        for (const [label, route, expected] of cases) {
            const target = await (await fetch(debugging + '/json/new?about:blank', {method: 'PUT'})).json();
            const cdp = await Cdp.connect(target.webSocketDebuggerUrl); pages.push(cdp);
            await cdp.call('Page.enable'); await cdp.call('Runtime.enable'); await cdp.call('Network.enable'); await cdp.call('Accessibility.enable');
            await cdp.call('Network.setCacheDisabled', {cacheDisabled: true});
            await cdp.call('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
            await checkDocument(cdp, origin, label + '-direct', expected, () => cdp.call('Page.navigate', {url: origin + route}));
            await checkDocument(cdp, origin, label + '-reload', expected, () => cdp.call('Page.reload', {ignoreCache: true}));
            if (label === 'root') {
                const rootHistory = await cdp.call('Page.getNavigationHistory');
                const rootId = rootHistory.entries[rootHistory.currentIndex].id;
                await checkDocument(cdp, origin, 'history-person', 'Person · 287', () => cdp.call('Page.navigate', {url: origin + '/person/287'}));
                const personHistory = await cdp.call('Page.getNavigationHistory');
                const personId = personHistory.entries[personHistory.currentIndex].id;
                await checkDocument(cdp, origin, 'history-back', expected, () => cdp.call('Page.navigateToHistoryEntry', {entryId: rootId}), false);
                await checkDocument(cdp, origin, 'history-forward', 'Person · 287', () => cdp.call('Page.navigateToHistoryEntry', {entryId: personId}), false);
            }
            await cdp.call('Page.close'); cdp.close();
        }
        report.passed = true;
    } finally {
        for (const cdp of pages) cdp.close();
        if (chrome && chrome.exitCode === null) { chrome.kill('SIGTERM'); await new Promise(resolve => chrome.once('exit', resolve)); }
        await new Promise(resolve => server.close(resolve));
        report.resourcesReleased = true;
        report.failureEvents = pages.flatMap(cdp => bootstrapFailures(cdp.events));
        fs.writeFileSync(path.join(artifacts, 'report.json'), JSON.stringify(report, null, 2));
        console.log('FIXTURE evidence: ' + artifacts);
    }
    console.log('PASS: production root/media/person/search fresh documents + reload, Back/Forward, JS/2 Wasm/font resources; no account writes.');
})().catch(error => { console.error(error); process.exitCode = 1; });
