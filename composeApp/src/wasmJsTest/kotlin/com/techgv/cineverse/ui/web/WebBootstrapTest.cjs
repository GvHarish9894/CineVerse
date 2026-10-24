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
        this.fetches = new Set(); this.precedingFetches = new Set();
        this.requests = new Map(); this.timeline = []; this.boundary = 'setup';
        socket.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            if (message.id) {
                const pending = this.pending.get(message.id); this.pending.delete(message.id);
                if (message.error) pending.reject(Error(JSON.stringify(message.error))); else pending.resolve(message.result);
            } else {
                const params = message.params;
                const receivedAt = Date.now();
                if (message.method === 'Network.requestWillBeSent') {
                    const request = {requestId: params.requestId, url: params.request.url, method: params.request.method, type: params.type, frameId: params.frameId,
                        loaderId: params.loaderId, timestamp: params.timestamp, wallTime: params.wallTime, initiator: params.initiator,
                        boundary: this.boundary, receivedAt, lifecycle: []};
                    this.requests.set(params.requestId, request);
                    if (params.type === 'Fetch') this.fetches.add(params.requestId);
                    message.params = request;
                } else if (message.method === 'Network.responseReceived') {
                    message.params = {requestId: params.requestId, timestamp: params.timestamp, type: params.type, frameId: params.frameId,
                        loaderId: params.loaderId, response: {url: params.response.url, status: params.response.status, mimeType: params.response.mimeType}};
                } else if (message.method === 'Runtime.exceptionThrown') {
                    const details = params.exceptionDetails;
                    message.params = {exceptionDetails: {text: details.text, url: details.url, lineNumber: details.lineNumber,
                        columnNumber: details.columnNumber, exception: {className: details.exception?.className, description: details.exception?.description}}};
                } else if (message.method.startsWith('Network.')) {
                    // Diagnostic whitelist: never persist headers, cookies, bodies or credential-bearing extra-info events.
                    if (!['Network.loadingFinished', 'Network.loadingFailed'].includes(message.method)) return;
                } else if (!message.method.startsWith('Page.')) return;
                if (message.method === 'Network.responseReceived' || message.method === 'Network.loadingFinished' || message.method === 'Network.loadingFailed') {
                    this.requests.get(params.requestId)?.lifecycle.push({method: message.method, receivedAt, boundary: this.boundary, ...message.params});
                }
                if (message.method === 'Network.loadingFinished' || message.method === 'Network.loadingFailed') this.fetches.delete(params.requestId);
                this.events.push(message);
                this.timeline.push({...message, receivedAt, boundary: this.boundary});
            }
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
    async documentIdentity(withDom = false) {
        try {
            const frame = (await this.call('Page.getFrameTree')).frameTree.frame;
            const evaluation = await this.call('Runtime.evaluate', {expression: 'JSON.stringify({url:location.href,timeOrigin:performance.timeOrigin,readyState:document.readyState})', returnByValue: true});
            return {receivedAt: Date.now(), frameId: frame.id, loaderId: frame.loaderId, frameUrl: frame.url, ...JSON.parse(evaluation.result.value),
                ...(withDom ? {backendNodeId: (await this.call('DOM.getDocument', {depth: 0})).root.backendNodeId} : {})};
        } catch (error) { return {receivedAt: Date.now(), unavailable: error.message}; }
    }
    close() { this.socket.close(); }
}

function bootstrapFailures(events, precedingFetches) {
    return events.filter(event => event.method === 'Runtime.exceptionThrown' ||
        // Ignore only Fetches already outstanding before navigation, never the new document's failures.
        (event.method === 'Network.loadingFailed' && !(event.params.canceled && event.params.type === 'Fetch' && precedingFetches.has(event.params.requestId))) ||
        (event.method === 'Network.responseReceived' && event.params.response.status >= 400));
}

function checkFailureClassification() {
    const preceding = new Set(['old']);
    const failure = (requestId, canceled, type) => ({method: 'Network.loadingFailed', params: {requestId, canceled, type}});
    assert.deepEqual(bootstrapFailures([failure('old', true, 'Fetch')], preceding), []);
    for (const event of [failure('active', true, 'Fetch'), failure('old', false, 'Fetch'), failure('old', true, 'Script'),
        {method: 'Runtime.exceptionThrown', params: {}}, {method: 'Network.responseReceived', params: {response: {status: 404}}}]) {
        assert.deepEqual(bootstrapFailures([event], preceding), [event]);
    }
    console.log('PASS: preceding-only cancellation classification; active/non-canceled/non-Fetch/runtime/HTTP failures remain failures.');
}

function sameDocument(expected, actual) {
    return ['frameId', 'loaderId', 'url', 'timeOrigin'].every(key => expected?.[key] !== undefined && expected[key] === actual?.[key]);
}

function authoritativeAxState(tree, expected, before, after) {
    const nodes = tree.nodes.filter(node => !node.ignored);
    const root = nodes.find(node => node.role?.value === 'RootWebArea');
    const axUrl = root?.properties?.find(property => property.name === 'url')?.value?.value;
    if (!sameDocument(expected, before) || !sameDocument(expected, after) || root?.frameId !== expected.frameId || axUrl !== expected.url ||
        root.backendDOMNodeId === undefined || root.backendDOMNodeId !== before.backendNodeId || root.backendDOMNodeId !== after.backendNodeId) return 'RejectedIdentity';
    // WebApp.kt: only Ready(null) renders this exact header button; absent/conflicting labels prove nothing.
    const ready = nodes.some(node => node.role?.value === 'button' && node.name?.value === 'Sign in');
    const checking = nodes.some(node => ['Verifying', 'Checking session…'].includes(node.name?.value));
    const failed = nodes.some(node => ['Unverified', 'Session verification failed. Account actions are unavailable.'].includes(node.name?.value));
    if ([ready, checking, failed].filter(Boolean).length !== 1) return 'UnknownOrConflicting';
    return ready ? 'Ready' : checking ? 'Checking' : 'Failed';
}

function checkOutcomeGuards() {
    const current = {frameId: 'frame', loaderId: 'new', url: 'http://fixture/', timeOrigin: 2, backendNodeId: 7};
    const root = {role: {value: 'RootWebArea'}, frameId: 'frame', backendDOMNodeId: 7, properties: [{name: 'url', value: {value: current.url}}]};
    const tree = (name, role = 'StaticText') => ({nodes: [root, {name: {value: name}, role: {value: role}}]});
    assert.equal(authoritativeAxState(tree('Sign in', 'button'), current, current, current), 'Ready');
    assert.equal(authoritativeAxState(tree('Verifying'), current, current, current), 'Checking');
    assert.equal(authoritativeAxState(tree('Unverified'), current, current, current), 'Failed');
    assert.equal(authoritativeAxState(tree('Sign in'), current, current, current), 'UnknownOrConflicting');
    for (const old of [{...current, loaderId: 'old'}, {...current, timeOrigin: 1}, {...current, frameId: 'old'}, {...current, url: 'http://fixture/old'}]) {
        assert.equal(authoritativeAxState(tree('Sign in', 'button'), current, old, current), 'RejectedIdentity');
        assert.equal(authoritativeAxState(tree('Sign in', 'button'), current, current, old), 'RejectedIdentity');
    }
    assert.equal(authoritativeAxState(tree('Sign in', 'button'), current, {...current, backendNodeId: 8}, current), 'RejectedIdentity');
    assert.equal(authoritativeAxState(tree('Sign in', 'button'), current, current, {...current, backendNodeId: 8}), 'RejectedIdentity');
    assert.equal(authoritativeAxState({nodes: [{...root, frameId: 'old'}, tree('Sign in', 'button').nodes[1]]}, current, current, current), 'RejectedIdentity');
    assert.equal(authoritativeAxState({nodes: [...tree('Sign in', 'button').nodes, tree('Verifying').nodes[1]]}, current, current, current), 'UnknownOrConflicting');
    const event = {method: 'Network.loadingFailed', params: {requestId: 'session', canceled: true, type: 'Fetch'}};
    const request = {method: 'GET', url: 'http://fixture/api/session', frameId: 'frame', loaderId: 'new'};
    for (const [candidate, accepted] of [[request, true], [{...request, method: 'POST'}, false], [{...request, url: 'http://other/api/session'}, false], [{...request, loaderId: 'old'}, false]]) {
        assert.equal(exactSessionAbort(event, {requests: new Map([['session', candidate]])}, current, 'http://fixture'), accepted);
    }
    console.log('PASS: same-document AX Ready/Checking/Failed and exact session request guards reject stale/ambiguous outcomes.');
}

function exactSessionAbort(event, cdp, expected, origin) {
    const request = cdp.requests.get(event.params?.requestId);
    return event.method === 'Network.loadingFailed' && event.params.canceled && event.params.type === 'Fetch' &&
        request?.method === 'GET' && request.url === origin + '/api/session' &&
        request.frameId === expected?.frameId && request.loaderId === expected?.loaderId;
}

async function observeSessionOutcome(cdp, origin, step, precedingFetches) {
    const expected = step.observations.at(-1);
    const navigation = cdp.events.findLast(event => event.method === 'Page.frameNavigated' && event.params.frame.id === expected?.frameId && event.params.frame.loaderId === expected?.loaderId);
    const probe = {startedAt: Date.now(), expected, samples: [], outcome: 'NotEligible'};
    step.sessionOutcomeProbe = probe;
    try {
        if (!navigation || navigation.params.frame.url !== expected.url || sameDocument(expected, step.before) ||
            !bootstrapFailures(cdp.events, precedingFetches).every(event => exactSessionAbort(event, cdp, expected, origin))) return;
        const deadline = Date.now() + 5000;
        probe.outcome = 'Pending';
        do {
            const failures = bootstrapFailures(cdp.events, precedingFetches);
            if (failures.some(event => !exactSessionAbort(event, cdp, expected, origin))) { probe.outcome = 'UnrelatedStrictFailure'; return; }
            const before = await cdp.documentIdentity(true);
            const tree = await cdp.call('Accessibility.getFullAXTree');
            const after = await cdp.documentIdentity(true);
            const state = authoritativeAxState(tree, expected, before, after);
            probe.samples.push({receivedAt: Date.now(), before, after, state});
            if (bootstrapFailures(cdp.events, precedingFetches).some(event => !exactSessionAbort(event, cdp, expected, origin))) { probe.outcome = 'UnrelatedStrictFailure'; return; }
            if (['Ready', 'Failed', 'RejectedIdentity'].includes(state)) { probe.outcome = state; return; }
            await sleep(100);
        } while (Date.now() < deadline);
        probe.timedOut = true;
    } finally {
        probe.finishedAt = Date.now();
        probe.sessionRequests = [...cdp.requests.values()].filter(request => request.url === origin + '/api/session');
    }
}

async function checkDocument(cdp, origin, label, expected, navigate, bootstrapRequired = true) {
    const before = await cdp.documentIdentity();
    const precedingFetches = new Set(cdp.fetches);
    cdp.precedingFetches = precedingFetches;
    const step = {label, expected, responses: [], precedingFetches: [...precedingFetches], before, observations: [], axObservations: []}; report.steps.push(step);
    cdp.events = []; cdp.boundary = label;
    step.navigationRequestedAt = Date.now();
    step.navigationResult = await navigate();
    step.navigationReturnedAt = Date.now();
    const tree = await until(async () => {
        step.observations.push(await cdp.documentIdentity());
        const strictFailures = bootstrapFailures(cdp.events, precedingFetches);
        if (strictFailures.length && !step.sessionOutcomeProbe) {
            step.originalStrictFailures = strictFailures;
            await observeSessionOutcome(cdp, origin, step, precedingFetches);
        }
        assert.deepEqual(bootstrapFailures(cdp.events, precedingFetches), [], label + ': resource/runtime failure');
        const tree = await cdp.call('Accessibility.getFullAXTree');
        const names = tree.nodes.filter(node => !node.ignored).map(node => node.name?.value);
        step.axObservations.push({receivedAt: Date.now(), expectedMatched: names.includes(expected), guestMatched: names.includes('Sign in'), after: await cdp.documentIdentity()});
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
    const finalFailures = bootstrapFailures(cdp.events, precedingFetches);
    if (finalFailures.length && !step.sessionOutcomeProbe) {
        step.originalStrictFailures = finalFailures;
        await observeSessionOutcome(cdp, origin, step, precedingFetches);
    }
    assert.deepEqual(bootstrapFailures(cdp.events, precedingFetches), []);
    fs.writeFileSync(path.join(artifacts, label + '-ax.json'), JSON.stringify(tree, null, 2));
    fs.writeFileSync(path.join(artifacts, label + '-events.json'), JSON.stringify(cdp.events, null, 2));
    const screenshot = await cdp.call('Page.captureScreenshot', {format: 'png'});
    fs.writeFileSync(path.join(artifacts, label + '-FIXTURE.png'), Buffer.from(screenshot.data, 'base64'));
    step.passed = true;
}

(async () => {
    checkFailureClassification();
    checkOutcomeGuards();
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
            await cdp.call('Page.enable'); await cdp.call('Page.setLifecycleEventsEnabled', {enabled: true});
            await cdp.call('Runtime.enable'); await cdp.call('Network.enable'); await cdp.call('Accessibility.enable');
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
        for (const [index, cdp] of pages.entries()) {
            if (cdp.socket.readyState === WebSocket.OPEN) cdp.finalIdentity = await cdp.documentIdentity();
            fs.writeFileSync(path.join(artifacts, 'page-' + index + '-diagnostics.json'), JSON.stringify({finalIdentity: cdp.finalIdentity,
                requests: [...cdp.requests.values()], timeline: cdp.timeline, eventsAtExit: cdp.events}, null, 2));
        }
        for (const cdp of pages) cdp.close();
        if (chrome && chrome.exitCode === null) { chrome.kill('SIGTERM'); await new Promise(resolve => chrome.once('exit', resolve)); }
        await new Promise(resolve => server.close(resolve));
        report.resourcesReleased = true;
        report.failureEvents = pages.flatMap(cdp => bootstrapFailures(cdp.events, cdp.precedingFetches));
        fs.writeFileSync(path.join(artifacts, 'report.json'), JSON.stringify(report, null, 2));
        console.log('FIXTURE evidence: ' + artifacts);
    }
    console.log('PASS: production root/media/person/search fresh documents + reload, Back/Forward, JS/2 Wasm/font resources; no account writes.');
})().catch(error => { console.error(error); process.exitCode = 1; });
