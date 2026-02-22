import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { runAgent } from '../panels/agents.js';
import { toast } from '../components/toast.js';
import { initSplitter } from '../components/splitter.js';

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
function isoToDate(v) { return v ? v.slice(0, 10) : ''; }
function dateToIso(v) { return v ? v + 'T00:00:00Z' : null; }

const PHASE_STATUSES = ['NOT_STARTED', 'PENDING', 'ACTIVE', 'IN_PROGRESS', 'COMPLETED', 'BLOCKED'];
const MILESTONE_STATUSES = ['UPCOMING', 'ON_TRACK', 'AT_RISK', 'MISSED', 'COMPLETED'];

let selectedPhaseId = null;
let selectedMilestoneId = null;

export async function render() {
  selectedPhaseId = null;
  selectedMilestoneId = null;
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'PLANS';
  document.getElementById('centerSub').textContent =
    'Plans are generated/updated from threads + milestones; Plan Agent keeps phase definitions consistent.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Plan Phases</b><div class="tiny">phases + milestones + entry/exit criteria</div></div>
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
    const [phaseList, milestoneList] = await Promise.all([
      api.phases.list(pid),
      api.milestones.list(pid).catch(() => []),
    ]);
    const countEl = document.getElementById('phaseCount');
    if (countEl) countEl.textContent = 'phases: ' + phaseList.length;

    // Group milestones by phaseId
    const milestonesByPhase = {};
    const unlinkedMilestones = [];
    milestoneList.forEach(ms => {
      if (ms.phaseId) {
        (milestonesByPhase[ms.phaseId] = milestonesByPhase[ms.phaseId] || []).push(ms);
      } else {
        unlinkedMilestones.push(ms);
      }
    });

    const planBody = document.getElementById('planBody');
    if (phaseList.length === 0 && milestoneList.length === 0) {
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
      } else if (phaseList.length > 0) {
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

      // Render phases with nested milestones
      phaseList.forEach((ph, idx) => {
        const ev = document.createElement('div');
        ev.className = 'event';
        ev.style.cursor = 'pointer';
        const date = ph.endDate ? new Date(ph.endDate).toLocaleDateString() : '\u2013';
        ev.innerHTML = `
          <div class="eventTop">
            <div style="min-width:0;flex:1">
              <div class="eventTitle">${esc(ph.name || 'Phase ' + (idx + 1))}</div>
              <div class="tiny">Status: ${ph.status || '\u2013'} \u2022 Order: ${ph.sortOrder || 0}</div>
            </div>
            <div style="display:flex;gap:4px;align-items:center">
              <span class="pill warn">end: ${esc(date)}</span>
              <button class="btn danger phase-delete-btn" style="padding:2px 6px;font-size:11px" title="Delete">\u00d7</button>
            </div>
          </div>`;
        ev.addEventListener('click', (e) => {
          if (e.target.closest('.phase-delete-btn') || e.target.closest('.ms-row')) return;
          selectedMilestoneId = null;
          if (selectedPhaseId === ph.phaseId) {
            selectedPhaseId = null;
            document.getElementById('readingPane').style.display = 'none';
          } else {
            selectedPhaseId = ph.phaseId;
            setSelected({ type: 'phase', id: ph.phaseId, data: ph });
            renderPhaseReadingPane(pid, ph);
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

        // Nested milestones for this phase
        const phaseMilestones = milestonesByPhase[ph.phaseId] || [];
        phaseMilestones.forEach(ms => {
          const msEl = createMilestoneRow(pid, ms);
          planBody.appendChild(msEl);
        });
      });

      // Unlinked milestones section
      if (unlinkedMilestones.length > 0) {
        const sep = document.createElement('div');
        sep.className = 'hr';
        planBody.appendChild(sep);

        const label = document.createElement('div');
        label.className = 'sectionLabel';
        label.textContent = 'UNLINKED MILESTONES';
        planBody.appendChild(label);

        unlinkedMilestones.forEach(ms => {
          const msEl = createMilestoneRow(pid, ms);
          planBody.appendChild(msEl);
        });
      }
    }
  } catch {
    document.getElementById('planBody').innerHTML = '<div class="tiny">Could not load phases.</div>';
  }
}

function statusPillClass(status) {
  const s = (status || '').toUpperCase();
  if (s === 'COMPLETED' || s === 'ON_TRACK') return 'good';
  if (s === 'AT_RISK' || s === 'IN_PROGRESS' || s === 'ACTIVE') return 'warn';
  if (s === 'MISSED' || s === 'BLOCKED') return 'bad';
  return '';
}

function createMilestoneRow(pid, ms) {
  const row = document.createElement('div');
  row.className = 'event ms-row';
  row.style.cursor = 'pointer';
  row.style.borderLeft = '3px solid var(--muted)';
  row.style.marginLeft = '16px';
  row.style.fontSize = '12px';
  const targetDate = ms.targetDate ? new Date(ms.targetDate).toLocaleDateString() : '\u2013';
  const pillClass = statusPillClass(ms.status);
  row.innerHTML = `
    <div class="eventTop">
      <div style="min-width:0;flex:1">
        <div class="eventTitle" style="font-size:12px">${esc(ms.name || 'Milestone')}</div>
        <div class="tiny">${ms.owner ? 'Owner: ' + esc(ms.owner) + ' \u2022 ' : ''}Target: ${esc(targetDate)}</div>
      </div>
      <div style="display:flex;gap:4px;align-items:center">
        <span class="pill ${pillClass}">${esc(ms.status || '\u2013')}</span>
        <button class="btn danger ms-delete-btn" style="padding:2px 6px;font-size:11px" title="Delete">\u00d7</button>
      </div>
    </div>`;
  row.addEventListener('click', (e) => {
    if (e.target.closest('.ms-delete-btn')) return;
    selectedPhaseId = null;
    if (selectedMilestoneId === ms.id) {
      selectedMilestoneId = null;
      document.getElementById('readingPane').style.display = 'none';
    } else {
      selectedMilestoneId = ms.id;
      setSelected({ type: 'milestone', id: ms.id, data: ms });
      renderMilestoneReadingPane(pid, ms);
    }
  });
  row.querySelector('.ms-delete-btn').addEventListener('click', async (e) => {
    e.stopPropagation();
    if (!confirm('Delete milestone "' + (ms.name || '') + '"?')) return;
    try {
      await api.milestones.delete(pid, ms.id);
      toast('milestone deleted');
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      render();
    } catch (err) { toast('delete failed: ' + err.message); }
  });
  return row;
}

// ── Phase reading pane ──
function renderPhaseReadingPane(pid, ph) {
  const pane = document.getElementById('readingPane');
  pane.style.display = 'block';

  const entry = (ph.entryCriteria || []);
  const exit = (ph.exitCriteria || []);

  let html = `<div class="reading-pane-divider">Phase Detail</div>`;
  html += `<div class="reading-pane">`;
  html += `<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">`;
  html += `<b style="font-size:14px">${esc(ph.name || 'Untitled')}</b>`;
  html += `<span class="pill ${statusPillClass(ph.status)}">${esc(ph.status || '\u2013')}</span>`;
  html += `</div>`;

  if (ph.description) {
    html += `<div class="hr"></div><div class="sectionLabel">Description</div>`;
    html += `<div class="reading-pane-content">${esc(ph.description)}</div>`;
  }

  if (ph.startDate || ph.endDate) {
    html += `<div class="hr"></div><div class="sectionLabel">Dates</div>`;
    html += `<div class="tiny">Start: ${ph.startDate ? isoToDate(ph.startDate) : '\u2013'} \u2022 End: ${ph.endDate ? isoToDate(ph.endDate) : '\u2013'}</div>`;
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
  html += `<div class="row" style="gap:8px"><button class="btn" id="rpEditPhase">Edit</button><button class="btn danger" id="rpDelPhase">Delete</button></div>`;
  html += `</div>`;
  pane.innerHTML = html;

  pane.querySelector('#rpEditPhase')?.addEventListener('click', () => showPhaseEditModal(pid, ph));
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

// ── Milestone reading pane ──
function renderMilestoneReadingPane(pid, ms) {
  const pane = document.getElementById('readingPane');
  pane.style.display = 'block';

  const pillClass = statusPillClass(ms.status);

  let html = `<div class="reading-pane-divider">Milestone Detail</div>`;
  html += `<div class="reading-pane">`;
  html += `<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">`;
  html += `<b style="font-size:14px">${esc(ms.name || 'Untitled')}</b>`;
  html += `<span class="pill ${pillClass}">${esc(ms.status || '\u2013')}</span>`;
  html += `</div>`;

  if (ms.description) {
    html += `<div class="hr"></div><div class="sectionLabel">Description</div>`;
    html += `<div class="reading-pane-content">${esc(ms.description)}</div>`;
  }

  html += `<div class="hr"></div><div class="sectionLabel">Dates</div>`;
  html += `<div class="tiny">Target: ${ms.targetDate ? isoToDate(ms.targetDate) : '\u2013'} \u2022 Actual: ${ms.actualDate ? isoToDate(ms.actualDate) : '\u2013'}</div>`;

  if (ms.owner) {
    html += `<div class="hr"></div><div class="sectionLabel">Owner</div>`;
    html += `<div class="tiny">${esc(ms.owner)}</div>`;
  }

  if (ms.phaseId) {
    html += `<div class="hr"></div><div class="tiny">phaseId: ${esc(ms.phaseId)}</div>`;
  }

  html += `<div class="hr"></div>`;
  html += `<div class="row" style="gap:8px"><button class="btn" id="rpEditMs">Edit</button><button class="btn danger" id="rpDelMs">Delete</button></div>`;
  html += `</div>`;
  pane.innerHTML = html;

  pane.querySelector('#rpEditMs')?.addEventListener('click', () => showMilestoneEditModal(pid, ms));
  pane.querySelector('#rpDelMs')?.addEventListener('click', async () => {
    if (!confirm('Delete this milestone?')) return;
    try {
      await api.milestones.delete(pid, ms.id);
      toast('milestone deleted');
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      render();
    } catch (e) { toast('delete failed: ' + e.message); }
  });
}

// ── Phase edit modal ──
function showPhaseEditModal(pid, ph) {
  document.getElementById('editModalOverlay')?.remove();

  const overlay = document.createElement('div');
  overlay.id = 'editModalOverlay';
  overlay.className = 'help-overlay';
  overlay.style.display = 'flex';

  const statusOpts = PHASE_STATUSES.map(s =>
    `<option value="${s}" ${(ph.status || '').toUpperCase() === s ? 'selected' : ''}>${s}</option>`
  ).join('');

  overlay.innerHTML = `
    <div class="help-modal" style="max-width:460px">
      <h2>Edit Phase</h2>
      <div class="help-section">
        <label class="tiny" style="display:block;margin-bottom:4px">Name</label>
        <input type="text" id="emName" class="input" style="width:100%;margin-bottom:10px" value="${esc(ph.name || '')}" />
        <label class="tiny" style="display:block;margin-bottom:4px">Description</label>
        <textarea id="emDesc" class="input" style="width:100%;height:80px;margin-bottom:10px">${esc(ph.description || '')}</textarea>
        <label class="tiny" style="display:block;margin-bottom:4px">Status</label>
        <select id="emStatus" class="select" style="width:100%;margin-bottom:10px">${statusOpts}</select>
        <div style="display:flex;gap:10px">
          <div style="flex:1">
            <label class="tiny" style="display:block;margin-bottom:4px">Start Date</label>
            <input type="date" id="emStart" class="input" style="width:100%" value="${isoToDate(ph.startDate)}" />
          </div>
          <div style="flex:1">
            <label class="tiny" style="display:block;margin-bottom:4px">End Date</label>
            <input type="date" id="emEnd" class="input" style="width:100%" value="${isoToDate(ph.endDate)}" />
          </div>
        </div>
      </div>
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:14px">
        <button class="btn ghost" id="emCancel">Cancel</button>
        <button class="btn" id="emSave">Save</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.remove(); });
  document.getElementById('emCancel').addEventListener('click', () => overlay.remove());
  document.getElementById('emSave').addEventListener('click', async () => {
    const data = {
      name: document.getElementById('emName').value.trim(),
      description: document.getElementById('emDesc').value.trim(),
      status: document.getElementById('emStatus').value,
      startDate: dateToIso(document.getElementById('emStart').value),
      endDate: dateToIso(document.getElementById('emEnd').value),
    };
    try {
      await api.phases.update(pid, ph.phaseId, data);
      toast('phase updated');
      overlay.remove();
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      render();
    } catch (e) {
      toast('save failed: ' + e.message);
    }
  });

  setTimeout(() => document.getElementById('emName')?.focus(), 50);
}

// ── Milestone edit modal ──
function showMilestoneEditModal(pid, ms) {
  document.getElementById('editModalOverlay')?.remove();

  const overlay = document.createElement('div');
  overlay.id = 'editModalOverlay';
  overlay.className = 'help-overlay';
  overlay.style.display = 'flex';

  const statusOpts = MILESTONE_STATUSES.map(s =>
    `<option value="${s}" ${(ms.status || '').toUpperCase() === s ? 'selected' : ''}>${s}</option>`
  ).join('');

  overlay.innerHTML = `
    <div class="help-modal" style="max-width:460px">
      <h2>Edit Milestone</h2>
      <div class="help-section">
        <label class="tiny" style="display:block;margin-bottom:4px">Name</label>
        <input type="text" id="emName" class="input" style="width:100%;margin-bottom:10px" value="${esc(ms.name || '')}" />
        <label class="tiny" style="display:block;margin-bottom:4px">Description</label>
        <textarea id="emDesc" class="input" style="width:100%;height:80px;margin-bottom:10px">${esc(ms.description || '')}</textarea>
        <label class="tiny" style="display:block;margin-bottom:4px">Status</label>
        <select id="emStatus" class="select" style="width:100%;margin-bottom:10px">${statusOpts}</select>
        <div style="display:flex;gap:10px;margin-bottom:10px">
          <div style="flex:1">
            <label class="tiny" style="display:block;margin-bottom:4px">Target Date</label>
            <input type="date" id="emTarget" class="input" style="width:100%" value="${isoToDate(ms.targetDate)}" />
          </div>
          <div style="flex:1">
            <label class="tiny" style="display:block;margin-bottom:4px">Actual Date</label>
            <input type="date" id="emActual" class="input" style="width:100%" value="${isoToDate(ms.actualDate)}" />
          </div>
        </div>
        <label class="tiny" style="display:block;margin-bottom:4px">Owner</label>
        <input type="text" id="emOwner" class="input" style="width:100%" value="${esc(ms.owner || '')}" />
      </div>
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:14px">
        <button class="btn ghost" id="emCancel">Cancel</button>
        <button class="btn" id="emSave">Save</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.remove(); });
  document.getElementById('emCancel').addEventListener('click', () => overlay.remove());
  document.getElementById('emSave').addEventListener('click', async () => {
    const data = {
      name: document.getElementById('emName').value.trim(),
      description: document.getElementById('emDesc').value.trim(),
      status: document.getElementById('emStatus').value,
      targetDate: dateToIso(document.getElementById('emTarget').value),
      actualDate: dateToIso(document.getElementById('emActual').value),
      owner: document.getElementById('emOwner').value.trim(),
    };
    try {
      await api.milestones.update(pid, ms.id, data);
      toast('milestone updated');
      overlay.remove();
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      render();
    } catch (e) {
      toast('save failed: ' + e.message);
    }
  });

  setTimeout(() => document.getElementById('emName')?.focus(), 50);
}
