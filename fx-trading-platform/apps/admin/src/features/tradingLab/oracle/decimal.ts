export type RoundingMode = 'DOWN' | 'UP' | 'HALF_UP'

export type Decimal = Readonly<{
  coefficient: bigint
  scale: number
}>

export class DecimalError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DecimalError'
  }
}

const DECIMAL_PATTERN = /^-?\d+(?:\.\d+)?$/

function assertScale(scale: number): void {
  if (!Number.isSafeInteger(scale) || scale < 0) {
    throw new DecimalError('scale must be a non-negative safe integer')
  }
}

function assertRoundingMode(rounding: RoundingMode): void {
  if (rounding !== 'DOWN' && rounding !== 'UP' && rounding !== 'HALF_UP') {
    throw new DecimalError('unsupported rounding mode')
  }
}

function createDecimal(coefficient: bigint, scale: number): Decimal {
  if (typeof coefficient !== 'bigint') {
    throw new DecimalError('coefficient must be a bigint')
  }
  assertScale(scale)

  if (coefficient === 0n) {
    return Object.freeze({ coefficient: 0n, scale: 0 })
  }

  let normalizedCoefficient = coefficient
  let normalizedScale = scale
  while (normalizedScale > 0 && normalizedCoefficient % 10n === 0n) {
    normalizedCoefficient /= 10n
    normalizedScale -= 1
  }

  return Object.freeze({
    coefficient: normalizedCoefficient,
    scale: normalizedScale,
  })
}

function checkedDecimal(value: Decimal): Decimal {
  if (value === null || typeof value !== 'object') {
    throw new DecimalError('value must be a decimal')
  }
  return createDecimal(value.coefficient, value.scale)
}

function powerOfTen(exponent: bigint): bigint {
  if (exponent < 0n) {
    throw new DecimalError('power exponent must not be negative')
  }
  return 10n ** exponent
}

function alignedCoefficients(left: Decimal, right: Decimal): readonly [bigint, bigint, number] {
  const normalizedLeft = checkedDecimal(left)
  const normalizedRight = checkedDecimal(right)
  const scale = normalizedLeft.scale > normalizedRight.scale ? normalizedLeft.scale : normalizedRight.scale

  return [
    normalizedLeft.coefficient * powerOfTen(BigInt(scale - normalizedLeft.scale)),
    normalizedRight.coefficient * powerOfTen(BigInt(scale - normalizedRight.scale)),
    scale,
  ]
}

function absolute(value: bigint): bigint {
  return value < 0n ? -value : value
}

function roundedQuotient(
  numerator: bigint,
  denominator: bigint,
  rounding: RoundingMode,
): bigint {
  assertRoundingMode(rounding)
  if (denominator === 0n) {
    throw new DecimalError('division by zero')
  }

  let normalizedNumerator = numerator
  let normalizedDenominator = denominator
  if (normalizedDenominator < 0n) {
    normalizedNumerator = -normalizedNumerator
    normalizedDenominator = -normalizedDenominator
  }

  const quotient = normalizedNumerator / normalizedDenominator
  const remainder = normalizedNumerator % normalizedDenominator
  if (remainder === 0n || rounding === 'DOWN') {
    return quotient
  }

  const direction = normalizedNumerator < 0n ? -1n : 1n
  if (rounding === 'UP') {
    return quotient + direction
  }

  return absolute(remainder) * 2n >= normalizedDenominator
    ? quotient + direction
    : quotient
}

export function decimal(value: string | bigint): Decimal {
  if (typeof value === 'bigint') {
    return createDecimal(value, 0)
  }
  if (typeof value !== 'string' || !DECIMAL_PATTERN.test(value)) {
    throw new DecimalError('value must be a plain signed decimal string or bigint')
  }

  const negative = value.startsWith('-')
  const unsigned = negative ? value.slice(1) : value
  const [whole, fraction = ''] = unsigned.split('.')
  const coefficient = BigInt(`${whole}${fraction}`)

  return createDecimal(negative ? -coefficient : coefficient, fraction.length)
}

export function add(left: Decimal, right: Decimal): Decimal {
  const [leftCoefficient, rightCoefficient, scale] = alignedCoefficients(left, right)
  return createDecimal(leftCoefficient + rightCoefficient, scale)
}

export function subtract(left: Decimal, right: Decimal): Decimal {
  const [leftCoefficient, rightCoefficient, scale] = alignedCoefficients(left, right)
  return createDecimal(leftCoefficient - rightCoefficient, scale)
}

export function multiply(left: Decimal, right: Decimal): Decimal {
  const normalizedLeft = checkedDecimal(left)
  const normalizedRight = checkedDecimal(right)
  const scale = normalizedLeft.scale + normalizedRight.scale
  assertScale(scale)

  return createDecimal(
    normalizedLeft.coefficient * normalizedRight.coefficient,
    scale,
  )
}

export function divide(
  left: Decimal,
  right: Decimal,
  scale: number,
  rounding: RoundingMode,
): Decimal {
  const normalizedLeft = checkedDecimal(left)
  const normalizedRight = checkedDecimal(right)
  assertScale(scale)
  assertRoundingMode(rounding)
  if (normalizedRight.coefficient === 0n) {
    throw new DecimalError('division by zero')
  }

  const exponent = BigInt(normalizedRight.scale) + BigInt(scale) - BigInt(normalizedLeft.scale)
  const numerator = exponent >= 0n
    ? normalizedLeft.coefficient * powerOfTen(exponent)
    : normalizedLeft.coefficient
  const denominator = exponent >= 0n
    ? normalizedRight.coefficient
    : normalizedRight.coefficient * powerOfTen(-exponent)

  return createDecimal(
    roundedQuotient(numerator, denominator, rounding),
    scale,
  )
}

export function quantize(
  value: Decimal,
  scale: number,
  rounding: RoundingMode,
): Decimal {
  const normalizedValue = checkedDecimal(value)
  assertScale(scale)
  assertRoundingMode(rounding)
  if (scale >= normalizedValue.scale) {
    return normalizedValue
  }

  const divisor = powerOfTen(BigInt(normalizedValue.scale - scale))
  return createDecimal(
    roundedQuotient(normalizedValue.coefficient, divisor, rounding),
    scale,
  )
}

export function floorToStep(value: Decimal, step: Decimal): Decimal {
  const normalizedValue = checkedDecimal(value)
  const normalizedStep = checkedDecimal(step)
  if (normalizedValue.coefficient < 0n) {
    throw new DecimalError('value must not be negative')
  }
  if (normalizedStep.coefficient <= 0n) {
    throw new DecimalError('step must be positive')
  }

  return multiply(
    divide(normalizedValue, normalizedStep, 0, 'DOWN'),
    normalizedStep,
  )
}

export function compare(left: Decimal, right: Decimal): -1 | 0 | 1 {
  const [leftCoefficient, rightCoefficient] = alignedCoefficients(left, right)
  if (leftCoefficient < rightCoefficient) {
    return -1
  }
  if (leftCoefficient > rightCoefficient) {
    return 1
  }
  return 0
}

export function toDecimalString(value: Decimal): string {
  const normalizedValue = checkedDecimal(value)
  const negative = normalizedValue.coefficient < 0n
  const digits = absolute(normalizedValue.coefficient).toString()
  const prefix = negative ? '-' : ''
  if (normalizedValue.scale === 0) {
    return `${prefix}${digits}`
  }

  const decimalPoint = digits.length - normalizedValue.scale
  if (decimalPoint > 0) {
    return `${prefix}${digits.slice(0, decimalPoint)}.${digits.slice(decimalPoint)}`
  }

  return `${prefix}0.${'0'.repeat(-decimalPoint)}${digits}`
}
