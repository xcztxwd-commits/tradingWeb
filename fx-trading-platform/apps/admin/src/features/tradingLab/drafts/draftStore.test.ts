import assert from 'node:assert/strict'
import test from 'node:test'

import { hashScenarioConfig } from '../model/normalization.ts'
import type {
  MarketPathDefinition,
  TradingLabConfigSnapshot,
  TradingLabScenario,
} from '../model/types.ts'
import { createTradingLabDraftStore } from './draftStore.ts'

type FakeTransactionOutcome = 'complete' | 'abort' | 'error' | 'manual'

class FakeNameList {
  readonly names: Set<string>

  constructor(names: Iterable<string> = []) {
    this.names = new Set(names)
  }

  get length(): number {
    return this.names.size
  }

  contains(name: string): boolean {
    return this.names.has(name)
  }

  item(index: number): string | null {
    return Array.from(this.names)[index] ?? null
  }
}

class FakeRequest<T> {
  result: T | undefined
  error: DOMException | null = null
  onsuccess: ((event: Event) => unknown) | null = null
  onerror: ((event: Event) => unknown) | null = null
}

class FakeOpenRequest extends FakeRequest<IDBDatabase> {
  onblocked: ((event: Event) => unknown) | null = null
  onupgradeneeded: ((event: Event) => unknown) | null = null
  transaction: FakeUpgradeTransaction | null = null
}

class FakeStoreSchema {
  readonly keyPath: string
  readonly indexes = new Map<string, {
    keyPath: string
    unique: boolean
  }>()

  constructor(keyPath: string) {
    this.keyPath = keyPath
  }
}

class FakeIndexedDB
{
  readonly records = new Map<string, unknown>()
  readonly openCalls: Array<{ name: string, version: number | undefined }> = []
  readonly database: FakeDatabase
  version = 0
  openFailure: 'blocked' | 'error' | null = null
  requestFailure: DOMException | null = null
  transactionOutcomes: FakeTransactionOutcome[] = []
  lastTransaction: FakeTransaction | null = null
  storeCreateCount = 0
  indexCreateCount = 0

  constructor(options: Readonly<{
    existingStore?: boolean
    existingIndex?: boolean
    existingIndexKeyPath?: string
    existingIndexUnique?: boolean
  }> = {}) {
    this.database = new FakeDatabase(this)
    if (options.existingStore) {
      this.database.schema = new FakeStoreSchema('id')
      if (options.existingIndex) {
        this.database.schema.indexes.set('updatedAt', {
          keyPath: options.existingIndexKeyPath ?? 'updatedAt',
          unique: options.existingIndexUnique ?? false,
        })
      }
    }
  }

  asFactory(): IDBFactory {
    return this as unknown as IDBFactory
  }

  open(name: string, version?: number): IDBOpenDBRequest {
    this.openCalls.push({ name, version })
    const request = new FakeOpenRequest()

    queueMicrotask(() => {
      if (this.openFailure === 'blocked') {
        request.onblocked?.(new Event('blocked'))
        return
      }
      if (this.openFailure === 'error') {
        request.error = new DOMException('fake open failed', 'UnknownError')
        request.onerror?.(new Event('error'))
        return
      }

      request.result = this.database as unknown as IDBDatabase
      const requestedVersion = version ?? this.version
      if (requestedVersion > this.version) {
        request.transaction = new FakeUpgradeTransaction(this)
        request.onupgradeneeded?.(new Event('upgradeneeded'))
        this.version = requestedVersion
      }
      request.onsuccess?.(new Event('success'))
    })

    return request as unknown as IDBOpenDBRequest
  }

  takeTransactionOutcome(): FakeTransactionOutcome {
    return this.transactionOutcomes.shift() ?? 'complete'
  }

  setRecord(key: string, value: unknown): void {
    this.records.set(key, structuredClone(value))
  }
}

class FakeDatabase {
  readonly factory: FakeIndexedDB
  schema: FakeStoreSchema | null = null
  closeCount = 0
  onversionchange: ((event: Event) => unknown) | null = null

  constructor(factory: FakeIndexedDB) {
    this.factory = factory
  }

  get objectStoreNames(): DOMStringList {
    return new FakeNameList(
      this.schema === null ? [] : ['drafts'],
    ) as unknown as DOMStringList
  }

  createObjectStore(
    name: string,
    options?: IDBObjectStoreParameters,
  ): IDBObjectStore {
    assert.equal(name, 'drafts')
    this.factory.storeCreateCount += 1
    this.schema = new FakeStoreSchema(String(options?.keyPath))
    return new FakeObjectStoreHandle(
      this.factory,
      this.schema,
      null,
    ) as unknown as IDBObjectStore
  }

  transaction(
    storeName: string,
    mode?: IDBTransactionMode,
  ): IDBTransaction {
    assert.equal(storeName, 'drafts')
    assert.ok(mode === 'readonly' || mode === 'readwrite')
    assert.ok(this.schema)
    const transaction = new FakeTransaction(
      this.factory,
      this.schema,
    )
    this.factory.lastTransaction = transaction
    return transaction as unknown as IDBTransaction
  }

  close(): void {
    this.closeCount += 1
  }
}

class FakeUpgradeTransaction {
  readonly factory: FakeIndexedDB

  constructor(factory: FakeIndexedDB) {
    this.factory = factory
  }

  objectStore(name: string): IDBObjectStore {
    assert.equal(name, 'drafts')
    assert.ok(this.factory.database.schema)
    return new FakeObjectStoreHandle(
      this.factory,
      this.factory.database.schema,
      null,
    ) as unknown as IDBObjectStore
  }

  abort(): void {
    // The production upgrade path only calls this when its handler throws.
  }
}

class FakeTransaction {
  readonly factory: FakeIndexedDB
  readonly schema: FakeStoreSchema
  readonly outcome: FakeTransactionOutcome
  readonly commits: Array<() => void> = []
  error: DOMException | null = null
  oncomplete: ((event: Event) => unknown) | null = null
  onerror: ((event: Event) => unknown) | null = null
  onabort: ((event: Event) => unknown) | null = null
  finished = false

  constructor(factory: FakeIndexedDB, schema: FakeStoreSchema) {
    this.factory = factory
    this.schema = schema
    this.outcome = factory.takeTransactionOutcome()
  }

  objectStore(name: string): IDBObjectStore {
    assert.equal(name, 'drafts')
    return new FakeObjectStoreHandle(
      this.factory,
      this.schema,
      this,
    ) as unknown as IDBObjectStore
  }

  request<T>(
    read: () => T,
    commit?: () => void,
  ): IDBRequest<T> {
    const request = new FakeRequest<T>()
    const requestFailure = this.factory.requestFailure
    this.factory.requestFailure = null

    queueMicrotask(() => {
      if (requestFailure !== null) {
        request.error = requestFailure
        request.onerror?.(new Event('error'))
        queueMicrotask(() => this.abortWith(requestFailure))
        return
      }

      request.result = read()
      if (commit !== undefined) {
        this.commits.push(commit)
      }
      request.onsuccess?.(new Event('success'))
      queueMicrotask(() => {
        if (this.outcome === 'complete') {
          this.complete()
        } else if (this.outcome === 'abort') {
          this.abortWith(new DOMException(
            'fake transaction aborted',
            'AbortError',
          ))
        } else if (this.outcome === 'error') {
          this.fail(new DOMException(
            'fake transaction failed',
            'UnknownError',
          ))
        }
      })
    })

    return request as unknown as IDBRequest<T>
  }

  complete(): void {
    if (this.finished) {
      return
    }
    this.finished = true
    this.commits.forEach((commit) => commit())
    this.oncomplete?.(new Event('complete'))
  }

  abort(): void {
    this.abortWith(new DOMException(
      'fake transaction aborted',
      'AbortError',
    ))
  }

  abortWith(error: DOMException): void {
    if (this.finished) {
      return
    }
    this.finished = true
    this.error = error
    this.onabort?.(new Event('abort'))
  }

  fail(error: DOMException): void {
    if (this.finished) {
      return
    }
    this.finished = true
    this.error = error
    this.onerror?.(new Event('error'))
  }
}

class FakeObjectStoreHandle {
  readonly factory: FakeIndexedDB
  readonly schema: FakeStoreSchema
  readonly transaction: FakeTransaction | null

  constructor(
    factory: FakeIndexedDB,
    schema: FakeStoreSchema,
    transaction: FakeTransaction | null,
  ) {
    this.factory = factory
    this.schema = schema
    this.transaction = transaction
  }

  get keyPath(): string {
    return this.schema.keyPath
  }

  get indexNames(): DOMStringList {
    return new FakeNameList(
      this.schema.indexes.keys(),
    ) as unknown as DOMStringList
  }

  createIndex(
    name: string,
    keyPath: string | string[],
    options?: IDBIndexParameters,
  ): IDBIndex {
    this.factory.indexCreateCount += 1
    this.schema.indexes.set(name, {
      keyPath: String(keyPath),
      unique: options?.unique ?? false,
    })
    return this.index(name)
  }

  index(name: string): IDBIndex {
    const schema = this.schema.indexes.get(name)
    if (schema === undefined) {
      throw new DOMException('fake index is missing', 'NotFoundError')
    }
    return {
      keyPath: schema.keyPath,
      unique: schema.unique,
    } as IDBIndex
  }

  get(key: IDBValidKey): IDBRequest<unknown> {
    const transaction = this.requiredTransaction()
    return transaction.request(() => {
      const value = this.factory.records.get(String(key))
      return value === undefined ? undefined : structuredClone(value)
    })
  }

  getAll(): IDBRequest<unknown[]> {
    const transaction = this.requiredTransaction()
    return transaction.request(
      () => Array.from(
        this.factory.records.values(),
        (value) => structuredClone(value),
      ),
    )
  }

  put(value: unknown): IDBRequest<IDBValidKey> {
    const transaction = this.requiredTransaction()
    const clone = structuredClone(value) as { id: string }
    return transaction.request<IDBValidKey>(
      () => clone.id,
      () => this.factory.records.set(clone.id, clone),
    )
  }

  delete(key: IDBValidKey): IDBRequest<undefined> {
    const transaction = this.requiredTransaction()
    return transaction.request(
      () => undefined,
      () => {
        this.factory.records.delete(String(key))
      },
    )
  }

  requiredTransaction(): FakeTransaction {
    assert.ok(this.transaction)
    return this.transaction
  }
}

function configSnapshot(): TradingLabConfigSnapshot {
  return {
    modelVersion: 'model-1.0',
    symbolConfigVersion: '001',
    codeVersion: 'build-1.0',
    executionPolicy: {
      matchingMode: 'SIMPLE',
      makerFeeRate: '0.001',
      takerFeeRate: '0.002',
      liquidationFeeRate: '0.003',
      slippageRate: '0',
      maxFillQuantityPerTick: '100',
    },
    instruments: [{
      symbol: 'BTCUSDT',
      productType: 'CRYPTO_SPOT',
      baseAsset: 'BTC',
      quoteAsset: 'USDT',
      tickSize: '0.01',
      stepSize: '0.001',
      pricePrecision: 2,
      quantityPrecision: 3,
      minQty: '0.001',
      maxQty: '100',
      minNotional: '10',
      maxNotional: '1000000',
      initialMarginRate: '0.01',
      maintenanceMarginRate: '0.005',
      liquidationFeeRate: '0.002',
      fixedFundingRate: '0.0001',
      fixedFundingIntervalMinutes: 480,
      markPriceSource: 'quote_mid',
      contractSize: '1',
      maxLeverage: 1,
      defaultLeverage: 1,
      marginAsset: 'USDT',
      settlementAsset: 'USDT',
      riskTier: 'TIER_1',
    }],
  }
}

function marketPath(): MarketPathDefinition {
  return {
    virtualStart: '2026-01-01T00:00:00.000Z',
    realistic: false,
    instruments: [{
      mode: 'SIMPLE',
      productType: 'CRYPTO_SPOT',
      symbol: 'BTCUSDT',
      seed: 'path-seed',
      last: {
        start: '60000',
        segments: [{
          target: '60100',
          durationSeconds: 300,
          offsetRangeSteps: 0,
          volatilitySteps: 0,
          maxStepPerSecond: 100,
        }],
      },
      spreadSteps: 2,
      indexOffsetSteps: 0,
      basisSteps: 0,
    }],
  }
}

async function scenario(
  id = 'draft-a',
  name = 'Draft A',
): Promise<TradingLabScenario> {
  const snapshot = configSnapshot()
  return {
    id,
    name,
    description: '',
    negativeMode: false,
    seed: 'seed',
    modelVersion: snapshot.modelVersion,
    configSnapshot: snapshot,
    configSnapshotHash: await hashScenarioConfig(snapshot),
    executionPolicy: { ...snapshot.executionPolicy },
    marketPath: marketPath(),
    initialBalances: { USDT: '100000' },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 1,
    },
    symbols: [{
      symbol: 'BTCUSDT',
      productType: 'CRYPTO_SPOT',
    }],
    timeline: [],
  }
}

function storedRecord(
  value: TradingLabScenario,
  updatedAt = '2026-07-25T01:02:03.004Z',
): Record<string, unknown> {
  return {
    schemaVersion: 1,
    id: value.id,
    name: value.name,
    updatedAt,
    scenario: value,
  }
}

async function waitForTransaction(factory: FakeIndexedDB): Promise<FakeTransaction> {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (factory.lastTransaction !== null) {
      return factory.lastTransaction
    }
    await new Promise<void>((resolve) => setTimeout(resolve, 0))
  }
  throw new Error('fake transaction was not created')
}

test('opens and upgrades the exact native IndexedDB schema', async () => {
  const factory = new FakeIndexedDB()
  const store = createTradingLabDraftStore({
    indexedDB: factory.asFactory(),
  })

  assert.deepEqual(await store.list(), [])
  assert.deepEqual(factory.openCalls, [{
    name: 'fx-platform-trading-lab',
    version: 1,
  }])
  assert.equal(factory.storeCreateCount, 1)
  assert.equal(factory.database.schema?.keyPath, 'id')
  assert.deepEqual(
    factory.database.schema?.indexes.get('updatedAt'),
    { keyPath: 'updatedAt', unique: false },
  )

  const closesBeforeVersionChange = factory.database.closeCount
  factory.database.onversionchange?.(new Event('versionchange'))
  assert.equal(factory.database.closeCount, closesBeforeVersionChange + 1)

  const existingFactory = new FakeIndexedDB({ existingStore: true })
  const existingStore = createTradingLabDraftStore({
    indexedDB: existingFactory.asFactory(),
  })
  assert.deepEqual(await existingStore.list(), [])
  assert.equal(existingFactory.storeCreateCount, 0)
  assert.equal(existingFactory.indexCreateCount, 1)
  assert.deepEqual(
    existingFactory.database.schema?.indexes.get('updatedAt'),
    { keyPath: 'updatedAt', unique: false },
  )

  for (const options of [
    {
      existingIndexKeyPath: 'wrongField',
      existingIndexUnique: false,
    },
    {
      existingIndexKeyPath: 'updatedAt',
      existingIndexUnique: true,
    },
  ]) {
    const invalidFactory = new FakeIndexedDB({
      existingStore: true,
      existingIndex: true,
      ...options,
    })
    invalidFactory.version = 1
    const invalidStore = createTradingLabDraftStore({
      indexedDB: invalidFactory.asFactory(),
    })
    await assert.rejects(invalidStore.list(), /index|索引/i)
  }
})

test('put/get is detached, preserves an empty timeline, and waits for commit', async () => {
  const factory = new FakeIndexedDB()
  factory.transactionOutcomes.push('manual')
  const store = createTradingLabDraftStore({
    indexedDB: factory.asFactory(),
    now: () => '2026-07-25T01:02:03.004Z',
  })
  const input = await scenario()
  let settled = false
  const putting = store.put(input).then(() => {
    settled = true
  })

  const transaction = await waitForTransaction(factory)
  await new Promise<void>((resolve) => setTimeout(resolve, 0))
  assert.equal(settled, false)
  assert.equal(factory.records.size, 0)
  transaction.complete()
  await putting
  assert.equal(settled, true)

  input.name = 'mutated after put'
  input.configSnapshot.instruments[0].tickSize = '99'
  const first = await store.get('draft-a')
  assert.ok(first)
  assert.equal(first.name, 'Draft A')
  assert.equal(first.configSnapshot.instruments[0].tickSize, '0.01')
  assert.deepEqual(first.timeline, [])
  assert.equal(
    Object.hasOwn(first as unknown as object, 'updatedAt'),
    false,
  )

  first.name = 'mutated after get'
  const second = await store.get('draft-a')
  assert.ok(second)
  assert.equal(second.name, 'Draft A')
  assert.notStrictEqual(first, second)
  assert.notStrictEqual(first.configSnapshot, second.configSnapshot)

  const wrapper = factory.records.get('draft-a') as {
    updatedAt: string
    scenario: object
  }
  assert.equal(wrapper.updatedAt, '2026-07-25T01:02:03.004Z')
  assert.equal(Object.hasOwn(wrapper.scenario, 'updatedAt'), false)
})

test('list sorts updatedAt descending then id ascending and delete is idempotent', async () => {
  const factory = new FakeIndexedDB()
  const timestamps = [
    '2026-07-25T02:00:00.000Z',
    '2026-07-25T02:00:00.000Z',
    '2026-07-25T01:00:00.000Z',
  ]
  const store = createTradingLabDraftStore({
    indexedDB: factory.asFactory(),
    now: () => {
      const value = timestamps.shift()
      assert.ok(value)
      return value
    },
  })

  await store.put(await scenario('draft-b', 'Draft B'))
  await store.put(await scenario('draft-a', 'Draft A'))
  await store.put(await scenario('draft-c', 'Draft C'))

  assert.deepEqual(await store.list(), [
    {
      id: 'draft-a',
      name: 'Draft A',
      updatedAt: '2026-07-25T02:00:00.000Z',
    },
    {
      id: 'draft-b',
      name: 'Draft B',
      updatedAt: '2026-07-25T02:00:00.000Z',
    },
    {
      id: 'draft-c',
      name: 'Draft C',
      updatedAt: '2026-07-25T01:00:00.000Z',
    },
  ])

  await store.delete('draft-a')
  await store.delete('draft-a')
  assert.equal(await store.get('draft-a'), null)
  assert.deepEqual(
    (await store.list()).map((summary) => summary.id),
    ['draft-b', 'draft-c'],
  )

  const extendedYearFactory = new FakeIndexedDB()
  const extendedYearTimestamps = [
    '9999-12-31T23:59:59.999Z',
    '+010000-01-01T00:00:00.000Z',
  ]
  const extendedYearStore = createTradingLabDraftStore({
    indexedDB: extendedYearFactory.asFactory(),
    now: () => {
      const value = extendedYearTimestamps.shift()
      assert.ok(value)
      return value
    },
  })
  await extendedYearStore.put(await scenario('year-9999', 'Year 9999'))
  await extendedYearStore.put(await scenario('year-10000', 'Year 10000'))
  assert.deepEqual(
    (await extendedYearStore.list()).map((summary) => summary.id),
    ['year-10000', 'year-9999'],
  )
})

test('rejects invalid operation ids and clock timestamps before persistence', async () => {
  const factory = new FakeIndexedDB()
  const store = createTradingLabDraftStore({
    indexedDB: factory.asFactory(),
    now: () => '2026-07-25 01:02:03',
  })

  await assert.rejects(store.get('   '), /id/i)
  await assert.rejects(store.delete('bad\u0000id'), /id/i)
  await assert.rejects(
    store.get(42 as unknown as string),
    /id/i,
  )
  await assert.rejects(store.put(await scenario()), /updatedAt|timestamp/i)
  assert.equal(factory.records.size, 0)
  assert.equal(factory.openCalls.length, 0)
})

test('rejects every present corrupt record and never omits corrupt list rows', async (t) => {
  const validScenario = await scenario()
  const cases: Array<{
    name: string
    value: unknown
  }> = [
    {
      name: 'non-object wrapper',
      value: null,
    },
    {
      name: 'wrong schema version',
      value: {
        ...storedRecord(validScenario),
        schemaVersion: 2,
      },
    },
    {
      name: 'non-canonical timestamp',
      value: {
        ...storedRecord(validScenario),
        updatedAt: '2026-07-25T09:02:03.004+08:00',
      },
    },
    {
      name: 'invalid id',
      value: {
        ...storedRecord(validScenario),
        id: '\u0000',
        scenario: {
          ...validScenario,
          id: '\u0000',
        },
      },
    },
    {
      name: 'mismatched scenario id',
      value: {
        ...storedRecord(validScenario),
        scenario: {
          ...validScenario,
          id: 'other-id',
        },
      },
    },
    {
      name: 'mismatched scenario name',
      value: {
        ...storedRecord(validScenario),
        name: 'other name',
      },
    },
    {
      name: 'structurally corrupt instrument',
      value: {
        ...storedRecord(validScenario),
        scenario: {
          ...validScenario,
          configSnapshot: {
            ...validScenario.configSnapshot,
            instruments: [null],
          },
        },
      },
    },
    {
      name: 'config hash mismatch',
      value: {
        ...storedRecord(validScenario),
        scenario: {
          ...validScenario,
          configSnapshotHash: 'f'.repeat(64),
        },
      },
    },
  ]

  for (const corruptCase of cases) {
    await t.test(corruptCase.name, async () => {
      const factory = new FakeIndexedDB()
      factory.setRecord('draft-a', corruptCase.value)
      const store = createTradingLabDraftStore({
        indexedDB: factory.asFactory(),
      })
      await assert.rejects(store.get('draft-a'))
    })
  }

  await t.test('list rejects instead of filtering a corrupt row', async () => {
    const factory = new FakeIndexedDB()
    factory.setRecord(
      'valid',
      storedRecord(await scenario('valid', 'Valid')),
    )
    factory.setRecord('corrupt', null)
    const store = createTradingLabDraftStore({
      indexedDB: factory.asFactory(),
    })
    await assert.rejects(store.list())
  })
})

test('reports blocked and failed database opens', async (t) => {
  await t.test('blocked', async () => {
    const factory = new FakeIndexedDB()
    factory.openFailure = 'blocked'
    const store = createTradingLabDraftStore({
      indexedDB: factory.asFactory(),
    })
    await assert.rejects(store.list(), /blocked|阻塞/i)
  })

  await t.test('error', async () => {
    const factory = new FakeIndexedDB()
    factory.openFailure = 'error'
    const store = createTradingLabDraftStore({
      indexedDB: factory.asFactory(),
    })
    await assert.rejects(store.list(), /fake open failed/)
  })
})

test('reports request failure, transaction error, and transaction abort', async (t) => {
  await t.test('request failure', async () => {
    const factory = new FakeIndexedDB()
    factory.requestFailure = new DOMException(
      'fake request failed',
      'UnknownError',
    )
    const store = createTradingLabDraftStore({
      indexedDB: factory.asFactory(),
    })
    await assert.rejects(store.get('draft-a'), /fake request failed/)
  })

  for (const outcome of ['error', 'abort'] as const) {
    await t.test(`transaction ${outcome}`, async () => {
      const factory = new FakeIndexedDB()
      factory.transactionOutcomes.push(outcome)
      const store = createTradingLabDraftStore({
        indexedDB: factory.asFactory(),
      })
      await assert.rejects(
        store.list(),
        outcome === 'abort' ? /aborted|abort/i : /failed/i,
      )
    })
  }
})

test('requires native IndexedDB instead of falling back', () => {
  assert.throws(
    () => createTradingLabDraftStore({
      indexedDB: undefined,
    }),
    /IndexedDB/i,
  )
})
