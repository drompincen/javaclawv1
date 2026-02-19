import * as api from '../api.js';
import { getState } from '../state.js';
import { toast } from '../components/toast.js';

const CRON_LABELS = {
  '0 0 9 * * MON-FRI':  'Weekdays 09:00 UTC',
  '0 0 10 * * MON':     'Monday 10:00 UTC',
  '0 0 18 * * MON-FRI': 'Weekdays 18:00 UTC',
};

function cronToHuman(cronExpr) {
  if (!cronExpr) return '—';
  return CRON_LABELS[cronExpr] || cronExpr;
}

function relativeTime(isoStr) {
  if (!isoStr) return '—';
  const target = new Date(isoStr);
  const now = Date.now();
  const diffMs = target.getTime() - now;
  if (diffMs < 0) return 'overdue';
  const mins = Math.floor(diffMs / 60000);
  if (mins < 60) return `in ${mins}m`;
  const hrs = Math.floor(mins / 60);
  const remMins = mins % 60;
  if (hrs < 24) return `in ${hrs}h ${remMins}m`;
  const days = Math.floor(hrs / 24);
  return `in ${days}d ${hrs % 24}h`;
}

function timeAgo(isoStr) {
  if (!isoStr) return '—';
  const past = new Date(isoStr);
  const diffMs = Date.now() - past.getTime();
  if (diffMs < 0) return 'just now';
  const mins = Math.floor(diffMs / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

function agentLabel(agentId) {
  return (agentId || '').replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

export async function render() {
  document.getElementById('centerTitle').textContent = 'SCHEDULER';
  document.getElementById('centerSub').textContent = 'Scheduled agent runs and execution history.';
  const body = document.getElementById('centerBody');

  let scheduleList = [];
  let pastExecs = [];
  try {
    [scheduleList, pastExecs] = await Promise.all([
      api.schedules.list(),
      api.executions.past({ size: 10 }),
    ]);
  } catch (e) {
    body.innerHTML = `<div class="tiny">Error loading scheduler data: ${esc(e.message)}</div>`;
    return;
  }

  // Past executions come as a Spring Page — extract content
  const pastItems = pastExecs.content || pastExecs || [];

  let html = '';

  // Card 1: Scheduled Agents
  html += '<div class="card"><div class="cardH"><b>Scheduled Agents</b></div><div class="cardB">';
  if (scheduleList.length === 0) {
    html += '<div class="tiny">No schedules configured.</div>';
  } else {
    html += '<table style="width:100%;border-collapse:collapse">';
    html += '<tr style="border-bottom:1px solid var(--border)">'
          + '<th style="text-align:left;padding:4px">Agent</th>'
          + '<th style="text-align:left;padding:4px">Schedule</th>'
          + '<th style="text-align:left;padding:4px">Next Run</th>'
          + '<th style="text-align:left;padding:4px">Status</th>'
          + '<th style="text-align:right;padding:4px">Actions</th>'
          + '</tr>';
    scheduleList.forEach(s => {
      const statusCls = s.enabled ? 'good' : '';
      const statusLabel = s.enabled ? 'ON' : 'OFF';
      const toggleLabel = s.enabled ? 'Disable' : 'Enable';
      html += `<tr style="border-bottom:1px solid var(--border)">
        <td style="padding:4px">${esc(agentLabel(s.agentId))}</td>
        <td style="padding:4px" class="tiny">${esc(cronToHuman(s.cronExpr))}</td>
        <td style="padding:4px" class="tiny">${esc(relativeTime(s.nextExecutionAt))}</td>
        <td style="padding:4px"><span class="pill ${statusCls}">${statusLabel}</span></td>
        <td style="padding:4px;text-align:right">
          <button class="btn" data-trigger="${esc(s.agentId)}" style="margin-right:4px">Run Now</button>
          <button class="btn" data-toggle="${esc(s.scheduleId)}" data-enabled="${s.enabled}">${toggleLabel}</button>
        </td>
      </tr>`;
    });
    html += '</table>';
  }
  html += '</div></div>';

  // Card 2: Recent Executions
  html += '<div class="card" style="margin-top:12px"><div class="cardH"><b>Recent Executions</b></div><div class="cardB">';
  if (pastItems.length === 0) {
    html += '<div class="tiny">No executions yet.</div>';
  } else {
    html += '<div class="timeline">';
    pastItems.forEach(ex => {
      const pillCls = ex.resultStatus === 'SUCCESS' ? 'good'
                    : ex.resultStatus === 'FAIL' ? 'bad'
                    : ex.resultStatus === 'CANCELLED' ? 'warn' : '';
      const durationSec = ex.durationMs ? (ex.durationMs / 1000).toFixed(1) + 's' : '—';
      html += `<div class="timelineEvent" style="margin-bottom:8px">
        <span>${esc(agentLabel(ex.agentId))}</span>
        <span class="pill ${pillCls}">${esc(ex.resultStatus || '?')}</span>
        <span class="tiny" style="margin-left:8px">${esc(durationSec)}</span>
        <span class="tiny" style="margin-left:8px">${esc(timeAgo(ex.endedAt || ex.startedAt))}</span>
      </div>`;
    });
    html += '</div>';
  }
  html += '</div></div>';

  body.innerHTML = html;

  // Wire "Run Now" buttons
  body.querySelectorAll('[data-trigger]').forEach(btn => {
    btn.addEventListener('click', async () => {
      const agentId = btn.dataset.trigger;
      btn.disabled = true;
      btn.textContent = '...';
      try {
        await api.executions.trigger({ agentId });
        toast(agentId + ' triggered');
      } catch (e) {
        toast('trigger failed: ' + e.message);
      }
      setTimeout(() => render(), 1000);
    });
  });

  // Wire Enable/Disable toggle buttons
  body.querySelectorAll('[data-toggle]').forEach(btn => {
    btn.addEventListener('click', async () => {
      const scheduleId = btn.dataset.toggle;
      const currentlyEnabled = btn.dataset.enabled === 'true';
      btn.disabled = true;
      try {
        await api.schedules.update(scheduleId, { enabled: !currentlyEnabled });
        toast(currentlyEnabled ? 'schedule disabled' : 'schedule enabled');
      } catch (e) {
        toast('update failed: ' + e.message);
      }
      await render();
    });
  });
}
