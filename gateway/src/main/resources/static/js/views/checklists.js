import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { runAgent } from '../panels/agents.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'CHECKLISTS';
  document.getElementById('centerSub').textContent =
    'Checklist Agent generates ORR/release checklists from plan phases + operational standards.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Checklists</b><div class="tiny">attach to phases/releases</div></div>
        <div class="row"><button class="btn" id="runChecklistAgent">Run Checklist Agent</button></div>
      </div>
      <div class="cardB" id="chkBody"></div>
    </div>`;

  document.getElementById('runChecklistAgent')?.addEventListener('click', () => runAgent('checklist_agent'));

  if (!pid) {
    document.getElementById('chkBody').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const cls = await api.checklists.list(pid);
    const container = document.getElementById('chkBody');
    if (cls.length === 0) {
      container.innerHTML = '<div class="tiny">No checklists yet.</div>';
    } else {
      container.innerHTML = '';
      cls.forEach(c => {
        const items = c.items || [];
        const done = items.filter(i => i.checked).length;
        const total = items.length;
        const pct = total > 0 ? Math.round((done / total) * 100) : 0;

        const ev = document.createElement('div');
        ev.className = 'event';
        ev.style.cursor = 'pointer';
        ev.innerHTML = `
          <div class="eventTop">
            <div class="eventTitle">${esc(c.name || 'Untitled')}</div>
            <span class="pill ${pct === 100 ? 'good' : 'warn'}">${done}/${total}</span>
          </div>
          <div class="tiny" style="margin-top:6px">Status: ${c.status || '–'}</div>
          <div class="progress-track"><div class="progress-fill" style="width:${pct}%"></div></div>`;
        ev.addEventListener('click', () => setSelected({ type: 'checklist', id: c.checklistId, data: c }));
        container.appendChild(ev);
      });
    }
  } catch {
    document.getElementById('chkBody').innerHTML = '<div class="tiny">Could not load checklists.</div>';
  }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
