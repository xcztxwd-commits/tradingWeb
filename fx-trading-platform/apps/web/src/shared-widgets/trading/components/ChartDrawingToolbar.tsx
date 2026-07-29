import {
  ChevronDown,
  ChevronRight,
  ChevronUp,
  Circle,
  Eraser,
  EyeOff,
  GitBranch,
  Magnet,
  MousePointer2,
  MoveHorizontal,
  MoveVertical,
  PenLine,
  Route,
  Square,
  Tag,
  Triangle,
  Type as TypeIcon,
  Waves,
  WavesLadder
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { drawingToolGroups } from '../chartSettings'
import type { ChartSettings, DrawingMagnetMode, DrawingTool, DrawingToolCommand, DrawingToolGroup, DrawingToolOption } from '../chartSettings'
import styles from './ChartDrawingToolbar.module.css'

type Props = {
  settings: ChartSettings['drawingToolSettings']
  drawingsHidden: boolean
  indicatorsHidden: boolean
  onDrawingToolChange: (tool: DrawingTool) => void
  onDrawingMagnetModeChange: (mode: DrawingMagnetMode) => void
  onToggleDrawingsHidden: () => void
  onToggleIndicatorsHidden: () => void
  onToggleAllHidden: () => void
  onClearDrawings: () => void
}

export function ChartDrawingToolbar({
  settings,
  drawingsHidden,
  indicatorsHidden,
  onDrawingToolChange,
  onDrawingMagnetModeChange,
  onToggleDrawingsHidden,
  onToggleIndicatorsHidden,
  onToggleAllHidden,
  onClearDrawings
}: Props) {
  const { t } = useTranslation()
  const railRef = useRef<HTMLElement | null>(null)
  const toolGroupRef = useRef<HTMLDivElement | null>(null)
  const [openGroup, setOpenGroup] = useState<DrawingToolGroup['value'] | null>(null)
  const [flyoutPosition, setFlyoutPosition] = useState({ top: 0, left: 0 })

  const handleToolChange = (tool: DrawingTool) => {
    onDrawingToolChange(tool)
    setOpenGroup(null)
  }

  const handleCommand = (command: DrawingToolCommand['value']) => {
    if (command === 'hideDrawings') onToggleDrawingsHidden()
    if (command === 'hideIndicators') onToggleIndicatorsHidden()
    if (command === 'hideAll') onToggleAllHidden()
    if (command === 'clearDrawings') onClearDrawings()
    if (command === 'weakMagnet') onDrawingMagnetModeChange(settings.magnetMode === 'weak' ? 'none' : 'weak')
    if (command === 'strongMagnet') onDrawingMagnetModeChange(settings.magnetMode === 'strong' ? 'none' : 'strong')
    setOpenGroup(null)
  }

  const positionFlyout = (element: HTMLElement) => {
    const buttonRect = element.getBoundingClientRect()
    const railRect = railRef.current?.getBoundingClientRect()
    setFlyoutPosition({
      top: buttonRect.top,
      left: railRect?.right ?? buttonRect.right
    })
  }

  const handleGroupMouseEnter = (group: DrawingToolGroup, element: HTMLElement) => {
    if (isDirectGroup(group)) {
      setOpenGroup(null)
      return
    }
    positionFlyout(element)
    setOpenGroup(group.value)
  }

  const toggleGroup = (group: DrawingToolGroup, element: HTMLElement) => {
    positionFlyout(element)
    setOpenGroup((current) => (current === group.value ? null : group.value))
  }

  const scrollToolRail = (direction: 'up' | 'down') => {
    toolGroupRef.current?.scrollBy({
      top: direction === 'up' ? -124 : 124,
      behavior: 'smooth'
    })
    setOpenGroup(null)
  }

  return (
    <aside ref={railRef} className={styles.rail} aria-label={t('chart.drawingTools')}>
      <button
        type="button"
        className={`${styles.scrollButton} ${styles.scrollButtonTop}`}
        aria-label={t('chart.scrollDrawingToolsUp')}
        title={t('chart.scrollUp')}
        onClick={() => scrollToolRail('up')}
      >
        <ChevronUp size={16} />
      </button>
      <div ref={toolGroupRef} className={styles.toolGroup}>
        <button
          type="button"
          className={settings.activeTool === 'cursor' ? styles.active : ''}
          title={t('chart.drawing.cursor')}
          aria-label={t('chart.drawing.cursor')}
          aria-pressed={settings.activeTool === 'cursor'}
          onClick={() => handleToolChange('cursor')}
        >
          <MousePointer2 size={17} />
        </button>

        {drawingToolGroups.map((group) => (
          <div
            key={group.value}
            className={styles.flyoutWrap}
            onMouseEnter={(event) => handleGroupMouseEnter(group, event.currentTarget)}
          >
            <button
              type="button"
              className={isGroupActive(group, settings.activeTool, settings.magnetMode, drawingsHidden, indicatorsHidden) ? styles.active : ''}
              title={t(group.label)}
              aria-label={t(group.label)}
              aria-haspopup={isDirectGroup(group) ? undefined : 'menu'}
              aria-expanded={isDirectGroup(group) ? undefined : openGroup === group.value}
              aria-pressed={isGroupActive(group, settings.activeTool, settings.magnetMode, drawingsHidden, indicatorsHidden)}
              onClick={(event) => {
                if (group.value === 'text') {
                  handleToolChange('text')
                  return
                }
                if (group.value === 'clear') {
                  handleCommand('clearDrawings')
                  return
                }
                toggleGroup(group, event.currentTarget)
              }}
            >
              <GroupIcon group={group.value} />
            </button>

            {!isDirectGroup(group) && openGroup === group.value ? (
              <div
                className={styles.flyoutMenu}
                role="menu"
                aria-label={t(group.label)}
                style={flyoutPosition}
                onMouseLeave={() => setOpenGroup((current) => (current === group.value ? null : current))}
              >
                {group.items.map((item) => (
                  <button
                    key={item.value}
                    type="button"
                    className={isItemActive(item, settings.activeTool, settings.magnetMode, drawingsHidden, indicatorsHidden) ? styles.menuActive : ''}
                    role="menuitem"
                    onClick={() => {
                      if (isDrawingToolOption(item)) {
                        handleToolChange(item.value)
                      } else {
                        handleCommand(item.value)
                      }
                    }}
                  >
                    <MenuItemIcon item={item} />
                    <span>{t(item.label)}</span>
                    {item.shortcut ? <kbd>{item.shortcut}</kbd> : null}
                  </button>
                ))}
              </div>
            ) : null}
          </div>
        ))}
      </div>
      <button
        type="button"
        className={`${styles.scrollButton} ${styles.scrollButtonBottom}`}
        aria-label={t('chart.scrollDrawingToolsDown')}
        title={t('chart.scrollDown')}
        onClick={() => scrollToolRail('down')}
      >
        <ChevronDown size={16} />
      </button>
    </aside>
  )
}

function GroupIcon({ group }: { group: DrawingToolGroup['value'] }) {
  const Icon = groupIcons[group]
  return (
    <>
      <Icon size={17} />
      {!isDirectGroupValue(group) ? <ChevronRight className={styles.chevron} size={11} /> : null}
    </>
  )
}

function MenuItemIcon({ item }: { item: DrawingToolOption | DrawingToolCommand }) {
  const Icon = isDrawingToolOption(item) ? drawingToolIcons[item.value] : commandIcons[item.value]
  return <Icon size={16} />
}

function isDrawingToolOption(item: DrawingToolOption | DrawingToolCommand): item is DrawingToolOption {
  return 'overlayName' in item
}

function isDirectGroup(group: DrawingToolGroup) {
  return isDirectGroupValue(group.value)
}

function isDirectGroupValue(value: DrawingToolGroup['value']) {
  return value === 'text' || value === 'clear'
}

function isGroupActive(
  group: DrawingToolGroup,
  activeTool: DrawingTool,
  magnetMode: DrawingMagnetMode,
  drawingsHidden: boolean,
  indicatorsHidden: boolean
) {
  if (group.value === 'visibility') return drawingsHidden || indicatorsHidden
  if (group.value === 'magnet') return magnetMode !== 'none'
  return group.items.some((item) => isDrawingToolOption(item) && item.value === activeTool)
}

function isItemActive(
  item: DrawingToolOption | DrawingToolCommand,
  activeTool: DrawingTool,
  magnetMode: DrawingMagnetMode,
  drawingsHidden: boolean,
  indicatorsHidden: boolean
) {
  if (isDrawingToolOption(item)) return item.value === activeTool
  if (item.value === 'hideDrawings') return drawingsHidden
  if (item.value === 'hideIndicators') return indicatorsHidden
  if (item.value === 'hideAll') return drawingsHidden && indicatorsHidden
  if (item.value === 'weakMagnet') return magnetMode === 'weak'
  if (item.value === 'strongMagnet') return magnetMode === 'strong'
  return false
}

const groupIcons: Record<DrawingToolGroup['value'], LucideIcon> = {
  line: PenLine,
  fibonacci: WavesLadder,
  shape: Square,
  channel: GitBranch,
  text: TypeIcon,
  visibility: EyeOff,
  magnet: Magnet,
  clear: Eraser
}

const commandIcons: Record<DrawingToolCommand['value'], LucideIcon> = {
  hideDrawings: EyeOff,
  hideIndicators: EyeOff,
  hideAll: EyeOff,
  weakMagnet: Magnet,
  strongMagnet: Magnet,
  clearDrawings: Eraser
}

const drawingToolIcons: Record<DrawingTool, LucideIcon> = {
  cursor: MousePointer2,
  segment: PenLine,
  arrowLine: PenLine,
  trendLine: PenLine,
  rayLine: Route,
  straightLine: PenLine,
  horizontalSegment: MoveHorizontal,
  horizontalRayLine: MoveHorizontal,
  horizontalLine: MoveHorizontal,
  priceLine: MoveHorizontal,
  verticalLine: MoveVertical,
  verticalSegment: MoveVertical,
  verticalRayLine: MoveVertical,
  fibonacciExtension: WavesLadder,
  fibonacciFan: WavesLadder,
  fibonacciLine: WavesLadder,
  rectangle: Square,
  brush: PenLine,
  circle: Circle,
  triangle: Triangle,
  priceChannel: Waves,
  parallelLine: GitBranch,
  parallelRayLine: GitBranch,
  priceTag: Tag,
  text: TypeIcon
}
