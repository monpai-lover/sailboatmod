// Run with: node --test tools/test_route_debug_page.cjs
// Execute the real Java-embedded page script; no Minecraft, browser, or npm install is required.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const javaFile = path.join(__dirname, '../src/main/java/com/monpai/sailboatmod/route/water/debug/RouteDebugHtml.java');
const java = fs.readFileSync(javaFile, 'utf8');
const block = java.match(/static final String PAGE\s*=\s*"""\r?\n([\s\S]*?)""";/);
assert.ok(block, 'RouteDebugHtml.PAGE must remain a Java text block');
const html = block[1].replace(/\\(?:\r?\n|[btnfrs"'\\])/g, escape => ({
  '\\b': '\b', '\\t': '\t', '\\n': '\n', '\\f': '\f', '\\r': '\r', '\\s': ' ',
  '\\"': '"', "\\'": "'", '\\\\': '\\',
}[escape] ?? ''));
const scripts = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(match => match[1]);
assert.ok(scripts.length, 'RouteDebugHtml.PAGE must contain its executable script');

class Element {
  constructor(tagName, attributes = '') {
    this.tagName = tagName.toUpperCase();
    this.id = attributes.match(/\bid=["']([^"']+)["']/i)?.[1] ?? '';
    this.disabled = /(?:^|\s)disabled(?:\s|=|$)/i.test(attributes);
    this.children = [];
    this.options = this.tagName === 'SELECT' ? [] : undefined;
    this.selectedIndex = -1;
    this._text = '';
    this._html = '';
    this._value = attributes.match(/\bvalue=["']([^"']*)["']/i)?.[1] ?? '';
    this.attributes = new Map();
    this.listeners = new Map();
  }
  get value() { return this.options ? this.options[this.selectedIndex]?.value ?? '' : this._value; }
  set value(value) {
    if (this.options) this.selectedIndex = this.options.findIndex(option => option.value === String(value));
    else this._value = String(value);
  }
  get textContent() { return this._text + this._html.replace(/<[^>]*>/g, '') + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this._text = String(value); this._html = ''; this.children = []; }
  get innerHTML() { return this._html; }
  set innerHTML(value) { this._html = String(value); this._text = ''; this.children = []; }
  add(option) { this.options.push(option); if (this.selectedIndex < 0 || option.selected) this.selectedIndex = this.options.length - 1; }
  appendChild(child) { this.children.push(child); return child; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this._html = ''; this._text = ''; this.children = children; }
  setAttribute(name, value) { this.attributes.set(name, String(value)); if (name === 'disabled') this.disabled = true; }
  removeAttribute(name) { this.attributes.delete(name); if (name === 'disabled') this.disabled = false; }
  addEventListener(type, handler) { this.listeners.set(type, handler); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
  querySelectorAll(selector) {
    const descendants = [...(this.options ?? []), ...this.children];
    return descendants.flatMap(child => [child, ...child.querySelectorAll('*')]).filter(child => matches(child, selector));
  }
}

function matches(element, selector) {
  if (selector === '*') return true;
  const id = selector.match(/#([\w-]+)/)?.[1];
  if (id && !selector.includes(' ') && element.id !== id) return false;
  const value = selector.match(/\[value=["']?([^\]"']+)["']?\]/)?.[1];
  if (value !== undefined && element.value !== value) return false;
  const tag = selector.replace(/^#[\w-]+\s+/, '').match(/^([a-z]+)/i)?.[1];
  return !tag || element.tagName === tag.toUpperCase();
}

function documentFromHtml() {
  const elements = new Map();
  for (const match of html.matchAll(/<([a-z][\w-]*)\b([^>]*\bid=["'][^"']+["'][^>]*)>/gi)) {
    const element = new Element(match[1], match[2]);
    elements.set(element.id, element);
    if (element.options) {
      const body = html.slice(match.index + match[0].length).split(/<\/select>/i)[0];
      for (const optionMatch of body.matchAll(/<option\b([^>]*)>([\s\S]*?)<\/option>/gi)) {
        const option = new Element('option', optionMatch[1]);
        option.textContent = optionMatch[2].replace(/<[^>]*>/g, '');
        option.selected = /(?:^|\s)selected(?:\s|=|$)/i.test(optionMatch[1]);
        element.add(option);
      }
    }
  }
  const staticText = html.replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1>/gi, '').replace(/<[^>]*>/g, '');
  return {
    getElementById: id => elements.get(id) ?? null,
    createElement: tag => new Element(tag),
    querySelector: selector => [...elements.values()].find(element => matches(element, selector)) ?? null,
    querySelectorAll: selector => [...elements.values()].filter(element => matches(element, selector)),
    get body() { return { textContent: staticText + [...elements.values()].map(element => element.textContent).join(' ') }; },
  };
}

const docks = [{ index: 0, name: 'Taipei', x: 1, z: 2 }, { index: 1, name: 'Amsterdam', x: 3, z: 4 }];
const success = {
  mode: 'tellus', pipeline: 'production-nbt', status: 'SUCCESS', log: 'fresh production route',
  // Extra legacy images intentionally verify that the production gallery only displays its final result.
  images: { verified: 'data:image/png;base64,FINAL', coarse: 'data:image/png;base64,COARSE', overview: 'data:image/png;base64,OVERVIEW' },
};
const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body, text: async () => JSON.stringify(body) });
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};
const flush = async () => { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)); };
const renderedContent = element => element.innerHTML + (element.src ?? '') + element.children.map(renderedContent).join('');

function page({ tellus = true, capabilities, dockResponse, route } = {}) {
  const document = documentFromHtml();
  const calls = [];
  const context = vm.createContext({
    document, console, URL, URLSearchParams, setTimeout, clearTimeout,
    Option: function Option(text, value) { const option = new Element('option'); option.textContent = text; option.value = value; return option; },
    fetch: async input => {
      const url = new URL(String(input), 'http://route-debug.test');
      calls.push(url);
      if (url.pathname === '/api/capabilities') return typeof capabilities === 'function' ? capabilities() : capabilities ?? response({
        tellusAvailable: tellus, defaultMode: tellus ? 'tellus' : 'nbt',
        tellusUnavailableReason: tellus ? '' : 'This world does not use the Tellus Earth generator.',
      });
      if (url.pathname === '/api/docks') return typeof dockResponse === 'function' ? dockResponse() : dockResponse ?? response(docks);
      if (url.pathname === '/api/route') return typeof route === 'function' ? route(url) : route ?? response(success);
      throw new Error(`Unexpected request: ${url}`);
    },
  });
  context.window = context;
  scripts.forEach(script => vm.runInContext(script, context, { filename: 'RouteDebugHtml.PAGE.js' }));
  return {
    document, calls, context,
    element(id) { const element = document.getElementById(id); assert.ok(element, `the page needs #${id}`); return element; },
    routes() { return calls.filter(url => url.pathname === '/api/route'); },
    go() { assert.equal(typeof context.go, 'function', 'Generate must call go()'); return context.go(); },
  };
}

test('mode selector exposes all four web modes', () => {
  const app = page();
  assert.deepEqual(app.element('mode').options.map(option => option.value).sort(), ['hybrid', 'nbt', 'noise', 'tellus']);
});

test('Tellus capability selects and enables the explicit Tellus mode', async () => {
  const app = page();
  await flush();
  assert.ok(app.calls.some(url => url.pathname === '/api/capabilities'), 'initialization must fetch server capabilities');
  assert.equal(app.element('mode').value, 'tellus');
  assert.equal(app.element('mode').options.find(option => option.value === 'tellus').disabled, false);
  assert.equal(app.element('generate').disabled, false);
});

test('non-Tellus world disables Tellus, shows its reason, and defaults to NBT', async () => {
  const app = page({ tellus: false });
  await flush();
  assert.equal(app.element('mode').value, 'nbt');
  const option = app.element('mode').options.find(option => option.value === 'tellus');
  assert.ok(option, 'Tellus remains visible outside Tellus worlds');
  assert.equal(option.disabled, true);
  assert.match(app.document.body.textContent, /This world does not use the Tellus Earth generator\./);
  assert.equal(app.element('generate').disabled, false);
});

for (const pendingEndpoint of ['capabilities', 'docks']) {
  test(`Generate waits until ${pendingEndpoint} initialization completes`, async () => {
    const pending = deferred();
    const app = page(pendingEndpoint === 'capabilities' ? { capabilities: () => pending.promise } : { dockResponse: () => pending.promise });
    await flush();
    assert.equal(app.element('generate').disabled, true);
    await app.go();
    assert.equal(app.routes().length, 0, 'go() must also guard requests while initialization is incomplete');
    pending.resolve(response(pendingEndpoint === 'capabilities' ? { tellusAvailable: true, defaultMode: 'tellus', tellusUnavailableReason: '' } : docks));
    await flush();
    assert.equal(app.element('generate').disabled, false);
  });
}

test('failed capabilities keep Generate disabled and show the server error', async () => {
  const app = page({ capabilities: response({ error: 'capabilities_failed', message: 'Capability service unavailable' }, 503) });
  await flush();
  assert.equal(app.element('generate').disabled, true);
  assert.match(app.document.body.textContent, /Capability service unavailable/);
  await app.go();
  assert.equal(app.routes().length, 0);
});

test('network failure during initialization remains visible and cannot generate', async () => {
  const app = page({ capabilities: () => Promise.reject(new Error('Capability network offline')) });
  await flush();
  assert.equal(app.element('generate').disabled, true);
  assert.match(app.document.body.textContent, /Capability network offline/);
  await app.go();
  assert.equal(app.routes().length, 0);
});

test('Tellus request preserves its explicit mode and only renders the final production stage', async () => {
  const app = page();
  await flush();
  await app.go();
  assert.equal(app.routes().length, 1);
  assert.equal(app.routes()[0].searchParams.get('mode'), 'tellus');
  assert.equal(app.routes()[0].searchParams.get('a'), '0');
  assert.equal(app.routes()[0].searchParams.get('b'), '1');
  const gallery = app.element('imgs');
  assert.equal(gallery.children.length, 1, 'production results have exactly one final-result stage');
  assert.match(renderedContent(gallery), /FINAL/);
  assert.doesNotMatch(renderedContent(gallery), /COARSE|OVERVIEW/);
  assert.equal(app.element('log').textContent, success.log);
  assert.match(app.element('msg').textContent, /done/i);
  assert.equal(app.element('generate').disabled, false);
});

test('Generate prevents a second request while work is pending and restores itself afterward', async () => {
  const pending = deferred();
  const app = page({ route: () => pending.promise });
  await flush();
  const first = app.go();
  assert.equal(app.element('generate').disabled, true);
  const second = app.go();
  assert.equal(app.routes().length, 1, 'double invocation must not launch concurrent route work');
  pending.resolve(response(success));
  await Promise.all([first, second]);
  assert.equal(app.element('generate').disabled, false);
});

for (const [description, routeResponse, reason] of [
  ['HTTP rejection', response({ error: 'tellus_unavailable', message: 'Tellus is unavailable for this world' }, 409), 'Tellus is unavailable for this world'],
  ['failed route result', response({ mode: 'tellus', pipeline: 'production-nbt', status: 'FAILED', reason: 'TERRAIN_NOT_READY', images: {}, log: 'not ready' }), 'TERRAIN_NOT_READY'],
]) {
  test(`${description} displays its reason without a done message`, async () => {
    const app = page({ route: routeResponse });
    await flush();
    await app.go();
    assert.ok(app.element('msg').textContent.includes(reason), `the route error must include ${reason}`);
    if (description === 'failed route result') assert.equal(app.element('log').textContent, 'not ready', 'failed planning must retain its diagnostic log');
    assert.doesNotMatch(app.element('msg').textContent, /\bdone\b/i);
    assert.equal(app.element('generate').disabled, false);
  });
}

test('network failure during route work restores Generate and displays the error', async () => {
  const app = page({ route: () => Promise.reject(new Error('Route connection dropped')) });
  await flush();
  await app.go();
  assert.match(app.element('msg').textContent, /Route connection dropped/);
  assert.doesNotMatch(app.element('msg').textContent, /\bdone\b/i);
  assert.equal(app.element('generate').disabled, false);
});
