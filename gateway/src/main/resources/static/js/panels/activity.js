import { onEvent } from '../ws.js';

let _logEl = null;

export function initActivityStream() {
  _logEl = document.getElementById('log');

  // Subscribe to WebSocket events
  onEvent((msg) => {
    if (msg._type === 'ws_status') return; // handled by app.js
    if (msg.type === 'EVENT' && msg.payload) {
      const p = msg.payload;
      const kind = inferKind(p.type);
      addLog(`${p.type}: ${typeof p.payload === 'string' ? p.payload.substring(0, 120) : JSON.stringify(p.payload).substring(0, 120)}`, kind);
    }
  });
}

export function addLog(line, kind = 'info') {
  if (!_logEl) _logEl = document.getElementById('log');
  if (!_logEl) return;

  const div = document.createElement('div');
  div.className = 'event';
  const badge = kind === 'good' ? 'good' : kind === 'bad' ? 'bad' : kind === 'warn' ? 'warn' : '';
  const ts = new Date().toLocaleTimeString();
  div.innerHTML = `<div class="eventTop"><div class="eventTitle" style="font-size:12px">${esc(line)}</div><span class="pill ${badge}">${esc(kind)}</span></div><div class="tiny">${ts}</div>`;
  _logEl.prepend(div);

  // Keep log bounded
  while (_logEl.children.length > 100) _logEl.removeChild(_logEl.lastChild);
}

export function clearLog() {
  if (_logEl) _logEl.innerHTML = '';
}

function inferKind(eventType) {
  if (!eventType) return 'info';
  const t = String(eventType).toUpperCase();
  if (t.includes('ERROR') || t.includes('FAILED')) return 'bad';
  if (t.includes('COMPLETED') || t.includes('PASS') || t.includes('READY')) return 'good';
  if (t.includes('STARTED') || t.includes('RUNNING') || t.includes('ROUTING')) return 'warn';
  return 'info';
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
