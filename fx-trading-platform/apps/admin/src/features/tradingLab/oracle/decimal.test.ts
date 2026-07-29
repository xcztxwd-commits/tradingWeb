import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import {
  DecimalError,
  add,
  compare,
  decimal,
  divide,
  floorToStep,
  multiply,
  quantize,
  subtract,
  toDecimalString,
  type Decimal,
} from './decimal.ts'

function text(value: Decimal): string {
  return toDecimalString(value)
}

test('parses and prints canonical plain decimals without losing precision', () => {
  assert.deepEqual(decimal('0.00000001'), { coefficient: 1n, scale: 8 })
  assert.equal(text(decimal('00060000.12000')), '60000.12')
  assert.equal(text(decimal('-0.0000')), '0')
  assert.equal(
    text(decimal('123456789012345678901234567890.12345678901234567890')),
    '123456789012345678901234567890.1234567890123456789',
  )
  assert.equal(text(decimal(90071992547409931234567890n)), '90071992547409931234567890')
})

test('adds and subtracts mixed scales exactly', () => {
  assert.equal(text(add(decimal('0.1'), decimal('0.2'))), '0.3')
  assert.equal(text(add(decimal('999999999999999999.99999999'), decimal('0.00000001'))), '1000000000000000000')
  assert.equal(text(subtract(decimal('1'), decimal('0.00000001'))), '0.99999999')
  assert.equal(text(subtract(decimal('-1.25'), decimal('-0.25'))), '-1')
})

test('multiplies using integer coefficients only', () => {
  assert.equal(text(multiply(decimal('60000.12'), decimal('0.005'))), '300.0006')
  assert.equal(text(multiply(decimal('-0.00000001'), decimal('100000000'))), '-1')
})

test('divides with explicit DOWN, UP, and HALF_UP rounding', () => {
  assert.equal(text(divide(decimal('1'), decimal('3'), 8, 'DOWN')), '0.33333333')
  assert.equal(text(divide(decimal('1'), decimal('3'), 2, 'UP')), '0.34')
  assert.equal(text(divide(decimal('-1'), decimal('3'), 2, 'DOWN')), '-0.33')
  assert.equal(text(divide(decimal('-1'), decimal('3'), 2, 'UP')), '-0.34')
  assert.equal(text(divide(decimal('1'), decimal('8'), 2, 'HALF_UP')), '0.13')
  assert.equal(text(divide(decimal('-1'), decimal('2'), 0, 'HALF_UP')), '-1')
  assert.equal(text(divide(decimal('10'), decimal('4'), 4, 'DOWN')), '2.5')
})

test('quantizes positive and negative values with Java BigDecimal rounding semantics', () => {
  assert.equal(text(quantize(decimal('1.234'), 2, 'DOWN')), '1.23')
  assert.equal(text(quantize(decimal('1.234'), 2, 'UP')), '1.24')
  assert.equal(text(quantize(decimal('1.234'), 2, 'HALF_UP')), '1.23')
  assert.equal(text(quantize(decimal('1.235'), 2, 'HALF_UP')), '1.24')
  assert.equal(text(quantize(decimal('-1.234'), 2, 'DOWN')), '-1.23')
  assert.equal(text(quantize(decimal('-1.234'), 2, 'UP')), '-1.24')
  assert.equal(text(quantize(decimal('-1.235'), 2, 'HALF_UP')), '-1.24')
  assert.equal(text(quantize(decimal('1.2'), 8, 'HALF_UP')), '1.2')
})

test('floors non-negative trading values to a positive step', () => {
  assert.equal(text(floorToStep(decimal('1.234567'), decimal('0.001'))), '1.234')
  assert.equal(text(floorToStep(decimal('0.000000019'), decimal('0.00000001'))), '0.00000001')
  assert.equal(text(floorToStep(decimal('10'), decimal('2.5'))), '10')
})

test('compares decimals independently of scale', () => {
  assert.equal(compare(decimal('1.2300'), decimal('1.23')), 0)
  assert.equal(compare(decimal('-0.01'), decimal('0')), -1)
  assert.equal(compare(decimal('100000000000000000000'), decimal('99999999999999999999.99999999')), 1)
})

test('rejects malformed input rather than coercing browser numbers', () => {
  const parseUnknown = decimal as (value: unknown) => Decimal
  for (const value of ['', ' ', ' 1', '1 ', '+1', '.5', '1.', '1e3', 'NaN', 'Infinity', '--1', 0.1, null]) {
    assert.throws(() => parseUnknown(value), DecimalError)
  }
})

test('rejects invalid arithmetic controls and impossible operations', () => {
  assert.throws(() => divide(decimal('1'), decimal('0'), 2, 'DOWN'), DecimalError)
  assert.throws(() => divide(decimal('1'), decimal('2'), -1, 'DOWN'), DecimalError)
  assert.throws(() => divide(decimal('1'), decimal('2'), 1.5, 'DOWN'), DecimalError)
  assert.throws(() => quantize(decimal('1'), Number.MAX_SAFE_INTEGER + 1, 'DOWN'), DecimalError)
  assert.throws(() => floorToStep(decimal('-0.1'), decimal('0.01')), DecimalError)
  assert.throws(() => floorToStep(decimal('1'), decimal('0')), DecimalError)
  assert.throws(() => floorToStep(decimal('1'), decimal('-0.1')), DecimalError)
})

test('production source has no floating-point or backend-oracle escape hatch', () => {
  const source = readFileSync(new URL('./decimal.ts', import.meta.url), 'utf8')
  const forbidden = [
    'parseFloat',
    'parseInt',
    'Number(',
    'Math.round',
    'toExponential',
    'BigDecimal',
    '/backend/',
    '\\backend\\',
  ]

  for (const token of forbidden) {
    assert.equal(source.includes(token), false, `forbidden token: ${token}`)
  }
  assert.doesNotMatch(source, /\d+(?:\.\d+)?[eE][+-]?\d+/)
})
