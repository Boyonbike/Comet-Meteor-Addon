// Run with: node --test scripts/test_dashboard.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function dashboard() {
  class Element {
    constructor() { this.children = []; this.dataset = {}; this.style = {}; this.textContent = ''; this.value = ''; }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    addEventListener() {}
    setAttribute(name, value) { this[name] = String(value); }
    set innerHTML(_) { throw new Error('Unsafe HTML rendering'); }
  }
  const elements = new Map();
  const document = {
    body: new Element(),
    getElementById(id) { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); },
    createElement() { return new Element(); }, createTextNode(text) { return text; }, querySelectorAll() { return []; }
  };
  let stream;
  class EventSource {
    constructor() { this.listeners = {}; stream = this; }
    addEventListener(name, callback) { this.listeners[name] = callback; }
    close() {}
  }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/main/resources/assets/tnt-coating/dashboard/app.js'), 'utf8'), {
    document, window: { location: { search: '' }, addEventListener() {} }, EventSource,
    URLSearchParams, Intl, Date, console: { error: (...args) => { throw new Error(args.join(' ')); } },
    fetch: async () => ({ ok: true, json: async () => ({ auth: false }) }), setInterval() {}
  });
  let sequence = 0;
  return { elements, open() { stream.listeners.open(); }, renderSnapshot(snapshot) { stream.listeners.snapshot({ data: JSON.stringify(snapshot) }); }, render(home) { stream.listeners.snapshot({ data: JSON.stringify({ sequence: ++sequence, flight: { active: false, home }, events: [], settings: {} }) }); } };
}

test('server uptime uses the published startedAt timestamp', () => {
  const d = dashboard();
  d.renderSnapshot({ sequence: 1, server: { running: true, startedAt: Date.now() - 65000, urls: [] } });
  assert.equal(d.elements.get('server-uptime').textContent, '00:01:05');
});

test('a reconnected stream accepts a restarted sequence and refreshes events', () => {
  const d = dashboard();
  d.renderSnapshot({ sequence: 50, flight: { active: true, stateLabel: 'Cruising' }, events: [{ sequence: 1, title: 'Old trip' }] });
  d.open();
  d.renderSnapshot({ sequence: 1, flight: { active: true, stateLabel: 'Taking off' }, events: [{ sequence: 1, title: 'New trip' }] });
  assert.equal(d.elements.get('flight-state').textContent, 'Taking off');
  assert.equal(d.elements.get('events-list').children[0].children[1].children[0].textContent, 'New trip');
});

test('older frames on the same connection are ignored', () => {
  const d = dashboard();
  d.renderSnapshot({ sequence: 5, flight: { active: true, stateLabel: 'Cruising' } });
  d.renderSnapshot({ sequence: 4, flight: { active: true, stateLabel: 'Taking off' } });
  assert.equal(d.elements.get('flight-state').textContent, 'Cruising');
});


test('main tile shows current coordinates and clears them when leaving the world', () => {
  const d = dashboard();
  d.renderSnapshot({ sequence: 1, client: { inWorld: true, x: 12500.25, y: 120, z: -34.5 } });
  assert.equal(d.elements.get('flight-coords').textContent, '12500.3, 120.0, -34.5');
  d.renderSnapshot({ sequence: 2, client: { inWorld: false } });
  assert.equal(d.elements.get('flight-coords').textContent, '—');
});

test('usable elytra count displays zero and positive counts while flight is off', () => {
  const d = dashboard();
  d.renderSnapshot({ sequence: 1, flight: { active: false, elytra: { usableCount: 3, minDurability: 10 } } });
  assert.equal(d.elements.get('elytra-usable').textContent, '3');
  d.renderSnapshot({ sequence: 2, flight: { active: false, elytra: { usableCount: 0, minDurability: 10 } } });
  assert.equal(d.elements.get('elytra-usable').textContent, '0');
});
