import * as api from '../api.js';
import { getState, incrementStep } from '../state.js';
import { toast } from '../components/toast.js';
import { addLog } from './activity.js';

const AGENTS = [
  { id: 'thread_agent',    label: 'Thread Agent' },
  { id: 'pm_agent',        label: 'PM Agent' },
  { id: 'plan_agent',      label: 'Plan Agent' },
  { id: 'reminder_agent',  label: 'Reminder Agent' },
  { id: 'checklist_agent', label: 'Checklist Agent' },
  { id: 'reconcile_agent', label: 'Reconcile Agent' },
  { id: 'distiller',       label: 'Distiller', primary: true },
];

export function initAgentPanel() {
  const container = document.getElementById('agentButtons');
  if (!container) return;

  container.innerHTML = '';
  AGENTS.forEach(a => {
    const btn = document.createElement('button');
    btn.className = 'btn' + (a.primary ? ' primary' : '');
    btn.textContent = a.label;
    btn.addEventListener('click', () => runAgent(a.id));
    container.appendChild(btn);
  });
}

export function initTimerPanel() {
  const applyBtn = document.getElementById('applyTimers');
  if (!applyBtn) return;

  applyBtn.addEventListener('click', () => {
    const artifacts = document.getElementById('tArtifacts')?.checked || false;
    const reminders = document.getElementById('tReminders')?.checked || false;
    const reconcile = document.getElementById('tReconcile')?.checked || false;
    const any = artifacts || reminders || reconcile;

    // Store in localStorage for now (scheduler agent will handle this later)
    localStorage.setItem('jc_timers', JSON.stringify({ artifacts, reminders, reconcile }));

    const statusEl = document.getElementById('timerStatus');
    if (statusEl) {
      statusEl.textContent = any ? 'timers: on' : 'timers: off';
      statusEl.className = 'pill ' + (any ? 'warn' : '');
    }
    toast('timers updated');
    addLog('TIMERS_UPDATED: ' + JSON.stringify({ artifacts, reminders, reconcile }), 'info');
  });

  // Restore timer state from localStorage
  try {
    const saved = JSON.parse(localStorage.getItem('jc_timers') || '{}');
    if (saved.artifacts) document.getElementById('tArtifacts').checked = true;
    if (saved.reminders) document.getElementById('tReminders').checked = true;
    if (saved.reconcile) document.getElementById('tReconcile').checked = true;
  } catch { /* ignore */ }
}

export async function runAgent(agentId) {
  const pid = getState().currentProjectId;
  if (!pid) { toast('select a project first'); return; }

  toast(agentId + ' starting...');
  addLog(agentId.toUpperCase() + '_STARTED', 'info');
  incrementStep();

  try {
    const session = await api.sessions.create({
      projectId: pid,
      metadata: { forcedAgentId: agentId }
    });
    await api.sessions.run(session.sessionId);
    addLog(agentId.toUpperCase() + '_RUNNING (session: ' + session.sessionId.substring(0, 8) + '...)', 'warn');
  } catch (e) {
    addLog(agentId.toUpperCase() + '_ERROR: ' + e.message, 'bad');
    toast(agentId + ' failed: ' + e.message);
  }
}
