import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const webSrc = resolve(currentDir, '..')

const contracts = [
  {
    route: 'orders',
    name: 'Orders',
    controllerTokens: ['useTranslatedAccountData', 'cancelOrder', 'cancelProtection', 'modifyOrder', 'updateProtection', 'getOrderEvents', 'operationGuard'],
    content: 'orders/OrdersRouteContent.tsx'
  },
  {
    route: 'positions',
    name: 'Positions',
    controllerTokens: ['useTranslatedAccountData', 'mutateTradingPosition', 'updateTradingPositionProtection', 'operationGuard'],
    content: 'positions/PositionsRouteContent.tsx'
  },
  {
    route: 'wallet',
    name: 'Wallet',
    controllerTokens: ['useWalletController', 'getFundOrders', 'createFundOrder', 'operationGuard'],
    content: 'wallet/WalletRouteContent.tsx'
  }
] as const

describe('orders, positions and wallet platform routes', () => {
  it('owns each controller above independently lazy PC and Mobile views', () => {
    for (const contract of contracts) {
      const routeSource = source('routes', contract.route, `${contract.name}Route.tsx`)
      assert.match(routeSource, new RegExp(`const Pc${contract.name}Page = lazy`))
      assert.match(routeSource, new RegExp(`const Mobile${contract.name}Page = lazy`))
      assert.match(routeSource, new RegExp(`const model = use${contract.name}RouteController\\(\\)`))
      assert.match(routeSource, /<PlatformView[\s\S]*model=\{model\}/)
      assert.doesNotMatch(routeSource, /^import .*\/(?:pc|mobile)\//m)
    }
  })

  it('keeps API calls, mutation state and the duplicate-submit guard out of both views', () => {
    for (const contract of contracts) {
      const controller = source('routes', contract.route, `use${contract.name}RouteController.ts`)
      const content = source('shared-widgets', ...contract.content.split('/'))
      const pc = source('pc', 'pages', contract.route, `Pc${contract.name}Page.tsx`)
      const mobile = source('mobile', 'pages', contract.route, `Mobile${contract.name}Page.tsx`)

      for (const token of contract.controllerTokens) assert.match(controller, new RegExp(token))
      assert.match(controller, /operationGuard\.run/)
      assert.doesNotMatch(
        `${content}\n${pc}\n${mobile}`,
        /useTranslatedAccountData|useWalletController|(?<!\.)\b(?:cancelOrder|mutateTradingPosition|createFundOrder|getFundOrders)\(/
      )
      assert.match(content, /renderDataCollection/)
    }
  })

  it('uses table collections only in PC and card collections only in Mobile', () => {
    const pcCollection = source('pc', 'components', 'PcDataCollection.tsx')
    const mobileCollection = source('mobile', 'components', 'MobileDataCollection.tsx')
    assert.match(pcCollection, /DataTable/)
    assert.doesNotMatch(pcCollection, /DataCardList/)
    assert.match(mobileCollection, /DataCardList/)
    assert.doesNotMatch(mobileCollection, /<DataTable/)

    for (const contract of contracts) {
      assert.match(source('pc', 'pages', contract.route, `Pc${contract.name}Page.tsx`), /PcDataCollection/)
      assert.match(source('mobile', 'pages', contract.route, `Mobile${contract.name}Page.tsx`), /MobileDataCollection/)
    }
  })

  it('keeps dialogs and controlled edit state in the shared route model across resize', () => {
    const ordersTypes = source('routes', 'orders', 'ordersRoute.types.ts')
    const positionsTypes = source('routes', 'positions', 'positionsRoute.types.ts')
    const walletTypes = source('routes', 'wallet', 'walletRoute.types.ts')
    assert.match(ordersTypes, /editingOrder/)
    assert.match(ordersTypes, /modifyFields/)
    assert.match(ordersTypes, /pendingCancelOrder/)
    assert.match(source('shared-widgets', 'orders', 'OrdersRouteContent.tsx'), /<Dialog/)
    assert.match(positionsTypes, /pendingClosePosition/)
    assert.match(positionsTypes, /protectionForm/)
    assert.match(walletTypes, /transferOpen/)
    assert.match(walletTypes, /resetOpen/)
  })
})

function source(...segments: string[]) {
  return readFileSync(join(webSrc, ...segments), 'utf8')
}
