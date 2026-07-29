import assert from 'node:assert/strict'
import { EventEmitter, once } from 'node:events'
import { readFileSync } from 'node:fs'
import { request as httpRequest } from 'node:http'
import net from 'node:net'
import { dirname, isAbsolute, resolve } from 'node:path'
import { PassThrough } from 'node:stream'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import {
  COMPOSE_FILE,
  COMPOSE_PROJECT,
  COMPOSE_SERVICES,
  HEALTH_URL,
  MAX_REQUEST_BODY_BYTES,
  SUPERVISOR_HOST,
  SUPERVISOR_PORT,
  SUPERVISOR_TOKEN_ENV,
  createSupervisorRelayLifecycle,
  createValidationSupervisor,
} from './validation-supervisor.mjs'

const TOKEN = 'task6-supervisor-test-token-0123456789abcdef'

function completedChild({ stdout = '', stderr = '', exitCode = 0, signal = null } = {}) {
  const child = new EventEmitter()
  child.stdout = new PassThrough()
  child.stderr = new PassThrough()
  child.signals = []
  child.kill = (requestedSignal) => {
    child.signals.push(requestedSignal)
    return true
  }
  queueMicrotask(() => {
    child.stdout.end(stdout)
    child.stderr.end(stderr)
    child.emit('close', exitCode, signal)
  })
  return child
}

function deferredChild({ closeOnKill = false } = {}) {
  const child = new EventEmitter()
  child.stdin = new PassThrough()
  child.stdout = new PassThrough()
  child.stderr = new PassThrough()
  child.signals = []
  child.closed = false
  child.kill = (signal) => {
    child.signals.push(signal)
    if (closeOnKill && !child.closed) queueMicrotask(() => child.close(null, signal))
    return true
  }
  child.close = (exitCode = 0, signal = null) => {
    if (child.closed) return
    child.closed = true
    child.stdout.end()
    child.stderr.end()
    child.emit('close', exitCode, signal)
  }
  return child
}

function fixedRelayLifecycle(events = []) {
  let running = false
  return {
    async start() {
      events.push('relay:start')
      running = true
    },
    async stop() {
      events.push('relay:stop')
      running = false
    },
    isRunning() {
      return running
    },
  }
}

function healthRequestStub(calls, {
  statusCode = 200,
  body = '{"status":"UP"}',
} = {}) {
  return (url, options, callback) => {
    calls.push({ url, options })
    const outgoing = new EventEmitter()
    outgoing.setTimeout = (timeoutMs, onTimeout) => {
      outgoing.timeoutMs = timeoutMs
      outgoing.onTimeout = onTimeout
      return outgoing
    }
    outgoing.destroy = (error) => {
      queueMicrotask(() => outgoing.emit('error', error))
      return outgoing
    }
    outgoing.end = () => queueMicrotask(() => {
      const incoming = new PassThrough()
      incoming.statusCode = statusCode
      incoming.headers = { 'content-type': 'application/json' }
      callback(incoming)
      incoming.end(body)
    })
    return outgoing
  }
}

async function startSupervisor(overrides = {}) {
  const supervisor = createValidationSupervisor({
    token: TOKEN,
    listenPort: 0,
    spawnImpl: () => completedChild(),
    healthRequestImpl: healthRequestStub([]),
    relayLifecycle: fixedRelayLifecycle(),
    ...overrides,
  })
  await supervisor.start()
  return supervisor
}

async function send(supervisor, {
  method = 'POST',
  path = '/validation-supervisor',
  token = TOKEN,
  authorization,
  contentType = 'application/json',
  body = { action: 'status' },
} = {}) {
  const payload = typeof body === 'string' ? body : JSON.stringify(body)
  const address = supervisor.address()
  const headers = {
    ...(contentType === null ? {} : { 'Content-Type': contentType }),
    ...(token === null && authorization === undefined
      ? {}
      : { Authorization: authorization ?? `Bearer ${token}` }),
    'Content-Length': Buffer.byteLength(payload),
  }
  return new Promise((resolveResponse, rejectResponse) => {
    const outgoing = httpRequest({
      hostname: '127.0.0.1',
      port: address.port,
      method,
      path,
      headers,
      agent: false,
    }, (incoming) => {
      const chunks = []
      incoming.on('data', (chunk) => chunks.push(chunk))
      incoming.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8')
        resolveResponse({
          status: incoming.statusCode,
          text,
          json: text ? JSON.parse(text) : null,
        })
      })
    })
    outgoing.on('error', rejectResponse)
    outgoing.end(payload)
  })
}

async function waitFor(predicate, timeoutMs = 1000) {
  const deadline = Date.now() + timeoutMs
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error('Timed out waiting for Supervisor state')
    await new Promise((resolveWait) => setTimeout(resolveWait, 10))
  }
}

async function within(promise, timeoutMs = 500) {
  let timer
  try {
    return await Promise.race([
      promise,
      new Promise((resolveTimeout, rejectTimeout) => {
        timer = setTimeout(
          () => rejectTimeout(new Error('Timed out waiting for bounded Supervisor result')),
          timeoutMs,
        )
      }),
    ])
  } finally {
    clearTimeout(timer)
  }
}

test('freezes loopback identity, Compose authority, services, and health target', () => {
  const expectedComposeFile = resolve(
    dirname(fileURLToPath(import.meta.url)),
    '..',
    'infra',
    'docker-compose.validation.yml',
  )
  assert.equal(SUPERVISOR_HOST, '127.0.0.1')
  assert.equal(SUPERVISOR_PORT, 18088)
  assert.equal(SUPERVISOR_TOKEN_ENV, 'SUPERVISOR_INTERNAL_TOKEN')
  assert.equal(COMPOSE_PROJECT, 'fx-trading-validation')
  assert.deepEqual(COMPOSE_SERVICES, [
    'validation-backend',
    'validation-postgres',
    'validation-redis',
  ])
  assert.equal(COMPOSE_FILE, expectedComposeFile)
  assert.equal(isAbsolute(COMPOSE_FILE), true)
  assert.equal(HEALTH_URL, 'http://127.0.0.1:18087/actuator/health')
})

test('publishes only the fixed Supervisor package aliases', () => {
  const packageJson = JSON.parse(readFileSync(resolve(dirname(COMPOSE_FILE), '..', 'package.json')))
  assert.equal(packageJson.scripts['validation:supervisor'], 'node scripts/validation-supervisor.mjs')
  assert.equal(
    packageJson.scripts['validation:supervisor:test'],
    'node --test scripts/validation-supervisor.test.mjs',
  )
})

test('binds the real HTTP server only to loopback by default', async (t) => {
  const supervisor = await startSupervisor()
  t.after(() => supervisor.close())
  assert.equal(supervisor.address().address, '127.0.0.1')
})

test('rejects missing and malformed bearer credentials before body parsing', async (t) => {
  const spawnCalls = []
  const healthCalls = []
  const supervisor = await startSupervisor({
    spawnImpl: (...args) => {
      spawnCalls.push(args)
      return completedChild()
    },
    healthRequestImpl: healthRequestStub(healthCalls),
  })
  t.after(() => supervisor.close())

  for (const credential of [
    { token: null },
    { authorization: TOKEN },
    { authorization: `Basic ${TOKEN}` },
    { authorization: 'Bearer' },
    { authorization: `Bearer ${TOKEN.slice(0, -1)}x` },
  ]) {
    const response = await send(supervisor, { ...credential, body: '{not-json' })
    assert.equal(response.status, 401)
    assert.equal(response.json.error.code, 'UNAUTHORIZED')
    assert.equal(response.text.includes(TOKEN), false)
  }
  assert.equal(spawnCalls.length, 0)
  assert.equal(healthCalls.length, 0)
})

test('accepts only the exact route, POST, and JSON content type', async (t) => {
  const calls = []
  const supervisor = await startSupervisor({
    spawnImpl: (...args) => {
      calls.push(args)
      return completedChild()
    },
  })
  t.after(() => supervisor.close())

  assert.equal((await send(supervisor, { method: 'GET' })).status, 405)
  assert.equal((await send(supervisor, { path: '/validation-supervisor?x=1' })).status, 404)
  assert.equal((await send(supervisor, { path: '/other' })).status, 404)
  assert.equal((await send(supervisor, { contentType: null })).status, 415)
  assert.equal((await send(supervisor, { contentType: 'text/plain' })).status, 415)
  assert.equal((await send(supervisor, {
    contentType: 'application/json; charset=utf-8',
  })).status, 200)
  assert.equal(calls.length, 1)
})

test('rejects malformed, oversized, extra-field, and metacharacter bodies', async (t) => {
  const spawnCalls = []
  const healthCalls = []
  const supervisor = await startSupervisor({
    spawnImpl: (...args) => {
      spawnCalls.push(args)
      return completedChild()
    },
    healthRequestImpl: healthRequestStub(healthCalls),
  })
  t.after(() => supervisor.close())

  const invalidBodies = [
    '', '{not-json', 'null', '[]', '{}', '{"action":null}',
    '{"action":"Start"}', '{"action":" start"}', '{"action":"start "}',
    '{"action":"status; calc.exe"}', '{"action":"start && whoami"}',
    '{"action":"$(whoami)"}', '{"action":"`whoami`"}',
    '{"action":"restart\\r\\nstop"}', '{"action":"../docker-compose.yml"}',
    '{"action":"start --profile live"}',
    '{"action":"status","command":"calc.exe"}',
    '{"action":"status","path":"../docker-compose.yml"}',
    '{"action":"status","service":"validation-backend"}',
    '{"action":"status","args":["--profile","live"]}',
    '{"action":"status","url":"http://example.com"}',
    '{"action":"status","unknown":true}',
  ]
  for (const body of invalidBodies) {
    const response = await send(supervisor, { body })
    assert.equal(response.status, 400, body)
    if (body) assert.equal(response.text.includes(body), false)
  }
  const oversized = JSON.stringify({ action: 'x'.repeat(MAX_REQUEST_BODY_BYTES + 1) })
  assert.equal((await send(supervisor, { body: oversized })).status, 413)
  assert.equal(spawnCalls.length, 0)
  assert.equal(healthCalls.length, 0)
})

test('maps all Docker actions to exact shell-free argv', async (t) => {
  const calls = []
  const supervisor = await startSupervisor({
    spawnImpl: (executable, args, options) => {
      calls.push({ executable, args, options })
      return completedChild({ stdout: 'fixed-output' })
    },
  })
  t.after(() => supervisor.close())

  for (const action of ['status', 'start', 'stop', 'restart']) {
    const response = await send(supervisor, { body: { action } })
    assert.equal(response.status, 200)
    assert.equal(response.json.action, action)
  }

  const prefix = ['compose', '-p', COMPOSE_PROJECT, '-f', COMPOSE_FILE]
  assert.deepEqual(calls.map(({ executable, args }) => ({ executable, args })), [
    { executable: 'docker', args: [...prefix, 'ps', '--format', 'json'] },
    { executable: 'docker', args: [...prefix, 'up', '-d'] },
    { executable: 'docker', args: [...prefix, 'stop'] },
    { executable: 'docker', args: [...prefix, 'restart'] },
  ])
  for (const call of calls) {
    const { env, ...fixedOptions } = call.options
    assert.deepEqual(fixedOptions, {
      cwd: resolve(dirname(COMPOSE_FILE), '..'),
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    assert.equal(SUPERVISOR_TOKEN_ENV in env, false)
  }
})

test('owns relay lifecycle around mutations without changing Compose argv', async (t) => {
  const order = []
  const supervisor = await startSupervisor({
    spawnImpl: (_executable, args) => {
      order.push(`compose:${args.at(-2) === 'up' ? 'start' : args.at(-1)}`)
      return completedChild()
    },
    relayLifecycle: fixedRelayLifecycle(order),
  })
  t.after(() => supervisor.close())

  assert.equal((await send(supervisor, { body: { action: 'start' } })).status, 200)
  assert.equal((await send(supervisor, { body: { action: 'restart' } })).status, 200)
  assert.equal((await send(supervisor, { body: { action: 'stop' } })).status, 200)
  assert.deepEqual(order, [
    'compose:start', 'relay:start',
    'relay:stop', 'compose:restart', 'relay:start',
    'relay:stop', 'compose:stop',
  ])
})

test('refreshes the fixed relay target after every successful compose start', async () => {
  const backendIds = ['a'.repeat(64), 'b'.repeat(64)]
  const lookupIds = []
  const calls = []
  const supervisor = await startSupervisor({
    relayLifecycle: undefined,
    spawnImpl: (executable, args) => {
      calls.push({ executable, args })
      if (args[0] === 'compose') return completedChild()
      if (args[0] === 'container') {
        const backendId = backendIds.shift()
        lookupIds.push(backendId)
        return completedChild({ stdout: `${backendId}\n` })
      }
      throw new Error('Unexpected fixed Supervisor command')
    },
  })

  try {
    assert.equal((await send(supervisor, { body: { action: 'start' } })).status, 200)
    assert.equal((await send(supervisor, { body: { action: 'start' } })).status, 200)
    assert.deepEqual(lookupIds, ['a'.repeat(64), 'b'.repeat(64)])
    assert.equal(calls.filter(({ args }) => args[0] === 'compose').length, 2)
  } finally {
    await supervisor.close()
  }
})

test('coalesces concurrent relay starts queued behind an in-flight stop', async () => {
  const backendIds = ['d'.repeat(64), 'e'.repeat(64), 'f'.repeat(64)]
  const lookupIds = []
  let connectionChild
  const lifecycle = createSupervisorRelayLifecycle({
    childTerminateGraceMs: 20,
    childKillGraceMs: 20,
    spawnImpl: (_executable, args) => {
      if (args[0] === 'container') {
        const backendId = backendIds.shift()
        lookupIds.push(backendId)
        return completedChild({ stdout: `${backendId}\n` })
      }
      connectionChild = deferredChild()
      return connectionChild
    },
  })
  let socket
  let stopping
  let starting

  try {
    await lifecycle.start()
    socket = net.createConnection({ host: '127.0.0.1', port: 18087 })
    await once(socket, 'connect')
    await waitFor(() => Boolean(connectionChild))
    stopping = lifecycle.stop()
    await waitFor(() => connectionChild.signals.includes('SIGTERM'))
    starting = Promise.all([lifecycle.start(), lifecycle.start()])
    connectionChild.close(null, 'SIGTERM')
    await stopping
    await starting
    assert.deepEqual(lookupIds, ['d'.repeat(64), 'e'.repeat(64)])
    assert.equal(lifecycle.isRunning(), true)
  } finally {
    socket?.destroy()
    connectionChild?.close(null, 'SIGKILL')
    await Promise.allSettled([stopping, starting])
    await lifecycle.stop().catch(() => {})
  }
})

test('health uses one fixed local GET without Docker, auth, or redirects', async (t) => {
  const spawnCalls = []
  const healthCalls = []
  const supervisor = await startSupervisor({
    spawnImpl: (...args) => {
      spawnCalls.push(args)
      return completedChild()
    },
    healthRequestImpl: healthRequestStub(healthCalls),
  })
  t.after(() => supervisor.close())

  const response = await send(supervisor, { body: { action: 'health' } })
  assert.equal(response.status, 200)
  assert.equal(response.json.health.status, 'UP')
  assert.equal(spawnCalls.length, 0)
  assert.equal(healthCalls.length, 1)
  assert.equal(healthCalls[0].url, HEALTH_URL)
  assert.equal(healthCalls[0].options.method, 'GET')
  assert.equal('authorization' in healthCalls[0].options.headers, false)
  assert.equal('Authorization' in healthCalls[0].options.headers, false)
})

test('fails closed on redirect, unhealthy, invalid, and oversized health responses', async () => {
  const cases = [
    { statusCode: 302, body: '{"status":"UP"}', expectedStatus: 503, code: 'HEALTH_UNAVAILABLE' },
    { statusCode: 503, body: '{"status":"DOWN"}', expectedStatus: 503, code: 'HEALTH_UNAVAILABLE' },
    { statusCode: 200, body: 'not-json', expectedStatus: 502, code: 'HEALTH_INVALID_RESPONSE' },
    { statusCode: 200, body: '{"status":"UP","pad":"xxxxxxxx"}', maxHealthBodyBytes: 8,
      expectedStatus: 502, code: 'HEALTH_RESPONSE_LIMIT' },
  ]
  for (const entry of cases) {
    const calls = []
    const supervisor = await startSupervisor({
      healthRequestImpl: healthRequestStub(calls, entry),
      ...(entry.maxHealthBodyBytes ? { maxHealthBodyBytes: entry.maxHealthBodyBytes } : {}),
    })
    try {
      const response = await send(supervisor, { body: { action: 'health' } })
      assert.equal(response.status, entry.expectedStatus)
      assert.equal(response.json.error.code, entry.code)
      assert.equal(calls.length, 1)
    } finally {
      await supervisor.close()
    }
  }
})

test('uses an absolute health deadline even while the peer keeps sending bytes', async () => {
  let interval
  let delayedEnd
  const healthRequestImpl = (_url, _options, callback) => {
    const outgoing = new EventEmitter()
    const incoming = new PassThrough()
    incoming.statusCode = 200
    incoming.headers = { 'content-type': 'application/json' }
    outgoing.setTimeout = () => outgoing
    outgoing.destroy = (error) => {
      clearInterval(interval)
      clearTimeout(delayedEnd)
      queueMicrotask(() => outgoing.emit('error', error))
      return outgoing
    }
    outgoing.end = () => {
      callback(incoming)
      interval = setInterval(() => incoming.write(' '), 5)
      delayedEnd = setTimeout(() => {
        clearInterval(interval)
        incoming.end('{"status":"UP"}')
      }, 250)
    }
    return outgoing
  }
  const supervisor = await startSupervisor({
    healthRequestImpl,
    healthTimeoutMs: 30,
  })
  try {
    const response = await within(
      send(supervisor, { body: { action: 'health' } }),
      150,
    )
    assert.equal(response.status, 504)
    assert.equal(response.json.error.code, 'HEALTH_TIMEOUT')
  } finally {
    await within(supervisor.close(), 500)
    clearInterval(interval)
    clearTimeout(delayedEnd)
  }
})

test('sanitizes spawn throws, nonzero exits, and signals', async (t) => {
  const children = [
    () => { throw new Error(`spawn failed ${TOKEN}`) },
    () => completedChild({ stderr: `failure ${TOKEN}`, exitCode: 17 }),
    () => completedChild({ signal: 'SIGTERM', exitCode: null }),
  ]
  const supervisor = await startSupervisor({ spawnImpl: () => children.shift()() })
  t.after(() => supervisor.close())

  for (let index = 0; index < 3; index += 1) {
    const response = await send(supervisor)
    assert.equal(response.status, 502)
    assert.equal(response.json.error.code, 'COMMAND_FAILED')
    assert.equal(response.text.includes(TOKEN), false)
    assert.equal(response.text.includes('failure'), false)
  }
})

test('times out, terminates, and reaps before returning 504', async (t) => {
  const child = deferredChild({ closeOnKill: true })
  const supervisor = await startSupervisor({
    spawnImpl: () => child,
    commandTimeoutMs: 20,
    childTerminateGraceMs: 20,
    childKillGraceMs: 20,
  })
  t.after(() => supervisor.close())

  const response = await send(supervisor)
  assert.equal(response.status, 504)
  assert.equal(response.json.error.code, 'COMMAND_TIMEOUT')
  assert.equal(child.closed, true)
  assert.deepEqual(child.signals, ['SIGTERM'])
})

test('bounds combined child output and terminates the producer', async (t) => {
  let child
  const supervisor = await startSupervisor({
    spawnImpl: () => {
      child = completedChild({ stdout: '123456789' })
      return child
    },
    maxChildOutputBytes: 8,
  })
  t.after(() => supervisor.close())

  const response = await send(supervisor)
  assert.equal(response.status, 502)
  assert.equal(response.json.error.code, 'COMMAND_OUTPUT_LIMIT')
  assert.deepEqual(child.signals, ['SIGTERM'])
  assert.equal(response.text.includes('123456789'), false)
})

test('retains an unreaped child and refuses a falsely clean shutdown', async () => {
  const child = deferredChild()
  const supervisor = await startSupervisor({
    spawnImpl: () => child,
    commandTimeoutMs: 20,
    childTerminateGraceMs: 20,
    childKillGraceMs: 20,
  })
  const response = await send(supervisor)
  assert.equal(response.status, 503)
  assert.equal(response.json.error.code, 'COMMAND_REAP_FAILED')
  await assert.rejects(
    within(supervisor.close(), 500),
    /unreaped child/u,
  )
  child.close(null, 'SIGKILL')
})

test('bounds shutdown of an in-flight unreaped command before its command timeout', async () => {
  const child = deferredChild()
  let spawnCount = 0
  const supervisor = await startSupervisor({
    spawnImpl: () => {
      spawnCount += 1
      return child
    },
    commandTimeoutMs: 10_000,
    childTerminateGraceMs: 20,
    childKillGraceMs: 20,
  })
  const request = send(supervisor).then(
    (response) => ({ response }),
    (error) => ({ error }),
  )
  await waitFor(() => spawnCount === 1)
  const closing = supervisor.close()

  try {
    await assert.rejects(
      within(closing, 300),
      /unreaped child/u,
    )
  } finally {
    child.close(null, 'SIGKILL')
    await Promise.allSettled([closing, request])
  }
})

test('tracks a failed relay lookup child and refuses a falsely clean shutdown', async () => {
  let lookupChild
  const supervisor = await startSupervisor({
    relayLifecycle: undefined,
    spawnImpl: (_executable, args) => {
      if (args[0] === 'compose') return completedChild()
      lookupChild = deferredChild()
      queueMicrotask(() => lookupChild.emit('error', new Error('lookup failed')))
      return lookupChild
    },
    childTerminateGraceMs: 20,
    childKillGraceMs: 20,
  })
  const response = await send(supervisor, { body: { action: 'start' } })
  assert.equal(response.status, 502)
  assert.equal(response.json.error.code, 'RELAY_FAILED')
  const closing = supervisor.close()

  try {
    await assert.rejects(
      within(closing, 300),
      /unreaped child/u,
    )
  } finally {
    lookupChild.close(null, 'SIGKILL')
    await Promise.allSettled([closing])
  }
})

test('does not poison command authority for an unrelated concurrent status child', async () => {
  let lookupChild
  let statusChild
  let composeStarts = 0
  const supervisor = await startSupervisor({
    relayLifecycle: undefined,
    spawnImpl: (_executable, args) => {
      if (args[0] === 'container') {
        lookupChild = deferredChild()
        return lookupChild
      }
      if (args.at(-2) === 'up') {
        composeStarts += 1
        return completedChild()
      }
      if (!statusChild) {
        statusChild = deferredChild()
        return statusChild
      }
      return completedChild()
    },
  })

  try {
    const starting = send(supervisor, { body: { action: 'start' } })
    await waitFor(() => Boolean(lookupChild))
    const status = send(supervisor, { body: { action: 'status' } })
    await waitFor(() => Boolean(statusChild))
    lookupChild.close(0)
    const startResponse = await starting
    assert.equal(startResponse.status, 502)
    assert.equal(startResponse.json.error.code, 'RELAY_FAILED')

    statusChild.close(0)
    assert.equal((await status).status, 200)
    assert.equal((await send(supervisor, { body: { action: 'status' } })).status, 200)
    assert.equal(composeStarts, 1)
  } finally {
    await supervisor.close()
  }
})

test('bounds shutdown while the fixed relay backend lookup is still in flight', async () => {
  let lookupChild
  const supervisor = await startSupervisor({
    relayLifecycle: undefined,
    spawnImpl: (_executable, args) => {
      if (args[0] === 'compose') return completedChild()
      lookupChild = deferredChild()
      return lookupChild
    },
    childTerminateGraceMs: 20,
    childKillGraceMs: 20,
  })
  const request = send(supervisor, { body: { action: 'start' } }).then(
    (response) => ({ response }),
    (error) => ({ error }),
  )
  await waitFor(() => Boolean(lookupChild))
  const closing = supervisor.close()

  try {
    await assert.rejects(
      within(closing, 300),
      /unreaped child/u,
    )
  } finally {
    lookupChild.close(null, 'SIGKILL')
    await Promise.allSettled([closing, request])
  }
})

test('uses Supervisor reap bounds for fixed relay connection children', async () => {
  const backendId = 'c'.repeat(64)
  let relayChild
  const supervisor = await startSupervisor({
    relayLifecycle: undefined,
    spawnImpl: (_executable, args) => {
      if (args[0] === 'compose') return completedChild()
      if (args[0] === 'container') return completedChild({ stdout: `${backendId}\n` })
      relayChild = deferredChild()
      return relayChild
    },
    childTerminateGraceMs: 20,
    childKillGraceMs: 20,
  })
  let socket
  let closing

  try {
    assert.equal((await send(supervisor, { body: { action: 'start' } })).status, 200)
    socket = net.createConnection({ host: '127.0.0.1', port: 18087 })
    await once(socket, 'connect')
    await waitFor(() => Boolean(relayChild))
    closing = supervisor.close()
    await assert.rejects(
      within(closing, 300),
      /unreaped child/u,
    )
  } finally {
    socket?.destroy()
    relayChild?.close(null, 'SIGKILL')
    await Promise.allSettled([closing])
  }
})

test('serializes mutating actions until child close', async (t) => {
  const firstChild = deferredChild()
  const calls = []
  const supervisor = await startSupervisor({
    spawnImpl: (...args) => {
      calls.push(args)
      return calls.length === 1 ? firstChild : completedChild()
    },
  })
  t.after(() => supervisor.close())

  const first = send(supervisor, { body: { action: 'start' } })
  await waitFor(() => calls.length === 1)
  const conflict = await send(supervisor, { body: { action: 'restart' } })
  assert.equal(conflict.status, 409)
  assert.equal(conflict.json.error.code, 'MUTATION_BUSY')
  assert.equal(calls.length, 1)

  firstChild.close()
  assert.equal((await first).status, 200)
  assert.equal((await send(supervisor, { body: { action: 'stop' } })).status, 200)
  assert.equal(calls.length, 2)
})

test('fails closed when the configured token is missing or invalid', () => {
  for (const token of [undefined, null, '', '   ', 'short', ` ${TOKEN}`, `${TOKEN} `]) {
    assert.throws(() => createValidationSupervisor({ token }), /Supervisor internal token/u)
  }
})

test('never inherits the Supervisor bearer token into Docker children', async (t) => {
  const previous = process.env[SUPERVISOR_TOKEN_ENV]
  process.env[SUPERVISOR_TOKEN_ENV] = 'must-not-reach-child-0123456789abcdef'
  t.after(() => {
    if (previous === undefined) delete process.env[SUPERVISOR_TOKEN_ENV]
    else process.env[SUPERVISOR_TOKEN_ENV] = previous
  })

  const calls = []
  const supervisor = await startSupervisor({
    spawnImpl: (_executable, _args, options) => {
      calls.push(options)
      return completedChild()
    },
  })
  t.after(() => supervisor.close())
  assert.equal((await send(supervisor)).status, 200)
  assert.equal(calls.length, 1)
  assert.equal(SUPERVISOR_TOKEN_ENV in calls[0].env, false)
})

test('close is idempotent and permanently closes the listener', async () => {
  const supervisor = await startSupervisor()
  const first = supervisor.close()
  const second = supervisor.close()
  assert.strictEqual(second, first)
  await first
  await assert.rejects(supervisor.start(), /closed/u)
})

test('bounds shutdown with an unauthenticated partial-header socket', async () => {
  const relayEvents = []
  const supervisor = await startSupervisor({
    relayLifecycle: fixedRelayLifecycle(relayEvents),
  })
  const address = supervisor.address()
  const socket = net.createConnection({ host: '127.0.0.1', port: address.port })
  await once(socket, 'connect')
  socket.write('P')
  const closing = supervisor.close()

  try {
    await within(closing, 200)
    assert.deepEqual(relayEvents, ['relay:stop'])
  } finally {
    socket.destroy()
    await Promise.allSettled([closing])
  }
})
