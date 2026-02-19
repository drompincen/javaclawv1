import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { toast } from '../components/toast.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'THREADS';
  document.getElementById('centerSub').textContent =
    'Threads are named + created by the Thread Agent from intake clustering (topic + continuity + dates).';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Project Threads</b><div class="tiny">createdBy: thread-agent \u2022 select to inspect</div></div>
      </div>
      <div class="cardB" id="threadTableContainer"></div>
    </div>`;

  if (!pid) {
    document.getElementById('threadTableContainer').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const threads = await api.threads.list(pid);
    const rows = threads.map(t => ({
      title: t.title || 'Untitled',
      summary: t.summary || '',
      status: t.status || t.lifecycle || 'DRAFT',
      decisions: (t.decisions || []).length,
      actions: (t.actions || []).length,
      updated: t.updatedAt ? new Date(t.updatedAt).toLocaleTimeString() : '',
      _raw: t
    }));
    renderTable(
      document.getElementById('threadTableContainer'),
      ['title', 'summary', 'status', 'decisions', 'actions', 'updated'],
      rows,
      (row) => setSelected({ type: 'thread', id: row._raw.threadId, data: row._raw })
    );

    // Append delete button to each row
    const tbody = document.querySelector('#threadTableContainer tbody');
    if (tbody) {
      // Add header cell for delete column
      const thead = document.querySelector('#threadTableContainer thead tr');
      if (thead) {
        const th = document.createElement('th');
        th.textContent = '';
        thead.appendChild(th);
      }
      tbody.querySelectorAll('tr').forEach((tr, idx) => {
        if (idx >= rows.length) return;
        const td = document.createElement('td');
        const btn = document.createElement('button');
        btn.className = 'btn danger';
        btn.textContent = '\u2715';
        btn.title = 'Delete thread';
        btn.style.padding = '2px 8px';
        btn.addEventListener('click', async (e) => {
          e.stopPropagation();
          const row = rows[idx];
          if (!confirm(`Delete thread "${row.title}"?`)) return;
          try {
            await api.threads.delete(pid, row._raw.threadId);
            toast('thread deleted');
            render();
          } catch (err) {
            toast('delete failed: ' + err.message);
          }
        });
        td.appendChild(btn);
        tr.appendChild(td);
      });
    }
  } catch {
    document.getElementById('threadTableContainer').innerHTML = '<div class="tiny">Could not load threads.</div>';
  }
}
