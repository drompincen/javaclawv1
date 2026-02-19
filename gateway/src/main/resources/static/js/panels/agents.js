import * as api from '../api.js';
import { getState, incrementStep } from '../state.js';
import { toast } from '../components/toast.js';
import { addLog } from './activity.js';

const AGENTS = [
  { id: 'reconcile-agent',  label: 'Reconcile' },
  { id: 'resource-agent',   label: 'Resource' },
  { id: 'objective-agent',  label: 'Objective' },
  { id: 'checklist-agent',  label: 'Checklist' },
  { id: 'plan-agent',       label: 'Plan' },
  { id: 'thread-extractor', label: 'Extractor' },
];

export function initAgentPanel() {
  const container = document.getElementById('agentButtons');
  if (!container) return;

  container.innerHTML = '<div class="tiny">Pipeline handles agents automatically during intake. Use timers below for scheduled runs.</div>';
}

// Schedule groups: checkbox → list of schedule IDs
const TIMER_GROUPS = {
  tArtifacts: ['default-checklist-agent', 'default-plan-agent'],
  tReminders: ['default-thread-extractor'],
  tReconcile: ['default-reconcile-agent', 'default-objective-agent'],
};

export async function initTimerPanel() {
  const applyBtn = document.getElementById('applyTimers');
  if (!applyBtn) return;

  // Load real schedule state
  let scheduleMap = {};
  try {
    const list = await api.schedules.list();
    list.forEach(s => { scheduleMap[s.scheduleId] = s; });
  } catch { /* backend may not be ready */ }

  // Set checkbox state from real schedules
  for (const [checkboxId, scheduleIds] of Object.entries(TIMER_GROUPS)) {
    const el = document.getElementById(checkboxId);
    if (el) {
      el.checked = scheduleIds.every(id => scheduleMap[id]?.enabled);
    }
  }
  updateTimerStatus();

  applyBtn.addEventListener('click', async () => {
    applyBtn.disabled = true;
    const updates = [];
    for (const [checkboxId, scheduleIds] of Object.entries(TIMER_GROUPS)) {
      const checked = document.getElementById(checkboxId)?.checked || false;
      scheduleIds.forEach(id => {
        updates.push(api.schedules.update(id, { enabled: checked }));
      });
    }
    try {
      await Promise.all(updates);
      toast('timers updated');
      addLog('TIMERS_UPDATED', 'info');
    } catch (e) {
      toast('timer update failed: ' + e.message);
    }
    applyBtn.disabled = false;
    updateTimerStatus();
  });
}

function updateTimerStatus() {
  const any = ['tArtifacts', 'tReminders', 'tReconcile']
      .some(id => document.getElementById(id)?.checked);
  const statusEl = document.getElementById('timerStatus');
  if (statusEl) {
    statusEl.textContent = any ? 'timers: on' : 'timers: off';
    statusEl.className = 'pill ' + (any ? 'warn' : '');
  }
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
