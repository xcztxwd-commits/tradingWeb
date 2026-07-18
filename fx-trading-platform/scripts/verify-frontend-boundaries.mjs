import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, extname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SOURCE_EXTENSIONS = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs'])
const IGNORED_DIRECTORIES = new Set(['coverage', 'dist', 'node_modules'])

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
  let index = 0
  let canStartRegex = true

  while (index < content.length) {
    const character = content[index]

    if (/\s/u.test(character)) {
      index += 1
      continue
    }
    if (character === '/' && content[index + 1] === '/') {
      index = skipLineComment(content, index + 2)
      continue
    }
    if (character === '/' && content[index + 1] === '*') {
      index = skipBlockComment(content, index + 2)
      continue
    }
    if (character === '/' && canStartRegex) {
      index = skipRegexLiteral(content, index + 1)
      canStartRegex = false
      continue
    }
    if (character === "'" || character === '"') {
      index = readQuotedString(content, index).end
      canStartRegex = false
      continue
    }
    if (character === '`') {
      index = skipTemplateLiteral(content, index + 1)
      canStartRegex = false
      continue
    }
    if (isIdentifierStart(character)) {
      const token = readIdentifier(content, index)
      if (token.value === 'import') {
        const result = readImportDeclaration(content, token.end)
        if (result.specifier) specifiers.push(result.specifier)
      } else if (token.value === 'export') {
        const result = readExportDeclaration(content, token.end)
        if (result.specifier) specifiers.push(result.specifier)
      }
      canStartRegex = keywordAllowsRegexAfter(token.value)
      index = token.end
      continue
    }

    canStartRegex = ![')', ']', '}'].includes(character)
    index += 1
  }

  return specifiers
}

function readImportDeclaration(content, start) {
  let index = skipTrivia(content, start)
  if (content[index] === '.') return { end: index + 1, specifier: null }
  if (content[index] === '(') {
    index = skipTrivia(content, index + 1)
    return readModuleSpecifier(content, index)
  }
  if (content[index] === "'" || content[index] === '"') {
    return readModuleSpecifier(content, index)
  }

  let depth = 0
  while (index < content.length) {
    index = skipTrivia(content, index)
    const character = content[index]
    if (character === "'" || character === '"') {
      index = readQuotedString(content, index).end
      continue
    }
    if (isIdentifierStart(character)) {
      const token = readIdentifier(content, index)
      if (depth === 0 && token.value === 'from') {
        return readModuleSpecifier(content, skipTrivia(content, token.end))
      }
      index = token.end
      continue
    }
    if (character === '{' || character === '[' || character === '(') depth += 1
    if (character === '}' || character === ']' || character === ')') depth = Math.max(0, depth - 1)
    if (depth === 0 && character === ';') break
    index += 1
  }
  return { end: index, specifier: null }
}

function readExportDeclaration(content, start) {
  let index = skipTrivia(content, start)
  if (content.startsWith('type', index) && !isIdentifierPart(content[index + 4])) {
    index = skipTrivia(content, index + 4)
  }
  if (content[index] !== '*' && content[index] !== '{') {
    return { end: index, specifier: null }
  }

  let depth = 0
  while (index < content.length) {
    index = skipTrivia(content, index)
    const character = content[index]
    if (isIdentifierStart(character)) {
      const token = readIdentifier(content, index)
      if (depth === 0 && token.value === 'from') {
        return readModuleSpecifier(content, skipTrivia(content, token.end))
      }
      index = token.end
      continue
    }
    if (character === '{') depth += 1
    if (character === '}') depth = Math.max(0, depth - 1)
    if (depth === 0 && (character === ';' || character === '\n' || character === '\r')) break
    index += 1
  }
  return { end: index, specifier: null }
}

function readModuleSpecifier(content, index) {
  if (content[index] !== "'" && content[index] !== '"') {
    return { end: index, specifier: null }
  }
  const quoted = readQuotedString(content, index)
  return { end: quoted.end, specifier: quoted.value }
}

function readQuotedString(content, start) {
  const quote = content[start]
  let value = ''
  let index = start + 1
  while (index < content.length) {
    const character = content[index]
    if (character === '\\') {
      value += character
      if (index + 1 < content.length) value += content[index + 1]
      index += 2
      continue
    }
    if (character === quote) return { end: index + 1, value }
    value += character
    index += 1
  }
  return { end: index, value }
}

function skipTrivia(content, start) {
  let index = start
  while (index < content.length) {
    if (/\s/u.test(content[index])) {
      index += 1
    } else if (content[index] === '/' && content[index + 1] === '/') {
      index = skipLineComment(content, index + 2)
    } else if (content[index] === '/' && content[index + 1] === '*') {
      index = skipBlockComment(content, index + 2)
    } else {
      break
    }
  }
  return index
}

function skipLineComment(content, start) {
  const newline = content.indexOf('\n', start)
  return newline === -1 ? content.length : newline + 1
}

function skipBlockComment(content, start) {
  const end = content.indexOf('*/', start)
  return end === -1 ? content.length : end + 2
}

function skipTemplateLiteral(content, start) {
  let index = start
  while (index < content.length) {
    if (content[index] === '\\') {
      index += 2
    } else if (content[index] === '`') {
      return index + 1
    } else {
      index += 1
    }
  }
  return index
}

function skipRegexLiteral(content, start) {
  let index = start
  let inCharacterClass = false
  while (index < content.length) {
    const character = content[index]
    if (character === '\\') {
      index += 2
    } else if (character === '[') {
      inCharacterClass = true
      index += 1
    } else if (character === ']') {
      inCharacterClass = false
      index += 1
    } else if (character === '/' && !inCharacterClass) {
      index += 1
      while (/[a-z]/iu.test(content[index] ?? '')) index += 1
      return index
    } else {
      index += 1
    }
  }
  return index
}

function readIdentifier(content, start) {
  let end = start + 1
  while (isIdentifierPart(content[end])) end += 1
  return { end, value: content.slice(start, end) }
}

function isIdentifierStart(character = '') {
  return /[A-Za-z_$]/u.test(character)
}

function isIdentifierPart(character = '') {
  return /[A-Za-z0-9_$]/u.test(character)
}

function keywordAllowsRegexAfter(keyword) {
  return ['await', 'case', 'delete', 'do', 'else', 'in', 'instanceof', 'new', 'of', 'return', 'throw', 'typeof', 'void', 'yield'].includes(keyword)
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
