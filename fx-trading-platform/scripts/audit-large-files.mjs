import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const platformRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const repoRoot = resolve(platformRoot, '..')

const limits = [
  [resolve(repoRoot, 'src/Store.ts'), 'src/Store.ts', 1900],
  [resolve(repoRoot, 'src/Chart.ts'), 'src/Chart.ts', 1450],
  [resolve(repoRoot, 'src/common/EventHandler.ts'), 'src/common/EventHandler.ts', 1000],
  [
    resolve(platformRoot, 'apps/web/src/routes/trading/useTradingRouteController.ts'),
    'fx-trading-platform/apps/web/src/routes/trading/useTradingRouteController.ts',
    330
  ],
  [
    resolve(platformRoot, 'apps/web/src/pc/pages/trading/PcTradingTerminal.tsx'),
    'fx-trading-platform/apps/web/src/pc/pages/trading/PcTradingTerminal.tsx',
    330
  ],
  [
    resolve(platformRoot, 'apps/web/src/mobile/pages/trading/MobileTradingTerminal.tsx'),
    'fx-trading-platform/apps/web/src/mobile/pages/trading/MobileTradingTerminal.tsx',
    230
  ],
  [
    resolve(platformRoot, 'apps/web/src/shared-widgets/trading/order-form/useTradePanelController.ts'),
    'fx-trading-platform/apps/web/src/shared-widgets/trading/order-form/useTradePanelController.ts',
    330
  ],
  [
    resolve(platformRoot, 'apps/web/src/shared-widgets/trading/order-form/TradePanel.tsx'),
    'fx-trading-platform/apps/web/src/shared-widgets/trading/order-form/TradePanel.tsx',
    230
  ],
  [
    resolve(platformRoot, 'backend/src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogService.java'),
    'fx-trading-platform/backend/src/main/java/com/fxplatform/admin/service/AdminFeatureCatalogService.java',
    250
  ]
]

const failures = []
const report = []

for (const [absolutePath, displayPath, limit] of limits) {
  const lines = readFileSync(absolutePath, 'utf8').split(/\r?\n/).length
  report.push({ displayPath, limit, lines })
  if (lines > limit) failures.push(`${displayPath} has ${lines} lines; limit is ${limit}`)
}

for (const item of report) {
  console.log(`${item.displayPath}: ${item.lines}/${item.limit} lines`)
}

if (failures.length > 0) {
  console.error(`Large file audit failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`)
  process.exit(1)
}

console.log('Large file audit passed.')
