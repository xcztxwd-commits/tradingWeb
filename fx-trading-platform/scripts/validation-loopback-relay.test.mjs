import assert from 'node:assert/strict'
import { EventEmitter, once } from 'node:events'
import net from 'node:net'
import { PassThrough } from 'node:stream'
import test from 'node:test'

import {
  BACKEND_LOOKUP_SPEC,
  RELAY_HOST,
  RELAY_PORT,
  createValidationLoopbackRelay,
  resolveValidationBackendContainerId,
  validationBackendExecSpec,
} from './validation-loopback-relay.mjs'

const CONTAINER_ID = 'a'.repeat(64)

function completedChild({ stdout = '', stderr = '', exitCode = 0 } = {}) {
  const child = new EventEmitter()
  child.stdin = new PassThrough()
  child.stdout = new PassThrough()
  child.stderr = new PassThrough()
  child.killed = false
  child.kill = () => {
    child.killed = true
    return true
  }
  queueMicrotask(() => {
    child.stdout.end(stdout)
    child.stderr.end(stderr)
    child.emit('close', exitCode, null)
  })
  return child
}

function relayChild(response) {
  const child = new EventEmitter()
  child.stdin = new PassThrough()
  child.stdout = new PassThrough()
  child.stderr = new PassThrough()
  child.killed = false
  child.closed = false
  child.kill = () => {
    if (child.killed) return false
    child.killed = true
    child.stdout.end()
    child.stderr.end()
    queueMicrotask(() => {
      child.closed = true
      child.emit('close', null, 'SIGTERM')
    })
    return true
  }

  let request = ''
  child.stdin.on('data', (chunk) => {
    request += chunk.toString('utf8')
    if (!request.includes('\r\n\r\n')) return
    child.stdout.end(response)
    queueMicrotask(() => {
      child.closed = true
      child.emit('close', 0, null)
    })
  })
  return child
}

function deferredChild() {
  const child = new EventEmitter()
  child.stdin = new PassThrough()
  child.stdout = new PassThrough()
  child.stderr = new PassThrough()
  child.killed = false
  child.signals = []
  child.kill = (signal) => {
    child.killed = true
    child.signals.push(signal)
    return true
  }
  child.closeNow = (exitCode = null, signal = 'SIGTERM') => {
    child.stdin.end()
    child.stdout.end()
    child.stderr.end()
    child.emit('close', exitCode, signal)
  }
  return child
}

async function waitFor(predicate, timeoutMs = 1_000) {
  const deadline = Date.now() + timeoutMs
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error('Timed out waiting for relay state')
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
}

async function within(promise, timeoutMs) {
  let timeout
  try {
    return await Promise.race([
      promise,
      new Promise((resolve, reject) => {
        timeout = setTimeout(() => reject(new Error('Timed out waiting for relay event')), timeoutMs)
      }),
    ])
  } finally {
    clearTimeout(timeout)
  }
}

test('uses fixed loopback and fixed project/service label lookup', async () => {
  assert.equal(RELAY_HOST, '127.0.0.1')
  assert.equal(RELAY_PORT, 18087)
  assert.deepEqual(BACKEND_LOOKUP_SPEC, {
    executable: 'docker',
    args: [
      'container',
      'ls',
      '--no-trunc',
      '--filter',
      'label=com.docker.compose.project=fx-trading-validation',
      '--filter',
      'label=com.docker.compose.service=validation-backend',
      '--filter',
      'status=running',
      '--format',
      '{{.ID}}',
    ],
  })

  const calls = []
  const spawnImpl = (executable, args, options) => {
    calls.push({ executable, args, options })
    return completedChild({ stdout: `${CONTAINER_ID}\n` })
  }

  assert.equal(await resolveValidationBackendContainerId({ spawnImpl }), CONTAINER_ID)
  assert.deepEqual(calls, [{
    ...BACKEND_LOOKUP_SPEC,
    options: { shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] },
  }])
})

test('fails closed unless lookup returns exactly one running backend container', async () => {
  const invalidOutputs = [
    '',
    'not-a-container-id\n',
    `${CONTAINER_ID}\n${'b'.repeat(64)}\n`,
  ]

  for (const stdout of invalidOutputs) {
    await assert.rejects(
      resolveValidationBackendContainerId({
        spawnImpl: () => completedChild({ stdout }),
      }),
      /exactly one running validation backend container/,
    )
  }
})

test('builds only the fixed shell-free docker exec transport', () => {
  assert.deepEqual(validationBackendExecSpec(CONTAINER_ID), {
    executable: 'docker',
    args: [
      'exec',
      '-i',
      CONTAINER_ID,
      '/bin/busybox',
      'nc',
      '127.0.0.1',
      '8080',
    ],
    options: { shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] },
  })
  assert.throws(
    () => validationBackendExecSpec(`${CONTAINER_ID};calc.exe`),
    /validated container id/,
  )
})

test('relays an HTTP connection over docker exec and reaps the child', async (t) => {
  const response = 'HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\n{"status":"UP"}'
  const calls = []
  let child
  const relay = createValidationLoopbackRelay({
    backendContainerId: CONTAINER_ID,
    listenPort: 0,
    idleTimeoutMs: 1_000,
    spawnImpl: (executable, args, options) => {
      calls.push({ executable, args, options })
      child = relayChild(response)
      return child
    },
  })
  t.after(() => relay.close())

  await relay.start()
  assert.equal(relay.host, RELAY_HOST)
  const address = relay.address()
  assert.equal(address.address, RELAY_HOST)

  const socket = net.createConnection({ host: RELAY_HOST, port: address.port })
  await once(socket, 'connect')
  socket.end('GET /actuator/health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n')
  let received = ''
  socket.setEncoding('utf8')
  socket.on('data', (chunk) => { received += chunk })
  await once(socket, 'end')

  assert.equal(received, response)
  assert.deepEqual(calls, [validationBackendExecSpec(CONTAINER_ID)])
  await relay.close()
  assert.equal(relay.activeConnectionCount, 0)
  assert.equal(child.killed || child.closed, true)
  await assert.rejects(relay.start(), /relay is closed/)
})

test('kills the docker exec child when the client disconnects', async (t) => {
  let child
  const relay = createValidationLoopbackRelay({
    backendContainerId: CONTAINER_ID,
    listenPort: 0,
    idleTimeoutMs: 50,
    spawnImpl: () => {
      child = relayChild('')
      return child
    },
  })
  t.after(() => relay.close())

  await relay.start()
  const socket = net.createConnection({ host: RELAY_HOST, port: relay.address().port })
  await once(socket, 'connect')
  socket.destroy()

  await waitFor(() => child.killed && relay.activeConnectionCount === 0)
  assert.equal(child.killed, true)
  assert.equal(relay.activeConnectionCount, 0)
})

test('contains docker exec stdin EPIPE instead of crashing the relay', async (t) => {
  let child
  const relay = createValidationLoopbackRelay({
    backendContainerId: CONTAINER_ID,
    listenPort: 0,
    idleTimeoutMs: 1_000,
    spawnImpl: () => {
      child = relayChild('')
      return child
    },
  })
  t.after(() => relay.close())

  await relay.start()
  const socket = net.createConnection({ host: RELAY_HOST, port: relay.address().port })
  await once(socket, 'connect')
  await waitFor(() => relay.activeConnectionCount === 1)

  const closed = once(socket, 'close')
  const epPipe = Object.assign(new Error('docker exec stdin closed'), { code: 'EPIPE' })
  child.stdin.destroy(epPipe)

  await within(closed, 300)
  await waitFor(() => child.killed && relay.activeConnectionCount === 0)
  assert.equal(child.killed, true)
  assert.equal(relay.activeConnectionCount, 0)
})

test('releases a slot when docker exec closes before a half-open client', async (t) => {
  const response = 'HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n'
  const children = []
  const relay = createValidationLoopbackRelay({
    backendContainerId: CONTAINER_ID,
    listenPort: 0,
    maxConnections: 1,
    idleTimeoutMs: 10_000,
    spawnImpl: () => {
      const child = relayChild(response)
      children.push(child)
      return child
    },
  })
  t.after(() => relay.close())

  await relay.start()
  const first = net.createConnection({
    host: RELAY_HOST,
    port: relay.address().port,
    allowHalfOpen: true,
  })
  await once(first, 'connect')
  let received = ''
  first.setEncoding('utf8')
  first.on('data', (chunk) => { received += chunk })
  const firstEnded = once(first, 'end')
  const firstClosed = once(first, 'close')
  first.write('GET /actuator/health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n')
  await waitFor(() => children[0]?.closed === true, 300)
  await within(firstEnded, 1_500)
  await waitFor(() => relay.activeConnectionCount === 0, 1_500)
  assert.equal(received, response)
  first.destroy()
  await firstClosed

  const second = net.createConnection({ host: RELAY_HOST, port: relay.address().port })
  await once(second, 'connect')
  await waitFor(() => children.length === 2)
  second.destroy()
})

test('close waits until every docker exec child is reaped', async () => {
  let child
  const relay = createValidationLoopbackRelay({
    backendContainerId: CONTAINER_ID,
    listenPort: 0,
    idleTimeoutMs: 1_000,
    spawnImpl: () => {
      child = deferredChild()
      return child
    },
  })

  await relay.start()
  const socket = net.createConnection({ host: RELAY_HOST, port: relay.address().port })
  await once(socket, 'connect')
  await waitFor(() => relay.activeConnectionCount === 1)

  let settled = false
  const closing = relay.close().then(() => { settled = true })
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(child.killed, true)
  assert.equal(settled, false)

  child.closeNow()
  await closing
  assert.equal(settled, true)
  assert.equal(relay.activeConnectionCount, 0)
})

test('lookup timeout waits for the docker child close before rejecting', async () => {
  const child = deferredChild()
  let settled = false
  const lookup = resolveValidationBackendContainerId({
    lookupTimeoutMs: 10,
    spawnImpl: () => child,
  }).then(
    (value) => ({ value }),
    (error) => ({ error }),
  ).finally(() => { settled = true })

  try {
    await waitFor(() => child.killed, 300)
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(settled, false)

    child.closeNow()
    const result = await lookup
    assert.match(result.error?.message ?? '', /timed out/)
  } finally {
    if (!settled) child.closeNow()
    await lookup
  }
})

test('contains lookup stream errors and reaps the docker child before rejecting', async () => {
  const child = deferredChild()
  let settled = false
  const lookup = resolveValidationBackendContainerId({
    lookupTimeoutMs: 1_000,
    childTerminateGraceMs: 20,
    childKillGraceMs: 20,
    spawnImpl: () => child,
  }).then(
    (value) => ({ value }),
    (error) => ({ error }),
  ).finally(() => { settled = true })

  try {
    child.stdout.destroy(new Error('lookup stdout failed'))
    await waitFor(() => child.signals.includes('SIGTERM'), 300)
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(settled, false)

    child.closeNow()
    const result = await lookup
    assert.match(result.error?.message ?? '', /lookup stdout failed/)
  } finally {
    if (!settled) child.closeNow()
    await lookup
  }
})

test('rejects connections above the fixed concurrency bound without spawning', async (t) => {
  const children = []
  const relay = createValidationLoopbackRelay({
    backendContainerId: CONTAINER_ID,
    listenPort: 0,
    maxConnections: 1,
    idleTimeoutMs: 1_000,
    spawnImpl: () => {
      const child = relayChild('')
      children.push(child)
      return child
    },
  })
  t.after(() => relay.close())

  await relay.start()
  const first = net.createConnection({ host: RELAY_HOST, port: relay.address().port })
  await once(first, 'connect')
  await waitFor(() => relay.activeConnectionCount === 1)

  const second = net.createConnection({ host: RELAY_HOST, port: relay.address().port })
  const secondClosed = once(second, 'close')
  await once(second, 'connect')
  await secondClosed

  assert.equal(children.length, 1)
  assert.equal(relay.activeConnectionCount, 1)
  first.destroy()
  await relay.close()
  assert.equal(relay.activeConnectionCount, 0)
  assert.equal(children[0].killed, true)
})

test('holds a connection slot until its disconnected docker child is reaped', async () => {
  const children = []
  const relay = createValidationLoopbackRelay({
    backendContainerId: CONTAINER_ID,
    listenPort: 0,
    maxConnections: 1,
    idleTimeoutMs: 1_000,
    spawnImpl: () => {
      const child = deferredChild()
      children.push(child)
      return child
    },
  })
  let first
  let second

  try {
    await relay.start()
    first = net.createConnection({ host: RELAY_HOST, port: relay.address().port })
    await once(first, 'connect')
    await waitFor(() => relay.activeConnectionCount === 1)
    first.resetAndDestroy()
    await waitFor(
      () => relay.activeConnectionCount === 0 && children[0]?.signals.includes('SIGTERM'),
    )

    second = net.createConnection({ host: RELAY_HOST, port: relay.address().port })
    const secondConnected = once(second, 'connect')
    const secondClosed = once(second, 'close')
    await secondConnected
    await within(secondClosed, 300)
    assert.equal(children.length, 1)
  } finally {
    first?.destroy()
    second?.destroy()
    for (const child of children) child.closeNow()
    await relay.close()
  }
})
