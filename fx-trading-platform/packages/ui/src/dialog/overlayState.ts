export type OverlayCloseSource = 'escape' | 'backdrop'

export type OverlayCloseOptions = {
  source: OverlayCloseSource
  pending?: boolean
  closeOnEscape?: boolean
  closeOnBackdrop?: boolean
}

export function shouldCloseOverlay({
  source,
  pending = false,
  closeOnEscape = true,
  closeOnBackdrop = true
}: OverlayCloseOptions) {
  if (pending) return false
  return source === 'escape' ? closeOnEscape : closeOnBackdrop
}
