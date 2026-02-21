import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { runAgent } from '../panels/agents.js';
import { toast } from '../components/toast.js';
import { initSplitter } from '../components/splitter.js';

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

let selectedPhaseId = null;

export async function render() {
  selectedPhaseId = null;
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
      <div id="readingPane" style="display:none"></div>
    </div>`;

  initSplitter(body.querySelector('.card'));

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

      // Gantt chart for phases that have dates
      const datedPhases = phaseList.filter(ph => ph.startDate || ph.endDate);
      if (datedPhases.length > 0) {
        const allStarts = datedPhases.map(ph => new Date(ph.startDate || ph.endDate).getTime());
        const allEnds = datedPhases.map(ph => new Date(ph.endDate || ph.startDate).getTime());
        const earliest = Math.min(...allStarts);
        const latest = Math.max(...allEnds);
        const span = latest - earliest || 1;

        const gantt = document.createElement('div');
        gantt.className = 'gantt-container';

        datedPhases.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0)).forEach(ph => {
          const s = new Date(ph.startDate || ph.endDate).getTime();
          const e = new Date(ph.endDate || ph.startDate).getTime();
          const left = ((s - earliest) / span) * 100;
          const width = Math.max(((e - s) / span) * 100, 2);
          const statusClass = (ph.status || '').toUpperCase() === 'COMPLETE' ? 'good'
            : (ph.status || '').toUpperCase() === 'IN_PROGRESS' ? 'warn' : '';

          const row = document.createElement('div');
          row.className = 'gantt-row';
          row.innerHTML = `
            <div class="gantt-label">${esc(ph.name || 'Phase')}</div>
            <div class="gantt-track">
              <div class="gantt-bar ${statusClass}" style="left:${left}%;width:${width}%" title="${esc(ph.name || '')}"></div>
            </div>`;
          gantt.appendChild(row);
        });
        planBody.appendChild(gantt);

        const sep = document.createElement('div');
        sep.className = 'hr';
        planBody.appendChild(sep);
      } else {
        // Fallback: render relative Gantt for phases without dates
        const sorted = [...phaseList].sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
        const gantt = document.createElement('div');
        gantt.className = 'gantt-container';

        const label = document.createElement('div');
        label.className = 'tiny';
        label.style.marginBottom = '6px';
        label.textContent = 'Relative phase timeline (no dates set)';
        gantt.appendChild(label);

        const barWidth = Math.max(Math.floor(100 / sorted.length), 5);
        sorted.forEach((ph, idx) => {
          const statusClass = (ph.status || '').toUpperCase() === 'COMPLETE' ? 'good'
            : (ph.status || '').toUpperCase() === 'IN_PROGRESS' ? 'warn' : '';
          const left = idx * barWidth;
          const row = document.createElement('div');
          row.className = 'gantt-row';
          row.innerHTML = `
            <div class="gantt-label">${esc(ph.name || 'Phase')}</div>
            <div class="gantt-track">
              <div class="gantt-bar ${statusClass}" style="left:${left}%;width:${barWidth}%" title="${esc(ph.name || '')}"></div>
            </div>`;
          gantt.appendChild(row);
        });
        planBody.appendChild(gantt);

        const sep = document.createElement('div');
        sep.className = 'hr';
        planBody.appendChild(sep);
      }

      phaseList.forEach((ph, idx) => {
        const ev = document.createElement('div');
        ev.className = 'event';
        ev.style.cursor = 'pointer';
        const entry = (ph.entryCriteria || []).join(', ') || '\u2013';
        const exit = (ph.exitCriteria || []).join(', ') || '\u2013';
        const date = ph.endDate ? new Date(ph.endDate).toLocaleDateString() : '\u2013';
        ev.innerHTML = `
          <div class="eventTop">
            <div style="min-width:0;flex:1">
              <div class="eventTitle">${esc(ph.name || 'Phase ' + (idx + 1))}</div>
              <div class="tiny">Status: ${ph.status || '\u2013'} \u2022 Order: ${ph.sortOrder || 0}</div>
            </div>
            <div style="display:flex;gap:4px;align-items:center">
              <span class="pill warn">milestone: ${esc(date)}</span>
              <button class="btn danger phase-delete-btn" style="padding:2px 6px;font-size:11px" title="Delete">\u00d7</button>
            </div>
          </div>`;
        ev.addEventListener('click', (e) => {
          if (e.target.closest('.phase-delete-btn')) return;
          if (selectedPhaseId === ph.phaseId) {
            selectedPhaseId = null;
            document.getElementById('readingPane').style.display = 'none';
          } else {
            selectedPhaseId = ph.phaseId;
            setSelected({ type: 'phase', id: ph.phaseId, data: ph });
            renderReadingPane(pid, ph);
          }
        });
        ev.querySelector('.phase-delete-btn').addEventListener('click', async (e) => {
          e.stopPropagation();
          if (!confirm('Delete phase "' + (ph.name || '') + '"?')) return;
          try {
            await api.phases.delete(pid, ph.phaseId);
            toast('phase deleted');
            document.dispatchEvent(new CustomEvent('jc:data-changed'));
            render();
          } catch (err) { toast('delete failed: ' + err.message); }
        });
        planBody.appendChild(ev);
      });
    }
  } catch {
    document.getElementById('planBody').innerHTML = '<div class="tiny">Could not load phases.</div>';
  }
}

function renderReadingPane(pid, ph) {
  const pane = document.getElementById('readingPane');
  pane.style.display = 'block';

  const entry = (ph.entryCriteria || []);
  const exit = (ph.exitCriteria || []);

  let html = `<div class="reading-pane-divider">Phase Detail</div>`;
  html += `<div class="reading-pane">`;
  html += `<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">`;
  html += `<b style="font-size:14px">${esc(ph.name || 'Untitled')}</b>`;
  html += `<span class="pill">${esc(ph.status || '\u2013')}</span>`;
  html += `</div>`;

  if (ph.description) {
    html += `<div class="hr"></div><div class="sectionLabel">Description</div>`;
    html += `<div class="reading-pane-content">${esc(ph.description)}</div>`;
  }

  if (entry.length > 0) {
    html += `<div class="hr"></div><div class="sectionLabel">Entry Criteria</div>`;
    entry.forEach(c => { html += `<div class="tiny">\u2022 ${esc(c)}</div>`; });
  }
  if (exit.length > 0) {
    html += `<div class="hr"></div><div class="sectionLabel">Exit Criteria</div>`;
    exit.forEach(c => { html += `<div class="tiny">\u2022 ${esc(c)}</div>`; });
  }

  html += `<div class="hr"></div>`;
  html += `<div class="tiny">sortOrder: ${ph.sortOrder || 0}</div>`;
  html += `<div class="hr"></div>`;
  html += `<div class="row"><button class="btn danger" id="rpDelPhase">Delete</button></div>`;
  html += `</div>`;
  pane.innerHTML = html;

  pane.querySelector('#rpDelPhase')?.addEventListener('click', async () => {
    if (!confirm('Delete this phase?')) return;
    try {
      await api.phases.delete(pid, ph.phaseId);
      toast('phase deleted');
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      render();
    } catch (e) { toast('delete failed: ' + e.message); }
  });
}
