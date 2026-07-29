import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import {
  mkdirSync,
  mkdtempSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

const verifier = await import('./verify-architecture.mjs')
const findArchitectureViolations = verifier.findArchitectureViolations ?? (() => [])
const temporaryRoots = []

afterEach(() => {
  for (const root of temporaryRoots.splice(0)) {
    rmSync(root, { force: true, recursive: true })
  }
})

test('rejects an Admin source that calls the validation backend directly', () => {
  const root = fixture({
    'apps/admin/src/features/tradingLab/api/directValidation.ts': `
      export const loadState = () =>
        fetch('http://127.0.0.1:18087/internal/validation/state')
    `,
  })

  assertViolation(
    findArchitectureViolations(root),
    'Trading Lab admin must not call validation directly:',
  )
})

test('rejects a main Trading Lab orchestrator importing OrderService', () => {
  const root = fixture({
    'backend/src/main/java/com/fxplatform/tradinglab/application/BadCoordinator.java': `
      package com.fxplatform.tradinglab.application;
      import com.fxplatform.trading.service.OrderService;
      final class BadCoordinator {
        private final OrderService orders = null;
      }
    `,
  })

  assertViolation(
    findArchitectureViolations(root),
    'Trading Lab orchestrator must not import OrderService:',
  )
})

test('rejects a Validation Supervisor with a dynamic command or compose path', () => {
  const root = fixture({
    'scripts/validation-supervisor.mjs': `
      import { spawn } from 'node:child_process'
      const composeFile = process.env.VALIDATION_COMPOSE_FILE
      export function run(request) {
        return spawn(request.command, ['compose', '-f', composeFile], { shell: true })
      }
    `,
  })

  assertViolation(
    findArchitectureViolations(root),
    'Validation Supervisor command and compose path must be fixed:',
  )
})

test('rejects Validation Compose sharing a main volume or network', () => {
  const root = fixture({
    'infra/docker-compose.yml': `
      services:
        postgres:
          image: postgres:16
      volumes:
        main-postgres-data:
      networks:
        main-internal:
    `,
    'infra/docker-compose.validation.yml': `
      name: fx-trading-validation
      services:
        validation-backend:
          image: validation
          volumes:
            - main-postgres-data:/var/lib/postgresql/data
          networks:
            - main-internal
      volumes:
        main-postgres-data:
      networks:
        main-internal:
    `,
  })

  assertViolation(
    findArchitectureViolations(root),
    'Validation Compose must not share main volumes or networks:',
  )
})

test('rejects live execution or an enabled external provider in validation profile', () => {
  const root = fixture({
    'backend/src/main/resources/application-validation.yml': `
      execution:
        mode: live
      market:
        realtime:
          enabled: true
          provider: binance
    `,
  })

  assertViolation(
    findArchitectureViolations(root),
    'Validation profile must keep demo execution and external providers disabled:',
  )
})

test('rejects a Trading Lab report entity without the chunk table entity', () => {
  const root = fixture({
    'backend/src/main/java/com/fxplatform/tradinglab/entity/TradingLabReportEntity.java': `
      package com.fxplatform.tradinglab.entity;
      final class TradingLabReportEntity {}
    `,
  })

  assertViolation(
    findArchitectureViolations(root),
    'Trading Lab reports must use the chunk table:',
  )
})

function fixture(files) {
  const root = mkdtempSync(join(tmpdir(), 'trading-lab-architecture-'))
  temporaryRoots.push(root)
  for (const [relativePath, content] of Object.entries(files)) {
    const path = join(root, relativePath)
    mkdirSync(dirname(path), { recursive: true })
    writeFileSync(path, dedent(content), 'utf8')
  }
  return root
}

function dedent(value) {
  const lines = value.replace(/^\s*\r?\n/u, '').split(/\r?\n/u)
  const indentation = Math.min(
    ...lines
      .filter((line) => line.trim() !== '')
      .map((line) => /^\s*/u.exec(line)[0].length),
  )
  return `${lines.map((line) => line.slice(indentation)).join('\n').trimEnd()}\n`
}

function assertViolation(violations, prefix) {
  assert.ok(
    violations.some((violation) => violation.startsWith(prefix)),
    `Expected violation "${prefix}", received:\n${violations.join('\n')}`,
  )
}
