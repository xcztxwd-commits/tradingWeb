export type ContentPreviewSurface = 'PC' | 'MOBILE'
export type ContentPreviewSizeMode = 'SMALL' | 'MEDIUM' | 'LARGE'

export type ContentPreviewCoverAsset = Readonly<{
  assetId: string
  url: string
}>

export type ContentPreviewCta = Readonly<{
  label: string
  routeKey: string
  params: Readonly<Record<string, string>>
}>

export type ContentPreviewModel = Readonly<{
  surface: ContentPreviewSurface
  sizeMode: ContentPreviewSizeMode
  title: string
  sanitizedHtml: string
  coverAsset: ContentPreviewCoverAsset | null
  cta: ContentPreviewCta | null
}>

const UUID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/iu
const SURFACES = new Set<ContentPreviewSurface>(['PC', 'MOBILE'])
const SIZE_MODES = new Set<ContentPreviewSizeMode>(['SMALL', 'MEDIUM', 'LARGE'])

export function createContentPreviewModel(
  input: unknown,
  surface: unknown
): ContentPreviewModel {
  const source = record(input, 'content preview')
  exactKeys(source, ['sizeMode', 'title', 'sanitizedHtml', 'coverAssetId', 'cta'])
  if (!SURFACES.has(surface as ContentPreviewSurface)) {
    throw new TypeError('Unsupported content preview surface')
  }
  if (!SIZE_MODES.has(source.sizeMode as ContentPreviewSizeMode)) {
    throw new TypeError('Unsupported content preview size')
  }

  return Object.freeze({
    surface: surface as ContentPreviewSurface,
    sizeMode: source.sizeMode as ContentPreviewSizeMode,
    title: text(source.title, 'title'),
    sanitizedHtml: text(source.sanitizedHtml, 'sanitizedHtml'),
    coverAsset: coverAsset(source.coverAssetId),
    cta: cta(source.cta)
  })
}

function coverAsset(value: unknown): ContentPreviewCoverAsset | null {
  if (value === undefined || value === null) return null
  const assetId = text(value, 'coverAssetId')
  if (!UUID.test(assetId)) {
    throw new TypeError('Cover asset ID must be a UUID')
  }
  return Object.freeze({
    assetId,
    url: `/api/public/engagement/assets/${assetId}`
  })
}

function cta(value: unknown): ContentPreviewCta | null {
  if (value === undefined || value === null) return null
  const action = record(value, 'cta')
  exactKeys(action, ['label', 'routeKey', 'params'])
  const rawParams = record(action.params, 'cta.params')
  const params = Object.fromEntries(Object.entries(rawParams).map(([key, item]) => {
    if (typeof item !== 'string') {
      throw new TypeError(`cta.params.${key} must be text`)
    }
    return [key, item]
  }))
  return Object.freeze({
    label: text(action.label, 'cta.label'),
    routeKey: text(action.routeKey, 'cta.routeKey'),
    params: Object.freeze(params)
  })
}

function record(value: unknown, name: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`${name} must be an object`)
  }
  return value as Record<string, unknown>
}

function exactKeys(value: Record<string, unknown>, allowed: string[]) {
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) {
      throw new TypeError(`Unsupported field: ${key}`)
    }
  }
}

function text(value: unknown, name: string) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new TypeError(`${name} must be non-empty text`)
  }
  return value
}
