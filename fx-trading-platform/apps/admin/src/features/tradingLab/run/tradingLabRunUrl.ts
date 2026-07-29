export type TradingLabRunLocation = Readonly<{
  runId: string | null
  error: string | null
}>

const CANONICAL_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u

export function readTradingLabRunLocation(
  search: string,
): TradingLabRunLocation {
  const values = new URLSearchParams(search).getAll('runId')
  if (values.length === 0) {
    return { runId: null, error: null }
  }
  if (
    values.length !== 1
    || values[0] === undefined
    || !CANONICAL_UUID.test(values[0])
  ) {
    return {
      runId: null,
      error: 'URL runId 必须是唯一的 canonical lowercase UUID。',
    }
  }
  return { runId: values[0], error: null }
}

export function writeTradingLabRunLocation(
  href: string,
  runId: string,
): string {
  if (!CANONICAL_UUID.test(runId)) {
    throw new Error('Trading Lab runId is not canonical')
  }
  const url = new URL(href)
  url.searchParams.delete('runId')
  url.searchParams.set('runId', runId)
  return `${url.pathname}${url.search}${url.hash}`
}
