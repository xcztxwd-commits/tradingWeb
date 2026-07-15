# Web Shared Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有页面呈现和交易行为的前提下，建立可自动验证的前端依赖边界，创建内部 `@fx-platform/ui` 与 `@fx-platform/frontend-core`，并完成主题、首个公共组件、共享类型、API/认证/行情能力的实际迁移。

**Architecture:** `apps/web` 继续作为唯一 Vite 应用；`packages/ui` 只提供主题和无业务 UI，`packages/frontend-core` 提供 UI 无关的模型、API、认证存储和行情能力。应用在本计划结束时直接消费两个 package，旧实现路径被删除；PC/Mobile 目录和运行时选择在后续独立计划中实施。

**Tech Stack:** React 19、TypeScript 5.8、Vite 7、npm workspaces、Node test runner、Zustand 5、STOMP、CSS custom properties。

## Global Constraints

- 必须先读取 `C:\workspace\tradingWeb\AGENTS.md` 与 `docs/superpowers/specs/2026-07-15-web-pc-mobile-ui-separation-design.md`。
- 当前仓库已有大量用户未提交前端改动；不得执行 `git reset --hard`、`git checkout --`、递归删除或覆盖用户改动。
- 如果本任务涉及的文件仍有未提交改动，停止实施并报告重叠文件；不得自行 stash 或提交用户改动。
- 保留一个 `apps/web`、一份路由契约和现有 URL。
- 本计划不创建 `pc/mobile` 页面、不修改布局、不做视觉重设计。
- 不改变后端 API、交易规则、钱包规则、行情 fallback、认证语义或 `demo/live` 隔离。
- `@fx-platform/ui` 不得依赖业务、路由或 `frontend-core`。
- `@fx-platform/frontend-core` 不得依赖应用页面、CSS 或 React Router。
- 每个任务先写失败测试，再做最小实现；每个任务独立验证、独立提交。
- 不能删除或放宽测试来掩盖失败。

## Scope Decomposition

本规格包含四个顺序子项目，本计划只实施第一个：

1. **Shared foundation（本计划）**：依赖守卫、UI package、core package、API/认证/行情迁移。
2. **Trading controller extraction（后续独立计划）**：交易 session、表单、校验、提交和 view-model 去 UI 化。
3. **Adaptive runtime（后续独立计划）**：唯一设备判断、公共 Provider、route adapter、PC/Mobile Shell。
4. **Route-by-route UI migration（按路由独立计划）**：普通页面、交易终端、旧 CSS 与兼容层清理。

本计划完成后应用的视觉和路由行为必须与执行前一致。

## Preflight Gate

在任何 Task 前执行：

```powershell
Set-Location C:\workspace\tradingWeb
git status --short
git diff --name-only
git diff --cached --name-only
```

本计划会修改或移动以下区域；其中任何文件仍为 dirty 时都必须停止：

```text
fx-trading-platform/package.json
fx-trading-platform/package-lock.json
fx-trading-platform/scripts/
fx-trading-platform/apps/web/src/main.tsx
fx-trading-platform/apps/web/src/app/
fx-trading-platform/apps/web/src/design-system/
fx-trading-platform/apps/web/src/components/SelectField.tsx
fx-trading-platform/apps/web/src/components/user-page/userPageModels.ts
fx-trading-platform/apps/web/src/services/
fx-trading-platform/apps/web/src/types/trading.ts
fx-trading-platform/apps/web/src/components/tables/types.ts
fx-trading-platform/apps/web/src/features/market/
fx-trading-platform/apps/web/src/styles.css
fx-trading-platform/scripts/verify-architecture.mjs
fx-trading-platform/docs/architecture.md
```

安全基线形成后运行：

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Expected：三条命令均退出码 `0`。若失败，记录完整失败并先恢复基线；不能把基线失败归入本计划。

---

### Task 1: Add Enforced Frontend Dependency Boundaries

**Files:**
- Create: `fx-trading-platform/scripts/verify-frontend-boundaries.mjs`
- Create: `fx-trading-platform/scripts/verify-frontend-boundaries.test.mjs`
- Modify: `fx-trading-platform/package.json`

**Interfaces:**
- Produces: `findFrontendBoundaryViolations(rootDir: string): string[]`
- Produces: npm scripts `test:frontend-boundaries` and `verify:frontend-boundaries`
- Enforces: `pc !-> mobile`、`mobile !-> pc`、`shared-widgets !-> pc/mobile`、`ui !-> core/app`、`core !-> ui/app/router/css`

- [ ] **Step 1: Write the failing boundary-verifier test**

Create `scripts/verify-frontend-boundaries.test.mjs`:

```js
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { afterEach, describe, it } from 'node:test'

import { findFrontendBoundaryViolations } from './verify-frontend-boundaries.mjs'

const roots = []

afterEach(() => {
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true })
})

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'fx-frontend-boundaries-'))
  roots.push(root)
  return root
}

function write(root, relativePath, source) {
  const path = join(root, relativePath)
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, source)
}

describe('frontend dependency boundaries', () => {
  it('reports every forbidden dependency direction', () => {
    const root = fixture()
    write(root, 'apps/web/src/pc/Page.tsx', "import '../mobile/Page'\n")
    write(root, 'apps/web/src/mobile/Page.tsx', "import '../pc/Page'\n")
    write(root, 'apps/web/src/shared-widgets/Quote.tsx', "import '../pc/Page'\n")
    write(root, 'packages/ui/src/Button.tsx', "import '@fx-platform/frontend-core'\n")
    write(root, 'packages/frontend-core/src/model.ts', "import '@fx-platform/ui'\nimport 'react-router-dom'\nimport './model.css'\n")
    write(root, 'packages/shared-types/src/index.ts', "export * from '@fx-platform/frontend-core'\n")

    const violations = findFrontendBoundaryViolations(root)

    assert.equal(violations.length, 8)
    assert.ok(violations.some((line) => line.includes('pc must not import mobile')))
    assert.ok(violations.some((line) => line.includes('mobile must not import pc')))
    assert.ok(violations.some((line) => line.includes('shared-widgets must not import pc')))
    assert.ok(violations.some((line) => line.includes('ui must not import frontend-core')))
    assert.ok(violations.some((line) => line.includes('frontend-core must not import ui')))
    assert.ok(violations.some((line) => line.includes('frontend-core must not import react-router-dom')))
    assert.ok(violations.some((line) => line.includes('frontend-core must not import styles')))
    assert.ok(violations.some((line) => line.includes('shared-types must not import frontend-core')))
  })

  it('allows the approved dependency flow', () => {
    const root = fixture()
    write(root, 'apps/web/src/pc/Page.tsx', "import '@fx-platform/ui'\nimport '@fx-platform/frontend-core'\n")
    write(root, 'apps/web/src/mobile/Page.tsx', "import '@fx-platform/ui'\nimport '../shared-widgets/Quote'\n")
    write(root, 'apps/web/src/shared-widgets/Quote.tsx', "import '@fx-platform/ui'\nimport '@fx-platform/frontend-core'\n")
    write(root, 'packages/ui/src/Button.tsx', "import type { ReactNode } from 'react'\n")
    write(root, 'packages/frontend-core/src/model.ts', "import type { ApiResponse } from '@fx-platform/shared-types'\n")

    assert.deepEqual(findFrontendBoundaryViolations(root), [])
  })
})
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "node --test fx-trading-platform/scripts/verify-frontend-boundaries.test.mjs"
```

Expected：FAIL，错误包含 `ERR_MODULE_NOT_FOUND`，因为 verifier 尚未创建。

- [ ] **Step 3: Implement the boundary verifier**

Create `scripts/verify-frontend-boundaries.mjs`:

```js
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const sourceExtensions = new Set(['.js', '.jsx', '.mjs', '.ts', '.tsx'])

const groupRoots = {
  pc: 'apps/web/src/pc',
  mobile: 'apps/web/src/mobile',
  'shared-widgets': 'apps/web/src/shared-widgets',
  'web-app': 'apps/web/src',
  ui: 'packages/ui/src',
  'frontend-core': 'packages/frontend-core/src',
  'shared-types': 'packages/shared-types/src'
}

const packageGroups = new Map([
  ['@fx-platform/ui', 'ui'],
  ['@fx-platform/frontend-core', 'frontend-core'],
  ['@fx-platform/shared-types', 'shared-types']
])

const forbiddenGroups = {
  pc: new Set(['mobile']),
  mobile: new Set(['pc']),
  'shared-widgets': new Set(['pc', 'mobile']),
  ui: new Set(['frontend-core', 'web-app', 'pc', 'mobile', 'shared-widgets']),
  'frontend-core': new Set(['ui', 'web-app', 'pc', 'mobile', 'shared-widgets']),
  'shared-types': new Set(['ui', 'frontend-core', 'web-app', 'pc', 'mobile', 'shared-widgets'])
}

export function findFrontendBoundaryViolations(rootDir) {
  const absoluteRoot = resolve(rootDir)
  const roots = Object.fromEntries(
    Object.entries(groupRoots).map(([group, path]) => [group, resolve(absoluteRoot, path)])
  )
  const violations = []

  for (const sourceGroup of Object.keys(groupRoots)) {
    if (sourceGroup === 'web-app') continue
    const sourceRoot = roots[sourceGroup]
    if (!existsSync(sourceRoot)) continue

    for (const sourceFile of collectSourceFiles(sourceRoot)) {
      const source = readFileSync(sourceFile, 'utf8')
      for (const specifier of extractImportSpecifiers(source)) {
        const targetGroup = classifyImport(sourceFile, specifier, roots)
        if (targetGroup && forbiddenGroups[sourceGroup]?.has(targetGroup)) {
          violations.push(formatViolation(absoluteRoot, sourceFile, sourceGroup, targetGroup, specifier))
        }
        if (sourceGroup === 'frontend-core' && specifier === 'react-router-dom') {
          violations.push(formatViolation(absoluteRoot, sourceFile, sourceGroup, 'react-router-dom', specifier))
        }
        if (sourceGroup === 'frontend-core' && /\.(?:css|less|sass|scss)$/.test(specifier)) {
          violations.push(formatViolation(absoluteRoot, sourceFile, sourceGroup, 'styles', specifier))
        }
      }
    }
  }

  return [...new Set(violations)].sort()
}

function collectSourceFiles(path) {
  const files = []
  for (const entry of readdirSync(path)) {
    const child = resolve(path, entry)
    const stat = statSync(child)
    if (stat.isDirectory()) {
      files.push(...collectSourceFiles(child))
      continue
    }
    const extension = entry.slice(entry.lastIndexOf('.'))
    if (sourceExtensions.has(extension) && !entry.includes('.test.')) files.push(child)
  }
  return files
}

function extractImportSpecifiers(source) {
  const specifiers = []
  const expression = /(?:import|export)\s+(?:type\s+)?(?:[^'\"]*?\sfrom\s*)?['\"]([^'\"]+)['\"]|import\s*\(\s*['\"]([^'\"]+)['\"]\s*\)/g
  for (const match of source.matchAll(expression)) specifiers.push(match[1] ?? match[2])
  return specifiers
}

function classifyImport(sourceFile, specifier, roots) {
  for (const [packageName, group] of packageGroups) {
    if (specifier === packageName || specifier.startsWith(`${packageName}/`)) return group
  }
  if (!specifier.startsWith('.')) return null

  const target = resolve(dirname(sourceFile), specifier)
  for (const group of ['pc', 'mobile', 'shared-widgets', 'ui', 'frontend-core', 'shared-types']) {
    if (isInside(target, roots[group])) return group
  }
  if (isInside(target, roots['web-app'])) return 'web-app'
  return null
}

function isInside(path, root) {
  return path === root || path.startsWith(`${root}${sep}`)
}

function formatViolation(root, sourceFile, sourceGroup, targetGroup, specifier) {
  const file = relative(root, sourceFile).split(sep).join('/')
  return `${file}: ${sourceGroup} must not import ${targetGroup} via "${specifier}"`
}

const directExecution = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (directExecution) {
  const root = fileURLToPath(new URL('..', import.meta.url))
  const violations = findFrontendBoundaryViolations(root)
  if (violations.length > 0) {
    console.error('Frontend boundary verification failed:')
    for (const violation of violations) console.error(`- ${violation}`)
    process.exit(1)
  }
  console.log('Frontend boundary verification passed.')
}
```

- [ ] **Step 4: Add package scripts**

Add these entries to `fx-trading-platform/package.json`:

```json
"test:frontend-boundaries": "node --test scripts/verify-frontend-boundaries.test.mjs",
"verify:frontend-boundaries": "node scripts/verify-frontend-boundaries.mjs",
"verify:architecture": "node scripts/verify-architecture.mjs && node scripts/verify-frontend-boundaries.mjs"
```

Replace the existing single-command `verify:architecture` value; do not create a duplicate key.

- [ ] **Step 5: Run focused and integrated verification**

Run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run test:frontend-boundaries"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:frontend-boundaries"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Expected：三条命令 PASS；最后两条分别输出 `Frontend boundary verification passed.` 与 `Architecture verification passed.`。

- [ ] **Step 6: Commit**

```powershell
git add fx-trading-platform/scripts/verify-frontend-boundaries.mjs fx-trading-platform/scripts/verify-frontend-boundaries.test.mjs fx-trading-platform/package.json
git commit -m "test: enforce frontend package boundaries"
```

---

### Task 2: Create `@fx-platform/ui` and Move the Theme System

**Files:**
- Create: `fx-trading-platform/packages/ui/package.json`
- Create: `fx-trading-platform/packages/ui/tsconfig.json`
- Create: `fx-trading-platform/packages/ui/src/index.ts`
- Move: `apps/web/src/design-system/theme/theme.css` → `packages/ui/src/theme/theme.css`
- Move: `apps/web/src/design-system/theme/themes.ts` → `packages/ui/src/theme/themes.ts`
- Move: `apps/web/src/design-system/theme/themes.test.ts` → `packages/ui/src/theme/themes.test.ts`
- Move: `apps/web/src/design-system/theme/ThemeProvider.tsx` → `packages/ui/src/theme/ThemeProvider.tsx`
- Move and modify: `apps/web/src/design-system/theme/ThemeProvider.test.ts` → `packages/ui/src/theme/ThemeProvider.test.ts`
- Create: `apps/web/src/app/uiPackageIntegration.test.ts`
- Modify: `apps/web/src/main.tsx`
- Modify: `apps/web/src/app/AppShell.tsx`
- Modify: `apps/web/src/pages/trading/TradingPage.tsx`
- Modify: `fx-trading-platform/package.json`
- Modify: `fx-trading-platform/package-lock.json`

**Interfaces:**
- Produces: `ThemeProvider`、`useTheme`、`TradingTheme`、`TradingThemeId`、`getTradingTheme`
- Produces: CSS entry `@fx-platform/ui/theme.css`
- Consumes: React 19 as a peer dependency

- [ ] **Step 1: Write the failing app integration test**

Create `apps/web/src/app/uiPackageIntegration.test.ts`:

```ts
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const appDir = dirname(fileURLToPath(import.meta.url))
const platformRoot = join(appDir, '..', '..', '..', '..')
const uiRoot = join(platformRoot, 'packages', 'ui')

describe('@fx-platform/ui integration', () => {
  it('owns the theme system and is consumed through its public API', () => {
    assert.equal(existsSync(join(uiRoot, 'package.json')), true)
    assert.equal(existsSync(join(uiRoot, 'src', 'theme', 'theme.css')), true)
    assert.equal(existsSync(join(uiRoot, 'src', 'theme', 'ThemeProvider.tsx')), true)

    const packageJson = JSON.parse(readFileSync(join(uiRoot, 'package.json'), 'utf8'))
    const publicApi = readFileSync(join(uiRoot, 'src', 'index.ts'), 'utf8')
    const main = readFileSync(join(appDir, '..', 'main.tsx'), 'utf8')
    const shell = readFileSync(join(appDir, 'AppShell.tsx'), 'utf8')

    assert.equal(packageJson.name, '@fx-platform/ui')
    assert.match(publicApi, /export \{ ThemeProvider, useTheme \}/)
    assert.match(main, /from '@fx-platform\/ui'/)
    assert.match(main, /import '@fx-platform\/ui\/theme\.css'/)
    assert.match(shell, /from '@fx-platform\/ui'/)
    assert.doesNotMatch(main, /design-system\/theme/)
    assert.doesNotMatch(shell, /design-system\/theme/)
  })
})
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform --workspace apps/web run test -- src/app/uiPackageIntegration.test.ts"
```

Expected：FAIL，断言 `packages/ui/package.json` 不存在。

- [ ] **Step 3: Create the UI package metadata**

Create `packages/ui/package.json`:

```json
{
  "name": "@fx-platform/ui",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "main": "src/index.ts",
  "types": "src/index.ts",
  "exports": {
    ".": {
      "types": "./src/index.ts",
      "import": "./src/index.ts"
    },
    "./theme.css": "./src/theme/theme.css"
  },
  "scripts": {
    "test": "node --test \"src/**/*.test.ts\"",
    "typecheck": "tsc --noEmit"
  },
  "peerDependencies": {
    "react": "^19.0.0",
    "react-dom": "^19.0.0"
  },
  "devDependencies": {
    "@types/react": "^19.0.0",
    "@types/react-dom": "^19.0.0",
    "typescript": "^5.8.3"
  }
}
```

Create `packages/ui/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["DOM", "DOM.Iterable", "ES2022"],
    "strict": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "allowImportingTsExtensions": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx"
  },
  "include": ["src"],
  "exclude": ["src/**/*.test.ts"]
}
```

Create `packages/ui/src/index.ts`:

```ts
export { ThemeProvider, useTheme } from './theme/ThemeProvider'
export { defaultThemeId, getTradingTheme, tradingThemes } from './theme/themes'
export type { TradingTheme, TradingThemeId, TradingThemeTokens } from './theme/themes'
```

- [ ] **Step 4: Move the existing theme implementation without changing values**

Run:

```powershell
New-Item -ItemType Directory -Force fx-trading-platform/packages/ui/src/theme | Out-Null
git mv fx-trading-platform/apps/web/src/design-system/theme/theme.css fx-trading-platform/packages/ui/src/theme/theme.css
git mv fx-trading-platform/apps/web/src/design-system/theme/themes.ts fx-trading-platform/packages/ui/src/theme/themes.ts
git mv fx-trading-platform/apps/web/src/design-system/theme/themes.test.ts fx-trading-platform/packages/ui/src/theme/themes.test.ts
git mv fx-trading-platform/apps/web/src/design-system/theme/ThemeProvider.tsx fx-trading-platform/packages/ui/src/theme/ThemeProvider.tsx
git mv fx-trading-platform/apps/web/src/design-system/theme/ThemeProvider.test.ts fx-trading-platform/packages/ui/src/theme/ThemeProvider.test.ts
```

In the moved `ThemeProvider.test.ts`, remove only the old `mainPath` constant and the test named `wraps the app once at the root`. The new app integration test owns that assertion. Keep the provider, persistence and hook assertions unchanged.

- [ ] **Step 5: Switch the app to the package public API**

Use these exact imports:

```ts
// apps/web/src/main.tsx
import { ThemeProvider } from '@fx-platform/ui'
import '@fx-platform/ui/theme.css'

// apps/web/src/app/AppShell.tsx
import { useTheme } from '@fx-platform/ui'

// apps/web/src/pages/trading/TradingPage.tsx
import { useTheme } from '@fx-platform/ui'
```

Delete the three old relative theme imports. Do not modify provider nesting or theme behavior.

- [ ] **Step 6: Add workspace scripts and refresh the lockfile**

Add to `fx-trading-platform/package.json`:

```json
"ui:test": "npm --workspace packages/ui run test",
"ui:typecheck": "npm --workspace packages/ui run typecheck"
```

Run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform install --package-lock-only --ignore-scripts"
```

Expected：`package-lock.json` contains a workspace entry for `packages/ui`; no lifecycle script runs.

- [ ] **Step 7: Run focused and application verification**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Expected：全部 PASS；Vite 能从 workspace package 解析 TS/TSX 和 CSS entry。

- [ ] **Step 8: Commit**

```powershell
git add fx-trading-platform/packages/ui fx-trading-platform/apps/web/src/main.tsx fx-trading-platform/apps/web/src/app/AppShell.tsx fx-trading-platform/apps/web/src/app/uiPackageIntegration.test.ts fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx fx-trading-platform/apps/web/src/design-system/theme fx-trading-platform/package.json fx-trading-platform/package-lock.json
git commit -m "refactor: extract shared theme package"
```

---

### Task 3: Move `SelectField` as the First Shared UI Component

**Files:**
- Move: `apps/web/src/components/SelectField.tsx` → `packages/ui/src/components/SelectField.tsx`
- Create: `packages/ui/src/components/SelectField.css`
- Create: `packages/ui/src/components/SelectField.test.ts`
- Modify: `packages/ui/src/index.ts`
- Modify: `packages/ui/package.json`
- Modify: `apps/web/src/components/LanguageSwitcher.tsx`
- Modify: `apps/web/src/pages/markets/MarketsPage.tsx`
- Modify: `apps/web/src/pages/userPages.test.ts`
- Modify: `apps/web/src/styles.css`
- Modify: `fx-trading-platform/package-lock.json`

**Interfaces:**
- Produces: `SelectField<T extends string>`
- Produces: `SelectFieldOption<T>` and `SelectFieldProps<T>`
- Preserves: keyboard navigation、listbox semantics、existing global class contract and current visual output

- [ ] **Step 1: Write the failing UI component contract test**

Create `packages/ui/src/components/SelectField.test.ts`:

```ts
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'SelectField.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'SelectField.css'), 'utf8')

describe('SelectField public component', () => {
  it('exports a typed controlled listbox contract', () => {
    assert.match(source, /export type SelectFieldOption/)
    assert.match(source, /export type SelectFieldProps/)
    assert.match(source, /export function SelectField/)
    assert.match(source, /role="listbox"/)
    assert.match(source, /role="option"/)
    assert.match(source, /aria-selected/)
  })

  it('supports keyboard operation and reduced motion', () => {
    for (const key of ['Escape', 'Tab', 'ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter']) {
      assert.match(source, new RegExp(key))
    }
    assert.match(styles, /prefers-reduced-motion/)
    assert.match(styles, /\.select-field\[data-open="true"\]/)
  })
})
```

- [ ] **Step 2: Run the focused test and verify it fails**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:test"
```

Expected：FAIL with `ENOENT` for `packages/ui/src/components/SelectField.tsx`.

- [ ] **Step 3: Move the component and expose its props**

```powershell
New-Item -ItemType Directory -Force fx-trading-platform/packages/ui/src/components | Out-Null
git mv fx-trading-platform/apps/web/src/components/SelectField.tsx fx-trading-platform/packages/ui/src/components/SelectField.tsx
```

Add this side-effect import after React/lucide imports:

```ts
import './SelectField.css'
```

Change the props declaration from `type SelectFieldProps` to:

```ts
export type SelectFieldProps<T extends string = string> = {
  ariaLabel?: string
  className?: string
  labelledBy?: string
  onChange: (value: T) => void
  options: readonly SelectFieldOption<T>[]
  value: T
}
```

- [ ] **Step 4: Move only the base SelectField CSS into the UI package**

Create `packages/ui/src/components/SelectField.css` with the existing `.select-field` through `.select-field__option svg` block from `apps/web/src/styles.css`, then append:

```css
@media (prefers-reduced-motion: reduce) {
  .select-field__button,
  .select-field__chevron,
  .select-field__menu,
  .select-field__option {
    transition: none;
    animation: none;
  }
}
```

Delete that base block and the SelectField entries in the global `prefers-reduced-motion` selector from `apps/web/src/styles.css`. Keep consumer overrides such as `.language-switcher .select-field` and `.market-sort-field .select-field` in the app stylesheet.

- [ ] **Step 5: Export and consume the public component**

Append to `packages/ui/src/index.ts`:

```ts
export { SelectField } from './components/SelectField'
export type { SelectFieldOption, SelectFieldProps } from './components/SelectField'
```

Add to `packages/ui/package.json` dependencies:

```json
"dependencies": {
  "lucide-react": "^0.468.0"
}
```

Replace the old relative imports in both consumers with:

```ts
import { SelectField, type SelectFieldOption } from '@fx-platform/ui'
```

- [ ] **Step 6: Point the existing prototype test at the package source**

In `apps/web/src/pages/userPages.test.ts`, add:

```ts
const platformRoot = join(pagesDir, '..', '..', '..', '..')
```

Replace the old SelectField path/CSS setup with:

```ts
const selectFieldPath = join(platformRoot, 'packages', 'ui', 'src', 'components', 'SelectField.tsx')
const selectField = existsSync(selectFieldPath) ? readFileSync(selectFieldPath, 'utf8') : ''
const selectFieldCss = readFileSync(join(platformRoot, 'packages', 'ui', 'src', 'components', 'SelectField.css'), 'utf8')
```

Keep `marketSortSelectCss` reading the app stylesheet because it is consumer-specific.

- [ ] **Step 7: Refresh dependencies and verify**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform install --package-lock-only --ignore-scripts"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run ui:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
```

Expected：全部 PASS；LanguageSwitcher 与 MarketsPage 外观和键盘行为不变。

- [ ] **Step 8: Commit**

```powershell
git add fx-trading-platform/packages/ui fx-trading-platform/apps/web/src/components/SelectField.tsx fx-trading-platform/apps/web/src/components/LanguageSwitcher.tsx fx-trading-platform/apps/web/src/pages/markets/MarketsPage.tsx fx-trading-platform/apps/web/src/pages/userPages.test.ts fx-trading-platform/apps/web/src/styles.css fx-trading-platform/package-lock.json
git commit -m "refactor: move select field into ui package"
```

---

### Task 4: Create `@fx-platform/frontend-core` and Move Models, Auth Storage, and API Clients

**Files:**
- Create: `packages/frontend-core/package.json`
- Create: `packages/frontend-core/tsconfig.json`
- Create: `packages/frontend-core/src/index.ts`
- Create: `packages/frontend-core/src/api/index.ts`
- Create: `packages/frontend-core/src/auth/index.ts`
- Create: `packages/frontend-core/src/models/index.ts`
- Move: `apps/web/src/types/trading.ts` → `packages/frontend-core/src/models/trading.ts`
- Move: `apps/web/src/components/tables/types.ts` → `packages/frontend-core/src/models/orderPosition.ts`
- Move: `apps/web/src/features/trading-session/tradingSessionStorage.ts` → `packages/frontend-core/src/auth/sessionStorage.ts`
- Move: every file under `apps/web/src/services/` → `packages/frontend-core/src/api/`
- Create: `apps/web/src/app/frontendCoreIntegration.test.ts`
- Modify: all application consumers listed below
- Modify: `scripts/verify-architecture.mjs`
- Modify: `fx-trading-platform/package.json`
- Modify: `fx-trading-platform/package-lock.json`

**Interfaces:**
- Produces: `@fx-platform/frontend-core/api`
- Produces: `@fx-platform/frontend-core/auth`
- Produces: `@fx-platform/frontend-core/models`
- Preserves: all existing function names, payload shapes, error behavior, token migration and STOMP topics

- [ ] **Step 1: Write the failing package integration test**

Create `apps/web/src/app/frontendCoreIntegration.test.ts`:

```ts
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const appDir = dirname(fileURLToPath(import.meta.url))
const platformRoot = join(appDir, '..', '..', '..', '..')
const coreRoot = join(platformRoot, 'packages', 'frontend-core')

describe('@fx-platform/frontend-core integration', () => {
  it('owns shared models, auth storage, API clients and streams', () => {
    for (const path of [
      'src/models/trading.ts',
      'src/models/orderPosition.ts',
      'src/auth/sessionStorage.ts',
      'src/api/apiClient.ts',
      'src/api/accountApi.ts',
      'src/api/authApi.ts',
      'src/api/marketStream.ts',
      'src/api/tradingApi.ts'
    ]) assert.equal(existsSync(join(coreRoot, path)), true, path)

    const packageJson = JSON.parse(readFileSync(join(coreRoot, 'package.json'), 'utf8'))
    const shell = readFileSync(join(appDir, 'AppShell.tsx'), 'utf8')
    assert.equal(packageJson.name, '@fx-platform/frontend-core')
    assert.match(shell, /@fx-platform\/frontend-core\/api/)
    assert.match(shell, /@fx-platform\/frontend-core\/auth/)
  })
})
```

- [ ] **Step 2: Run the test and verify it fails**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform --workspace apps/web run test -- src/app/frontendCoreIntegration.test.ts"
```

Expected：FAIL because `packages/frontend-core` does not exist.

- [ ] **Step 3: Create package metadata and public entry points**

Create `packages/frontend-core/package.json`:

```json
{
  "name": "@fx-platform/frontend-core",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "main": "src/index.ts",
  "types": "src/index.ts",
  "exports": {
    ".": { "types": "./src/index.ts", "import": "./src/index.ts" },
    "./api": { "types": "./src/api/index.ts", "import": "./src/api/index.ts" },
    "./auth": { "types": "./src/auth/index.ts", "import": "./src/auth/index.ts" },
    "./models": { "types": "./src/models/index.ts", "import": "./src/models/index.ts" }
  },
  "scripts": {
    "test": "node --test \"src/**/*.test.ts\"",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@fx-platform/shared-types": "0.1.0",
    "@stomp/stompjs": "^7.3.0"
  },
  "peerDependencies": {
    "react": "^19.0.0"
  },
  "devDependencies": {
    "@types/react": "^19.0.0",
    "typescript": "^5.8.3"
  }
}
```

Create `packages/frontend-core/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["DOM", "DOM.Iterable", "ES2022"],
    "strict": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "allowImportingTsExtensions": true,
    "isolatedModules": true,
    "noEmit": true
  },
  "include": ["src"],
  "exclude": ["src/**/*.test.ts"]
}
```

Create public indices:

```ts
// src/index.ts
export * from './api/index'
export * from './auth/index'
export * from './models/index'

// src/api/index.ts
export * from './accountApi'
export * from './apiClient'
export * from './authApi'
export * from './financeApi'
export * from './homeApi'
export * from './ledgerApi'
export * from './marketApi'
export * from './marketStream'
export * from './tradingApi'

// src/auth/index.ts
export * from './sessionStorage'

// src/models/index.ts
export * from './orderPosition'
export * from './trading'
```

- [ ] **Step 4: Move existing sources and tests**

Run exact moves:

```powershell
New-Item -ItemType Directory -Force fx-trading-platform/packages/frontend-core/src/api | Out-Null
New-Item -ItemType Directory -Force fx-trading-platform/packages/frontend-core/src/auth | Out-Null
New-Item -ItemType Directory -Force fx-trading-platform/packages/frontend-core/src/models | Out-Null
git mv fx-trading-platform/apps/web/src/types/trading.ts fx-trading-platform/packages/frontend-core/src/models/trading.ts
git mv fx-trading-platform/apps/web/src/components/tables/types.ts fx-trading-platform/packages/frontend-core/src/models/orderPosition.ts
git mv fx-trading-platform/apps/web/src/features/trading-session/tradingSessionStorage.ts fx-trading-platform/packages/frontend-core/src/auth/sessionStorage.ts
git mv fx-trading-platform/apps/web/src/services/* fx-trading-platform/packages/frontend-core/src/api/
```

Update internal imports inside the package:

| File | Old import | New import |
|---|---|---|
| `api/apiClient.ts` | `../features/trading-session/tradingSessionStorage.ts` | `../auth/sessionStorage.ts` |
| `api/accountApi.ts` | `../types/trading` | `../models/trading` |
| `api/financeApi.ts` | `../types/trading` | `../models/trading` |
| `api/ledgerApi.ts` | `../types/trading` | `../models/trading` |
| `api/marketApi.ts` | `../types/trading` | `../models/trading` |
| `api/marketStream.ts` | `../types/trading` | `../models/trading` |
| `api/tradingApi.ts` | `../types/trading` and `../components/tables/types` | `../models/trading` and `../models/orderPosition` |
| `models/orderPosition.ts` | `../../types/trading` | `./trading` |

Do not alter endpoint strings, request construction, retry logic, storage keys or event names.

- [ ] **Step 5: Update all application consumers to package subpaths**

Apply this deterministic import map throughout `apps/web/src`:

| Existing source | Replacement |
|---|---|
| any `services/accountApi`, `apiClient`, `authApi`, `financeApi`, `homeApi`, `ledgerApi`, `marketApi`, `marketStream`, `tradingApi` | named imports from `@fx-platform/frontend-core/api` |
| any `features/trading-session/tradingSessionStorage` | named imports from `@fx-platform/frontend-core/auth` |
| any `types/trading` or `components/tables/types` | type imports from `@fx-platform/frontend-core/models` |

The update must cover these consumer groups:

```text
apps/web/src/app/**
apps/web/src/components/**
apps/web/src/features/market/**
apps/web/src/features/trading/**
apps/web/src/features/trading-session/**
apps/web/src/pages/**
apps/web/src/stores/**
```

After edits, run:

```powershell
rg -n "src/services|services/(accountApi|apiClient|authApi|financeApi|homeApi|ledgerApi|marketApi|marketStream|tradingApi)|types/trading|components/tables/types|tradingSessionStorage" fx-trading-platform/apps/web/src -g "*.ts" -g "*.tsx"
```

Expected：only intentional source-reading paths in tests remain; update those in Step 6, then the command returns no matches.

- [ ] **Step 6: Update source-reading tests and architecture verification**

In `apps/web/src/pages/userPages.test.ts`, read these files from `platformRoot`:

```ts
const accountApi = readFileSync(join(platformRoot, 'packages', 'frontend-core', 'src', 'api', 'accountApi.ts'), 'utf8')
const tradingTypes = readFileSync(join(platformRoot, 'packages', 'frontend-core', 'src', 'models', 'trading.ts'), 'utf8')
```

In `features/trading-session/tradingSession.test.ts`, replace source/dynamic paths with:

```ts
new URL('../../../../../packages/frontend-core/src/api/apiClient.ts', import.meta.url)
new URL('../../../../../packages/frontend-core/src/api/authApi.ts', import.meta.url)
new URL('../../../../../packages/frontend-core/src/api/marketStream.ts', import.meta.url)
```

Update `components/user-page/userPageModels.test.ts` to import `ApiClientError` from `@fx-platform/frontend-core/api`.

In `scripts/verify-architecture.mjs`:

- replace required `apps/web/src/services/apiClient.ts` with `packages/frontend-core/src/api/apiClient.ts`;
- replace required `apps/web/src/services/marketStream.ts` with `packages/frontend-core/src/api/marketStream.ts`;
- replace all service content-check paths with their `packages/frontend-core/src/api/*` equivalents;
- move the “frontend must not call Massive directly” scan to `packages/frontend-core/src/api/marketApi.ts`.

- [ ] **Step 7: Add scripts, refresh lockfile, and verify**

Add to `fx-trading-platform/package.json`:

```json
"frontend-core:test": "npm --workspace packages/frontend-core run test",
"frontend-core:typecheck": "npm --workspace packages/frontend-core run typecheck"
```

Run:

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform install --package-lock-only --ignore-scripts"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Expected：全部 PASS；`apps/web/src/services`、`apps/web/src/types/trading.ts` 和 `apps/web/src/components/tables/types.ts` 不再存在。

- [ ] **Step 8: Commit**

```powershell
git add fx-trading-platform/packages/frontend-core fx-trading-platform/apps/web/src fx-trading-platform/scripts/verify-architecture.mjs fx-trading-platform/package.json fx-trading-platform/package-lock.json
git commit -m "refactor: extract frontend api and models"
```

---

### Task 5: Move the Shared Market Runtime into `frontend-core`

**Files:**
- Move: `apps/web/src/features/market/` → `packages/frontend-core/src/market/`
- Create: `packages/frontend-core/src/market/index.ts`
- Modify: `packages/frontend-core/src/index.ts`
- Modify: `packages/frontend-core/package.json`
- Modify: all market consumers in `apps/web/src`
- Modify: `apps/web/src/pages/userPages.test.ts`
- Modify: `scripts/verify-architecture.mjs`

**Interfaces:**
- Produces: `@fx-platform/frontend-core/market`
- Preserves: market models、favorites、snapshot、provider status、HTTP calls、STOMP subscriptions and fallback semantics
- Consumes: `frontend-core/api`、`frontend-core/auth`、React peer

- [ ] **Step 1: Add a failing market ownership assertion**

Append to `apps/web/src/app/frontendCoreIntegration.test.ts`:

```ts
it('owns the complete shared market runtime', () => {
  for (const path of [
    'src/market/tradingModels.ts',
    'src/market/tradingMarketApi.ts',
    'src/market/tradingMarketAdapters.ts',
    'src/market/marketDataStore.ts',
    'src/market/quoteMarketDataAdapter.ts',
    'src/market/useMarketFavorites.ts'
  ]) assert.equal(existsSync(join(coreRoot, path)), true, path)
})
```

- [ ] **Step 2: Run the focused test and verify it fails**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform --workspace apps/web run test -- src/app/frontendCoreIntegration.test.ts"
```

Expected：FAIL on the first missing `src/market/*` file.

- [ ] **Step 3: Move the complete market directory**

```powershell
git mv fx-trading-platform/apps/web/src/features/market fx-trading-platform/packages/frontend-core/src/market
```

Update only the four cross-module imports:

| Moved file | New import |
|---|---|
| `binanceMarketData.ts` | `apiGet` from `../api/apiClient.ts` |
| `quoteMarketDataAdapter.ts` | stream functions from `../api/marketStream` |
| `tradingMarketApi.ts` | `apiGet`, `apiPut` from `../api/apiClient` |
| `useMarketFavorites.ts` | `readStoredAuthToken` from `../auth/sessionStorage` |

All imports between market files remain relative and unchanged.

- [ ] **Step 4: Create the market public API**

Create `packages/frontend-core/src/market/index.ts`:

```ts
export * from './binanceMarketData'
export * from './marketDataStore'
export * from './marketDataTypes'
export * from './marketFavorites'
export * from './marketSnapshot'
export * from './mockTradingData'
export * from './quoteMarketDataAdapter'
export * from './quoteMarketDataSnapshot'
export * from './tradingMarketAdapters'
export * from './tradingMarketApi'
export * from './tradingModels'
export * from './useMarketFavorites'
```

Add this subpath to `packages/frontend-core/package.json` under `exports`:

```json
"./market": {
  "types": "./src/market/index.ts",
  "import": "./src/market/index.ts"
}
```

Add the required comma after the preceding `./models` export entry so the JSON remains valid.

Append to `packages/frontend-core/src/index.ts`:

```ts
export * from './market/index'
```

- [ ] **Step 5: Update all application market imports**

For every import whose source resolves inside the former `apps/web/src/features/market` directory, preserve its existing named specifiers verbatim and change only the module source string to `@fx-platform/frontend-core/market`.

This includes:

```text
apps/web/src/components/market-side-panel/**
apps/web/src/features/trading/**
apps/web/src/pages/home/components/MarketPreviewPanel.tsx
apps/web/src/pages/markets/MarketsPage.tsx
apps/web/src/pages/trading/**
```

Update test type imports in:

```text
pages/trading/chartCandleData.test.ts
pages/trading/marketSnapshotAvailability.test.ts
pages/trading/tradingPageTradeRules.test.ts
pages/trading/mobile/mobileTradingPresentation.test.ts
pages/trading/useTradingQuotes.test.ts
```

All five use `@fx-platform/frontend-core/market`; keep `.ts` extensions only for relative imports, not package imports.

- [ ] **Step 6: Update source-reading tests and architecture paths**

In `pages/userPages.test.ts`:

```ts
const binanceMarketData = readFileSync(
  join(platformRoot, 'packages', 'frontend-core', 'src', 'market', 'binanceMarketData.ts'),
  'utf8'
)
```

In `scripts/verify-architecture.mjs`, replace required/content-check paths:

```text
apps/web/src/features/market/tradingMarketApi.ts
apps/web/src/features/market/tradingMarketAdapters.ts
apps/web/src/features/market/tradingModels.ts
```

with:

```text
packages/frontend-core/src/market/tradingMarketApi.ts
packages/frontend-core/src/market/tradingMarketAdapters.ts
packages/frontend-core/src/market/tradingModels.ts
```

The existing forbidden page import checks for `components/market-side-panel` remain active.

- [ ] **Step 7: Prove the package no longer depends on the app**

Run:

```powershell
rg -n "apps/web|\.\./\.\./apps|\.\./\.\./\.\./apps" fx-trading-platform/packages/frontend-core/src -g "*.ts" -g "*.tsx"
rg -n "features/market" fx-trading-platform/apps/web/src -g "*.ts" -g "*.tsx"
```

Expected：both commands return no matches.

- [ ] **Step 8: Run market, package and app tests**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
```

Expected：全部 PASS；行情 adapter/store 测试现在由 `frontend-core:test` 执行。

- [ ] **Step 9: Commit**

```powershell
git add fx-trading-platform/packages/frontend-core/src/market fx-trading-platform/packages/frontend-core/src/index.ts fx-trading-platform/apps/web/src fx-trading-platform/scripts/verify-architecture.mjs
git commit -m "refactor: move market runtime into frontend core"
```

---

### Task 6: Extract Pure Table Models and Close the First Public-Core Slice

**Files:**
- Create: `packages/frontend-core/src/models/tableModels.ts`
- Create: `packages/frontend-core/src/models/tableModels.test.ts`
- Modify: `packages/frontend-core/src/models/index.ts`
- Modify: `apps/web/src/components/user-page/userPageModels.ts`
- Modify: `apps/web/src/components/user-page/userPageModels.test.ts`

**Interfaces:**
- Produces: `SortDirection`、`filterByStatus`、`sortRows`、`paginateRows`、`toNumber`
- Keeps in app: translated `formatApiError` and `ApiErrorView`

- [ ] **Step 1: Write the failing pure-model test**

Create `packages/frontend-core/src/models/tableModels.test.ts`:

```ts
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { filterByStatus, paginateRows, sortRows } from './tableModels.ts'

const rows = [
  { id: '1', status: 'FILLED', amount: '10', createdAt: '2026-06-12T01:00:00Z' },
  { id: '2', status: 'PENDING', amount: '25', createdAt: '2026-06-12T03:00:00Z' },
  { id: '3', status: 'CANCELED', amount: '5', createdAt: '2026-06-12T02:00:00Z' }
]

describe('shared table models', () => {
  it('filters exact statuses and keeps ALL unchanged', () => {
    assert.deepEqual(filterByStatus(rows, 'ALL').map((row) => row.id), ['1', '2', '3'])
    assert.deepEqual(filterByStatus(rows, 'PENDING').map((row) => row.id), ['2'])
  })

  it('sorts numeric strings and timestamps without mutation', () => {
    assert.deepEqual(sortRows(rows, 'amount', 'asc').map((row) => row.id), ['3', '1', '2'])
    assert.deepEqual(sortRows(rows, 'createdAt', 'desc').map((row) => row.id), ['2', '3', '1'])
    assert.deepEqual(rows.map((row) => row.id), ['1', '2', '3'])
  })

  it('clamps pages and reports stable metadata', () => {
    assert.deepEqual(paginateRows(rows, 99, 2), {
      items: rows.slice(2),
      page: 2,
      pageSize: 2,
      total: 3,
      totalPages: 2
    })
  })
})
```

- [ ] **Step 2: Run and verify failure**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:test"
```

Expected：FAIL with `ERR_MODULE_NOT_FOUND` for `tableModels.ts`.

- [ ] **Step 3: Move the exact pure implementation**

Create `packages/frontend-core/src/models/tableModels.ts` by moving these declarations unchanged from `apps/web/src/components/user-page/userPageModels.ts`:

```text
SortDirection
filterByStatus
sortRows
paginateRows
toNumber
compareValues
toTimestamp
```

`compareValues` and `toTimestamp` remain private. The file has no imports.

Append to `packages/frontend-core/src/models/index.ts`:

```ts
export * from './tableModels'
```

Replace the removed declarations in the app file with:

```ts
export { filterByStatus, paginateRows, sortRows, toNumber } from '@fx-platform/frontend-core/models'
export type { SortDirection } from '@fx-platform/frontend-core/models'
```

Keep `ApiClientError`, `t`, `ApiErrorView` and `formatApiError` in the app file.

- [ ] **Step 4: Split the existing test by responsibility**

In `userPageModels.test.ts`:

- import `filterByStatus`, `paginateRows`, `sortRows` from `@fx-platform/frontend-core/models`;
- import only `formatApiError` from `./userPageModels.ts`;
- keep all four current tests; the first three now exercise the package and the fourth exercises the translated app adapter.

- [ ] **Step 5: Verify and commit**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend-core:typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"
git add fx-trading-platform/packages/frontend-core/src/models fx-trading-platform/apps/web/src/components/user-page/userPageModels.ts fx-trading-platform/apps/web/src/components/user-page/userPageModels.test.ts
git commit -m "refactor: extract shared table models"
```

Expected：all commands PASS and the commit contains only this task.

---

### Task 7: Add the Unified Frontend Gate and Document the New Foundation

**Files:**
- Modify: `fx-trading-platform/package.json`
- Modify: `fx-trading-platform/docs/architecture.md`
- Modify: `fx-trading-platform/scripts/verify-architecture.mjs`

**Interfaces:**
- Produces: `npm run frontend:check`
- Documents: package ownership, import flow, migration status and next subproject

- [ ] **Step 1: Add a failing script-contract assertion**

Extend `scripts/verify-frontend-boundaries.test.mjs` with:

```js
it('is wired into the platform frontend check', () => {
  const packageJson = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
  assert.match(packageJson.scripts['frontend:check'], /ui:test/)
  assert.match(packageJson.scripts['frontend:check'], /frontend-core:test/)
  assert.match(packageJson.scripts['frontend:check'], /web:test/)
  assert.match(packageJson.scripts['frontend:check'], /web:build/)
  assert.match(packageJson.scripts['frontend:check'], /verify:architecture/)
})
```

Also add `readFileSync` to that test file's `node:fs` import.

- [ ] **Step 2: Run and verify the new assertion fails**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run test:frontend-boundaries"
```

Expected：FAIL because `frontend:check` is not defined.

- [ ] **Step 3: Add the unified command**

Add to `fx-trading-platform/package.json`:

```json
"frontend:check": "npm run ui:test && npm run ui:typecheck && npm run frontend-core:test && npm run frontend-core:typecheck && npm run web:test && npm run web:build && npm run verify:architecture"
```

- [ ] **Step 4: Update architecture documentation**

Add a Web frontend section to `docs/architecture.md` containing this exact dependency flow:

```text
apps/web -> @fx-platform/ui
apps/web -> @fx-platform/frontend-core
@fx-platform/frontend-core -> @fx-platform/shared-types
@fx-platform/ui -> React peer
```

Document:

- theme and `SelectField` ownership in `packages/ui`;
- models/API/auth/market ownership in `packages/frontend-core`;
- `scripts/verify-frontend-boundaries.mjs` as the enforcement point;
- `npm run frontend:check` as the required static gate;
- trading controller extraction as the next subproject;
- PC/Mobile UI selection is not yet enabled at the end of this plan.

- [ ] **Step 5: Run final static and bundle verification**

```powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run frontend:check"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run admin:build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:bundle-budget"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run audit:large-files"
```

Expected：all commands exit `0`.

- [ ] **Step 6: Verify ownership and forbidden imports explicitly**

```powershell
rg -n "react-router-dom|@fx-platform/ui|apps/web|\.(css|scss|less)'" fx-trading-platform/packages/frontend-core/src -g "*.ts" -g "*.tsx"
rg -n "@fx-platform/frontend-core|apps/web" fx-trading-platform/packages/ui/src -g "*.ts" -g "*.tsx"
rg -n "src/services|features/market|design-system/theme|components/SelectField" fx-trading-platform/apps/web/src -g "*.ts" -g "*.tsx"
```

Expected：all three commands return no forbidden matches. Imports of React inside `ui`, React inside market store/hooks, and `shared-types` inside core are permitted and are not part of these expressions.

- [ ] **Step 7: Commit**

```powershell
git add fx-trading-platform/package.json fx-trading-platform/docs/architecture.md fx-trading-platform/scripts/verify-architecture.mjs fx-trading-platform/scripts/verify-frontend-boundaries.test.mjs
git commit -m "docs: close shared frontend foundation"
```

## Foundation Definition of Done

- [ ] `packages/ui` and `packages/frontend-core` are real workspace packages consumed by `apps/web`.
- [ ] Theme and `SelectField` no longer live under `apps/web`.
- [ ] Shared models, token storage, HTTP clients, STOMP client and market runtime no longer live under `apps/web`.
- [ ] `apps/web` contains no duplicate implementation or compatibility re-export for the moved slices.
- [ ] Boundary verifier rejects all forbidden dependency directions.
- [ ] `frontend:check`, bundle budget and large-file audit pass.
- [ ] Admin build continues to pass after workspace and lockfile changes.
- [ ] Current routes, visuals, authentication, quotes, orders, positions and wallet behavior remain unchanged.
- [ ] No backend file, API contract or execution mode was modified.

## Follow-up Plan Boundaries

After this plan passes, create the next plans in this order; do not combine them into one implementation session:

1. `trading-controller-extraction-implementation.md`: move trading session, trade form, validation, submission adapter and page view-model into core while removing i18n/UI dependencies.
2. `adaptive-web-runtime-implementation.md`: add `useDeviceClass`, route adapters, lazy platform views and distinct PC/Mobile shells.
3. One plan per route group: auth/home, account/settings, market/orders/positions/wallet, trading terminal.
4. `legacy-web-ui-cleanup-implementation.md`: delete compatibility CSS/components and close final bundle/visual gates.

The copy-ready commands for new sessions are stored beside this plan in `2026-07-15-web-pc-mobile-ui-separation-new-session-commands.md`.
