import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { registerHooks } from 'node:module'
import { afterEach, beforeEach, describe, it } from 'node:test'

const fakeStompUrl =
  'data:text/javascript,' +
  encodeURIComponent(`
    export class Client {
      constructor(config) {
        this.config = config
        this.active = false
        this.deactivated = false
        this.subscriptions = []
        globalThis.__marketStreamFakeClients.push(this)
      }

      activate() {
        this.active = true
      }

      deactivate() {
        this.deactivated = true
        this.active = false
        return Promise.resolve()
      }

      subscribe(topic, callback) {
        const subscription = {
          topic,
          callback,
          unsubscribed: false,
          unsubscribe() {
            subscription.unsubscribed = true
          }
        }
        this.subscriptions.push(subscription)
        return subscription
      }
    }
  `)

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === '@stomp/stompjs') {
      return { shortCircuit: true, url: fakeStompUrl }
    }

    return nextResolve(specifier, context)
  }
})

beforeEach(() => {
  globalThis.__marketStreamFakeClients = []
  globalThis.window = { location: { protocol: 'http:', host: 'localhost' } }
})

afterEach(() => {
  delete globalThis.window
  delete globalThis.__marketStreamFakeClients
})

async function importMarketStream() {
  return import(`./marketStream.ts?test=${Date.now()}-${Math.random()}`)
}

describe('market stream shared client', () => {
  it('derives the websocket broker URL from the configured API base URL', async () => {
    const { resolveMarketStreamBrokerUrl } = await importMarketStream()

    assert.equal(
      resolveMarketStreamBrokerUrl('http://127.0.0.1:18088', { protocol: 'http:', host: '127.0.0.1:5193' }),
      'ws://127.0.0.1:18088/ws'
    )
    assert.equal(
      resolveMarketStreamBrokerUrl('https://api.example.test', { protocol: 'https:', host: 'app.example.test' }),
      'wss://api.example.test/ws'
    )
  })

  it('shares one /ws STOMP subscription for two BTCUSDT quote handlers on the same page', async () => {
    const { subscribeQuote } = await importMarketStream()
    const received = []

    subscribeQuote('BTCUSDT', 'token-a', (quote) => received.push(['first', quote]))
    subscribeQuote('BTCUSDT', 'token-a', (quote) => received.push(['second', quote]))

    const [client] = globalThis.__marketStreamFakeClients
    assert.equal(globalThis.__marketStreamFakeClients.length, 1)

    client.config.onConnect()
    assert.equal(client.subscriptions.length, 1)
    assert.equal(client.config.brokerURL, 'ws://localhost/ws')
    assert.equal(client.subscriptions[0].topic, '/topic/market/quotes/BTCUSDT')

    client.subscriptions[0].callback({ body: '{"symbol":"BTCUSDT","bid":65000}' })
    assert.deepEqual(received, [
      ['first', { symbol: 'BTCUSDT', bid: 65000 }],
      ['second', { symbol: 'BTCUSDT', bid: 65000 }]
    ])

    client.config.onConnect()
    assert.equal(client.subscriptions.length, 1)
  })

  it('normalizes market topic symbols before subscribing', async () => {
    const { subscribeOrderBook, subscribeQuote, subscribeRecentTrades } = await importMarketStream()

    subscribeQuote('btc-usdt', null, () => {})
    subscribeOrderBook('btc/usdt', null, () => {})
    subscribeRecentTrades('btc_usdt', null, () => {})

    const [client] = globalThis.__marketStreamFakeClients
    client.config.onConnect()

    assert.deepEqual(
      client.subscriptions.map((subscription) => subscription.topic),
      [
        '/topic/market/quotes/BTCUSDT',
        '/topic/market/order-book/BTCUSDT',
        '/topic/market/trades/BTCUSDT'
      ]
    )
  })

  it('does not connect directly to Binance websocket hosts from the frontend', async () => {
    const source = await readFile(new URL('./marketStream.ts', import.meta.url), 'utf8')

    assert.doesNotMatch(source, /stream\.binance\.com|data-stream\.binance\.vision/)
  })

  it('isolates malformed JSON and throwing handlers from valid delivery', async () => {
    const { subscribeQuote } = await importMarketStream()
    const warnings = []
    const originalWarn = console.warn
    console.warn = (...args) => warnings.push(args)

    try {
      const received = []
      subscribeQuote('EURUSD', 'token-a', () => {
        throw new Error('handler failed')
      })
      subscribeQuote('EURUSD', 'token-a', (quote) => received.push(quote))

      const [client] = globalThis.__marketStreamFakeClients
      client.config.onConnect()
      const [subscription] = client.subscriptions

      assert.doesNotThrow(() => subscription.callback({ body: '{bad json' }))
      assert.doesNotThrow(() => subscription.callback({ body: '{"symbol":"EURUSD","bid":1.2}' }))
      assert.deepEqual(received, [{ symbol: 'EURUSD', bid: 1.2 }])
      assert.equal(warnings.length, 2)
    } finally {
      console.warn = originalWarn
    }
  })

  it('unsubscribes the topic and deactivates the idle client after the last handler is removed', async () => {
    const { subscribeQuote } = await importMarketStream()
    const unsubscribeFirst = subscribeQuote('EURUSD', 'token-a', () => {})
    const unsubscribeSecond = subscribeQuote('EURUSD', 'token-a', () => {})
    const [client] = globalThis.__marketStreamFakeClients

    client.config.onConnect()
    const [subscription] = client.subscriptions

    unsubscribeFirst()
    assert.equal(subscription.unsubscribed, false)
    assert.equal(client.deactivated, false)

    unsubscribeSecond()
    assert.equal(subscription.unsubscribed, true)
    assert.equal(client.deactivated, true)
  })

  it('isolates authenticated sessions when the token changes', async () => {
    const { subscribeQuote } = await importMarketStream()

    const unsubscribeFirst = subscribeQuote('EURUSD', 'token-a', () => {})
    const firstClient = globalThis.__marketStreamFakeClients[0]

    const unsubscribeSecond = subscribeQuote('EURUSD', 'token-b', () => {})
    const secondClient = globalThis.__marketStreamFakeClients[1]

    assert.equal(globalThis.__marketStreamFakeClients.length, 2)
    assert.equal(firstClient.deactivated, false)
    assert.equal(secondClient.deactivated, false)

    unsubscribeFirst()
    assert.equal(firstClient.deactivated, true)
    assert.equal(secondClient.deactivated, false)

    unsubscribeSecond()
    assert.equal(secondClient.deactivated, true)
  })

  it('subscribes to account trading-session events with the authenticated stream token', async () => {
    const { subscribeTradingSessionEvents } = await importMarketStream()
    const received = []

    subscribeTradingSessionEvents('token-a', (event) => received.push(event))

    const [client] = globalThis.__marketStreamFakeClients
    client.config.onConnect()

    assert.equal(client.config.connectHeaders.Authorization, 'Bearer token-a')
    assert.equal(client.subscriptions[0].topic, '/user/queue/trading-events')

    client.subscriptions[0].callback({ body: '{"type":"ORDER_EVENT","status":"FILLED"}' })
    assert.deepEqual(received, [{ type: 'ORDER_EVENT', status: 'FILLED' }])
  })

  it('preserves the canonical Perpetual suffix and exposes source-change events', async () => {
    const { subscribeQuote, subscribeMarketSourceChanges } = await importMarketStream()
    const sourceChanges = []

    subscribeQuote('btc_usdt-perp', null, () => {})
    subscribeMarketSourceChanges('btc/usdt_perp', null, (event) => sourceChanges.push(event))

    const [client] = globalThis.__marketStreamFakeClients
    client.config.onConnect()

    assert.deepEqual(client.subscriptions.map((subscription) => subscription.topic), [
      '/topic/market/quotes/BTCUSDT-PERP',
      '/topic/market/source-changes/BTCUSDT-PERP'
    ])
    client.subscriptions[1].callback({
      body: '{"type":"MARKET_SOURCE_CHANGED","symbol":"BTCUSDT-PERP","providerCode":"local-perp","sourceMode":"LOCAL_SIMULATED"}'
    })
    assert.equal(sourceChanges[0].providerCode, 'local-perp')
  })

  it('notifies account subscribers only when the STOMP client reconnects', async () => {
    const { subscribeTradingSessionEvents } = await importMarketStream()
    let reconnects = 0

    subscribeTradingSessionEvents('token-a', () => {}, () => {
      reconnects += 1
    })

    const [client] = globalThis.__marketStreamFakeClients
    client.config.onConnect()
    assert.equal(reconnects, 0)
    client.config.onConnect()
    assert.equal(reconnects, 0)

    client.config.onWebSocketClose()
    client.config.onConnect()

    assert.equal(reconnects, 1)
  })
})
