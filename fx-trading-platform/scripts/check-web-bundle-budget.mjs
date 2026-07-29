import { existsSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '')
const assetsDir = join(projectRoot, 'apps/web/dist/assets')

const budgets = [
  {
    label: 'TradingRoute + PcTradingTerminal JS',
    patterns: [/^TradingRoute-.*\.js$/, /^PcTradingTerminal-.*\.js$/],
    maxBytes: 380_000
  },
  {
    label: 'MobileTradingTerminal JS',
    patterns: [/^MobileTradingTerminal-.*\.js$/],
    maxBytes: 20_000
  },
  {
    label: 'IndicatorSettingsModal JS',
    patterns: [/^IndicatorSettingsModal-.*\.js$/],
    maxBytes: 20_000
  },
  {
    label: 'ChartDrawingToolbar JS',
    patterns: [/^ChartDrawingToolbar-.*\.js$/],
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
  const matchedFiles = []
  for (const pattern of budget.patterns) {
    const matches = files.filter((file) => pattern.test(file))
    if (matches.length === 0) {
      failures.push(`Missing required chunk for ${budget.label}: ${pattern}`)
    }
    matchedFiles.push(...matches)
  }

  const uniqueFiles = [...new Set(matchedFiles)]
  const size = uniqueFiles.reduce((total, file) => total + statSync(join(assetsDir, file)).size, 0)
  report.push({ label: budget.label, files: uniqueFiles, size, maxBytes: budget.maxBytes })
  if (uniqueFiles.length > 0 && size > budget.maxBytes) {
    failures.push(`${budget.label} is ${size} bytes, expected <= ${budget.maxBytes} bytes (${uniqueFiles.join(', ')})`)
  }
}

for (const item of report) {
  console.log(`${item.label}: ${item.size}/${item.maxBytes} bytes (${item.files.join(', ') || 'missing'})`)
}

if (failures.length > 0) {
  fail(`Bundle budget failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`)
}

console.log('Bundle budget passed.')

function fail(message) {
  console.error(message)
  process.exit(1)
}
