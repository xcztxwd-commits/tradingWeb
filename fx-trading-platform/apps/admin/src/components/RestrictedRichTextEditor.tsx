import type { Editor, JSONContent } from '@tiptap/core'
import TextAlign from '@tiptap/extension-text-align'
import { Color, TextStyle } from '@tiptap/extension-text-style'
import { EditorContent, useEditor, useEditorState } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import { useEffect, useRef } from 'react'

import {
  InternalLink,
  normalizeInternalLinkReference,
  normalizePlatformImageReference,
  PlatformImage
} from './restrictedRichTextExtensions'
import type { InternalLinkReference, PlatformImageReference } from './restrictedRichTextExtensions'
import './RestrictedRichTextEditor.css'

const EMPTY_DOCUMENT: JSONContent = {
  type: 'doc',
  content: [{ type: 'paragraph' }]
}

const RESTRICTED_TEXT_COLORS = [
  { value: '#172033', label: '默认深色', className: 'restricted-rich-text-editor__color--ink' },
  { value: '#1d4ed8', label: '蓝色', className: 'restricted-rich-text-editor__color--blue' },
  { value: '#b91c1c', label: '红色', className: 'restricted-rich-text-editor__color--red' },
  { value: '#15803d', label: '绿色', className: 'restricted-rich-text-editor__color--green' }
] as const

const RESTRICTED_EXTENSIONS = [
  StarterKit.configure({
    blockquote: false,
    code: false,
    codeBlock: false,
    heading: { levels: [1, 2, 3] },
    horizontalRule: false,
    link: false,
    strike: false,
    underline: false
  }),
  TextStyle,
  Color,
  TextAlign.configure({
    types: ['heading', 'paragraph'],
    alignments: ['left', 'center', 'right']
  }),
  PlatformImage,
  InternalLink
]

type MaybePromise<T> = T | Promise<T>

export type RestrictedRichTextDocument = JSONContent

export type RestrictedRichTextEditorProps = Readonly<{
  value?: RestrictedRichTextDocument
  onChange: (document: RestrictedRichTextDocument) => void
  disabled?: boolean
  ariaLabel?: string
  onPickPlatformImage?: () => MaybePromise<PlatformImageReference | null>
  onPickInternalLink?: () => MaybePromise<InternalLinkReference | null>
}>

export default function RestrictedRichTextEditor({
  value = EMPTY_DOCUMENT,
  onChange,
  disabled = false,
  ariaLabel = '受限富文本内容',
  onPickPlatformImage,
  onPickInternalLink
}: RestrictedRichTextEditorProps) {
  const onChangeRef = useRef(onChange)
  const serializedValue = JSON.stringify(value)
  const lastDocumentRef = useRef(serializedValue)
  onChangeRef.current = onChange

  const editor = useEditor({
    extensions: RESTRICTED_EXTENSIONS,
    content: value,
    editable: !disabled,
    immediatelyRender: false,
    shouldRerenderOnTransaction: false,
    editorProps: {
      attributes: {
        'aria-label': ariaLabel,
        'aria-multiline': 'true',
        class: 'restricted-rich-text-editor__content',
        role: 'textbox'
      },
      handlePaste(view, event) {
        const clipboardData = event.clipboardData
        if (!clipboardData) return false
        const containsImage = Array.from(clipboardData.items).some((item) => item.type.startsWith('image/'))
        const containsHtml = clipboardData.getData('text/html').length > 0
        if (!containsImage && !containsHtml) return false

        event.preventDefault()
        const plainText = clipboardData.getData('text/plain')
        if (!containsImage && plainText) {
          const { from, to } = view.state.selection
          view.dispatch(view.state.tr.insertText(plainText, from, to))
        }
        return true
      }
    },
    onUpdate: ({ editor: updatedEditor }) => {
      const document = updatedEditor.getJSON()
      lastDocumentRef.current = JSON.stringify(document)
      onChangeRef.current(document)
    }
  }, [])

  useEffect(() => {
    if (!editor || serializedValue === lastDocumentRef.current) return
    lastDocumentRef.current = serializedValue
    editor.commands.setContent(JSON.parse(serializedValue), { emitUpdate: false })
  }, [editor, serializedValue])

  useEffect(() => {
    editor?.setEditable(!disabled, false)
  }, [disabled, editor])

  return (
    <div className="restricted-rich-text-editor" data-disabled={disabled ? 'true' : 'false'}>
      {editor ? (
        <RestrictedToolbar
          editor={editor}
          disabled={disabled}
          onPickPlatformImage={onPickPlatformImage}
          onPickInternalLink={onPickInternalLink}
        />
      ) : null}
      <EditorContent editor={editor} />
    </div>
  )
}

type RestrictedToolbarProps = Readonly<{
  editor: Editor
  disabled: boolean
  onPickPlatformImage?: () => MaybePromise<PlatformImageReference | null>
  onPickInternalLink?: () => MaybePromise<InternalLinkReference | null>
}>

function RestrictedToolbar({
  editor,
  disabled,
  onPickPlatformImage,
  onPickInternalLink
}: RestrictedToolbarProps) {
  const state = useEditorState({
    editor,
    selector: ({ editor: currentEditor }) => ({
      paragraph: currentEditor.isActive('paragraph'),
      heading1: currentEditor.isActive('heading', { level: 1 }),
      heading2: currentEditor.isActive('heading', { level: 2 }),
      heading3: currentEditor.isActive('heading', { level: 3 }),
      bold: currentEditor.isActive('bold'),
      italic: currentEditor.isActive('italic'),
      bulletList: currentEditor.isActive('bulletList'),
      orderedList: currentEditor.isActive('orderedList'),
      alignment: (['left', 'center', 'right'] as const).find((alignment) =>
        currentEditor.isActive({ textAlign: alignment })) ?? null,
      color: RESTRICTED_TEXT_COLORS.find(({ value }) =>
        currentEditor.isActive('textStyle', { color: value }))?.value ?? null,
      internalLink: currentEditor.isActive('link'),
      canUndo: currentEditor.can().undo(),
      canRedo: currentEditor.can().redo()
    })
  })

  const pickPlatformImage = async () => {
    if (!onPickPlatformImage) return
    const reference = normalizePlatformImageReference(await onPickPlatformImage())
    if (!reference || editor.isDestroyed) return
    editor.chain().focus().insertContent({ type: 'image', attrs: reference }).run()
  }

  const pickInternalLink = async () => {
    if (!onPickInternalLink) return
    const reference = normalizeInternalLinkReference(await onPickInternalLink())
    if (!reference || editor.isDestroyed) return
    editor.chain().focus().setMark('link', reference).run()
  }

  return (
    <div className="restricted-rich-text-editor__toolbar" role="toolbar" aria-label="富文本格式工具栏">
      <ToolbarGroup label="段落样式">
        <ToolbarButton label="正文" active={state.paragraph} disabled={disabled}
          onActivate={() => editor.chain().focus().setParagraph().run()} />
        <ToolbarButton label="标题 1" active={state.heading1} disabled={disabled}
          onActivate={() => editor.chain().focus().toggleHeading({ level: 1 }).run()} />
        <ToolbarButton label="标题 2" active={state.heading2} disabled={disabled}
          onActivate={() => editor.chain().focus().toggleHeading({ level: 2 }).run()} />
        <ToolbarButton label="标题 3" active={state.heading3} disabled={disabled}
          onActivate={() => editor.chain().focus().toggleHeading({ level: 3 }).run()} />
      </ToolbarGroup>

      <ToolbarGroup label="文字格式">
        <ToolbarButton label="粗体" active={state.bold} disabled={disabled}
          onActivate={() => editor.chain().focus().toggleBold().run()} />
        <ToolbarButton label="斜体" active={state.italic} disabled={disabled}
          onActivate={() => editor.chain().focus().toggleItalic().run()} />
      </ToolbarGroup>

      <ToolbarGroup label="列表">
        <ToolbarButton label="项目符号" active={state.bulletList} disabled={disabled}
          onActivate={() => editor.chain().focus().toggleBulletList().run()} />
        <ToolbarButton label="编号列表" active={state.orderedList} disabled={disabled}
          onActivate={() => editor.chain().focus().toggleOrderedList().run()} />
      </ToolbarGroup>

      <ToolbarGroup label="文字颜色">
        {RESTRICTED_TEXT_COLORS.map((color) => (
          <ToolbarButton
            key={color.value}
            label={color.label}
            active={state.color === color.value}
            className={`restricted-rich-text-editor__color ${color.className}`}
            disabled={disabled}
            onActivate={() => editor.chain().focus().setColor(color.value).run()}
          />
        ))}
      </ToolbarGroup>

      <ToolbarGroup label="对齐">
        <ToolbarButton label="左对齐" active={state.alignment === 'left'} disabled={disabled}
          onActivate={() => editor.chain().focus().setTextAlign('left').run()} />
        <ToolbarButton label="居中" active={state.alignment === 'center'} disabled={disabled}
          onActivate={() => editor.chain().focus().setTextAlign('center').run()} />
        <ToolbarButton label="右对齐" active={state.alignment === 'right'} disabled={disabled}
          onActivate={() => editor.chain().focus().setTextAlign('right').run()} />
      </ToolbarGroup>

      {onPickPlatformImage || onPickInternalLink ? (
        <ToolbarGroup label="受控内容">
          {onPickPlatformImage ? (
            <ToolbarButton label="平台图片" disabled={disabled} onActivate={() => { void pickPlatformImage() }} />
          ) : null}
          {onPickInternalLink ? (
            <ToolbarButton label="内部链接" active={state.internalLink} disabled={disabled}
              onActivate={() => { void pickInternalLink() }} />
          ) : null}
          {onPickInternalLink ? (
            <ToolbarButton label="移除链接" disabled={disabled || !state.internalLink}
              onActivate={() => editor.chain().focus().unsetMark('link').run()} />
          ) : null}
        </ToolbarGroup>
      ) : null}

      <ToolbarGroup label="历史">
        <ToolbarButton label="撤销" disabled={disabled || !state.canUndo}
          onActivate={() => editor.chain().focus().undo().run()} />
        <ToolbarButton label="重做" disabled={disabled || !state.canRedo}
          onActivate={() => editor.chain().focus().redo().run()} />
      </ToolbarGroup>
    </div>
  )
}

type ToolbarGroupProps = Readonly<{
  label: string
  children: React.ReactNode
}>

function ToolbarGroup({ label, children }: ToolbarGroupProps) {
  return <div className="restricted-rich-text-editor__toolbar-group" role="group" aria-label={label}>{children}</div>
}

type ToolbarButtonProps = Readonly<{
  label: string
  onActivate: () => void
  active?: boolean
  disabled?: boolean
  className?: string
}>

function ToolbarButton({ label, onActivate, active, disabled, className = '' }: ToolbarButtonProps) {
  return (
    <button
      type="button"
      className={`restricted-rich-text-editor__tool ${className}`.trim()}
      aria-label={label}
      aria-pressed={active}
      title={label}
      disabled={disabled}
      onClick={onActivate}
    >
      {className ? <span className="restricted-rich-text-editor__color-dot" aria-hidden="true" /> : label}
    </button>
  )
}
