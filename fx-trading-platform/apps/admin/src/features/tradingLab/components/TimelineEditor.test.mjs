import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const readSource = (name) => {
  const path = join(currentDir, name)
  return existsSync(path) ? readFileSync(path, 'utf8') : ''
}
const editorSource = readSource('TimelineEditor.tsx')
const actionSource = readSource('TimelineActionEditor.tsx')

describe('Timeline editor source wiring', () => {
  it('keeps the frozen focused React interfaces', () => {
    assert.match(editorSource, /export type TimelineEditorProps\s*=\s*\{/)
    for (const declaration of [
      /actions:\s*TimelineAction\[\]/,
      /locked:\s*boolean/,
      /issues:\s*ScenarioValidationIssue\[\]/,
      /onChange\(actions:\s*TimelineAction\[\]\):\s*void/,
    ]) {
      assert.match(editorSource, declaration)
    }
    assert.match(actionSource, /export type TimelineActionEditorProps\s*=\s*\{/)
    assert.match(actionSource, /onChange\(action:\s*TimelineAction\):\s*void/)
  })

  it('delegates every list mutation and action type switch to the pure command seam', () => {
    assert.match(editorSource, /editTimeline\(/)
    for (const command of [
      "'ADD'",
      "'DELETE'",
      "'DUPLICATE'",
      "'REPLACE'",
      "'MOVE_BEFORE'",
      "'MOVE_BY'",
    ]) {
      assert.ok(editorSource.includes(`type: ${command}`), `${command} command is missing`)
    }
    assert.match(actionSource, /switchTimelineActionType\(/)
    for (const type of [
      'PLACE_ORDER',
      'CANCEL_ORDER',
      'CANCEL_ALL',
      'SET_POSITION_MODE',
      'SET_MARGIN_MODE',
      'SET_LEVERAGE',
      'ADD_MARGIN',
      'REMOVE_MARGIN',
      'APPLY_FUNDING',
      'PLACE_OCO',
    ]) {
      assert.ok(actionSource.includes(`case '${type}'`), `${type} dispatch is missing`)
    }
  })

  it('uses action ids for native DnD-before and exposes up/down controls', () => {
    assert.match(
      editorSource,
      /dataTransfer\.setData\(['"]text\/plain['"],\s*action\.id\)/,
    )
    assert.match(editorSource, /dataTransfer\.getData\(['"]text\/plain['"]\)/)
    assert.match(
      editorSource,
      /type:\s*'MOVE_BEFORE',\s*actionId,\s*targetId:\s*action\.id/,
    )
    assert.match(editorSource, /上移/)
    assert.match(editorSource, /下移/)
  })

  it('guards locked handlers before UUID generation and mirrors lock in the DOM', () => {
    assert.match(editorSource, /if\s*\(locked\)\s*\{\s*return\s*\}/)
    assert.match(
      editorSource,
      /if\s*\(locked\)\s*\{\s*return\s*\}[\s\S]*crypto\.randomUUID\(\)/,
    )
    assert.match(editorSource, /draggable=\{!locked\}/)
    assert.match(editorSource, /disabled=\{locked/)
    assert.match(actionSource, /if\s*\(locked\)\s*\{\s*return\s*\}/)
    assert.match(actionSource, /disabled=\{locked/)
  })

  it('routes current-index issues and preserves raw editable text', () => {
    assert.match(editorSource, /`timeline\[\$\{index\}\]`/)
    assert.match(editorSource, /issues=\{actionIssues\}/)
    assert.match(actionSource, /event\.target\.value/)
    assert.doesNotMatch(actionSource, /parseFloat\(/)
    assert.doesNotMatch(actionSource, /\.trim\(\)/)
  })

  it('labels expected-error editing as negative-only', () => {
    assert.match(actionSource, /预期错误[^<]*仅负向模式/)
    assert.match(actionSource, /预期 HTTP 状态[^<]*仅负向模式/)
    assert.match(actionSource, /预期业务代码[^<]*仅负向模式/)
    assert.match(actionSource, /expectedError/)
  })

  it('uses centralized bilingual labels for editable price controls', () => {
    assert.match(actionSource, /PRICE_FIELD_LABELS/)
    assert.match(actionSource, /PRICE_TYPE_LABELS/)
    for (const key of [
      'price',
      'requestedPrice',
      'triggerPrice',
      'activationPrice',
      'stopLoss',
      'takeProfit',
    ]) {
      assert.match(actionSource, new RegExp(`PRICE_FIELD_LABELS\\.${key}`))
      assert.doesNotMatch(
        actionSource,
        new RegExp(`<label[^>]*>\\s*${key}\\s*<`, 'i'),
      )
    }
  })

  it('keeps every PLACE_OCO skeleton field visible and editable', () => {
    assert.match(
      actionSource,
      /case 'PLACE_OCO':[\s\S]*label="quantityUnit（数量单位）"[\s\S]*onParameter\('quantityUnit', value\)/,
    )
    assert.match(
      actionSource,
      /case 'PLACE_OCO':[\s\S]*label="triggerPriceType（触发价格类型）"[\s\S]*onParameter\('triggerPriceType', value\)/,
    )
  })
})
