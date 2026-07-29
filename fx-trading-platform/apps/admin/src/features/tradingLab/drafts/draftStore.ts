import type { TradingLabScenario } from '../model/types.ts'
import { normalizeTradingLabScenarioDocument } from './scenarioDocument.ts'

const DATABASE_NAME = 'fx-platform-trading-lab'
const DATABASE_VERSION = 1
const STORE_NAME = 'drafts'
const UPDATED_AT_INDEX = 'updatedAt'
const STORED_RECORD_KEYS = [
  'id',
  'name',
  'scenario',
  'schemaVersion',
  'updatedAt',
]

export type TradingLabDraftSummary = Readonly<{
  id: string
  name: string
  updatedAt: string
}>

export interface TradingLabDraftStore {
  list(): Promise<TradingLabDraftSummary[]>
  get(id: string): Promise<TradingLabScenario | null>
  put(scenario: TradingLabScenario): Promise<void>
  delete(id: string): Promise<void>
}

type StoredTradingLabDraft = Readonly<{
  schemaVersion: 1
  id: string
  name: string
  updatedAt: string
  scenario: TradingLabScenario
}>

function errorOrDefault(
  error: unknown,
  defaultMessage: string,
): Error {
  return error instanceof Error ? error : new Error(defaultMessage)
}

function rejectInvalidId(): never {
  throw new TypeError('Trading Lab draft id is invalid')
}

function assertDraftId(value: unknown): asserts value is string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    rejectInvalidId()
  }

  for (let index = 0; index < value.length; index += 1) {
    const codeUnit = value.charCodeAt(index)
    if (codeUnit < 0x20 || codeUnit === 0x7f) {
      rejectInvalidId()
    }
    if (codeUnit >= 0xd800 && codeUnit <= 0xdbff) {
      const next = value.charCodeAt(index + 1)
      if (!(next >= 0xdc00 && next <= 0xdfff)) {
        rejectInvalidId()
      }
      index += 1
    } else if (codeUnit >= 0xdc00 && codeUnit <= 0xdfff) {
      rejectInvalidId()
    }
  }
}

function canonicalTimestamp(value: unknown): string {
  if (typeof value !== 'string') {
    throw new TypeError('Trading Lab draft updatedAt must be a timestamp')
  }
  const parsed = new Date(value)
  if (
    !Number.isFinite(parsed.getTime())
    || parsed.toISOString() !== value
  ) {
    throw new TypeError(
      'Trading Lab draft updatedAt must be a canonical UTC timestamp',
    )
  }
  return value
}

function isStrictStoredRecord(
  value: unknown,
): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return false
  }
  const prototype = Object.getPrototypeOf(value)
  if (prototype !== Object.prototype && prototype !== null) {
    return false
  }
  const keys = Reflect.ownKeys(value)
  if (keys.some((key) => typeof key === 'symbol')) {
    return false
  }
  const sortedKeys = (keys as string[]).sort()
  return sortedKeys.length === STORED_RECORD_KEYS.length
    && sortedKeys.every((key, index) => key === STORED_RECORD_KEYS[index])
}

async function validateStoredRecord(
  value: unknown,
): Promise<StoredTradingLabDraft> {
  if (!isStrictStoredRecord(value) || value.schemaVersion !== 1) {
    throw new TypeError('Trading Lab draft record wrapper is corrupt')
  }

  assertDraftId(value.id)
  if (typeof value.name !== 'string') {
    throw new TypeError('Trading Lab draft record name is corrupt')
  }
  const updatedAt = canonicalTimestamp(value.updatedAt)
  const scenario = await normalizeTradingLabScenarioDocument(value.scenario)
  if (scenario.id !== value.id) {
    throw new TypeError('Trading Lab draft record id does not match scenario')
  }
  if (scenario.name !== value.name) {
    throw new TypeError('Trading Lab draft record name does not match scenario')
  }

  return {
    schemaVersion: 1,
    id: value.id,
    name: value.name,
    updatedAt,
    scenario,
  }
}

function assertDraftStoreSchema(objectStore: IDBObjectStore): void {
  if (objectStore.keyPath !== 'id') {
    throw new Error('Trading Lab draft store keyPath is invalid')
  }
  if (!objectStore.indexNames.contains(UPDATED_AT_INDEX)) {
    throw new Error('Trading Lab draft updatedAt index is missing')
  }
  const index = objectStore.index(UPDATED_AT_INDEX)
  if (index.keyPath !== UPDATED_AT_INDEX || index.unique) {
    throw new Error('Trading Lab draft updatedAt index is invalid')
  }
}

function openDatabase(factory: IDBFactory): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    let settled = false
    const request = factory.open(DATABASE_NAME, DATABASE_VERSION)

    const rejectOnce = (error: unknown, defaultMessage: string): void => {
      if (settled) {
        return
      }
      settled = true
      reject(errorOrDefault(error, defaultMessage))
    }

    request.onupgradeneeded = () => {
      try {
        const database = request.result
        const objectStore = database.objectStoreNames.contains(STORE_NAME)
          ? request.transaction?.objectStore(STORE_NAME)
          : database.createObjectStore(STORE_NAME, { keyPath: 'id' })
        if (objectStore === undefined) {
          throw new Error('Trading Lab draft upgrade transaction is missing')
        }
        if (!objectStore.indexNames.contains(UPDATED_AT_INDEX)) {
          objectStore.createIndex(
            UPDATED_AT_INDEX,
            UPDATED_AT_INDEX,
            { unique: false },
          )
        }
        assertDraftStoreSchema(objectStore)
      } catch (error) {
        try {
          request.transaction?.abort()
        } catch {
          // The original upgrade failure is the actionable error.
        }
        rejectOnce(error, 'Failed to upgrade Trading Lab draft database')
      }
    }

    request.onblocked = () => {
      rejectOnce(
        new Error('Trading Lab IndexedDB open was blocked'),
        'Trading Lab IndexedDB open was blocked',
      )
    }
    request.onerror = () => {
      rejectOnce(
        request.error,
        'Failed to open Trading Lab IndexedDB',
      )
    }
    request.onsuccess = () => {
      const database = request.result
      if (settled) {
        database.close()
        return
      }
      settled = true
      database.onversionchange = () => {
        database.close()
      }
      resolve(database)
    }
  })
}

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(errorOrDefault(
      request.error,
      'Trading Lab IndexedDB request failed',
    ))
  })
}

function transactionCompletion(
  transaction: IDBTransaction,
): Promise<void> {
  return new Promise((resolve, reject) => {
    let settled = false
    const rejectOnce = (defaultMessage: string): void => {
      if (settled) {
        return
      }
      settled = true
      reject(errorOrDefault(transaction.error, defaultMessage))
    }

    transaction.oncomplete = () => {
      if (settled) {
        return
      }
      settled = true
      resolve()
    }
    transaction.onerror = () => {
      rejectOnce('Trading Lab IndexedDB transaction failed')
    }
    transaction.onabort = () => {
      rejectOnce('Trading Lab IndexedDB transaction aborted')
    }
  })
}

async function runRequest<T>(
  factory: IDBFactory,
  mode: IDBTransactionMode,
  createRequest: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const database = await openDatabase(factory)
  try {
    const transaction = database.transaction(STORE_NAME, mode)
    const completion = transactionCompletion(transaction)
    let request: IDBRequest<T>
    try {
      const objectStore = transaction.objectStore(STORE_NAME)
      assertDraftStoreSchema(objectStore)
      request = createRequest(objectStore)
    } catch (error) {
      try {
        transaction.abort()
      } catch {
        // The synchronous request failure remains the primary error.
      }
      await completion.catch(() => undefined)
      throw error
    }
    const [result] = await Promise.all([
      requestResult(request),
      completion,
    ])
    return result
  } finally {
    database.close()
  }
}

export function createTradingLabDraftStore(
  options: Readonly<{
    indexedDB?: IDBFactory
    now?: () => string
  }> = {},
): TradingLabDraftStore {
  const factory = options.indexedDB ?? globalThis.indexedDB
  if (factory === undefined || typeof factory.open !== 'function') {
    throw new Error('Native IndexedDB is required for Trading Lab drafts')
  }
  const now = options.now ?? (() => new Date().toISOString())

  return {
    async list(): Promise<TradingLabDraftSummary[]> {
      const values = await runRequest(
        factory,
        'readonly',
        (store) => store.getAll() as IDBRequest<unknown[]>,
      )
      const records: StoredTradingLabDraft[] = []
      for (const value of values) {
        records.push(await validateStoredRecord(value))
      }
      records.sort((left, right) => {
        const newestFirst = Date.parse(right.updatedAt)
          - Date.parse(left.updatedAt)
        return newestFirst !== 0
          ? newestFirst
          : left.id < right.id
            ? -1
            : left.id > right.id
              ? 1
              : 0
      })
      return records.map(({ id, name, updatedAt }) => ({
        id,
        name,
        updatedAt,
      }))
    },

    async get(id: string): Promise<TradingLabScenario | null> {
      assertDraftId(id)
      const value = await runRequest(
        factory,
        'readonly',
        (store) => store.get(id) as IDBRequest<unknown>,
      )
      return value === undefined
        ? null
        : (await validateStoredRecord(value)).scenario
    },

    async put(scenario: TradingLabScenario): Promise<void> {
      const normalized = await normalizeTradingLabScenarioDocument(scenario)
      assertDraftId(normalized.id)
      if (typeof normalized.name !== 'string') {
        throw new TypeError('Trading Lab draft scenario name is corrupt')
      }
      const updatedAt = canonicalTimestamp(now())
      const record: StoredTradingLabDraft = {
        schemaVersion: 1,
        id: normalized.id,
        name: normalized.name,
        updatedAt,
        scenario: normalized,
      }
      await runRequest(
        factory,
        'readwrite',
        (store) => store.put(record),
      )
    },

    async delete(id: string): Promise<void> {
      assertDraftId(id)
      await runRequest(
        factory,
        'readwrite',
        (store) => store.delete(id),
      )
    },
  }
}
