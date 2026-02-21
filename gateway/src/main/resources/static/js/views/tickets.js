import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { toast } from '../components/toast.js';
import { initSplitter } from '../components/splitter.js';

const STATUSES = ['ALL', 'TODO', 'IN_PROGRESS', 'REVIEW', 'DONE', 'BLOCKED'];
function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

function parseAssignee(ticket) {
  if (ticket.owner) return ticket.owner;
  if (!ticket.description) return '';
  const m = ticket.description.match(/(?:Assignee|Owner|Assigned to)\s*:\s*(.+)/i);
  return m ? m[1].trim() : '';
}

let selectedTicketId = null;

export async function render() {
  selectedTicketId = null;
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
      <div id="readingPane" style="display:none"></div>
    </div>`;

  initSplitter(body.querySelector('.card'));

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
      assignee: parseAssignee(t),
      externalref: t.externalRef || '',
      updated: t.updatedAt ? new Date(t.updatedAt).toLocaleTimeString() : '',
      _raw: t
    }));
    renderTable(
      document.getElementById('ticketTableContainer'),
      ['title', 'status', 'priority', 'assignee', 'externalRef', 'updated'],
      rows,
      (row) => {
        const t = row._raw;
        if (selectedTicketId === t.ticketId) {
          selectedTicketId = null;
          document.getElementById('readingPane').style.display = 'none';
        } else {
          selectedTicketId = t.ticketId;
          setSelected({ type: 'ticket', id: t.ticketId, data: t });
          renderReadingPane(pid, t);
        }
      }
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
            document.dispatchEvent(new CustomEvent('jc:data-changed'));
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

function renderReadingPane(pid, d) {
  const pane = document.getElementById('readingPane');
  pane.style.display = 'block';

  let html = `<div class="reading-pane-divider">Ticket Detail</div>`;
  html += `<div class="reading-pane">`;
  html += `<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">`;
  html += `<b style="font-size:14px">${esc(d.title || 'Untitled')}</b>`;
  html += `<div style="display:flex;gap:4px"><span class="pill">${esc(d.status || '')}</span><span class="pill">${esc(d.priority || '')}</span></div>`;
  html += `</div>`;

  if (d.description) {
    html += `<div class="hr"></div><div class="sectionLabel">Description</div>`;
    html += `<div class="reading-pane-content">${esc(d.description)}</div>`;
  }

  html += `<div class="hr"></div>`;
  html += `<div class="tiny">assignee: ${esc(parseAssignee(d) || 'unassigned')}</div>`;
  html += `<div class="tiny">externalRef: ${esc(d.externalRef || '-')}</div>`;
  if ((d.blockedBy || []).length > 0) {
    html += `<div class="tiny">blockedBy: ${d.blockedBy.join(', ')}</div>`;
  }
  if (d.storyPoints != null) {
    html += `<div class="tiny">storyPoints: ${d.storyPoints}</div>`;
  }
  if (d.updatedAt) {
    html += `<div class="tiny">updated: ${new Date(d.updatedAt).toLocaleString()}</div>`;
  }

  html += `<div class="hr"></div>`;
  html += `<div class="row"><button class="btn danger" id="rpDelTicket">Delete</button></div>`;
  html += `</div>`;
  pane.innerHTML = html;

  pane.querySelector('#rpDelTicket')?.addEventListener('click', async () => {
    if (!confirm('Delete this ticket?')) return;
    try {
      await api.tickets.delete(pid, d.ticketId);
      toast('ticket deleted');
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      render();
    } catch (e) { toast('delete failed: ' + e.message); }
  });
}
