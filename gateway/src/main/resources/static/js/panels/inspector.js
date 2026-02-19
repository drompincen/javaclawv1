import * as api from '../api.js';
import { getState, onChange } from '../state.js';
import { toast } from '../components/toast.js';

export function initInspector() {
  onChange((changeType) => {
    if (changeType === 'selected') renderInspector();
  });
}

export function renderInspector() {
  const box = document.getElementById('inspector');
  if (!box) return;

  const sel = getState().selectedEntity;
  if (!sel || !sel.data) {
    box.innerHTML = '<div class="tiny">Select a thread/objective/plan/reminder to see details.</div>';
    return;
  }

  const d = sel.data;
  switch (sel.type) {
    case 'thread': {
      const decisions = d.decisions || [];
      const actions = d.actions || [];
      box.innerHTML = `
        <div><b>Thread</b>: ${esc(d.title)}</div>
        <div class="tiny">status: ${d.status || d.lifecycle || '\u2013'} \u2022 evidence: ${d.evidenceCount || 0} \u2022 objectives: ${(d.objectiveIds || []).length}</div>
        ${d.summary ? `<div class="hr"></div><div class="tiny"><b>Summary:</b> ${esc(d.summary)}</div>` : ''}
        ${d.content ? `<div class="hr"></div><div class="tiny"><b>Content:</b></div>
<div class="tiny" style="white-space:pre-wrap;max-height:300px;overflow-y:auto">${esc(d.content)}</div>` : ''}
        ${decisions.length > 0 ? `<div class="hr"></div><div class="tiny"><b>Decisions:</b></div>${decisions.map(dec => `<div class="tiny">\u2714 ${esc(dec.text)}${dec.decidedBy ? ' (' + esc(dec.decidedBy) + ')' : ''}</div>`).join('')}` : ''}
        ${actions.length > 0 ? `<div class="hr"></div><div class="tiny"><b>Action Items:</b></div>${actions.map(a => `<div class="tiny">\u25B6 ${esc(a.text)}${a.assignee ? ' \u2192 ' + esc(a.assignee) : ''} <span class="pill">${a.status || 'OPEN'}</span></div>`).join('')}` : ''}
        <div class="hr"></div>
        <div class="tiny" id="threadMessages"><i>Loading messages...</i></div>`;
      // Async load thread messages
      loadThreadMessages(d.threadId || sel.id);
      break;
    }

    case 'objective': {
      const pid = getState().currentProjectId;
      box.innerHTML = `
        <div><b>Objective</b>: ${esc(d.sprintName)}</div>
        <div class="tiny">${esc(d.outcome)}</div>
        <div class="hr"></div>
        <div class="tiny">coveragePct: ${d.coveragePercent != null ? d.coveragePercent + '%' : '\u2013'}</div>
        <div class="tiny">measurableSignal: ${esc(d.measurableSignal)}</div>
        <div class="tiny">risks: ${(d.risks || []).join(', ') || 'none'}</div>
        <div class="tiny">status: ${d.status || '\u2013'}</div>
        <div class="hr"></div>
        <div class="row" id="objActions">
          <button class="btn" data-action="complete">Complete</button>
          <button class="btn" data-action="atrisk">At Risk</button>
          <button class="btn danger" data-action="remove">Remove</button>
        </div>`;
      // Wire objective action buttons
      box.querySelectorAll('#objActions button').forEach(btn => {
        btn.addEventListener('click', async () => {
          const action = btn.dataset.action;
          const objId = d.objectiveId || sel.id;
          try {
            if (action === 'complete') {
              await api.objectives.update(pid, objId, { ...d, status: 'ACHIEVED' });
              toast('objective marked ACHIEVED');
            } else if (action === 'atrisk') {
              const risks = [...(d.risks || []), 'flagged at-risk manually'];
              await api.objectives.update(pid, objId, { ...d, status: 'AT_RISK', risks });
              toast('objective marked AT_RISK');
            } else if (action === 'remove') {
              if (!confirm('Delete this objective?')) return;
              await api.objectives.delete(pid, objId);
              toast('objective deleted');
            }
          } catch (e) {
            toast('action failed: ' + e.message);
          }
        });
      });
      break;
    }

    case 'ticket':
      box.innerHTML = `
        <div><b>Ticket</b>: ${esc(d.title)}</div>
        <div class="hr"></div>
        ${d.description ? `<div class="tiny">${esc(d.description)}</div><div class="hr"></div>` : ''}
        <div class="tiny">status: ${esc(d.status)}</div>
        <div class="tiny">priority: ${esc(d.priority)}</div>
        <div class="tiny">assignee: ${esc(d.assignee)}</div>
        <div class="tiny">externalRef: ${esc(d.externalRef)}</div>
        ${(d.blockedBy || []).length > 0 ? `<div class="tiny">blockedBy: ${d.blockedBy.join(', ')}</div>` : ''}`;
      break;

    case 'resource':
      box.innerHTML = `
        <div><b>Resource</b>: ${esc(d.name)}</div>
        <div class="hr"></div>
        <div class="tiny">role: ${esc(d.role)}</div>
        <div class="tiny">capacity: ${d.capacity != null ? d.capacity : '\u2013'}</div>
        <div class="tiny">availability: ${d.availability != null ? d.availability : '\u2013'}</div>
        <div class="tiny">email: ${esc(d.email)}</div>
        ${(d.skills || []).length > 0 ? `<div class="tiny">skills: ${d.skills.join(', ')}</div>` : ''}`;
      break;

    case 'phase':
      box.innerHTML = `
        <div><b>Phase</b>: ${esc(d.name)}</div>
        <div class="tiny">${esc(d.description)}</div>
        <div class="hr"></div>
        <div class="tiny"><b>Entry</b>: ${(d.entryCriteria || []).join(', ') || '\u2013'}</div>
        <div class="tiny"><b>Exit</b>: ${(d.exitCriteria || []).join(', ') || '\u2013'}</div>
        <div class="tiny">status: ${d.status || '\u2013'} \u2022 sortOrder: ${d.sortOrder || 0}</div>`;
      break;

    case 'reminder':
      box.innerHTML = `
        <div><b>Reminder</b></div>
        <div class="tiny">${esc(d.message)}</div>
        <div class="hr"></div>
        <div class="tiny">triggerAt: ${d.triggerAt || '\u2013'}</div>
        <div class="tiny">type: ${d.type || '\u2013'}</div>
        <div class="tiny">recurring: ${d.recurring ? 'Yes' : 'No'}</div>
        <div class="tiny">triggered: ${d.triggered ? 'Yes' : 'No'}</div>`;
      break;

    case 'checklist': {
      const items = d.items || [];
      box.innerHTML = `
        <div><b>Checklist</b></div>
        <div class="tiny">${esc(d.name)} \u2022 status: ${d.status || '\u2013'}</div>
        <div class="hr"></div>
        ${items.map(i => `<div class="tiny">${i.checked ? '\u2611' : '\u2610'} ${esc(i.text)}${i.assignee ? ' (' + esc(i.assignee) + ')' : ''}</div>`).join('')}
        ${items.length === 0 ? '<div class="tiny">No items.</div>' : ''}`;
      break;
    }

    case 'session': {
      const time = d.updatedAt ? new Date(d.updatedAt).toLocaleString() : '';
      const statusClass = d.status === 'COMPLETED' ? 'good' : d.status === 'FAILED' ? 'bad' : 'warn';
      box.innerHTML = `
        <div><b>Session</b></div>
        <div class="tiny">${time} <span class="pill ${statusClass}">${d.status || 'IDLE'}</span></div>
        <div class="hr"></div>
        <div id="sessionMessages"><i class="tiny">Loading conversation...</i></div>`;
      loadSessionMessages(sel.id);
      break;
    }

    case 'blindspot':
      box.innerHTML = `
        <div><b>Blindspot</b>: ${esc(d.title)}</div>
        <div class="tiny">severity: ${d.severity || '\u2013'} \u2022 category: ${d.category || '\u2013'} \u2022 status: ${d.status || '\u2013'}</div>
        <div class="hr"></div>
        ${d.description ? `<div class="tiny">${esc(d.description)}</div>` : ''}
        ${d.owner ? `<div class="tiny">owner: ${esc(d.owner)}</div>` : ''}
        ${d.resolvedAt ? `<div class="tiny">resolved: ${d.resolvedAt}</div>` : ''}`;
      break;

    case 'reconciliation': {
      const conflicts = d.conflicts || [];
      const mappings = d.mappings || [];
      box.innerHTML = `
        <div><b>Reconciliation</b></div>
        <div class="tiny">status: ${d.status || '\u2013'} \u2022 source: ${d.sourceType || '\u2013'}</div>
        <div class="hr"></div>
        <div class="tiny">mappings: ${mappings.length}</div>
        <div class="tiny">conflicts: ${conflicts.length}</div>
        ${conflicts.map(c => `<div class="tiny">\u2022 ${esc(c.field)}: ${esc(c.sourceValue)} vs ${esc(c.ticketValue)}</div>`).join('')}`;
      break;
    }

    case 'link':
      box.innerHTML = `
        <div><b>Link</b></div>
        <div class="tiny">${esc(d.title)}</div>
        <div class="hr"></div>
        <div class="tiny">url: <a href="${esc(d.url)}" target="_blank" style="color:var(--accent)">${esc(d.url)}</a></div>
        <div class="tiny">category: ${esc(d.category)}</div>
        <div class="tiny">pinned: ${d.pinned ? 'Yes' : 'No'}</div>
        <div class="tiny">tags: ${(d.tags || []).join(', ') || '\u2013'}</div>`;
      break;

    default:
      box.innerHTML = `<div class="tiny">No inspector for type: ${esc(sel.type)}</div>`;
  }
}

async function loadThreadMessages(threadId) {
  const container = document.getElementById('threadMessages');
  if (!container) return;
  try {
    const msgs = await api.sessions.messages(threadId);
    if (!msgs || msgs.length === 0) {
      container.innerHTML = '<i>No messages.</i>';
      return;
    }
    container.innerHTML = '<b>Messages:</b>' + msgs.map(m => {
      const roleCls = m.role === 'assistant' ? 'accent' : '';
      const content = (m.content || '').substring(0, 300);
      return `<div style="margin-top:6px"><span class="chip ${roleCls}">${esc(m.role)}</span> <span class="tiny">${esc(content)}${(m.content || '').length > 300 ? '\u2026' : ''}</span></div>`;
    }).join('');
  } catch {
    container.innerHTML = '<i>Could not load messages.</i>';
  }
}

async function loadSessionMessages(sessionId) {
  const container = document.getElementById('sessionMessages');
  if (!container) return;
  try {
    const msgs = await api.sessions.messages(sessionId);
    if (!msgs || msgs.length === 0) {
      container.innerHTML = '<i class="tiny">No messages.</i>';
      return;
    }
    container.innerHTML = msgs.map(m => {
      const roleCls = m.role === 'assistant' ? 'accent' : '';
      const agentLabel = m.agentId ? ` <span class="chip">${esc(m.agentId)}</span>` : '';
      const content = (m.content || '').substring(0, 500);
      return `<div style="margin-top:8px"><span class="chip ${roleCls}">${esc(m.role)}</span>${agentLabel}<div class="tiny" style="margin-top:4px">${esc(content)}${(m.content || '').length > 500 ? '\u2026' : ''}</div></div>`;
    }).join('');
  } catch {
    container.innerHTML = '<i class="tiny">Could not load session messages.</i>';
  }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
