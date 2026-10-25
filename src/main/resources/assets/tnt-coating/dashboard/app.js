(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const PLACEHOLDER = '—';
  const token = new URLSearchParams(window.location.search).get('token');

  let lastSequence = -1;
  let lastEventsKey = '';
  let lastSettingsSignature = null;
  let lastSnapshotAt = 0;
  let accessWarning = '';
  let source = null;

  /* ---------- formatting ---------- */

  function text(id, value) {
    const el = $(id);
    if (!el) return;
    el.textContent = value === null || value === undefined || value === '' ? PLACEHOLDER : String(value);
  }

  function fixed(value, digits = 1) {
    return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(digits) : PLACEHOLDER;
  }

  function whole(value) {
    return typeof value === 'number' && Number.isFinite(value) ? String(Math.round(value)) : PLACEHOLDER;
  }

  function metres(value) {
    return typeof value === 'number' && Number.isFinite(value) ? `${Math.round(value)} m` : PLACEHOLDER;
  }

  function clockTime(ms) {
    return typeof ms === 'number' && Number.isFinite(ms)
      ? new Date(ms).toLocaleTimeString([], { hour12: false })
      : PLACEHOLDER;
  }

  function pad(value) {
    return String(value).padStart(2, '0');
  }

  function duration(nanos) {
    if (typeof nanos !== 'number' || !Number.isFinite(nanos) || nanos <= 0) return '00:00:00';
    let seconds = Math.floor(nanos / 1e9);
    const hours = Math.floor(seconds / 3600);
    seconds -= hours * 3600;
    const minutes = Math.floor(seconds / 60);
    seconds -= minutes * 60;
    return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
  }

  function bar(id, percent) {
    const el = $(id);
    if (!el) return;
    const safe = typeof percent === 'number' && Number.isFinite(percent)
      ? Math.max(0, Math.min(100, percent))
      : 0;
    el.style.width = `${safe}%`;
  }

  function chip(label, tone = '') {
    const span = document.createElement('span');
    span.className = `chip ${tone}`.trim();
    span.textContent = label;
    return span;
  }

  function setChips(id, items) {
    const host = $(id);
    if (host) host.replaceChildren(...items);
  }

  function eventColor(value) {
    return typeof value === 'number' ? `#${(value & 0xffffff).toString(16).padStart(6, '0')}` : 'var(--muted)';
  }

  function shortDimension(value) {
    return value ? String(value).replace(/^minecraft:/, '') : PLACEHOLDER;
  }

  function worldClock(dayTime) {
    if (typeof dayTime !== 'number' || !Number.isFinite(dayTime)) return null;
    const hours = (Math.floor(dayTime / 1000) + 6) % 24;
    const minutes = Math.floor(((dayTime % 1000) / 1000) * 60);
    return `${pad(hours)}:${pad(minutes)}`;
  }

  /* ---------- connection ---------- */

  function setConnection(state, label) {
    const dot = $('conn-dot');
    if (dot) dot.dataset.state = state;
    text('conn-text', label);
  }

  function showBanner(message) {
    const banner = $('error-banner');
    if (!banner) return;
    banner.hidden = !message;
    banner.textContent = message || '';
  }

  function connect() {
    if (source) source.close();
    const query = token ? `?token=${encodeURIComponent(token)}` : '';
    source = new EventSource(`/api/events${query}`);

    source.addEventListener('open', () => {
      setConnection('live', 'Live');
    });

    source.addEventListener('snapshot', (event) => {
      try {
        const snapshot = JSON.parse(event.data);
        if (typeof snapshot.sequence === 'number' && snapshot.sequence <= lastSequence) return;
        lastSequence = snapshot.sequence;
        lastSnapshotAt = Date.now();
        setConnection('live', 'Live');
        render(snapshot);
      } catch (error) {
        console.error('Bad dashboard snapshot', error);
      }
    });

    source.addEventListener('error', () => {
      const hint = token ? 'Reconnecting…' : 'Reconnecting… is an access token needed?';
      setConnection('stale', hint);
    });
  }

  async function probeAccess() {
    try {
      const response = await fetch('/api/health', { cache: 'no-store' });
      if (!response.ok) return;
      const health = await response.json();
      if (health.auth && !token) {
        accessWarning = 'This dashboard requires an access token. Ask the PC that runs Minecraft for the token and open the page as /?token=…';
        showBanner(accessWarning);
      }
    } catch (error) {
      /* the EventSource status already covers connectivity */
    }
  }

  /* ---------- rendering ---------- */

  function render(snapshot) {
    document.body.dataset.trip = snapshot.flight && snapshot.flight.active ? 'active' : 'idle';
    text('generated-at', clockTime(snapshot.generatedAt));
    showBanner(snapshot.error ? `Snapshot error: ${snapshot.error}` : accessWarning);
    renderServer(snapshot.server);
    renderFlight(snapshot.flight, snapshot.client);
    renderClient(snapshot.client);
    renderEvents(snapshot.events);
    renderSettings(snapshot.settings, snapshot.settingsSignature);
  }

  function renderServer(server) {
    const host = $('server-urls');
    if (host) {
      const urls = (server && server.urls) || [];
      host.replaceChildren();
      if (urls.length === 0) {
        host.textContent = PLACEHOLDER;
      } else {
        urls.forEach((url, index) => {
          const link = document.createElement('a');
          link.href = url;
          link.textContent = url;
          link.target = '_blank';
          link.rel = 'noopener';
          host.append(link);
          if (index < urls.length - 1) host.append(document.createTextNode(' · '));
        });
      }
    }
    if (!server) {
      text('server-listen', PLACEHOLDER);
      text('server-clients', PLACEHOLDER);
      text('server-version', PLACEHOLDER);
      text('server-uptime', PLACEHOLDER);
      return;
    }
    text('server-listen', `${server.bindAddress}:${server.port}`);
    text('server-clients', whole(server.clients));
    text('server-version', server.version);
    text('server-uptime', server.running && server.startedAtMillis
      ? duration((Date.now() - server.startedAtMillis) * 1e6)
      : 'stopped');
  }

  function renderFlight(flight, client) {
    const f = flight || {};
    const inWorld = Boolean(client && client.inWorld);
    text('flight-state', f.active ? (f.stateLabel || f.stateName) : (inWorld ? 'Highway Fly off' : 'Not in a world'));
    text('flight-action', f.active ? f.action : (inWorld ? 'Enable the highway-fly module to start a trip.' : 'Join a world to see live flight data.'));
    text('flight-detail', f.active ? f.detail : PLACEHOLDER);
    text('trip-time', duration(f.tripNanos));
    text('active-time', duration(f.activeNanos));

    const route = f.route;
    if (!route) {
      bar('progress-bar', 0);
      text('progress-percent', f.active ? 'No route' : PLACEHOLDER);
      text('progress-remaining', PLACEHOLDER);
      text('route-destination', PLACEHOLDER);
      text('route-length', PLACEHOLDER);
      text('route-distance', PLACEHOLDER);
      text('route-target-y', PLACEHOLDER);
      text('route-active-y', PLACEHOLDER);
      text('route-yaw', PLACEHOLDER);
    } else {
      const progress = typeof route.progress === 'number' && Number.isFinite(route.progress)
        ? Math.max(0, Math.min(1, route.progress))
        : 0;
      bar('progress-bar', progress * 100);
      text('progress-percent', `${Math.round(progress * 100)}%`);
      text('progress-remaining', metres(route.remaining));
      text('route-destination', `${whole(route.destinationX)}, ${whole(route.destinationZ)}`);
      text('route-length', metres(route.length));
      text('route-distance', metres(route.distanceToDestination));
      text('route-target-y', route.targetY === null ? PLACEHOLDER : fixed(route.targetY, 1));
      const altitude = f.altitude;
      text('route-active-y', altitude && altitude.activeY !== null ? fixed(altitude.activeY, 1) : PLACEHOLDER);
      text('route-yaw', route.yaw === null ? PLACEHOLDER : `${fixed(route.yaw, 0)}°`);
    }

    const counters = f.counters || {};
    text('counter-state', f.active ? (f.stateName || PLACEHOLDER) : PLACEHOLDER);
    text('counter-detour', whole(counters.detourTicks));
    text('counter-blocked', whole(counters.blockedAttempts));
    text('counter-takeoff', whole(counters.takeoffAttempts));
    text('counter-rubberbands', whole(counters.rubberbands));
    text('counter-walk-failures', whole(counters.walkFailures));
    text('counter-failed-swaps', whole(counters.failedSwaps));
    text('counter-walk-blocks', whole(counters.walkBlocks));
    text('counter-walk-ticks', whole(counters.walkTicks));
    text('counter-retry', whole(counters.retryTicks));
    text('counter-climb', whole(counters.climbTicks));

    const path = f.path;
    if (path) {
      const points = path.points || [];
      text('path-summary', `Path: ${points.length} point${points.length === 1 ? '' : 's'}${path.truncated ? ' (truncated)' : ''}`);
      const tail = points.slice(-5).map((point) => `${fixed(point.x)}, ${fixed(point.y)}, ${fixed(point.z)}`);
      text('path-points', tail.length ? `Latest: ${tail.join(' → ')}` : PLACEHOLDER);
    } else {
      text('path-summary', 'Path: no active flight');
      text('path-points', PLACEHOLDER);
    }

    const walk = f.walk;
    if (walk && walk.started) {
      const target = walk.hasTarget ? ` → ${whole(walk.targetX)}, ${whole(walk.targetZ)}` : '';
      text('walk-summary', `Walk: ${whole(walk.blocks)} blocks${target} · ${whole(walk.failures)} failure(s)`);
    } else {
      text('walk-summary', 'Walk: not walking');
    }

    const safety = f.safety || {};
    const damageTaken = typeof safety.damageTaken === 'number' ? safety.damageTaken : 0;
    const damageLimit = typeof safety.damageLimit === 'number' ? safety.damageLimit : 0;
    bar('safety-damage-bar', damageLimit > 0 ? (damageTaken / damageLimit) * 100 : (damageTaken > 0 ? 100 : 0));
    text('safety-damage-text', damageLimit > 0 ? `${fixed(damageTaken)} / ${fixed(damageLimit)}` : `${fixed(damageTaken)} / off`);
    text('safety-noprogress', safety.noProgressLimitSeconds === null || safety.noProgressLimitSeconds === undefined
      ? PLACEHOLDER
      : `${safety.noProgressLimitSeconds} s`);

    const safetyFlags = [];
    if (safety.safetyDisconnect) safetyFlags.push(chip('safety disconnect', 'warn'));
    if (safety.deathNotified) safetyFlags.push(chip('death noted', 'danger'));
    setChips('safety-flags', safetyFlags);

    const elytra = f.elytra || {};
    text('elytra-state', elytra.equipped ? 'equipped' : 'missing');
    text('elytra-durability', elytra.remaining === null || elytra.remaining === undefined
      ? (elytra.equipped ? 'unlimited' : PLACEHOLDER)
      : whole(elytra.remaining));
    text('elytra-min', whole(elytra.minDurability));

    const portal = f.portal || {};
    text('portal-mode', portal.mode || PLACEHOLDER);
    const portalStates = [];
    if (portal.inside) portalStates.push('inside');
    if (portal.crossing) portalStates.push('crossing');
    if (portal.walkPaused) portalStates.push('walk paused');
    text('portal-state', portalStates.length ? portalStates.join(' · ') : 'clear');
    text('portal-hit', portal.hit
      ? `${fixed(portal.hit.distance)} m at ${whole(portal.hit.x)}, ${whole(portal.hit.y)}, ${whole(portal.hit.z)}`
      : 'none');

    text('recovery-state', f.recovering ? 'recovering' : 'ready');
    text('recovery-allowed', f.recovering ? (f.recoveryAllowed ? 'yes, on this server' : 'no, wrong server') : PLACEHOLDER);
    text('lobby-info', f.lobbyInfo || PLACEHOLDER);

    const home = f.home;
    if (!home) {
      text('home-state', 'inactive');
      text('home-waiting', PLACEHOLDER);
    } else {
      text('home-state', `${home.mode || 'auto'} → ${home.home || PLACEHOLDER}`);
      text('home-waiting', home.waiting
        ? `waiting ${duration(home.waitMillis * 1e6)}`
        : (home.cooldownKnown && home.cooldownLabel ? home.cooldownLabel : 'idle'));
    }

    const webhook = f.webhook;
    if (!webhook) {
      text('webhook-state', PLACEHOLDER);
    } else if (!webhook.enabled) {
      text('webhook-state', 'off');
    } else {
      text('webhook-state', `${webhook.configured ? 'configured' : 'no URL'}${webhook.terminal ? ' · terminal sent' : ''}`);
    }

    const paused = f.pausedModules || [];
    text('paused-modules', paused.length ? paused.join(', ') : 'none');
  }

  function renderClient(client) {
    if (!client || !client.inWorld) {
      text('player-name', client && client.singleplayer ? 'Singleplayer menu' : 'Not in a world');
      text('player-server', PLACEHOLDER);
      ['player-dimension', 'player-coords', 'player-ping', 'player-speed', 'player-vspeed',
        'player-health-text', 'player-air-text', 'player-extra'].forEach((id) => text(id, PLACEHOLDER));
      bar('player-health-bar', 0);
      bar('player-air-bar', 0);
      setChips('player-flags', []);
      return;
    }

    text('player-name', client.player || PLACEHOLDER);
    text('player-server', client.singleplayer ? 'Singleplayer' : (client.server || 'Unknown server'));
    text('player-dimension', shortDimension(client.dimension));
    text('player-coords', `${fixed(client.x)}, ${fixed(client.y)}, ${fixed(client.z)}`);
    text('player-ping', client.ping === null || client.ping === undefined ? PLACEHOLDER : `${whole(client.ping)} ms`);
    text('player-speed', `${fixed(client.speedBps)} b/s`);
    text('player-vspeed', `${fixed(client.verticalSpeedBps)} b/s`);
    bar('player-health-bar', client.maxHealth ? (client.health / client.maxHealth) * 100 : 0);
    text('player-health-text', client.health === null ? PLACEHOLDER
      : `${fixed(client.health)} / ${fixed(client.maxHealth)}`);
    bar('player-air-bar', client.maxAir ? (client.air / client.maxAir) * 100 : 0);
    text('player-air-text', client.air === null ? PLACEHOLDER : `${whole(client.air)} / ${whole(client.maxAir)}`);

    const flags = [];
    if (client.fallFlying) flags.push(chip('elytra flying', 'ok'));
    if (client.onGround) flags.push(chip('on ground'));
    if (client.inWater) flags.push(chip('in water', 'warn'));
    if (client.inLava) flags.push(chip('in lava', 'danger'));
    if (client.singleplayer) flags.push(chip('singleplayer'));
    setChips('player-flags', flags);

    const clock = worldClock(client.dayTime);
    const weather = client.thundering ? 'thunder' : client.raining ? 'rain' : 'clear';
    const extra = [
      `Food ${whole(client.food)}`,
      `Armor ${whole(client.armor)}`,
      client.absorption > 0 ? `Absorption ${fixed(client.absorption)}` : null,
      `Weather ${weather}`,
      clock ? `Clock ${clock}` : null
    ].filter(Boolean);
    text('player-extra', extra.join(' · '));
  }

  function renderEvents(events) {
    if (!Array.isArray(events)) return;
    const key = events.length ? `${events[events.length - 1].sequence}:${events.length}` : '0';
    if (key === lastEventsKey) return;
    lastEventsKey = key;

    const host = $('events-list');
    const empty = $('events-empty');
    if (!host) return;
    host.replaceChildren();
    const shown = events.slice(-60).reverse();
    shown.forEach((event) => {
      const item = document.createElement('li');
      item.className = 'event';

      const dot = document.createElement('span');
      dot.className = 'ev-dot';
      dot.style.background = eventColor(event.color);

      const body = document.createElement('div');
      const title = document.createElement('p');
      title.className = 'ev-title';
      title.textContent = event.title || event.type || event.kind;
      if (event.kind === 'phase') {
        const badge = document.createElement('span');
        badge.className = 'ev-kind';
        badge.textContent = event.type || '';
        title.append(' ', badge);
      }
      body.append(title);
      if (event.description) {
        const description = document.createElement('p');
        description.className = 'ev-desc';
        description.textContent = event.description;
        body.append(description);
      }

      const time = document.createElement('time');
      time.className = 'ev-time';
      time.textContent = clockTime(event.time);

      item.append(dot, body, time);
      host.append(item);
    });
    if (empty) empty.hidden = shown.length > 0;
  }

  function renderSettings(settings, signature) {
    if (signature === lastSettingsSignature) return;
    lastSettingsSignature = signature;

    const host = $('settings-list');
    const empty = $('settings-empty');
    if (!host) return;
    host.replaceChildren();

    const groups = settings || {};
    const names = Object.keys(groups);
    if (empty) empty.hidden = names.length > 0;
    if (names.length === 0) return;

    names.forEach((groupName) => {
      const values = groups[groupName] || [];
      const details = document.createElement('details');
      details.className = 'settings-group';

      const summary = document.createElement('summary');
      summary.textContent = `${groupName} (${values.length})`;
      details.append(summary);

      const rows = document.createElement('div');
      rows.className = 'settings-rows';
      values.forEach((value) => {
        const row = document.createElement('div');
        row.className = 'setting-row';

        const name = document.createElement('span');
        name.className = 'setting-name';
        name.textContent = value.title || value.name;
        name.title = `${value.name} · ${value.type}`;

        const rendered = document.createElement('span');
        rendered.className = 'setting-value mono';
        rendered.textContent = value.value;

        row.append(name, rendered);
        rows.append(row);
      });
      details.append(rows);
      host.append(details);
    });

    applySettingsFilter();
  }

  function applySettingsFilter() {
    const input = $('settings-filter');
    const query = input ? input.value.trim().toLowerCase() : '';
    document.querySelectorAll('#settings-list .settings-group').forEach((group) => {
      let visible = 0;
      group.querySelectorAll('.setting-row').forEach((row) => {
        const match = !query || row.textContent.toLowerCase().includes(query);
        row.hidden = !match;
        if (match) visible += 1;
      });
      group.hidden = visible === 0;
      if (query && visible > 0) group.open = true;
    });
  }

  /* ---------- boot ---------- */

  const filter = $('settings-filter');
  if (filter) filter.addEventListener('input', applySettingsFilter);

  setInterval(() => {
    if (source && source.readyState === EventSource.OPEN && Date.now() - lastSnapshotAt > 5_000) {
      setConnection('stale', 'Waiting for the game');
    }
  }, 2_000);

  window.addEventListener('beforeunload', () => {
    if (source) source.close();
  });

  probeAccess();
  connect();
})();
