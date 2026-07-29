import type { DragEvent } from 'react'

import type {
  ScenarioValidationIssue,
  TimelineAction,
  TimelineActionType,
} from '../model/types.ts'
import {
  TIMELINE_ACTION_TYPES,
  createTimelineAction,
  editTimeline,
  type TimelineCommand,
} from '../timeline/timelineCommands.ts'
import { TimelineActionEditor } from './TimelineActionEditor.tsx'

export type TimelineEditorProps = {
  actions: TimelineAction[]
  locked: boolean
  issues: ScenarioValidationIssue[]
  onChange(actions: TimelineAction[]): void
}

function issuesAtIndex(
  issues: readonly ScenarioValidationIssue[],
  index: number,
): ScenarioValidationIssue[] {
  const path = `timeline[${index}]`
  return issues.filter((issue) =>
    issue.path === path || issue.path.startsWith(`${path}.`))
}

export function TimelineEditor({
  actions,
  locked,
  issues,
  onChange,
}: TimelineEditorProps) {
  function commit(command: TimelineCommand): void {
    if (locked) {
      return
    }
    const edited = editTimeline(actions, command, false)
    if (edited !== actions) {
      onChange(edited)
    }
  }

  function handleAdd(type: TimelineActionType): void {
    if (locked) {
      return
    }
    const action = createTimelineAction(type, crypto.randomUUID())
    commit({ type: 'ADD', action })
  }

  function handleDuplicate(actionId: string): void {
    if (locked) {
      return
    }
    commit({
      type: 'DUPLICATE',
      actionId,
      newId: crypto.randomUUID(),
    })
  }

  function handleDragStart(
    event: DragEvent<HTMLElement>,
    action: TimelineAction,
  ): void {
    if (locked) {
      event.preventDefault()
      return
    }
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', action.id)
  }

  function handleDragOver(event: DragEvent<HTMLElement>): void {
    if (locked) {
      return
    }
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
  }

  function handleDrop(
    event: DragEvent<HTMLElement>,
    action: TimelineAction,
  ): void {
    if (locked) {
      return
    }
    event.preventDefault()
    const actionId = event.dataTransfer.getData('text/plain')
    commit({
      type: 'MOVE_BEFORE',
      actionId,
      targetId: action.id,
    })
  }

  return (
    <section className="trading-lab-timeline" aria-label="场景时间线编辑器">
      <header className="trading-lab-timeline-header">
        <div>
          <h2>动作时间线</h2>
          <p>同一虚拟秒内按下方显式 sequence 顺序执行。</p>
        </div>
        <div className="trading-lab-timeline-add-actions">
          {TIMELINE_ACTION_TYPES.map((type) => (
            <button
              key={type}
              type="button"
              disabled={locked}
              onClick={() => {
                if (locked) {
                  return
                }
                handleAdd(type)
              }}
            >
              新增 {type}
            </button>
          ))}
        </div>
      </header>

      {actions.length === 0 ? (
        <p className="state-block empty">时间线为空，请新增或导入动作。</p>
      ) : (
        <ol className="trading-lab-timeline-list">
          {actions.map((action, index) => {
            const actionIssues = issuesAtIndex(issues, index)
            return (
              <li
                key={action.id}
                className="trading-lab-timeline-item"
                draggable={!locked}
                aria-disabled={locked}
                onDragStart={(event) => handleDragStart(event, action)}
                onDragOver={handleDragOver}
                onDrop={(event) => handleDrop(event, action)}
              >
                <div className="trading-lab-timeline-order-controls">
                  <strong>sequence {action.sequence}</strong>
                  <button
                    type="button"
                    disabled={locked || index === 0}
                    onClick={() => {
                      if (locked) {
                        return
                      }
                      commit({
                        type: 'MOVE_BY',
                        actionId: action.id,
                        offset: -1,
                      })
                    }}
                  >
                    上移
                  </button>
                  <button
                    type="button"
                    disabled={locked || index === actions.length - 1}
                    onClick={() => {
                      if (locked) {
                        return
                      }
                      commit({
                        type: 'MOVE_BY',
                        actionId: action.id,
                        offset: 1,
                      })
                    }}
                  >
                    下移
                  </button>
                  <button
                    type="button"
                    disabled={locked}
                    onClick={() => {
                      if (locked) {
                        return
                      }
                      handleDuplicate(action.id)
                    }}
                  >
                    复制
                  </button>
                  <button
                    type="button"
                    disabled={locked}
                    onClick={() => {
                      if (locked) {
                        return
                      }
                      commit({ type: 'DELETE', actionId: action.id })
                    }}
                  >
                    删除
                  </button>
                </div>
                <TimelineActionEditor
                  action={action}
                  locked={locked}
                  issues={actionIssues}
                  onChange={(editedAction) => {
                    if (locked) {
                      return
                    }
                    commit({ type: 'REPLACE', action: editedAction })
                  }}
                />
              </li>
            )
          })}
        </ol>
      )}
    </section>
  )
}
