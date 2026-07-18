import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { createServer as createNetServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, isAbsolute, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { createServer as createViteServer } from 'vite'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const harnessRoot = mkdtempSync(join(projectRoot, '.ui-primitives-'))
const browserProfile = mkdtempSync(join(tmpdir(), 'fx-ui-primitives-chrome-'))

assert(isWithinProject(harnessRoot), `Harness directory must stay within ${projectRoot}`)
assert(isWithinDirectory(tmpdir(), browserProfile), `Browser profile must stay within ${tmpdir()}`)

let viteServer
let browser
let cdp

try {
  writeHarness()
  viteServer = await startHarnessServer()
  const port = await reservePort()
  browser = spawnBrowser(port)
  const target = await waitForBrowserTarget(port)
  cdp = await connectCdp(target.webSocketDebuggerUrl)

  const address = viteServer.httpServer?.address()
  assert(address && typeof address === 'object', 'Vite harness must expose a listening address')
  const pageUrl = `http://127.0.0.1:${address.port}/`
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')
  await cdp.send('Page.navigate', { url: pageUrl })
  await waitFor(
    async () => evaluate('document.querySelector(".test-select > button") !== null'),
    'UI harness render',
    {
      attempts: 300,
      snapshot: () => evaluate(`({ href: location.href, readyState: document.readyState, body: document.body?.innerText ?? '' })`)
    }
  )

  const initial = await evaluate(`(() => {
    const select = document.querySelector('.test-select > button')
    const empty = document.querySelector('.empty-select > button')
    const icon = document.querySelector('.custom-icon')
    const labelledBy = select?.getAttribute('aria-labelledby') ?? ''
    return {
      selectType: select?.getAttribute('type'),
      hasPopup: select?.getAttribute('aria-haspopup'),
      expanded: select?.getAttribute('aria-expanded'),
      labelledBy,
      labelledByHasLabel: labelledBy.split(' ').includes('select-label'),
      emptyDisabled: empty?.disabled,
      iconType: icon?.getAttribute('type'),
      iconLabel: icon?.getAttribute('aria-label'),
      iconTitle: icon?.getAttribute('title'),
      iconDisabled: icon?.disabled,
      iconCustomClass: icon?.classList.contains('custom-icon'),
      minHeight: getComputedStyle(select).minHeight
    }
  })()`)

  assert.deepEqual(initial, {
    selectType: 'button',
    hasPopup: 'listbox',
    expanded: 'false',
    labelledBy: initial.labelledBy,
    labelledByHasLabel: true,
    emptyDisabled: true,
    iconType: 'button',
    iconLabel: 'Run action',
    iconTitle: 'Run action',
    iconDisabled: true,
    iconCustomClass: true,
    minHeight: '36px'
  })
  assert.match(initial.labelledBy, /^select-label\s+.+-button$/u)

  await click('.test-select > button')
  await waitFor(async () => evaluate(`document.querySelector('.test-select > button')?.getAttribute('aria-expanded') === 'true'`), 'select open')
  const openState = await evaluate(`(() => {
    const listbox = document.querySelector('.test-select [role="listbox"]')
    return {
      listbox: Boolean(listbox),
      labelledBy: listbox?.getAttribute('aria-labelledby'),
      optionCount: listbox?.querySelectorAll('[role="option"]').length,
      selected: listbox?.querySelector('[aria-selected="true"]')?.dataset.value
    }
  })()`)
  assert.deepEqual(openState, { listbox: true, labelledBy: 'select-label', optionCount: 3, selected: 'beta' })

  await click('.test-select [role="option"][data-value="alpha"]')
  await waitFor(async () => evaluate(`document.querySelector('#selected')?.textContent === 'alpha'`), 'click selection')
  assert.equal(await isExpanded(), false)

  await click('.test-select > button')
  await waitFor(isExpanded, 'select reopen')
  await evaluate(`document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))`)
  await waitFor(async () => !(await isExpanded()), 'outside pointer close')

  await dispatchKey('ArrowUp')
  await waitFor(isExpanded, 'ArrowUp open')
  assert.equal(await activeValue(), 'gamma')
  await dispatchKey('Enter')
  await waitFor(async () => evaluate(`document.querySelector('#selected')?.textContent === 'gamma'`), 'Enter selection')
  assert.equal(await isExpanded(), false)

  await dispatchKey('Home')
  await waitFor(isExpanded, 'Home open')
  assert.equal(await activeValue(), 'alpha')
  await dispatchKey('Escape')
  await waitFor(async () => !(await isExpanded()), 'Escape close')

  await dispatchKey('End')
  await waitFor(isExpanded, 'End open')
  assert.equal(await activeValue(), 'gamma')
  await dispatchKey('Tab')
  await waitFor(async () => !(await isExpanded()), 'Tab close')

  await dispatchKey(' ')
  await waitFor(isExpanded, 'Space open')
  await dispatchKey(' ')
  await waitFor(async () => !(await isExpanded()), 'Space selection close')

  console.log('UI primitive browser smoke passed: click, outside click, keyboard, ARIA, disabled and custom-class contracts.')
} finally {
  cdp?.close()
  await stopBrowser(browser)
  await viteServer?.close()
  removeTemporaryDirectory(projectRoot, harnessRoot)
  removeTemporaryDirectory(tmpdir(), browserProfile)
}

function writeHarness() {
  writeFileSync(
    join(harnessRoot, 'index.html'),
    '<!doctype html><html><head><meta charset="UTF-8" /></head><body><div id="root"></div><script type="module" src="/main.jsx"></script></body></html>\n'
  )
  writeFileSync(
    join(harnessRoot, 'main.jsx'),
    `import React, { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { IconButton, SelectField } from '../packages/ui/src/index.ts'
import '../packages/ui/src/theme/theme.css'

const options = [
  { value: 'alpha', label: 'Alpha' },
  { value: 'beta', label: 'Beta' },
  { value: 'gamma', label: 'Gamma' }
]

function Harness() {
  const [value, setValue] = useState('beta')
  return (
    <main id="outside" tabIndex={-1}>
      <span id="select-label">Choose value</span>
      <SelectField className="test-select" labelledBy="select-label" value={value} options={options} onChange={setValue} />
      <SelectField className="empty-select" ariaLabel="Empty select" value="" options={[]} onChange={() => {}} />
      <IconButton className="custom-icon" icon={<span aria-hidden="true">+</span>} label="Run action" disabled />
      <output id="selected">{value}</output>
    </main>
  )
}

createRoot(document.getElementById('root')).render(<Harness />)
`
  )
}

async function startHarnessServer() {
  const server = await createViteServer({
    root: harnessRoot,
    logLevel: 'error',
    server: {
      host: '127.0.0.1',
      port: 0,
      strictPort: false,
      fs: { allow: [projectRoot, harnessRoot] }
    }
  })
  await server.listen()
  return server
}

function spawnBrowser(port) {
  const executable = findBrowserExecutable()
  return spawn(
    executable,
    [
      '--headless=new',
      '--disable-gpu',
      '--no-first-run',
      '--no-default-browser-check',
      `--remote-debugging-port=${port}`,
      `--user-data-dir=${browserProfile}`,
      'about:blank'
    ],
    { stdio: 'ignore', windowsHide: true }
  )
}

function findBrowserExecutable() {
  const candidates = [
    process.env.CHROME_PATH,
    join(process.env.PROGRAMFILES ?? '', 'Google/Chrome/Application/chrome.exe'),
    join(process.env['PROGRAMFILES(X86)'] ?? '', 'Google/Chrome/Application/chrome.exe'),
    join(process.env.PROGRAMFILES ?? '', 'Microsoft/Edge/Application/msedge.exe')
  ].filter(Boolean)
  const executable = candidates.find((candidate) => existsSync(candidate))
  assert(executable, 'Chrome or Edge is required for UI primitive browser smoke')
  return executable
}

async function reservePort() {
  const server = createNetServer()
  await new Promise((resolvePromise, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolvePromise)
  })
  const address = server.address()
  assert(address && typeof address === 'object')
  await new Promise((resolvePromise, reject) => server.close((error) => (error ? reject(error) : resolvePromise())))
  return address.port
}

async function waitForBrowserTarget(port) {
  let lastError
  for (let attempt = 0; attempt < 80; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/list`)
      if (response.ok) {
        const targets = await response.json()
        const target = targets.find((candidate) => candidate.type === 'page' && candidate.webSocketDebuggerUrl)
        if (target) return target
      }
    } catch (error) {
      lastError = error
    }
    await delay(100)
  }
  throw new Error(`Browser CDP target did not become ready: ${lastError?.message ?? 'unknown error'}`)
}

async function connectCdp(webSocketDebuggerUrl) {
  const socket = new WebSocket(webSocketDebuggerUrl)
  await new Promise((resolvePromise, reject) => {
    socket.addEventListener('open', resolvePromise, { once: true })
    socket.addEventListener('error', () => reject(new Error('CDP websocket failed to open')), { once: true })
  })

  let nextId = 1
  const pending = new Map()
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (!message.id) return
    const waiter = pending.get(message.id)
    if (!waiter) return
    pending.delete(message.id)
    if (message.error) waiter.reject(new Error(message.error.message))
    else waiter.resolve(message.result)
  })

  return {
    send(method, params = {}) {
      const id = nextId
      nextId += 1
      return new Promise((resolvePromise, reject) => {
        pending.set(id, { resolve: resolvePromise, reject })
        socket.send(JSON.stringify({ id, method, params }))
      })
    },
    close() {
      socket.close()
    }
  }
}

async function evaluate(expression) {
  const result = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text)
  }
  return result.result.value
}

async function click(selector) {
  const clicked = await evaluate(`(() => { const element = document.querySelector(${JSON.stringify(selector)}); if (!element) return false; element.click(); return true })()`)
  assert.equal(clicked, true, `Expected clickable element ${selector}`)
}

async function dispatchKey(key) {
  const dispatched = await evaluate(`(() => {
    const button = document.querySelector('.test-select > button')
    if (!button) return false
    button.focus()
    button.dispatchEvent(new KeyboardEvent('keydown', { key: ${JSON.stringify(key)}, bubbles: true, cancelable: true }))
    return true
  })()`)
  assert.equal(dispatched, true, `Expected key target for ${key}`)
}

async function isExpanded() {
  return evaluate(`document.querySelector('.test-select > button')?.getAttribute('aria-expanded') === 'true'`)
}

async function activeValue() {
  return evaluate(`document.querySelector('.test-select [role="option"][data-active="true"]')?.dataset.value ?? null`)
}

async function waitFor(predicate, label, { attempts = 80, snapshot } = {}) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    if (await predicate()) return
    await delay(50)
  }
  const state = snapshot ? `; state=${JSON.stringify(await snapshot())}` : ''
  throw new Error(`Timed out waiting for ${label}${state}`)
}

function delay(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds))
}

function isWithinProject(path) {
  return isWithinDirectory(projectRoot, path)
}

function isWithinDirectory(directory, path) {
  const pathFromDirectory = relative(resolve(directory), resolve(path))
  return pathFromDirectory !== '' && !pathFromDirectory.startsWith('..') && !isAbsolute(pathFromDirectory)
}

async function stopBrowser(child) {
  if (!child || child.exitCode !== null) return
  child.kill()
  await Promise.race([
    new Promise((resolvePromise) => child.once('exit', resolvePromise)),
    delay(3000)
  ])
}

function removeTemporaryDirectory(parent, target) {
  if (!existsSync(target)) return
  assert(isWithinDirectory(parent, target), `Refusing to remove unexpected path ${target}`)
  rmSync(target, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
}
