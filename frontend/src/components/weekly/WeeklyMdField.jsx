import { useEffect, useId, useMemo, useRef, useState } from 'react'
import MDEditor, { commands as mdCommands } from '@uiw/react-md-editor'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ImagePlus } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import { useToast } from '../ui/Toast.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { clipboardToMarkdownTable } from '../../utils/pasteTable'
import { downscaleImage } from '../../utils/imageDownscale'
import { formatFileSize } from '../../utils/formatBytes.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'

/*
 * Haftalık rapor düzenleyicisinin alan parçaları (2026-09-27, WeeklyReportsPage'ten ayrıldı — davranış aynı):
 * Markdown alanı (Excel yapıştırma → tablo, açıklamalı görsel yükleme, kaynak/önizleme geçişi araç çubuğunda) ve
 * serbest sayı alanı. Yeni: alanın altında karakter sayacı.
 */

/** Araç çubuğundaki özel "görsel yükle" komutu ikonu (varsayılan image komutu yalnız şablon metni ekler). */
const IMAGE_UPLOAD_ICON = (
  <svg width="13" height="13" viewBox="0 0 20 20">
    <path fill="currentColor"
      d="M15 9c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm4-7H1c-.55 0-1 .45-1 1v14c0 .55.45 1 1 1h18c.55 0 1-.45 1-1V3c0-.55-.45-1-1-1zm-1 13l-6-5-2 2-4-5-4 8V4h16v11z" />
  </svg>
)

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

/** Bayt → okunur boyut (≥1MB ise MB, aksi KB). Ortak kaynak utils/formatBytes.formatFileSize (öneri 29); ad korunur. */
export const fmtFileSize = formatFileSize

/** Seçimi tam satırlara genişletip her satıra fn uygular (girinti komutları). */
function transformSelectedLines(state, editorApi, fn) {
  const text = state.text ?? ''
  const start = text.lastIndexOf('\n', Math.max(0, state.selection.start - 1)) + 1
  let end = text.indexOf('\n', state.selection.end)
  if (end === -1) end = text.length
  const next = text.slice(start, end).split('\n').map(fn).join('\n')
  editorApi.setSelectionRange({ start, end })
  editorApi.replaceSelection(next)
  // Blok seçili kalsın — arka arkaya girintileme akıcı olsun
  editorApi.setSelectionRange({ start, end: start + next.length })
}

/**
 * Markdown alanı: geniş tek yazma alanı (sağ üst ikonlarla kaynak/önizleme geçişi), Excel yapıştırma
 * (TSV/HTML→markdown tablo) ve araç çubuğu üzerinden açıklamalı görsel yükleme. highlightEnable kapalı — şeffaf
 * textarea + arkadaki vurgu katmanı kayma/hayalet-metin sorunları üretiyordu.
 */
export function MdField({ value, onChange, editable, reportId, height = 220, label }) {
  const t = useT()
  const { lang } = useLanguage()
  const { theme } = useTheme()
  const toast = useToast()
  const fileRef = useRef(null)
  const caretRef = useRef(null) // kullanıcı textarea'da imleç hareket ettirince dolar; görsel buraya eklenir
  const [pendingFile, setPendingFile] = useState(null) // { file } → açıklama modalı
  const [caption, setCaption] = useState('')
  const [uploading, setUploading] = useState(false)
  const chars = String(value ?? '').length

  const editorCommands = useMemo(() => {
    const tt = (key) => ({ 'aria-label': t(key), title: t(key) })
    return [
      // Word "Stiller" benzeri başlık menüsü (H1 rapor içinde fazla büyük)
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
        name: 'indent',
        keyCommand: 'indent',
        buttonProps: tt('wr.cmdIndent'),
        icon: INDENT_ICON,
        execute: (state, editorApi) => transformSelectedLines(state, editorApi, (l) => '    ' + l),
      },
      {
        name: 'outdent',
        keyCommand: 'outdent',
        buttonProps: tt('wr.cmdOutdent'),
        icon: OUTDENT_ICON,
        execute: (state, editorApi) => transformSelectedLines(state, editorApi, (l) => l.replace(/^ {1,4}/, '')),
      },
      mdCommands.divider,
      { ...mdCommands.table, buttonProps: tt('wr.cmdTable') },
      { ...mdCommands.hr, buttonProps: tt('wr.cmdHr') },
      mdCommands.divider,
      {
        name: 'image-upload',
        keyCommand: 'image-upload',
        buttonProps: { 'aria-label': t('wr.uploadImage'), title: t('wr.uploadImage') },
        icon: IMAGE_UPLOAD_ICON,
        execute: () => fileRef.current?.click(),
      },
    ]
  }, [t])

  // Kullanıcının editördeki imleç konumunu izle — görsel buraya eklenecek
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

  // Görsel seçilince HEMEN optimize et (mail için agresif küçültme) — modalda orijinal→optimize boyutu gösterilir.
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
      const cap = caption.trim()
      const res = await api.weeklyReports.uploadImage(reportId, pendingFile.file, cap || null)
      if (res?.success) {
        const alt = cap || pendingFile.file.name
        const captionLine = cap ? `\n**${cap}**\n` : '\n'
        const insert = `${captionLine}\n![${alt}](/api/weekly-reports/images/${res.data.id})\n`
        const v = value ?? ''
        const c = caretRef.current
        if (c && typeof c.start === 'number' && c.start <= v.length) {
          onChange(v.slice(0, c.start) + insert + v.slice(c.end ?? c.start)) // imleç konumuna
        } else {
          onChange(v + insert) // imleç belirtilmemiş → sona
        }
        caretRef.current = null // kontrollü değer değişimi caret'i bozar; sonraki etkileşim tazeler
        if (pendingFile.processedSize < pendingFile.originalSize) {
          toast.success(t('wr.imageOptimized', fmtFileSize(pendingFile.originalSize), fmtFileSize(pendingFile.processedSize)))
        }
        setPendingFile(null)
        setCaption('')
      } else {
        toast.error(res?.error || t('wr.uploadFailed'))
      }
    } catch {
      toast.error(t('wr.uploadFailed'))
    } finally {
      setUploading(false)
    }
  }

  if (!editable) {
    return <MarkdownView value={value} className="rounded-md border bg-muted/20 px-3 py-2" />
  }

  return (
    <div className="min-w-0" onPasteCapture={handlePasteCapture} onKeyUp={recordCaret} onMouseUp={recordCaret}>
      <div data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
        <MDEditor
          value={value ?? ''}
          onChange={(v) => onChange(v ?? '')}
          preview="edit"
          height={height}
          visibleDragbar={true}
          highlightEnable={false}
          commands={editorCommands}
          extraCommands={[mdCommands.codeEdit, mdCommands.codePreview,
            mdCommands.divider, mdCommands.fullscreen]}
          textareaProps={label ? { 'aria-label': label } : undefined}
        />
      </div>
      <div className="mt-1 flex flex-wrap items-center justify-between gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
        <span>{t('wr.pasteHint')}</span>
        <span data-slot="wr-char-count" className="tabular-nums">{t('wr.ed.chars', chars.toLocaleString(lang === 'en' ? 'en-GB' : 'tr-TR'))}</span>
      </div>
      {/* Görsel seçici — gizli shadcn Input (dosya); araç çubuğundaki "görsel yükle" komutu açar */}
      <Input ref={fileRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp,image/bmp"
        className="hidden" tabIndex={-1} aria-hidden="true" onChange={handleFileChosen} />

      {/* Görsel yükleme — ui/ModalShell (shadcn Dialog); yüklenirken kapatılamaz */}
      <ModalShell open={!!pendingFile} onClose={() => { if (!uploading) setPendingFile(null) }} busy={uploading}
        title={t('wr.uploadImage')} icon={ImagePlus} size="sm"
        footer={<>
          <Button variant="secondary" disabled={uploading} onClick={() => setPendingFile(null)}>{t('wr.cancel')}</Button>
          <Button disabled={uploading || !pendingFile || pendingFile.processing} onClick={doUpload}>
            {uploading ? t('wr.uploading') : t('wr.insertImage')}
          </Button>
        </>}>
        {pendingFile && (
          <div className="flex flex-col gap-2.5">
            <p className="text-[.8em] text-muted-foreground">{t('wr.imageFormats')}</p>
            <AlertBanner tone="info" className="mb-0 text-[.85em]">{t('wr.imageLimitNote')}</AlertBanner>
            <p className="text-[.85em] [overflow-wrap:anywhere]">
              <strong>{pendingFile.name}</strong>{' · '}
              {pendingFile.processing ? (
                <span className="text-muted-foreground">{t('wr.imageProcessing')}</span>
              ) : (
                <span>
                  {t('wr.imageOriginal')}: {fmtFileSize(pendingFile.originalSize)}
                  {pendingFile.processedSize < pendingFile.originalSize && (
                    <> {' → '}<strong className="text-success">
                      {t('wr.imageOptimizedLabel')}: {fmtFileSize(pendingFile.processedSize)}
                    </strong></>
                  )}
                </span>
              )}
            </p>
            <Field label={t('wr.imageCaption')} className="mb-0">
              {({ id }) => (
                <Textarea id={id} autoFocus rows={3}
                  value={caption} placeholder={t('wr.imageCaptionPlaceholder')}
                  onChange={(e) => setCaption(e.target.value)} />
              )}
            </Field>
          </div>
        )}
      </ModalShell>
    </div>
  )
}

/** Salt okunur Markdown gövdesi (`show-markdown` tipografisi); boşsa `empty` metni ya da "Not yok". */
export function MarkdownView({ value, className, empty }) {
  const t = useT()
  const { theme } = useTheme()
  return (
    <div className={cn('show-markdown min-w-0', className)} data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
      {String(value ?? '').trim()
        ? <ReactMarkdown remarkPlugins={[remarkGfm]}>{value}</ReactMarkdown>
        : <p className="text-sm text-muted-foreground">{empty ?? t('wr.ed.noNotes')}</p>}
    </div>
  )
}

/** Önem noktası (Madde 1 sayı alanları) — renk + metin (etiket) birlikte; renk tek başına bilgi taşımaz. */
const DOT = {
  urgent: 'bg-red-600 dark:bg-red-400', high: 'bg-orange-500 dark:bg-orange-400',
  medium: 'bg-amber-400 dark:bg-amber-300', low: 'bg-sky-500 dark:bg-sky-400',
  // Madde 1 durum dağılımı (2026-09-27)
  working: 'bg-blue-600 dark:bg-blue-400', planned: 'bg-violet-500 dark:bg-violet-400',
  on_hold: 'bg-zinc-400 dark:bg-zinc-500', done: 'bg-green-600 dark:bg-green-400',
}

/**
 * Serbest elle yazılabilen sayı alanı — yazarken boş bırakılabilir, yalnız rakam kabul eder; state'e anlık sayı yazılır.
 * `tone` önem noktası, `extra` etiket satırının sağında (ör. geçen haftaya göre fark rozeti).
 */
export function NumInput({ label, value, onChange, editable, tone, extra, below, readOnlyHint }) {
  const id = useId()
  const [text, setText] = useState(value != null ? String(value) : '0')

  useEffect(() => {
    const parsed = text === '' ? 0 : parseInt(text, 10)
    if (value !== parsed) setText(value != null ? String(value) : '')
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps

  function handleChange(e) {
    const cleaned = e.target.value.replace(/[^0-9]/g, '')
    setText(cleaned)
    onChange(cleaned === '' ? 0 : parseInt(cleaned, 10))
  }

  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      {/* Etiket satırı SABİT yükseklik: fark rozeti olan/olmayan alanların kutuları aynı hizada kalsın */}
      <div className="flex h-6 min-w-0 items-center justify-between gap-1">
        <Label htmlFor={id} className="min-w-0 gap-1.5 truncate text-[.82em] font-semibold text-muted-foreground">
          {tone && <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', DOT[tone])} />}
          {label}
        </Label>
        {extra}
      </div>
      <Input id={id} type="text" inputMode="numeric" pattern="[0-9]*" value={text} disabled={!editable}
        title={readOnlyHint} className="tabular-nums" onChange={handleChange}
        onBlur={() => { if (text === '') setText('0') }} />
      {below}
    </div>
  )
}
