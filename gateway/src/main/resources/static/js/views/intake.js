import * as api from '../api.js';
import { getState, setSelected, incrementStep } from '../state.js';
import { toast } from '../components/toast.js';
import { addLog } from '../panels/activity.js';

export async function render() {
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'INTAKE';
  document.getElementById('centerSub').textContent =
    'Paste text or provide file paths. Agents organize into threads, ideas, plans, objectives, reminders, checklists, reconciliation.';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Freeform intake</b><div class="tiny">Commands: <span class="chip accent">use project &lt;name&gt;</span></div></div>
      </div>
      <div class="cardB">
        <textarea id="intakeText" placeholder="Paste Confluence/Jira/Smartsheet/link dumps here..."></textarea>
        <div class="row" style="margin-top:8px;">
          <button class="btn" data-intent="threads">Thread Agent</button>
          <button class="btn" data-intent="ideas">Extract ideas</button>
          <button class="btn" data-intent="plan">Create/update plan</button>
          <button class="btn" data-intent="objectives">Align objectives</button>
          <button class="btn" data-intent="reminders">Create reminders</button>
          <button class="btn" data-intent="checklists">Create checklists</button>
          <button class="btn" data-intent="reconcile">Reconcile everything</button>
          <button class="btn primary" id="intakeSend">Send</button>
        </div>
      </div>
    </div>
    <div style="height:10px"></div>
    <div class="grid2">
      <div class="card">
        <div class="cardH"><div><b>Recent intake</b><div class="tiny">select to inspect</div></div></div>
        <div class="cardB"><div class="timeline" id="intakeTimeline"><div class="tiny">Loading...</div></div></div>
      </div>
      <div class="card">
        <div class="cardH"><div><b>Outcome expectations</b><div class="tiny">what "done" looks like</div></div></div>
        <div class="cardB">
          <div class="tiny"><b>Thread Agent</b>: names threads by topic + continuity; links to sprint objectives<br/>
          <b>Plan Agent</b>: phases + entry/exit + milestones; maps to epics/initiatives<br/>
          <b>Reminder Agent</b>: normalized schedules; attaches to milestones + checklists<br/>
          <b>Checklist Agent</b>: ORR/release lists; owners; status<br/>
          <b>Reconcile Agent</b>: objectives ↔ milestones ↔ ticket dump alignment; drift deltas</div>
          <div class="hr"></div>
          <div class="chips"><span class="chip">Mongo change streams</span><span class="chip">Event types</span><span class="chip">Checker loop</span></div>
        </div>
      </div>
    </div>`;

  // Intent buttons
  body.querySelectorAll('[data-intent]').forEach(btn => {
    btn.addEventListener('click', () => {
      const ta = document.getElementById('intakeText');
      const intent = btn.dataset.intent;
      if (ta && !ta.value.startsWith('Intent:')) ta.value = `Intent: ${intent}\n` + ta.value;
      toast('intent set: ' + intent);
      addLog('INTAKE_INTENT: ' + intent, 'info');
    });
  });

  // Send button
  const sendBtn = document.getElementById('intakeSend');
  if (sendBtn) {
    sendBtn.addEventListener('click', async () => {
      const ta = document.getElementById('intakeText');
      const raw = (ta?.value || '').trim();
      if (!raw) { toast('nothing to send'); return; }
      if (!pid) { toast('select a project first'); return; }

      try {
        // Create a session for this intake and send the message
        const session = await api.sessions.create({ projectId: pid });
        await api.sessions.sendMessage(session.sessionId, { content: raw, role: 'user' });
        await api.sessions.run(session.sessionId);
        ta.value = '';
        incrementStep();
        toast('intake sent');
        addLog('USER_MESSAGE_RECEIVED', 'info');
        addLog('CONTROLLER: routing to agents', 'warn');
      } catch (e) {
        toast('send failed: ' + e.message);
        addLog('INTAKE_ERROR: ' + e.message, 'bad');
      }
    });
  }

  // Load recent threads as intake timeline proxy
  if (pid) {
    try {
      const threadList = await api.threads.list(pid);
      const tl = document.getElementById('intakeTimeline');
      if (threadList.length === 0) {
        tl.innerHTML = '<div class="tiny">No intake yet for this project.</div>';
      } else {
        tl.innerHTML = '';
        threadList.slice(0, 10).forEach(t => {
          const ev = document.createElement('div');
          ev.className = 'event';
          ev.style.cursor = 'pointer';
          ev.innerHTML = `<div class="eventTop"><div style="min-width:0"><div class="eventTitle">${esc(t.title || 'Untitled')}</div><div class="tiny">${t.updatedAt || ''}</div></div><span class="pill">${t.lifecycle || 'DRAFT'}</span></div>`;
          ev.addEventListener('click', () => setSelected({ type: 'thread', id: t.threadId, data: t }));
          tl.appendChild(ev);
        });
      }
    } catch { /* no threads endpoint yet */ }
  }
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
