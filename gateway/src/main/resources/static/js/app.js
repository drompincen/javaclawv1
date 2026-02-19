import * as api from './api.js';
import * as ws from './ws.js';
import { getState, setProject, setView, onChange, setWsConnected } from './state.js';
import { initNav, highlightNav, updateNavBadge } from './nav.js';
import { initAgentPanel, initTimerPanel } from './panels/agents.js';
import { initActivityStream, addLog, clearLog } from './panels/activity.js';
import { initInspector } from './panels/inspector.js';
import { toast } from './components/toast.js';

// View modules (lazy-ish: all loaded upfront as ES modules)
import * as intakeView from './views/intake.js';
import * as threadsView from './views/threads.js';
import * as objectivesView from './views/objectives.js';
import * as plansView from './views/plans.js';
import * as remindersView from './views/reminders.js';
import * as checklistsView from './views/checklists.js';
import * as reconcileView from './views/reconcile.js';
import * as linksView from './views/links.js';

const VIEW_MAP = {
  intake:     intakeView,
  threads:    threadsView,
  objectives: objectivesView,
  plans:      plansView,
  reminders:  remindersView,
  checklists: checklistsView,
  reconcile:  reconcileView,
  links:      linksView,
};

// ── Init ──
document.addEventListener('DOMContentLoaded', async () => {
  initNav();
  initAgentPanel();
  initTimerPanel();
  initActivityStream();
  initInspector();

  // WebSocket
  ws.connect();
  ws.onEvent((msg) => {
    if (msg._type === 'ws_status') {
      setWsConnected(msg.connected);
      const badge = document.getElementById('wsBadge');
      if (badge) {
        badge.textContent = msg.connected ? 'WS LIVE' : 'WS OFF';
        badge.className = 'badge ' + (msg.connected ? 'good' : 'bad');
      }
    }
  });

  // Load projects into selector
  await loadProjects();

  // State change reactions
  onChange((changeType) => {
    if (changeType === 'view') {
      highlightNav();
      renderCurrentView();
    }
    if (changeType === 'project') {
      renderCurrentView();
      refreshNavBadges();
      // Subscribe to project events via WS
      const pid = getState().currentProjectId;
      if (pid) ws.subscribeProject(pid);
    }
    if (changeType === 'step') {
      const el = document.getElementById('stepBadge');
      if (el) el.textContent = 'STEP ' + getState().stepCount;
    }
  });

  // Wire up topbar controls
  document.getElementById('runController')?.addEventListener('click', () => {
    toast('controller run');
    addLog('AGENT_STEP_STARTED', 'info');
    addLog('CONTROLLER: chose agents (auto)', 'warn');
  });
  document.getElementById('clearLog')?.addEventListener('click', () => { clearLog(); toast('log cleared'); });
  document.getElementById('toggleHelp')?.addEventListener('click', () => {
    toast('F3 run controller \u2022 Intake: use project <name> \u2022 Right pane: run agents on-demand + timers');
  });

  // Keyboard shortcuts
  document.addEventListener('keydown', (e) => {
    if (e.key === 'F7') { e.preventDefault(); setView('intake'); setTimeout(() => document.getElementById('intakeText')?.focus(), 50); }
    if (e.key === 'F3') { e.preventDefault(); document.getElementById('runController')?.click(); }
    if (e.key === 'F1') { e.preventDefault(); document.getElementById('toggleHelp')?.click(); }
  });

  // Render initial view
  renderCurrentView();
  addLog('SYSTEM_READY', 'good');
});

// ── Project selector ──
async function loadProjects() {
  const sel = document.getElementById('projectSelect');
  if (!sel) return;

  try {
    const projectList = await api.projects.list();
    sel.innerHTML = '<option value="">— select project —</option>';
    projectList.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p.projectId;
      opt.textContent = p.name || p.projectId;
      sel.appendChild(opt);
    });

    // Restore previously selected project
    const savedId = getState().currentProjectId;
    if (savedId && projectList.find(p => p.projectId === savedId)) {
      sel.value = savedId;
      setProject(savedId);
    }

    sel.addEventListener('change', () => {
      const pid = sel.value || null;
      setProject(pid);
      if (pid) {
        toast('project set: ' + (sel.options[sel.selectedIndex]?.textContent || pid));
        addLog('CONTEXT: project=' + pid, 'info');
      }
    });
  } catch (e) {
    sel.innerHTML = '<option value="">Error loading projects</option>';
  }
}

// ── View rendering ──
async function renderCurrentView() {
  const view = getState().currentView;
  const mod = VIEW_MAP[view];
  if (mod && mod.render) {
    try {
      await mod.render();
    } catch (e) {
      console.error('View render error:', view, e);
      const body = document.getElementById('centerBody');
      if (body) body.innerHTML = `<div class="tiny">Error rendering ${view}: ${e.message}</div>`;
    }
  }
}

// ── Nav badge refresh ──
async function refreshNavBadges() {
  const pid = getState().currentProjectId;
  if (!pid) return;

  const safe = async (fn) => { try { return await fn(); } catch { return []; } };

  const [threadList, objList, phaseList, remList, chkList, linkList, recList] = await Promise.all([
    safe(() => api.threads.list(pid)),
    safe(() => api.objectives.list(pid)),
    safe(() => api.phases.list(pid)),
    safe(() => api.reminders.list(pid)),
    safe(() => api.checklists.list(pid)),
    safe(() => api.links.list(pid)),
    safe(() => api.reconciliations.list(pid)),
  ]);

  updateNavBadge('intake', 'READY');
  updateNavBadge('threads', threadList.length);
  updateNavBadge('objectives', objList.length);
  updateNavBadge('plans', phaseList.length);
  updateNavBadge('reminders', remList.length);
  updateNavBadge('checklists', chkList.length);
  updateNavBadge('links', linkList.length);
  updateNavBadge('reconcile', recList.length > 0 ? '\u0394' : '0');
}
