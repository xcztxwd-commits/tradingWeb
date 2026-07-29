import type { PopupDisplayModel, PopupQueueSnapshot } from '@fx-platform/frontend-core'

import { prepareEngagementHtml, resolveEngagementRoute } from '../../engagement/engagementNavigation.ts'

export type EngagementPopupCta = Readonly<{
  label: string
  route: string
}>

export type EngagementPopupView = Readonly<{
  deliveryId: string
  title: string
  surface: PopupDisplayModel['content']['surface']
  sizeMode: PopupDisplayModel['content']['sizeMode']
  coverUrl: string | null
  html: string
  cta: EngagementPopupCta | null
}>

export function createEngagementPopupView(
  snapshot: PopupQueueSnapshot<PopupDisplayModel>
): EngagementPopupView | null {
  const current = snapshot.current
  if (!current) return null
  const html = prepareEngagementHtml(current.content.sanitizedHtml)
  if (!html) return null
  const ctaRoute = current.content.cta
    ? resolveEngagementRoute(current.content.cta.routeKey, current.content.cta.params)
    : null

  return Object.freeze({
    deliveryId: current.deliveryId,
    title: current.content.title,
    surface: current.content.surface,
    sizeMode: current.content.sizeMode,
    coverUrl: current.content.coverAsset?.url ?? null,
    html,
    cta: current.content.cta && ctaRoute
      ? Object.freeze({ label: current.content.cta.label, route: ctaRoute })
      : null
  })
}

export async function activateEngagementPopupCta(
  cta: EngagementPopupCta | null,
  clickCurrent: () => Promise<'CONTINUE' | 'TERMINATE' | null>,
  navigate: (route: string) => void
) {
  if (!cta) return false
  if (await clickCurrent() !== 'TERMINATE') return false
  navigate(cta.route)
  return true
}
