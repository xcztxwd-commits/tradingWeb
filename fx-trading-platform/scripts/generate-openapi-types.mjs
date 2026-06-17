import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { spawnSync } from 'node:child_process'

const checkOnly = process.argv.includes('--check')
const inputPath = resolve(process.env.OPENAPI_INPUT || 'artifacts/openapi/backend-openapi.json')
const outputPath = resolve(process.env.OPENAPI_TYPES_OUTPUT || 'packages/shared-types/src/generated/openapi.ts')

if (!existsSync(inputPath)) {
  console.error(`OpenAPI input not found: ${inputPath}`)
  process.exit(1)
}

await mkdir(dirname(outputPath), { recursive: true })

const targetPath = checkOnly
  ? resolve(mkdtempSync(resolve(tmpdir(), 'fx-openapi-types-')), 'openapi.ts')
  : outputPath

const cliPath = resolve('node_modules/openapi-typescript/bin/cli.js')
const result = spawnSync(process.execPath, [cliPath, inputPath, '--output', targetPath], {
  cwd: resolve('.'),
  encoding: 'utf8',
  stdio: 'pipe'
})

if (result.status !== 0) {
  if (result.stderr) {
    process.stderr.write(result.stderr)
  }
  if (result.stdout) {
    process.stdout.write(result.stdout)
  }
  if (result.error) {
    console.error(result.error.message)
  }
  process.exit(result.status || 1)
}

if (checkOnly) {
  const generated = readFileSync(targetPath, 'utf8')
  const current = existsSync(outputPath) ? readFileSync(outputPath, 'utf8') : ''
  rmSync(dirname(targetPath), { recursive: true, force: true })
  if (generated !== current) {
    console.error(`Generated OpenAPI types are out of date: ${outputPath}`)
    process.exit(1)
  }
  console.log(`Generated OpenAPI types are current: ${outputPath}`)
} else {
  console.log(`Generated OpenAPI types written to ${outputPath}`)
}
