/**
 * ChromeDriver E2E test: real-time UI updates after intake pipeline.
 *
 * Verifies that after submitting intake content, agent tiles blink (.running)
 * and nav badge counts update WITHOUT a manual page refresh.
 *
 * Run:
 *   cd gateway/src/main/resources/static/tests
 *   npm install
 *   node intake-realtime.test.mjs
 */
import { Builder, By, until } from 'selenium-webdriver';
import chrome from 'selenium-webdriver/chrome.js';
import { spawn, execSync } from 'child_process';
import { fileURLToPath } from 'url';
import path from 'path';
import fs from 'fs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(__dirname, '..', '..', '..', '..', '..', '..');

const PORT = 18081;
const BASE = `http://localhost:${PORT}`;
const STARTUP_TIMEOUT = 90_000;   // max ms to wait for backend
const POLL_INTERVAL = 1000;       // ms between server-ready polls

// ── Helpers ──────────────────────────────────────────────────────

function green(s) { return `\x1b[32m${s}\x1b[0m`; }
function red(s)   { return `\x1b[31m${s}\x1b[0m`; }
function dim(s)   { return `\x1b[90m${s}\x1b[0m`; }

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
  console.log(`        ${reason}`);
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

// ── Backend lifecycle ────────────────────────────────────────────

let backend = null;

function startBackend() {
  return new Promise((resolve, reject) => {
    console.log(dim(`  Starting backend: jbang --fresh javaclaw.java --testMode --port ${PORT}`));
    backend = spawn('jbang', ['--fresh', 'javaclaw.java', '--testMode', '--port', String(PORT)], {
      cwd: PROJECT_ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env },
    });

    let output = '';
    backend.stdout.on('data', (d) => { output += d.toString(); });
    backend.stderr.on('data', (d) => { output += d.toString(); });
    backend.on('error', (err) => reject(new Error('Failed to start backend: ' + err.message)));

    // Poll /api/projects until it responds 200
    const start = Date.now();
    const timer = setInterval(async () => {
      if (Date.now() - start > STARTUP_TIMEOUT) {
        clearInterval(timer);
        reject(new Error(`Backend did not start within ${STARTUP_TIMEOUT}ms.\nOutput:\n${output.slice(-2000)}`));
        return;
      }
      try {
        const res = await fetch(`${BASE}/api/projects`);
        if (res.ok) {
          clearInterval(timer);
          console.log(dim(`  Backend ready in ${Date.now() - start}ms`));
          resolve();
        }
      } catch { /* not ready yet */ }
    }, POLL_INTERVAL);
  });
}

function killBackend() {
  if (backend && !backend.killed) {
    backend.kill('SIGTERM');
    // Force kill after 3s
    setTimeout(() => {
      try { backend.kill('SIGKILL'); } catch { /* already dead */ }
    }, 3000);
  }
  // Also kill any process holding the port (in case child spawned sub-processes)
  try {
    execSync(`lsof -ti :${PORT} | xargs kill -9 2>/dev/null`, { stdio: 'ignore' });
  } catch { /* nothing on port */ }
}

// ── Chrome lifecycle ─────────────────────────────────────────────

/** Locate a usable chromedriver binary for the current platform. */
function findChromeDriver() {
  // 1. Snap-packaged chromedriver (common on Ubuntu/WSL with snap chromium)
  try {
    const out = execSync('snap run chromium.chromedriver --version 2>/dev/null', { encoding: 'utf-8' });
    if (out.includes('ChromeDriver')) {
      // Create a small wrapper script because Selenium Service needs a direct path
      const wrapper = '/tmp/chromedriver-snap-wrapper.sh';
      fs.writeFileSync(wrapper, '#!/bin/sh\nexec snap run chromium.chromedriver "$@"\n', { mode: 0o755 });
      console.log(dim(`  Using snap chromedriver: ${out.trim()}`));
      return wrapper;
    }
  } catch { /* no snap chromedriver */ }

  // 2. System chromedriver on PATH
  try {
    const out = execSync('chromedriver --version 2>/dev/null', { encoding: 'utf-8' });
    if (out.includes('ChromeDriver')) {
      console.log(dim(`  Using system chromedriver: ${out.trim()}`));
      return 'chromedriver';
    }
  } catch { /* not found */ }

  // 3. Fall back — let Selenium auto-detect (may fail on non-x86)
  console.log(dim('  No explicit chromedriver found; relying on Selenium auto-detect'));
  return null;
}

/** Locate the Chromium/Chrome browser binary. */
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

  const driver = await builder.build();
  return driver;
}

// ── Main test flow ───────────────────────────────────────────────

let driver = null;

async function run() {
  console.log('\n── E2E: Real-Time UI Updates After Intake ──\n');

  // 1. Start backend
  try {
    await startBackend();
    ok('Backend started in testMode');
  } catch (e) {
    fail('Backend started in testMode', e.message);
    return; // cannot continue
  }

  // 2. Launch Chrome
  try {
    driver = await launchChrome();
    ok('Chrome launched (headless)');
  } catch (e) {
    fail('Chrome launched (headless)', e.message);
    return;
  }

  try {
    // 3. Navigate to UI
    await driver.get(`${BASE}/index.html`);
    ok('Navigated to UI');

    // 4. Wait for WebSocket "WS LIVE"
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

    // 5. Create project via REST API (use unique name to avoid 409 on re-runs)
    let projectId;
    const projectName = `E2E Test ${Date.now()}`;
    try {
      const res = await fetch(`${BASE}/api/projects`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: projectName }),
      });
      if (!res.ok) throw new Error(`POST /api/projects -> ${res.status}`);
      const data = await res.json();
      projectId = data.projectId || data.id || data._id;
      if (!projectId) throw new Error('No projectId in response: ' + JSON.stringify(data));
      ok(`Project created: ${projectId}`);
    } catch (e) {
      fail('Project created via REST', e.message);
      return;
    }

    // 6. Select project in dropdown
    try {
      // Trigger a project list reload in the browser so the new project appears
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
            if (val === projectId) return true;
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
        projectId,
      );
      // Give the UI a moment to react
      await sleep(1500);
      ok('Project selected in dropdown');
    } catch (e) {
      fail('Project selected in dropdown', e.message);
      return;
    }

    // 7. Navigate to intake (click nav item)
    try {
      const navItem = await driver.findElement(By.css('.navItem[data-view="intake"]'));
      await navItem.click();
      await sleep(500);
      ok('Navigated to Intake view');
    } catch (e) {
      fail('Navigated to Intake view', e.message);
      return;
    }

    // 8. Submit intake content
    const MEETING_NOTES = `Sprint planning meeting notes - March 2026.
Discussed backend API refactoring for better performance.
Action items:
- Sarah: migrate user service to async by Friday
- Mike: update deployment scripts for staging
- Team: review security audit findings
Risks: tight deadline for Q2 release, dependency on external vendor API.
Decisions: adopt trunk-based development, weekly demos starting next Monday.`;

    try {
      const textarea = await driver.findElement(By.id('intakeText'));
      await textarea.sendKeys(MEETING_NOTES);

      const sendBtn = await driver.findElement(By.id('intakeSend'));
      await sendBtn.click();
      ok('Intake content submitted');
    } catch (e) {
      fail('Intake content submitted', e.message);
      return;
    }

    // 9. Assert agent tile reaches .running state (blinking)
    try {
      await poll(
        'agent tile .running',
        async () => {
          const tiles = await driver.findElements(By.css('.agent-tile.running'));
          return tiles.length > 0;
        },
        15_000,
        300,
      );
      ok('Agent tile reached .running state (blinking)');
    } catch (e) {
      // Capture diagnostic info
      const tiles = await driver.findElements(By.css('.agent-tile'));
      const classes = [];
      for (const t of tiles) {
        classes.push(await t.getAttribute('class'));
      }
      fail('Agent tile reached .running state (blinking)',
        `${e.message}\n        Tile classes found: [${classes.join(', ')}]`);
    }

    // 10. Assert agent tile reaches .done state (completion)
    try {
      await poll(
        'agent tile .done',
        async () => {
          const tiles = await driver.findElements(By.css('.agent-tile.done'));
          return tiles.length > 0;
        },
        30_000,
        500,
      );
      ok('Agent tile reached .done state');
    } catch (e) {
      const tiles = await driver.findElements(By.css('.agent-tile'));
      const classes = [];
      for (const t of tiles) {
        classes.push(await t.getAttribute('class'));
      }
      fail('Agent tile reached .done state',
        `${e.message}\n        Tile classes found: [${classes.join(', ')}]`);
    }

    // 11. Assert threads badge updated (count > 0)
    try {
      await poll(
        'threads badge > 0',
        async () => {
          const badge = await driver.findElement(By.id('navThreadsBadge'));
          const text = await badge.getText();
          const num = parseInt(text, 10);
          return !isNaN(num) && num > 0;
        },
        30_000,
        500,
      );
      const badge = await driver.findElement(By.id('navThreadsBadge'));
      const count = await badge.getText();
      ok(`Threads badge updated: ${count}`);
    } catch (e) {
      // Grab current badge value for diagnostics
      let badgeText = '?';
      try {
        const badge = await driver.findElement(By.id('navThreadsBadge'));
        badgeText = await badge.getText();
      } catch { /* ignore */ }
      // Also check via REST API if threads exist in the backend
      let apiThreads = '?';
      try {
        const res = await fetch(`${BASE}/api/projects/${projectId}/threads`);
        const threads = await res.json();
        apiThreads = `${threads.length} threads: ${threads.map(t => t.title).join(', ')}`;
      } catch (err) { apiThreads = 'API error: ' + err.message; }
      fail('Threads badge updated (count > 0)',
        `${e.message}\n        Badge text: "${badgeText}"\n        REST API: ${apiThreads}`);
    }

  } catch (e) {
    fail('Unexpected error', e.message);
  }
}

// ── Entry point ──────────────────────────────────────────────────

async function main() {
  try {
    await run();
  } finally {
    // Cleanup
    console.log(dim('\n  Cleaning up...'));
    if (driver) {
      try { await driver.quit(); } catch { /* ignore */ }
    }
    killBackend();
    // Give processes a moment to die
    await sleep(1000);
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
