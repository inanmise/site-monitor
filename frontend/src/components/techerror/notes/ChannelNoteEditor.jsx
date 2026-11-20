import { useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react'
import { Eye, History, IndentDecrease, IndentIncrease, PencilLine, RefreshCw, Save, ShieldAlert, Undo2, Upload, X } from 'lucide-react'
import { api } from '../../../api/client'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import { useTheme } from '../../../i18n/theme.jsx'
import { useStickyBottomBar } from '../../../hooks/useStickyBottomBar.js'
import { useLatestCallback } from '../../../hooks/useLatestCallback.js'
import { storageOwner } from '../../../utils/personalStorage.js'
import { useUnsavedWork } from '../../../utils/unsavedWork.js'
import { clipboardToMarkdownTable } from '../../../utils/pasteTable'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { cn } from '@/lib/utils'
import AlertBanner from '../../ui/AlertBanner.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import LazyMdEditor from '../../ui/mdEditor/LazyMdEditor.jsx'
import { fmtDateTime } from '../TechErrorParts.jsx'
import { formatBytes, formatNumber } from '../techErrorModel.js'
import { ImagePrepError, prepareImage } from '../imagePrep.js'
import { isLockConflict, lockRetryDelayMs } from '../ReportImages.jsx'
import { MAIN_ACTION_GRID, SLOT_ACTIONS_WRAP, SLOT_ACTION_CLASS, shortcutKeys, useSlotCapture } from '../capture/SlotCapture.jsx'
import { CaptureSessionChip, CaptureStopNotice, useCaptureScope } from '../capture/CaptureParts.jsx'
import { imageFromDataTransfer } from '../capture/captureModel.js'
import { renameClipboardFile } from '../capture/clipboardImage.js'
import { isFileDrag, useFileDropGuard } from '../capture/useFileDropGuard.js'
import { LazyNoteView } from './lazyParts.jsx'
import NoteImageList from './NoteImageList.jsx'
import NoteVersionsDialog from './NoteVersionsDialog.jsx'
import {
  conflictInfoOf, dropNoteBackup, insertAtCaret, isChannelGone, isNoteConflict, isOldServerAnswer, limitsOf, noteCharCount,
  noteDirty, noteImageMarkdown, noteImageRefIds, noteImageRefusalKey, normalizeNoteBody, readNoteBackup, retryAfterSeconds,
  writeNoteBackup,
} from './noteModel.js'

/** Tarayıcının açıp PNG / JPEG'e çevirebildiği türler (rapor görsel yuvalarıyla aynı — imagePrep çevirir). */
const ACCEPT = 'image/png,image/jpeg,image/webp,image/gif,image/bmp,image/avif'
const BYTE_PARAMS = new Set(['te.img.errTooBig', 'te.img.errStillLarge'])
/** Pano / ekran görüntüsü dosya adlarının öneki (`kanal-notu-pano-…png`, `kanal-notu-ekran-…png`). */
export const NOTE_CAPTURE_ID = 'kanal-notu'
/** Yazarken yerel yedek bu kadar sonra yazılır. */
export const NOTE_BACKUP_DELAY_MS = 1000

/** Seçimi tam satırlara genişletip her satıra fn uygular (girinti komutları — ui/MarkdownEditor ile aynı). */
function transformSelectedLines(state, mdApi, fn) {
  const text = state.text ?? ''
  const start = text.lastIndexOf('\n', Math.max(0, state.selection.start - 1)) + 1
  let end = text.indexOf('\n', state.selection.end)
  if (end === -1) end = text.length
  const next = text.slice(start, end).split('\n').map(fn).join('\n')
  mdApi.setSelectionRange({ start, end })
  mdApi.replaceSelection(next)
  mdApi.setSelectionRange({ start, end: start + next.length })
}

const outdentLine = (line) => {
  let n = 0
  while (n < 4 && line[n] === ' ') n++
  return line.slice(n)
}

const NO_EXTRA_COMMANDS = () => []

/**
 * KANAL NOTU DÜZENLEYİCİSİ (2026-11-20; tembel parça). Markdown (GFM) yazılır; görseller rapor görsel yuvalarıyla AYNI üç
 * yoldan gelir — Görsel yükle → Ekrandan al → Panodan yapıştır (aynı düğmeler, aynı sıra) — ayrıca yazma alanına
 * yapıştırma / sürükle-bırak. Hat: `prepareImage` (tarayıcıda hazırlama) → yükleme → imlece KENDİ satırında
 * `![ad](te-note-img:<kimlik>)`. Yazma alanına yapıştırmada sıra: Office / Excel tablosu → görsel → metin.
 *
 * KAYIT: açık "Kaydet" (otomatik kayıt YOK), temizken kapalı; Ctrl/⌘+S. Sürüm denetimli — başkası arada kaydettiyse 409:
 * kim / ne zaman kaydettiği söylenir, taslak korunur, "En son hâli yükle". Uç yoksa (eski pod / kanal kalktı) "geçici
 * olarak kullanılamıyor", taslak korunur. Kayıt sürerken yazılan metin kaybolmaz (yanıt yalnız gönderilen metne eşit
 * taslağın yerine geçer). Kaydedilmemiş taslak tarayıcıda yedeklenir; bulunan yedek KENDİLİĞİNDEN uygulanmaz ("geri yükle").
 *
 * Önizleme notun kilitli görüntüleyicisidir (`ChannelNoteView`) — düzenleyicinin kendi önizlemesi kullanılmaz.
 */
export default function ChannelNoteEditor({
  channelId, note, initialDraft = null, onDirtyChange, onSaved, onImagesChange, onReloadLatest, onClose,
}) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const { isDark } = useTheme()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const stickyBarRef = useStickyBottomBar()
  const keys = shortcutKeys()
  const canEdit = !!note?.can_edit
  const limits = useMemo(() => limitsOf(note), [note])

  const [base, setBase] = useState(() => ({ body: normalizeNoteBody(note?.body ?? ''), version: note?.version ?? 0 }))
  const [draft, setDraft] = useState(() => (typeof initialDraft?.body === 'string' ? initialDraft.body : (note?.body ?? '')))
  const [images, setImages] = useState(() => (Array.isArray(note?.images) ? note.images : []))
  const [imageBytes, setImageBytes] = useState(() => (typeof note?.image_bytes === 'number' ? note.image_bytes : null))
  const [mode, setMode] = useState('write')
  const [saving, setSaving] = useState(false)
  const [conflict, setConflict] = useState(null)          // { currentVersion, by, at }
  const [unavailable, setUnavailable] = useState(null)    // 'old' | 'gone'
  const [saveProblem, setSaveProblem] = useState(null)    // { text, details }
  const [bodyProblem, setBodyProblem] = useState(null)    // sunucunun `body` alan hatası
  const [imgBusy, setImgBusy] = useState(false)
  const [imgPhase, setImgPhase] = useState(null)          // 'prep' | 'upload'
  const [imgProblem, setImgProblem] = useState(null)
  const [imgNote, setImgNote] = useState(null)
  const [kept, setKept] = useState(null)                  // yüklenemeyen HAZIR görsel (yeniden almadan yeniden dene)
  const [deletingId, setDeletingId] = useState(null)
  const [drag, setDrag] = useState(false)
  const [versionsOpen, setVersionsOpen] = useState(false)
  // Kayıt bitince (başarılı ya da değil) yedek zamanlayıcısı yeniden kurulsun diye artar — kayıt başında iptal edilen
  // zamanlayıcı, kayıt sürerken yazılan ya da kaydedilemeyen taslak için yeniden kurulmazdı
  const [backupTick, setBackupTick] = useState(0)
  const versionsBtnRef = useRef(null)
  // Bu tarayıcıda bulunan eski taslak (kayıtlı metinden ve açılış taslağından farklıysa) — kendiliğinden uygulanmaz
  const [backup, setBackup] = useState(() => {
    if (!note?.can_edit) return null
    const b = readNoteBackup(channelId)
    if (!b) return null
    const body = normalizeNoteBody(b.body)
    const opening = normalizeNoteBody(typeof initialDraft?.body === 'string' ? initialDraft.body : (note?.body ?? ''))
    return body === normalizeNoteBody(note?.body ?? '') || body === opening ? null : b
  })

  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])
  // Kayıtlı metne eşit (artık gereksiz) yedek silinir
  useEffect(() => {
    if (!canEdit) return
    const b = readNoteBackup(channelId)
    if (b && normalizeNoteBody(b.body) === normalizeNoteBody(note?.body ?? '')) dropNoteBackup(channelId)
    // yalnız açılışta
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  useCaptureScope()
  // Yazma alanının / görsel alanının dışına bırakılan dosya sayfayı o dosyaya götürmesin
  useFileDropGuard(canEdit)

  const wrapRef = useRef(null)
  const inputRef = useRef(null)
  const caretRef = useRef(null)
  const pendingCaret = useRef(null)
  const draftRef = useRef(draft)
  draftRef.current = draft
  const baseRef = useRef(base)
  baseRef.current = base
  const backupRef = useRef(backup)
  backupRef.current = backup
  const backupTimer = useRef(null)
  const wroteBackup = useRef(false)
  const imgBusyRef = useRef(false)
  // Görsel listesi + bayt toplamı: yükleme / silme sonucu hem duruma hem üst bileşene AYNI değerle yazılır
  const imagesRef = useRef(images)
  imagesRef.current = images
  const imageBytesRef = useRef(imageBytes)
  imageBytesRef.current = imageBytes

  const dirty = useMemo(() => noteDirty(draft, base.body), [draft, base.body])
  const chars = useMemo(() => noteCharCount(draft), [draft])
  const tooLong = chars > limits.max_chars
  const dirtyRef = useRef(dirty)
  dirtyRef.current = dirty

  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange])
  useUnsavedWork(dirty)
  useEffect(() => {
    if (!dirty) return undefined
    const h = (e) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [dirty])

  // Yazarken yerel yedek — uygulama içinde başka yere geçmek emeği silmesin. Çözülmemiş ESKİ yedek varken yazarken
  // üstüne yazılmaz (kullanıcı önce "geri yükle" ya da "taslağı sil" der); ama çakışma, "en son hâli yükle", uç yokluğu ve
  // ayrılma ŞİMDİKİ taslağı koşulsuz yazar (`keepDraft`) — yoksa düzenleyici yeniden kurulunca yazılan metin kaybolur, yalnız
  // eski yedek önerilirdi. Taslak temizlenince bu sayfanın yazdığı yedek silinir.
  useEffect(() => {
    if (!canEdit || backup) return undefined
    if (!dirty) {
      if (wroteBackup.current) { dropNoteBackup(channelId); wroteBackup.current = false }
      return undefined
    }
    const h = setTimeout(() => {
      if (writeNoteBackup(channelId, { version: baseRef.current.version, body: draftRef.current })) wroteBackup.current = true
    }, NOTE_BACKUP_DELAY_MS)
    backupTimer.current = h
    return () => clearTimeout(h)
  }, [draft, dirty, canEdit, channelId, backup, backupTick])
  /**
   * Şimdiki taslağı KOŞULSUZ yedekler (eski, çözülmemiş yedeğin yerine geçer — kullanıcının en son yazdığı budur) ve
   * eski yedek bandını kapatır: banttaki "geri yükle" artık depodaki taslakla aynı şeyi söylemezdi.
   */
  const keepDraft = useLatestCallback((version) => {
    clearTimeout(backupTimer.current)
    if (!canEdit || !dirtyRef.current) return
    if (writeNoteBackup(channelId, { version, body: draftRef.current })) wroteBackup.current = true
    if (backupRef.current && alive.current) { backupRef.current = null; setBackup(null) }
  })
  // Ayrılırken son saniye de yedeklenir — eski yedek bandı açıksa da (yazılan metin kaybolmasın); çıkış yapılmışsa yazılmaz
  useEffect(() => () => {
    if (!canEdit || !dirtyRef.current || !storageOwner()) return
    writeNoteBackup(channelId, { version: baseRef.current.version, body: draftRef.current })
    // yalnız ayrılırken
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Programla eklenen parçadan sonra imleç eklenen yerin ardına gelir (odak başka yere geçtiyse çalınmaz)
  useEffect(() => {
    if (pendingCaret.current == null) return
    const at = pendingCaret.current
    pendingCaret.current = null
    caretRef.current = { start: at, end: at }
    const ta = wrapRef.current?.querySelector('textarea')
    const active = document.activeElement
    const free = !active || active === document.body || !!wrapRef.current?.contains(active)
    if (ta && mode === 'write' && free) {
      try { ta.focus({ preventScroll: true }); ta.setSelectionRange(at, at) } catch { /* yok */ }
    }
  }, [draft, mode])

  // Başvuru kümesi yalnız içerik değişince yenilenir (görsel listesi her tuşta yeniden çizilmesin)
  const deferredDraft = useDeferredValue(draft)
  const refKey = useMemo(() => noteImageRefIds(deferredDraft).join(','), [deferredDraft])
  const usedIds = useMemo(() => new Set(refKey ? refKey.split(',') : []), [refKey])

  const editorCommands = useCallback((md) => {
    const tt = (key) => ({ 'aria-label': t(key), title: t(key) })
    return [
      md.group([md.title2, md.title3, md.title4], { name: 'title', groupName: 'title', buttonProps: tt('wr.cmdTitle') }),
      md.divider,
      md.bold, md.italic, md.strikethrough,
      md.divider,
      md.link, md.quote, md.code,
      md.divider,
      { ...md.unorderedListCommand, buttonProps: tt('wr.cmdUl') },
      { ...md.orderedListCommand, buttonProps: tt('wr.cmdOl') },
      { ...md.checkedListCommand, buttonProps: tt('wr.cmdTaskList') },
      {
        name: 'indent', keyCommand: 'indent', buttonProps: tt('wr.cmdIndent'), icon: <IndentIncrease size={13} aria-hidden="true" />,
        execute: (state, mdApi) => transformSelectedLines(state, mdApi, (l) => `    ${l}`),
      },
      {
        name: 'outdent', keyCommand: 'outdent', buttonProps: tt('wr.cmdOutdent'), icon: <IndentDecrease size={13} aria-hidden="true" />,
        execute: (state, mdApi) => transformSelectedLines(state, mdApi, outdentLine),
      },
      md.divider,
      { ...md.table, buttonProps: tt('wr.cmdTable') },
      { ...md.hr, buttonProps: tt('wr.cmdHr') },
    ]
  }, [t])

  const textareaProps = useMemo(() => ({
    'aria-label': t('te.note.editorLabel'), placeholder: t('te.note.placeholder'), 'data-slot': 'te-note-textarea', spellCheck: true,
  }), [t])

  function recordCaret(e) {
    if (e.target?.tagName !== 'TEXTAREA') return
    caretRef.current = { start: e.target.selectionStart, end: e.target.selectionEnd }
  }

  /** İmlece (seçimin sonuna) kendi satırında bir parça ekler. */
  const insertSnippet = useCallback((snippet) => {
    const cur = draftRef.current
    const c = caretRef.current
    const at = c && typeof c.end === 'number' ? c.end : (c && typeof c.start === 'number' ? c.start : cur.length)
    const r = insertAtCaret(cur, at, snippet)
    pendingCaret.current = r.caret
    setDraft(r.body)
    setMode('write')
  }, [])

  const insertImage = useCallback((img) => {
    insertSnippet(noteImageMarkdown(img.id, img.file_name, t('te.note.img.altFallback')))
  }, [insertSnippet, t])

  const prepNoteText = (n) => [
    n.resized && t('te.img.resized', `${n.fromW} × ${n.fromH}`, `${n.toW} × ${n.toH}`, formatBytes(n.fromBytes, uiLang), formatBytes(n.toBytes, uiLang)),
    n.converted && t('te.img.converted', n.fromKind, n.toKind),
    n.rotated && t('te.img.rotated'),
  ].filter(Boolean).join(' ')

  const applyImages = useLatestCallback((list, bytes) => {
    imagesRef.current = list
    imageBytesRef.current = bytes
    setImages(list)
    setImageBytes(bytes)
    onImagesChange?.(list, bytes)
  })

  /** Hazırlanmış görseli yükler; kilit çakışmasında AYNI dosya sunucunun önerdiği süre sonra BİR KEZ yeniden gönderilir. */
  async function sendImage(prepared) {
    setImgPhase('upload')
    try {
      let res = await api.techErrors.uploadChannelNoteImage(channelId, prepared.file)
      if (isLockConflict(res)) {
        await new Promise((r) => { setTimeout(r, lockRetryDelayMs(res)) })
        if (!alive.current) return
        res = await api.techErrors.uploadChannelNoteImage(channelId, prepared.file)
      }
      if (!alive.current) return
      if (res?.success && res.data?.image && res.data.image.id != null) {
        const img = res.data.image
        setKept(null)
        setImgProblem(null)
        const known = imagesRef.current.some((i) => String(i.id) === String(img.id))
        const bytes = imageBytesRef.current == null || known ? imageBytesRef.current : imageBytesRef.current + (Number(img.size_bytes) || 0)
        applyImages([img, ...imagesRef.current.filter((i) => String(i.id) !== String(img.id))], bytes)
        insertImage(img)
        const changed = prepared.info?.resized || prepared.info?.converted || prepared.info?.rotated
        setImgNote(changed ? prepNoteText(prepared.info) : null)
        toast.success(changed ? `${t('te.note.img.added')} ${prepNoteText(prepared.info)}` : t('te.note.img.added'), changed ? 8000 : undefined)
      } else if (res == null) {
        // 401: oturum yolu çalışır; hazır görsel bekletilir
        setKept(prepared)
        setImgProblem(t('te.img.uploadError'))
      } else {
        const wait = res.code === 'RATE_LIMITED' ? (retryAfterSeconds(res) ?? 60) : null
        const key = noteImageRefusalKey(res, wait)
        // Eski pod'un genel "bulunamadı" metni yerine ne olduğunu söyleyen metin; ötekilerde sunucunun metni
        setImgProblem(isOldServerAnswer(res) ? t('te.note.unavailableBody') : res.error || (key ? t(key, wait ?? 60) : t('te.img.uploadError')))
        // Yalnız geçici retlerde (kilit, hız sınırı, uç geçici yok) hazır görsel bekletilir; doğrulama / sınır reddi
        // aynı dosyayla yine aynı sonucu verir
        setKept(isLockConflict(res) || res.code === 'RATE_LIMITED' || isOldServerAnswer(res) || res.status === 0 || res.status >= 500 ? prepared : null)
      }
    } catch (e) {
      if (!alive.current) return
      setImgProblem(e?.message || t('te.img.uploadError'))
      setKept(prepared)   // ağ hatası: kırpılmış ekran görüntüsü yeniden alınmasın
    }
  }

  /** Dosya → tarayıcıda hazırlama → yükleme → imlece ekleme. Bir anda tek yükleme. */
  const addImage = useLatestCallback(async (file) => {
    if (!canEdit || !file) return
    if (imgBusyRef.current) { setImgNote(t('te.cap.pasteBusy')); return }
    imgBusyRef.current = true
    setImgProblem(null)
    setImgNote(null)
    setKept(null)
    setImgBusy(true)
    setImgPhase('prep')
    try {
      let prepared
      try {
        prepared = await prepareImage(file)
      } catch (e) {
        if (!alive.current) return
        if (e instanceof ImagePrepError) {
          const params = BYTE_PARAMS.has(e.key) ? e.params.map((n) => formatBytes(n, uiLang)) : e.params
          setImgProblem(t(e.key, ...params))
        } else {
          setImgProblem(t('te.img.errBrowser'))
        }
        return
      }
      if (!alive.current) return
      await sendImage(prepared)
    } finally {
      imgBusyRef.current = false
      if (alive.current) {
        setImgBusy(false)
        setImgPhase(null)
        if (inputRef.current) inputRef.current.value = ''
      }
    }
  })

  async function retryKept() {
    const prepared = kept
    if (!prepared || imgBusyRef.current) return
    imgBusyRef.current = true
    setImgProblem(null)
    setImgBusy(true)
    try {
      await sendImage(prepared)
    } finally {
      imgBusyRef.current = false
      if (alive.current) { setImgBusy(false); setImgPhase(null) }
    }
  }

  const cap = useSlotCapture({
    slotId: NOTE_CAPTURE_ID, label: t('te.note.img.zoneName'), busy: imgBusy, readOnly: !canEdit, onFile: addImage,
  })
  const mainCount = 1 + (cap.snipButton ? 1 : 0) + (cap.pasteButton ? 1 : 0)

  /** Yazma alanına yapıştırma: Office / Excel tablosu → görsel → (ötekiler) tarayıcının kendi metin yapıştırması. */
  function onPasteCapture(e) {
    const ta = e.target
    if (!canEdit || ta?.tagName !== 'TEXTAREA') return
    let md = null
    // Pano okunamıyorsa (tarayıcı kısıtı) tablo çevirisi atlanır — yapıştırmanın kalanı (görsel / metin) sürer
    try { md = clipboardToMarkdownTable(e.clipboardData) } catch { md = null }
    if (md) {
      e.preventDefault()
      e.stopPropagation()
      const cur = draftRef.current
      const start = Math.min(ta.selectionStart ?? cur.length, cur.length)
      const end = Math.min(Math.max(ta.selectionEnd ?? start, start), cur.length)
      pendingCaret.current = start + md.length
      setDraft(cur.slice(0, start) + md + cur.slice(end))
      toast.success(t('wr.pasteTableDone'))
      return
    }
    const { file, image } = imageFromDataTransfer(e.clipboardData)
    if (!file) return
    // Görsel olmayan dosya + metin: metin yapıştırılır. Yalnız dosya (PDF…) yükleme yoluna gider — türünü söyleyen hata yazar
    const types = Array.from(e.clipboardData?.types || []).map(String)
    if (!image && types.some((x) => x.startsWith('text/'))) return
    e.preventDefault()
    e.stopPropagation()
    caretRef.current = { start: ta.selectionStart, end: ta.selectionEnd }
    addImage(image ? renameClipboardFile(file, NOTE_CAPTURE_ID) : file)
  }

  const refreshImages = useCallback(async () => {
    try {
      const res = await api.techErrors.channelNote(channelId)
      if (!alive.current || !res?.success || !res.data) return
      applyImages(Array.isArray(res.data.images) ? res.data.images : [], typeof res.data.image_bytes === 'number' ? res.data.image_bytes : null)
    } catch { /* okunamadı: eldeki liste kalır */ }
  }, [channelId, applyImages])

  async function removeImage(img) {
    if (deletingId != null) return
    const name = img.file_name || t('te.note.view.imageUnnamed')
    const yes = await showConfirm({
      title: t('te.note.img.deleteTitle'), message: t('te.note.img.deleteBody', name), confirmText: t('te.delete'), cancelText: t('te.cancel'),
    })
    if (!yes || !alive.current) return
    setDeletingId(img.id)
    setImgProblem(null)
    try {
      const res = await api.techErrors.deleteChannelNoteImage(channelId, img.id)
      if (!alive.current) return
      if (res?.success) {
        const bytes = imageBytesRef.current == null ? null : Math.max(0, imageBytesRef.current - (Number(img.size_bytes) || 0))
        applyImages(imagesRef.current.filter((i) => String(i.id) !== String(img.id)), bytes)
        toast.success(t('te.note.img.deleted', name))
      } else if (res != null) {
        const wait = res.code === 'RATE_LIMITED' ? (retryAfterSeconds(res) ?? 60) : 60
        const key = noteImageRefusalKey(res, wait)
        setImgProblem(isOldServerAnswer(res) ? t('te.note.unavailableBody') : res.error || (key ? t(key, wait) : t('te.note.img.deleteProblem')))
        // Kullanımda (409) ya da artık yok (404): liste sunucudakiyle tazelenir
        if (res.status === 409 || res.status === 404) await refreshImages()
      }
    } catch (e) {
      if (alive.current) setImgProblem(e?.message || t('te.note.img.deleteProblem'))
    } finally {
      if (alive.current) setDeletingId(null)
    }
  }

  const save = useLatestCallback(async () => {
    if (!canEdit || saving || !dirty || tooLong || conflict) return
    const sent = normalizeNoteBody(draftRef.current)
    const version = baseRef.current.version
    // Kayıt sürerken yüklenen görsel yanıtın (kayıt anında okunmuş) listesinde yoktur — yanıt onu silmesin diye
    const imagesBefore = new Set(imagesRef.current.map((i) => String(i.id)))
    setSaving(true)
    setSaveProblem(null)
    setBodyProblem(null)
    setUnavailable(null)
    clearTimeout(backupTimer.current)
    try {
      const res = await api.techErrors.saveChannelNote(channelId, { version, body: sent })
      if (res?.success && res.data) {
        const saved = res.data
        const savedBody = normalizeNoteBody(saved.body ?? '')
        // Başarı üst bileşene HER ZAMAN bildirilir (düzenleyici bu arada kapandıysa da not sunucuda değişti)
        onSaved?.(saved)
        if (!alive.current) return
        setBase({ body: savedBody, version: saved.version ?? version + 1 })
        if (Array.isArray(saved.images)) {
          const inResponse = new Set(saved.images.map((i) => String(i.id)))
          const addedMeanwhile = imagesRef.current.filter((i) => !imagesBefore.has(String(i.id)) && !inResponse.has(String(i.id)))
          if (addedMeanwhile.length) {
            const extra = addedMeanwhile.reduce((n, i) => n + (Number(i.size_bytes) || 0), 0)
            const bytes = typeof saved.image_bytes === 'number' ? saved.image_bytes + extra : imageBytesRef.current
            // Üst bileşen de (onSaved yanıtın listesini yazdı) birleşik listeyi alır
            applyImages([...addedMeanwhile, ...saved.images], bytes)
          } else {
            imagesRef.current = saved.images
            setImages(saved.images)
            if (typeof saved.image_bytes === 'number') { imageBytesRef.current = saved.image_bytes; setImageBytes(saved.image_bytes) }
          }
        } else if (typeof saved.image_bytes === 'number') { imageBytesRef.current = saved.image_bytes; setImageBytes(saved.image_bytes) }
        // Kayıt sürerken yazılan metin kaybolmaz: yanıt yalnız GÖNDERİLEN metne eşit taslağın yerine geçer
        setDraft((cur) => (normalizeNoteBody(cur) === sent ? savedBody : cur))
        dropNoteBackup(channelId)
        wroteBackup.current = false
        setBackup(null)
        toast.success(saved.changed === false ? t('te.note.savedSame') : t('te.note.saved'))
      } else if (!alive.current) {
        // kapandı: taslak ayrılırken yedeklendi
      } else if (isNoteConflict(res)) {
        setConflict(conflictInfoOf(res))
        // Taslak korunur (en son hâli yüklemeden önce de sonra da) — eski yedek bandı açıksa da
        keepDraft(version)
      } else if (res?.code === 'VALIDATION_FAILED' && (res.field === 'body' || res.fields?.body)) {
        setBodyProblem(res.error || t('te.note.tooLong', formatNumber(limits.max_chars, uiLang)))
      } else if (isOldServerAnswer(res) || isChannelGone(res)) {
        setUnavailable(isChannelGone(res) ? 'gone' : 'old')
        keepDraft(version)
      } else if (res != null) {
        setSaveProblem({ text: res.error || t('te.saveError'), details: res.errorInfo })
      }
    } catch (e) {
      if (alive.current) setSaveProblem({ text: e?.message || t('te.saveError'), details: e })
    } finally {
      if (alive.current) { setSaving(false); setBackupTick((n) => n + 1) }
    }
  })

  function onKeyDown(e) {
    if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && (e.key === 's' || e.key === 'S')) {
      e.preventDefault()
      save()
    }
  }

  function discard() {
    clearTimeout(backupTimer.current)
    if (!backup) dropNoteBackup(channelId)
    wroteBackup.current = false
    setDraft(base.body)
    setConflict(null)
    setSaveProblem(null)
    setBodyProblem(null)
    setUnavailable(null)
  }

  async function close() {
    if (dirty) {
      const yes = await showConfirm({
        title: t('te.leaveTitle'), message: t('te.note.leaveBody'), confirmText: t('te.leaveConfirm'), cancelText: t('te.stay'),
      })
      if (!yes) return
    }
    onClose?.()
  }

  /** Sürümü düzenleyiciye yükler: ŞİMDİKİ sürümün üzerine kaydedilmemiş taslak (kirliyse önce sorar). */
  async function loadVersion({ version, body }) {
    if (dirty) {
      const yes = await showConfirm({
        title: t('te.ver.loadDirtyTitle'), message: t('te.ver.loadDirtyBody', version), confirmText: t('te.ver.loadConfirm'), cancelText: t('te.cancel'),
      })
      if (!yes) return false
    }
    setDraft(typeof body === 'string' ? body : '')
    caretRef.current = null
    setMode('write')
    toast.success(t('te.ver.loaded', version))
    return true
  }

  if (!canEdit) return null

  const statusText = saving ? t('te.saving') : conflict ? t('te.note.statusConflict') : dirty ? t('te.unsaved') : t('te.allSaved')
  return (
    <div className="flex min-w-0 flex-col gap-4" data-slot="te-note-editor" data-dirty={dirty ? 'true' : 'false'} onKeyDown={onKeyDown}>
      {backup && (
        <div data-slot="te-note-backup">
          <AlertBanner tone="info" icon={History} title={t('te.backupTitle')}
            actions={(
              <div className="flex flex-wrap gap-2">
                <Button type="button" size="sm" className="pointer-coarse:min-h-10" data-slot="te-note-backup-restore"
                  onClick={() => { setDraft(backup.body); caretRef.current = null; setBackup(null); wroteBackup.current = true }}>
                  {t('te.backupRestore')}
                </Button>
                <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-note-backup-discard"
                  onClick={() => { dropNoteBackup(channelId); setBackup(null) }}>
                  {t('te.backupDiscard')}
                </Button>
              </div>
            )}>
            {backup.version != null && backup.version !== base.version ? t('te.note.backupBodyStale') : t('te.note.backupBody')}
          </AlertBanner>
        </div>
      )}
      {conflict && (
        <div data-slot="te-note-conflict">
          <AlertBanner tone="warning" title={t('te.note.conflictTitle')} role="alert"
            actions={(
              <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-note-load-latest"
                onClick={() => {
                  // Düzenleme kaybolmasın: en son hâli yüklemeden önce yedeğe yazılır (eski yedeğin yerine); yükleme sonrası
                  // "geri yükle" bu taslağı önerir
                  keepDraft(baseRef.current.version)
                  onReloadLatest?.()
                }}>
                {t('te.loadLatest')}
              </Button>
            )}>
            {conflict.by && conflict.at ? t('te.note.conflictBodyWho', conflict.by, fmtDateTime(conflict.at, uiLang))
              : conflict.by ? t('te.note.conflictBodyBy', conflict.by) : t('te.note.conflictBody')}
          </AlertBanner>
        </div>
      )}
      {unavailable && (
        <div data-slot="te-note-unavailable" data-kind={unavailable}>
          <AlertBanner tone="warning" title={t('te.note.unavailableTitle')} role="alert">
            {unavailable === 'gone' ? t('te.note.saveGoneBody') : t('te.note.saveUnavailableBody')}
          </AlertBanner>
        </div>
      )}
      {saveProblem && (
        <div data-slot="te-note-save-problem">
          <AlertBanner tone="danger" title={t('te.note.saveProblemTitle')} role="alert" details={saveProblem.details}>{saveProblem.text}</AlertBanner>
        </div>
      )}

      <p className="flex items-start gap-2 rounded-lg border border-dashed bg-muted/30 px-3 py-2 text-sm text-muted-foreground" data-slot="te-note-secrets-hint">
        <ShieldAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        <span className="min-w-0 [overflow-wrap:anywhere]">{t('te.note.secretsHint')}</span>
      </p>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <SegmentedControl value={mode} onChange={setMode} ariaLabel={t('te.note.modeLabel')}
          options={[{ value: 'write', label: t('te.note.modeWrite'), icon: PencilLine }, { value: 'preview', label: t('te.note.modePreview'), icon: Eye }]} />
        <Button ref={versionsBtnRef} type="button" variant="outline" size="sm" className="pointer-coarse:min-h-10" data-slot="te-note-versions-open"
          onClick={() => setVersionsOpen(true)}>
          <History aria-hidden="true" /> {t('te.ver.open')}
        </Button>
      </div>

      {/* Yazma alanı gizlenirken BAĞLI kalır (geri al geçmişi, imleç). Dosya sürüklenince çerçeve vurgulanır */}
      <div ref={wrapRef} hidden={mode !== 'write'} data-slot="te-note-write-pane"
        className={cn('wr-editor min-w-0 rounded-md', drag && 'ring-2 ring-primary/60')} data-color-mode={isDark ? 'dark' : 'light'}
        onPasteCapture={onPasteCapture} onKeyUp={recordCaret} onMouseUp={recordCaret} onSelect={recordCaret}
        onDragOver={(e) => { if (isFileDrag(e)) { e.preventDefault(); setDrag(true) } }}
        onDragLeave={() => setDrag(false)}
        onDrop={(e) => {
          if (!isFileDrag(e)) return
          e.preventDefault()
          setDrag(false)
          const f = e.dataTransfer?.files?.[0]
          if (f) addImage(f)
        }}>
        <LazyMdEditor value={draft} onChange={(v) => setDraft(v ?? '')} preview="edit" height={380} visibleDragbar highlightEnable={false}
          commands={editorCommands} extraCommands={NO_EXTRA_COMMANDS} textareaProps={textareaProps} />
      </div>
      {mode === 'preview' && (
        <div className="min-h-40 min-w-0 rounded-lg border p-4" data-slot="te-note-preview">
          {draft.trim() === ''
            ? <p className="text-sm text-muted-foreground">{t('te.note.previewEmpty')}</p>
            : <LazyNoteView body={draft} images={images} />}
        </div>
      )}

      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.note.pasteHint', keys.paste)}</p>
        <p className={cn('text-xs tabular-nums', tooLong ? 'font-semibold text-destructive' : 'text-muted-foreground')} data-slot="te-note-chars"
          data-over={tooLong ? 'true' : 'false'}>
          {t('te.note.chars', formatNumber(chars, uiLang), formatNumber(limits.max_chars, uiLang))}
        </p>
      </div>
      {tooLong && (
        <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert" data-slot="te-note-too-long">
          {t('te.note.tooLong', formatNumber(limits.max_chars, uiLang))}
        </p>
      )}
      {bodyProblem && <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert" data-slot="te-note-body-problem">{bodyProblem}</p>}

      {/* Görseller: rapor görsel yuvalarıyla AYNI üç eylem, aynı sıra ve görünüm (SlotCapture) */}
      <section className={cn('flex min-w-0 flex-col gap-3 rounded-xl border p-3', cap.zoneClassName)} {...cap.zoneProps}
        data-slot="te-note-image-zone" aria-busy={imgBusy ? 'true' : undefined}>
        <div className="flex flex-col gap-1">
          <h3 className="text-sm font-semibold">{t('te.note.img.title')}</h3>
          <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.note.img.hint', formatBytes(limits.max_image_bytes, uiLang))}</p>
        </div>
        <CaptureSessionChip />
        <CaptureStopNotice />
        <div className={SLOT_ACTIONS_WRAP} data-slot="te-image-actions" data-count={mainCount}>
          <Input ref={inputRef} type="file" accept={ACCEPT} hidden tabIndex={-1} aria-hidden="true"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) addImage(f) }} />
          <div className={cn('grid gap-2', MAIN_ACTION_GRID[mainCount])} data-slot="te-image-main-actions">
            <Button type="button" size="sm" variant="default" disabled={imgBusy} onClick={() => inputRef.current?.click()}
              className={SLOT_ACTION_CLASS} data-slot="te-image-upload">
              {imgBusy ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <Upload aria-hidden="true" />}
              {imgBusy ? (imgPhase === 'prep' ? t('te.img.preparing') : t('te.img.uploading')) : t('te.img.upload')}
              <span className="sr-only">{`: ${t('te.note.img.zoneName')}`}</span>
            </Button>
            {cap.snipButton}
            {cap.pasteButton}
          </div>
        </div>
        {imgProblem && <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert" data-slot="te-note-img-problem">{imgProblem}</p>}
        {kept && !imgBusy && (
          <div className="flex min-w-0 flex-col gap-2" data-slot="te-image-kept">
            <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.img.keptHint')}</p>
            <div className="flex flex-wrap items-center gap-2">
              <Button type="button" size="sm" variant="outline" onClick={retryKept} className="pointer-coarse:min-h-10" data-slot="te-image-retry">
                <RefreshCw aria-hidden="true" /> {t('te.img.retryKept')}
              </Button>
              <Button type="button" size="sm" variant="ghost" className="pointer-coarse:min-h-10" data-slot="te-image-kept-drop"
                onClick={() => { setKept(null); setImgProblem(null) }}>
                {t('te.img.dropKept')}
              </Button>
            </div>
          </div>
        )}
        {imgNote && <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" role="status" data-slot="te-note-img-note">{imgNote}</p>}
        {cap.notes}
        <NoteImageList images={images} usedIds={usedIds} imageBytes={imageBytes} limits={limits} uiLang={uiLang}
          busyId={deletingId} disabled={saving} onInsert={insertImage} onDelete={removeImage} />
      </section>
      {cap.dialog}

      <NoteVersionsDialog channelId={channelId} open={versionsOpen} onClose={() => setVersionsOpen(false)} canLoad
        currentVersion={base.version} images={images} onLoad={loadVersion} returnFocusRef={versionsBtnRef} />

      <div ref={stickyBarRef} className="sticky bottom-0 z-10 -mx-1 flex flex-wrap items-center justify-between gap-2 rounded-t-xl border-t bg-background/95 px-1 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] backdrop-blur"
        data-slot="te-note-actions">
        <p className="text-sm text-muted-foreground" aria-live="polite" data-slot="te-note-status">{statusText}</p>
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="ghost" disabled={saving} className="pointer-coarse:min-h-10" data-slot="te-note-close" onClick={close}>
            <X aria-hidden="true" /> {t('te.note.closeEditor')}
          </Button>
          <Button type="button" variant="outline" disabled={!dirty || saving} className="pointer-coarse:min-h-10" data-slot="te-note-discard" onClick={discard}>
            <Undo2 aria-hidden="true" /> {t('te.discard')}
          </Button>
          <Button type="button" disabled={!dirty || saving || tooLong || !!conflict} className="pointer-coarse:min-h-10" data-slot="te-note-save"
            onClick={() => save()} title={t('te.note.saveShortcut', keys.paste.startsWith('⌘') ? '⌘S' : 'Ctrl+S')}>
            <Save aria-hidden="true" /> {saving ? t('te.saving') : t('te.save')}
          </Button>
        </div>
      </div>
    </div>
  )
}
