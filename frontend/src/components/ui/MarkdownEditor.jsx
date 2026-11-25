import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ImagePlus, ImageOff } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import { useToast } from './Toast.jsx'
import ModalShell from './ModalShell.jsx'
import Field from './Field.jsx'
import { clipboardToMarkdownTable } from '../../utils/pasteTable'
import { downscaleImage } from '../../utils/imageDownscale'
import { formatFileSize } from '../../utils/formatBytes.js'
import {
  uploadToken, uploadPlaceholder, replaceUploadPlaceholder, safeAlt, beginUpload, endUpload, removeUploadPlaceholders,
} from '../../utils/mdUpload.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
// Editör (~1 MB) ilk düzenlenebilir çizimde yüklenir — bu sarmalayıcıyı içe aktaran ekranlar onu taşımaz (2026-10-09).
import LazyMdEditor from './mdEditor/LazyMdEditor.jsx'

// Büyük görünüm penceresi yalnız bir görsele tıklanınca yüklenir (okuma görünümü — "Hızlı Giriş", 2026-11-20)
const ImageLightbox = lazy(() => import('../techerror/ImageLightbox.jsx').then((m) => ({ default: m.ImageLightbox })))

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

// Dosya boyutu metni: ortak utils/formatBytes.formatFileSize (öneri 29 — WeeklyMdField ile birebir aynı kopya kaldırıldı).
const fmtFileSize = formatFileSize

/** Bir yapıştırma / bırakmada en çok bu kadar görsel alınır; fazlası söylenir (sınırsız yükleme yok). */
export const MAX_FILES_PER_DROP = 10
/** Aynı editörde aynı anda en çok bu kadar yükleme sürer; ötekiler sırada bekler. */
export const MAX_PARALLEL_UPLOADS = 3

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

/** Dosya sürükleniyor mu (metin / bağlantı sürüklemesi değil). */
function isFileDrag(e) {
  const types = e?.dataTransfer?.types
  return !!types && Array.from(types).includes('Files')
}

/** Panodaki dosyalar (görseller önce) — yalnız dosya öğeleri; metin öğesi dosya sayılmaz. */
function clipboardFiles(dt) {
  if (!dt) return []
  const out = []
  for (const item of Array.from(dt.items || [])) {
    if (item?.kind !== 'file' || typeof item.getAsFile !== 'function') continue
    const f = item.getAsFile()
    if (f) out.push(f)
  }
  if (out.length === 0) for (const f of Array.from(dt.files || [])) if (f) out.push(f)
  return out
}

/** Panodan gelen görselin adı yoksa (ekran görüntüsü "image.png") okunur bir ad: `gorsel-<yyyyMMdd-HHmmss>.<uzantı>`. */
function pastedName(file, now = new Date()) {
  const n = String(file?.name || '')
  if (n && n !== 'image.png' && n !== 'image.jpg' && n !== 'image.jpeg') return n
  const p = (x) => String(x).padStart(2, '0')
  const ext = String(file?.type || '').split('/')[1]?.replace(/[^a-z0-9]/g, '') || 'png'
  return `gorsel-${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}${p(now.getSeconds())}.${ext}`
}

/**
 * Okuma görünümündeki görsel (`imagePreview`): tıklanınca büyük görünüm (lightbox) açılır; görsel artık yoksa (silinmiş,
 * erişim kalkmış) kırık resim yerine "görsel artık yok" yer tutucusu çizilir.
 */
function MdImage({ src, alt }) {
  const t = useT()
  const [failed, setFailed] = useState(false)
  const [open, setOpen] = useState(false)
  const btnRef = useRef(null)
  const label = String(alt || '').trim() || t('md.imageUnnamed')
  if (failed || !src) {
    return (
      <span data-slot="md-image-gone" className="my-1 inline-flex max-w-full items-start gap-1.5 rounded-md border border-dashed px-2 py-1 align-middle text-xs text-muted-foreground [overflow-wrap:anywhere]">
        <ImageOff aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('md.imageGone', label)}
      </span>
    )
  }
  return (
    <>
      <Button ref={btnRef} type="button" variant="ghost" data-slot="md-image" aria-label={t('md.imageZoom', label)}
        className="h-auto max-w-full cursor-zoom-in rounded-md p-0 hover:bg-transparent" onClick={() => setOpen(true)}>
        <img src={src} alt={alt || ''} loading="lazy" decoding="async" onError={() => setFailed(true)} className="block h-auto max-w-full rounded-md" />
      </Button>
      {open && (
        <Suspense fallback={null}>
          <ImageLightbox open onOpenChange={setOpen} src={src} label={label} returnFocusRef={btnRef} />
        </Suspense>
      )}
    </>
  )
}
const PREVIEW_COMPONENTS = { img: ({ src, alt }) => <MdImage src={src} alt={alt} /> }

/**
 * Weekly Reports MdField ile AYNI zengin markdown editör çekirdeği — paylaşılan,
 * yeniden kullanılabilir. Toolbar (başlık/kalın/italik/liste/girinti/tablo),
 * Excel→markdown tablo yapıştırma ve kaynak/önizleme geçişi içerir.
 *
 * Görsel yükleme OPSİYONELDİR: `uploadImage(file, caption) => Promise<url|null>` prop'u verilirse:
 *   - araç çubuğuna görsel butonu eklenir; seçilen görsel hazırlanır (`prepareFile` verilmişse onunla — tam çözünürlük,
 *     verilmemişse istemcide küçültülür), açıklama penceresi (ui/ModalShell) açılır ve imleç konumuna ![caption](url) eklenir;
 *   - ("Hızlı Giriş", 2026-11-20) yazma alanına YAPIŞTIRILAN ya da BIRAKILAN görseller açıklama sorulmadan yüklenir: imleçte
 *     önce bir yükleme yer tutucusu (`utils/mdUpload`) yazılır, yükleme bitince EN GÜNCEL metindeki yer tutucu gerçek
 *     bağlantıyla değişir (bu arada yazılan metin kaybolmaz), başarısızsa yer tutucu silinir ve nedeni söylenir. Sıra:
 *     Excel / Office tablosu → görsel → metin (metin yapıştırma metin kalır). Bir seferde en çok {@link MAX_FILES_PER_DROP}
 *     görsel, aynı anda en çok {@link MAX_PARALLEL_UPLOADS} yükleme. Editör sökülürse sıradaki yüklemeler başlamaz, süren
 *     yüklemenin sonucu uygulanmaz ve bu editörün yarım yer tutucuları `onUpdate(güncelleyici)` ile (verilmişse — üst
 *     bileşenin EN GÜNCEL değeri üzerinde çalışan işlevsel güncelleme; ölü `value` kopyası değil) metinden silinir.
 * `uploadImage` hata verirse (`Error`) iletisi gösterilir. `prepareFile(file) => Promise<{ file, note? }>` hazırlama hatasında
 * okunur iletiyle `Error` fırlatmalıdır.
 *
 * editable=false iken render edilmiş markdown gösterir; `imagePreview` verilirse görseller tıklanınca büyür, olmayan görsel
 * "artık yok" yazar. `textareaProps` yazma alanına geçer (erişilebilir ad / açıklama).
 */
export default function MarkdownEditor({
  value, onChange, editable = true, height = 200, uploadImage, makeUniqueCaption, prepareFile, imagePreview = false,
  textareaProps, onUpdate,
}) {
  const t = useT()
  const { isDark } = useTheme()
  const toast = useToast()
  const fileRef = useRef(null)
  const caretRef = useRef(null)
  const [pendingFile, setPendingFile] = useState(null)
  const [caption, setCaption] = useState('')
  const [uploading, setUploading] = useState(false)
  const [drag, setDrag] = useState(false)
  const [inFlight, setInFlight] = useState(0)
  // En güncel metin: zaman uyumsuz yükleme bitince değiştirme bunun üzerinde yapılır (yazılan metin ezilmesin). Kendi
  // çağrımız da hemen yazar — aynı tikte biten iki yükleme birbirinin sonucunu silmesin.
  const valueRef = useRef(value ?? '')
  valueRef.current = value ?? ''
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  const onUpdateRef = useRef(onUpdate)
  onUpdateRef.current = onUpdate
  const aliveRef = useRef(true)
  const queueRef = useRef([])
  const activeRef = useRef(0)
  // Bu editörün yer tutucusu metinde duran (sırada ya da süren) yüklemeleri — sökülürken metinden silinir
  const pendingRef = useRef(new Set())
  useEffect(() => {
    aliveRef.current = true
    const pending = pendingRef.current
    const queue = queueRef
    return () => {
      aliveRef.current = false
      queue.current = []                                   // sıradakiler başlamaz
      if (pending.size === 0) return
      const tokens = [...pending]
      pending.clear()
      for (const tk of tokens) endUpload(tk)
      // Yarım yer tutucular kaydı kilitlemesin / metinde kalmasın: üst bileşenin EN GÜNCEL değeri üzerinde silinir
      try { onUpdateRef.current?.((cur) => removeUploadPlaceholders(cur, tokens)) } catch { /* yoksay */ }
    }
  }, [])

  const emit = useCallback((next) => {
    valueRef.current = next
    onChangeRef.current?.(next)
  }, [])

  // Komut KURUCUSU (kütüphane tembel yüklenir; createMdEditor onu yüklendikten sonra çağırır) — kimliği eskiden
  // useMemo'lanan dizininkiyle aynı bağımlılıklara bağlı, editör komutları aynı anlarda yeniden kaydeder.
  const editorCommands = useCallback((mdCommands) => {
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

  /** Görseli yüklemeye hazırlar: `prepareFile` (tam çözünürlük, tür / boyut denetimi) ya da eski küçültme. */
  async function prepare(file) {
    if (prepareFile) return prepareFile(file)
    return { file: await downscaleImage(file) }
  }

  /** Sıradaki yüklemeleri başlatır (aynı anda en çok MAX_PARALLEL_UPLOADS). */
  function pump() {
    while (aliveRef.current && activeRef.current < MAX_PARALLEL_UPLOADS && queueRef.current.length > 0) {
      const job = queueRef.current.shift()
      activeRef.current++
      runJob(job).finally(() => {
        activeRef.current--
        if (aliveRef.current) setInFlight((n) => Math.max(0, n - 1))
        pump()
      })
    }
  }

  async function runJob({ file, token, name }) {
    let url = null
    let problem = null
    try {
      try {
        const prepared = await prepare(file)
        if (!aliveRef.current) return
        url = await uploadImage(prepared.file, name)
        if (!url) problem = t('wr.uploadFailed')
      } catch (e) {
        problem = e?.message || t('wr.uploadFailed')
      }
      if (!aliveRef.current) return
      const replacement = url ? `![${safeAlt(name)}](${url})` : ''
      const r = replaceUploadPlaceholder(valueRef.current, token, replacement)
      if (r.found) emit(r.text)
      if (problem) toast.error(t('md.uploadFailedNamed', name, problem))
    } finally {
      pendingRef.current.delete(token)
      endUpload(token)
    }
  }

  /** Yapıştırılan / bırakılan görselleri imlece yer tutucularıyla ekler ve yüklemeleri sıraya koyar. */
  function addFiles(files, caret) {
    if (!uploadImage || !files.length) return
    const list = files.slice(0, MAX_FILES_PER_DROP)
    if (files.length > list.length) toast.info(t('md.tooManyFiles', MAX_FILES_PER_DROP, files.length))
    const jobs = list.map((f) => {
      const base = pastedName(f)
      const name = makeUniqueCaption ? makeUniqueCaption(base) : base
      const token = uploadToken()
      pendingRef.current.add(token)
      beginUpload(token)
      return { file: f, token, name }
    })
    const cur = valueRef.current
    const at = caret && typeof caret.start === 'number' ? Math.min(caret.start, cur.length) : cur.length
    const end = caret && typeof caret.end === 'number' ? Math.min(Math.max(caret.end, at), cur.length) : at
    const before = cur.slice(0, at)
    const after = cur.slice(end)
    const snippet = jobs.map((j) => uploadPlaceholder(t('md.uploadingAlt', j.name), j.token)).join('\n\n')
    const lead = before === '' || before.endsWith('\n\n') ? '' : (before.endsWith('\n') ? '\n' : '\n\n')
    const tail = after === '' || after.startsWith('\n\n') ? '' : (after.startsWith('\n') ? '\n' : '\n\n')
    emit(before + lead + snippet + tail + after)
    caretRef.current = null
    queueRef.current.push(...jobs)
    setInFlight((n) => n + jobs.length)
    pump()
  }

  function handlePasteCapture(e) {
    if (e.target?.tagName !== 'TEXTAREA') return
    let md = null
    try { md = clipboardToMarkdownTable(e.clipboardData) } catch { md = null }
    if (md) {
      e.preventDefault()
      e.stopPropagation()
      const ta = e.target
      const cur = valueRef.current
      const start = Math.min(ta.selectionStart ?? cur.length, cur.length)
      const end = Math.min(Math.max(ta.selectionEnd ?? start, start), cur.length)
      emit(cur.slice(0, start) + md + cur.slice(end))
      toast.success(t('wr.pasteTableDone'))
      return
    }
    if (!uploadImage) return
    const files = clipboardFiles(e.clipboardData)
    if (files.length === 0) return
    // Dosya + metin (ör. Office'ten kopyalanan biçimli metin): metin yapıştırılır. Görsel dosyası varsa görsel alınır.
    const images = files.filter((f) => String(f.type || '').startsWith('image/'))
    const types = Array.from(e.clipboardData?.types || []).map(String)
    if (images.length === 0 && types.some((x) => x.startsWith('text/'))) return
    e.preventDefault()
    e.stopPropagation()
    const ta = e.target
    addFiles(images.length ? images : files, { start: ta.selectionStart, end: ta.selectionEnd })
  }

  function handleDrop(e) {
    if (!uploadImage || !isFileDrag(e)) return
    e.preventDefault()
    e.stopPropagation()
    setDrag(false)
    const files = Array.from(e.dataTransfer?.files || []).filter(Boolean)
    if (files.length) addFiles(files, caretRef.current)
  }

  async function handleFileChosen(e) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setCaption('')
    setPendingFile({ processing: true, name: file.name, originalSize: file.size })
    try {
      const prepared = await prepare(file)
      if (!aliveRef.current) return
      setPendingFile({ file: prepared.file, name: file.name, originalSize: file.size, processedSize: prepared.file.size })
    } catch (err) {
      if (!aliveRef.current) return
      setPendingFile(null)
      toast.error(err?.message || t('wr.uploadFailed'))
    }
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
        const v = valueRef.current
        const c = caretRef.current
        if (c && typeof c.start === 'number' && c.start <= v.length) {
          emit(v.slice(0, c.start) + insert + v.slice(c.end ?? c.start))
        } else {
          emit(v + insert)
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
    } catch (err) {
      toast.error(err?.message || t('wr.uploadFailed'))
    } finally {
      setUploading(false)
    }
  }

  if (!editable) {
    return (
      <div className="show-markdown" data-color-mode={isDark ? 'dark' : 'light'}>
        <ReactMarkdown remarkPlugins={[remarkGfm]} components={imagePreview ? PREVIEW_COMPONENTS : undefined}>{value || '—'}</ReactMarkdown>
      </div>
    )
  }

  return (
    <div onPasteCapture={handlePasteCapture} onKeyUp={recordCaret} onMouseUp={recordCaret} onSelect={recordCaret}
      onDragOver={uploadImage ? (e) => { if (isFileDrag(e)) { e.preventDefault(); setDrag(true) } } : undefined}
      onDragLeave={uploadImage ? () => setDrag(false) : undefined}
      onDrop={uploadImage ? handleDrop : undefined}>
      <div className={cn('wr-editor rounded-[3px]', drag && 'ring-2 ring-primary/60')} data-color-mode={isDark ? 'dark' : 'light'}
        data-drag={drag || undefined}>
        <LazyMdEditor
          value={value ?? ''}
          onChange={(v) => emit(v ?? '')}
          preview="edit"
          height={height}
          visibleDragbar={true}
          highlightEnable={false}
          commands={editorCommands}
          extraCommands={(mdCommands) => [mdCommands.codeEdit, mdCommands.codePreview, mdCommands.divider, mdCommands.fullscreen]}
          textareaProps={textareaProps}
        />
      </div>
      <div className="mt-1 text-xs text-muted-foreground">{t(uploadImage ? 'md.pasteHintImages' : 'wr.pasteHint')}</div>
      {inFlight > 0 && (
        <div data-slot="md-uploading" role="status" className="mt-1 text-xs font-medium text-primary">
          {t('md.uploadingCount', inFlight)}
        </div>
      )}

      {uploadImage && (
        <Input ref={fileRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp,image/bmp"
               hidden tabIndex={-1} onChange={handleFileChosen} />
      )}

      {/* Görsel açıklama penceresi — ModalShell (shadcn Dialog): Escape / odak tuzağı / odak iadesi kabuktan;
          yükleme sürerken kapatma yolları kapalı (busy). */}
      {pendingFile && (
        <ModalShell open onClose={() => setPendingFile(null)} busy={uploading} size="sm"
          title={t('wr.uploadImage')} icon={ImagePlus} closeLabel={t('wr.cancel')}
          footer={(
            <>
              <Button variant="secondary" disabled={uploading}
                onClick={() => setPendingFile(null)}>{t('wr.cancel')}</Button>
              <Button disabled={uploading || pendingFile.processing} onClick={doUpload}>
                {uploading ? t('wr.uploading') : t('wr.insertImage')}
              </Button>
            </>
          )}>
          <p className="mb-1.5 text-[.8em] text-muted-foreground">{t('wr.imageFormats')}</p>
          <p className="mb-2.5 text-[.85em] [overflow-wrap:anywhere]">
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
        </ModalShell>
      )}
    </div>
  )
}
