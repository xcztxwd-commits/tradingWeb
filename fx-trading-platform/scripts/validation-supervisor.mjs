import { spawn } from 'node:child_process'
import { createHash, timingSafeEqual } from 'node:crypto'
import { createServer, request as httpRequest } from 'node:http'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

import {
  createValidationLoopbackRelay,
  resolveValidationBackendContainerId,
} from './validation-loopback-relay.mjs'

export const SUPERVISOR_HOST = '127.0.0.1'
export const SUPERVISOR_PORT = 18088
export const SUPERVISOR_TOKEN_ENV = 'SUPERVISOR_INTERNAL_TOKEN'
export const COMPOSE_PROJECT = 'fx-trading-validation'
export const COMPOSE_SERVICES = Object.freeze([
  'validation-backend',
  'validation-postgres',
  'validation-redis',
])
export const HEALTH_URL = 'http://127.0.0.1:18087/actuator/health'
export const MAX_REQUEST_BODY_BYTES = 4096

const MODULE_DIRECTORY = dirname(fileURLToPath(import.meta.url))
const PLATFORM_ROOT = resolve(MODULE_DIRECTORY, '..')
export const COMPOSE_FILE = resolve(
  PLATFORM_ROOT,
  'infra',
  'docker-compose.validation.yml',
)

const SUPERVISOR_PATH = '/validation-supervisor'
const MAX_TOKEN_BYTES = 4096
const MIN_TOKEN_BYTES = 32
const MAX_CHILD_OUTPUT_BYTES = 128 * 1024
const MAX_HEALTH_BODY_BYTES = 64 * 1024
const DEFAULT_COMMAND_TIMEOUT_MS = 5 * 60 * 1000
const DEFAULT_HEALTH_TIMEOUT_MS = 5000
const DEFAULT_BODY_TIMEOUT_MS = 5000
const DEFAULT_CHILD_TERMINATE_GRACE_MS = 1000
const DEFAULT_CHILD_KILL_GRACE_MS = 1000
const JSON_CONTENT_TYPE_PATTERN = /^application\/json(?:\s*;\s*charset=utf-8)?$/iu
const MUTATING_ACTIONS = new Set(['start', 'stop', 'restart'])

const COMPOSE_PREFIX = Object.freeze([
  'compose',
  '-p',
  COMPOSE_PROJECT,
  '-f',
  COMPOSE_FILE,
])

const ACTION_ARGUMENTS = Object.freeze({
  status: Object.freeze([...COMPOSE_PREFIX, 'ps', '--format', 'json']),
  start: Object.freeze([...COMPOSE_PREFIX, 'up', '-d']),
  stop: Object.freeze([...COMPOSE_PREFIX, 'stop']),
  restart: Object.freeze([...COMPOSE_PREFIX, 'restart']),
})

const SPAWN_OPTIONS = Object.freeze({
  cwd: PLATFORM_ROOT,
  shell: false,
  windowsHide: true,
  stdio: Object.freeze(['ignore', 'pipe', 'pipe']),
})

class SupervisorFailure extends Error {
  constructor(status, code) {
    super(code)
    this.name = 'SupervisorFailure'
    this.status = status
    this.code = code
  }
}

export function createValidationSupervisor({
  token,
  listenPort = SUPERVISOR_PORT,
  spawnImpl = spawn,
  healthRequestImpl = httpRequest,
  relayLifecycle,
  commandTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
  healthTimeoutMs = DEFAULT_HEALTH_TIMEOUT_MS,
  bodyTimeoutMs = DEFAULT_BODY_TIMEOUT_MS,
  childTerminateGraceMs = DEFAULT_CHILD_TERMINATE_GRACE_MS,
  childKillGraceMs = DEFAULT_CHILD_KILL_GRACE_MS,
  maxChildOutputBytes = MAX_CHILD_OUTPUT_BYTES,
  maxHealthBodyBytes = MAX_HEALTH_BODY_BYTES,
} = {}) {
  const expectedAuthorizationDigest = validateAndDigestToken(token)
  assertIntegerInRange(listenPort, 0, 65535, 'Supervisor listen port')
  assertPositiveInteger(commandTimeoutMs, 'Supervisor command timeout')
  assertPositiveInteger(healthTimeoutMs, 'Supervisor health timeout')
  assertPositiveInteger(bodyTimeoutMs, 'Supervisor body timeout')
  assertPositiveInteger(childTerminateGraceMs, 'Supervisor child terminate grace')
  assertPositiveInteger(childKillGraceMs, 'Supervisor child kill grace')
  assertPositiveInteger(maxChildOutputBytes, 'Supervisor child output limit')
  assertPositiveInteger(maxHealthBodyBytes, 'Supervisor health body limit')

  let closed = false
  let startPromise
  let closePromise
  let mutationBusy = false
  let commandAuthorityPoisoned = false
  const inFlight = new Set()
  const commands = new Set()
  const children = new Set()
  const relayChildren = new Set()
  const sockets = new Set()
  const shutdownController = new AbortController()
  const childEnvironment = withoutSupervisorToken(process.env)
  const createFixedSpawnImpl = (ownerChildren) => {
    return (executable, args, options) => {
      if (closed) throw new Error('Validation Supervisor is closing')
      const child = spawnImpl(executable, args, {
        ...options,
        env: childEnvironment,
      })
      children.add(child)
      ownerChildren?.add(child)
      child.once('close', () => {
        children.delete(child)
        ownerChildren?.delete(child)
      })
      return child
    }
  }
  const fixedComposeSpawnImpl = createFixedSpawnImpl()
  const fixedRelaySpawnImpl = createFixedSpawnImpl(relayChildren)
  const fixedRelayLifecycle = relayLifecycle ?? createSupervisorRelayLifecycle({
    spawnImpl: fixedRelaySpawnImpl,
    signal: shutdownController.signal,
    childTerminateGraceMs,
    childKillGraceMs,
  })
  assertRelayLifecycle(fixedRelayLifecycle)

  const server = createServer((request, response) => {
    const operation = handleRequest(request, response)
      .catch((error) => {
        if (response.headersSent || response.destroyed) return
        const failure = error instanceof SupervisorFailure
          ? error
          : new SupervisorFailure(500, 'INTERNAL_ERROR')
        sendJson(response, failure.status, {
          ok: false,
          error: { code: failure.code },
        })
      })
      .finally(() => inFlight.delete(operation))
    inFlight.add(operation)
  })

  server.headersTimeout = bodyTimeoutMs
  server.requestTimeout = bodyTimeoutMs
  server.keepAliveTimeout = bodyTimeoutMs
  server.on('connection', (socket) => {
    sockets.add(socket)
    socket.once('close', () => sockets.delete(socket))
  })

  server.on('clientError', (_error, socket) => {
    if (!socket.destroyed) {
      socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n')
    }
  })

  async function handleRequest(request, response) {
    if (closed) {
      request.resume()
      throw new SupervisorFailure(503, 'SUPERVISOR_CLOSING')
    }
    if (request.url !== SUPERVISOR_PATH) {
      request.resume()
      throw new SupervisorFailure(404, 'NOT_FOUND')
    }
    if (request.method !== 'POST') {
      request.resume()
      response.setHeader('Allow', 'POST')
      throw new SupervisorFailure(405, 'METHOD_NOT_ALLOWED')
    }
    if (!isAuthorized(request, expectedAuthorizationDigest)) {
      request.resume()
      throw new SupervisorFailure(401, 'UNAUTHORIZED')
    }
    if (!hasJsonContentType(request)) {
      request.resume()
      throw new SupervisorFailure(415, 'UNSUPPORTED_MEDIA_TYPE')
    }

    const body = await readBoundedJsonBody(
      request,
      bodyTimeoutMs,
      shutdownController.signal,
    )
    const action = validateActionBody(body)
    const result = await executeAction(action)
    sendJson(response, 200, result)
  }

  async function executeAction(action) {
    if (closed) {
      throw new SupervisorFailure(503, 'SUPERVISOR_CLOSING')
    }
    if (action === 'health') {
      const health = await requestFixedHealth({
        requestImpl: healthRequestImpl,
        timeoutMs: healthTimeoutMs,
        maxBodyBytes: maxHealthBodyBytes,
        signal: shutdownController.signal,
      })
      return {
        ok: true,
        action,
        status: health.statusCode,
        health: health.body,
      }
    }

    if (commandAuthorityPoisoned) {
      throw new SupervisorFailure(503, 'COMMAND_AUTHORITY_UNAVAILABLE')
    }

    const mutating = MUTATING_ACTIONS.has(action)
    if (mutating && mutationBusy) {
      throw new SupervisorFailure(409, 'MUTATION_BUSY')
    }
    if (mutating) mutationBusy = true

    try {
      if (action === 'stop' || action === 'restart') {
        await stopRelayFixed()
      }
      if (closed) {
        throw new SupervisorFailure(503, 'SUPERVISOR_CLOSING')
      }

      const command = runFixedComposeCommand(action, {
        spawnImpl: fixedComposeSpawnImpl,
        timeoutMs: commandTimeoutMs,
        terminateGraceMs: childTerminateGraceMs,
        killGraceMs: childKillGraceMs,
        maxOutputBytes: maxChildOutputBytes,
        signal: shutdownController.signal,
        onUnreaped: () => { commandAuthorityPoisoned = true },
      })
      commands.add(command)
      let result
      try {
        result = await command
      } finally {
        commands.delete(command)
      }

      if (action === 'start' || action === 'restart') {
        if (closed) {
          throw new SupervisorFailure(503, 'SUPERVISOR_CLOSING')
        }
        await startRelayFixed()
      }

      return {
        ok: true,
        action,
        exitCode: result.exitCode,
        output: result.stdout,
        relayRunning: Boolean(fixedRelayLifecycle.isRunning()),
      }
    } finally {
      if (mutating) mutationBusy = false
    }
  }

  async function startRelayFixed() {
    try {
      await fixedRelayLifecycle.start()
    } catch {
      if (relayChildren.size > 0) commandAuthorityPoisoned = true
      throw new SupervisorFailure(502, 'RELAY_FAILED')
    }
  }

  async function stopRelayFixed() {
    try {
      await fixedRelayLifecycle.stop()
    } catch {
      if (relayChildren.size > 0) commandAuthorityPoisoned = true
      throw new SupervisorFailure(502, 'RELAY_FAILED')
    }
  }

  return {
    host: SUPERVISOR_HOST,
    port: listenPort,
    address() {
      return server.address()
    },
    start() {
      if (closed) return Promise.reject(new Error('Validation Supervisor is closed'))
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
        server.listen(listenPort, SUPERVISOR_HOST)
      })
      return startPromise
    },
    close() {
      if (closePromise) return closePromise
      closed = true
      shutdownController.abort()
      for (const socket of sockets) socket.destroy()
      closePromise = (async () => {
        const childReap = reapTrackedChildren(
          children,
          childTerminateGraceMs,
          childKillGraceMs,
        )
        const serverClose = closeHttpServer(server, startPromise)
        const results = await Promise.allSettled([
          serverClose,
          childReap,
          ...inFlight,
          ...commands,
        ])
        const closeFailure = results.find((result) => result.status === 'rejected')
        let relayFailure
        try {
          await fixedRelayLifecycle.stop()
        } catch (error) {
          relayFailure = error
        }
        if (closeFailure) throw closeFailure.reason
        if (relayFailure) throw relayFailure
      })()
      return closePromise
    },
  }
}

export function createSupervisorRelayLifecycle({
  spawnImpl = spawn,
  signal,
  childTerminateGraceMs = DEFAULT_CHILD_TERMINATE_GRACE_MS,
  childKillGraceMs = DEFAULT_CHILD_KILL_GRACE_MS,
} = {}) {
  assertPositiveInteger(childTerminateGraceMs, 'Relay child terminate grace')
  assertPositiveInteger(childKillGraceMs, 'Relay child kill grace')
  let relay
  let startPromise
  let stopPromise

  return {
    async start() {
      if (startPromise) return startPromise
      if (stopPromise) await stopPromise
      if (startPromise) return startPromise
      startPromise = (async () => {
        const current = relay
        if (current) {
          await current.close()
          if (relay === current) relay = undefined
        }
        const backendContainerId = await resolveValidationBackendContainerId({
          spawnImpl,
          signal,
          childTerminateGraceMs,
          childKillGraceMs,
        })
        const candidate = createValidationLoopbackRelay({
          backendContainerId,
          spawnImpl,
          childTerminateGraceMs,
          childKillGraceMs,
        })
        try {
          await candidate.start()
          relay = candidate
        } catch (error) {
          await candidate.close().catch(() => {})
          throw error
        }
      })()
      try {
        await startPromise
      } finally {
        startPromise = undefined
      }
    },
    async stop() {
      if (stopPromise) return stopPromise
      stopPromise = (async () => {
        if (startPromise) await startPromise.catch(() => {})
        const current = relay
        if (current) await current.close()
        if (relay === current) relay = undefined
      })()
      try {
        await stopPromise
      } finally {
        stopPromise = undefined
      }
    },
    isRunning() {
      return Boolean(relay?.address())
    },
  }
}

function runFixedComposeCommand(action, {
  spawnImpl,
  timeoutMs,
  terminateGraceMs,
  killGraceMs,
  maxOutputBytes,
  signal,
  onUnreaped,
}) {
  const fixedArgs = ACTION_ARGUMENTS[action]
  if (!fixedArgs) {
    return Promise.reject(new SupervisorFailure(400, 'INVALID_ACTION'))
  }

  let child
  try {
    child = spawnImpl('docker', [...fixedArgs], {
      cwd: SPAWN_OPTIONS.cwd,
      shell: SPAWN_OPTIONS.shell,
      windowsHide: SPAWN_OPTIONS.windowsHide,
      stdio: [...SPAWN_OPTIONS.stdio],
    })
  } catch {
    return Promise.reject(new SupervisorFailure(502, 'COMMAND_FAILED'))
  }
  return new Promise((resolveCommand, rejectCommand) => {
    let stdout = ''
    let retainedBytes = 0
    let failure
    let settled = false
    let closed = false
    let terminateTimer
    let killTimer

    const abortCommand = () => {
      failure = failure ?? new SupervisorFailure(503, 'SUPERVISOR_CLOSING')
      beginTermination()
    }

    const commandTimer = setTimeout(() => {
      failure = failure ?? new SupervisorFailure(504, 'COMMAND_TIMEOUT')
      beginTermination()
    }, timeoutMs)

    const collect = (chunk, retain) => {
      const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
      if (retainedBytes + buffer.length > maxOutputBytes) {
        failure = failure ?? new SupervisorFailure(502, 'COMMAND_OUTPUT_LIMIT')
        beginTermination()
        return
      }
      retainedBytes += buffer.length
      if (retain) stdout += buffer.toString('utf8')
    }

    child.stdout.on('data', (chunk) => collect(chunk, true))
    child.stderr.on('data', (chunk) => collect(chunk, false))
    child.stdout.on('error', () => {
      failure = failure ?? new SupervisorFailure(502, 'COMMAND_FAILED')
      beginTermination()
    })
    child.stderr.on('error', () => {
      failure = failure ?? new SupervisorFailure(502, 'COMMAND_FAILED')
      beginTermination()
    })
    child.once('error', () => {
      failure = failure ?? new SupervisorFailure(502, 'COMMAND_FAILED')
      beginTermination()
    })
    child.once('close', (exitCode, signal) => {
      closed = true
      if (failure) {
        finish(failure)
      } else if (exitCode !== 0 || signal !== null) {
        finish(new SupervisorFailure(502, 'COMMAND_FAILED'))
      } else {
        finish(null, { exitCode, stdout })
      }
    })
    signal?.addEventListener('abort', abortCommand, { once: true })
    if (signal?.aborted) abortCommand()

    function beginTermination() {
      if (closed || terminateTimer || killTimer) return
      requestChildSignal(child, 'SIGTERM')
      terminateTimer = setTimeout(() => {
        if (closed) return
        requestChildSignal(child, 'SIGKILL')
        killTimer = setTimeout(() => {
          if (closed) return
          onUnreaped()
          finish(new SupervisorFailure(503, 'COMMAND_REAP_FAILED'))
        }, killGraceMs)
      }, terminateGraceMs)
    }

    function finish(error, result) {
      if (settled) return
      settled = true
      clearTimeout(commandTimer)
      clearTimeout(terminateTimer)
      clearTimeout(killTimer)
      signal?.removeEventListener('abort', abortCommand)
      if (error) rejectCommand(error)
      else resolveCommand(result)
    }
  })
}

function requestFixedHealth({ requestImpl, timeoutMs, maxBodyBytes, signal }) {
  return new Promise((resolveHealth, rejectHealth) => {
    let settled = false
    let timedOut = false
    let outgoing
    let absoluteTimer
    const abortHealth = () => {
      outgoing?.destroy()
      finish(new SupervisorFailure(503, 'SUPERVISOR_CLOSING'))
    }
    try {
      outgoing = requestImpl(HEALTH_URL, {
        method: 'GET',
        headers: {
          accept: 'application/json',
          connection: 'close',
        },
      }, (incoming) => {
        const chunks = []
        let bytes = 0
        let tooLarge = false
        incoming.on('data', (chunk) => {
          const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
          bytes += buffer.length
          if (bytes > maxBodyBytes) {
            tooLarge = true
            incoming.destroy()
            finish(new SupervisorFailure(502, 'HEALTH_RESPONSE_LIMIT'))
            return
          }
          chunks.push(buffer)
        })
        incoming.once('error', () => {
          if (!tooLarge) finish(new SupervisorFailure(502, 'HEALTH_UNAVAILABLE'))
        })
        incoming.once('end', () => {
          if (tooLarge) {
            finish(new SupervisorFailure(502, 'HEALTH_RESPONSE_LIMIT'))
            return
          }
          if (incoming.statusCode < 200 || incoming.statusCode >= 300) {
            finish(new SupervisorFailure(503, 'HEALTH_UNAVAILABLE'))
            return
          }
          try {
            const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
            if (body === null || Array.isArray(body) || typeof body !== 'object') {
              throw new Error('Invalid health document')
            }
            finish(null, { statusCode: incoming.statusCode, body })
          } catch {
            finish(new SupervisorFailure(502, 'HEALTH_INVALID_RESPONSE'))
          }
        })
      })
    } catch {
      rejectHealth(new SupervisorFailure(502, 'HEALTH_UNAVAILABLE'))
      return
    }

    outgoing.once('error', () => {
      finish(new SupervisorFailure(
        timedOut ? 504 : 502,
        timedOut ? 'HEALTH_TIMEOUT' : 'HEALTH_UNAVAILABLE',
      ))
    })
    const expire = () => {
      if (settled) return
      timedOut = true
      outgoing.destroy(new Error('Validation health request timed out'))
    }
    outgoing.setTimeout(timeoutMs, expire)
    absoluteTimer = setTimeout(expire, timeoutMs)
    signal?.addEventListener('abort', abortHealth, { once: true })
    if (signal?.aborted) {
      abortHealth()
      return
    }
    outgoing.end()

    function finish(error, value) {
      if (settled) return
      settled = true
      clearTimeout(absoluteTimer)
      signal?.removeEventListener('abort', abortHealth)
      if (error) rejectHealth(error)
      else resolveHealth(value)
    }
  })
}

function validateAndDigestToken(token) {
  if (typeof token !== 'string') {
    throw new Error('Supervisor internal token is required')
  }
  const tokenBytes = Buffer.byteLength(token)
  if (
    tokenBytes < MIN_TOKEN_BYTES
    || tokenBytes > MAX_TOKEN_BYTES
    || token.trim() !== token
    || /[\r\n]/u.test(token)
  ) {
    throw new Error('Supervisor internal token is invalid')
  }
  return sha256(`Bearer ${token}`)
}

function isAuthorized(request, expectedDigest) {
  const values = rawHeaderValues(request, 'authorization')
  const supplied = values.length === 1 ? values[0] : ''
  const suppliedDigest = sha256(supplied)
  return values.length === 1 && timingSafeEqual(expectedDigest, suppliedDigest)
}

function hasJsonContentType(request) {
  const values = rawHeaderValues(request, 'content-type')
  return values.length === 1 && JSON_CONTENT_TYPE_PATTERN.test(values[0])
}

function rawHeaderValues(request, targetName) {
  const values = []
  for (let index = 0; index < request.rawHeaders.length; index += 2) {
    if (request.rawHeaders[index].toLowerCase() === targetName) {
      values.push(request.rawHeaders[index + 1])
    }
  }
  return values
}

function readBoundedJsonBody(request, timeoutMs, signal) {
  const declaredLength = request.headers['content-length']
  if (declaredLength !== undefined) {
    const parsedLength = Number(declaredLength)
    if (!Number.isSafeInteger(parsedLength) || parsedLength < 0) {
      request.resume()
      return Promise.reject(new SupervisorFailure(400, 'INVALID_REQUEST'))
    }
    if (parsedLength > MAX_REQUEST_BODY_BYTES) {
      request.resume()
      return Promise.reject(new SupervisorFailure(413, 'REQUEST_TOO_LARGE'))
    }
  }

  return new Promise((resolveBody, rejectBody) => {
    const chunks = []
    let bytes = 0
    let tooLarge = false
    let settled = false
    const abortBody = () => {
      request.destroy()
      finish(new SupervisorFailure(503, 'SUPERVISOR_CLOSING'))
    }
    const timer = setTimeout(() => {
      request.resume()
      finish(new SupervisorFailure(408, 'REQUEST_TIMEOUT'))
    }, timeoutMs)

    request.on('data', (chunk) => {
      const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
      bytes += buffer.length
      if (bytes > MAX_REQUEST_BODY_BYTES) {
        tooLarge = true
        return
      }
      chunks.push(buffer)
    })
    request.once('aborted', () => finish(new SupervisorFailure(400, 'INVALID_REQUEST')))
    request.once('error', () => finish(new SupervisorFailure(400, 'INVALID_REQUEST')))
    request.once('end', () => {
      if (tooLarge) {
        finish(new SupervisorFailure(413, 'REQUEST_TOO_LARGE'))
        return
      }
      try {
        const text = Buffer.concat(chunks).toString('utf8')
        if (text.length === 0) throw new Error('Empty body')
        finish(null, JSON.parse(text))
      } catch {
        finish(new SupervisorFailure(400, 'INVALID_REQUEST'))
      }
    })
    signal?.addEventListener('abort', abortBody, { once: true })
    if (signal?.aborted) abortBody()

    function finish(error, value) {
      if (settled) return
      settled = true
      clearTimeout(timer)
      signal?.removeEventListener('abort', abortBody)
      if (error) rejectBody(error)
      else resolveBody(value)
    }
  })
}

function validateActionBody(body) {
  if (body === null || Array.isArray(body) || typeof body !== 'object') {
    throw new SupervisorFailure(400, 'INVALID_REQUEST')
  }
  const keys = Object.keys(body)
  if (keys.length !== 1 || keys[0] !== 'action' || typeof body.action !== 'string') {
    throw new SupervisorFailure(400, 'INVALID_REQUEST')
  }
  if (body.action !== 'health' && !ACTION_ARGUMENTS[body.action]) {
    throw new SupervisorFailure(400, 'INVALID_ACTION')
  }
  return body.action
}

function sendJson(response, status, body) {
  const payload = Buffer.from(JSON.stringify(body))
  response.writeHead(status, {
    'Cache-Control': 'no-store',
    Connection: 'close',
    'Content-Length': payload.length,
    'Content-Type': 'application/json; charset=utf-8',
    'X-Content-Type-Options': 'nosniff',
  })
  response.end(payload)
}

function closeHttpServer(server, startPromise) {
  return (async () => {
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
  })()
}

function requestChildSignal(child, signal) {
  try {
    child.kill(signal)
  } catch {
    // The bounded reap state remains authoritative.
  }
}

async function reapTrackedChildren(children, terminateGraceMs, killGraceMs) {
  if (children.size === 0) return
  for (const child of children) requestChildSignal(child, 'SIGTERM')
  if (await setBecomesEmpty(children, terminateGraceMs)) return
  for (const child of children) requestChildSignal(child, 'SIGKILL')
  if (await setBecomesEmpty(children, killGraceMs)) return
  throw new Error('Validation Supervisor has unreaped child processes')
}

function setBecomesEmpty(values, timeoutMs) {
  if (values.size === 0) return Promise.resolve(true)
  return new Promise((resolveEmpty) => {
    const startedAt = Date.now()
    const interval = setInterval(() => {
      if (values.size === 0) {
        clearInterval(interval)
        resolveEmpty(true)
      } else if (Date.now() - startedAt >= timeoutMs) {
        clearInterval(interval)
        resolveEmpty(false)
      }
    }, Math.min(10, timeoutMs))
  })
}

function sha256(value) {
  return createHash('sha256').update(value, 'utf8').digest()
}

function withoutSupervisorToken(environment) {
  return Object.fromEntries(
    Object.entries(environment).filter(
      ([key]) => key.toUpperCase() !== SUPERVISOR_TOKEN_ENV,
    ),
  )
}

function assertRelayLifecycle(value) {
  if (
    value === null
    || typeof value !== 'object'
    || typeof value.start !== 'function'
    || typeof value.stop !== 'function'
    || typeof value.isRunning !== 'function'
  ) {
    throw new Error('Invalid fixed validation relay lifecycle')
  }
}

function assertPositiveInteger(value, label) {
  assertIntegerInRange(value, 1, Number.MAX_SAFE_INTEGER, label)
}

function assertIntegerInRange(value, minimum, maximum, label) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${label} is invalid`)
  }
}

async function runCli() {
  const supervisor = createValidationSupervisor({
    token: process.env[SUPERVISOR_TOKEN_ENV],
  })
  await supervisor.start()
  process.stdout.write(
    `Validation Supervisor listening on ${SUPERVISOR_HOST}:${SUPERVISOR_PORT}\n`,
  )

  let shuttingDown = false
  const shutdown = () => {
    if (shuttingDown) return
    shuttingDown = true
    supervisor.close().catch(() => {
      process.stderr.write('Validation Supervisor shutdown failed\n')
      process.exitCode = 1
    })
  }
  process.once('SIGINT', shutdown)
  process.once('SIGTERM', shutdown)
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : ''
if (invokedPath === import.meta.url) {
  runCli().catch(() => {
    process.stderr.write('Validation Supervisor failed to start\n')
    process.exitCode = 1
  })
}
