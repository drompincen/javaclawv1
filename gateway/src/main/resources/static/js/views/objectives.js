import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { toast } from '../components/toast.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'OBJECTIVES';
  document.getElementById('centerSub').textContent =
    'Sprint objectives with coverage + unmapped tickets; PM agent keeps them aligned.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Sprint Objectives</b><div class="tiny">coverage % + unmapped tickets</div></div>
        <div class="row">
          <button class="btn" id="addObjToggle">Add Objective</button>
        </div>
      </div>
      <div id="addObjForm" style="display:none;padding:10px 12px;border-bottom:1px solid var(--line)">
        <div class="row" style="margin-bottom:6px">
          <input type="text" id="objSprint" placeholder="Sprint name" style="flex:1">
          <input type="date" id="objStart">
          <input type="date" id="objEnd">
        </div>
        <textarea id="objOutcome" placeholder="Outcome description..." style="min-height:60px"></textarea>
        <div class="row" style="margin-top:6px">
          <button class="btn primary" id="objSubmit">Create</button>
          <button class="btn ghost" id="objCancel">Cancel</button>
        </div>
      </div>
      <div class="cardB" id="objTableContainer"></div>
    </div>`;

  // Toggle form
  document.getElementById('addObjToggle')?.addEventListener('click', () => {
    const form = document.getElementById('addObjForm');
    form.style.display = form.style.display === 'none' ? 'block' : 'none';
  });
  document.getElementById('objCancel')?.addEventListener('click', () => {
    document.getElementById('addObjForm').style.display = 'none';
  });

  // Submit new objective
  document.getElementById('objSubmit')?.addEventListener('click', async () => {
    if (!pid) { toast('select a project first'); return; }
    const sprintName = document.getElementById('objSprint')?.value?.trim();
    const outcome = document.getElementById('objOutcome')?.value?.trim();
    const startDate = document.getElementById('objStart')?.value || null;
    const endDate = document.getElementById('objEnd')?.value || null;
    if (!sprintName || !outcome) { toast('sprint name + outcome required'); return; }
    try {
      await api.objectives.create(pid, { sprintName, outcome, startDate, endDate, status: 'PROPOSED' });
      toast('objective created');
      document.getElementById('addObjForm').style.display = 'none';
      render();
    } catch (e) {
      toast('create failed: ' + e.message);
    }
  });

  if (!pid) {
    document.getElementById('objTableContainer').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const objs = await api.objectives.list(pid);
    const rows = objs.map(o => ({
      sprint: o.sprintName || '',
      objective: o.outcome || '',
      coverage: o.coveragePercent != null ? o.coveragePercent + '%' : '\u2013',
      unmapped: (o.ticketIds || []).length,
      risk: (o.risks || []).length > 0 ? 'Yes' : 'Low',
      status: o.status || '',
      _raw: o
    }));
    renderTable(
      document.getElementById('objTableContainer'),
      ['sprint', 'objective', 'coverage', 'unmapped', 'risk', 'status', 'actions'],
      rows,
      (row) => setSelected({ type: 'objective', id: row._raw.objectiveId, data: row._raw })
    );

    // Inject inline action buttons into each row's actions cell
    const tableEl = document.getElementById('objTableContainer').querySelector('table');
    if (tableEl) {
      const bodyRows = tableEl.querySelectorAll('tbody tr');
      bodyRows.forEach((tr, i) => {
        const actionsCell = tr.querySelector('td:last-child');
        if (!actionsCell) return;
        actionsCell.innerHTML = '';
        const obj = objs[i];
        const objId = obj.objectiveId;

        const mkBtn = (label, cls, handler) => {
          const b = document.createElement('button');
          b.className = 'btn ' + cls;
          b.innerHTML = label;
          b.style.padding = '3px 6px';
          b.style.fontSize = '11px';
          b.addEventListener('click', async (e) => {
            e.stopPropagation();
            await handler();
          });
          return b;
        };

        actionsCell.style.whiteSpace = 'nowrap';
        actionsCell.appendChild(mkBtn('\u2713', '', async () => {
          await api.objectives.update(pid, objId, { ...obj, status: 'ACHIEVED' });
          toast('objective marked ACHIEVED');
          render();
        }));
        actionsCell.appendChild(mkBtn('\u26A0', '', async () => {
          const risks = [...(obj.risks || []), 'flagged at-risk manually'];
          await api.objectives.update(pid, objId, { ...obj, status: 'AT_RISK', risks });
          toast('objective marked AT_RISK');
          render();
        }));
        actionsCell.appendChild(mkBtn('\u2715', 'danger', async () => {
          if (!confirm('Delete this objective?')) return;
          await api.objectives.delete(pid, objId);
          toast('objective deleted');
          render();
        }));
      });
    }
  } catch {
    document.getElementById('objTableContainer').innerHTML = '<div class="tiny">Could not load objectives.</div>';
  }
}
