import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import { PrngSeedError, createPrng } from './prng.ts'

const UINT32_RANGE = 4_294_967_296

function uint32Sequence(seed: string, count: number): number[] {
  const next = createPrng(seed)
  return Array.from({ length: count }, () => next() * UINT32_RANGE)
}

test('matches the frozen FNV-1a UTF-8 plus Mulberry32-v1 vectors', () => {
  assert.deepEqual(
    uint32Sequence('001', 4),
    [2465129208, 849744588, 1148051309, 2544436958],
  )
  assert.deepEqual(
    uint32Sequence('seed-中文', 4),
    [364959174, 1396918064, 180313900, 700739425],
  )
})

test('preserves the exact opaque seed and returns deterministic values in [0, 1)', () => {
  const first = createPrng('001')
  const replay = createPrng('001')
  const trailingSpace = createPrng('001 ')

  for (let index = 0; index < 32; index += 1) {
    const value = first()
    assert.equal(value, replay())
    assert.ok(value >= 0)
    assert.ok(value < 1)
  }
  assert.notDeepEqual(
    uint32Sequence('001', 8),
    Array.from({ length: 8 }, () => trailingSpace() * UINT32_RANGE),
  )
})

test('rejects blank, over-256-unit, non-string, and unpaired-surrogate seeds', () => {
  const invalidSeeds: unknown[] = [
    '',
    ' ',
    '\t\r\n',
    'a'.repeat(257),
    '😀'.repeat(129),
    String.fromCharCode(0xd800),
    String.fromCharCode(0xdc00),
    `valid${String.fromCharCode(0xd800)}tail`,
    1,
    null,
  ]

  for (const seed of invalidSeeds) {
    assert.throws(
      () => (createPrng as (value: unknown) => () => number)(seed),
      (error: unknown) => (
        error instanceof PrngSeedError
        && error.code === 'PRNG_SEED_INVALID'
      ),
    )
  }

  assert.doesNotThrow(() => createPrng('😀'.repeat(128)))
})

test('production PRNG source has no ambient random, clock, crypto, or backend escape hatch', () => {
  const source = readFileSync(new URL('./prng.ts', import.meta.url), 'utf8')
  const forbidden = [
    'Math.random',
    'Date.now',
    'new Date',
    'performance.',
    'randomUUID',
    'getRandomValues',
    'node:crypto',
    '/backend/',
    '\\backend\\',
  ]

  for (const token of forbidden) {
    assert.equal(source.includes(token), false, `forbidden token: ${token}`)
  }
  assert.match(source, /TextEncoder/)
  assert.doesNotMatch(source, /charCodeAt/)
})
