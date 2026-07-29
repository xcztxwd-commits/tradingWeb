import { canonicalJson } from '../model/normalization.ts'
import type { TradingLabScenario } from '../model/types.ts'
import { normalizeTradingLabScenarioDocument } from './scenarioDocument.ts'

const JSON_MIME = 'application/json;charset=utf-8'
const UNSAFE_FILE_NAME = /[<>:"/\\|?*\u0000-\u001f\u007f]/g
const UNSAFE_FILE_END = /[. ]+$/g

function defaultSave(blob: Blob, fileName: string): void {
  if (
    typeof document === 'undefined'
    || document.body === null
    || typeof URL.createObjectURL !== 'function'
    || typeof URL.revokeObjectURL !== 'function'
  ) {
    throw new Error('当前浏览器不支持 Trading Lab JSON 下载')
  }

  const objectUrl = URL.createObjectURL(blob)
  let anchor: HTMLAnchorElement | null = null
  try {
    anchor = document.createElement('a')
    anchor.href = objectUrl
    anchor.download = fileName
    anchor.style.display = 'none'
    document.body.append(anchor)
    anchor.click()
  } finally {
    anchor?.remove()
    URL.revokeObjectURL(objectUrl)
  }
}

function exportFileName(id: string): string {
  const safeId = id
    .replace(UNSAFE_FILE_NAME, '_')
    .replace(UNSAFE_FILE_END, (suffix) => '_'.repeat(suffix.length))
  return `${safeId}.json`
}

export async function importTradingLabScenarioJson(
  source: string,
): Promise<TradingLabScenario> {
  let value: unknown
  try {
    value = JSON.parse(source) as unknown
  } catch (error) {
    throw new Error('Trading Lab 场景 JSON 解析失败', { cause: error })
  }
  return normalizeTradingLabScenarioDocument(value)
}

export async function exportTradingLabScenarioJson(
  scenario: TradingLabScenario,
): Promise<string> {
  const normalized = await normalizeTradingLabScenarioDocument(scenario)
  return canonicalJson(normalized)
}

export async function downloadTradingLabScenarioJson(
  scenario: TradingLabScenario,
  save: (blob: Blob, fileName: string) => void = defaultSave,
): Promise<void> {
  const normalized = await normalizeTradingLabScenarioDocument(scenario)
  const source = canonicalJson(normalized)
  save(
    new Blob([source], { type: JSON_MIME }),
    exportFileName(normalized.id),
  )
}
