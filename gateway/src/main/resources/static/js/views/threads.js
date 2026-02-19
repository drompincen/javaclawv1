import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { toast } from '../components/toast.js';

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

let selectedThreadId = null;

export async function render() {
  selectedThreadId = null;
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'THREADS';
  document.getElementById('centerSub').textContent =
    'Threads are named + created by the Thread Agent from intake clustering (topic + continuity + dates).';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Project Threads</b><div class="tiny">createdBy: thread-agent \u2022 select to inspect</div></div>
      </div>
      <div class="cardB">
        <div class="timeline" id="threadList"></div>
      </div>
      <div id="readingPane" style="display:none"></div>
    </div>`;

  if (!pid) {
    document.getElementById('threadList').innerHTML = '<div class="tiny">Select a project first.</div>';
    return;
  }

  try {
    const threads = await api.threads.list(pid);
    const container = document.getElementById('threadList');

    if (threads.length === 0) {
      container.innerHTML = '<div class="tiny">No threads yet. Run intake to generate threads.</div>';
      return;
    }

    container.innerHTML = '';
    threads.forEach(t => {
      const el = document.createElement('div');
      el.className = 'event thread-row';
      el.dataset.threadId = t.threadId;

      const decCount = (t.decisions || []).length;
      const actCount = (t.actions || []).length;
      const preview = (t.content || '').substring(0, 150);
      const statusLabel = t.status || t.lifecycle || 'DRAFT';

      el.innerHTML = `
        <div class="thread-row-top">
          <div style="min-width:0;flex:1">
            <div class="thread-row-title">${esc(t.title || 'Untitled')}</div>
            <div class="thread-row-preview">${esc(preview)}</div>
          </div>
          <div style="display:flex;gap:4px;align-items:center;flex-shrink:0">
            <span class="pill">${esc(statusLabel)}</span>
            <span class="chip">${decCount} dec</span>
            <span class="chip">${actCount} act</span>
            <button class="btn danger thread-delete-btn" style="padding:2px 8px" title="Delete thread">\u2715</button>
          </div>
        </div>`;

      // Row click → select/deselect
      el.addEventListener('click', (e) => {
        if (e.target.closest('.thread-delete-btn')) return;
        if (selectedThreadId === t.threadId) {
          selectedThreadId = null;
          el.classList.remove('active');
          document.getElementById('readingPane').style.display = 'none';
        } else {
          // Deselect previous
          container.querySelectorAll('.thread-row.active').forEach(r => r.classList.remove('active'));
          selectedThreadId = t.threadId;
          el.classList.add('active');
          setSelected({ type: 'thread', id: t.threadId, data: t });
          renderReadingPane(pid, t);
        }
      });

      // Delete button
      el.querySelector('.thread-delete-btn').addEventListener('click', async (e) => {
        e.stopPropagation();
        if (!confirm(`Delete thread "${t.title || 'Untitled'}"?`)) return;
        try {
          await api.threads.delete(pid, t.threadId);
          toast('thread deleted');
          render();
        } catch (err) {
          toast('delete failed: ' + err.message);
        }
      });

      container.appendChild(el);
    });
  } catch {
    document.getElementById('threadList').innerHTML = '<div class="tiny">Could not load threads.</div>';
  }
}

async function renderReadingPane(pid, t) {
  const pane = document.getElementById('readingPane');
  pane.style.display = 'block';

  const statusLabel = t.status || t.lifecycle || 'DRAFT';
  const decisions = t.decisions || [];
  const actions = t.actions || [];

  let html = `<div class="reading-pane-divider">Thread Detail</div>`;
  html += `<div class="reading-pane">`;
  html += `<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">`;
  html += `<b style="font-size:14px">${esc(t.title || 'Untitled')}</b>`;
  html += `<span class="pill">${esc(statusLabel)}</span>`;
  html += `</div>`;

  // Content
  if (t.content) {
    html += `<div class="hr"></div>`;
    html += `<div class="sectionLabel">Content</div>`;
    html += `<div class="reading-pane-content">${esc(t.content)}</div>`;
  }

  // Decisions
  if (decisions.length > 0) {
    html += `<div class="hr"></div>`;
    html += `<div class="sectionLabel">Decisions</div>`;
    decisions.forEach(d => {
      const by = d.decidedBy ? ` \u2014 ${esc(d.decidedBy)}` : '';
      html += `<div class="tiny" style="padding:2px 0">\u2714 ${esc(d.decision || d.title || JSON.stringify(d))}${by}</div>`;
    });
  }

  // Actions
  if (actions.length > 0) {
    html += `<div class="hr"></div>`;
    html += `<div class="sectionLabel">Action Items</div>`;
    actions.forEach(a => {
      const assignee = a.assignee ? ` \u2192 ${esc(a.assignee)}` : '';
      const st = a.status ? ` <span class="pill" style="font-size:10px">${esc(a.status)}</span>` : '';
      html += `<div class="tiny" style="padding:2px 0">\u25B6 ${esc(a.action || a.title || JSON.stringify(a))}${assignee}${st}</div>`;
    });
  }

  // Messages (async)
  html += `<div class="hr"></div>`;
  html += `<div class="sectionLabel">Messages</div>`;
  html += `<div id="readingPaneMessages"><div class="tiny">Loading messages...</div></div>`;
  html += `</div>`;

  pane.innerHTML = html;

  // Load messages
  try {
    const sessions = await api.sessions.list();
    const threadSession = sessions.find(s => s.metadata?.threadId === t.threadId);
    if (threadSession) {
      const msgs = await api.sessions.messages(threadSession.sessionId);
      const msgContainer = document.getElementById('readingPaneMessages');
      if (msgContainer && msgs.length > 0) {
        msgContainer.innerHTML = '';
        msgs.slice(0, 20).forEach(m => {
          const div = document.createElement('div');
          div.className = 'tiny';
          div.style.padding = '2px 0';
          const content = (m.content || '').substring(0, 200);
          div.innerHTML = `<b>[${esc(m.role)}]</b> ${esc(content)}`;
          msgContainer.appendChild(div);
        });
      } else if (msgContainer) {
        msgContainer.innerHTML = '<div class="tiny">No messages.</div>';
      }
    } else {
      const msgContainer = document.getElementById('readingPaneMessages');
      if (msgContainer) msgContainer.innerHTML = '<div class="tiny">No session found.</div>';
    }
  } catch {
    const msgContainer = document.getElementById('readingPaneMessages');
    if (msgContainer) msgContainer.innerHTML = '<div class="tiny">Could not load messages.</div>';
  }
}
