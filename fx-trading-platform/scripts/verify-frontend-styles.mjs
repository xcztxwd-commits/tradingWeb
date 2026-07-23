import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const GLOBAL_STYLES_MAX_LINES = 400
const GLOBAL_CLASS_ALLOWLIST = new Set(['.sr-only'])
const THEME_CSS = 'packages/ui/src/theme/theme.css'
const directColorPattern = /#[0-9a-f]{3,8}\b|\b(?:rgb|rgba|hsl|hsla)\s*\([^)]*\)/giu
const referencePalettePattern = /var\(\s*(--theme-reference-[a-z0-9-]+)\b/giu
const retiredBreakpointPattern = /@media[^\{]*\((?:max-width\s*:\s*768px|min-width\s*:\s*769px)\)/giu
const blockGlobalPattern = /:global\s*\{/gu
const globalClassBridgePattern = /:global\(\s*(\.[_a-zA-Z][_a-zA-Z0-9-]*)/gu

export function findFrontendStyleViolations(rootDir) {
  const root = resolve(rootDir)
  const violations = []
  const globalStylesPath = resolve(root, 'apps/web/src/styles.css')

  if (existsSync(globalStylesPath)) {
    const content = readFileSync(globalStylesPath, 'utf8')
    const lineCount = content.split(/\r?\n/u).length
    if (lineCount > GLOBAL_STYLES_MAX_LINES) {
      violations.push(`apps/web/src/styles.css has ${lineCount} lines; maximum is ${GLOBAL_STYLES_MAX_LINES}`)
    }
    for (const className of readClassSelectors(content)) {
      if (!GLOBAL_CLASS_ALLOWLIST.has(className)) {
        violations.push(`apps/web/src/styles.css contains route/component class selector ${className}`)
      }
    }
  }

  for (const scanRoot of [resolve(root, 'apps/web/src'), resolve(root, 'packages/ui/src')]) {
    if (!existsSync(scanRoot)) continue
    for (const file of collectCssFiles(scanRoot)) {
      const source = normalizePath(relative(root, file))
      const content = stripComments(readFileSync(file, 'utf8'))
      for (const match of content.matchAll(blockGlobalPattern)) {
        const line = content.slice(0, match.index).split(/\r?\n/u).length
        violations.push(`${source}:${line} contains block-form :global; use local CSS module classes`)
      }
      for (const match of content.matchAll(globalClassBridgePattern)) {
        const line = content.slice(0, match.index).split(/\r?\n/u).length
        violations.push(`${source}:${line} contains global class bridge ${match[1]}; use a local CSS module class`)
      }
      if (source !== THEME_CSS) {
        for (const match of content.matchAll(directColorPattern)) {
          const line = content.slice(0, match.index).split(/\r?\n/u).length
          violations.push(`${source}:${line} contains direct color literal ${match[0]}`)
        }
      }
      for (const match of content.matchAll(referencePalettePattern)) {
        const line = content.slice(0, match.index).split(/\r?\n/u).length
        violations.push(`${source}:${line} contains reference palette token ${match[1]}; use a semantic theme token`)
      }
      for (const match of content.matchAll(retiredBreakpointPattern)) {
        const line = content.slice(0, match.index).split(/\r?\n/u).length
        violations.push(`${source}:${line} contains retired 768/769 breakpoint ${match[0]}`)
      }
    }
  }

  return [...new Set(violations)].sort()
}

function collectCssFiles(directory) {
  const files = []
  for (const entry of readdirSync(directory)) {
    if (entry === 'coverage' || entry === 'dist' || entry === 'node_modules') continue
    const target = resolve(directory, entry)
    const stat = statSync(target)
    if (stat.isDirectory()) files.push(...collectCssFiles(target))
    else if (entry.endsWith('.css')) files.push(target)
  }
  return files.sort()
}

function readClassSelectors(content) {
  const classNames = new Set()
  const withoutComments = stripComments(content)
  for (const match of withoutComments.matchAll(/([^{}]+)\{/gu)) {
    const prelude = match[1].trim()
    if (prelude.startsWith('@') || /^\d+(?:\.\d+)?%$|^(?:from|to)$/u.test(prelude)) continue
    for (const classMatch of prelude.matchAll(/\.[_a-zA-Z][_a-zA-Z0-9-]*/gu)) classNames.add(classMatch[0])
  }
  return [...classNames].sort()
}

function stripComments(content) {
  return content.replace(/\/\*[\s\S]*?\*\//gu, '')
}

function normalizePath(path) {
  return path.replaceAll('\\', '/')
}

const entryPath = process.argv[1] ? resolve(process.argv[1]) : ''
if (entryPath === resolve(fileURLToPath(import.meta.url))) {
  const projectRoot = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/u, '')
  const violations = findFrontendStyleViolations(projectRoot)
  if (violations.length > 0) {
    console.error('Frontend style verification failed:')
    for (const violation of violations) console.error(`- ${violation}`)
    process.exit(1)
  }
  console.log('Frontend style verification passed.')
}
