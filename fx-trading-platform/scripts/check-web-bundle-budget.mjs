import { existsSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const assetsDir = join(projectRoot, 'apps/web/dist/assets')

const budgets = [
  {
    label: 'TradingPage JS',
    pattern: /^TradingPage-.*\.js$/,
    maxBytes: 380_000
  },
  {
    label: 'MobileTradingTerminal JS',
    pattern: /^MobileTradingTerminal-.*\.js$/,
    maxBytes: 20_000
  },
  {
    label: 'IndicatorSettingsModal JS',
    pattern: /^IndicatorSettingsModal-.*\.js$/,
    maxBytes: 20_000
  },
  {
    label: 'ChartDrawingToolbar JS',
    pattern: /^ChartDrawingToolbar-.*\.js$/,
    maxBytes: 80_000
  }
]

if (!existsSync(assetsDir)) {
  fail(`Missing build assets directory: ${assetsDir}. Run web:build first.`)
}

const files = readdirSync(assetsDir)
const failures = []
const report = []

for (const budget of budgets) {
  const matches = files.filter((file) => budget.pattern.test(file))
  if (matches.length === 0) {
    failures.push(`Missing required chunk: ${budget.label}`)
    continue
  }

  for (const file of matches) {
    const size = statSync(join(assetsDir, file)).size
    report.push({ label: budget.label, file, size, maxBytes: budget.maxBytes })
    if (size > budget.maxBytes) {
      failures.push(`${budget.label} is ${size} bytes, expected <= ${budget.maxBytes} bytes (${file})`)
    }
  }
}

for (const item of report) {
  console.log(`${item.label}: ${item.size}/${item.maxBytes} bytes (${item.file})`)
}

if (failures.length > 0) {
  fail(`Bundle budget failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`)
}

console.log('Bundle budget passed.')

function fail(message) {
  console.error(message)
  process.exit(1)
}
