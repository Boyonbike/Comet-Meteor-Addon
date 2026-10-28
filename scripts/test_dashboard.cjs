// Run with: node --test scripts/test_dashboard.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function dashboard(search = '') {
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
  const replacements = [];
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/main/resources/assets/tnt-coating/dashboard/app.js'), 'utf8'), {
    document, window: { location: { search, pathname: '/', hash: '' }, addEventListener() {} }, EventSource,
    history: { replaceState(...args) { replacements.push(args); } },
    URLSearchParams, Intl, Date, console: { error: (...args) => { throw new Error(args.join(' ')); } },
    fetch: async () => ({ ok: true, json: async () => ({ auth: false }) }), setInterval() {}
  });
  let sequence = 0;
  return { elements, replacements, stream: () => stream, document, open() { stream.listeners.open(); }, renderSnapshot(snapshot) { stream.listeners.snapshot({ data: JSON.stringify(snapshot) }); }, render(home) { stream.listeners.snapshot({ data: JSON.stringify({ sequence: ++sequence, flight: { active: false, home }, events: [], settings: {} }) }); } };
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

const boatFrame = (sequence, boat, extra = {}) => ({ sequence, mode: 'boat', client: { inWorld: boat.inWorld !== false }, flight: { active: false }, boat, events: [], settings: {}, ...extra });

test('boat mode switches branding and panels, then highway mode switches back', () => {
  const d = dashboard();
  d.renderSnapshot(boatFrame(1, { inWorld: true, active: true, automaticTravel: true, state: 'Travelling', heading: 90 }));
  assert.equal(d.document.body.dataset.mode, 'boat');
  assert.equal(d.elements.get('brand-title').textContent, 'Auto Boat Fly');
  assert.equal(d.elements.get('settings-title').textContent, 'Auto Boat Fly settings');
  assert.equal(d.elements.get('flight-state').textContent, 'Travelling');
  d.renderSnapshot({ sequence: 2, mode: 'highway', flight: { active: true, stateLabel: 'Cruising' } });
  assert.equal(d.document.body.dataset.mode, 'highway');
  assert.equal(d.elements.get('brand-title').textContent, 'Highway Fly');
  assert.equal(d.elements.get('flight-state').textContent, 'Cruising');
});

test('a boat mode frame without boat telemetry falls back to highway rendering', () => {
  const d = dashboard();
  d.renderSnapshot({ sequence: 1, mode: 'boat', boat: null, flight: { active: true, stateLabel: 'Cruising' } });
  assert.equal(d.document.body.dataset.mode, 'highway');
});

test('eGaps render exact units including zero and clear after leaving the world', () => {
  const d = dashboard();
  d.renderSnapshot(boatFrame(1, { inWorld: true, egaps: 128, state: 'Travelling' }));
  assert.equal(d.elements.get('boat-egaps').textContent, '128');
  d.renderSnapshot(boatFrame(2, { inWorld: true, egaps: 0 }));
  assert.equal(d.elements.get('boat-egaps').textContent, '0');
  d.renderSnapshot(boatFrame(3, { inWorld: false, egaps: 128 }));
  assert.equal(d.elements.get('boat-egaps').textContent, '—');
  assert.equal(d.elements.get('flight-state').textContent, 'Not in a world');
});

test('fixed heading is independent of current yaw and the controller state is named', () => {
  const d = dashboard();
  d.renderSnapshot(boatFrame(1, { inWorld: true, active: true, automaticTravel: false, heading: -90, yaw: 12, state: 'Paused for positioning' }));
  assert.equal(d.elements.get('boat-heading').textContent, '-90.0° E');
  assert.equal(d.elements.get('boat-yaw').textContent, '12.0° S');
  assert.equal(d.elements.get('boat-controller').textContent, 'Paused for positioning');
  d.renderSnapshot(boatFrame(2, { inWorld: true, active: true, automaticTravel: true, recovering: true, heading: -90, yaw: 80 }));
  assert.equal(d.elements.get('boat-heading').textContent, '-90.0° E');
  assert.equal(d.elements.get('boat-controller').textContent, 'Paused for recovery');
  d.renderSnapshot(boatFrame(3, { inWorld: true, active: true, automaticTravel: true, heading: 180 }));
  assert.equal(d.elements.get('boat-heading').textContent, '180.0° N');
  assert.equal(d.elements.get('boat-controller').textContent, 'Automatic travel');
});

test('safety logout reason remains visible after disconnect', () => {
  const d = dashboard();
  d.renderSnapshot(boatFrame(1, { inWorld: false, active: false, state: 'Safety logout', detail: 'No eGaps remain.', heading: 90 }));
  assert.equal(d.elements.get('flight-state').textContent, 'Safety logout');
  assert.equal(d.elements.get('flight-action').textContent, 'No eGaps remain.');
});

test('boat safety limits, helpers and home schedule render as text', () => {
  const d = dashboard();
  d.renderSnapshot(boatFrame(1, { inWorld: true, active: true, state: '<script>bad()</script>', minimumHealth: 8, egapReserve: null,
    autoEat: true, autoLobby: false, home: { mode: 'Auto detect', home: 'boat', paused: true, account: 'a', server: 's', history: [] } },
    { events: [{ title: '<img src=x onerror=bad()>', description: 'text', time: Date.now() }] }));
  assert.equal(d.elements.get('flight-state').textContent, '<script>bad()</script>');
  assert.equal(d.elements.get('events-list').children[0].children[1].children[0].textContent, '<img src=x onerror=bad()>');
  assert.equal(d.elements.get('boat-minimum-health').textContent, '8.0 HP (4.0 hearts)');
  assert.equal(d.elements.get('boat-egap-reserve').textContent, 'off');
  assert.match(d.elements.get('home-state').textContent, /paused/);
  const helpers = d.elements.get('boat-helpers').children.map(chip => chip.textContent);
  assert.deepEqual(helpers, ['AutoWalk off', 'AutoLobby off', 'AutoSetHome off', 'AutoEat on']);
});

test('the access token is used for the stream and removed from the visible URL', () => {
  const d = dashboard('?token=boat%20secret');
  assert.deepEqual(d.replacements[0].slice(2), ['/']);
});
