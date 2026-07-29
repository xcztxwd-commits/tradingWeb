export type SelectFieldKeyTransitionInput = {
  key: string
  open: boolean
  activeIndex: number
  selectedIndex: number
  optionCount: number
  modified?: boolean
}

export type SelectFieldKeyTransition = {
  open: boolean
  activeIndex: number
  selectIndex: number | null
  preventDefault: boolean
}

export function getSelectFieldKeyTransition({
  key,
  open,
  activeIndex,
  selectedIndex,
  optionCount,
  modified = false
}: SelectFieldKeyTransitionInput): SelectFieldKeyTransition {
  const unchanged = { open, activeIndex, selectIndex: null, preventDefault: false }
  if (modified) return unchanged

  if (key === 'Escape' || key === 'Tab') {
    return { ...unchanged, open: false }
  }

  if (optionCount <= 0) {
    return { ...unchanged, open: false, activeIndex: 0 }
  }

  const fallbackIndex = isOptionIndex(selectedIndex, optionCount) ? selectedIndex : 0
  const currentIndex = isOptionIndex(activeIndex, optionCount) ? activeIndex : fallbackIndex

  if (key === 'ArrowDown' || key === 'ArrowUp') {
    const direction = key === 'ArrowDown' ? 1 : -1
    const index = open ? currentIndex : fallbackIndex
    return {
      open: true,
      activeIndex: wrapIndex(index + direction, optionCount),
      selectIndex: null,
      preventDefault: true
    }
  }

  if (key === 'Home' || key === 'End') {
    return {
      open: true,
      activeIndex: key === 'Home' ? 0 : optionCount - 1,
      selectIndex: null,
      preventDefault: true
    }
  }

  if (key === 'Enter' || key === ' ') {
    return open
      ? { open: false, activeIndex: currentIndex, selectIndex: currentIndex, preventDefault: true }
      : { open: true, activeIndex: fallbackIndex, selectIndex: null, preventDefault: true }
  }

  return unchanged
}

function isOptionIndex(index: number, optionCount: number) {
  return Number.isInteger(index) && index >= 0 && index < optionCount
}

function wrapIndex(index: number, optionCount: number) {
  return (index + optionCount) % optionCount
}
