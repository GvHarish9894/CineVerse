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
const requiredFonts = ['bricolage_grotesque_medium.ttf', 'bricolage_grotesque_bold.ttf', 'bricolage_grotesque_extrabold.ttf', 'inter_regular.ttf', 'inter_medium.ttf', 'inter_semibold.ttf', 'inter_bold.ttf'];

// Observation only: native promises/readers/results and all original arguments/receivers are returned unchanged.
function installReadTrace() {
    const streams = new WeakMap(), readers = new WeakMap();
    let serial = 0, sequence = 0;
    const emit = (record, phase, extra = {}) => {
        try { globalThis.__w1ReadTrace(JSON.stringify({...record, phase, sequence: ++sequence,
            timeOrigin: performance.timeOrigin, at: performance.now(), documentUrl: location.href, ...extra})); } catch (_) {}
    };
    const fetch = globalThis.fetch;
    globalThis.fetch = function (...args) {
        let record;
        try {
            const url = typeof args[0] === 'string' ? new URL(args[0], document.baseURI) : null;
            const method = args[1] ? Object.getOwnPropertyDescriptor(args[1], 'method')?.value || 'GET' : 'GET';
            if (url?.origin === location.origin && (url.pathname === '/api/session' || url.pathname.startsWith('/api/tmdb/'))) {
                record = {id: ++serial, url: url.href, method};
                emit(record, 'fetch');
                const signal = args[1] && Object.getOwnPropertyDescriptor(args[1], 'signal')?.value;
                if (signal?.aborted) emit(record, 'alreadyAborted');
                signal?.addEventListener('abort', () => emit(record, 'abort'), {once: true});
            }
        } catch (_) {}
        const promise = Reflect.apply(fetch, this, args);
        if (record) promise.then(response => {
            try { if (response.body) streams.set(response.body, record); emit(record, 'response', {status: response.status}); } catch (_) {}
        }, () => emit(record, 'fetchRejected'));
        return promise;
    };
    const getReader = ReadableStream.prototype.getReader;
    ReadableStream.prototype.getReader = function (...args) {
        const reader = Reflect.apply(getReader, this, args);
        const record = streams.get(this);
        if (record) readers.set(reader, record);
        return reader;
    };
    const read = ReadableStreamDefaultReader.prototype.read;
    ReadableStreamDefaultReader.prototype.read = function (...args) {
        const promise = Reflect.apply(read, this, args);
        const record = readers.get(this);
        if (record) promise.then(result => { if (result.done === true) emit(record, 'end'); }, () => emit(record, 'readRejected'));
        return promise;
    };
}

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
        this.contexts = new Map(); this.readTraces = [];
        socket.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            if (message.id) {
                const pending = this.pending.get(message.id); this.pending.delete(message.id);
                if (message.error) pending.reject(Error(JSON.stringify(message.error))); else pending.resolve(message.result);
            } else {
                const params = message.params;
                const receivedAt = Date.now();
                if (message.method === 'Runtime.executionContextCreated') {
                    const context = params.context;
                    this.contexts.set(context.id, {frameId: context.auxData?.frameId, isDefault: context.auxData?.isDefault});
                    return;
                }
                if (message.method === 'Runtime.bindingCalled' && params.name === '__w1ReadTrace') {
                    const value = JSON.parse(params.payload);
                    const context = this.contexts.get(params.executionContextId);
                    this.readTraces.push({...value, frameId: context?.frameId, isDefault: context?.isDefault,
                        executionContextId: params.executionContextId, receivedAt, boundary: this.boundary});
                    return;
                }
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
    console.log('PASS: same-document AX Ready/Checking/Failed guards reject stale/ambiguous outcomes.');
}

function consumedApiAbort(event, cdp, expected, origin) {
    const request = cdp.requests.get(event.params?.requestId);
    if (event.method !== 'Network.loadingFailed' || !event.params.canceled || event.params.type !== 'Fetch' ||
        request?.method !== 'GET' || request.frameId !== expected?.frameId || request.loaderId !== expected?.loaderId) return null;
    const url = new URL(request.url);
    if (url.origin !== origin || !(url.pathname === '/api/session' || url.pathname.startsWith('/api/tmdb/'))) return null;
    const response = request.lifecycle.find(item => item.method === 'Network.responseReceived')?.response;
    if (!response || response.status < 200 || response.status >= 300 || response.mimeType !== 'application/json') return null;
    const starts = cdp.readTraces.filter(trace => trace.phase === 'fetch' && trace.isDefault && trace.url === request.url && trace.method === 'GET' &&
        trace.frameId === expected.frameId && trace.documentUrl === expected.url && trace.timeOrigin === expected.timeOrigin &&
        Math.abs(trace.timeOrigin + trace.at - request.wallTime * 1000) < 50);
    if (starts.length !== 1) return null;
    const traces = cdp.readTraces.filter(trace => trace.id === starts[0].id && trace.executionContextId === starts[0].executionContextId);
    const responseTrace = traces.find(trace => trace.phase === 'response');
    const end = traces.find(trace => trace.phase === 'end');
    const abort = traces.find(trace => trace.phase === 'abort');
    if (!responseTrace || responseTrace.status !== response.status || !end || !abort || starts[0].sequence >= responseTrace.sequence ||
        responseTrace.sequence >= end.sequence || end.sequence >= abort.sequence || end.at > abort.at ||
        traces.some(trace => ['alreadyAborted', 'readRejected', 'fetchRejected'].includes(trace.phase))) return null;
    return {requestId: request.requestId, url: request.url, frameId: request.frameId, loaderId: request.loaderId,
        documentUrl: expected.url, timeOrigin: expected.timeOrigin, end, abort, catalog: url.pathname.startsWith('/api/tmdb/')};
}

function checkConsumptionProof() {
    const expected = {frameId: 'frame', loaderId: 'loader', url: 'http://fixture/', timeOrigin: 1000};
    const event = {method: 'Network.loadingFailed', params: {requestId: 'read', canceled: true, type: 'Fetch'}};
    const request = {requestId: 'read', method: 'GET', url: 'http://fixture/api/session', frameId: 'frame', loaderId: 'loader', wallTime: 1,
        lifecycle: [{method: 'Network.responseReceived', response: {status: 200, mimeType: 'application/json'}}]};
    const trace = {id: 1, executionContextId: 1, url: request.url, method: 'GET', isDefault: true, frameId: 'frame', documentUrl: expected.url, timeOrigin: 1000};
    const traces = [{...trace, phase: 'fetch', sequence: 1, at: 0}, {...trace, phase: 'response', status: 200, sequence: 2, at: 0.5}, {...trace, phase: 'end', sequence: 3, at: 1}, {...trace, phase: 'abort', sequence: 4, at: 2}];
    const prove = (candidate = request, events = traces, failure = event, identity = expected) => consumedApiAbort(failure, {requests: new Map([['read', candidate]]), readTraces: events}, identity, 'http://fixture');
    assert.ok(prove());
    for (const candidate of [{...request, method: 'POST'}, {...request, url: 'http://other/api/session'}, {...request, url: 'http://fixture/api/auth/start'},
        {...request, loaderId: 'old'}, {...request, frameId: 'old'}, {...request, lifecycle: []},
        ...[199, 300, 500].map(status => ({...request, lifecycle: [{method: 'Network.responseReceived', response: {status, mimeType: 'application/json'}}]})),
        {...request, lifecycle: [{method: 'Network.responseReceived', response: {status: 200, mimeType: 'text/html'}}]}]) assert.equal(prove(candidate), null);
    for (const events of [traces.filter(item => item.phase !== 'end'), traces.filter(item => item.phase !== 'abort'),
        traces.filter(item => item.phase !== 'response'), traces.map(item => item.phase === 'response' ? {...item, status: 500} : item),
        traces.map(item => item.phase === 'end' ? {...item, sequence: 5} : item),
        traces.map(item => item.phase === 'end' ? {...item, at: 3} : item),
        traces.map(item => ({...item, timeOrigin: 0})), traces.map(item => ({...item, documentUrl: 'http://fixture/old'})),
        traces.map(item => ({...item, isDefault: false})), [...traces, {...traces[0], id: 2}],
        ...['alreadyAborted', 'readRejected', 'fetchRejected'].map(phase => [...traces, {...trace, phase}])]) assert.equal(prove(request, events), null);
    assert.equal(prove(request, traces, {...event, params: {...event.params, canceled: false}}), null);
    assert.equal(prove(request, traces, {...event, params: {...event.params, type: 'Script'}}), null);
    assert.equal(prove(request, traces, {method: 'Runtime.exceptionThrown', params: {}}), null);
    const proof = prove();
    const document = {...expected, backendNodeId: 7};
    const sample = {state: 'Ready', before: document, after: document, axFrameId: 'frame', axUrl: expected.url, axBackendNodeId: 7, homeEmpty: true};
    assert.ok(validApplicationOutcome(proof, sample));
    assert.ok(validApplicationOutcome({...proof, catalog: true}, sample));
    for (const outcome of [...['Checking', 'Failed', 'UnknownOrConflicting', 'RejectedIdentity'].map(state => ({...sample, state})),
        {...sample, before: {...document, loaderId: 'old'}}, {...sample, after: {...document, timeOrigin: 0}},
        {...sample, axFrameId: 'old'}, {...sample, axUrl: 'http://fixture/old'}, {...sample, axBackendNodeId: 8}, {...sample, axBackendNodeId: undefined},
        {...sample, after: {...document, backendNodeId: 8}}]) assert.equal(validApplicationOutcome(proof, outcome), false);
    assert.equal(validApplicationOutcome({...proof, catalog: true}, {...sample, homeEmpty: false}), false);
    assert.equal(validApplicationOutcome(null, sample), false);
    assert.equal(validApplicationOutcome(proof, null), false);
    console.log('PASS: completed-read policy rejects unknown/early/ambiguous/foreign/method/status/MIME/identity/state/empty-data failures.');
}

function parsedHomeEmpty(tree) {
    const nodes = tree.nodes.filter(node => !node.ignored);
    return nodes.some(node => node.name?.value === 'No titles yet') &&
        nodes.some(node => node.name?.value === 'There are no results for this section.') &&
        nodes.some(node => node.role?.value === 'button' && node.name?.value === 'Refresh Trending');
}

function validApplicationOutcome(proof, sample) {
    if (!proof || !sample || sample.state !== 'Ready') return false;
    const expected = {frameId: proof.frameId, loaderId: proof.loaderId, url: proof.documentUrl, timeOrigin: proof.timeOrigin};
    return sameDocument(expected, sample.before) && sameDocument(expected, sample.after) && sample.axFrameId === expected.frameId &&
        sample.axUrl === expected.url && sample.axBackendNodeId !== undefined && sample.axBackendNodeId === sample.before.backendNodeId &&
        sample.axBackendNodeId === sample.after.backendNodeId && (!proof.catalog || sample.homeEmpty === true);
}

async function applicationSample(cdp, expected) {
    const before = await cdp.documentIdentity(true);
    const tree = await cdp.call('Accessibility.getFullAXTree');
    const after = await cdp.documentIdentity(true);
    const root = tree.nodes.find(node => !node.ignored && node.role?.value === 'RootWebArea');
    return {tree, sample: {receivedAt: Date.now(), before, after, state: authoritativeAxState(tree, expected, before, after),
        homeEmpty: parsedHomeEmpty(tree), axFrameId: root?.frameId, axBackendNodeId: root?.backendDOMNodeId,
        axUrl: root?.properties?.find(property => property.name === 'url')?.value?.value}};
}

function remainingFailures(cdp, origin, step, precedingFetches) {
    const expected = step.observations.at(-1);
    const outcome = step.completionProbe?.samples.at(-1);
    return bootstrapFailures(cdp.events, precedingFetches).filter(event => !validApplicationOutcome(consumedApiAbort(event, cdp, expected, origin), outcome));
}

async function observeCompletionOutcome(cdp, origin, step, precedingFetches) {
    const expected = step.observations.at(-1);
    const navigation = cdp.events.findLast(event => event.method === 'Page.frameNavigated' && event.params.frame.id === expected?.frameId && event.params.frame.loaderId === expected?.loaderId);
    const probe = {startedAt: Date.now(), expected, samples: [], outcome: 'NotEligible'};
    step.completionProbe = probe;
    try {
        if (!navigation || navigation.params.frame.url !== expected.url || sameDocument(expected, step.before) ||
            !bootstrapFailures(cdp.events, precedingFetches).every(event => consumedApiAbort(event, cdp, expected, origin))) return;
        const deadline = Date.now() + 5000;
        probe.outcome = 'Pending';
        do {
            const failures = bootstrapFailures(cdp.events, precedingFetches);
            if (failures.some(event => !consumedApiAbort(event, cdp, expected, origin))) { probe.outcome = 'UnrelatedStrictFailure'; return; }
            const {sample} = await applicationSample(cdp, expected);
            probe.samples.push(sample);
            const proofs = bootstrapFailures(cdp.events, precedingFetches).map(event => consumedApiAbort(event, cdp, expected, origin));
            if (proofs.some(proof => !proof)) { probe.outcome = 'UnrelatedStrictFailure'; return; }
            if (proofs.every(proof => validApplicationOutcome(proof, sample))) { probe.outcome = 'Ready'; probe.proofs = proofs; return; }
            if (['Failed', 'RejectedIdentity'].includes(sample.state)) { probe.outcome = sample.state; return; }
            await sleep(100);
        } while (Date.now() < deadline);
        probe.timedOut = true;
    } finally {
        probe.finishedAt = Date.now();
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
        if (strictFailures.length) {
            step.originalStrictFailures = strictFailures;
            await observeCompletionOutcome(cdp, origin, step, precedingFetches);
        }
        step.rejectedFailures = remainingFailures(cdp, origin, step, precedingFetches);
        assert.deepEqual(step.rejectedFailures, [], label + ': resource/runtime failure');
        const identity = step.observations.at(-1);
        const navigation = cdp.events.findLast(event => event.method === 'Page.frameNavigated' && event.params.frame.id === identity.frameId && event.params.frame.loaderId === identity.loaderId);
        if (!navigation || sameDocument(identity, step.before)) return false;
        const {tree, sample} = await applicationSample(cdp, identity);
        const names = tree.nodes.filter(node => !node.ignored).map(node => node.name?.value);
        step.axObservations.push(sample);
        if (!names.includes(expected) || sample.state !== 'Ready' || (identity.url === origin + '/' && !sample.homeEmpty)) return false;
        step.applicationOutcome = sample;
        const responses = cdp.events.filter(event => event.method === 'Network.responseReceived' && event.params.loaderId === identity.loaderId);
        const fonts = responses.filter(event => event.params.response.url.includes('/composeResources/') && event.params.response.url.endsWith('.ttf'));
        const resources = responses.filter(event => event.params.response.url === origin + '/composeApp.js' || event.params.response.url.endsWith('.wasm') || fonts.includes(event));
        const finished = new Set(cdp.events.filter(event => event.method === 'Network.loadingFinished').map(event => event.params.requestId));
        return !bootstrapRequired || (requiredFonts.every(name => fonts.some(event => event.params.response.url.endsWith('/' + name))) &&
            resources.every(event => finished.has(event.params.requestId))) ? tree : false;
    }, label + ': Kotlin route and bundled fonts');
    const document = (await cdp.call('Runtime.evaluate', {expression: 'JSON.stringify({url:location.href,base:document.baseURI,script:document.querySelector("script[src]").src})', returnByValue: true})).result.value;
    step.document = JSON.parse(document);
    assert.equal(step.document.base, origin + '/');
    assert.equal(step.document.script, origin + '/composeApp.js');
    step.responses = cdp.events.filter(event => event.method === 'Network.responseReceived' && event.params.loaderId === step.observations.at(-1).loaderId)
        .map(event => ({url: event.params.response.url, status: event.params.response.status, mime: event.params.response.mimeType}));
    const script = step.responses.find(response => response.url === origin + '/composeApp.js');
    if (bootstrapRequired) assert.ok(script && script.status === 200 && script.mime.includes('javascript'), label + ': real JS, not fallback HTML');
    const wasm = step.responses.filter(response => response.url.endsWith('.wasm'));
    if (bootstrapRequired) assert.equal(wasm.length, 2, label + ': Kotlin and Skiko Wasm');
    assert.ok(wasm.every(response => response.status === 200 && response.mime === 'application/wasm'));
    assert.ok(step.responses.filter(response => response.url.includes('/composeResources/')).every(response => response.url.startsWith(origin + '/composeResources/') && response.status === 200 && response.mime !== 'text/html'));
    const finalFailures = bootstrapFailures(cdp.events, precedingFetches);
    if (finalFailures.length) {
        step.originalStrictFailures = finalFailures;
        await observeCompletionOutcome(cdp, origin, step, precedingFetches);
    }
    step.rejectedFailures = remainingFailures(cdp, origin, step, precedingFetches);
    assert.deepEqual(step.rejectedFailures, []);
    fs.writeFileSync(path.join(artifacts, label + '-ax.json'), JSON.stringify(tree, null, 2));
    fs.writeFileSync(path.join(artifacts, label + '-events.json'), JSON.stringify(cdp.events, null, 2));
    const screenshot = await cdp.call('Page.captureScreenshot', {format: 'png'});
    fs.writeFileSync(path.join(artifacts, label + '-FIXTURE.png'), Buffer.from(screenshot.data, 'base64'));
    step.passed = true;
}

(async () => {
    checkFailureClassification();
    checkOutcomeGuards();
    checkConsumptionProof();
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
            await cdp.call('Runtime.enable'); await cdp.call('Runtime.addBinding', {name: '__w1ReadTrace'});
            await cdp.call('Page.addScriptToEvaluateOnNewDocument', {source: '(' + installReadTrace.toString() + ')()'});
            await cdp.call('Network.enable'); await cdp.call('Accessibility.enable');
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
                requests: [...cdp.requests.values()], readTraces: cdp.readTraces, timeline: cdp.timeline, eventsAtExit: cdp.events}, null, 2));
        }
        for (const cdp of pages) cdp.close();
        if (chrome && chrome.exitCode === null) { chrome.kill('SIGTERM'); await new Promise(resolve => chrome.once('exit', resolve)); }
        await new Promise(resolve => server.close(resolve));
        report.resourcesReleased = true;
        report.observedStrictEvents = pages.flatMap(cdp => bootstrapFailures(cdp.events, cdp.precedingFetches));
        report.failureEvents = report.steps.flatMap(step => step.rejectedFailures || []);
        fs.writeFileSync(path.join(artifacts, 'report.json'), JSON.stringify(report, null, 2));
        console.log('FIXTURE evidence: ' + artifacts);
    }
    console.log('PASS: production root/media/person/search fresh documents + reload, Back/Forward, JS/2 Wasm/font resources; no account writes.');
})().catch(error => { console.error(error); process.exitCode = 1; });
