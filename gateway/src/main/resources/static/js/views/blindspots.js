import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { toast } from '../components/toast.js';

const SEVERITY_COLORS = { CRITICAL: 'bad', HIGH: 'warn', MEDIUM: '', LOW: 'good' };

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'BLINDSPOTS';
  document.getElementById('centerSub').textContent =
    'Blindspots are risks, gaps, and issues detected by the Reconcile Agent.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Project Blindspots</b><div class="tiny">detected by reconcile-agent</div></div>
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
      const sevClass = SEVERITY_COLORS[b.severity] || '';
      el.innerHTML = `
        <div class="eventTop">
          <div style="min-width:0">
            <div class="eventTitle">${esc(b.title)}</div>
            <div class="tiny">${esc(b.description || '')}</div>
          </div>
          <div style="display:flex;gap:4px;align-items:center">
            <span class="pill ${sevClass}">${b.severity || '?'}</span>
            <span class="chip">${b.category || '?'}</span>
            <span class="pill">${b.status || 'OPEN'}</span>
          </div>
        </div>`;
      el.addEventListener('click', () => setSelected({ type: 'blindspot', id: b.blindspotId, data: b }));
      container.appendChild(el);
    });
  } catch {
    document.getElementById('blindspotList').innerHTML = '<div class="tiny">Could not load blindspots.</div>';
  }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
