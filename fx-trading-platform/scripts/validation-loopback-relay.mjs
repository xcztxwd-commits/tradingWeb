import { spawn } from 'node:child_process'
import net from 'node:net'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

export const RELAY_HOST = '127.0.0.1'
export const RELAY_PORT = 18087
export const RELAY_MAX_CONNECTIONS = 32
export const RELAY_IDLE_TIMEOUT_MS = 10 * 60 * 1000

const CONTAINER_ID_PATTERN = /^[0-9a-f]{64}$/
const CHILD_OUTPUT_LIMIT = 4096
const LOOKUP_TIMEOUT_MS = 5000
const CHILD_TERMINATE_GRACE_MS = 1000
const CHILD_KILL_GRACE_MS = 1000
const SOCKET_CLOSE_GRACE_MS = 1000

export const BACKEND_LOOKUP_SPEC = Object.freeze({
  executable: 'docker',
  args: Object.freeze([
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
  ]),
})

const LOOKUP_SPAWN_OPTIONS = Object.freeze({
  shell: false,
  windowsHide: true,
  stdio: Object.freeze(['ignore', 'pipe', 'pipe']),
})

const RELAY_SPAWN_OPTIONS = Object.freeze({
  shell: false,
  windowsHide: true,
  stdio: Object.freeze(['pipe', 'pipe', 'pipe']),
})

export function validationBackendExecSpec(backendContainerId) {
  assertValidatedContainerId(backendContainerId)
  return {
    executable: 'docker',
    args: [
      'exec',
      '-i',
      backendContainerId,
      '/bin/busybox',
      'nc',
      '127.0.0.1',
      '8080',
    ],
    options: {
      shell: RELAY_SPAWN_OPTIONS.shell,
      windowsHide: RELAY_SPAWN_OPTIONS.windowsHide,
      stdio: [...RELAY_SPAWN_OPTIONS.stdio],
    },
  }
}

export async function resolveValidationBackendContainerId({
  spawnImpl = spawn,
  lookupTimeoutMs = LOOKUP_TIMEOUT_MS,
  childTerminateGraceMs = CHILD_TERMINATE_GRACE_MS,
  childKillGraceMs = CHILD_KILL_GRACE_MS,
  signal,
} = {}) {
  if (!Number.isInteger(lookupTimeoutMs) || lookupTimeoutMs < 1) {
    throw new Error('Invalid validation backend lookup timeout')
  }
  assertPositiveInteger(childTerminateGraceMs, 'Invalid child terminate grace')
  assertPositiveInteger(childKillGraceMs, 'Invalid child kill grace')
  const stdout = await collectFixedCommand(
    BACKEND_LOOKUP_SPEC,
    LOOKUP_SPAWN_OPTIONS,
    spawnImpl,
    {
      timeoutMs: lookupTimeoutMs,
      terminateGraceMs: childTerminateGraceMs,
      killGraceMs: childKillGraceMs,
      signal,
    },
  )
  const matches = stdout
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean)

  if (matches.length !== 1 || !CONTAINER_ID_PATTERN.test(matches[0])) {
    throw new Error('Expected exactly one running validation backend container')
  }
  return matches[0]
}

export function createValidationLoopbackRelay({
  backendContainerId,
  spawnImpl = spawn,
  listenPort = RELAY_PORT,
  maxConnections = RELAY_MAX_CONNECTIONS,
  idleTimeoutMs = RELAY_IDLE_TIMEOUT_MS,
  childTerminateGraceMs = CHILD_TERMINATE_GRACE_MS,
  childKillGraceMs = CHILD_KILL_GRACE_MS,
} = {}) {
  assertValidatedContainerId(backendContainerId)
  if (!Number.isInteger(listenPort) || listenPort < 0 || listenPort > 65535) {
    throw new Error('Invalid relay listen port')
  }
  if (!Number.isInteger(maxConnections) || maxConnections < 1) {
    throw new Error('Invalid relay connection limit')
  }
  if (!Number.isInteger(idleTimeoutMs) || idleTimeoutMs < 1) {
    throw new Error('Invalid relay idle timeout')
  }
  assertPositiveInteger(childTerminateGraceMs, 'Invalid child terminate grace')
  assertPositiveInteger(childKillGraceMs, 'Invalid child kill grace')

  const active = new Map()
  const pendingChildren = new Set()
  let startPromise
  let closePromise
  let closed = false

  const server = net.createServer({ allowHalfOpen: true }, (socket) => {
    if (closed || pendingChildren.size >= maxConnections) {
      socket.destroy()
      return
    }

    let child
    try {
      const spec = validationBackendExecSpec(backendContainerId)
      child = spawnImpl(spec.executable, spec.args, spec.options)
    } catch {
      socket.destroy()
      return
    }

    const connection = trackChild(child)
    connection.socket = socket
    connection.socketCloseTimer = undefined
    pendingChildren.add(connection)
    active.set(socket, connection)
    socket.setKeepAlive(true)
    socket.setTimeout(idleTimeoutMs)

    const failTransport = () => socket.destroy()
    child.stderr.on('data', () => {})
    child.stdin.on('error', failTransport)
    child.stdout.on('error', failTransport)
    child.stderr.on('error', failTransport)
    socket.pipe(child.stdin)
    child.stdout.pipe(socket)

    socket.on('timeout', () => socket.destroy())
    socket.on('error', () => {})
    socket.on('close', () => {
      active.delete(socket)
      clearTimeout(connection.socketCloseTimer)
      socket.unpipe(child.stdin)
      child.stdout.unpipe(socket)
      child.stdin.destroy()
      if (!connection.exited) {
        terminateTrackedChild(
          connection,
          'Failed to reap validation relay child',
          childTerminateGraceMs,
          childKillGraceMs,
        )
          .catch((error) => { connection.reapError = error })
      }
    })

    child.on('error', () => socket.destroy())
    child.on('close', () => {
      pendingChildren.delete(connection)
      if (!socket.destroyed) {
        if (socket.writableFinished) socket.destroy()
        else socket.end(() => socket.destroy())
        connection.socketCloseTimer = setTimeout(
          () => socket.destroy(),
          SOCKET_CLOSE_GRACE_MS,
        )
        connection.socketCloseTimer.unref?.()
      }
    })
  })

  server.on('error', () => {})

  return {
    host: RELAY_HOST,
    get activeConnectionCount() {
      return active.size
    },
    address() {
      return server.address()
    },
    start() {
      if (closed) return Promise.reject(new Error('Validation loopback relay is closed'))
      if (server.listening) return Promise.resolve()
      if (startPromise) return startPromise
      startPromise = new Promise((resolveStart, rejectStart) => {
        const onError = (error) => {
          server.off('listening', onListening)
          startPromise = undefined
          rejectStart(error)
        }
        const onListening = () => {
          server.off('error', onError)
          resolveStart()
        }
        server.once('error', onError)
        server.once('listening', onListening)
        server.listen(listenPort, RELAY_HOST)
      })
      return startPromise
    },
    close() {
      if (closePromise) return closePromise
      closed = true
      const childrenToReap = [...pendingChildren]
      for (const { socket } of [...active.values()]) {
        socket.destroy()
      }
      closePromise = Promise.all([
        closeServer(server, startPromise),
        ...childrenToReap.map((connection) =>
          terminateTrackedChild(
            connection,
            'Failed to reap validation relay child',
            childTerminateGraceMs,
            childKillGraceMs,
          )),
      ]).then(() => {
        active.clear()
      })
      return closePromise
    },
  }
}

async function collectFixedCommand(spec, options, spawnImpl, {
  timeoutMs,
  terminateGraceMs,
  killGraceMs,
  signal,
}) {
  return new Promise((resolveOutput, rejectOutput) => {
    let child
    try {
      child = spawnImpl(spec.executable, [...spec.args], {
        shell: options.shell,
        windowsHide: options.windowsHide,
        stdio: [...options.stdio],
      })
    } catch (error) {
      rejectOutput(error)
      return
    }

    const tracker = trackChild(child)
    let stdout = ''
    let settled = false
    let failure
    const abortLookup = () => {
      failAndTerminate(
        new Error('Validation backend lookup was interrupted'),
        'Validation backend lookup shutdown was not reaped',
      )
    }
    const timeout = setTimeout(() => {
      failAndTerminate(
        new Error('Validation backend lookup timed out'),
        'Validation backend lookup timed out and was not reaped',
      )
    }, timeoutMs)

    child.stdout.setEncoding('utf8')
    child.stdout.on('data', (chunk) => {
      if (stdout.length + chunk.length > CHILD_OUTPUT_LIMIT) {
        failAndTerminate(
          new Error('Validation backend lookup output exceeded limit'),
          'Validation backend lookup output-limit child was not reaped',
        )
        return
      }
      stdout += chunk
    })
    child.stdout.on('error', (error) => {
      failAndTerminate(error, 'Validation backend lookup stdout failure was not reaped')
    })
    child.stderr.on('data', () => {})
    child.stderr.on('error', (error) => {
      failAndTerminate(error, 'Validation backend lookup stderr failure was not reaped')
    })
    child.on('error', (error) => {
      failAndTerminate(error, 'Validation backend lookup process failure was not reaped')
    })
    child.on('close', (code) => {
      if (failure) {
        finish(failure)
      } else if (code !== 0) {
        finish(new Error(`Validation backend lookup failed with exit code ${code}`))
      } else {
        finish(null, stdout)
      }
    })
    signal?.addEventListener('abort', abortLookup, { once: true })
    if (signal?.aborted) abortLookup()

    function failAndTerminate(error, reapFailureMessage) {
      if (settled) return
      failure = failure ?? error
      terminateTrackedChild(
        tracker,
        reapFailureMessage,
        terminateGraceMs,
        killGraceMs,
      ).then(
        () => finish(failure),
        finish,
      )
    }

    function finish(error, value) {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      signal?.removeEventListener('abort', abortLookup)
      if (error) rejectOutput(error)
      else resolveOutput(value)
    }
  })
}

function trackChild(child) {
  let resolveReaped
  const tracker = {
    child,
    exited: false,
    reapError: undefined,
    reaped: new Promise((resolveReapedPromise) => { resolveReaped = resolveReapedPromise }),
    terminationPromise: undefined,
  }
  child.once('close', () => {
    tracker.exited = true
    resolveReaped()
  })
  return tracker
}

function terminateTrackedChild(
  tracker,
  failureMessage,
  terminateGraceMs = CHILD_TERMINATE_GRACE_MS,
  killGraceMs = CHILD_KILL_GRACE_MS,
) {
  if (tracker.exited) return Promise.resolve()
  if (tracker.terminationPromise) return tracker.terminationPromise
  tracker.terminationPromise = (async () => {
    requestChildSignal(tracker.child, 'SIGTERM')
    if (await settlesWithin(tracker.reaped, terminateGraceMs)) return
    requestChildSignal(tracker.child, 'SIGKILL')
    if (await settlesWithin(tracker.reaped, killGraceMs)) return
    throw new Error(failureMessage)
  })()
  return tracker.terminationPromise
}

function requestChildSignal(child, signal) {
  try {
    child.kill(signal)
  } catch {
    // The bounded reaper below remains authoritative even when signalling throws.
  }
}

async function settlesWithin(promise, timeoutMs) {
  let timeout
  try {
    return await Promise.race([
      promise.then(() => true),
      new Promise((resolveTimeout) => {
        timeout = setTimeout(() => resolveTimeout(false), timeoutMs)
      }),
    ])
  } finally {
    clearTimeout(timeout)
  }
}

function assertPositiveInteger(value, message) {
  if (!Number.isInteger(value) || value < 1) throw new Error(message)
}

async function closeServer(server, startPromise) {
  if (startPromise) {
    try {
      await startPromise
    } catch {
      return
    }
  }
  if (!server.listening) return
  await new Promise((resolveClose, rejectClose) => {
    server.close((error) => {
      if (error) rejectClose(error)
      else resolveClose()
    })
  })
}

function assertValidatedContainerId(containerId) {
  if (!CONTAINER_ID_PATTERN.test(containerId ?? '')) {
    throw new Error('Expected a validated container id')
  }
}

async function runCli() {
  const backendContainerId = await resolveValidationBackendContainerId()
  const relay = createValidationLoopbackRelay({ backendContainerId })
  await relay.start()
  process.stdout.write(`Validation loopback relay listening on ${RELAY_HOST}:${RELAY_PORT}\n`)

  let shuttingDown = false
  const shutdown = () => {
    if (shuttingDown) return
    shuttingDown = true
    relay.close().catch((error) => {
      process.stderr.write(`Validation loopback relay shutdown failed: ${error.message}\n`)
      process.exitCode = 1
    })
  }
  process.once('SIGINT', shutdown)
  process.once('SIGTERM', shutdown)
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : ''
if (invokedPath === import.meta.url) {
  runCli().catch((error) => {
    process.stderr.write(`Validation loopback relay failed: ${error.message}\n`)
    process.exitCode = 1
  })
}
