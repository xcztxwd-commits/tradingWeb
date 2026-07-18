import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const dataTable = readFileSync(join(currentDir, 'DataTable.tsx'), 'utf8')
const pageState = readFileSync(join(currentDir, 'PageState.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, '..', '..', 'styles.css'), 'utf8')

describe('user page shared UI system', () => {
  it('uses skeleton loading states instead of a text-only loading panel', () => {
    assert.match(pageState, /import \{ StateSurface \} from '@fx-platform\/ui'/)
    assert.match(pageState, /<StateSurface/)
    assert.doesNotMatch(pageState, /state-panel--loading/)
    assert.doesNotMatch(styles, /@keyframes\s+userPageSkeleton/)
  })

  it('gives empty tables an optional next action', () => {
    assert.match(dataTable, /emptyAction/)
    assert.match(dataTable, /TableEmptyState/)
    assert.match(dataTable, /empty=\{/)
  })

  it('renders a card layout for tables on small screens', () => {
    assert.match(dataTable, /data-table__cards/)
    assert.match(dataTable, /DataCardList/)
    assert.match(styles, /@media\s*\(max-width:\s*720px\)/)
    assert.match(styles, /\.data-table__cards\s*{[\s\S]*display:\s*grid/)
    assert.match(styles, /\.user-page__table\s*{[\s\S]*display:\s*none/)
    assert.doesNotMatch(dataTable, /data-table__card-row/)
  })

  it('announces table sorting state and next sort direction', () => {
    assert.match(dataTable, /getNextDataSort/)
    assert.match(dataTable, /DataTable as UiDataTable/)
    assert.match(dataTable, /sortKey=\{sortKey\}/)
    assert.match(dataTable, /sortDirection=\{sortDirection\}/)
    assert.match(dataTable, /common\.tableSort/)
    assert.match(dataTable, /common\.sortAsc/)
    assert.match(dataTable, /common\.sortDesc/)
    assert.match(dataTable, /getSortLabel/)
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
