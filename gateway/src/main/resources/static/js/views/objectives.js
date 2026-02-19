import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { runAgent } from '../panels/agents.js';

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
          <button class="btn" id="runPmAgent">Run PM Agent</button>
          <button class="btn" id="runObjAgent">Run Objective Agent</button>
        </div>
      </div>
      <div class="cardB" id="objTableContainer"></div>
    </div>`;

  document.getElementById('runPmAgent')?.addEventListener('click', () => runAgent('pm_agent'));
  document.getElementById('runObjAgent')?.addEventListener('click', () => runAgent('objective_agent'));

  if (!pid) {
    document.getElementById('objTableContainer').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const objs = await api.objectives.list(pid);
    const rows = objs.map(o => ({
      sprint: o.sprintName || '',
      objective: o.outcome || '',
      coverage: o.coveragePercent != null ? o.coveragePercent + '%' : '–',
      unmapped: (o.ticketIds || []).length,
      risk: (o.risks || []).length > 0 ? 'Yes' : 'Low',
      status: o.status || '',
      _raw: o
    }));
    renderTable(
      document.getElementById('objTableContainer'),
      ['sprint', 'objective', 'coverage', 'unmapped', 'risk', 'status'],
      rows,
      (row) => setSelected({ type: 'objective', id: row._raw.objectiveId, data: row._raw })
    );
  } catch {
    document.getElementById('objTableContainer').innerHTML = '<div class="tiny">Could not load objectives.</div>';
  }
}
