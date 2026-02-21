import * as api from '../api.js';
import { getState, setSelected } from '../state.js';
import { toast } from '../components/toast.js';
import { initSplitter } from '../components/splitter.js';

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

let selectedThreadId = null;
const selectedForMerge = new Set();

export async function render() {
  selectedThreadId = null;
  selectedForMerge.clear();
  const pid = getState().currentProjectId;
  document.getElementById('centerTitle').textContent = 'THREADS';
  document.getElementById('centerSub').textContent =
    'Threads are named + created by the Thread Agent from intake clustering (topic + continuity + dates).';

  const body = document.getElementById('centerBody');
  body.innerHTML = `
    <div class="card">
      <div class="cardH">
        <div><b>Project Threads</b><div class="tiny">createdBy: thread-agent \u2022 select to inspect</div></div>
        <div class="row" id="threadActions"></div>
      </div>
      <div class="cardB">
        <div class="timeline" id="threadList"></div>
      </div>
      <div id="readingPane" style="display:none"></div>
    </div>`;

  initSplitter(body.querySelector('.card'));

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

    // Separate active and merged threads
    const active = threads.filter(t => t.lifecycle !== 'MERGED');
    const merged = threads.filter(t => t.lifecycle === 'MERGED');

    function updateMergeButton() {
      const actions = document.getElementById('threadActions');
      if (!actions) return;
      if (selectedForMerge.size >= 2) {
        if (!document.getElementById('mergeBtn')) {
          const btn = document.createElement('button');
          btn.className = 'btn';
          btn.id = 'mergeBtn';
          btn.textContent = `Merge ${selectedForMerge.size} Selected`;
          btn.addEventListener('click', () => showMergeModal(pid, threads));
          actions.appendChild(btn);
        } else {
          document.getElementById('mergeBtn').textContent = `Merge ${selectedForMerge.size} Selected`;
        }
      } else {
        document.getElementById('mergeBtn')?.remove();
      }
    }

    function renderThread(t, container, isMerged) {
      const el = document.createElement('div');
      el.className = 'event thread-row';
      el.dataset.threadId = t.threadId;
      if (isMerged) el.style.opacity = '0.4';

      const decCount = (t.decisions || []).length;
      const actCount = (t.actions || []).length;
      const preview = (t.content || t.summary || '').substring(0, 150) || 'No content yet';
      const statusLabel = t.lifecycle === 'MERGED' ? 'MERGED' : (t.status || t.lifecycle || 'DRAFT');

      el.innerHTML = `
        <div class="thread-row-top">
          <div style="display:flex;align-items:center;gap:6px;min-width:0;flex:1">
            ${!isMerged ? '<input type="checkbox" class="merge-cb" style="accent-color:var(--accent);flex-shrink:0">' : ''}
            <div style="min-width:0;flex:1">
              <div class="thread-row-title">${esc(t.title || 'Untitled')}</div>
              <div class="thread-row-preview">${esc(preview)}</div>
            </div>
          </div>
          <div style="display:flex;gap:4px;align-items:center;flex-shrink:0">
            <span class="pill">${esc(statusLabel)}</span>
            <span class="chip">${decCount} dec</span>
            <span class="chip">${actCount} act</span>
            ${!isMerged ? '<button class="btn danger thread-delete-btn" style="padding:2px 8px" title="Delete thread">\u2715</button>' : ''}
          </div>
        </div>`;

      // Merge checkbox
      const cb = el.querySelector('.merge-cb');
      if (cb) {
        cb.addEventListener('click', (e) => {
          e.stopPropagation();
          if (cb.checked) {
            selectedForMerge.add(t.threadId);
          } else {
            selectedForMerge.delete(t.threadId);
          }
          updateMergeButton();
        });
      }

      // Row click → select/deselect
      el.addEventListener('click', (e) => {
        if (e.target.closest('.thread-delete-btn') || e.target.closest('.merge-cb')) return;
        if (selectedThreadId === t.threadId) {
          selectedThreadId = null;
          el.classList.remove('active');
          document.getElementById('readingPane').style.display = 'none';
        } else {
          container.querySelectorAll('.thread-row.active').forEach(r => r.classList.remove('active'));
          selectedThreadId = t.threadId;
          el.classList.add('active');
          setSelected({ type: 'thread', id: t.threadId, data: t });
          renderReadingPane(pid, t);
        }
      });

      // Delete button
      if (!isMerged) {
        el.querySelector('.thread-delete-btn').addEventListener('click', async (e) => {
          e.stopPropagation();
          if (!confirm(`Delete thread "${t.title || 'Untitled'}"?`)) return;
          try {
            await api.threads.delete(pid, t.threadId);
            toast('thread deleted');
            document.dispatchEvent(new CustomEvent('jc:data-changed'));
            render();
          } catch (err) {
            toast('delete failed: ' + err.message);
          }
        });
      }

      container.appendChild(el);
    }

    container.innerHTML = '';
    active.forEach(t => renderThread(t, container, false));

    if (merged.length > 0) {
      const header = document.createElement('div');
      header.className = 'obj-section-header clickable';
      header.innerHTML = `<span>MERGED</span><span class="chip">${merged.length}</span>`;
      const mergedSection = document.createElement('div');
      mergedSection.className = 'timeline';
      mergedSection.style.display = 'none';
      header.addEventListener('click', () => {
        mergedSection.style.display = mergedSection.style.display === 'none' ? 'flex' : 'none';
      });
      container.appendChild(header);
      merged.forEach(t => renderThread(t, mergedSection, true));
      container.appendChild(mergedSection);
    }
  } catch {
    document.getElementById('threadList').innerHTML = '<div class="tiny">Could not load threads.</div>';
  }
}

function showMergeModal(pid, threads) {
  const selectedThreads = threads.filter(t => selectedForMerge.has(t.threadId));
  const defaultTitle = selectedThreads[0]?.title || 'Merged Thread';

  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `
    <div class="modal-box">
      <b style="font-size:14px">Merge ${selectedThreads.length} Threads</b>
      <div class="hr"></div>
      <div class="tiny" style="margin-bottom:8px">Threads to merge:</div>
      ${selectedThreads.map(t => `<div class="tiny" style="padding:2px 0">\u2022 ${esc(t.title || 'Untitled')}</div>`).join('')}
      <div class="hr"></div>
      <div class="tiny" style="margin-bottom:4px">Merged thread title:</div>
      <input type="text" id="mergeTitleInput" value="${esc(defaultTitle)}" style="width:100%;margin-bottom:12px">
      <div class="row">
        <button class="btn primary" id="mergeConfirm">Merge</button>
        <button class="btn ghost" id="mergeCancel">Cancel</button>
      </div>
    </div>`;

  document.body.appendChild(overlay);

  overlay.querySelector('#mergeCancel').addEventListener('click', () => overlay.remove());
  overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.remove(); });

  overlay.querySelector('#mergeConfirm').addEventListener('click', async () => {
    const targetTitle = overlay.querySelector('#mergeTitleInput').value.trim() || defaultTitle;
    try {
      await api.threads.merge(pid, {
        sourceThreadIds: [...selectedForMerge],
        targetTitle
      });
      toast('threads merged');
      document.dispatchEvent(new CustomEvent('jc:data-changed'));
      overlay.remove();
      render();
    } catch (err) {
      toast('merge failed: ' + err.message);
    }
  });
}

async function renderReadingPane(pid, t) {
  const pane = document.getElementById('readingPane');
  pane.style.display = 'block';

  const statusLabel = t.lifecycle === 'MERGED' ? 'MERGED' : (t.status || t.lifecycle || 'DRAFT');
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
  } else if (t.summary) {
    html += `<div class="hr"></div>`;
    html += `<div class="sectionLabel">Summary</div>`;
    html += `<div class="reading-pane-content">${esc(t.summary)}</div>`;
  } else {
    html += `<div class="hr"></div>`;
    html += `<div class="tiny">No content available.</div>`;
  }

  // Merge info
  if (t.mergedFromThreadIds && t.mergedFromThreadIds.length > 0) {
    html += `<div class="hr"></div>`;
    html += `<div class="sectionLabel">Merged From</div>`;
    t.mergedFromThreadIds.forEach(id => {
      html += `<div class="tiny" style="padding:2px 0">\u2022 ${esc(id)}</div>`;
    });
  }
  if (t.mergedIntoThreadId) {
    html += `<div class="hr"></div>`;
    html += `<div class="tiny">Merged into: ${esc(t.mergedIntoThreadId)}</div>`;
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
