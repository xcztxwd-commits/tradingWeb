import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'EngagementPopup.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'EngagementPopup.module.css'), 'utf8')

describe('EngagementPopup contract', () => {
  it('delegates focus, Escape, backdrop and overlay priority to the shared Dialog', () => {
    assert.match(source, /import \{[^}]*Dialog[^}]*\} from '@fx-platform\/ui'/)
    assert.match(source, /priority="standard"/)
    assert.match(source, /initialFocusRef=\{closeButtonRef\}/)
    assert.match(source, /onClose=\{handleClose\}/)
    assert.match(source, /ref=\{closeButtonRef\}[\s\S]*onClick=\{handleClose\}/)
  })

  it('marks only a renderable delivery shown and exposes close, opt-out and one CTA', () => {
    assert.match(source, /getCriticalDialogOpen/)
    assert.match(source, /if \(!view \|\| snapshot\.blocked \|\| getCriticalDialogOpen\(\)\) return/)
    assert.match(source, /actions\.popupMounted\(\)/)
    assert.match(source, /\[actions, snapshot\.blocked, view\?\.deliveryId\]/)
    assert.match(source, /actions\.closeCurrent/)
    assert.match(source, /actions\.optOutCurrent/)
    assert.match(source, /activateEngagementPopupCta\(view\.cta, actions\.clickCurrent, navigate\)/)
    assert.equal((source.match(/onClick=\{handleClose\}/g) ?? []).length, 2)
    assert.equal((source.match(/className=\{styles\.cta\}/g) ?? []).length, 1)
  })

  it('renders canonical cover/body content and leaves body link metadata inert', () => {
    assert.match(source, /src=\{view\.coverUrl\}/)
    assert.match(source, /dangerouslySetInnerHTML=\{\{ __html: view\.html \}\}/)
    assert.doesNotMatch(source, /data-route-key[\s\S]*onClick/)
  })

  it('implements all fixed sizes, a scrolling body and one mobile layout', () => {
    for (const size of ['Small', 'Medium', 'Large']) {
      assert.match(styles, new RegExp(`\\.panel${size}\\s*\\{`))
    }
    assert.match(styles, /\.body\s*\{[\s\S]*overflow-y:\s*auto/)
    assert.match(styles, /@media \(max-width:\s*900px\)[\s\S]*\.mobileLayer/)
    assert.doesNotMatch(source, /PcEngagementPopup|MobileEngagementPopup/)
  })

  it('styles the backend allowlisted alignment classes without a global CSS bridge', () => {
    for (const alignment of ['left', 'center', 'right']) {
      assert.match(styles, new RegExp(`\\.body \\[class~="align-${alignment}"\\]`))
    }
    assert.doesNotMatch(styles, /:global\(\.align-/)
  })
})
