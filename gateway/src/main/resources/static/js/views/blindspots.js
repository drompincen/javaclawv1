import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { toast } from '../components/toast.js';

const SEVERITY_COLORS = { CRITICAL: 'bad', HIGH: 'warn', MEDIUM: '', LOW: 'good' };

let expandedId = null;

export async function render() {
  expandedId = null;
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'BLINDSPOTS';
  document.getElementById('centerSub').textContent =
    'Blindspots are risks, gaps, and issues detected by the Reconcile Agent.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Project Blindspots</b><div class="tiny">detected by reconcile-agent \u2022 click to expand</div></div>
      </div>
      <div class="cardB" id="blindspotList"><div class="tiny">Loading...</div></div>
    </div>`;

  if (!pid) {
    document.getElementById('blindspotList').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const blindspots = await api.blindspots.list(pid);
    const container = document.getElementById('blindspotList');

    if (blindspots.length === 0) {
      container.innerHTML = '<div class="tiny">No blindspots detected. Run the intake pipeline with multi-source data to generate blindspots.</div>';
      return;
    }

    container.innerHTML = '';
    blindspots.forEach(b => {
      const el = document.createElement('div');
      el.className = 'event';
      el.style.cursor = 'pointer';
      el.dataset.blindspotId = b.blindspotId;
      const sevClass = SEVERITY_COLORS[b.severity] || '';

      el.innerHTML = `
        <div class="eventTop">
          <div style="min-width:0;flex:1">
            <div class="eventTitle"><span class="expand-indicator">\u25B8</span>${esc(b.title)}</div>
          </div>
          <div style="display:flex;gap:4px;align-items:center">
            <span class="pill ${sevClass}">${b.severity || '?'}</span>
            <span class="chip">${b.category || '?'}</span>
            <span class="pill">${b.status || 'OPEN'}</span>
          </div>
        </div>
        <div class="blindspot-detail" style="display:none"></div>`;

      const detailDiv = el.querySelector('.blindspot-detail');

      el.addEventListener('click', () => {
        setSelected({ type: 'blindspot', id: b.blindspotId, data: b });

        if (expandedId === b.blindspotId) {
          // Collapse
          expandedId = null;
          detailDiv.style.display = 'none';
          el.querySelector('.expand-indicator').textContent = '\u25B8';
        } else {
          // Collapse previous
          if (expandedId) {
            const prev = container.querySelector(`[data-blindspot-id="${expandedId}"]`);
            if (prev) {
              prev.querySelector('.blindspot-detail').style.display = 'none';
              prev.querySelector('.expand-indicator').textContent = '\u25B8';
            }
          }
          // Expand this
          expandedId = b.blindspotId;
          el.querySelector('.expand-indicator').textContent = '\u25BE';

          let detailHtml = `<div style="font-weight:700;font-size:11px;color:var(--muted);margin-bottom:6px">WHY THIS IS A BLINDSPOT:</div>`;
          detailHtml += `<div class="tiny" style="white-space:pre-wrap;margin-bottom:8px">${esc(b.description || 'No description available.')}</div>`;

          // Source refs
          if (b.sourceRefs && b.sourceRefs.length > 0) {
            detailHtml += `<div class="tiny" style="margin-bottom:4px"><b>Sources:</b></div>`;
            b.sourceRefs.forEach(ref => {
              const label = ref.type ? `${ref.type}:${ref.id || ''}` : (ref.id || JSON.stringify(ref));
              detailHtml += `<div class="tiny">\u2022 ${esc(label)}</div>`;
            });
          }

          // Delta pack / reconcile refs
          const refs = [];
          if (b.deltaPackId) refs.push(`delta-pack: ${b.deltaPackId}`);
          if (b.reconcileRunId) refs.push(`reconcile-run: ${b.reconcileRunId}`);
          if (refs.length > 0) {
            detailHtml += `<div class="tiny" style="margin-top:4px">${esc(refs.join(' \u2022 '))}</div>`;
          }

          // Owner + created
          const meta = [];
          if (b.owner) meta.push(`Owner: ${b.owner}`);
          else meta.push('Owner: unassigned');
          if (b.createdAt) meta.push(`Detected: ${new Date(b.createdAt).toLocaleDateString()}`);
          detailHtml += `<div class="tiny" style="margin-top:4px;color:var(--muted)">${esc(meta.join(' \u2022 '))}</div>`;

          detailDiv.innerHTML = detailHtml;
          detailDiv.style.display = 'block';
        }
      });

      container.appendChild(el);
    });
  } catch {
    document.getElementById('blindspotList').innerHTML = '<div class="tiny">Could not load blindspots.</div>';
  }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
