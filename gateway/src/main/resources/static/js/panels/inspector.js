import { getState, onChange } from '../state.js';

export function initInspector() {
  onChange((changeType) => {
    if (changeType === 'selected') renderInspector();
  });
}

export function renderInspector() {
  const box = document.getElementById('inspector');
  if (!box) return;

  const sel = getState().selectedEntity;
  if (!sel || !sel.data) {
    box.innerHTML = '<div class="tiny">Select a thread/objective/plan/reminder to see details.</div>';
    return;
  }

  const d = sel.data;
  switch (sel.type) {
    case 'thread':
      box.innerHTML = `
        <div><b>Thread</b>: ${esc(d.title)}</div>
        <div class="tiny">status: ${d.lifecycle || '–'} • updated: ${d.updatedAt || '–'} • createdBy: ${d.createdBy || '–'}</div>
        <div class="hr"></div>
        <div class="tiny">evidenceRefs: ${(d.evidenceRefs || []).length}</div>
        <div class="tiny">ideaIds: ${(d.ideaIds || []).length}</div>
        <div class="tiny">objectiveIds: ${(d.objectiveIds || []).join(', ') || '–'}</div>`;
      break;

    case 'objective':
      box.innerHTML = `
        <div><b>Objective</b>: ${esc(d.sprintName)}</div>
        <div class="tiny">${esc(d.outcome)}</div>
        <div class="hr"></div>
        <div class="tiny">coveragePct: ${d.coveragePercent != null ? d.coveragePercent + '%' : '–'}</div>
        <div class="tiny">measurableSignal: ${esc(d.measurableSignal)}</div>
        <div class="tiny">risks: ${(d.risks || []).join(', ') || 'none'}</div>
        <div class="tiny">status: ${d.status || '–'}</div>`;
      break;

    case 'phase':
      box.innerHTML = `
        <div><b>Phase</b>: ${esc(d.name)}</div>
        <div class="tiny">${esc(d.description)}</div>
        <div class="hr"></div>
        <div class="tiny"><b>Entry</b>: ${(d.entryCriteria || []).join(', ') || '–'}</div>
        <div class="tiny"><b>Exit</b>: ${(d.exitCriteria || []).join(', ') || '–'}</div>
        <div class="tiny">status: ${d.status || '–'} • sortOrder: ${d.sortOrder || 0}</div>`;
      break;

    case 'reminder':
      box.innerHTML = `
        <div><b>Reminder</b></div>
        <div class="tiny">${esc(d.message)}</div>
        <div class="hr"></div>
        <div class="tiny">triggerAt: ${d.triggerAt || '–'}</div>
        <div class="tiny">type: ${d.type || '–'}</div>
        <div class="tiny">recurring: ${d.recurring ? 'Yes' : 'No'}</div>
        <div class="tiny">triggered: ${d.triggered ? 'Yes' : 'No'}</div>`;
      break;

    case 'checklist':
      const items = d.items || [];
      box.innerHTML = `
        <div><b>Checklist</b></div>
        <div class="tiny">${esc(d.name)} • status: ${d.status || '–'}</div>
        <div class="hr"></div>
        ${items.map(i => `<div class="tiny">${i.checked ? '\u2611' : '\u2610'} ${esc(i.text)}${i.assignee ? ' (' + esc(i.assignee) + ')' : ''}</div>`).join('')}
        ${items.length === 0 ? '<div class="tiny">No items.</div>' : ''}`;
      break;

    case 'reconciliation':
      const conflicts = d.conflicts || [];
      const mappings = d.mappings || [];
      box.innerHTML = `
        <div><b>Reconciliation</b></div>
        <div class="tiny">status: ${d.status || '–'} • source: ${d.sourceType || '–'}</div>
        <div class="hr"></div>
        <div class="tiny">mappings: ${mappings.length}</div>
        <div class="tiny">conflicts: ${conflicts.length}</div>
        ${conflicts.map(c => `<div class="tiny">\u2022 ${esc(c.field)}: ${esc(c.sourceValue)} vs ${esc(c.ticketValue)}</div>`).join('')}`;
      break;

    case 'link':
      box.innerHTML = `
        <div><b>Link</b></div>
        <div class="tiny">${esc(d.title)}</div>
        <div class="hr"></div>
        <div class="tiny">url: <a href="${esc(d.url)}" target="_blank" style="color:var(--accent)">${esc(d.url)}</a></div>
        <div class="tiny">category: ${esc(d.category)}</div>
        <div class="tiny">pinned: ${d.pinned ? 'Yes' : 'No'}</div>
        <div class="tiny">tags: ${(d.tags || []).join(', ') || '–'}</div>`;
      break;

    default:
      box.innerHTML = `<div class="tiny">No inspector for type: ${esc(sel.type)}</div>`;
  }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
