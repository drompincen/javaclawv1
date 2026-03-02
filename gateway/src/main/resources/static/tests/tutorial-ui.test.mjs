/**
 * ChromeDriver UI test runner for tutorial groups.
 *
 * Connects to an ALREADY-RUNNING server (tutorials start it) and verifies
 * that the browser UI correctly reflects entities created by each tutorial group.
 *
 * Usage:
 *   cd gateway/src/main/resources/static/tests
 *   npm install
 *   node tutorial-ui.test.mjs --group 1 --port 18080
 *   node tutorial-ui.test.mjs --group 1 --port 18080 --project-id <PID>
 */
import { Builder, By, until } from 'selenium-webdriver';
import chrome from 'selenium-webdriver/chrome.js';
import { execSync } from 'child_process';
import fs from 'fs';

// ── Arg parsing ──────────────────────────────────────────────────

const args = process.argv.slice(2);
function arg(name) {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : null;
}

const GROUP = parseInt(arg('group') || '1', 10);
const PORT  = parseInt(arg('port')  || '18080', 10);
let   PROJECT_ID = arg('project-id') || null;
const BASE  = `http://localhost:${PORT}`;

if (![1, 2].includes(GROUP)) {
  console.error('--group must be 1 or 2');
  process.exit(1);
}

// ── Colour helpers ───────────────────────────────────────────────

function green(s) { return `\x1b[32m${s}\x1b[0m`; }
function red(s)   { return `\x1b[31m${s}\x1b[0m`; }
function dim(s)   { return `\x1b[90m${s}\x1b[0m`; }

// ── Pass / fail tracking ─────────────────────────────────────────

let passed = 0;
let failed = 0;
const failures = [];

function ok(name) {
  passed++;
  console.log(`  ${green('PASS')} ${name}`);
}
function fail(name, reason) {
  failed++;
  failures.push({ name, reason });
  console.log(`  ${red('FAIL')} ${name}`);
  if (reason) console.log(`        ${reason}`);
}

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

/** Poll a predicate until it returns truthy or timeout expires. */
async function poll(label, fn, timeoutMs, intervalMs = 500) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const result = await fn();
      if (result) return result;
    } catch { /* keep trying */ }
    await sleep(intervalMs);
  }
  throw new Error(`Timed out after ${timeoutMs}ms: ${label}`);
}

// ── Chrome lifecycle (copied from intake-realtime.test.mjs) ─────

function findChromeDriver() {
  try {
    const out = execSync('snap run chromium.chromedriver --version 2>/dev/null', { encoding: 'utf-8' });
    if (out.includes('ChromeDriver')) {
      const wrapper = '/tmp/chromedriver-snap-wrapper.sh';
      fs.writeFileSync(wrapper, '#!/bin/sh\nexec snap run chromium.chromedriver "$@"\n', { mode: 0o755 });
      console.log(dim(`  Using snap chromedriver: ${out.trim()}`));
      return wrapper;
    }
  } catch { /* no snap chromedriver */ }

  try {
    const out = execSync('chromedriver --version 2>/dev/null', { encoding: 'utf-8' });
    if (out.includes('ChromeDriver')) {
      console.log(dim(`  Using system chromedriver: ${out.trim()}`));
      return 'chromedriver';
    }
  } catch { /* not found */ }

  console.log(dim('  No explicit chromedriver found; relying on Selenium auto-detect'));
  return null;
}

function findChromeBinary() {
  const candidates = [
    '/usr/bin/chromium-browser',
    '/usr/bin/chromium',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/google-chrome',
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  return null;
}

async function launchChrome() {
  const options = new chrome.Options();
  options.addArguments(
    '--headless=new',
    '--no-sandbox',
    '--disable-dev-shm-usage',
    '--disable-gpu',
    '--window-size=1280,900',
  );

  const chromeBin = findChromeBinary();
  if (chromeBin) {
    options.setChromeBinaryPath(chromeBin);
    console.log(dim(`  Chrome binary: ${chromeBin}`));
  }

  const builder = new Builder()
    .forBrowser('chrome')
    .setChromeOptions(options);

  const cdPath = findChromeDriver();
  if (cdPath) {
    const service = new chrome.ServiceBuilder(cdPath);
    builder.setChromeService(service);
  }

  return builder.build();
}

// ── Common UI assertion helpers ──────────────────────────────────

/** Click a nav item and verify the center title changes. */
async function navigateToView(driver, viewKey, expectedTitle) {
  const navItem = await driver.findElement(By.css(`.navItem[data-view="${viewKey}"]`));
  await navItem.click();
  await sleep(600);
  const title = await driver.findElement(By.id('centerTitle')).getText();
  if (title === expectedTitle) {
    ok(`Navigated to ${expectedTitle} view`);
    return true;
  } else {
    fail(`Navigated to ${expectedTitle} view`, `centerTitle = "${title}"`);
    return false;
  }
}

/** Read a nav badge count (returns number or 0). */
async function getNavBadge(driver, badgeId) {
  try {
    const badge = await driver.findElement(By.id(badgeId));
    const text = await badge.getText();
    return parseInt(text, 10) || 0;
  } catch {
    return 0;
  }
}

/** Assert a badge shows a count >= expected. */
async function assertBadgeGte(driver, badgeId, minimum, label) {
  const count = await getNavBadge(driver, badgeId);
  if (count >= minimum) {
    ok(`${label} badge shows ${count} (>= ${minimum})`);
  } else {
    fail(`${label} badge shows ${count} (>= ${minimum})`, `got ${count}`);
  }
  return count;
}

/** Count elements matching a CSS selector in the page. */
async function countElements(driver, selector) {
  const els = await driver.findElements(By.css(selector));
  return els.length;
}

/** Assert element count >= expected. */
async function assertRowCountGte(driver, selector, minimum, label) {
  const count = await countElements(driver, selector);
  if (count >= minimum) {
    ok(`${label} row count ${count} (>= ${minimum})`);
  } else {
    fail(`${label} row count ${count} (>= ${minimum})`, `found ${count}`);
  }
  return count;
}

/** Assert text appears somewhere in #centerBody. */
async function assertBodyContains(driver, text, label) {
  try {
    const body = await driver.findElement(By.id('centerBody'));
    const html = await body.getAttribute('innerHTML');
    if (html.includes(text)) {
      ok(label || `centerBody contains "${text}"`);
      return true;
    } else {
      fail(label || `centerBody contains "${text}"`, 'not found in centerBody');
      return false;
    }
  } catch (e) {
    fail(label || `centerBody contains "${text}"`, e.message);
    return false;
  }
}

/** Assert an element exists by CSS selector. */
async function assertElementExists(driver, selector, label) {
  try {
    const els = await driver.findElements(By.css(selector));
    if (els.length > 0) {
      ok(label);
      return true;
    } else {
      fail(label, `selector "${selector}" not found`);
      return false;
    }
  } catch (e) {
    fail(label, e.message);
    return false;
  }
}

/** Assert an element's text content matches expected. */
async function assertElementText(driver, selector, expectedText, label) {
  try {
    const el = await driver.findElement(By.css(selector));
    const text = await el.getText();
    if (text.includes(expectedText)) {
      ok(label);
      return true;
    } else {
      fail(label, `expected "${expectedText}", got "${text}"`);
      return false;
    }
  } catch (e) {
    fail(label, e.message);
    return false;
  }
}

// ── Auto-detect project ID if not provided ───────────────────────

async function resolveProjectId() {
  if (PROJECT_ID) return PROJECT_ID;
  try {
    const res = await fetch(`${BASE}/api/projects`);
    const projects = await res.json();
    if (projects.length > 0) {
      PROJECT_ID = projects[0].projectId;
      ok(`Auto-detected project: ${PROJECT_ID} (${projects[0].name})`);
      return PROJECT_ID;
    }
  } catch { /* ignore */ }
  fail('Auto-detect project', 'No projects found on server');
  return null;
}

// ── Group test functions ─────────────────────────────────────────

/**
 * Group 1: Intake Pipelines — after tutorials 01, 02, 03, 04
 * Entities: intake-generated threads, tickets, objectives, resources, blindspots
 */
async function group1Tests(driver) {
  console.log(dim('\n  ── Group 1: Intake Pipeline Views ──'));

  // Intake view — verify controls present
  await navigateToView(driver, 'intake', 'INTAKE');
  await assertElementExists(driver, '#intakeText', 'Intake textarea exists');
  await assertElementExists(driver, '#intakeSend', 'Intake send button exists');
  await assertElementExists(driver, '#askClawInput', 'Ask Claw input exists');

  // Threads — pipeline-generated
  await navigateToView(driver, 'threads', 'THREADS');
  await assertBadgeGte(driver, 'navThreadsBadge', 1, 'Threads (pipeline-generated)');
  await assertRowCountGte(driver, '.thread-row', 1, 'Pipeline threads');

  // Tickets — pipeline-generated
  await navigateToView(driver, 'tickets', 'TICKETS');
  await assertBadgeGte(driver, 'navTicketsBadge', 1, 'Tickets (pipeline-generated)');

  // Objectives — pipeline-generated
  await navigateToView(driver, 'objectives', 'OBJECTIVES');
  await assertBadgeGte(driver, 'navObjBadge', 1, 'Objectives (pipeline-generated)');

  // Resources — created by pipeline
  await navigateToView(driver, 'resources', 'RESOURCES');
  await assertBadgeGte(driver, 'navResBadge', 1, 'Resources (pipeline-created)');
}

/**
 * Group 2: Agent Orchestration & Ask Claw — after tutorials 05, 06, 07, 08
 * Entities: agent-created objectives, phases, checklists, ask-claw results
 */
async function group2Tests(driver) {
  console.log(dim('\n  ── Group 2: Agent & Ask Claw Views ──'));

  // Objectives — agent-created
  await navigateToView(driver, 'objectives', 'OBJECTIVES');
  await assertBadgeGte(driver, 'navObjBadge', 1, 'Objectives (agent-created)');

  // Plans — agent-created phases
  await navigateToView(driver, 'plans', 'PLANS');
  await assertBadgeGte(driver, 'navPlansBadge', 1, 'Plans (agent-created)');

  // Checklists — agent-created
  await navigateToView(driver, 'checklists', 'CHECKLISTS');
  await assertBadgeGte(driver, 'navChkBadge', 1, 'Checklists (agent-created)');

  // Token counter should show non-zero usage
  await assertElementExists(driver, '#tokenCount', 'Token counter exists');
  try {
    const tokenEl = await driver.findElement(By.id('tokenCount'));
    const tokenText = await tokenEl.getText();
    // Expect something like "🪙 1234 tokens | 5 calls | avg 200ms"
    const match = tokenText.match(/(\d+)\s*tokens/);
    if (match && parseInt(match[1], 10) > 0) {
      ok(`Token counter shows usage: ${tokenText.trim()}`);
    } else {
      fail('Token counter shows usage', `text = "${tokenText}"`);
    }
  } catch (e) {
    fail('Token counter shows usage', e.message);
  }

  // Agent status grid present
  await assertElementExists(driver, '#log', 'Agent status grid exists');
  await assertElementExists(driver, '.agent-tile', 'Agent tiles rendered');

  // Ask Claw input interactable
  await navigateToView(driver, 'intake', 'INTAKE');
  try {
    const askInput = await driver.findElement(By.id('askClawInput'));
    await askInput.clear();
    await askInput.sendKeys('test question');
    const val = await askInput.getAttribute('value');
    if (val === 'test question') {
      ok('Ask Claw input is interactable');
    } else {
      fail('Ask Claw input is interactable', `value = "${val}"`);
    }
    await askInput.clear();
  } catch (e) {
    fail('Ask Claw input is interactable', e.message);
  }
}

// ── Main test flow ───────────────────────────────────────────────

async function run(driver) {
  console.log(`\n── Tutorial UI Tests: Group ${GROUP} (port ${PORT}) ──\n`);

  // 1. Verify server is running
  try {
    const res = await fetch(`${BASE}/api/projects`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    ok('Server is running');
  } catch (e) {
    fail('Server is running', `${e.message} — is the server started on port ${PORT}?`);
    return;
  }

  // 2. Navigate to UI
  await driver.get(`${BASE}/index.html`);
  ok('Navigated to UI');

  // 3. Wait for WebSocket "WS LIVE"
  try {
    await poll(
      'WS LIVE badge',
      async () => {
        const badge = await driver.findElement(By.id('wsBadge'));
        const text = await badge.getText();
        return text.includes('WS LIVE');
      },
      15_000,
    );
    ok('WebSocket connected (WS LIVE)');
  } catch (e) {
    fail('WebSocket connected (WS LIVE)', e.message);
    return;
  }

  // 4. Resolve project ID
  const pid = await resolveProjectId();
  if (!pid) return;

  // 5. Select project in dropdown
  try {
    // Refresh project list in browser
    await driver.executeScript(`
      const sel = document.getElementById('projectSelect');
      const res = await fetch('/api/projects');
      const projects = await res.json();
      sel.innerHTML = '<option value="">— select project —</option>';
      projects.forEach(p => {
        const opt = document.createElement('option');
        opt.value = p.projectId;
        opt.textContent = p.name || p.projectId;
        sel.appendChild(opt);
      });
    `);

    await poll(
      'project appears in dropdown',
      async () => {
        const select = await driver.findElement(By.id('projectSelect'));
        const options = await select.findElements(By.tagName('option'));
        for (const opt of options) {
          const val = await opt.getAttribute('value');
          if (val === pid) return true;
        }
        return false;
      },
      10_000,
    );

    // Select it via JS to trigger change event
    await driver.executeScript(
      `const sel = document.getElementById('projectSelect');
       sel.value = arguments[0];
       sel.dispatchEvent(new Event('change'));`,
      pid,
    );
    await sleep(1500);
    ok('Project selected in dropdown');
  } catch (e) {
    fail('Project selected in dropdown', e.message);
    return;
  }

  // 6. Verify WS badge still shows LIVE after project switch
  await assertElementText(driver, '#wsBadge', 'WS LIVE', 'WebSocket still LIVE after project select');

  // 7. Run group-specific tests
  switch (GROUP) {
    case 1: await group1Tests(driver); break;
    case 2: await group2Tests(driver); break;
  }
}

// ── Entry point ──────────────────────────────────────────────────

async function main() {
  let driver = null;
  try {
    driver = await launchChrome();
    ok('Chrome launched (headless)');
    await run(driver);
  } catch (e) {
    fail('Unexpected error', e.message);
  } finally {
    console.log(dim('\n  Cleaning up...'));
    if (driver) {
      try { await driver.quit(); } catch { /* ignore */ }
    }
  }

  // Summary
  console.log('\n' + '='.repeat(50));
  console.log(`  ${passed} passed, ${failed} failed, ${passed + failed} total`);
  if (failures.length > 0) {
    console.log(`\n${red('Failures:')}`);
    failures.forEach(f => console.log(`  ${red('FAIL')} ${f.name}: ${f.reason}`));
  }
  console.log('');
  process.exit(failed > 0 ? 1 : 0);
}

main();
