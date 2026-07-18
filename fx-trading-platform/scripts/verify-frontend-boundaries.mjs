import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, extname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SOURCE_EXTENSIONS = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs'])
const IGNORED_DIRECTORIES = new Set(['coverage', 'dist', 'node_modules'])
const IMPORT_PATTERNS = [
  /\b(?:import|export)\s+(?:type\s+)?(?:[^'";]*?\s+from\s*)?['"]([^'"]+)['"]/gu,
  /\bimport\s*\(\s*['"]([^'"]+)['"]\s*\)/gu
]

export function findFrontendBoundaryViolations(rootDir) {
  const absoluteRoot = resolve(rootDir)
  const violations = []
  const scanRoots = [
    resolve(absoluteRoot, 'packages/ui/src'),
    resolve(absoluteRoot, 'packages/frontend-core/src'),
    resolve(absoluteRoot, 'apps/web/src')
  ]

  for (const scanRoot of scanRoots) {
    if (!existsSync(scanRoot)) continue
    for (const file of collectSourceFiles(scanRoot)) {
      const source = normalizePath(relative(absoluteRoot, file))
      const content = readFileSync(file, 'utf8')
      for (const specifier of readImportSpecifiers(content)) {
        const violation = inspectImport({ absoluteRoot, file, source, specifier })
        if (violation) violations.push(violation)
      }
    }
  }

  return [...new Set(violations)].sort()
}

function collectSourceFiles(directory) {
  const files = []
  for (const entry of readdirSync(directory)) {
    if (IGNORED_DIRECTORIES.has(entry)) continue
    const target = resolve(directory, entry)
    const stat = statSync(target)
    if (stat.isDirectory()) {
      files.push(...collectSourceFiles(target))
      continue
    }
    if (!SOURCE_EXTENSIONS.has(extname(entry)) || entry.endsWith('.d.ts')) continue
    files.push(target)
  }
  return files
}

function readImportSpecifiers(content) {
  const specifiers = []
  for (const pattern of IMPORT_PATTERNS) {
    pattern.lastIndex = 0
    for (const match of content.matchAll(pattern)) {
      specifiers.push(match[1])
    }
  }
  return specifiers
}

function inspectImport({ absoluteRoot, file, source, specifier }) {
  const normalizedSpecifier = normalizePath(specifier)
  const target = describeTarget(absoluteRoot, file, normalizedSpecifier)

  if (/^@fx-platform\/(?:ui|frontend-core)\/src(?:\/|$)/u.test(normalizedSpecifier)) {
    return formatViolation(source, specifier, normalizedSpecifier, 'package deep import')
  }

  const sourceLayer = classifyLayer(source)
  const targetLayer = classifyLayer(target)

  if (sourceLayer === 'ui') {
    if (!isAllowedUiImport(source, normalizedSpecifier, target)) {
      return formatViolation(source, specifier, target, 'ui dependency')
    }
  } else if (sourceLayer === 'frontend-core') {
    if (!isAllowedCoreImport(source, normalizedSpecifier, target)) {
      return formatViolation(source, specifier, target, 'frontend-core dependency')
    }
  } else if (sourceLayer === 'pc' && targetLayer === 'mobile') {
    return formatViolation(source, specifier, target, 'pc to mobile dependency')
  } else if (sourceLayer === 'mobile' && targetLayer === 'pc') {
    return formatViolation(source, specifier, target, 'mobile to pc dependency')
  } else if (sourceLayer === 'shared-widgets' && (targetLayer === 'pc' || targetLayer === 'mobile')) {
    return formatViolation(source, specifier, target, 'shared widget to platform dependency')
  }

  return null
}

function isAllowedUiImport(source, specifier, target) {
  if (specifier.startsWith('.')) return target.startsWith('packages/ui/src/')
  if (isNodeTestImport(source, specifier)) return true
  return isPackageImport(specifier, 'react')
    || isPackageImport(specifier, 'react-dom')
    || isPackageImport(specifier, 'lucide-react')
    || isPackageImport(specifier, '@fx-platform/ui')
}

function isAllowedCoreImport(source, specifier, target) {
  if (specifier.endsWith('.css')) return false
  if (specifier.startsWith('.')) return target.startsWith('packages/frontend-core/src/')
  if (isNodeTestImport(source, specifier)) return true
  return isPackageImport(specifier, 'react')
    || isPackageImport(specifier, 'zustand')
    || isPackageImport(specifier, '@stomp/stompjs')
    || isPackageImport(specifier, '@fx-platform/shared-types')
    || isPackageImport(specifier, '@fx-platform/frontend-core')
}

function isNodeTestImport(source, specifier) {
  return source.includes('.test.') && specifier.startsWith('node:')
}

function isPackageImport(specifier, packageName) {
  return specifier === packageName || specifier.startsWith(`${packageName}/`)
}

function describeTarget(absoluteRoot, file, specifier) {
  if (!specifier.startsWith('.')) return specifier
  const target = resolve(dirname(file), ...specifier.split('/'))
  return normalizePath(relative(absoluteRoot, target))
}

function classifyLayer(path) {
  if (path.startsWith('packages/ui/src/')) return 'ui'
  if (path.startsWith('packages/frontend-core/src/')) return 'frontend-core'
  if (path.startsWith('apps/web/src/pc/')) return 'pc'
  if (path.startsWith('apps/web/src/mobile/')) return 'mobile'
  if (path.startsWith('apps/web/src/shared-widgets/')) return 'shared-widgets'
  if (path.startsWith('apps/web/src/')) return 'web'
  if (path.startsWith('apps/')) return 'app'
  return 'external'
}

function formatViolation(source, specifier, target, rule) {
  return `${source}: forbidden ${rule} import "${specifier}" -> ${target}`
}

function normalizePath(path) {
  return path.replaceAll('\\', '/')
}

function runCli() {
  const root = fileURLToPath(new URL('..', import.meta.url))
  const violations = findFrontendBoundaryViolations(root)
  if (violations.length > 0) {
    console.error('Frontend boundary verification failed:')
    for (const violation of violations) console.error(`- ${violation}`)
    process.exitCode = 1
    return
  }
  console.log('Frontend boundary verification passed.')
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : ''
if (invokedPath === resolve(fileURLToPath(import.meta.url))) runCli()
