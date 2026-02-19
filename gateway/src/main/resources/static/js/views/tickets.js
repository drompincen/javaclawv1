import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';

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
  }

  renderFiltered('ALL');

  document.getElementById('ticketStatusFilter')?.addEventListener('change', (e) => {
    renderFiltered(e.target.value);
  });
}
