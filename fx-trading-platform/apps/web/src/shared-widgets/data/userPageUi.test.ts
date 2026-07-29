import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const dataCollection = readFileSync(join(currentDir, 'RouteDataCollection.tsx'), 'utf8')
const pcCollection = readFileSync(join(currentDir, '..', '..', 'pc', 'components', 'PcDataCollection.tsx'), 'utf8')
const mobileCollection = readFileSync(join(currentDir, '..', '..', 'mobile', 'components', 'MobileDataCollection.tsx'), 'utf8')
const pageState = readFileSync(join(currentDir, 'PageState.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'UserPageSurface.module.css'), 'utf8')

describe('user page shared UI system', () => {
  it('uses skeleton loading states instead of a text-only loading panel', () => {
    assert.match(pageState, /import \{ StateSurface \} from '@fx-platform\/ui'/)
    assert.match(pageState, /<StateSurface/)
    assert.doesNotMatch(pageState, /state-panel--loading/)
    assert.doesNotMatch(styles, /@keyframes\s+userPageSkeleton/)
  })

  it('gives empty tables an optional next action', () => {
    assert.match(dataCollection, /emptyAction/)
    assert.match(dataCollection, /RouteDataCollectionEmpty/)
    assert.match(`${pcCollection}\n${mobileCollection}`, /empty=\{/)
  })

  it('loads a card collection only for Mobile instead of CSS-hiding a table', () => {
    assert.match(pcCollection, /DataTable/)
    assert.doesNotMatch(pcCollection, /DataCardList/)
    assert.match(mobileCollection, /DataCardList/)
    assert.doesNotMatch(mobileCollection, /<DataTable/)
    assert.doesNotMatch(styles, /\.data-table__cards/)
    assert.doesNotMatch(styles, /\.user-page__table\s*{\s*display:\s*none/)
  })

  it('announces table sorting state and next sort direction', () => {
    assert.match(dataCollection, /getNextDataSort/)
    assert.match(pcCollection, /sortKey=\{state\.sortKey\}/)
    assert.match(pcCollection, /sortDirection=\{state\.sortDirection\}/)
    assert.match(pcCollection, /common\.tableSort/)
    assert.match(pcCollection, /common\.sortAsc/)
    assert.match(pcCollection, /common\.sortDesc/)
    assert.match(pcCollection, /getSortLabel/)
  })

  it('defines semantic table action variants including destructive actions', () => {
    assert.match(styles, /\.table-action--primary/)
    assert.match(styles, /\.table-action--secondary/)
    assert.match(styles, /\.table-action--danger/)
    assert.match(styles, /\.table-action--danger:disabled/)
  })

  it('keeps state messages user-facing instead of exposing engineering codes', () => {
    assert.match(pageState, /function getReadableErrorTitle/)
    assert.match(pageState, /\[A-Z0-9_\]/)
    assert.match(pageState, /common\.loadFailed/)
    assert.match(pageState, /common\.issueId/)
    assert.doesNotMatch(pageState, /request id:/)
  })
})
