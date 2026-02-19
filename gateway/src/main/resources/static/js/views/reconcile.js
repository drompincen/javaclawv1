import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { runAgent } from '../panels/agents.js';

const SEVERITY_COLORS = { CRITICAL: '#e74c3c', HIGH: '#e67e22', MEDIUM: '#f1c40f', LOW: '#95a5a6' };
const TYPE_COLORS = {
  MISSING_EPIC: '#9b59b6', DATE_DRIFT: '#3498db', OWNER_MISMATCH: '#e67e22',
  ORPHANED_WORK: '#e74c3c', COVERAGE_GAP: '#2ecc71'
};

function pill(text, color) {
  return `<span style="display:inline-block;padding:2px 8px;border-radius:10px;font-size:11px;color:#fff;background:${color || '#666'}">${text}</span>`;
}

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
      <div class="cardB" id="deltaBody"></div>
    </div>
    <div class="card" style="margin-top:16px">
      <div class="cardH">
        <div><b>Blindspots</b><div class="tiny">gaps, risks, and issues discovered during reconciliation</div></div>
      </div>
      <div class="cardB" id="blindspotBody"></div>
    </div>`;

  document.getElementById('runReconcileAgent')?.addEventListener('click', () => runAgent('reconcile_agent'));

  if (!pid) {
    document.getElementById('deltaBody').innerHTML = '<div class="tiny">Select a project first.</div>';
    document.getElementById('blindspotBody').innerHTML = '';
    return;
  }

  // Load delta packs
  try {
    const packs = await api.deltaPacks.list(pid);
    const container = document.getElementById('deltaBody');
    if (packs.length === 0) {
      container.innerHTML = '<div class="tiny">No delta packs yet. Run the Reconcile Agent to generate one.</div>';
    } else {
      const latest = packs[0];
      const deltas = latest.deltas || [];
      if (deltas.length === 0) {
        container.innerHTML = '<div class="tiny">Latest delta pack has no deltas. Status: ' + (latest.status || '\u2013') + '</div>';
      } else {
        let html = '<table class="table"><thead><tr><th>Title</th><th>Type</th><th>Severity</th><th>Description</th><th>Suggested Action</th></tr></thead><tbody>';
        deltas.forEach(d => {
          html += `<tr>
            <td>${esc(d.title || '')}</td>
            <td>${pill(d.deltaType || '', TYPE_COLORS[d.deltaType] || '#666')}</td>
            <td>${pill(d.severity || '', SEVERITY_COLORS[d.severity] || '#666')}</td>
            <td>${esc(d.description || '')}</td>
            <td>${esc(d.suggestedAction || '')}</td>
          </tr>`;
        });
        html += '</tbody></table>';
        if (latest.summary) {
          html += `<div class="tiny" style="margin-top:8px">Total: ${latest.summary.totalDeltas || deltas.length} deltas | Status: ${latest.status || '\u2013'}</div>`;
        }
        container.innerHTML = html;
      }
    }
  } catch {
    document.getElementById('deltaBody').innerHTML = '<div class="tiny">Could not load delta packs.</div>';
  }

  // Load blindspots
  try {
    const spots = await api.blindspots.list(pid);
    const container = document.getElementById('blindspotBody');
    if (spots.length === 0) {
      container.innerHTML = '<div class="tiny">No blindspots detected.</div>';
    } else {
      const rows = spots.map(s => ({
        title: s.title || '',
        category: s.category || '',
        severity: s.severity || '',
        status: s.status || '',
        _raw: s
      }));
      renderTable(container, ['title', 'category', 'severity', 'status'], rows, (row) => {
        setSelected({ type: 'blindspot', id: row._raw.blindspotId, data: row._raw });
      });
    }
  } catch {
    document.getElementById('blindspotBody').innerHTML = '<div class="tiny">Could not load blindspots.</div>';
  }
}

function esc(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}
