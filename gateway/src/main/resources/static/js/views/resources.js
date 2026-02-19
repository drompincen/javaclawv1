import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { renderTable } from '../components/table.js';
import { toast } from '../components/toast.js';

let currentMode = 'all';

export async function render() {
  currentMode = 'all';
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
      (row) => setSelected({ type: 'resource', id: row._raw.resourceId, data: row._raw })
    );

    // Post-process availability cells to add FREE/BUSY pills
    const tableEl = document.getElementById('resourceTableContainer').querySelector('table');
    if (tableEl) {
      const bodyRows = tableEl.querySelectorAll('tbody tr');
      bodyRows.forEach((tr, i) => {
        const cells = tr.querySelectorAll('td');
        const availCell = cells[3];
        if (!availCell) return;
        const val = resources[i]?.availability;
        if (val != null) {
          const pill = document.createElement('span');
          pill.className = 'pill ' + (val >= 0.5 ? 'good' : 'bad');
          pill.textContent = val >= 0.5 ? 'FREE' : 'BUSY';
          pill.style.marginLeft = '6px';
          pill.style.fontSize = '10px';
          availCell.appendChild(pill);
        }
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
      el.addEventListener('click', () => setSelected({ type: 'resource', id: r.resourceId, data: r }));
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
      el.addEventListener('click', () => setSelected({ type: 'resource', id: r.resourceId, data: r }));
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
      renderView(currentMode);
    } catch (e) {
      toast('add failed: ' + e.message);
    }
  });
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
