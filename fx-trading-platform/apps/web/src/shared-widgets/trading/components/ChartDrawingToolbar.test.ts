import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const styles = readFileSync(join(currentDir, 'ChartDrawingToolbar.module.css'), 'utf8')
const source = readFileSync(join(currentDir, 'ChartDrawingToolbar.tsx'), 'utf8')

describe('chart drawing toolbar styles', () => {
  it('keeps flyout menus connected to the rail so hover does not close them while entering the menu', () => {
    assert.match(styles, /\.flyoutWrap::after\s*{[^}]*content:\s*''[^}]*}/s)
    assert.match(styles, /\.flyoutMenu\s*{[^}]*left:\s*100%[^}]*}/s)
    assert.doesNotMatch(styles, /\.flyoutMenu\s*{[^}]*left:\s*calc\(100%\s*\+\s*\d+px\)[^}]*}/s)
  })

  it('adds hover-revealed transparent scroll buttons for the left drawing rail', () => {
    assert.match(source, /scrollToolRail\('up'\)/)
    assert.match(source, /scrollToolRail\('down'\)/)
    assert.match(source, /chart\.scrollDrawingToolsUp/)
    assert.match(source, /chart\.scrollDrawingToolsDown/)
    assert.match(styles, /\.toolGroup\s*{[^}]*overflow-y:\s*auto[^}]*}/s)
    assert.match(styles, /\.scrollButton\s*{[^}]*opacity:\s*0[^}]*pointer-events:\s*none[^}]*}/s)
    assert.match(styles, /\.rail\s+\.scrollButton\s*{[^}]*left:\s*0[^}]*right:\s*0[^}]*height:\s*22px[^}]*margin:\s*0 auto[^}]*}/s)
    assert.match(styles, /\.rail:hover\s+\.scrollButton,\s*\.rail:focus-within\s+\.scrollButton\s*{[^}]*opacity:\s*1[^}]*}/s)
  })
})
