import { Check, ChevronDown } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'

export type SelectFieldOption<T extends string = string> = {
  label: string
  value: T
}

type SelectFieldProps<T extends string = string> = {
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
  const rootClassName = ['select-field', className].filter(Boolean).join(' ')
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
    if (event.altKey || event.ctrlKey || event.metaKey) return

    if (event.key === 'Escape') {
      setOpen(false)
      return
    }

    if (event.key === 'Tab') {
      setOpen(false)
      return
    }

    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const direction = event.key === 'ArrowDown' ? 1 : -1
      if (!open) {
        openMenu(Math.max(0, fallbackIndex + direction))
        return
      }
      setActiveIndex((current) => (current + direction + options.length) % options.length)
      return
    }

    if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      openMenu(event.key === 'Home' ? 0 : options.length - 1)
      return
    }

    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      if (open) {
        selectOption(activeIndex)
        return
      }
      openMenu()
    }
  }

  return (
    <div ref={rootRef} className={rootClassName} data-open={open ? 'true' : 'false'} onKeyDown={handleKeyDown}>
      <button
        id={buttonId}
        type="button"
        className="select-field__button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listboxId : undefined}
        aria-label={labelledBy ? undefined : ariaLabel}
        aria-labelledby={labelledBy ? `${labelledBy} ${buttonId}` : undefined}
        disabled={options.length === 0}
        onClick={() => (open ? setOpen(false) : openMenu())}
      >
        <span className="select-field__value">{selectedOption?.label ?? ''}</span>
        <ChevronDown className="select-field__chevron" size={16} aria-hidden="true" />
      </button>
      {open ? (
        <div
          id={listboxId}
          className="select-field__menu"
          role="listbox"
          aria-label={labelledBy ? undefined : ariaLabel}
          aria-labelledby={labelledBy}
        >
          {options.map((option, index) => (
            <button
              key={option.value}
              id={`${generatedId}-option-${option.value}`}
              type="button"
              className="select-field__option"
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
