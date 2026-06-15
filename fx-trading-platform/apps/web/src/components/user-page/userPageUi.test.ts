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
    assert.match(pageState, /state-panel--loading/)
    assert.match(pageState, /state-panel__skeleton/)
    assert.match(styles, /\.state-panel--loading/)
    assert.match(styles, /@keyframes\s+userPageSkeleton/)
  })

  it('gives empty tables an optional next action', () => {
    assert.match(dataTable, /emptyAction/)
    assert.match(dataTable, /data-table__empty/)
    assert.match(styles, /\.data-table__empty-action/)
  })

  it('renders a card layout for tables on small screens', () => {
    assert.match(dataTable, /data-table__cards/)
    assert.match(dataTable, /data-table__card-row/)
    assert.match(styles, /@media\s*\(max-width:\s*720px\)/)
    assert.match(styles, /\.data-table__cards\s*{[\s\S]*display:\s*grid/)
    assert.match(styles, /\.user-page__table\s*{[\s\S]*display:\s*none/)
    assert.match(styles, /\.data-table__card-row\s*{[\s\S]*grid-template-columns:\s*minmax\(88px,\s*36%\)\s*minmax\(0,\s*1fr\)/)
    assert.match(styles, /\.data-table__card-value\s*{[\s\S]*overflow-wrap:\s*anywhere/)
  })

  it('announces table sorting state and next sort direction', () => {
    assert.match(dataTable, /const activeSort = sortKey === columnKey/)
    assert.match(dataTable, /const nextSortDirection = activeSort && sortDirection === 'asc' \? 'desc' : 'asc'/)
    assert.match(
      dataTable,
      /const ariaSort: 'ascending' \| 'descending' \| undefined = activeSort[\s\S]*\? \(sortDirection === 'asc' \? 'ascending' : 'descending'\)[\s\S]*: undefined/,
    )
    assert.match(dataTable, /aria-sort=\{ariaSort\}/)
    assert.match(dataTable, /common\.tableSort/)
    assert.match(dataTable, /common\.sortAsc/)
    assert.match(dataTable, /common\.sortDesc/)
    assert.match(dataTable, /aria-hidden="true"/)
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
