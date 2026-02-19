import * as api from '../api.js';
import { getState } from '../state.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'SPRINT HEALTH';
  document.getElementById('centerSub').textContent =
    'Sprint-by-sprint objective coverage, ticket breakdown, and risk signals.';

  const body = document.getElementById('centerBody');

  if (!pid) {
    body.innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  let objectives = [], tickets = [];
  try { objectives = await api.objectives.list(pid); } catch {}
  try { tickets = await api.tickets.list(pid); } catch {}

  // Group objectives by sprint name
  const sprintMap = {};
  objectives.forEach(o => {
    const key = o.sprintName || 'Unassigned';
    if (!sprintMap[key]) sprintMap[key] = { name: key, objectives: [], startDate: null, endDate: null };
    sprintMap[key].objectives.push(o);
    if (o.startDate && (!sprintMap[key].startDate || o.startDate < sprintMap[key].startDate)) {
      sprintMap[key].startDate = o.startDate;
    }
    if (o.endDate && (!sprintMap[key].endDate || o.endDate > sprintMap[key].endDate)) {
      sprintMap[key].endDate = o.endDate;
    }
  });

  const sprints = Object.values(sprintMap);
  const today = new Date().toISOString().split('T')[0];

  // Sort: current sprint first, then by startDate desc
  sprints.sort((a, b) => {
    const aCurrent = a.startDate && a.endDate && a.startDate <= today && today <= a.endDate;
    const bCurrent = b.startDate && b.endDate && b.startDate <= today && today <= b.endDate;
    if (aCurrent && !bCurrent) return -1;
    if (!aCurrent && bCurrent) return 1;
    return (b.startDate || '').localeCompare(a.startDate || '');
  });

  // Ticket status counts
  const statusCounts = {};
  tickets.forEach(t => {
    const s = t.status || 'UNKNOWN';
    statusCounts[s] = (statusCounts[s] || 0) + 1;
  });
  const totalTickets = tickets.length;
  const doneTickets = statusCounts['DONE'] || 0;

  if (sprints.length === 0 && totalTickets === 0) {
    body.innerHTML = '<div class="tiny">No objectives or tickets yet.</div>';
    return;
  }

  let html = '';

  // Overall ticket summary
  html += `<div class="card" style="margin-bottom:10px">
    <div class="cardH"><div><b>Ticket Overview</b><div class="tiny">${totalTickets} total</div></div></div>
    <div class="cardB">
      <div class="row">${Object.entries(statusCounts).map(([s, c]) => {
        const cls = s === 'DONE' ? 'good' : s === 'BLOCKED' ? 'bad' : s === 'IN_PROGRESS' ? 'warn' : '';
        return `<span class="pill ${cls}">${esc(s)}: ${c}</span>`;
      }).join('')}</div>
      <div class="progress-track" style="margin-top:8px">
        <div class="progress-fill" style="width:${totalTickets > 0 ? Math.round(doneTickets / totalTickets * 100) : 0}%"></div>
      </div>
      <div class="tiny" style="margin-top:4px">${totalTickets > 0 ? Math.round(doneTickets / totalTickets * 100) : 0}% complete</div>
    </div>
  </div>`;

  // Per-sprint cards
  sprints.forEach(sprint => {
    const isCurrent = sprint.startDate && sprint.endDate && sprint.startDate <= today && today <= sprint.endDate;
    const borderStyle = isCurrent ? 'border-color: var(--accent)' : '';
    const objs = sprint.objectives;
    const atRisk = objs.filter(o => (o.risks || []).length > 0 || o.status === 'AT_RISK').length;
    const achieved = objs.filter(o => o.status === 'ACHIEVED').length;
    const avgCoverage = objs.length > 0
      ? Math.round(objs.reduce((sum, o) => sum + (o.coveragePercent || 0), 0) / objs.length)
      : 0;

    html += `<div class="card" style="margin-bottom:10px;${borderStyle}">
      <div class="cardH">
        <div>
          <b>${esc(sprint.name)}</b>
          ${isCurrent ? '<span class="badge good" style="margin-left:8px">CURRENT</span>' : ''}
          <div class="tiny">${sprint.startDate || '?'} \u2192 ${sprint.endDate || '?'}</div>
        </div>
        <div class="row">
          <span class="pill">${objs.length} obj</span>
          ${atRisk > 0 ? `<span class="pill bad">${atRisk} at-risk</span>` : ''}
          <span class="pill">${avgCoverage}% cov</span>
        </div>
      </div>
      <div class="cardB">
        ${objs.map(o => {
          const statusCls = o.status === 'ACHIEVED' ? 'good' : o.status === 'AT_RISK' ? 'bad' : '';
          return `<div class="tiny" style="margin-bottom:4px">
            <span class="pill ${statusCls}" style="margin-right:6px">${esc(o.status || 'PROPOSED')}</span>
            ${esc(o.outcome || '')}
            ${o.coveragePercent != null ? ` <span class="tiny">(${o.coveragePercent}%)</span>` : ''}
          </div>`;
        }).join('')}
        <div class="progress-track">
          <div class="progress-fill" style="width:${objs.length > 0 ? Math.round(achieved / objs.length * 100) : 0}%"></div>
        </div>
        <div class="tiny" style="margin-top:4px">${achieved}/${objs.length} achieved</div>
      </div>
    </div>`;
  });

  body.innerHTML = html;
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
