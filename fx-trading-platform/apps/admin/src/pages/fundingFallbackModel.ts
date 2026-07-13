type FundingFallbackState = {
  actualSource?: string | null
  fallbackReason?: string | null
  sourceMode?: string | null
}

export function formatFundingFallbackState(config: FundingFallbackState) {
  const actualSource = normalize(config.actualSource)
  const fallbackReason = normalize(config.fallbackReason)
  const sourceMode = normalize(config.sourceMode)
  const sourceModeSuffix = sourceMode ? ` · ${sourceMode}` : ''

  if (!actualSource) return `UNAVAILABLE · 未选出实际来源${sourceModeSuffix}`
  if (fallbackReason) return `FALLBACK · ${fallbackReason} → ${actualSource}${sourceModeSuffix}`
  return `PRIMARY · ${actualSource}${sourceModeSuffix}`
}

function normalize(value: string | null | undefined) {
  return value?.trim().toUpperCase() ?? ''
}
