import * as api from '../api.js';
import { onEvent } from '../ws.js';

const ALL_AGENTS = [
  { id: 'reconcile-agent',  label: 'Reconcile' },
  { id: 'resource-agent',   label: 'Resource' },
  { id: 'objective-agent',  label: 'Objective' },
  { id: 'checklist-agent',  label: 'Checklist' },
  { id: 'plan-agent',       label: 'Plan' },
  { id: 'thread-extractor', label: 'Extractor' },
  { id: 'thread-agent',     label: 'Thread' },
  { id: 'intake-triage',    label: 'Intake' },
  { id: 'pm',               label: 'PM' },
  { id: 'coder',            label: 'Coder' },
  { id: 'generalist',       label: 'Generalist' },
  { id: 'distiller',        label: 'Distiller' },
  { id: 'controller',       label: 'Controller' },
  { id: 'reviewer',         label: 'Reviewer' },
  { id: 'reminder',         label: 'Reminder' },
];

// agentId → { status: 'idle' | 'running' | 'done' | 'error', since: Date }
const agentState = {};
let _container = null;

export function initActivityStream() {
  _container = document.getElementById('log');
  if (!_container) return;

  // Init all agents as idle
  ALL_AGENTS.forEach(a => { agentState[a.id] = { status: 'idle', since: null }; });

  // Poll running executions on load
  refreshFromBackend();

  // Subscribe to WebSocket events for live updates
  onEvent((msg) => {
    if (msg._type === 'ws_status') return;
    if (msg.type !== 'EVENT' || !msg.payload) return;
    const p = msg.payload;
    const agentId = p.payload?.agentId || p.agentId;
    if (!agentId || !agentState[agentId]) return;

    const evType = p.type || '';
    if (evType === 'AGENT_STEP_STARTED' || evType === 'AGENT_DELEGATED') {
      agentState[agentId] = { status: 'running', since: new Date() };
    } else if (evType === 'AGENT_STEP_COMPLETED' || evType === 'AGENT_RESPONSE') {
      agentState[agentId] = { status: 'done', since: new Date() };
      // Fade back to idle after 8s
      setTimeout(() => {
        if (agentState[agentId]?.status === 'done') {
          agentState[agentId] = { status: 'idle', since: null };
          renderGrid();
        }
      }, 8000);
    } else if (evType === 'ERROR') {
      agentState[agentId] = { status: 'error', since: new Date() };
      setTimeout(() => {
        if (agentState[agentId]?.status === 'error') {
          agentState[agentId] = { status: 'idle', since: null };
          renderGrid();
        }
      }, 10000);
    }
    renderGrid();
  });

  renderGrid();
}

async function refreshFromBackend() {
  try {
    const futures = await api.executions.future({ execStatus: 'RUNNING' });
    (futures || []).forEach(ex => {
      if (agentState[ex.agentId]) {
        agentState[ex.agentId] = { status: 'running', since: new Date(ex.scheduledAt) };
      }
    });
    renderGrid();
  } catch { /* backend may not be up yet */ }
}

function renderGrid() {
  if (!_container) return;
  _container.innerHTML = ALL_AGENTS.map(a => {
    const st = agentState[a.id] || { status: 'idle' };
    const icon = st.status === 'running' ? '<span class="agent-icon spinning">&#9881;</span>'
               : st.status === 'done'    ? '<span class="agent-icon done-flash">&#10003;</span>'
               : st.status === 'error'   ? '<span class="agent-icon error-flash">&#10007;</span>'
               :                           '<span class="agent-icon idle">&#9679;</span>';
    const cls = `agent-tile ${st.status}`;
    return `<div class="${cls}">${icon}<span class="agent-name">${esc(a.label)}</span></div>`;
  }).join('');
}

// Keep legacy exports so existing callers don't break
export function addLog() {}
export function clearLog() {}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
