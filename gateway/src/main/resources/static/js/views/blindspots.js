import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { toast } from '../components/toast.js';
import { initSplitter } from '../components/splitter.js';

const SEVERITY_COLORS = { CRITICAL: 'bad', HIGH: 'warn', MEDIUM: '', LOW: 'good' };

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

let selectedId = null;

export async function render() {
  selectedId = null;
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
      <div id="readingPane" style="display:none"></div>
    </div>`;

  initSplitter(body.querySelector('.card'));

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

    const rows = blindspots.map(b => ({
      title: b.title || '',
      severity: b.severity || '?',
      category: b.category || '?',
      status: b.status || 'OPEN',
      _raw: b
    }));
    renderTable(
      container,
      ['title', 'severity', 'category', 'status'],
      rows,
      (row) => {
        const b = row._raw;
        if (selectedId === b.blindspotId) {
          selectedId = null;
          document.getElementById('readingPane').style.display = 'none';
        } else {
          selectedId = b.blindspotId;
          setSelected({ type: 'blindspot', id: b.blindspotId, data: b });
          renderReadingPane(pid, b);
        }
      }
    );

    // Add delete buttons to each row
    const tableEl = container.querySelector('table');
    if (tableEl) {
      const thead = tableEl.querySelector('thead tr');
      if (thead) { const th = document.createElement('th'); th.textContent = ''; thead.appendChild(th); }
      const bodyRows = tableEl.querySelectorAll('tbody tr');
      bodyRows.forEach((tr, i) => {
        const td = document.createElement('td');
        if (!blindspots[i]) { tr.appendChild(td); return; }
        const btn = document.createElement('button');
        btn.className = 'btn danger';
        btn.style.cssText = 'padding:2px 6px;font-size:11px;';
        btn.textContent = '\u00d7';
        btn.title = 'Delete blindspot';
        btn.addEventListener('click', async (e) => {
          e.stopPropagation();
          if (!confirm('Delete blindspot "' + (blindspots[i].title || '') + '"?')) return;
          try {
            await api.blindspots.delete(pid, blindspots[i].blindspotId);
            toast('blindspot deleted');
            document.dispatchEvent(new CustomEvent('jc:data-changed'));
            render();
          } catch (err) { toast('delete failed: ' + err.message); }
        });
        td.appendChild(btn);
        tr.appendChild(td);
      });
    }
  } catch {
    document.getElementById('blindspotList').innerHTML = '<div class="tiny">Could not load blindspots.</div>';
  }
}

function renderReadingPane(pid, b) {
  const pane = document.getElementById('readingPane');
  pane.style.display = 'block';

  const sevClass = SEVERITY_COLORS[b.severity] || '';

  let html = `<div class="reading-pane-divider">Blindspot Detail</div>`;
  html += `<div class="reading-pane">`;
  html += `<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">`;
  html += `<b style="font-size:14px">${esc(b.title)}</b>`;
  html += `<div style="display:flex;gap:4px"><span class="pill ${sevClass}">${b.severity || '?'}</span><span class="chip">${b.category || '?'}</span><span class="pill">${b.status || 'OPEN'}</span></div>`;
  html += `</div>`;

  if (b.description) {
    html += `<div class="hr"></div><div class="sectionLabel">Why This Is A Blindspot</div>`;
    html += `<div class="reading-pane-content" style="white-space:pre-wrap">${esc(b.description)}</div>`;
  }

  if (b.sourceRefs && b.sourceRefs.length > 0) {
    html += `<div class="hr"></div><div class="sectionLabel">Sources</div>`;
    b.sourceRefs.forEach(ref => {
      const label = ref.type ? `${ref.type}:${ref.id || ''}` : (ref.id || JSON.stringify(ref));
      html += `<div class="tiny">\u2022 ${esc(label)}</div>`;
    });
  }

  const refs = [];
  if (b.deltaPackId) refs.push(`delta-pack: ${b.deltaPackId}`);
  if (b.reconcileRunId) refs.push(`reconcile-run: ${b.reconcileRunId}`);
  if (refs.length > 0) {
    html += `<div class="tiny" style="margin-top:4px">${esc(refs.join(' \u2022 '))}</div>`;
  }

  const meta = [];
  if (b.owner) meta.push(`Owner: ${b.owner}`);
  else meta.push('Owner: unassigned');
  if (b.createdAt) meta.push(`Detected: ${new Date(b.createdAt).toLocaleDateString()}`);
  html += `<div class="hr"></div><div class="tiny" style="color:var(--muted)">${esc(meta.join(' \u2022 '))}</div>`;

  html += `<div class="hr"></div>`;
  html += `<div class="row"><button class="btn danger" id="rpDelBlindspot">Delete</button></div>`;
  html += `</div>`;
  pane.innerHTML = html;

  pane.querySelector('#rpDelBlindspot')?.addEventListener('click', async () => {
    if (!confirm('Delete this blindspot?')) return;
    try {
      await api.blindspots.delete(pid, b.blindspotId);
      toast('blindspot deleted');
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      render();
    } catch (e) { toast('delete failed: ' + e.message); }
  });
}
