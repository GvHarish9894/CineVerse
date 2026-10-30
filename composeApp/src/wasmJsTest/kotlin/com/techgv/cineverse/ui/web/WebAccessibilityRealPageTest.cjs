// Node 22+ real-page runner for :composeApp:webAccessibilityRealPageTest.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const {spawn} = require('node:child_process');

assert.ok(Number(process.versions.node.split('.')[0]) >= 22, 'webAccessibilityRealPageTest requires Node 22+');
const dist = path.resolve(process.argv[2] || path.join(__dirname, '../../../../../../../../build/dist/wasmJs/accessibilityHarnessDevelopmentExecutable'));
const artifacts = fs.mkdtempSync(path.join(os.tmpdir(), 'cineverse-accessibility-'));
const profile = path.join(artifacts, 'chrome-profile');
const mime = {'.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.mjs': 'application/javascript', '.wasm': 'application/wasm', '.ttf': 'font/ttf', '.json': 'application/json'};
const fonts = ['bricolage_grotesque_medium.ttf', 'bricolage_grotesque_bold.ttf', 'bricolage_grotesque_extrabold.ttf', 'inter_regular.ttf', 'inter_medium.ttf', 'inter_semibold.ttf', 'inter_bold.ttf'];
const scripts = ['/CineVerse-composeApp-accessibilityHarness.mjs', '/skiko.mjs'];
const cases = ['modal-owned-replacement', 'header-signin-tab', 'narrow-logo-entry', 'rating-slider-submit', 'rating-disposed-replacement', 'trailer-forward-guards'];
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const deepFindSource = `(selector) => {
    function find(root) {
        const direct = root.querySelector(selector); if (direct) return direct;
        for (const node of root.querySelectorAll('*')) if (node.shadowRoot) { const found = find(node.shadowRoot); if (found) return found; }
        return null;
    }
    return find(document);
}`;
const activeSource = `() => { let active=document.activeElement; while(active?.shadowRoot?.activeElement) active=active.shadowRoot.activeElement; return active; }`;

function serve(request, response) {
    if (request.method !== 'GET') { response.writeHead(405); return response.end(); }
    if (new URL(request.url, 'http://localhost').pathname === '/favicon.ico') { response.writeHead(204); return response.end(); }
    const url = new URL(request.url, 'http://localhost');
    const relative = decodeURIComponent(url.pathname).replace(/^\/+/, '');
    let file = path.resolve(dist, relative || 'index.html');
    if (file !== dist && !file.startsWith(dist + path.sep)) { response.writeHead(404); return response.end(); }
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
        if ((request.headers.accept || '').includes('text/html')) file = path.join(dist, 'index.html');
        else { response.writeHead(404); return response.end(); }
    }
    response.writeHead(200, {'Content-Type': mime[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store'});
    fs.createReadStream(file).pipe(response);
}

async function until(check, description, milliseconds = 1000) {
    const deadline = Date.now() + milliseconds;
    do { const result = await check(); if (result) return result; await sleep(25); } while (Date.now() < deadline);
    throw Error('Timed out: ' + description);
}

class Cdp {
    constructor(socket) {
        this.socket = socket;
        this.serial = 0;
        this.pending = new Map();
        this.events = [];
        this.responses = new Map();
        this.finished = new Set();
        socket.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            if (message.id) {
                const pending = this.pending.get(message.id);
                this.pending.delete(message.id);
                if (message.error) pending.reject(Error(JSON.stringify(message.error))); else pending.resolve(message.result);
                return;
            }
            this.events.push(message);
            if (message.method === 'Network.responseReceived') this.responses.set(message.params.requestId, message.params.response);
            if (message.method === 'Network.loadingFinished') this.finished.add(message.params.requestId);
        });
    }
    static async connect(url) {
        const socket = new WebSocket(url);
        await new Promise((resolve, reject) => {
            socket.addEventListener('open', resolve, {once: true});
            socket.addEventListener('error', reject, {once: true});
        });
        return new Cdp(socket);
    }
    call(method, params = {}) {
        const id = ++this.serial;
        return new Promise((resolve, reject) => {
            this.pending.set(id, {resolve, reject});
            this.socket.send(JSON.stringify({id, method, params}));
        });
    }
    close() { if (this.socket.readyState < WebSocket.CLOSING) this.socket.close(); }
}

function failures(page) {
    return page.events.filter(event =>
        event.method === 'Runtime.exceptionThrown' ||
        (event.method === 'Runtime.consoleAPICalled' && event.params.type === 'error') ||
        (event.method === 'Log.entryAdded' && event.params.entry.level === 'error') ||
        event.method === 'Network.loadingFailed' ||
        (event.method === 'Network.responseReceived' && event.params.response.status >= 400));
}

async function evaluate(page, expression, returnByValue = true) {
    const result = await page.call('Runtime.evaluate', {expression, awaitPromise: true, returnByValue});
    if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return returnByValue ? result.result.value : result.result;
}

async function bridge(page) {
    const value = await evaluate(page, `globalThis.__cvAccessibilityHarness ? globalThis.__cvAccessibilityHarness.snapshot() : null`);
    return value && JSON.parse(value);
}

async function dispatch(page, command, argument = '') {
    return JSON.parse(await evaluate(page, `globalThis.__cvAccessibilityHarness.dispatch(${JSON.stringify(command)},${JSON.stringify(argument)})`));
}

async function objectFor(page, selector) {
    const result = await evaluate(page, `(${deepFindSource})(${JSON.stringify(selector)})`, false);
    return result.subtype === 'null' ? null : result;
}

async function activeObject(page) {
    const result = await evaluate(page, `(${activeSource})()`, false);
    return result.subtype === 'null' ? null : result;
}

// Compose keeps keyboard focus inside the canvas; AT focus does not follow it, so Enter proves which control holds it.
async function canvasActive(page) { return await evaluate(page, `(${activeSource})()?.tagName`) === 'CANVAS'; }

async function domFocus(page, selector) {
    const object = await objectFor(page, selector);
    assert.ok(object?.objectId, 'Missing focus target ' + selector);
    await page.call('DOM.focus', {objectId: object.objectId});
    return object;
}

async function callOn(page, objectId, functionDeclaration, arguments = []) {
    const result = await page.call('Runtime.callFunctionOn', {objectId, functionDeclaration, arguments, returnByValue: true});
    if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
}

async function click(page, selector) {
    const object = await objectFor(page, selector);
    assert.ok(object?.objectId, 'Missing click target ' + selector);
    await clickBox(page, {objectId: object.objectId});
}

async function clickBox(page, node) {
    const box = await page.call('DOM.getBoxModel', node);
    const quad = box.model.content;
    const x = (quad[0] + quad[2] + quad[4] + quad[6]) / 4;
    const y = (quad[1] + quad[3] + quad[5] + quad[7]) / 4;
    await page.call('Input.dispatchMouseEvent', {type: 'mousePressed', x, y, button: 'left', clickCount: 1});
    await page.call('Input.dispatchMouseEvent', {type: 'mouseReleased', x, y, button: 'left', clickCount: 1});
}

async function key(page, key, shift = false) {
    const code = key === 'Tab' ? 'Tab' : key === 'Enter' ? 'Enter' : key;
    const windowsVirtualKeyCode = key === 'Tab' ? 9 : key === 'Enter' ? 13 : 0;
    const base = {key, code, modifiers: shift ? 8 : 0, windowsVirtualKeyCode, nativeVirtualKeyCode: windowsVirtualKeyCode};
    await page.call('Input.dispatchKeyEvent', {type: 'keyDown', ...base});
    await page.call('Input.dispatchKeyEvent', {type: 'keyUp', ...base});
}

function axProperty(node, name) { return node?.properties?.find(property => property.name === name)?.value?.value; }
function axNodes(tree) { return tree.nodes.filter(node => !node.ignored); }
function axNamed(tree, role, name) { return axNodes(tree).filter(node => node.role?.value === role && node.name?.value === name); }
async function axTree(page) { return page.call('Accessibility.getFullAXTree'); }
async function focusedAx(page, role, name) {
    const nodes = axNamed(await axTree(page), role, name);
    return nodes.length === 1 && axProperty(nodes[0], 'focused') === true;
}

async function borderRect(page, node) {
    const quad = (await page.call('DOM.getBoxModel', node)).model.border;
    const xs = [quad[0], quad[2], quad[4], quad[6]]; const ys = [quad[1], quad[3], quad[5], quad[7]];
    return {left: Math.min(...xs), right: Math.max(...xs), top: Math.min(...ys), bottom: Math.max(...ys)};
}
// Whatever element holds focus (Compose's IME input or the mirror), AT must land on one textbox named like the field, over the field.
async function focusedSearchField(page, name) {
    const focused = axNodes(await axTree(page)).filter(node => ['textbox', 'searchbox'].includes(node.role?.value) && axProperty(node, 'focused') === true);
    if (focused.length !== 1 || focused[0].name?.value !== name || !focused[0].backendDOMNodeId) return false;
    const field = await objectFor(page, '#header-search-input');
    if (!field) return false;
    const [a, b] = await Promise.all([borderRect(page, {backendNodeId: focused[0].backendDOMNodeId}), borderRect(page, {objectId: field.objectId})]).catch(() => []);
    return !!a && a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom;
}

async function selectorState(page, selector) {
    return evaluate(page, `(() => { const n=(${deepFindSource})(${JSON.stringify(selector)}); return n && {tag:n.tagName,id:n.id,role:n.getAttribute('role'),label:n.getAttribute('aria-label'),modal:n.getAttribute('aria-modal'),owns:n.getAttribute('aria-owns'),min:n.getAttribute('aria-valuemin'),max:n.getAttribute('aria-valuemax'),now:n.getAttribute('aria-valuenow'),text:n.getAttribute('aria-valuetext'),tabindex:n.getAttribute('tabindex')}; })()`);
}

async function assertResources(page, origin) {
    await until(() => {
        assert.deepEqual(failures(page), []);
        const responses = [...page.responses.entries()].filter(([id]) => page.finished.has(id)).map(([, response]) => response);
        const fontResponses = responses.filter(response => response.url.includes('/composeResources/') && response.url.endsWith('.ttf'));
        const wasmResponses = responses.filter(response => new URL(response.url).pathname.endsWith('.wasm'));
        const scriptsLoaded = scripts.every(script => responses.some(response => response.url === origin + script));
        return scriptsLoaded && wasmResponses.length >= 2 && fonts.every(font => fontResponses.some(response => response.url.endsWith('/' + font)));
    }, 'harness/Skiko modules, Kotlin/Skiko Wasm and seven fonts', 15000);
    const responses = [...page.responses.entries()];
    for (const name of scripts) {
        const script = responses.find(([, response]) => response.url === origin + name);
        assert.ok(script && script[1].status === 200 && script[1].mimeType.includes('javascript') && page.finished.has(script[0]), 'Missing module ' + name);
    }
    const wasms = responses.filter(([, response]) => new URL(response.url).pathname.endsWith('.wasm'));
    assert.ok(wasms.length >= 2, 'Expected Kotlin and Skiko Wasm');
    assert.ok(wasms.every(([id, response]) => response.status === 200 && response.mimeType === 'application/wasm' && page.finished.has(id)));
    for (const font of fonts) {
        const response = responses.find(([, item]) => item.url.includes('/composeResources/') && item.url.endsWith('/' + font));
        assert.ok(response && response[1].status === 200 && response[1].mimeType !== 'text/html' && page.finished.has(response[0]), 'Missing real font ' + font);
    }
}

async function ready(page, origin, firstNode) {
    await until(async () => {
        assert.deepEqual(failures(page), []);
        const state = await bridge(page);
        return state?.composed && await firstNode();
    }, 'harness composition and first case node', 15000);
    const state = await bridge(page);
    assert.equal(state.mountCount, 1);
    await assertResources(page, origin);
    assert.deepEqual(failures(page), []);
}

async function modalCase(page, origin) {
    await ready(page, origin, async () => (await selectorState(page, '[role="dialog"]'))?.label === 'Modal 0');
    let modal = await selectorState(page, '[role="dialog"]');
    assert.equal(modal.modal, 'true'); assert.equal(modal.label, 'Modal 0');
    let tree = await axTree(page);
    assert.equal(axNamed(tree, 'button', 'Close').length, 1);
    assert.equal(axNamed(tree, 'button', 'Real dialog action').length, 1);
    assert.equal(await evaluate(page, `(() => { let n=(${deepFindSource})('#modal-background'); for(;n;n=n.parentElement) if(n.hasAttribute('inert')||n.getAttribute('aria-hidden')==='true') return true; return false; })()`), true);
    await evaluate(page, `(${deepFindSource})('[role="dialog"]').removeAttribute('role')`);
    await until(async () => (await selectorState(page, '[role="dialog"]'))?.label === 'Modal 0', 'modal role repair');
    await dispatch(page, 'set-modal-generation', '1');
    await until(async () => (await selectorState(page, '[role="dialog"]'))?.label === 'Modal 1', 'modal replacement');
    assert.equal(await evaluate(page, `(() => { const all=[]; function scan(r){for(const n of r.querySelectorAll('*')){if(n.getAttribute('aria-label')==='Modal 0')all.push(n);if(n.shadowRoot)scan(n.shadowRoot)}}scan(document);return all.length;})()`), 0);
    assert.equal(await evaluate(page, `(() => { let n=(${deepFindSource})('#modal-background'); for(;n;n=n.parentElement) if(n.hasAttribute('inert')||n.getAttribute('aria-hidden')==='true') return true; return false; })()`), true);
    await click(page, '[role="dialog"] [aria-label="Close"]');
    await until(async () => !(await objectFor(page, '[role="dialog"]')), 'modal close');
    assert.equal(await evaluate(page, `(() => { let n=(${deepFindSource})('#modal-background'); for(;n;n=n.parentElement) if(n.hasAttribute('inert')||n.getAttribute('aria-hidden')==='true') return false; return true; })()`), true);
    await click(page, '#modal-background');
    await until(async () => (await bridge(page)).backgroundActions === 1, 'background action');
}

async function headerCase(page, origin) {
    await ready(page, origin, async () => !!(await objectFor(page, '#header-search-input')));
    // A real user seeds Compose focus by clicking the field; DOM.focus on the hidden a11y copy does not move it.
    const searchName = 'Search movies, TV and people';
    const searchNodes = axNamed(await axTree(page), 'textbox', searchName);
    assert.equal(searchNodes.length, 1); assert.ok(searchNodes[0].backendDOMNodeId);
    await clickBox(page, {backendNodeId: searchNodes[0].backendDOMNodeId});
    await until(() => focusedSearchField(page, searchName), 'named search AX focus after click');
    await key(page, 'Tab');
    await until(() => canvasActive(page), 'canvas activeElement after Tab');
    await key(page, 'Enter');
    await until(async () => (await bridge(page)).signIns === 1, 'sign-in callback');
    await key(page, 'Tab', true);
    await until(() => focusedSearchField(page, searchName), 'named search AX focus');
    const signIns = axNamed(await axTree(page), 'button', 'Sign in');
    assert.equal(signIns.length, 1); assert.notEqual(axProperty(signIns[0], 'disabled'), true); assert.ok(signIns[0].backendDOMNodeId);
}

async function narrowCase(page, origin) {
    await ready(page, origin, async () => axNamed(await axTree(page), 'button', 'CineVerse home').length === 1);
    const logos = axNamed(await axTree(page), 'button', 'CineVerse home');
    assert.equal(logos.length, 1); assert.notEqual(axProperty(logos[0], 'disabled'), true); assert.ok(logos[0].backendDOMNodeId);
    await evaluate(page, `(${activeSource})()?.blur?.()`);
    assert.equal(await evaluate(page, 'document.activeElement === document.body'), true);
    await key(page, 'Tab');
    await until(() => canvasActive(page), 'canvas activeElement after Tab');
    await key(page, 'Enter');
    await until(async () => (await bridge(page)).homeNavigations === 1, 'home callback');
}

async function ratingCase(page, origin) {
    await until(async () => (await bridge(page))?.composed, 'rating harness composition', 15000);
    await dispatch(page, 'open-rate', 'movie');
    await ready(page, origin, async () => (await selectorState(page, '#web-rate-stars'))?.role === 'slider');
    let slider = await selectorState(page, '#web-rate-stars');
    assert.deepEqual({min: slider.min, max: slider.max, now: slider.now, text: slider.text, tabindex: slider.tabindex}, {min: '0', max: '10', now: '0', text: 'Not chosen', tabindex: '-1'});
    await evaluate(page, `(() => { const n=(${deepFindSource})('#web-rate-stars'); for(let i=0;i<2;i++)n.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true,cancelable:true})); })()`);
    await until(async () => (await selectorState(page, '#web-rate-stars'))?.now === '1', 'rating value 1');
    // Compose web debounces its a11y DOM sync (100 ms), so visible text lands after the bridge's synchronous valuenow.
    await until(async () => {
        const names = axNodes(await axTree(page)).map(node => node.name?.value);
        return names.includes('1.0 / 10') && names.includes('Poor');
    }, 'rating AX text 1.0 / 10 and Poor');
    await evaluate(page, `(${deepFindSource})('#web-rate-stars').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}))`);
    await until(async () => !(await objectFor(page, '#web-rate-stars')), 'rating submit close');
    const state = await until(async () => {
        const snapshot = await bridge(page);
        return snapshot.writes.length === 1 && snapshot.outcomes.length === 1 ? snapshot : false;
    }, 'rating write outcome');
    assert.deepEqual(state.writes, ['rate-account-a:rating/movie:7=1.0']);
    assert.deepEqual(state.outcomes, ['Rate(1.0)']);
}

async function disposedRatingCase(page, origin) {
    await until(async () => (await bridge(page))?.composed, 'disposed-rating harness composition', 15000);
    await dispatch(page, 'open-rate', 'movie');
    await ready(page, origin, async () => (await selectorState(page, '#web-rate-stars'))?.role === 'slider');
    const old = await objectFor(page, '#web-rate-stars');
    const oldNode = await page.call('DOM.describeNode', {objectId: old.objectId});
    await dispatch(page, 'close-rate');
    await until(async () => !(await objectFor(page, '#web-rate-stars')), 'rating prompt close');
    assert.deepEqual(await callOn(page, old.objectId, `function(){return ['aria-valuenow','aria-valuetext'].map(k=>this.getAttribute(k))}`), [null, null]);
    await dispatch(page, 'open-rate', 'other');
    await until(async () => (await selectorState(page, '#web-rate-stars'))?.role === 'slider', 'replacement rating slider');
    const replacement = await objectFor(page, '#web-rate-stars');
    const replacementNode = await page.call('DOM.describeNode', {objectId: replacement.objectId});
    assert.notEqual(oldNode.node.backendNodeId, replacementNode.node.backendNodeId);
    await callOn(page, old.objectId, `function(){this.dispatchEvent(new KeyboardEvent('keydown',{key:'End',bubbles:true,cancelable:true}))}`);
    await sleep(100);
    const replacementState = await selectorState(page, '#web-rate-stars');
    assert.equal(replacementState.now, '0'); assert.equal(replacementState.text, 'Not chosen');
    const names = axNodes(await axTree(page)).map(node => node.name?.value);
    assert.ok(names.includes('– / 10')); assert.ok(names.includes('Choose a rating'));
    const state = await bridge(page);
    assert.deepEqual(state.writes, []); assert.deepEqual(state.outcomes, []);
}

async function trailerCase(page, origin) {
    await ready(page, origin, async () => !!(await objectFor(page, 'iframe[data-cv-trailer]')) && (await selectorState(page, '[role="dialog"]'))?.label === 'Official Trailer');
    const frame = await selectorState(page, 'iframe[data-cv-trailer]');
    const before = await selectorState(page, '[data-cv-trailer-before]');
    const after = await selectorState(page, '[data-cv-trailer-after]');
    assert.ok(frame && before && after);
    assert.match(await evaluate(page, `(${deepFindSource})('iframe[data-cv-trailer]').getAttribute('title')`), /Official Trailer/);
    const modal = await selectorState(page, '[role="dialog"]');
    const owned = modal.owns.split(/\s+/);
    assert.ok(owned.includes(frame.id) && owned.includes(before.id) && owned.includes(after.id));
    assert.equal(await evaluate(page, `(${deepFindSource})('[role="dialog"]').getRootNode()===(${deepFindSource})('iframe[data-cv-trailer]').getRootNode()`), true);
    assert.equal(await evaluate(page, `(() => { for(const s of ['[data-cv-trailer-before]','[data-cv-trailer-after]']){let n=(${deepFindSource})(s);for(;n;n=n.parentElement)if(n.getAttribute('aria-hidden')==='true')return false}return true})()`), true);
    await until(() => focusedAx(page, 'button', 'Close'), 'initial Close focus');
    await key(page, 'Tab');
    await until(async () => await callOn(page, (await activeObject(page)).objectId, `function(){return this.matches('iframe[data-cv-trailer]')}`), 'iframe focus');
    await domFocus(page, '[data-cv-trailer-after]');
    await until(() => focusedAx(page, 'button', 'Teaser One · Teaser'), 'teaser focus from after guard');
    await key(page, 'Tab');
    await until(() => focusedAx(page, 'button', 'Close'), 'Close after teaser');
    await key(page, 'Tab', true);
    await until(() => focusedAx(page, 'button', 'Teaser One · Teaser'), 'reverse teaser focus');
    await key(page, 'Tab', true);
    await until(async () => await callOn(page, (await activeObject(page)).objectId, `function(){return this.matches('iframe[data-cv-trailer]')}`), 'reverse iframe focus');
    await domFocus(page, '[data-cv-trailer-before]');
    await until(() => focusedAx(page, 'button', 'Close'), 'Close from before guard');
    await click(page, '[role="dialog"] [aria-label="Close"]');
    await until(async () => !(await objectFor(page, '[data-cv-trailer], [data-cv-trailer-before], [data-cv-trailer-after]')) && !(await objectFor(page, '[role="dialog"]')), 'trailer disposal');
    assert.equal((await bridge(page)).trailerOpen, false);
}

const implementations = {
    'modal-owned-replacement': modalCase,
    'header-signin-tab': headerCase,
    'narrow-logo-entry': narrowCase,
    'rating-slider-submit': ratingCase,
    'rating-disposed-replacement': disposedRatingCase,
    'trailer-forward-guards': trailerCase,
};

async function diagnostics(page, name, error) {
    const directory = path.join(artifacts, name); fs.mkdirSync(directory, {recursive: true});
    const write = (file, value) => fs.writeFileSync(path.join(directory, file), typeof value === 'string' ? value : JSON.stringify(value, null, 2));
    write('error.txt', error.stack || String(error));
    try { write('ax.json', await axTree(page)); } catch (failure) { write('ax-error.txt', String(failure)); }
    try { write('dom-snapshot.json', await page.call('DOMSnapshot.captureSnapshot', {computedStyles: []})); } catch (failure) { write('dom-error.txt', String(failure)); }
    try { write('bridge.json', await bridge(page)); } catch (failure) { write('bridge-error.txt', String(failure)); }
    write('network-and-runtime.json', page.events);
    try {
        const shot = await page.call('Page.captureScreenshot', {format: 'png'});
        fs.writeFileSync(path.join(directory, 'page.png'), Buffer.from(shot.data, 'base64'));
    } catch (failure) { write('screenshot-error.txt', String(failure)); }
}

async function pageSocket(debugging, targetId) {
    return until(async () => {
        const targets = await (await fetch(debugging + '/json/list')).json();
        return targets.find(target => target.id === targetId)?.webSocketDebuggerUrl || false;
    }, 'target websocket', 15000);
}

(async () => {
    assert.ok(fs.existsSync(path.join(dist, 'index.html')), 'Build webAccessibilityHarnessDistribution first: ' + dist);
    assert.ok(process.env.CHROME_BIN, 'Set CHROME_BIN to the leased Chrome executable');
    const server = http.createServer(serve);
    let chrome; let browser;
    const remainingTargets = new Set(); const remainingContexts = new Set();
    try {
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        const origin = 'http://127.0.0.1:' + server.address().port;
        const stderr = fs.openSync(path.join(artifacts, 'chrome.log'), 'w');
        chrome = spawn(process.env.CHROME_BIN, ['--headless', '--remote-debugging-port=0', '--user-data-dir=' + profile, '--no-first-run', '--no-default-browser-check', '--disable-background-networking', '--force-renderer-accessibility', 'about:blank'], {stdio: ['ignore', 'ignore', stderr]});
        fs.closeSync(stderr);
        const activePort = path.join(profile, 'DevToolsActivePort');
        await until(() => { if (chrome.exitCode !== null) throw Error('Owned Chrome exited'); return fs.existsSync(activePort); }, 'Chrome startup', 15000);
        const [port, browserPath] = fs.readFileSync(activePort, 'utf8').trim().split('\n');
        const debugging = 'http://127.0.0.1:' + port;
        browser = await Cdp.connect('ws://127.0.0.1:' + port + browserPath);
        for (const name of cases) {
            const {browserContextId} = await browser.call('Target.createBrowserContext'); remainingContexts.add(browserContextId);
            const {targetId} = await browser.call('Target.createTarget', {url: 'about:blank', browserContextId}); remainingTargets.add(targetId);
            const page = await Cdp.connect(await pageSocket(debugging, targetId));
            try {
                for (const domain of ['Page', 'Runtime', 'Network', 'DOM', 'Accessibility', 'Log']) await page.call(domain + '.enable');
                await page.call('Network.setCacheDisabled', {cacheDisabled: true});
                const narrow = name === 'narrow-logo-entry';
                await page.call('Emulation.setDeviceMetricsOverride', {width: narrow ? 390 : 1440, height: narrow ? 900 : 1000, deviceScaleFactor: 1, mobile: false});
                await page.call('Page.navigate', {url: origin + '/?case=' + encodeURIComponent(name)});
                await implementations[name](page, origin);
                assert.deepEqual(failures(page), []);
                console.log('COMPLETE: ' + name);
            } catch (error) {
                await diagnostics(page, name, error);
                throw error;
            } finally {
                page.close();
                await browser.call('Target.closeTarget', {targetId}).catch(() => {}); remainingTargets.delete(targetId);
                await browser.call('Target.disposeBrowserContext', {browserContextId}).catch(() => {}); remainingContexts.delete(browserContextId);
            }
        }
        console.log('COMPLETE: six isolated accessibility real-page cases; evidence directory ' + artifacts);
    } finally {
        if (browser) {
            for (const targetId of remainingTargets) await browser.call('Target.closeTarget', {targetId}).catch(() => {});
            for (const browserContextId of remainingContexts) await browser.call('Target.disposeBrowserContext', {browserContextId}).catch(() => {});
            browser.close();
        }
        if (chrome && chrome.exitCode === null && chrome.signalCode === null) {
            const exited = () => chrome.exitCode !== null || chrome.signalCode !== null;
            chrome.kill('SIGTERM');
            await until(exited, 'Chrome SIGTERM exit', 5000).catch(() => { chrome.kill('SIGKILL'); return until(exited, 'Chrome SIGKILL exit', 5000); });
        }
        if (server.listening) await new Promise(resolve => server.close(resolve));
        fs.rmSync(profile, {recursive: true, force: true});
    }
})().catch(error => { console.error(error); console.error('Accessibility evidence: ' + artifacts); process.exitCode = 1; });
