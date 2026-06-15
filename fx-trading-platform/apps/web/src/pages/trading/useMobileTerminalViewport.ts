import { useEffect, useState } from 'react'

export function useMobileTerminalViewport() {
  const [isMobileTerminal, setIsMobileTerminal] = useState(() =>
    typeof window === 'undefined' ? false : window.matchMedia('(max-width: 768px)').matches
  )

  useEffect(() => {
    if (typeof window === 'undefined') return

    const mediaQuery = window.matchMedia('(max-width: 768px)')
    const syncViewport = () => setIsMobileTerminal(mediaQuery.matches)

    syncViewport()
    mediaQuery.addEventListener('change', syncViewport)
    return () => {
      mediaQuery.removeEventListener('change', syncViewport)
    }
  }, [])

  return isMobileTerminal
}
