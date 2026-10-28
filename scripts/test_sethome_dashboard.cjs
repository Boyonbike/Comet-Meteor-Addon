// Run with: node --test scripts/test_sethome_dashboard.cjs
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
    set innerHTML(_) { throw new Error('Unsafe HTML rendering'); }
  }
  const elements = new Map();
  const document = {
    body: new Element(),
    getElementById(id) { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); },
    createElement() { return new Element(); }, querySelectorAll() { return []; }
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
  return { elements, render(home) { stream.listeners.snapshot({ data: JSON.stringify({ sequence: ++sequence, flight: { active: false, home }, events: [], settings: {} }) }); } };
}
const home = () => ({ mode: 'Auto detect', home: 'Test', account: 'test-account', server: 'example.org', paused: true, fixedTimer: false, history: [],
  schedule: { phase: 'COOLDOWN', cooldownMillis: 294000, bufferMillis: 10000, intervalMillis: 0, retryMillis: 0, waitMillis: 304000, learnedMillis: 300000 } });

test('paused home history and empty states render with separate countdowns', () => {
  const d = dashboard(); const h = home(); d.render(h);
  assert.match(d.elements.get('home-state').textContent, /paused/);
  assert.equal(d.elements.get('home-cooldown').textContent, '00:04:54');
  assert.equal(d.elements.get('home-buffer').textContent, '00:00:10 after cooldown');
  assert.equal(d.elements.get('home-history-empty').hidden, false);
  h.history = [{ home: '<script>alert(1)</script>', confirmedAt: 1700000000000, location: { x: 12, y: 70, z: -34, dimension: 'minecraft:the_nether' } }];
  d.render(h);
  assert.equal(d.elements.get('home-history-body').children[0].children[1].textContent, h.history[0].home);
  assert.equal(d.elements.get('home-history-empty').hidden, true);
  assert.match(d.elements.get('home-history-context').textContent, /test-account.*example.org/);
  assert.match(d.elements.get('home-history-zone').textContent, /Local time/);
});

test('buffer and fixed countdowns and response deadlines have distinct meanings', () => {
  const d = dashboard(); const h = home();
  h.schedule = { ...h.schedule, phase: 'BUFFER', cooldownMillis: 0, bufferMillis: 5000, waitMillis: 5000 };
  d.render(h);
  assert.equal(d.elements.get('home-buffer').textContent, '00:00:05');
  h.fixedTimer = true; h.schedule.phase = 'INTERVAL'; h.schedule.intervalMillis = 4000; d.render(h);
  assert.match(d.elements.get('home-buffer').textContent, /Not applied/);
  assert.match(d.elements.get('home-cooldown').textContent, /informational/);
  assert.equal(d.elements.get('home-interval').textContent, '00:00:04');
  h.schedule = { ...h.schedule, phase: 'AWAITING_CONFIRMATION', waiting: true, responseMillis: 15000, waitMillis: 60000 };
  d.render(h);
  assert.equal(d.elements.get('home-waiting').textContent, 'awaiting confirmation · 00:00:15');
  d.render(null);
  assert.equal(d.elements.get('home-history-body').children.length, 0);
});

test('reported and estimated home cooldowns are clearly distinguished', () => {
  const d = dashboard(); const h = home();
  h.schedule.estimatedCooldown = true; d.render(h);
  assert.equal(d.elements.get('home-cooldown').textContent, '00:04:54 (estimate)');
  h.schedule.estimatedCooldown = false; d.render(h);
  assert.equal(d.elements.get('home-cooldown').textContent, '00:04:54');
  assert.equal(d.elements.get('home-waiting').textContent, 'cooldown · 00:05:04');
});
