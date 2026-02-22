import * as api from './api.js';
import * as ws from './ws.js';
import { getState, setProject, setView, onChange, setWsConnected } from './state.js';
import { initNav, highlightNav, updateNavBadge } from './nav.js';
import { initAgentPanel, initTimerPanel } from './panels/agents.js';
import { initActivityStream } from './panels/activity.js';
import { toast } from './components/toast.js';

// View modules (lazy-ish: all loaded upfront as ES modules)
import * as intakeView from './views/intake.js';
import * as threadsView from './views/threads.js';
import * as ticketsView from './views/tickets.js';
import * as resourcesView from './views/resources.js';
import * as objectivesView from './views/objectives.js';
import * as sprintHealthView from './views/sprinthealth.js';
import * as plansView from './views/plans.js';
import * as remindersView from './views/reminders.js';
import * as checklistsView from './views/checklists.js';
import * as reconcileView from './views/reconcile.js';
import * as blindspotsView from './views/blindspots.js';
import * as linksView from './views/links.js';
import * as schedulerView from './views/scheduler.js';

const VIEW_MAP = {
  intake:       intakeView,
  threads:      threadsView,
  tickets:      ticketsView,
  resources:    resourcesView,
  objectives:   objectivesView,
  sprinthealth: sprintHealthView,
  plans:        plansView,
  reminders:    remindersView,
  checklists:   checklistsView,
  reconcile:    reconcileView,
  blindspots:   blindspotsView,
  links:        linksView,
  scheduler:    schedulerView,
};

let eventCount = 0;

// ── Provider badge ──
async function refreshProviderBadge() {
  const badge = document.getElementById('providerBadge');
  if (!badge) return;
  try {
    const data = await api.config.getProvider();
    const provider = data.provider || '';
    if (!provider || provider.toLowerCase().includes('no api key') || provider.toLowerCase().includes('none')) {
      badge.textContent = 'NO KEY';
      badge.className = 'badge bad';
    } else {
      badge.textContent = provider;
      badge.className = 'badge good';
    }
  } catch {
    badge.textContent = 'NO KEY';
    badge.className = 'badge bad';
  }
}

// ── API key modal (Ctrl-K) ──
function showApiKeyModal() {
  // Remove existing modal if any
  document.getElementById('apiKeyOverlay')?.remove();

  const overlay = document.createElement('div');
  overlay.id = 'apiKeyOverlay';
  overlay.className = 'help-overlay';
  overlay.style.display = 'flex';
  overlay.innerHTML = `
    <div class="help-modal" style="max-width:420px">
      <h2>API Keys</h2>
      <div class="help-section">
        <label class="tiny" style="display:block;margin-bottom:4px">Anthropic API Key</label>
        <input type="text" id="akAnthropicKey" class="input" style="width:100%;margin-bottom:12px" placeholder="sk-ant-..." />
        <label class="tiny" style="display:block;margin-bottom:4px">OpenAI API Key</label>
        <input type="text" id="akOpenaiKey" class="input" style="width:100%" placeholder="sk-..." />
      </div>
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:14px">
        <button class="btn ghost" id="akCancel">Cancel</button>
        <button class="btn" id="akSave">Save</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  // Pre-populate with masked keys
  api.config.getKeys().then(data => {
    document.getElementById('akAnthropicKey').value = data.anthropicKey || '';
    document.getElementById('akOpenaiKey').value = data.openaiKey || '';
  }).catch(() => {});

  overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.remove(); });
  document.getElementById('akCancel').addEventListener('click', () => overlay.remove());
  document.getElementById('akSave').addEventListener('click', async () => {
    const anthropicKey = document.getElementById('akAnthropicKey').value.trim();
    const openaiKey = document.getElementById('akOpenaiKey').value.trim();
    try {
      await api.config.setKeys({ anthropicKey, openaiKey });
      toast('API keys updated');
      overlay.remove();
      refreshProviderBadge();
    } catch (e) {
      toast('Failed to save keys: ' + e.message);
    }
  });

  // Focus first input
  setTimeout(() => document.getElementById('akAnthropicKey')?.focus(), 50);
}

// ── Init ──
document.addEventListener('DOMContentLoaded', async () => {
  initNav();
  initAgentPanel();
  initTimerPanel();  // async — runs in background, does not block init
  initActivityStream();
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
    // Count EVENT messages as activity proxy
    if (msg.type === 'EVENT') {
      eventCount++;
    }
  });

  // Data-mutation events that should trigger UI refresh
  const DATA_EVENTS = new Set([
    'THREAD_CREATED', 'THREAD_RENAMED', 'THREAD_MERGED',
    'TICKET_CREATED', 'TICKET_UPDATED',
    'BLINDSPOT_CREATED', 'BLINDSPOT_ACKNOWLEDGED', 'BLINDSPOT_RESOLVED',
    'OBJECTIVE_UPDATED', 'OBJECTIVE_COVERAGE_COMPUTED',
    'PHASE_CREATED', 'PHASE_UPDATED',
    'MILESTONE_CREATED', 'MILESTONE_UPDATED',
    'CHECKLIST_CREATED', 'CHECKLIST_UPDATED', 'CHECKLIST_COMPLETED',
    'RESOURCE_ASSIGNED', 'RESOURCE_OVERLOADED',
    'LINK_CREATED', 'LINK_UPDATED',
    'OBJECTIVE_CREATED', 'OBJECTIVE_DELETED',
    'REMINDER_CREATED',
    'DELTA_PACK_CREATED',
    'INTAKE_PIPELINE_COMPLETED',
    'MEMORY_STORED', 'MEMORY_DISTILLED',
    'SCHEDULE_CREATED', 'SCHEDULE_UPDATED',
    'PROJECT_CREATED',
  ]);

  let _refreshTimer = null;
  ws.onEvent((msg) => {
    if (msg.type !== 'EVENT' || !msg.payload) return;
    const evType = msg.payload.type || '';
    if (!DATA_EVENTS.has(evType)) return;

    // Debounce: refresh after 1.5s of quiet (avoids rapid re-renders during pipeline)
    clearTimeout(_refreshTimer);
    _refreshTimer = setTimeout(async () => {
      await renderCurrentView();
      await refreshNavBadges();
      loadProjects();
      refreshTokenCounter();
    }, 1500);
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
      // Show/hide share button
      const shareBtn = document.getElementById('shareProject');
      if (shareBtn) shareBtn.style.display = pid ? '' : 'none';
    }
    if (changeType === 'step') {
      const el = document.getElementById('stepBadge');
      if (el) el.textContent = 'STEP ' + getState().stepCount;
    }
  });

  // Wire up topbar controls
  document.getElementById('runController')?.addEventListener('click', () => {
    toast('controller run');
  });

  // Share project link
  document.getElementById('shareProject')?.addEventListener('click', () => {
    const pid = getState().currentProjectId;
    if (!pid) return;
    const sel = document.getElementById('projectSelect');
    const name = sel?.options[sel.selectedIndex]?.textContent || pid;
    const url = `${location.origin}${location.pathname}?project=${encodeURIComponent(name)}`;
    navigator.clipboard.writeText(url).then(() => toast('link copied to clipboard')).catch(() => toast('copy failed'));
  });
  // Help modal (F1)
  const helpOverlay = document.getElementById('helpOverlay');
  function toggleHelp() {
    if (!helpOverlay) return;
    helpOverlay.style.display = helpOverlay.style.display === 'none' ? 'flex' : 'none';
  }
  document.getElementById('toggleHelp')?.addEventListener('click', toggleHelp);
  document.getElementById('helpClose')?.addEventListener('click', toggleHelp);
  helpOverlay?.addEventListener('click', (e) => { if (e.target === helpOverlay) toggleHelp(); });

  // Keyboard shortcuts
  document.addEventListener('keydown', (e) => {
    if (e.key === 'F7') { e.preventDefault(); setView('intake'); setTimeout(() => document.getElementById('intakeText')?.focus(), 50); }
    if (e.key === 'F3') { e.preventDefault(); document.getElementById('runController')?.click(); }
    if (e.key === 'F1') { e.preventDefault(); toggleHelp(); }
    if (e.key === 'Escape' && helpOverlay?.style.display !== 'none') { toggleHelp(); }
    if (e.key === 'Escape') { document.getElementById('apiKeyOverlay')?.remove(); }
    if (e.key === 'k' && e.ctrlKey && !e.altKey && !e.metaKey) { e.preventDefault(); showApiKeyModal(); }
    // N = new project (only when not in a text field)
    if (e.key === 'N' && !e.ctrlKey && !e.altKey && !e.metaKey) {
      const tag = document.activeElement?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      e.preventDefault();
      createNewProject();
    }
  });

  // Token counter + project poll fallback + provider badge
  refreshTokenCounter();
  refreshProviderBadge();
  setInterval(refreshTokenCounter, 10000);
  setInterval(loadProjects, 10000);
  setInterval(refreshProviderBadge, 10000);

  // Show share button if project already selected
  if (getState().currentProjectId) {
    const shareBtn = document.getElementById('shareProject');
    if (shareBtn) shareBtn.style.display = '';
  }

  // Listen for in-app data changes (create/delete/update from views)
  document.addEventListener('jc:data-changed', () => refreshNavBadges());

  // Render initial view then refresh badges
  await renderCurrentView();
  await refreshNavBadges();
});

// ── New project ──
async function createNewProject() {
  const name = prompt('New project name:');
  if (!name || !name.trim()) return;
  try {
    const created = await api.projects.create({ name: name.trim() });
    toast('project created: ' + name.trim());
    await loadProjects();
    const sel = document.getElementById('projectSelect');
    if (sel && created.projectId) {
      sel.value = created.projectId;
      setProject(created.projectId);
    }
  } catch (e) {
    toast('create failed: ' + e.message);
  }
}

// ── Project selector ──
let _projectListenerBound = false;
async function loadProjects() {
  const sel = document.getElementById('projectSelect');
  if (!sel) return;

  try {
    const projectList = await api.projects.list();
    const prevValue = sel.value;
    sel.innerHTML = '<option value="">— select project —</option>';
    projectList.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p.projectId;
      opt.textContent = p.name || p.projectId;
      sel.appendChild(opt);
    });

    // Restore previously selected project (from URL param or localStorage)
    const savedId = getState().currentProjectId || prevValue;
    if (savedId) {
      // Support both project ID and project name in the URL
      const match = projectList.find(p => p.projectId === savedId || p.name === savedId);
      if (match) {
        sel.value = match.projectId;
        if (getState().currentProjectId !== match.projectId) {
          setProject(match.projectId);
        }
      }
    }

    if (!_projectListenerBound) {
      _projectListenerBound = true;
      sel.addEventListener('change', () => {
        const pid = sel.value || null;
        setProject(pid);
        if (pid) {
          toast('project set: ' + (sel.options[sel.selectedIndex]?.textContent || pid));
        }
      });
    }
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

// ── Token counter ──
async function refreshTokenCounter() {
  const el = document.getElementById('tokenCount');
  if (!el) return;
  try {
    const m = await api.logs.metrics();
    const tokens = m.recentTokens ?? 0;
    const calls = m.totalInteractions ?? 0;
    const msgs = m.totalMessages ?? 0;
    const avg = m.avgDurationMs != null ? Math.round(m.avgDurationMs) : 0;
    el.textContent = `\u{1FA99} ${tokens} tok | ${msgs} msgs | ${calls} calls | avg ${avg}ms`;
  } catch { /* metrics endpoint not available yet */ }
}

// ── Nav badge refresh ──
async function refreshNavBadges() {
  const pid = getState().currentProjectId;
  if (!pid) return;

  const safe = async (fn) => { try { return await fn(); } catch { return []; } };

  const [threadList, ticketList, resourceList, objList, phaseList, remList, chkList, linkList, recList, blindList, schedList] = await Promise.all([
    safe(() => api.threads.list(pid)),
    safe(() => api.tickets.list(pid)),
    safe(() => api.resources.list()),
    safe(() => api.objectives.list(pid)),
    safe(() => api.phases.list(pid)),
    safe(() => api.reminders.list(pid)),
    safe(() => api.checklists.list(pid)),
    safe(() => api.links.list(pid)),
    safe(() => api.reconciliations.list(pid)),
    safe(() => api.blindspots.list(pid)),
    safe(() => api.schedules.list()),
  ]);

  updateNavBadge('intake', 'READY');
  updateNavBadge('threads', threadList.length);
  updateNavBadge('tickets', ticketList.length);
  updateNavBadge('resources', resourceList.length);
  updateNavBadge('objectives', objList.length);
  updateNavBadge('sprinthealth', objList.length > 0 ? '\u2661' : '0');
  updateNavBadge('plans', phaseList.length);
  updateNavBadge('reminders', remList.length);
  updateNavBadge('checklists', chkList.length);
  updateNavBadge('links', linkList.length);
  updateNavBadge('reconcile', recList.length > 0 ? '\u0394' : '0');
  updateNavBadge('blindspots', blindList.length);
  const enabledCount = Array.isArray(schedList) ? schedList.filter(s => s.enabled).length : 0;
  updateNavBadge('scheduler', enabledCount);
}
