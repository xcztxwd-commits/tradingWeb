import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const klinechartsDist = resolve(scriptDir, '../..', 'dist/index.esm.js')

if (!existsSync(klinechartsDist)) {
  console.error('Missing KLineCharts build artifact: dist/index.esm.js')
  console.error('Run `pnpm.cmd run build` from the repository root before building @fx-platform/web.')
  process.exit(1)
}
