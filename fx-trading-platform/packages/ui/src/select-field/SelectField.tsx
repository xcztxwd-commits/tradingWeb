import { Check, ChevronDown } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'

import styles from './SelectField.module.css'
import { getSelectFieldKeyTransition } from './selectFieldState'

export type SelectFieldOption<T extends string = string> = {
  label: string
  value: T
}

export type SelectFieldProps<T extends string = string> = {
  ariaLabel?: string
  className?: string
  labelledBy?: string
  onChange: (value: T) => void
  options: readonly SelectFieldOption<T>[]
  value: T
}

export function SelectField<T extends string>({
  ariaLabel,
  className,
  labelledBy,
  onChange,
  options,
  value
}: SelectFieldProps<T>) {
  const generatedId = useId()
  const rootRef = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const selectedIndex = useMemo(() => options.findIndex((option) => option.value === value), [options, value])
  const fallbackIndex = selectedIndex >= 0 ? selectedIndex : 0
  const [activeIndex, setActiveIndex] = useState(fallbackIndex)
  const selectedOption = options[fallbackIndex]
  const rootClassName = [styles.root, className].filter(Boolean).join(' ')
  const buttonId = `${generatedId}-button`
  const listboxId = `${generatedId}-listbox`

  useEffect(() => {
    setActiveIndex(fallbackIndex)
  }, [fallbackIndex])

  useEffect(() => {
    if (!open) return undefined

    const handlePointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }

    window.addEventListener('pointerdown', handlePointerDown)
    return () => window.removeEventListener('pointerdown', handlePointerDown)
  }, [open])

  const openMenu = (nextActiveIndex = fallbackIndex) => {
    if (options.length === 0) return
    setActiveIndex(nextActiveIndex)
    setOpen(true)
  }

  const selectOption = (nextIndex: number) => {
    const nextOption = options[nextIndex]
    if (!nextOption) return
    if (nextOption.value !== value) {
      onChange(nextOption.value)
    }
    setOpen(false)
  }

  const handleKeyDown = (event: KeyboardEvent) => {
    const transition = getSelectFieldKeyTransition({
      key: event.key,
      open,
      activeIndex,
      selectedIndex,
      optionCount: options.length,
      modified: event.altKey || event.ctrlKey || event.metaKey
    })

    if (transition.preventDefault) event.preventDefault()
    if (transition.selectIndex !== null) {
      setActiveIndex(transition.activeIndex)
      selectOption(transition.selectIndex)
      return
    }
    if (transition.activeIndex !== activeIndex) setActiveIndex(transition.activeIndex)
    if (transition.open !== open) setOpen(transition.open)
  }

  return (
    <div ref={rootRef} className={rootClassName} data-open={open ? 'true' : 'false'} onKeyDown={handleKeyDown}>
      <button
        id={buttonId}
        type="button"
        className={styles.button}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listboxId : undefined}
        aria-label={labelledBy ? undefined : ariaLabel}
        aria-labelledby={labelledBy ? `${labelledBy} ${buttonId}` : undefined}
        disabled={options.length === 0}
        onClick={() => (open ? setOpen(false) : openMenu())}
      >
        <span className={styles.value}>{selectedOption?.label ?? ''}</span>
        <ChevronDown className={styles.chevron} size={16} aria-hidden="true" />
      </button>
      {open ? (
        <div
          id={listboxId}
          className={styles.menu}
          role="listbox"
          aria-label={labelledBy ? undefined : ariaLabel}
          aria-labelledby={labelledBy}
        >
          {options.map((option, index) => (
            <button
              key={option.value}
              id={`${generatedId}-option-${option.value}`}
              type="button"
              className={styles.option}
              role="option"
              aria-selected={option.value === value}
              data-active={index === activeIndex ? 'true' : 'false'}
              data-value={option.value}
              onMouseEnter={() => setActiveIndex(index)}
              onClick={() => selectOption(index)}
            >
              <span>{option.label}</span>
              {option.value === value ? <Check size={14} aria-hidden="true" /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}
