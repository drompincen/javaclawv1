import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { toast } from '../components/toast.js';
import { initSplitter } from '../components/splitter.js';

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

function parseAssignee(ticket) {
  if (ticket.owner) return ticket.owner;
  if (!ticket.description) return '';
  const m = ticket.description.match(/(?:Assignee|Owner|Assigned to)\s*:\s*(.+)/i);
  return m ? m[1].trim() : '';
}

let currentMode = 'all';
let selectedResourceId = null;

export async function render() {
  currentMode = 'all';
  selectedResourceId = null;
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'RESOURCES';
  document.getElementById('centerSub').textContent =
    'Team members, capacity, and availability. Paste names to bulk-add.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Team Resources</b><div class="tiny">click to inspect</div></div>
        <div class="row">
          <div class="view-tabs">
            <button class="view-tab active" data-mode="all">All</button>
            <button class="view-tab" data-mode="capacity">Capacity</button>
            <button class="view-tab" data-mode="overuse">Overuse</button>
          </div>
          <label class="toggle" style="padding:4px 8px"><input type="checkbox" id="resourceProjectOnly"/><span class="tiny">This project only</span></label>
        </div>
      </div>
      <div class="cardB" id="resourceTableContainer"></div>
      <div id="readingPane" style="display:none"></div>
    </div>
    <div style="height:10px"></div>
    <div class="card">
      <div class="cardH"><div><b>Paste-to-add</b><div class="tiny">one name per line</div></div></div>
      <div class="cardB">
        <textarea id="resourceNames" placeholder="Jane Doe&#10;John Smith&#10;..." style="min-height:80px"></textarea>
        <div class="row" style="margin-top:8px;">
          <button class="btn primary" id="addMissingBtn">Add Missing</button>
        </div>
      </div>
    </div>`;

  initSplitter(body.querySelector('.card'));

  let allResources = [];
  try {
    allResources = await api.resources.list();
  } catch {
    document.getElementById('resourceTableContainer').innerHTML = '<div class="tiny">Could not load resources.</div>';
    return;
  }

  function getFiltered(projectOnly) {
    return projectOnly && pid
      ? allResources.filter(r => r.projectId === pid)
      : allResources;
  }

  function selectResource(r) {
    if (selectedResourceId === r.resourceId) {
      selectedResourceId = null;
      document.getElementById('readingPane').style.display = 'none';
    } else {
      selectedResourceId = r.resourceId;
      setSelected({ type: 'resource', id: r.resourceId, data: r });
      renderReadingPane(r, pid);
    }
  }

  function renderAll(resources) {
    const rows = resources.map(r => ({
      name: r.name || '',
      role: r.role || '',
      capacity: r.capacity != null ? r.capacity : '',
      availability: r.availability != null ? r.availability : '',
      email: r.email || '',
      project: r.projectId || '',
      _raw: r
    }));
    renderTable(
      document.getElementById('resourceTableContainer'),
      ['name', 'role', 'capacity', 'availability', 'email', 'project'],
      rows,
      (row) => selectResource(row._raw)
    );

    // Post-process table rows: availability pills + delete buttons
    const tableEl = document.getElementById('resourceTableContainer').querySelector('table');
    if (tableEl) {
      const thead = tableEl.querySelector('thead tr');
      if (thead) { const th = document.createElement('th'); th.textContent = ''; thead.appendChild(th); }
      const bodyRows = tableEl.querySelectorAll('tbody tr');
      bodyRows.forEach((tr, i) => {
        // FREE/BUSY pill
        const cells = tr.querySelectorAll('td');
        const availCell = cells[3];
        if (availCell) {
          const val = resources[i]?.availability;
          if (val != null) {
            const pill = document.createElement('span');
            pill.className = 'pill ' + (val >= 0.5 ? 'good' : 'bad');
            pill.textContent = val >= 0.5 ? 'FREE' : 'BUSY';
            pill.style.marginLeft = '6px';
            pill.style.fontSize = '10px';
            availCell.appendChild(pill);
          }
        }
        // Delete button
        const td = document.createElement('td');
        if (!resources[i]) { tr.appendChild(td); return; }
        const btn = document.createElement('button');
        btn.className = 'btn danger';
        btn.style.cssText = 'padding:2px 6px;font-size:11px;';
        btn.textContent = '\u00d7';
        btn.title = 'Delete resource';
        btn.addEventListener('click', async (e) => {
          e.stopPropagation();
          if (!confirm('Delete resource "' + (resources[i].name || '') + '"?')) return;
          try {
            await api.resources.delete(resources[i].resourceId);
            const idx = allResources.findIndex(r => r.resourceId === resources[i].resourceId);
            if (idx >= 0) allResources.splice(idx, 1);
            toast('resource deleted');
            document.dispatchEvent(new CustomEvent('jc:data-changed'));
            renderView(currentMode);
          } catch (err) { toast('delete failed: ' + err.message); }
        });
        td.appendChild(btn);
        tr.appendChild(td);
      });
    }
  }

  function renderCapacity(resources) {
    const container = document.getElementById('resourceTableContainer');
    const sorted = [...resources].sort((a, b) => (a.availability || 0) - (b.availability || 0));

    if (sorted.length === 0) {
      container.innerHTML = '<div class="tiny">No resources found.</div>';
      return;
    }

    container.innerHTML = '';
    const timeline = document.createElement('div');
    timeline.className = 'timeline';

    sorted.forEach(r => {
      const util = Math.round((r.availability || 0) * 100);
      const barColor = util >= 70 ? 'var(--good)' : util >= 30 ? 'var(--warn)' : 'var(--bad)';

      const el = document.createElement('div');
      el.className = 'event';
      el.style.cursor = 'pointer';
      el.innerHTML = `
        <div class="eventTop">
          <div style="min-width:0;flex:1">
            <div class="eventTitle">${esc(r.name || 'Unknown')}</div>
            <div class="tiny">${esc(r.role || '')} \u2022 Capacity: ${r.capacity != null ? r.capacity : '\u2013'} \u2022 Availability: ${util}%</div>
          </div>
        </div>
        <div class="progress-track" style="margin-top:6px">
          <div class="progress-fill" style="width:${util}%;background:${barColor}"></div>
        </div>`;
      el.addEventListener('click', () => selectResource(r));
      timeline.appendChild(el);
    });

    container.appendChild(timeline);
  }

  function renderOveruse(resources) {
    const container = document.getElementById('resourceTableContainer');
    const overloaded = resources.filter(r => r.availability != null && r.availability < 0.5);

    if (overloaded.length === 0) {
      container.innerHTML = '<div class="tiny" style="padding:10px">All resources have healthy capacity.</div>';
      return;
    }

    container.innerHTML = `<div class="sectionLabel" style="color:var(--warn);margin-bottom:8px">\u26A0 OVERLOADED RESOURCES</div>`;
    const timeline = document.createElement('div');
    timeline.className = 'timeline';

    overloaded.sort((a, b) => (a.availability || 0) - (b.availability || 0)).forEach(r => {
      const util = Math.round((r.availability || 0) * 100);
      const barColor = 'var(--bad)';

      const el = document.createElement('div');
      el.className = 'event';
      el.style.cursor = 'pointer';
      el.style.borderLeft = '3px solid var(--bad)';
      el.innerHTML = `
        <div class="eventTop">
          <div style="min-width:0;flex:1">
            <div class="eventTitle">${esc(r.name || 'Unknown')}</div>
            <div class="tiny">${esc(r.role || '')} \u2022 Capacity: ${r.capacity != null ? r.capacity : '\u2013'} \u2022 Availability: ${util}%</div>
          </div>
          <span class="pill bad">BUSY</span>
        </div>
        <div class="progress-track" style="margin-top:6px">
          <div class="progress-fill" style="width:${util}%;background:${barColor}"></div>
        </div>`;
      el.addEventListener('click', () => selectResource(r));
      timeline.appendChild(el);
    });

    container.appendChild(timeline);
  }

  function renderView(mode) {
    const projectOnly = document.getElementById('resourceProjectOnly')?.checked || false;
    const filtered = getFiltered(projectOnly);
    if (mode === 'capacity') renderCapacity(filtered);
    else if (mode === 'overuse') renderOveruse(filtered);
    else renderAll(filtered);
  }

  // Tab click handlers
  document.querySelectorAll('.view-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.view-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      currentMode = tab.dataset.mode;
      renderView(currentMode);
    });
  });

  renderView('all');

  document.getElementById('resourceProjectOnly')?.addEventListener('change', () => {
    renderView(currentMode);
  });

  document.getElementById('addMissingBtn')?.addEventListener('click', async () => {
    const ta = document.getElementById('resourceNames');
    const raw = (ta?.value || '').trim();
    if (!raw) { toast('paste names first'); return; }

    const names = raw.split('\n').map(n => n.trim()).filter(Boolean);
    const existingNames = new Set(allResources.map(r => (r.name || '').toLowerCase()));
    const missing = names.filter(n => !existingNames.has(n.toLowerCase()));

    if (missing.length === 0) {
      toast('all names already exist');
      return;
    }

    try {
      for (const name of missing) {
        const created = await api.resources.create({
          name,
          role: 'ENGINEER',
          capacity: 100,
          availability: 1.0,
          projectId: pid || null
        });
        allResources.push(created);
      }
      ta.value = '';
      toast(`added ${missing.length} resource(s)`);
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      renderView(currentMode);
    } catch (e) {
      toast('add failed: ' + e.message);
    }
  });
}

async function renderReadingPane(d, pid) {
  const pane = document.getElementById('readingPane');
  pane.style.display = 'block';

  const util = d.availability != null ? Math.round(d.availability * 100) : null;
  const barColor = util != null ? (util >= 70 ? 'var(--good)' : util >= 30 ? 'var(--warn)' : 'var(--bad)') : '';

  let html = `<div class="reading-pane-divider">Resource Detail</div>`;
  html += `<div class="reading-pane">`;
  html += `<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">`;
  html += `<b style="font-size:14px">${esc(d.name || 'Unknown')}</b>`;
  html += `<span class="pill">${esc(d.role || '')}</span>`;
  html += `</div>`;

  html += `<div class="hr"></div>`;
  html += `<div class="tiny">capacity: ${d.capacity != null ? d.capacity : '\u2013'}</div>`;
  html += `<div class="tiny">availability: ${d.availability != null ? d.availability : '\u2013'}</div>`;
  if (util != null) {
    html += `<div class="progress-track" style="margin-top:6px"><div class="progress-fill" style="width:${util}%;background:${barColor}"></div></div>`;
  }
  html += `<div class="tiny">email: ${esc(d.email || '-')}</div>`;
  if ((d.skills || []).length > 0) {
    html += `<div class="tiny">skills: ${d.skills.join(', ')}</div>`;
  }
  html += `<div class="tiny">project: ${esc(d.projectId || '-')}</div>`;

  // Assigned tickets section
  html += `<div class="hr"></div>`;
  html += `<div class="sectionLabel">Assigned Tickets</div>`;
  html += `<div id="rpAssignedTickets"><div class="tiny">Loading...</div></div>`;

  html += `<div class="hr"></div>`;
  html += `<div class="row"><button class="btn danger" id="rpDelResource">Delete</button></div>`;
  html += `</div>`;
  pane.innerHTML = html;

  // Fetch and display assigned tickets
  const ticketContainer = document.getElementById('rpAssignedTickets');
  if (pid && ticketContainer) {
    try {
      const tickets = await api.tickets.list(pid);
      const assigned = tickets.filter(t =>
        parseAssignee(t).toLowerCase() === (d.name || '').toLowerCase()
      );
      if (assigned.length > 0) {
        ticketContainer.innerHTML = assigned.map(t =>
          `<div class="tiny" style="padding:2px 0">\u2022 ${esc(t.title || 'Untitled')} <span class="pill" style="font-size:10px">${esc(t.status || '')}</span></div>`
        ).join('');
      } else {
        ticketContainer.innerHTML = '<div class="tiny">No tickets assigned.</div>';
      }
    } catch {
      ticketContainer.innerHTML = '<div class="tiny">Could not load tickets.</div>';
    }
  } else {
    if (ticketContainer) ticketContainer.innerHTML = '<div class="tiny">Select a project to see tickets.</div>';
  }

  pane.querySelector('#rpDelResource')?.addEventListener('click', async () => {
    if (!confirm('Delete this resource?')) return;
    try {
      await api.resources.delete(d.resourceId);
      toast('resource deleted');
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      render();
    } catch (e) { toast('delete failed: ' + e.message); }
  });
}
