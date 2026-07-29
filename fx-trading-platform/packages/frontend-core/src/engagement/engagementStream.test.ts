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
        globalThis.__engagementStreamFakeClients.push(this)
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
  globalThis.__engagementStreamFakeClients = []
  globalThis.window = { location: { protocol: 'http:', host: 'localhost' } }
})

afterEach(() => {
  delete globalThis.window
  delete globalThis.__engagementStreamFakeClients
})

async function importEngagementStream() {
  return import(`./engagementStream.ts?test=${Date.now()}-${Math.random()}`)
}

async function importMarketStream() {
  return import(`../market/marketStream.ts?engagement-test=${Date.now()}-${Math.random()}`)
}

async function importStompStream() {
  return import(`../realtime/stompStream.ts?engagement-test=${Date.now()}-${Math.random()}`)
}

describe('engagement stream subscription', () => {
  it('keeps the shared STOMP seam outside the market namespace', async () => {
    const marketSource = await readFile(new URL('../market/marketStream.ts', import.meta.url), 'utf8')
    const engagementSource = await readFile(new URL('./engagementStream.ts', import.meta.url), 'utf8')
    const realtimeSource = await readFile(new URL('../realtime/stompStream.ts', import.meta.url), 'utf8')

    assert.match(marketSource, /\.\.\/realtime\/stompStream\.ts/)
    assert.match(engagementSource, /\.\.\/realtime\/stompStream\.ts/)
    assert.doesNotMatch(realtimeSource, /topic\/market|engagement-updates/)
  })

  it('shares one connection for the same token without disposing anonymous or other-token sessions', async () => {
    const { subscribeQuote } = await importMarketStream()
    const { subscribeEngagementUpdates } = await importEngagementStream()

    const unsubscribeAnonymous = subscribeQuote('EURUSD', null, () => {})
    const unsubscribeAuthenticatedQuote = subscribeQuote('EURUSD', 'token-a', () => {})
    const unsubscribeEngagement = subscribeEngagementUpdates('token-a', () => {})
    const unsubscribeOtherToken = subscribeEngagementUpdates('token-b', () => {})

    assert.equal(globalThis.__engagementStreamFakeClients.length, 3)
    const [anonymousClient, tokenAClient, tokenBClient] = globalThis.__engagementStreamFakeClients
    assert.equal(anonymousClient.deactivated, false)
    assert.equal(tokenAClient.deactivated, false)
    assert.equal(tokenBClient.deactivated, false)

    unsubscribeAnonymous()
    assert.equal(anonymousClient.deactivated, true)
    assert.equal(tokenAClient.deactivated, false)
    assert.equal(tokenBClient.deactivated, false)

    unsubscribeAuthenticatedQuote()
    assert.equal(tokenAClient.deactivated, false)
    unsubscribeEngagement()
    assert.equal(tokenAClient.deactivated, true)
    assert.equal(tokenBClient.deactivated, false)
    unsubscribeOtherToken()
    assert.equal(tokenBClient.deactivated, true)
  })

  it('subscribes to the authenticated topic and user queue and accepts only the three wakeup payload types', async () => {
    const { subscribeEngagementUpdates } = await importEngagementStream()
    const received = []
    const unsubscribe = subscribeEngagementUpdates('token-a', (event) => received.push(event))

    const [client] = globalThis.__engagementStreamFakeClients
    client.config.onConnect()

    assert.equal(client.config.connectHeaders.Authorization, 'Bearer token-a')
    assert.deepEqual(
      client.subscriptions.map((subscription) => subscription.topic).sort(),
      ['/topic/engagement/updates', '/user/queue/engagement-updates'].sort()
    )

    const [topicSubscription] = client.subscriptions
    topicSubscription.callback({
      body: '{"updateType":"CAMPAIGN_UPDATED","aggregateId":"10000000-0000-0000-0000-000000000001","occurredAt":"2026-07-20T01:02:03Z"}'
    })
    topicSubscription.callback({
      body: '{"updateType":"CAMPAIGN_INVALIDATED","aggregateId":"10000000-0000-0000-0000-000000000002","occurredAt":"2026-07-20T01:02:04Z"}'
    })
    topicSubscription.callback({
      body: '{"updateType":"MESSAGE_UPDATED","aggregateId":"10000000-0000-0000-0000-000000000003","occurredAt":"2026-07-20T01:02:05Z"}'
    })
    topicSubscription.callback({
      body: '{"updateType":"UNKNOWN","aggregateId":"10000000-0000-0000-0000-000000000004","occurredAt":"2026-07-20T01:02:06Z"}'
    })
    topicSubscription.callback({ body: 'null' })

    assert.deepEqual(received, [
      {
        updateType: 'CAMPAIGN_UPDATED',
        aggregateId: '10000000-0000-0000-0000-000000000001',
        occurredAt: '2026-07-20T01:02:03Z'
      },
      {
        updateType: 'CAMPAIGN_INVALIDATED',
        aggregateId: '10000000-0000-0000-0000-000000000002',
        occurredAt: '2026-07-20T01:02:04Z'
      },
      {
        updateType: 'MESSAGE_UPDATED',
        aggregateId: '10000000-0000-0000-0000-000000000003',
        occurredAt: '2026-07-20T01:02:05Z'
      }
    ])

    unsubscribe()
  })

  it('isolates malformed JSON and throwing handlers from valid engagement delivery', async () => {
    const { subscribeEngagementUpdates } = await importEngagementStream()
    const warnings = []
    const originalWarn = console.warn
    console.warn = (...args) => warnings.push(args)

    try {
      const received = []
      const unsubscribeThrowing = subscribeEngagementUpdates('token-a', () => {
        throw new Error('handler failed')
      })
      const unsubscribeValid = subscribeEngagementUpdates('token-a', (event) => received.push(event))

      const [client] = globalThis.__engagementStreamFakeClients
      client.config.onConnect()
      assert.equal(client.subscriptions.length, 2)

      const topicSubscription = client.subscriptions.find(
        (subscription) => subscription.topic === '/topic/engagement/updates'
      )
      assert.doesNotThrow(() => topicSubscription.callback({ body: '{bad json' }))
      assert.doesNotThrow(() =>
        topicSubscription.callback({
          body: '{"updateType":"MESSAGE_UPDATED","aggregateId":"10000000-0000-0000-0000-000000000003","occurredAt":"2026-07-20T01:02:05Z"}'
        })
      )
      assert.deepEqual(received, [
        {
          updateType: 'MESSAGE_UPDATED',
          aggregateId: '10000000-0000-0000-0000-000000000003',
          occurredAt: '2026-07-20T01:02:05Z'
        }
      ])
      assert.equal(warnings.length, 2)

      unsubscribeThrowing()
      unsubscribeValid()
    } finally {
      console.warn = originalWarn
    }
  })

  it('notifies reconnect once and releases both destinations after the last subscriber', async () => {
    const { subscribeEngagementUpdates } = await importEngagementStream()
    let reconnects = 0
    const unsubscribeFirst = subscribeEngagementUpdates('token-a', () => {}, () => {
      reconnects += 1
    })
    const unsubscribeSecond = subscribeEngagementUpdates('token-a', () => {})

    const [client] = globalThis.__engagementStreamFakeClients
    client.config.onConnect()
    assert.equal(client.subscriptions.length, 2)
    assert.equal(reconnects, 0)

    client.config.onWebSocketClose()
    client.config.onConnect()
    assert.equal(reconnects, 1)

    const activeSubscriptions = client.subscriptions.slice(-2)
    unsubscribeFirst()
    assert.equal(activeSubscriptions.some((subscription) => subscription.unsubscribed), false)
    assert.equal(client.deactivated, false)

    unsubscribeSecond()
    assert.equal(activeSubscriptions.every((subscription) => subscription.unsubscribed), true)
    assert.equal(client.deactivated, true)
    unsubscribeSecond()
  })

  it('keeps duplicate callback leases independent until each disposer runs', async () => {
    const { subscribeStompTopic } = await importStompStream()
    let deliveries = 0
    let reconnects = 0
    const onMessage = () => {
      deliveries += 1
    }
    const onReconnect = () => {
      reconnects += 1
    }
    const unsubscribeFirst = subscribeStompTopic('/user/queue/lease-test', 'token-a', onMessage, onReconnect)
    const unsubscribeSecond = subscribeStompTopic('/user/queue/lease-test', 'token-a', onMessage, onReconnect)

    const [client] = globalThis.__engagementStreamFakeClients
    client.config.onConnect()
    const [subscription] = client.subscriptions
    subscription.callback({ body: '{}' })
    assert.equal(deliveries, 2)

    unsubscribeFirst()
    assert.equal(subscription.unsubscribed, false)
    assert.equal(client.deactivated, false)
    subscription.callback({ body: '{}' })
    assert.equal(deliveries, 3)

    client.config.onWebSocketClose()
    client.config.onConnect()
    assert.equal(reconnects, 1)

    unsubscribeSecond()
    assert.equal(client.subscriptions.at(-1).unsubscribed, true)
    assert.equal(client.deactivated, true)
  })

  it('exposes the object subscription adapter consumed by the popup queue controller', async () => {
    const { engagementUpdateSubscription } = await importEngagementStream()
    const received = []
    let reconnects = 0
    const unsubscribe = engagementUpdateSubscription.subscribe('token-a', {
      onUpdate: (event) => received.push(event),
      onReconnect: () => {
        reconnects += 1
      }
    })

    const [client] = globalThis.__engagementStreamFakeClients
    client.config.onConnect()
    client.subscriptions[0].callback({
      body: '{"updateType":"CAMPAIGN_UPDATED","aggregateId":"10000000-0000-0000-0000-000000000001","occurredAt":"2026-07-20T01:02:03Z"}'
    })
    assert.equal(received.length, 1)

    client.config.onWebSocketClose()
    client.config.onConnect()
    assert.equal(reconnects, 1)
    unsubscribe()
    assert.equal(client.deactivated, true)
  })
})
