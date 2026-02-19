import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { runAgent } from '../panels/agents.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'RECONCILE';
  document.getElementById('centerSub').textContent =
    'Reconcile Agent aligns objectives \u2194 milestones \u2194 ticket dump and produces delta pack.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Delta Pack</b><div class="tiny">smartsheet \u2194 jira \u2194 objectives \u2194 milestones</div></div>
        <div class="row"><button class="btn" id="runReconcileAgent">Run Reconcile Agent</button></div>
      </div>
      <div class="cardB" id="reconcileBody"></div>
    </div>`;

  document.getElementById('runReconcileAgent')?.addEventListener('click', () => runAgent('reconcile_agent'));

  if (!pid) {
    document.getElementById('reconcileBody').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const recs = await api.reconciliations.list(pid);
    const container = document.getElementById('reconcileBody');
    if (recs.length === 0) {
      container.innerHTML = '<div class="tiny">No reconciliation runs yet. Run the Reconcile Agent to generate a delta pack.</div>';
    } else {
      // Show conflicts from the most recent reconciliation
      const latest = recs[0];
      const conflicts = latest.conflicts || [];
      if (conflicts.length === 0) {
        container.innerHTML = '<div class="tiny">Latest reconciliation has no conflicts. Status: ' + (latest.status || '–') + '</div>';
      } else {
        const rows = conflicts.map(c => ({
          delta: c.field || '',
          type: c.sourceValue || '',
          action: c.resolution || 'Resolve',
          _raw: c
        }));
        renderTable(container, ['delta', 'type', 'action'], rows, (row) => {
          setSelected({ type: 'reconciliation', id: latest.reconciliationId, data: latest });
        });
      }
    }
  } catch {
    document.getElementById('reconcileBody').innerHTML = '<div class="tiny">Could not load reconciliations.</div>';
  }
}
