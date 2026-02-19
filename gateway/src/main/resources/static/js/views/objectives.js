import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { toast } from '../components/toast.js';

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

function classifyObjective(obj) {
  const now = new Date();
  const soon = new Date(now.getTime() + 14 * 86400000);
  const start = obj.startDate ? new Date(obj.startDate) : null;
  const end = obj.endDate ? new Date(obj.endDate) : null;

  if (obj.status === 'ACHIEVED' || obj.status === 'ARCHIVED') return 'completed';
  if (obj.status === 'ACTIVE' || obj.status === 'AT_RISK') return 'now';
  if (start && end && start <= now && end >= now) return 'now';
  if (start && start <= soon && start > now) return 'approaching';
  if (end && end <= soon && end > now) return 'approaching';
  if (!start && !end) return 'now';
  return 'future';
}

function daysUntil(dateStr) {
  if (!dateStr) return null;
  const diff = new Date(dateStr).getTime() - Date.now();
  return Math.max(0, Math.ceil(diff / 86400000));
}

let completedVisible = false;

export async function render() {
  completedVisible = false;
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'OBJECTIVES';
  document.getElementById('centerSub').textContent =
    'Sprint objectives with coverage + unmapped tickets; PM agent keeps them aligned.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Sprint Objectives</b><div class="tiny">coverage % + unmapped tickets</div></div>
        <div class="row">
          <button class="btn" id="addObjToggle">Add Objective</button>
        </div>
      </div>
      <div id="addObjForm" style="display:none;padding:10px 12px;border-bottom:1px solid var(--line)">
        <div class="row" style="margin-bottom:6px">
          <input type="text" id="objSprint" placeholder="Sprint name" style="flex:1">
          <input type="date" id="objStart">
          <input type="date" id="objEnd">
        </div>
        <textarea id="objOutcome" placeholder="Outcome description..." style="min-height:60px"></textarea>
        <div class="row" style="margin-top:6px">
          <button class="btn primary" id="objSubmit">Create</button>
          <button class="btn ghost" id="objCancel">Cancel</button>
        </div>
      </div>
      <div class="cardB" id="objTableContainer"></div>
    </div>`;

  // Toggle form
  document.getElementById('addObjToggle')?.addEventListener('click', () => {
    const form = document.getElementById('addObjForm');
    form.style.display = form.style.display === 'none' ? 'block' : 'none';
  });
  document.getElementById('objCancel')?.addEventListener('click', () => {
    document.getElementById('addObjForm').style.display = 'none';
  });

  // Submit new objective
  document.getElementById('objSubmit')?.addEventListener('click', async () => {
    if (!pid) { toast('select a project first'); return; }
    const sprintName = document.getElementById('objSprint')?.value?.trim();
    const outcome = document.getElementById('objOutcome')?.value?.trim();
    const startDate = document.getElementById('objStart')?.value || null;
    const endDate = document.getElementById('objEnd')?.value || null;
    if (!sprintName || !outcome) { toast('sprint name + outcome required'); return; }
    try {
      await api.objectives.create(pid, { sprintName, outcome, startDate, endDate, status: 'PROPOSED' });
      toast('objective created');
      document.getElementById('addObjForm').style.display = 'none';
      render();
    } catch (e) {
      toast('create failed: ' + e.message);
    }
  });

  if (!pid) {
    document.getElementById('objTableContainer').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const objs = await api.objectives.list(pid);
    const groups = { now: [], approaching: [], future: [], completed: [] };
    objs.forEach(o => {
      const cls = classifyObjective(o);
      if (cls === 'approaching') groups.approaching.push(o);
      else groups[cls].push(o);
    });

    const container = document.getElementById('objTableContainer');
    container.innerHTML = '';

    const nowAndApproaching = [...groups.now, ...groups.approaching];

    // Render section
    function renderSection(label, icon, items, sectionClass, opts = {}) {
      if (items.length === 0 && !opts.showEmpty) return;

      const header = document.createElement('div');
      header.className = 'obj-section-header' + (opts.clickable ? ' clickable' : '');
      header.innerHTML = `<span>${icon} ${label}</span><span class="chip">${items.length} obj</span>`;

      const section = document.createElement('div');
      section.className = 'timeline';
      if (opts.hidden) section.style.display = 'none';

      if (opts.clickable) {
        header.addEventListener('click', () => {
          const visible = section.style.display !== 'none';
          section.style.display = visible ? 'none' : 'flex';
        });
      }

      container.appendChild(header);

      items.forEach(o => {
        const el = document.createElement('div');
        const cls = classifyObjective(o);
        let borderClass = '';
        if (cls === 'now') borderClass = 'obj-now';
        else if (cls === 'approaching') borderClass = 'obj-approaching';
        else if (cls === 'completed') borderClass = 'obj-completed';
        el.className = `event ${borderClass}`;
        el.style.cursor = 'pointer';

        const coverage = o.coveragePercent != null ? o.coveragePercent + '%' : '\u2013';
        const riskLabel = (o.risks || []).length > 0 ? '\u26A0' : '';
        const statusClass = o.status === 'AT_RISK' ? 'bad' : o.status === 'ACHIEVED' ? 'good' : o.status === 'ACTIVE' ? '' : '';

        let countdownHtml = '';
        if (cls === 'approaching') {
          const d = daysUntil(o.startDate) ?? daysUntil(o.endDate);
          if (d != null) countdownHtml = ` <span class="countdown-badge">\u23F0 in ${d}d</span>`;
        }

        el.innerHTML = `
          <div class="eventTop">
            <div style="min-width:0;flex:1">
              <div class="eventTitle">
                <span class="chip" style="font-size:10px;margin-right:4px">${esc(o.sprintName || '')}</span>
                ${esc(o.outcome || '')}
                ${countdownHtml}
              </div>
              <div class="tiny">${coverage} coverage ${riskLabel} \u2022 ${(o.ticketIds || []).length} tickets</div>
            </div>
            <div style="display:flex;gap:4px;align-items:center;flex-shrink:0">
              <span class="pill ${statusClass}">${esc(o.status || '')}</span>
              <span class="obj-actions" style="display:flex;gap:2px"></span>
            </div>
          </div>`;

        // Action buttons
        const actionsSpan = el.querySelector('.obj-actions');
        const objId = o.objectiveId;

        const mkBtn = (label, cls, handler) => {
          const b = document.createElement('button');
          b.className = 'btn ' + cls;
          b.innerHTML = label;
          b.style.padding = '3px 6px';
          b.style.fontSize = '11px';
          b.addEventListener('click', async (e) => {
            e.stopPropagation();
            await handler();
          });
          return b;
        };

        actionsSpan.appendChild(mkBtn('\u2713', '', async () => {
          await api.objectives.update(pid, objId, { ...o, status: 'ACHIEVED' });
          toast('objective marked ACHIEVED');
          render();
        }));
        actionsSpan.appendChild(mkBtn('\u26A0', '', async () => {
          const risks = [...(o.risks || []), 'flagged at-risk manually'];
          await api.objectives.update(pid, objId, { ...o, status: 'AT_RISK', risks });
          toast('objective marked AT_RISK');
          render();
        }));
        actionsSpan.appendChild(mkBtn('\u2715', 'danger', async () => {
          if (!confirm('Delete this objective?')) return;
          await api.objectives.delete(pid, objId);
          toast('objective deleted');
          render();
        }));

        el.addEventListener('click', (e) => {
          if (e.target.closest('button')) return;
          setSelected({ type: 'objective', id: objId, data: o });
        });

        section.appendChild(el);
      });

      container.appendChild(section);
    }

    renderSection('NOW & APPROACHING', '\uD83D\uDD34', nowAndApproaching, 'now');
    renderSection('FUTURE', '\uD83D\uDCC5', groups.future, 'future');
    renderSection('COMPLETED', '\u2705', groups.completed, 'completed', { clickable: true, hidden: true });

    if (objs.length === 0) {
      container.innerHTML = '<div class="tiny">No objectives yet. Use the form above to add one.</div>';
    }
  } catch {
    document.getElementById('objTableContainer').innerHTML = '<div class="tiny">Could not load objectives.</div>';
  }
}
