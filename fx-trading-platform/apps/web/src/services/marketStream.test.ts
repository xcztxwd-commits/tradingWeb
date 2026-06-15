import assert from 'node:assert/strict'
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

  it('shares one client and one STOMP subscription for two quote handlers on the same topic', async () => {
    const { subscribeQuote } = await importMarketStream()
    const received = []

    subscribeQuote('EURUSD', 'token-a', (quote) => received.push(['first', quote]))
    subscribeQuote('EURUSD', 'token-a', (quote) => received.push(['second', quote]))

    const [client] = globalThis.__marketStreamFakeClients
    assert.equal(globalThis.__marketStreamFakeClients.length, 1)

    client.config.onConnect()
    assert.equal(client.subscriptions.length, 1)

    client.subscriptions[0].callback({ body: '{"symbol":"EURUSD","bid":1.1}' })
    assert.deepEqual(received, [
      ['first', { symbol: 'EURUSD', bid: 1.1 }],
      ['second', { symbol: 'EURUSD', bid: 1.1 }]
    ])

    client.config.onConnect()
    assert.equal(client.subscriptions.length, 1)
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

  it('deactivates the previous session and creates a new client when the token changes', async () => {
    const { subscribeQuote } = await importMarketStream()

    subscribeQuote('EURUSD', 'token-a', () => {})
    const firstClient = globalThis.__marketStreamFakeClients[0]

    subscribeQuote('EURUSD', 'token-b', () => {})
    const secondClient = globalThis.__marketStreamFakeClients[1]

    assert.equal(globalThis.__marketStreamFakeClients.length, 2)
    assert.equal(firstClient.deactivated, true)
    assert.equal(secondClient.deactivated, false)
  })
})
