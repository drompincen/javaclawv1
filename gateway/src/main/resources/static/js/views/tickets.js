import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { toast } from '../components/toast.js';

const STATUSES = ['ALL', 'TODO', 'IN_PROGRESS', 'REVIEW', 'DONE', 'BLOCKED'];

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'TICKETS';
  document.getElementById('centerSub').textContent =
    'All tickets for the current project. Filter by status.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Tickets</b><div class="tiny">click to inspect</div></div>
        <div class="row">
          <select class="select" id="ticketStatusFilter" style="min-width:140px">
            ${STATUSES.map(s => `<option value="${s}">${s}</option>`).join('')}
          </select>
        </div>
      </div>
      <div class="cardB" id="ticketTableContainer"></div>
    </div>`;

  if (!pid) {
    document.getElementById('ticketTableContainer').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  let allTickets = [];
  try {
    allTickets = await api.tickets.list(pid);
  } catch {
    document.getElementById('ticketTableContainer').innerHTML = '<div class="tiny">Could not load tickets.</div>';
    return;
  }

  function renderFiltered(status) {
    const filtered = status === 'ALL' ? allTickets : allTickets.filter(t => t.status === status);
    const rows = filtered.map(t => ({
      title: t.title || 'Untitled',
      status: t.status || '',
      priority: t.priority || '',
      assignee: t.assignee || '',
      externalref: t.externalRef || '',
      updated: t.updatedAt ? new Date(t.updatedAt).toLocaleTimeString() : '',
      _raw: t
    }));
    renderTable(
      document.getElementById('ticketTableContainer'),
      ['title', 'status', 'priority', 'assignee', 'externalRef', 'updated'],
      rows,
      (row) => setSelected({ type: 'ticket', id: row._raw.ticketId, data: row._raw })
    );

    // Append delete button to each row
    const tableEl = document.getElementById('ticketTableContainer').querySelector('table');
    if (tableEl) {
      const thead = tableEl.querySelector('thead tr');
      if (thead) { const th = document.createElement('th'); th.textContent = ''; thead.appendChild(th); }
      const bodyRows = tableEl.querySelectorAll('tbody tr');
      bodyRows.forEach((tr, i) => {
        const td = document.createElement('td');
        if (!filtered[i]) { tr.appendChild(td); return; }
        const btn = document.createElement('button');
        btn.className = 'btn danger';
        btn.style.cssText = 'padding:2px 6px;font-size:11px;';
        btn.textContent = '\u00d7';
        btn.title = 'Delete ticket';
        btn.addEventListener('click', async (e) => {
          e.stopPropagation();
          if (!confirm('Delete ticket "' + (filtered[i].title || '') + '"?')) return;
          try {
            await api.tickets.delete(pid, filtered[i].ticketId);
            const idx = allTickets.findIndex(t => t.ticketId === filtered[i].ticketId);
            if (idx >= 0) allTickets.splice(idx, 1);
            toast('ticket deleted');
            renderFiltered(document.getElementById('ticketStatusFilter')?.value || 'ALL');
          } catch (err) { toast('delete failed: ' + err.message); }
        });
        td.appendChild(btn);
        tr.appendChild(td);
      });
    }
  }

  renderFiltered('ALL');

  document.getElementById('ticketStatusFilter')?.addEventListener('change', (e) => {
    renderFiltered(e.target.value);
  });
}
