import { useMemo, useRef, useState } from 'react'
import MDEditor, { commands as mdCommands } from '@uiw/react-md-editor'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ImagePlus } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import { useToast } from './Toast.jsx'
import { clipboardToMarkdownTable } from '../../utils/pasteTable'
import { downscaleImage } from '../../utils/imageDownscale'

const INDENT_ICON = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
    <path d="M3 4h18v2H3V4zm8 5h10v2H11V9zm0 5h10v2H11v-2zm-8 5h18v2H3v-2zM3 9l4 3-4 3V9z" />
  </svg>
)
const OUTDENT_ICON = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
    <path d="M3 4h18v2H3V4zm8 5h10v2H11V9zm0 5h10v2H11v-2zm-8 5h18v2H3v-2zM7 9l-4 3 4 3V9z" />
  </svg>
)

function fmtFileSize(bytes) {
  if (bytes == null) return '—'
  return bytes >= 1024 * 1024
    ? (bytes / 1024 / 1024).toFixed(1) + ' MB'
    : Math.max(1, Math.round(bytes / 1024)) + ' KB'
}

/** Seçimi tam satırlara genişletip her satıra fn uygular (girinti komutları). */
function transformSelectedLines(state, api, fn) {
  const text = state.text ?? ''
  const start = text.lastIndexOf('\n', Math.max(0, state.selection.start - 1)) + 1
  let end = text.indexOf('\n', state.selection.end)
  if (end === -1) end = text.length
  const next = text.slice(start, end).split('\n').map(fn).join('\n')
  api.setSelectionRange({ start, end })
  api.replaceSelection(next)
  api.setSelectionRange({ start, end: start + next.length })
}

/**
 * Weekly Reports MdField ile AYNI zengin markdown editör çekirdeği — paylaşılan,
 * yeniden kullanılabilir. Toolbar (başlık/kalın/italik/liste/girinti/tablo),
 * Excel→markdown tablo yapıştırma ve kaynak/önizleme geçişi içerir.
 *
 * Görsel yükleme OPSİYONELDİR: `uploadImage(file, caption) => Promise<url|null>`
 * prop'u verilirse araç çubuğuna görsel butonu eklenir; seçilen görsel istemcide
 * küçültülür, açıklama modalı açılır ve imleç konumuna ![caption](url) eklenir.
 * editable=false iken render edilmiş markdown gösterir.
 */
export default function MarkdownEditor({ value, onChange, editable = true, height = 200, uploadImage, makeUniqueCaption }) {
  const t = useT()
  const { theme } = useTheme()
  const toast = useToast()
  const fileRef = useRef(null)
  const caretRef = useRef(null)
  const [pendingFile, setPendingFile] = useState(null)
  const [caption, setCaption] = useState('')
  const [uploading, setUploading] = useState(false)

  const editorCommands = useMemo(() => {
    const tt = (key) => ({ 'aria-label': t(key), title: t(key) })
    const cmds = [
      mdCommands.group([mdCommands.title2, mdCommands.title3, mdCommands.title4], {
        name: 'title', groupName: 'title', buttonProps: tt('wr.cmdTitle'),
      }),
      mdCommands.divider,
      mdCommands.bold, mdCommands.italic, mdCommands.strikethrough,
      mdCommands.divider,
      mdCommands.link, mdCommands.quote,
      mdCommands.divider,
      { ...mdCommands.unorderedListCommand, buttonProps: tt('wr.cmdUl') },
      { ...mdCommands.orderedListCommand, buttonProps: tt('wr.cmdOl') },
      { ...mdCommands.checkedListCommand, buttonProps: tt('wr.cmdTaskList') },
      {
        name: 'indent', keyCommand: 'indent', buttonProps: tt('wr.cmdIndent'), icon: INDENT_ICON,
        execute: (state, api) => transformSelectedLines(state, api, (l) => '    ' + l),
      },
      {
        name: 'outdent', keyCommand: 'outdent', buttonProps: tt('wr.cmdOutdent'), icon: OUTDENT_ICON,
        execute: (state, api) => transformSelectedLines(state, api, (l) => l.replace(/^ {1,4}/, '')),
      },
      mdCommands.divider,
      { ...mdCommands.table, buttonProps: tt('wr.cmdTable') },
      { ...mdCommands.hr, buttonProps: tt('wr.cmdHr') },
    ]
    if (uploadImage) {
      cmds.push(mdCommands.divider, {
        name: 'image-upload', keyCommand: 'image-upload',
        buttonProps: { 'aria-label': t('wr.uploadImage'), title: t('wr.uploadImage') },
        icon: <ImagePlus size={12} />,
        execute: () => fileRef.current?.click(),
      })
    }
    return cmds
  }, [t, uploadImage])

  function recordCaret(e) {
    if (e.target?.tagName !== 'TEXTAREA') return
    caretRef.current = { start: e.target.selectionStart, end: e.target.selectionEnd }
  }

  function handlePasteCapture(e) {
    if (e.target?.tagName !== 'TEXTAREA') return
    const md = clipboardToMarkdownTable(e.clipboardData)
    if (!md) return
    e.preventDefault()
    e.stopPropagation()
    const ta = e.target
    const start = ta.selectionStart ?? (value?.length ?? 0)
    const end = ta.selectionEnd ?? start
    onChange((value ?? '').slice(0, start) + md + (value ?? '').slice(end))
    toast.success(t('wr.pasteTableDone'))
  }

  async function handleFileChosen(e) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setCaption('')
    setPendingFile({ processing: true, name: file.name, originalSize: file.size })
    const processed = await downscaleImage(file)
    setPendingFile({ file: processed, name: file.name, originalSize: file.size, processedSize: processed.size })
  }

  async function doUpload() {
    if (!pendingFile || pendingFile.processing || !pendingFile.file) return
    setUploading(true)
    try {
      const userCap = caption.trim()
      // İsim benzersizliği: aynı incident içinde aynı isim → "ad (2).uzantı" (içerik farklı olsa da
      // iki görsel de yüklenir, isimleri ayrışır, karışmaz). makeUniqueCaption verilmezse ham isim.
      const baseName = userCap || pendingFile.name || 'image'
      const uniqueName = makeUniqueCaption ? makeUniqueCaption(baseName) : baseName
      const url = await uploadImage(pendingFile.file, userCap ? uniqueName : null)
      if (url) {
        const captionLine = userCap ? `\n**${uniqueName}**\n` : '\n'
        const insert = `${captionLine}\n![${uniqueName}](${url})\n`
        const v = value ?? ''
        const c = caretRef.current
        if (c && typeof c.start === 'number' && c.start <= v.length) {
          onChange(v.slice(0, c.start) + insert + v.slice(c.end ?? c.start))
        } else {
          onChange(v + insert)
        }
        caretRef.current = null
        if (pendingFile.processedSize < pendingFile.originalSize) {
          toast.success(t('wr.imageOptimized', fmtFileSize(pendingFile.originalSize), fmtFileSize(pendingFile.processedSize)))
        }
        setPendingFile(null)
        setCaption('')
      } else {
        toast.error(t('wr.uploadFailed'))
      }
    } catch {
      toast.error(t('wr.uploadFailed'))
    } finally {
      setUploading(false)
    }
  }

  if (!editable) {
    return (
      <div className="show-markdown" data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{value || '—'}</ReactMarkdown>
      </div>
    )
  }

  return (
    <div onPasteCapture={handlePasteCapture} onKeyUp={recordCaret} onMouseUp={recordCaret}>
      <div className="wr-editor" data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
        <MDEditor
          value={value ?? ''}
          onChange={(v) => onChange(v ?? '')}
          preview="edit"
          height={height}
          visibleDragbar={true}
          highlightEnable={false}
          commands={editorCommands}
          extraCommands={[mdCommands.codeEdit, mdCommands.codePreview, mdCommands.divider, mdCommands.fullscreen]}
        />
      </div>
      <div className="wr-hint">{t('wr.pasteHint')}</div>

      {uploadImage && (
        <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp,image/bmp"
               style={{ display: 'none' }} onChange={handleFileChosen} />
      )}

      {pendingFile && (
        <div className="modal-overlay" onClick={() => !uploading && setPendingFile(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('wr.uploadImage')}</h3>
            <div style={{ fontSize: '.8em', color: 'var(--text-light)', marginBottom: 6 }}>{t('wr.imageFormats')}</div>
            <p style={{ fontSize: '.85em', marginBottom: 10 }}>
              <strong>{pendingFile.name}</strong>{' · '}
              {pendingFile.processing ? (
                <span style={{ color: 'var(--text-light)' }}>{t('wr.imageProcessing')}</span>
              ) : (
                <span>
                  {t('wr.imageOriginal')}: {fmtFileSize(pendingFile.originalSize)}
                  {pendingFile.processedSize < pendingFile.originalSize && (
                    <> {' → '}<strong style={{ color: '#16a34a' }}>
                      {t('wr.imageOptimizedLabel')}: {fmtFileSize(pendingFile.processedSize)}
                    </strong></>
                  )}
                </span>
              )}
            </p>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: '.9em' }}>
              {t('wr.imageCaption')}
              <textarea className="wr-caption" autoFocus rows={3}
                value={caption} placeholder={t('wr.imageCaptionPlaceholder')}
                onChange={(e) => setCaption(e.target.value)} />
            </label>
            <div className="modal-actions">
              <button className="btn btn-secondary" disabled={uploading}
                onClick={() => setPendingFile(null)}>{t('wr.cancel')}</button>
              <button className="btn btn-primary" disabled={uploading || pendingFile.processing} onClick={doUpload}>
                {uploading ? t('wr.uploading') : t('wr.insertImage')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
