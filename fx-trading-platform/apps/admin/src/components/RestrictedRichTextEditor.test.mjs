import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const adminDir = join(currentDir, '..', '..')
const workspaceDir = join(adminDir, '..', '..')

const readSource = (relativePath) => {
  const absolutePath = join(currentDir, relativePath)
  return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}

const componentSource = readSource('RestrictedRichTextEditor.tsx')
const extensionSource = readSource('restrictedRichTextExtensions.ts')
const stylesSource = readSource('RestrictedRichTextEditor.css')
const adminPackage = JSON.parse(readFileSync(join(adminDir, 'package.json'), 'utf8'))
const packageLockSource = readFileSync(join(workspaceDir, 'package-lock.json'), 'utf8')

const TIPTAP_DEPENDENCIES = [
  '@tiptap/core',
  '@tiptap/react',
  '@tiptap/pm',
  '@tiptap/starter-kit',
  '@tiptap/extension-text-style',
  '@tiptap/extension-text-align'
]

describe('RestrictedRichTextEditor dependency boundary', () => {
  it('pins only the approved MIT Tiptap core packages to 3.23.6', () => {
    for (const dependency of TIPTAP_DEPENDENCIES) {
      assert.equal(adminPackage.dependencies?.[dependency], '3.23.6', `${dependency} must be pinned exactly`)
      assert.match(packageLockSource, new RegExp(`"${dependency.replace('/', '\\/')}"\\s*:\\s*"3\\.23\\.6"`))
    }

    const installedTiptapPackages = Object.keys(adminPackage.dependencies ?? {}).filter((name) => name.startsWith('@tiptap/'))
    assert.deepEqual(installedTiptapPackages.toSorted(), TIPTAP_DEPENDENCIES.toSorted())
    assert.doesNotMatch(packageLockSource, /@tiptap\/(?:cli|cloud|pro|ui-components|extension-image)/i)
  })

  it('keeps the heavy editor directly lazy-importable without a barrel export', () => {
    assert.match(componentSource, /export default function RestrictedRichTextEditor/)
    assert.match(componentSource, /from '@tiptap\/react'/)
    assert.doesNotMatch(componentSource, /from ['"]\.['"]|from ['"]\.\/index['"]/)
  })
})

describe('RestrictedRichTextEditor rendering and state contract', () => {
  it('renders a Tiptap contenteditable surface and never falls back to a textarea or raw HTML', () => {
    assert.match(componentSource, /<EditorContent\s+editor=\{editor\}/)
    assert.match(componentSource, /class:\s*'restricted-rich-text-editor__content'/)
    assert.match(stylesSource, /\.restricted-rich-text-editor__content/)
    assert.doesNotMatch(componentSource, /<textarea|dangerouslySetInnerHTML|getHTML\(|rawHtml/i)
  })

  it('emits editor JSON as the only authoritative change payload', () => {
    assert.match(componentSource, /onUpdate:\s*\(\{ editor: updatedEditor \}\)/)
    assert.match(componentSource, /updatedEditor\.getJSON\(\)/)
    assert.match(componentSource, /onChangeRef\.current\(document\)/)
  })

  it('provides the required restricted toolbar commands and a fixed color palette', () => {
    for (const command of [
      'setParagraph',
      'toggleHeading',
      'toggleBold',
      'toggleItalic',
      'toggleBulletList',
      'toggleOrderedList',
      'setColor',
      'setTextAlign',
      'undo',
      'redo'
    ]) {
      assert.match(componentSource, new RegExp(`\\.${command}\\(`), `${command} must be wired to the toolbar`)
    }
    assert.match(componentSource, /const RESTRICTED_TEXT_COLORS = \[[^\]]+\] as const/s)
    assert.doesNotMatch(componentSource, /<input[^>]+type=["']color["']/i)
  })

  it('blocks rich HTML and image clipboard payloads instead of importing their attributes', () => {
    assert.match(componentSource, /handlePaste/)
    assert.match(componentSource, /clipboardData/)
    assert.match(componentSource, /text\/html/)
    assert.match(componentSource, /image\//)
    assert.doesNotMatch(componentSource + extensionSource, /@tiptap\/extension-image|TiptapUi|UIComponents/i)
  })
})

describe('restricted rich-text schema', () => {
  it('uses a platform image node whose JSON attrs are only assetId and alt', async () => {
    assert.match(extensionSource, /export const PlatformImage/)
    assert.match(extensionSource, /name:\s*'image'/)
    assert.match(extensionSource, /assetId:\s*\{\s*default:\s*null,\s*rendered:\s*false\s*\}/s)
    assert.match(extensionSource, /alt:\s*\{\s*default:\s*'',\s*rendered:\s*false\s*\}/s)
    assert.doesNotMatch(extensionSource, /(?:href|externalUrl|dataUrl):\s*\{/)
    assert.match(extensionSource, /`\/api\/public\/engagement\/assets\/\$\{assetId\}`/)
    assert.match(extensionSource, /parseHTML\(\)\s*\{\s*return \[\]\s*\}/s)

    const { platformAssetUrl } = await import('./restrictedRichTextExtensions.ts')
    const assetId = '123e4567-e89b-42d3-a456-426614174000'
    assert.equal(platformAssetUrl(assetId), `/api/public/engagement/assets/${assetId}`)
    assert.equal(platformAssetUrl('https://evil.example/image.png'), null)
    assert.equal(platformAssetUrl('data:image/png;base64,AAAA'), null)
  })

  it('uses an internal-link mark with routeKey and params but no href', () => {
    assert.match(extensionSource, /export const InternalLink/)
    assert.match(extensionSource, /name:\s*'link'/)
    assert.match(extensionSource, /routeKey:\s*\{\s*default:\s*null,\s*rendered:\s*false\s*\}/s)
    assert.match(extensionSource, /params:\s*\{\s*default:\s*\{\},\s*rendered:\s*false\s*\}/s)
    assert.doesNotMatch(extensionSource, /href\s*:/)
    assert.match(extensionSource, /data-route-key/)
    assert.match(componentSource, /link:\s*false/)
  })
})
