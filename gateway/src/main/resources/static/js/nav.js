import { getState, setView } from './state.js';

const WORKSPACES = [
  { key: 'intake',       label: 'Intake',        icon: '\u{1F4E5}', badgeId: 'navIntakeBadge' },
  { key: 'threads',      label: 'Threads',       icon: '\u{1F9F5}', badgeId: 'navThreadsBadge' },
  { key: 'tickets',      label: 'Tickets',       icon: '\u{1F3AB}', badgeId: 'navTicketsBadge' },
  { key: 'resources',    label: 'Resources',     icon: '\u{1F465}', badgeId: 'navResBadge' },
  { key: 'objectives',   label: 'Objectives',    icon: '\u{1F3AF}', badgeId: 'navObjBadge' },
  { key: 'sprinthealth', label: 'Sprint Health', icon: '\u{1F3C3}', badgeId: 'navSprintBadge' },
  { key: 'plans',        label: 'Plans',         icon: '\u{1F9E9}', badgeId: 'navPlansBadge' },
  { key: 'reminders',    label: 'Reminders',     icon: '\u{23F1}',  badgeId: 'navRemBadge' },
  { key: 'checklists',   label: 'Checklists',    icon: '\u{2611}',  badgeId: 'navChkBadge' },
  { key: 'reconcile',    label: 'Reconcile',     icon: '\u{1F504}', badgeId: 'navRecBadge' },
  { key: 'blindspots',   label: 'Blindspots',    icon: '\u{1F441}', badgeId: 'navBlindBadge' },
  { key: 'links',        label: 'LinkHub',       icon: '\u{1F517}', badgeId: 'navLinksBadge' },
  { key: 'scheduler',    label: 'Scheduler',     icon: '\u23F0',    badgeId: 'navSchedBadge' },
];

export function initNav() {
  const container = document.getElementById('nav');
  if (!container) return;
  container.innerHTML = '';
  WORKSPACES.forEach(ws => {
    const el = document.createElement('div');
    el.className = 'navItem' + (getState().currentView === ws.key ? ' active' : '');
    el.dataset.view = ws.key;
    el.innerHTML = `<span>${ws.icon} ${ws.label}</span><span class="badge" id="${ws.badgeId}">0</span>`;
    el.addEventListener('click', () => setView(ws.key));
    container.appendChild(el);
  });
}

export function highlightNav() {
  const view = getState().currentView;
  document.querySelectorAll('#nav .navItem').forEach(n => {
    n.classList.toggle('active', n.dataset.view === view);
  });
}

export function updateNavBadge(key, value) {
  const ws = WORKSPACES.find(w => w.key === key);
  if (!ws) return;
  const el = document.getElementById(ws.badgeId);
  if (el) el.textContent = value;
}
