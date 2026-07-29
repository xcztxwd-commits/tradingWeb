const FNV1A_OFFSET_BASIS = 0x811c9dc5
const FNV1A_PRIME = 0x01000193
const MULBERRY32_INCREMENT = 0x6d2b79f5
const UINT32_RANGE = 4_294_967_296
const MAX_SEED_UTF16_UNITS = 256

export class PrngSeedError extends Error {
  readonly code = 'PRNG_SEED_INVALID' as const

  constructor(message: string) {
    super(message)
    this.name = 'PrngSeedError'
  }
}

function assertWellFormedUnicode(value: string): void {
  for (const character of value) {
    const codePoint = character.codePointAt(0)
    if (
      codePoint === undefined
      || (codePoint >= 0xd800 && codePoint <= 0xdfff)
    ) {
      throw new PrngSeedError('seed must not contain an unpaired surrogate')
    }
  }
}

function assertValidSeed(seed: unknown): asserts seed is string {
  if (
    typeof seed !== 'string'
    || seed.length === 0
    || seed.length > MAX_SEED_UTF16_UNITS
    || seed.trim().length === 0
  ) {
    throw new PrngSeedError('seed must contain 1..256 UTF-16 units')
  }
  assertWellFormedUnicode(seed)
}

function fnv1aUtf8(seed: string): number {
  let hash = FNV1A_OFFSET_BASIS
  for (const byte of new TextEncoder().encode(seed)) {
    hash ^= byte
    hash = Math.imul(hash, FNV1A_PRIME) >>> 0
  }
  return hash >>> 0
}

function createMulberry32(initialState: number): () => number {
  let state = initialState

  return () => {
    state = (state + MULBERRY32_INCREMENT) >>> 0
    let value = state
    value = Math.imul(value ^ (value >>> 15), value | 1) >>> 0
    value ^= (
      value
      + Math.imul(value ^ (value >>> 7), value | 61)
    ) >>> 0
    value = (value ^ (value >>> 14)) >>> 0
    return value / UINT32_RANGE
  }
}

/**
 * FNV-1a-32 over the seed's exact UTF-8 bytes, followed by Mulberry32-v1.
 * The returned values are deterministic unsigned uint32 values scaled by 2^32.
 */
export function createPrng(seed: string): () => number {
  assertValidSeed(seed)
  return createMulberry32(fnv1aUtf8(seed))
}

/**
 * @internal Hashes an already domain-separated string without applying the
 * public single-seed length limit. The complete valid Unicode string is hashed
 * as exact UTF-8 bytes; it is never truncated or normalized.
 */
export function createPrngFromUtf8Domain(domain: string): () => number {
  if (typeof domain !== 'string' || domain.length === 0) {
    throw new PrngSeedError('domain must not be empty')
  }
  assertWellFormedUnicode(domain)
  return createMulberry32(fnv1aUtf8(domain))
}
