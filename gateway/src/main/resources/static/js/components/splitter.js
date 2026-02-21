/**
 * Outlook-style resizable splitter for summary/detail split views.
 *
 * Call initSplitter(cardEl) after rendering a card that contains:
 *   <div class="cardB"> ... summary list ... </div>
 *   <div id="readingPane">
 *     <div class="reading-pane-divider"> ... </div>
 *     <div class="reading-pane"> ... </div>
 *   </div>
 *
 * The divider becomes draggable. Dragging adjusts the flex ratio between
 * the summary (cardB) and the detail (reading-pane).
 */
export function initSplitter(cardEl) {
  if (!cardEl) return;

  // Apply flex layout to the card
  cardEl.style.display = 'flex';
  cardEl.style.flexDirection = 'column';
  cardEl.style.height = '100%';

  const cardBody = cardEl.querySelector('.cardB');
  const readingPane = cardEl.querySelector('#readingPane') || cardEl.querySelector('[id$="readingPane"]');
  if (!cardBody) return;

  // Make cardB scrollable and flexible
  cardBody.style.flex = '1';
  cardBody.style.overflow = 'auto';
  cardBody.style.minHeight = '80px';

  // Make the parent panelBody fill without scrolling (the children scroll instead)
  const panelBody = cardEl.closest('.panelBody');
  if (panelBody) {
    panelBody.style.overflow = 'hidden';
    panelBody.style.display = 'flex';
    panelBody.style.flexDirection = 'column';
  }

  // Observe the readingPane for when it becomes visible
  if (readingPane) {
    const observer = new MutationObserver(() => {
      if (readingPane.style.display !== 'none') {
        attachDrag(cardEl, cardBody, readingPane);
        observer.disconnect();
      }
    });
    observer.observe(readingPane, { attributes: true, attributeFilter: ['style'] });

    // Also attach immediately if already visible
    if (readingPane.style.display !== 'none') {
      attachDrag(cardEl, cardBody, readingPane);
      observer.disconnect();
    }
  }
}

function attachDrag(cardEl, cardBody, readingPane) {
  // Already attached to this readingPane?
  if (readingPane.dataset.splitterAttached) return;
  readingPane.dataset.splitterAttached = 'true';

  // Default split: 50/50
  const savedRatio = sessionStorage.getItem('splitter-ratio');
  const ratio = savedRatio ? parseFloat(savedRatio) : 0.5;

  function applyStyles() {
    readingPane.style.display = 'flex';
    readingPane.style.flexDirection = 'column';
    readingPane.style.minHeight = '100px';
    cardBody.style.flex = String(ratio);
    readingPane.style.flex = String(1 - ratio);

    const rpContent = readingPane.querySelector('.reading-pane');
    if (rpContent) {
      rpContent.style.flex = '1';
      rpContent.style.overflow = 'auto';
      rpContent.style.maxHeight = 'none';
    }
  }

  applyStyles();

  // Re-apply styles when innerHTML changes (new divider/content)
  const childObserver = new MutationObserver(() => applyStyles());
  childObserver.observe(readingPane, { childList: true });

  // Drag handling — use event delegation on readingPane so it survives innerHTML replacements
  let dragging = false;

  readingPane.addEventListener('mousedown', (e) => {
    if (!e.target.closest('.reading-pane-divider')) return;
    e.preventDefault();
    dragging = true;
    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';
  });

  document.addEventListener('mousemove', (e) => {
    if (!dragging) return;
    const cardRect = cardEl.getBoundingClientRect();
    const headerEl = cardEl.querySelector('.cardH');
    const headerHeight = headerEl ? headerEl.offsetHeight : 0;
    const availableHeight = cardRect.height - headerHeight;
    const mouseY = e.clientY - cardRect.top - headerHeight;
    const newRatio = Math.max(0.15, Math.min(0.85, mouseY / availableHeight));

    cardBody.style.flex = String(newRatio);
    readingPane.style.flex = String(1 - newRatio);
    sessionStorage.setItem('splitter-ratio', String(newRatio));
  });

  document.addEventListener('mouseup', () => {
    if (dragging) {
      dragging = false;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    }
  });
}
