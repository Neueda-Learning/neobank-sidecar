/*
 * The sidecar's whole UI. No build step, no framework, no dependencies — it is served straight
 * out of the jar, which is why the published image plus a MySQL is the entire box.
 */
'use strict';

const POLL_MS = 2000;
const SEND_ALL_GAP_MS = 250;

const el = (id) => document.getElementById(id);

let scenarios = [];
let info = null;

/* ---------------------------------------------------------------- fetch helpers */

async function getJson(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error(`${path} → ${response.status}`);
  return response.json();
}

async function postJson(path, body) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  let parsed = null;
  try { parsed = text ? JSON.parse(text) : null; } catch { /* a non-JSON error page */ }
  // A 400 from the sidecar is a result to display, not an exception to swallow.
  if (!response.ok) throw new Error(parsed?.message || text || `HTTP ${response.status}`);
  return parsed;
}

/* ---------------------------------------------------------------- header */

async function refreshHealth() {
  try {
    const [health, served] = await Promise.all([getJson('health'), getJson('info')]);
    info = served;
    const up = health.status === 'UP';
    el('pill').textContent = up ? 'Up' : 'Down';
    el('pill').className = 'pill ' + (up ? 'up' : 'down');
    el('sub').innerHTML =
      `dispatches to <code>${escapeHtml(served.moduleUrl)}${escapeHtml(served.modulePath)}</code>` +
      ` · receives callbacks on <code>${escapeHtml(served.callbackPath)}</code>`;
    el('modulePath').textContent = served.modulePath;
    el('count').textContent = `${served.scenarioCount} applications in the library`;
    if (!el('moduleUrl').value) el('moduleUrl').value = served.moduleUrl;
  } catch {
    el('pill').textContent = 'Down';
    el('pill').className = 'pill down';
    el('sub').textContent = 'the sidecar is not answering';
  }
}

/* ---------------------------------------------------------------- scenarios */

async function loadScenarios() {
  const library = await getJson('api/v1/scenarios');
  scenarios = (library.scenarios || []).filter((s) => s.request);

  const select = el('scenario');
  select.innerHTML = '';
  for (const scenario of scenarios) {
    const option = document.createElement('option');
    option.value = scenario.id;
    option.textContent = `${scenario.id} — ${scenario.title}`;
    select.appendChild(option);
  }
  showSelected();
}

function selected() {
  return scenarios.find((s) => s.id === el('scenario').value) || null;
}

function showSelected() {
  const scenario = selected();
  if (!scenario) return;
  const expect = scenario.expectHttp ? ` · expects HTTP ${scenario.expectHttp}` : '';
  el('trait').textContent = (scenario.trait || '') + expect;
  el('envelope').value = JSON.stringify(scenario.request, null, 2);
}

/* ---------------------------------------------------------------- sending */

function readEnvelope() {
  const raw = el('envelope').value.trim();
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (e) {
    // Scenario 26 is invalid JSON-shaped-but-valid-JSON on purpose; genuinely broken JSON that
    // the student typed is a different thing and must not be posted as a mystery.
    throw new Error(`the envelope is not valid JSON: ${e.message}`);
  }
}

async function send() {
  const scenario = selected();
  const button = el('send');
  button.disabled = true;
  try {
    const envelope = readEnvelope();
    const result = await postJson('api/v1/dispatch', {
      scenarioId: scenario ? scenario.id : null,
      envelope,
      moduleUrl: el('moduleUrl').value.trim() || null,
      freshId: el('freshId').checked,
    });
    const status = result.ackHttpStatus;
    say(`sent ${result.applicationId ?? '(no id)'} → HTTP ${status}`);
  } catch (e) {
    say(e.message);
  } finally {
    button.disabled = false;
    refreshLog();
  }
}

async function sendAll() {
  const button = el('sendAll');
  button.disabled = true;
  const fresh = el('freshId').checked;
  const moduleUrl = el('moduleUrl').value.trim() || null;
  try {
    for (let i = 0; i < scenarios.length; i++) {
      const scenario = scenarios[i];
      say(`sending ${i + 1}/${scenarios.length} — ${scenario.id}`);
      try {
        // Deliberately the scenario as it is on disk, not the textarea: "send all" means the
        // corpus, and quietly sending 26 copies of whatever is in the editor would be a trap.
        await postJson('api/v1/dispatch', {
          scenarioId: scenario.id,
          envelope: null,
          moduleUrl,
          freshId: fresh,
        });
      } catch (e) {
        say(`${scenario.id}: ${e.message}`);
      }
      refreshLog();
      await sleep(SEND_ALL_GAP_MS);
    }
    say(`sent ${scenarios.length} applications — scenario 26 is meant to come back 400`);
  } finally {
    button.disabled = false;
    refreshLog();
  }
}

/* ---------------------------------------------------------------- the log */

async function refreshLog() {
  let rows;
  try {
    rows = await getJson('api/v1/dispatches');
  } catch {
    return;
  }

  const body = el('rows');
  if (!rows.length) {
    body.innerHTML = '<tr><td colspan="6" class="empty">Nothing sent yet.</td></tr>';
    return;
  }

  body.innerHTML = rows.map((row) => `
    <tr>
      <td class="mono">${escapeHtml(row.applicationId ?? '—')}${
        row.unsolicited ? ' <span class="st st-dead">unsolicited</span>' : ''}</td>
      <td class="sub">${escapeHtml(row.scenarioId ?? 'edited')}</td>
      <td class="sub">${clock(row.sentAt)}</td>
      <td>${ackCell(row)}</td>
      <td>${callbackCell(row)}</td>
      <td class="sub">${escapeHtml(row.callbackComment ?? '')}</td>
    </tr>`).join('');
}

function ackCell(row) {
  if (row.unsolicited) return '<span class="sub">— never sent</span>';
  const status = row.ackHttpStatus;
  if (status === null || status === undefined) return '<span class="st st-waiting">sending</span>';
  // 0 means the request never landed: unreachable module, wrong port, timeout.
  if (status === 0) return `<span class="st st-dead">unreachable</span> ` +
    `<span class="sub mono">${escapeHtml(shorten(row.ackBody))}</span>`;
  const tone = status >= 200 && status < 300 ? 'st-ok' : status < 500 ? 'st-bad' : 'st-dead';
  return `<span class="st ${tone}">${status}</span>`;
}

function callbackCell(row) {
  if (!row.callbackStatus) {
    if (row.ackHttpStatus === 0) return '<span class="sub">—</span>';
    if (row.ackHttpStatus >= 400) return '<span class="sub">not expected</span>';
    return '<span class="st st-waiting">waiting…</span>';
  }
  const status = escapeHtml(row.callbackStatus);
  const from = row.callbackServiceId ? ` <span class="sub mono">${escapeHtml(row.callbackServiceId)}</span>` : '';
  return `<span class="st st-${status}">${status}</span>${from}`;
}

/* ---------------------------------------------------------------- small helpers */

function say(message) {
  el('sendMsg').textContent = message;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function clock(iso) {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleTimeString();
}

function shorten(text) {
  if (!text) return '';
  return text.length > 70 ? text.slice(0, 69) + '…' : text;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/* ---------------------------------------------------------------- wiring */

el('scenario').addEventListener('change', showSelected);
el('reset').addEventListener('click', showSelected);
el('send').addEventListener('click', send);
el('sendAll').addEventListener('click', sendAll);
el('clear').addEventListener('click', async () => {
  await fetch('api/v1/dispatches', { method: 'DELETE' });
  refreshLog();
});

refreshHealth();
loadScenarios().catch((e) => say(e.message));
refreshLog();
setInterval(refreshLog, POLL_MS);
setInterval(refreshHealth, 10000);
