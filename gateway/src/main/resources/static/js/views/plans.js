import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { runAgent } from '../panels/agents.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'PLANS';
  document.getElementById('centerSub').textContent =
    'Plans are generated/updated from threads + milestones; Plan Agent keeps phase definitions consistent.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Plan Phases</b><div class="tiny">phases + entry/exit criteria</div></div>
        <div class="row">
          <button class="btn" id="runPlanAgent">Run Plan Agent</button>
          <span class="pill" id="phaseCount">phases: 0</span>
        </div>
      </div>
      <div class="cardB" id="planBody"></div>
    </div>`;

  document.getElementById('runPlanAgent')?.addEventListener('click', () => runAgent('plan_agent'));

  if (!pid) {
    document.getElementById('planBody').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const phaseList = await api.phases.list(pid);
    const countEl = document.getElementById('phaseCount');
    if (countEl) countEl.textContent = 'phases: ' + phaseList.length;

    const planBody = document.getElementById('planBody');
    if (phaseList.length === 0) {
      planBody.innerHTML = '<div class="tiny">No plan phases yet. Provide milestones or Smartsheet dump in Intake.</div>';
    } else {
      planBody.innerHTML = '';
      phaseList.forEach((ph, idx) => {
        const ev = document.createElement('div');
        ev.className = 'event';
        ev.style.cursor = 'pointer';
        const entry = (ph.entryCriteria || []).join(', ') || '–';
        const exit = (ph.exitCriteria || []).join(', ') || '–';
        const date = ph.endDate ? new Date(ph.endDate).toLocaleDateString() : '–';
        ev.innerHTML = `
          <div class="eventTop">
            <div class="eventTitle">${esc(ph.name || 'Phase ' + (idx + 1))}</div>
            <span class="pill warn">milestone: ${esc(date)}</span>
          </div>
          <div class="tiny" style="margin-top:6px"><b>Entry:</b> ${esc(entry)}</div>
          <div class="tiny"><b>Exit:</b> ${esc(exit)}</div>
          <div class="tiny">Status: ${ph.status || '–'}</div>`;
        ev.addEventListener('click', () => setSelected({ type: 'phase', id: ph.phaseId, data: ph }));
        planBody.appendChild(ev);
      });
    }
  } catch {
    document.getElementById('planBody').innerHTML = '<div class="tiny">Could not load phases.</div>';
  }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
