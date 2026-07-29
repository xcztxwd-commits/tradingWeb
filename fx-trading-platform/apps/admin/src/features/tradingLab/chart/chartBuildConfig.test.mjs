import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const platformRoot = new URL('../../../../../..', import.meta.url)

async function readPlatformFile(relativePath) {
  return readFile(new URL(relativePath, platformRoot), 'utf8')
}

test('Admin build resolves the existing local KLineCharts dist without a package dependency', async () => {
  const packageJson = JSON.parse(
    await readPlatformFile('apps/admin/package.json'),
  )
  assert.equal(
    packageJson.scripts.prebuild,
    'node ../../scripts/ensure-klinecharts-dist.mjs',
  )
  assert.equal(packageJson.dependencies?.klinecharts, undefined)
  assert.equal(packageJson.devDependencies?.klinecharts, undefined)

  const viteConfig = await readPlatformFile('apps/admin/vite.config.ts')
  assert.match(viteConfig, /import \{ resolve \} from 'node:path'/u)
  assert.match(
    viteConfig,
    /klinecharts:\s*resolve\(__dirname,\s*'\.\.\/\.\.\/\.\.\/dist\/index\.esm\.js'\)/u,
  )

  const tsconfig = JSON.parse(
    await readPlatformFile('apps/admin/tsconfig.json'),
  )
  assert.deepEqual(
    tsconfig.compilerOptions.paths?.klinecharts,
    ['../../../dist/index.d.ts'],
  )
})

test('Trading Lab stays lazy and reuses the unchanged dist guard', async () => {
  const adminApp = await readPlatformFile('apps/admin/src/app/AdminApp.tsx')
  assert.match(
    adminApp,
    /lazy\(\(\)\s*=>\s*[\s\S]*?import\('\.\.\/features\/tradingLab'\)/u,
  )
  assert.doesNotMatch(adminApp, /from ['"]klinecharts['"]/u)

  const ensureScript = await readPlatformFile(
    'scripts/ensure-klinecharts-dist.mjs',
  )
  assert.match(ensureScript, /dist\/index\.esm\.js/u)
})
