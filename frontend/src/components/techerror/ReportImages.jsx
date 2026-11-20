import { memo, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { History, ImagePlus, RefreshCw, Trash2, Upload, ZoomIn } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Spinner } from '@/components/shadcn/spinner'
import { CollapsibleCard } from './SectionCollapse.jsx'
import { cn } from '@/lib/utils'
import { LIMITS, formatBytes, imageRows } from './techErrorModel.js'
import { ImagePrepError, displayWidthPct, prepareImage } from './imagePrep.js'
import { CopyTextButton, periodLabel } from './TechErrorParts.jsx'
import { ImageLightbox } from './ImageLightbox.jsx'
import { ImageFillContext, PrevImagesContext, SeqFillBar, usePrevSlotImage } from './ImageFill.jsx'
import BulkDropDialog from './BulkDropDialog.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { isFileDrag } from './capture/useFileDropGuard.js'
import {
  SLOT_ACTIONS_PAIRED, SLOT_ACTIONS_WRAP, SLOT_ACTION_CLASS, slotActionGrid, useSlotCapture,
} from './capture/SlotCapture.jsx'
import {
  CaptureHint, CaptureSessionChip, CaptureStopNotice, ImagesPanelHeaderButton, useCaptureScope,
} from './capture/CaptureParts.jsx'
import { postImagesChanged } from './capture/imageSync.js'
import { UNDO_MS, registerUndo, snapshotImage } from './imageUndo.js'

/** Tarayıcının açıp PNG / JPEG'e çevirebildiği türler (sunucu yalnız PNG / JPEG alır — imagePrep çevirir). */
const ACCEPT = 'image/png,image/jpeg,image/webp,image/gif,image/bmp,image/avif'
const BYTE_PARAMS = new Set(['te.img.errTooBig', 'te.img.errStillLarge'])
/**
 * Konu görselinden önceki kayıt neden olmadı (inceleme 2026-10-18 — "işaretli alanları düzeltin" her nedende yanlıştı):
 * `beforeUpload` `{ ok, reason }` döner; bilinmeyen / eski boolean cevap `invalid` gibi okunur.
 */
export const UPLOAD_BLOCK_KEYS = Object.freeze({
  invalid: 'te.iss.uploadNeedsSave',
  held: 'te.iss.uploadHeld',
  conflict: 'te.iss.uploadConflict',
  network: 'te.iss.uploadNetwork',
  server: 'te.iss.uploadServer',
})

/** Kilit çakışması (503 LOCK_CONFLICT) sonrası tek yeniden denemenin en uzun beklemesi (sunucu 2 sn önerir). */
export const LOCK_RETRY_MAX_MS = 3000

/** Yanıt bir kilit çakışması mı (aynı raporda aynı anda iki işlem — ör. Görsel paneli + ana pencere aynı anda yükledi)? */
export function isLockConflict(res) {
  return !!res && res.success === false && res.code === 'LOCK_CONFLICT'
}

/**
 * Kilit çakışmasından sonra beklenecek süre (ms): sunucunun önerisi (`Retry-After` / `retry_after_seconds`), en çok
 * {@link LOCK_RETRY_MAX_MS}; öneri yoksa / bozuksa 2 sn.
 */
export function lockRetryDelayMs(res) {
  const raw = res?.retry_after_seconds ?? res?.retryAfter
  const s = Number(raw)
  if (raw == null || raw === '' || !Number.isFinite(s) || s < 0) return 2000
  return Math.min(LOCK_RETRY_MAX_MS, Math.round(s * 1000))
}

/**
 * Grafik yuvaları — iki kolonlu ızgara (PDF'in aynısı): yarım yuvalar yan yana, tam yuva iki kolon genişliğinde. Her
 * yuvaya bir görsel (PNG / JPEG, ≤ 5 MB); yeni yükleme eskisinin yerini alır. Yükleme sunucuda baytlardan doğrulanır;
 * hata yuvanın altında yazar. Görsel yüklemek rapor sürümünü artırmaz (açık değer düzenlemesi çakışmaya düşmez).
 */
// memo: yazarken (her tuşta düzenleyici yeniden çizilir) değişmeyen görsel bölümleri — 22 yuva — yeniden çizilmez;
// düzenleyici geri çağrıyı sabit verir (useCallback)
// Ekrandan görsel alma (2026-10-18): yapıştırma / "Ekrandan al" / Görsel paneli `capture/*`; her yükleme ve silme öteki
// pencerelere duyurulur (imageSync). `inPanel`: Görsel paneli penceresinde (panel düğmesi ve bölüm başı paylaşım çipi yok).
/**
 * Sunucunun yüklenen dosyaya verdiği ad — {@code TechErrorReportService.fileName} ile aynı kural: yol kısmı atılır, denetim
 * karakterleri boşluk olur, çoklu boşluk tek, kırpılır, boşsa null, en çok SON 200 karakter.
 */
export function storedFileName(raw) {
  if (raw == null) return null
  let n = String(raw).replace(/\\/g, '/')
  const slash = n.lastIndexOf('/')
  if (slash >= 0) n = n.slice(slash + 1)
  let out = ''
  for (const ch of n.slice(0, 4096)) {
    const c = ch.codePointAt(0)
    out += (c < 0x20 || (c >= 0x7f && c <= 0x9f)) ? ' ' : ch
  }
  out = out.replace(/ {2,}/g, ' ').trim()
  if (!out) return null
  return out.length > 200 ? out.slice(out.length - 200) : out
}

/**
 * Yuvadaki görsel bu pencerenin bekletilen dosyası mı (inceleme 2026-10-19): ağ hatasında sunucu yüklemeyi ASLINDA kaydetmiş
 * olabilir — "Yeniden dene" o görseli "öteki pencere yükledi" diye sormamalı. Bilinen kimlik (ilk yüklemenin döndürdüğü)
 * önce; yoksa ad + bayt sayısı (sunucu adı aynı kuralla kurar, boyut hazırlanmış dosyanınki). Biri eksikse "bilinmiyor" =
 * değil (onay sorulur — öteki pencerenin görselini sormadan ezmektense).
 */
export function isOwnUpload(current, file, ownId = null) {
  if (!current) return false
  if (ownId != null && current.id != null) return current.id === ownId
  if (!file || current.size_bytes == null || current.file_name == null) return false
  const name = storedFileName(file.name)
  return name != null && current.file_name === name && Number(current.size_bytes) === file.size
}

export const ImagesBlock = memo(function ImagesBlock({ section, reportId, images, resolve, uiLang, readOnly, onImagesChange, showTotals = true, inPanel = false }) {
  const t = useT()
  useCaptureScope()
  const notifyChange = useCallback((fn) => { onImagesChange(fn); postImagesChanged(reportId) }, [onImagesChange, reportId])
  const bySlot = new Map(images.map((i) => [i.slot_id, i]))
  const total = images.reduce((a, i) => a + (i.size_bytes || 0), 0)
  // Rapor toplamı yükleme ANINDA okunur: hazırlama sürerken başka yuvaya yüklenen görsel de sayılır (inceleme 2026-10-15)
  const imagesRef = useRef(images)
  imagesRef.current = images
  const otherBytesOf = (slotId) => (imagesRef.current || [])
    .reduce((a, i) => a + (i.slot_id === slotId ? 0 : (i.size_bytes || 0)), 0)
  const rows = imageRows(section.slots || [])
  // Toplu bırakma (2026-11-20, C4): bölüme (yuvanın DIŞINA) bırakılan dosyalar eşleme penceresini açar — yalnız sağlayıcı varken
  // (rapor düzenleyicisi, Görsel paneli) ve düzenlenebilir raporda. Yuvaya bırakılan dosya yuvanın kendi işidir (kabarcıklanmaz)
  const fill = useContext(ImageFillContext)
  const [bulk, setBulk] = useState(null)
  const canBulk = !!fill && !readOnly
  // "Sıralı doldur" çubuğu yalnız çizilecekse verilir (boş öğe başlığın altına boş bir satır açmasın): ilk görsel bölümü,
  // sağlayıcı var, düzenlenebilir, masaüstü (Görsel panelinde her genişlikte), raporda yuva var
  const isMobile = useIsMobile()
  const seqVisible = showTotals && canBulk && (inPanel || !isMobile) && (fill?.order?.() || []).length > 0
  const cardProps = canBulk ? {
    onDragOver: (e) => { if (isFileDrag(e)) e.preventDefault() },
    onDrop: (e) => {
      // Kapı (portal) içeriği — açık büyük görünüm, eşleme penceresi — DOM'da bölümün içinde değildir: oraya bırakılan dosya
      // React ağacında buraya kabarcıklansa da pencere açılmaz (pencere koruması sayfayı dosyaya götürmez)
      if (bulk || !e.currentTarget?.contains?.(e.target)) return
      const files = Array.from(e.dataTransfer?.files || [])
      if (!files.length) return
      e.preventDefault()
      setBulk({ files })
    },
  } : undefined
  return (
    // Katlanır kart (2026-10-16): ipucu ve rapor toplamı yalnız açıkken
    <CollapsibleCard sectionId={section.id} slot="te-images" title={resolve(section.title)} contentClassName="flex flex-col gap-3"
      headerEnd={showTotals && !inPanel ? <ImagesPanelHeaderButton reportId={reportId} readOnly={readOnly} /> : null}
      // "Sıralı doldur" (C2): bölüm kapalıyken de görünür — sağlayıcı yoksa / salt okunurda / telefonda hiç çizilmez
      meta={seqVisible ? <SeqFillBar inPanel={inPanel} /> : null}
      cardProps={cardProps}
      hint={showTotals ? (
        <>
          <p className="text-sm text-muted-foreground">{t('te.img.hint')}</p>
          <p className="text-xs text-muted-foreground" data-slot="te-img-total">
            {t('te.img.total', formatBytes(total, uiLang), formatBytes(LIMITS.reportImageBytes, uiLang))}
          </p>
          <CaptureHint readOnly={readOnly} inPanel={inPanel} />
          {canBulk && <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-bulk-hint">{t('te.bulk.hint')}</p>}
        </>
      ) : null}>
        {!inPanel && <CaptureSessionChip />}
        {showTotals && !inPanel && <CaptureStopNotice />}
        {rows.map((row) => (
          <div key={row.map((r) => r.id).join('|')} className="grid gap-3 md:grid-cols-2">
            {row.map((slot) => (
              <SlotTile key={slot.id} slot={slot} image={bySlot.get(slot.id)} reportId={reportId} resolve={resolve}
                uiLang={uiLang} readOnly={readOnly} onImagesChange={notifyChange} inPanel={inPanel}
                otherBytes={() => otherBytesOf(slot.id)}
                pairedRow={slot.width === 'full'} fillable withPrev
                className={slot.width === 'full' ? 'md:col-span-2' : row.length === 1 ? 'md:col-span-1' : ''} />
            ))}
          </div>
        ))}
        {bulk && (
          <BulkDropDialog files={bulk.files} startSectionId={section.id} resolve={resolve} onClose={() => setBulk(null)} />
        )}
    </CollapsibleCard>
  )
})

/**
 * Tek görsel yuvası. Anomali konusu görselleri de bunu kullanır (2026-10-18, `ImageSlotTile`): `beforeUpload` yüklemeden
 * önce çağrılır (konu henüz kaydedilmediyse düzenleyici raporu kaydeder) ve `{ ok, reason }` döner; olmazsa yükleme yapılmaz,
 * NEDENE göre metin yuvada (`UPLOAD_BLOCK_KEYS`). `hideWidth`: genişlik etiketi gösterilmez (konu görseli her zaman tam
 * genişlik). `contextLabel`: görselin erişilebilir adına eklenen bağlam (konu başlığı — "Görsel 1" iki konuda karışmasın).
 * `onRemoved`: görsel silindikten sonra (konu görselinin açıklaması taslaktan düşer); bir işlev dönerse "Geri al" başarılı
 * olunca çağrılır (açıklama geri gelir — inceleme 2026-11-20).
 */
export function ImageSlotTile(props) {
  return <SlotTile {...props} />
}

/*
 * `fillable` (2026-11-20, C2 / C4): raporun görsel bölümündeki yuva (konu görseli değil) — sağlayıcıya kaydolur; sıralı
 * doldurma ve toplu bırakma ona yuvanın NORMAL yükleme yolundan yükler. `withPrev` (C3): "Geçen ay" görünümü.
 */
function SlotTile({ slot, image, reportId, resolve, uiLang, readOnly, onImagesChange, className, otherBytes = () => 0, beforeUpload = null,
  hideWidth = false, contextLabel = null, onRemoved = null, inPanel = false, pairedRow = false, fillable = false, withPrev = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const inputRef = useRef(null)
  const [busy, setBusy] = useState(false)
  const [phase, setPhase] = useState(null)        // 'prep' (tarayıcıda hazırlanıyor) | 'upload'
  // Hata metni bir başvuruda da tutulur: toplu bırakma / sıralı yapıştırma dosya başına sonucu buradan okur
  const [error, setErrorState] = useState(null)
  const errorRef = useRef(null)
  const setError = useCallback((v) => { errorRef.current = v; setErrorState(v) }, [])
  const [note, setNote] = useState(null)          // son yüklemenin küçültme / çevirme özeti
  const [zoomed, setZoomed] = useState(false)     // büyük görünüm (2026-10-16)
  const [zoomView, setZoomView] = useState('current')   // büyük görünümde bu ay / geçen ay (C3)
  const zoomRef = useRef(null)                    // kapanınca odak görsele döner
  const prevThumbRef = useRef(null)               // geçen ayın küçük görselinden açıldıysa odak oraya
  const [zoomFrom, setZoomFrom] = useState('current')
  const [drag, setDrag] = useState(false)
  // Sağlayıcı (rapor düzenleyicisi / Görsel paneli) — konu görseli kaydolmaz
  const fillCtx = useContext(ImageFillContext)
  const fillApi = fillable ? fillCtx : null
  const prevCtx = useContext(PrevImagesContext)
  const prevSrc = withPrev && prevCtx?.load ? prevCtx : null
  const pv = usePrevSlotImage(prevSrc, slot.id)
  const prevImage = pv.on && pv.state.status === 'ok' ? pv.state.image : null
  // Kaynak tam önceki ay değilse (`neighbors.prev`, 2026-11-20 inceleme) metinler "geçen ay" demez — raporun ayını söyler
  const prevExact = prevSrc?.exact !== false
  const prevPeriod = prevSrc ? (periodLabel({ year: prevSrc.year, month: prevSrc.month }, t) || t('te.prev.unknownPeriod')) : ''
  const label = resolve(slot.label)
  // Ekran okuyucu / alternatif metin adı: bağlam varsa "Konu — Görsel 1" (görünen etiket yine kısa)
  const fullLabel = contextLabel ? `${contextLabel} — ${label}` : label
  /** Küçültme / çevirme / döndürme özeti (yuvanın altında ve başarı bildiriminde) */
  const noteText = (n) => [
    n.resized && t('te.img.resized', `${n.fromW} × ${n.fromH}`, `${n.toW} × ${n.toH}`, formatBytes(n.fromBytes, uiLang), formatBytes(n.toBytes, uiLang)),
    n.converted && t('te.img.converted', n.fromKind, n.toKind),
    n.rotated && t('te.img.rotated'),
  ].filter(Boolean).join(' ')

  // Kilit çakışmasında (503 LOCK_CONFLICT — Görsel paneli ile ana pencere aynı anda yükledi) ya da ağ hatasında HAZIRLANMIŞ
  // görsel bellekte bekletilir (son inceleme 2026-10-18: kırpılmış ekran görüntüsü yeniden alınmak zorundaydı); başarıda, yeni
  // yüklemede ya da "Vazgeç"te bırakılır. `{ prepared, startId }`: startId = yükleme BAŞLADIĞINDA yuvadaki görselin kimliği
  // (yeniden deneme öteki pencerenin o arada koyduğu görseli sormadan ezmesin — inceleme 2026-10-19)
  const [kept, setKept] = useState(null)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])
  // Yuvadaki görsel (eşitlenmiş liste) — yeniden okuma yapılamazsa yeniden denemenin karşılaştırması buna düşer
  const imageRef = useRef(image)
  imageRef.current = image
  const busyRef = useRef(busy)
  busyRef.current = busy
  const keptRef = useRef(kept)
  keptRef.current = kept

  // Geri alma (2026-11-20, A3): yuva başına EN ÇOK bir bildirim — yeni silme / değiştirme öncekini kapatır; bildirim
  // kapanınca (süre, X, yuva sökülmesi, oturum sıfırlama) tutulan baytlar bırakılır
  const undoRef = useRef(null)          // { id, file, expectId }
  const toastRef = useRef(toast)
  toastRef.current = toast
  const dropUndo = useCallback(() => {
    const u = undoRef.current
    if (!u) return
    undoRef.current = null
    u.file = null
    u.unregister?.()
    const tt = toastRef.current
    if (typeof tt?.dismiss === 'function') tt.dismiss(u.id)
  }, [])
  // Yuva sökülünce (rapordan çıkış, konu kartının yeniden kurulması) bildirim kapanır, baytlar bırakılır
  useEffect(() => () => dropUndo(), [dropUndo])
  // Yuva salt okunur olunca (onay kilidi yeniden uygulandı — 2026-11-20, B3) açık "Geri al" kapanır: kilitli rapora görsel
  // geri yüklenmesin
  const readOnlyRef = useRef(readOnly)
  readOnlyRef.current = readOnly
  useEffect(() => { if (readOnly) dropUndo() }, [readOnly, dropUndo])

  // Geri alma için görsel okumaları (inceleme 2026-11-20): yuva sökülünce kesilir; okuma hiç atmaz (kesilince null)
  const snapAborts = useRef(new Set())
  const startSnapshot = useCallback((target) => {
    const ctl = new AbortController()
    snapAborts.current.add(ctl)
    const promise = snapshotImage(target, api.techErrors.imageUrl(target.id), { signal: ctl.signal })
      .finally(() => { snapAborts.current.delete(ctl) })
    return { promise, abort: () => ctl.abort() }
  }, [])
  useEffect(() => () => {
    for (const ctl of [...snapAborts.current]) ctl.abort()
    snapAborts.current.clear()
  }, [])

  /**
   * Silme / değiştirme başarılı: önceki baytlar okunabildiyse "Geri al" sunulur. `expectId` = işlemden sonra yuvadaki görsel;
   * `restore` = geri yükleme başarılı olunca çağrılır (konu görselinin açıklaması taslağa geri döner — inceleme 2026-11-20);
   * `waiting`: yuvada yükleme sürerken basılan "Geri al"ın yeniden sunuluşu (metin bunu söyler).
   */
  function offerUndo(kind, file, expectId, restore = null, waiting = false) {
    if (!file || readOnly || !alive.current || typeof toast.action !== 'function') return
    dropUndo()
    const entry = { id: null, file, expectId: expectId ?? null, kind, restore, unregister: null }
    const msg = waiting ? t('te.undo.busy', fullLabel)
      : t(kind === 'remove' ? 'te.undo.imageRemoved' : 'te.undo.imageReplaced', fullLabel)
    const id = toast.action(msg, {
      label: t('te.undo.action'), duration: UNDO_MS,
      onAction: () => { undoImage(entry) },
      onClose: () => {
        entry.file = null                 // baytlar bırakılır
        entry.unregister?.()
        if (undoRef.current === entry) undoRef.current = null
      },
    })
    entry.id = id
    entry.unregister = registerUndo(() => { if (typeof toast.dismiss === 'function') toast.dismiss(id) })
    undoRef.current = entry
  }

  /** "Geri al": eski baytlar NORMAL yükleme yolundan geri yüklenir (hazırlama, doğrulama, kilit çakışması, duyuru). */
  async function undoImage(entry) {
    const file = entry?.file
    if (!file || !alive.current || readOnlyRef.current) return
    // Yuvada başka bir yükleme sürüyor (inceleme 2026-11-20): bildirim kapanır ama geri alma KAYBOLMAZ — aynı baytlarla
    // yeniden sunulur ("bitince geri alabilirsiniz" sözü doğru kalsın)
    if (busyRef.current) { offerUndo(entry.kind, file, entry.expectId, entry.restore, true); return }
    // O arada yuvaya başka bir görsel geldiyse (öteki pencere / yeni yükleme) sormadan ezilmez — kendi metniyle sorulur
    const cur = imageRef.current?.id ?? null
    if (cur !== (entry.expectId ?? null)) {
      const yes = await showConfirm({ title: t('te.undo.replaceNewerTitle'), message: t('te.undo.replaceNewerBody', fullLabel),
        confirmText: t('te.undo.action'), cancelText: t('te.cancel') })
      if (!yes || !alive.current) return
    }
    const uploaded = await upload(file, { undo: false, advance: false })
    if (uploaded && typeof entry.restore === 'function') {
      try { entry.restore() } catch { /* açıklama geri dönmese de görsel geri geldi */ }
    }
  }

  /**
   * Yükleme. `advance` (C2): başarılı yüklemeden sonra "Sıralı doldur" açıksa odak sıradaki boş yuvaya geçer (geri alma ve
   * toplu bırakma geçmez). `quiet` (C4): başarı bildirimi gösterilmez (toplu bırakma sonucu kendi penceresinde yazar).
   */
  async function upload(file, { undo = true, advance = true, quiet = false } = {}) {
    const startId = imageRef.current?.id ?? null
    // Değiştirmenin geri alınabilmesi için yuvadaki görselin baytları hazırlamayla AYNI ANDA okunur (süre sınırlı; okunamazsa
    // geri alma sunulmaz, yükleme beklemez)
    const before = undo ? imageRef.current : null
    const snap = before ? startSnapshot(before) : null
    setError(null)
    setNote(null)
    setKept(null)
    setBusy(true)
    busyRef.current = true            // aynı çizimdeki ikinci istek (sıralı yapıştırma, toplu bırakma) yuvayı meşgul görsün
    setPhase('prep')
    let uploaded = null
    try {

      // Tarayıcıda hazırla (2026-10-16: çözünürlük korunur): yalnız sunucu sınırını aşan görsel alan ortalamasıyla
      // küçülür, gerekirse PNG'ye çevrilir; neyin yanlış olduğunu ve ne yapılacağını söyleyen hata
      let prepared
      try {
        prepared = await prepareImage(file)
      } catch (e) {
        if (e instanceof ImagePrepError) {
          const params = BYTE_PARAMS.has(e.key) ? e.params.map((n) => formatBytes(n, uiLang)) : e.params
          setError(t(e.key, ...params))
        } else {
          // Açma hatası errDecode olarak gelir; beklenmeyen hata çizim / bellek tarafıdır — "dosya bozuk" denmez
          setError(t('te.img.errBrowser'))
        }
        return
      }
      uploaded = (await send(prepared, startId, false, { advance, quiet })) || null
      const newId = uploaded?.id ?? null
      if (uploaded && snap) snap.promise.then((f) => offerUndo('replace', f, newId))
      return uploaded
    } finally {
      // Yükleme olmadıysa (ret, ağ hatası, hazırlanamadı) geri alma okuması boşuna sürmez (inceleme 2026-11-20)
      if (snap && !uploaded) snap.abort()
      setBusy(false)
      setPhase(null)
      if (inputRef.current) inputRef.current.value = ''
    }
  }

  /**
   * Yeniden denemeden ÖNCE yuva yeniden okunur (inceleme 2026-10-19): kilit çakışması tam da öteki pencerenin (Görsel paneli)
   * aynı yuvaya yüklediği anda olur. Yuvada yükleme BAŞLADIĞINDA olmayan bir görsel varsa (`since` = o anki kimlik) normal
   * değiştirmedeki onay sorulur; Vazgeç → öteki pencerenin görseli kalır (eşitleme gösterir), bekletilen dosya bırakılır.
   * Okunamazsa bu pencerenin eşitlenmiş listesi kullanılır. Dönen `{ ok, since }`: `since` onaylanan görselin kimliği.
   */
  async function confirmBeforeRetry(since, prepared = null, quiet = false) {
    let current = imageRef.current ?? null
    try {
      const res = await api.techErrors.report(reportId)
      const list = res?.success ? res.data?.images : null
      if (Array.isArray(list)) current = list.find((i) => i?.slot_id === slot.id) ?? null
    } catch { /* okunamadı: eşitlenmiş liste */ }
    if (!alive.current) return { ok: false }
    if (!current || current.id == null || current.id === since) return { ok: true, since }
    // Yuvadaki görsel BU pencerenin bekletilen dosyası (ağ hatası ama sunucu kaydetmişti — inceleme 2026-10-19): iş bitmiş
    // sayılır — onay yok, bekletilen dosya bırakılır, liste sunucudaki görselle tazelenir. Görsel geri döner (2026-11-20):
    // toplu bırakma / sıralı yapıştırma sonucu "yüklendi" der, "yüklenmedi" değil
    if (prepared && isOwnUpload(current, prepared.file)) {
      const own = current
      onImagesChange((list) => [...list.filter((i) => i.slot_id !== slot.id), own])
      setKept(null)
      setError(null)
      if (!quiet) toast.success(t('te.img.uploaded', label))
      return { ok: false, done: true, image: own }
    }
    const yes = await showConfirm({
      title: t('te.cap.replaceTitle'), message: t('te.img.replaceOtherBody', fullLabel), confirmText: t('te.img.replace'),
      cancelText: t('te.cancel'),
    })
    if (!alive.current) return { ok: false }
    if (!yes) {
      setKept(null)
      setError(null)
      return { ok: false }
    }
    return { ok: true, since: current.id }
  }

  /**
   * Hazırlanmış görseli yükler (yeni dosya ya da bekletilen görselin "Yeniden dene"si). Kilit çakışmasında AYNI dosya,
   * sunucunun önerdiği süre (≤ 3 sn) sonra BİR KEZ yeniden gönderilir; yine olmazsa ileti yuvada, görsel bekletilir. Her
   * yeniden denemeden önce yuva yeniden okunur ({@link confirmBeforeRetry}). `startId`: yükleme başladığında yuvadaki görsel;
   * `retry`: "Yeniden dene" (ilk gönderim de bir yeniden denemedir).
   */
  async function send(prepared, startId, retry = false, { advance = false, quiet = false } = {}) {
    let since = startId
    try {
      // Rapor toplamı (sunucu da denetler; burada yüklemeden ÖNCE söylenir)
      const others = otherBytes()
      if (others + prepared.file.size > LIMITS.reportImageBytes) {
        setError(t('te.img.errTotal', formatBytes(others + prepared.file.size, uiLang), formatBytes(LIMITS.reportImageBytes, uiLang)))
        return
      }
      // Konu görseli (2026-10-18): konu sunucuda kayıtlı değilse önce rapor kaydedilir — görsel hazırlandıktan SONRA (reddedilen
      // dosya için boşuna kayıt yok); kaydedilmemiş konunun görseli sunucuda sahipsiz kalırdı
      if (beforeUpload) {
        let ready
        try { ready = await beforeUpload() } catch { ready = { ok: false, reason: 'network' } }
        const ok = ready === true || ready?.ok === true
        if (!ok) { setError(t(UPLOAD_BLOCK_KEYS[ready?.reason] || UPLOAD_BLOCK_KEYS.invalid)); return }
      }
      setPhase('upload')
      // Yeniden okumada yuvadaki görsel bu pencerenin kendi yüklemesi çıktıysa iş bitmiştir: görsel döner (sıralı doldur ilerler)
      const ownDone = (go) => {
        if (!go.done || !go.image) return undefined
        if (advance) fillApi?.onUploaded?.(slot.id)
        return go.image
      }
      if (retry) {
        const go = await confirmBeforeRetry(since, prepared, quiet)
        if (!go.ok) return ownDone(go)
        since = go.since
      }
      let res = await api.techErrors.uploadImage(reportId, slot.id, prepared.file)
      if (isLockConflict(res)) {
        await new Promise((r) => { setTimeout(r, lockRetryDelayMs(res)) })
        if (!alive.current) return
        const go = await confirmBeforeRetry(since, prepared, quiet)
        if (!go.ok) return ownDone(go)
        since = go.since
        res = await api.techErrors.uploadImage(reportId, slot.id, prepared.file)
      }
      if (res?.success && res.data) {
        // Başarı üst listeye ve öteki pencerelere HER ZAMAN yazılır (inceleme 2026-10-19): yükleme sürerken yuva kalktıysa
        // (Vazgeç / yedeği geri yükleme / tam yeniden yükleme yeni kuşak çizer) görsel sunucuda yine de değişti — yeni yuva
        // ve Görsel paneli onu göstermeli. Yalnız bu yuvanın yerel durumu ve bildirim kurulu yuvada
        const uploaded = res.data
        // Geç gelen başarı (inceleme 2026-10-19): eşitleme bu yuvaya o arada DAHA YENİ bir görsel (daha büyük kimlik — öteki
        // pencerenin sonraki yüklemesi) getirdiyse o kalır; eskisi onun yerine yazılmaz
        onImagesChange((list) => {
          const shown = list.find((i) => i.slot_id === slot.id)
          if (shown && shown.id != null && uploaded.id != null && Number(shown.id) > Number(uploaded.id)) return list
          return [...list.filter((i) => i.slot_id !== slot.id), uploaded]
        })
        if (!alive.current) return
        setKept(null)
        const changed = prepared.info.resized || prepared.info.converted || prepared.info.rotated
        // Not YÜKLENEN görsele bağlı: başka biri görseli değiştirip yeniden okunursa eski not o görselin altında kalmaz
        if (changed) setNote({ imageId: res.data.id, info: prepared.info })
        // Not bildirimde de okunur (yuvanın altındaki not görselle birlikte eklendiği için duyurulmaz); uzun metin → uzun süre
        if (!quiet) {
          if (changed) toast.success(`${t('te.img.uploaded', label)} ${noteText(prepared.info)}`, 8000)
          else toast.success(t('te.img.uploaded', label))
        }
        // Sıralı doldur (C2): açıksa odak sıradaki boş yuvaya (kapalıysa sağlayıcı hiçbir şey yapmaz)
        if (advance) fillApi?.onUploaded?.(slot.id)
        return uploaded
      } else {
        if (!alive.current) return
        // Sunucunun iletisi yuvada. Yalnız kilit çakışmasında görsel bekletilir; başka bir ret (doğrulama, boyut — "Yeniden
        // dene"nin aldığı da) bekleyeni BIRAKIR: aynı dosyayı yeniden denemek aynı reddi alırdı (inceleme 2026-10-19)
        setError(res?.error || t('te.img.uploadError'))
        setKept(isLockConflict(res) ? { prepared, startId: since } : null)
      }
    } catch (e) {
      if (!alive.current) return
      setError(e?.message || t('te.img.uploadError'))
      // Ağ hatası: hazır görsel de bekletilir (kırpılmış ekran görüntüsü yeniden alınmasın)
      setKept({ prepared, startId: since })
    }
  }

  /** Bekletilen görseli (yeniden hazırlamadan, yeniden almadan) yeniden yükler. */
  async function retryKept() {
    const held = kept
    if (!held || busy) return
    setError(null)
    setBusy(true)
    setPhase('upload')
    try {
      await send(held.prepared, held.startId, true)
    } finally {
      if (alive.current) {
        setBusy(false)
        setPhase(null)
      }
    }
  }

  // Dolu yuvaya yapıştırma önce sorar: yeni görsel eskisinin yerini alır, geri alma yok (inceleme 2026-10-18)
  const confirmReplace = () => showConfirm({
    title: t('te.cap.replaceTitle'), message: t('te.cap.replaceBody', fullLabel), confirmText: t('te.img.replace'), cancelText: t('te.cancel'),
  })

  async function remove() {
    const target = image
    const ok = await showConfirm({ title: t('te.img.removeTitle'), message: t('te.img.removeBody', fullLabel), variant: 'danger',
      confirmText: t('te.img.remove'), cancelText: t('te.cancel') })
    if (!ok || !alive.current || !target) return
    setBusy(true)
    try {
      // Geri alma için baytlar onaydan SONRA, silmeden ÖNCE okunur (inceleme 2026-11-20: Vazgeç'te hiç istek gitmez; yuva
      // sökülünce kesilir). Süre sınırlı; görsel ETag + özel önbellekle çoğunlukla tarayıcıdan anında gelir. Okunamazsa /
      // büyükse geri alma yok, silme yine olur
      const file = readOnly ? null : await startSnapshot(target).promise
      if (!alive.current) return
      const res = await api.techErrors.deleteImage(reportId, target.id)
      if (res?.success) {
        setNote(null)
        onImagesChange((list) => list.filter((i) => i.id !== target.id))
        const restore = onRemoved?.()
        if (file) offerUndo('remove', file, null, typeof restore === 'function' ? restore : null)
      }
      else setError(res?.error || t('te.img.removeError'))
    } catch (e) {
      setError(e?.message || t('te.img.removeError'))
    } finally {
      setBusy(false)
    }
  }

  // Yapıştırma (Ctrl/⌘+V, "Panodan yapıştır") ve "Ekrandan al" aynı yükleme yoluna varır (capture/SlotCapture) — anomali
  // konusu görselinde de (ImageSlotTile): `beforeUpload` (kaydedilmemiş konu önce kaydedilir) ve nedenli ret metni aynı.
  // Ad bağlamlı ("Konu — Görsel 1"): iki konunun "Görsel 1" yuvası ekran okuyucuda / onayda karışmasın
  const cap = useSlotCapture({ slotId: slot.id, label: fullLabel, busy, readOnly, inPanel, hasImage: !!image, confirmReplace, onFile: upload })
  const inputId = `te-img-${slot.id}`

  // Yükleme / silme bitince: sürerken düğmeler kapalıydı (ya da "Kaldır" kalktı) ve odak sayfanın gövdesine düşmüş olabilir —
  // yuvaya döner (klavye kullanıcısı kaldığı yerden sürer). Kullanıcı başka bir yere geçtiyse odağa dokunulmaz (inceleme
  // 2026-10-18: kırpmadan sonra odak kapalı "Ekrandan al"a dönüp kayboluyordu)
  const wasBusy = useRef(false)
  const zoneRef = cap.zoneRef

  // Sağlayıcıya kayıt (C2 / C4): sıralı yapıştırma ve toplu bırakma bu yuvaya YUVANIN yolundan yükler. `mode: 'empty'`
  // dolu yuvaya ASLA yüklemez (değiştirme yok); `replace` yalnız kullanıcı dolu yuvayı açıkça seçtiyse (onay sorulmuştur).
  // Sonuç `{ status: 'done'|'skipped'|'failed', reason?, message? }` — hata metni yuvanın kendi iletisi
  const uploadRef = useRef(upload)
  uploadRef.current = upload
  const handleRef = useRef(null)
  handleRef.current = {
    upload: async (file, { mode = 'empty', advance = false, quiet = false } = {}) => {
      if (readOnlyRef.current) return { status: 'skipped', reason: 'readOnly' }
      if (busyRef.current) return { status: 'skipped', reason: 'busy' }
      // Gönderilemeyen (bekletilen) görsel varsa yeni yükleme onu sessizce bırakırdı — iki kipte de atlanır (inceleme 2026-11-20)
      if (keptRef.current) return { status: 'skipped', reason: 'kept' }
      if (mode !== 'replace' && imageRef.current) return { status: 'skipped', reason: 'filled' }
      errorRef.current = null
      const res = await uploadRef.current(file, { undo: true, advance, quiet })
      if (res) return { status: 'done', image: res }
      return errorRef.current ? { status: 'failed', message: errorRef.current } : { status: 'skipped', reason: 'notUploaded' }
    },
    focus: () => {
      const z = zoneRef.current
      if (!z || !z.isConnected) return false
      try { z.focus() } catch { return false }
      return document.activeElement === z
    },
    isBusy: () => busyRef.current,
    hasImage: () => !!imageRef.current,
    hasKept: () => !!keptRef.current,
    /** Öğe bu yuvanın içinde mi (yükleme sonrası odak yalnız yuvadan ya da boştan taşınır). */
    ownsFocus: (el) => !!el && !!zoneRef.current?.contains?.(el),
  }
  useEffect(() => fillApi?.register?.(slot.id, handleRef), [fillApi, slot.id])

  useEffect(() => {
    if (wasBusy.current && !busy) {
      try {
        const a = document.activeElement
        if (!a || a === document.body || !a.isConnected || a.disabled) zoneRef.current?.focus?.({ preventScroll: true })
      } catch { /* yok */ }
    }
    wasBusy.current = busy
  }, [busy, zoneRef])

  // Eylemler HER yuvada AYNI (2026-11-20, kullanıcı isteği: "görsel yükle, Ekrandan al, panodan yapıştır her bir panelde
  // eşit olmalı, aynı sırada olmalı, farklılık olmamalı"): Görsel yükle → Ekrandan al → Panodan yapıştır, tek satırda eşit
  // boyda düğmeler; görsel varsa altında "Kaldır". Yarım / tam yuva, konu görseli ve Görsel paneli aynı kodu kullanır —
  // yarım yuvanın "Diğer işlemler" menüsü kaldırıldı. Ana eylem sayısı cihazın yeteneğine bağlıdır (ekran paylaşımı / pano
  // arayüzü yoksa o düğme hiç çizilmez) — aynı cihazda bütün yuvalarda aynıdır.
  const mainCount = 1 + (cap.snipButton ? 1 : 0) + (cap.pasteButton ? 1 : 0)
  return (
    <>
    <div className={cn('flex min-w-0 flex-col gap-2 rounded-xl border p-3', cap.zoneClassName, drag && 'border-primary bg-primary/5', className)}
      {...cap.zoneProps}
      data-slot="te-image-slot" data-slot-id={slot.id} data-width={slot.width} data-has-image={image ? 'true' : 'false'}
      onDragOver={readOnly ? undefined : (e) => { e.preventDefault(); setDrag(true) }}
      onDragLeave={readOnly ? undefined : () => setDrag(false)}
      onDrop={readOnly ? undefined : (e) => {
        e.preventDefault()
        // Yuvaya bırakılan dosya bu yuvanın işidir: bölümün toplu bırakma penceresi (C4) açılmaz
        e.stopPropagation()
        setDrag(false)
        const files = Array.from(e.dataTransfer?.files || [])
        const f = files[0]
        if (f && !busy) {
          upload(f)
          // Birden çok dosya tek yuvaya bırakıldı (C4 asgari güvence): yalnız ilki yüklendi — sessiz kalmaz
          if (files.length > 1) toast.info(t('te.bulk.slotMulti', files.length, label), 10000)
        }
      }}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <p data-slot="te-copy-row" className="min-w-0 text-sm font-semibold leading-snug [overflow-wrap:anywhere]">
          <span data-slot="te-image-label">{label}</span>
          <CopyTextButton text={label} />
        </p>
        {(!hideWidth || prevSrc) && (
          <div className="flex shrink-0 flex-wrap items-center gap-2">
            {!hideWidth && <span className="text-xs text-muted-foreground">{slot.width === 'full' ? t('te.width.full') : t('te.width.half')}</span>}
            {/* Geçen ay (C3): isteyince — açılınca geçen ayın raporu BİR KEZ okunur (bütün yuvalar aynı okumayı paylaşır) */}
            {prevSrc && (
              <Button type="button" size="sm" variant={pv.on ? 'secondary' : 'ghost'} aria-pressed={pv.on} onClick={pv.toggle}
                className="h-7 gap-1 px-2 text-xs pointer-coarse:min-h-10" data-slot="te-image-prev-toggle">
                <History aria-hidden="true" /> {prevExact ? t('te.prev.toggle') : t('te.prev.toggleOther')}
                <span className="sr-only">{`: ${fullLabel}`}</span>
              </Button>
            )}
          </div>
        )}
      </div>
      {image ? (
        <>
          {/* Yuvanın genişliğini doldurur, oran korunur (kullanıcı kararı 2026-10-16: "hepsi yüklendiği alanın boyutunu
              alsın") — eskiden 320 px'lik tavan görseli daraltıp yanlarda boşluk bırakıyordu. PDF ile aynı kural: yalnız
              sayfaya sığmayacak kadar uzun görsel daralır (displayWidthPct). width / height yer ayırır (yüklenince kayma yok) */}
          {/* Tıklayınca büyük görünüm (2026-10-16, kullanıcı isteği: "görsele tıklanınca büyüteç gibi pop-up açılsın,
              × ile kapansın") */}
          <Button ref={zoomRef} type="button" variant="ghost" onClick={() => { setZoomFrom('current'); setZoomView('current'); setZoomed(true) }}
            aria-label={t('te.img.zoom', fullLabel)}
            data-slot="te-image-zoom" style={{ maxWidth: `${displayWidthPct(slot.width, image.width, image.height)}%` }}
            className="group relative mx-auto block h-auto w-full cursor-zoom-in rounded-md p-0 hover:bg-transparent">
            <img src={api.techErrors.imageUrl(image.id)} alt={fullLabel} loading="lazy" data-slot="te-image"
              width={image.width || undefined} height={image.height || undefined}
              className="block h-auto w-full rounded-md border" />
            <span aria-hidden="true"
              className="pointer-events-none absolute right-2 top-2 rounded-md border bg-background/90 p-1.5 text-foreground opacity-80 shadow-sm transition-opacity group-hover:opacity-100">
              <ZoomIn className="size-4" />
            </span>
          </Button>
          <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
            {[image.file_name, `${image.width} × ${image.height}`, formatBytes(image.size_bytes, uiLang)].filter(Boolean).join(' · ')}
          </p>
          {note && note.imageId === image.id && (
            <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-image-note">{noteText(note.info)}</p>
          )}
        </>
      ) : (
        <div className="flex min-h-28 flex-col items-center justify-center gap-1 rounded-md border border-dashed px-3 py-4 text-center text-sm text-muted-foreground">
          <ImagePlus aria-hidden="true" className="size-6" />
          <span>{readOnly ? t('te.img.none') : t('te.img.drop')}</span>
          {cap.emptyHint}
        </div>
      )}
      {prevSrc && pv.on && (
        // Geçen ayın aynı yuvası (C3): yalnız gösterilir, ASLA kopyalanmaz. Okunamadıysa "okunamadı" + yeniden dene (ASLA "yoktu")
        <div className="flex min-w-0 flex-col gap-2 rounded-md border border-dashed bg-muted/30 p-2" data-slot="te-image-prev"
          data-state={pv.state.status}>
          <p className="m-0 text-xs font-medium text-muted-foreground [overflow-wrap:anywhere]">{t('te.prev.heading', prevPeriod)}</p>
          {(pv.state.status === 'loading' || pv.state.status === 'idle') && (
            <p className="m-0 inline-flex items-center gap-1.5 text-xs text-muted-foreground">
              <Spinner className="size-3.5" /> {t('te.prev.loading', prevPeriod)}
            </p>
          )}
          {pv.state.status === 'failed' && (
            <div className="flex flex-wrap items-center gap-2">
              <p className="m-0 min-w-0 text-xs text-destructive [overflow-wrap:anywhere]" role="alert" data-slot="te-image-prev-failed">
                {pv.state.error === 'gone' ? t('te.prev.fail.gone', prevPeriod) : pv.state.error === 'timeout' ? t('te.prev.fail.timeout', prevPeriod) : t('te.prev.fail.failed', prevPeriod)}
              </p>
              <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" onClick={pv.retry} data-slot="te-image-prev-retry">
                {t('te.retry')}<span className="sr-only">{`: ${fullLabel}`}</span>
              </Button>
            </div>
          )}
          {pv.state.status === 'ok' && prevImage && (
            <>
              <Button ref={prevThumbRef} type="button" variant="ghost" data-slot="te-image-prev-open"
                aria-label={t('te.prev.zoom', fullLabel, prevPeriod)}
                className="h-auto w-fit max-w-full cursor-zoom-in justify-start rounded-md p-0 hover:bg-transparent pointer-coarse:min-h-10 pointer-coarse:min-w-10"
                onClick={() => { setZoomFrom('prev'); setZoomView('prev'); setZoomed(true) }}>
                <img src={api.techErrors.imageUrl(prevImage.id)} alt={t('te.prev.alt', fullLabel, prevPeriod)} loading="lazy"
                  width={prevImage.width || undefined} height={prevImage.height || undefined} data-slot="te-image-prev-img"
                  className="block h-auto max-h-40 w-auto max-w-full rounded border" />
              </Button>
              <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                {[prevImage.file_name, prevImage.width && prevImage.height ? `${prevImage.width} × ${prevImage.height}` : null,
                  prevImage.size_bytes != null ? formatBytes(prevImage.size_bytes, uiLang) : null].filter(Boolean).join(' · ')}
              </p>
            </>
          )}
          {pv.state.status === 'ok' && !prevImage && (
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-image-prev-none">{t('te.prev.none', prevPeriod)}</p>
          )}
        </div>
      )}
      {!readOnly && (
        // Ana eylemler eşit sütunlu bir ızgarada, hep aynı sırada (SlotCapture.jsx → SLOT_ACTION_CLASS / MAIN_ACTION_GRID):
        // kap yeterince genişse tek satırda eşit karolar, çok darsa alt alta. Kabın genişliği bütün yuvalarda aynıdır — en
        // çok 448 px; iki sütunlu ızgarada satırı kaplayan (tam) yuvada yarım yuvanın iç genişliği (`pairedRow`) — bu yüzden
        // aynı ekranda yarım ve tam yuva hep aynı dizilir. "Kaldır" aynı ızgaranın SON hücresidir (slotActionGrid: o sütun
        // görsel yokken boş durur) — öteki düğmelerle aynı satırda, aynı boyda; yükleme sonrası ana düğmeler kaymaz.
        <div className={cn(SLOT_ACTIONS_WRAP, pairedRow && SLOT_ACTIONS_PAIRED)} data-slot="te-image-actions" data-count={mainCount}>
          <Input ref={inputRef} id={inputId} type="file" accept={ACCEPT} hidden tabIndex={-1} aria-hidden="true"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f) }} />
          <div className={cn('grid gap-2', slotActionGrid(mainCount))} data-slot="te-image-main-actions">
            {/* Erişilebilir ad görünen metni İÇERİR (yuva adı yalnız ekran okuyucuya eklenir — etiket-adda kuralı) */}
            <Button type="button" size="sm" variant={image ? 'outline' : 'default'} disabled={busy}
              onClick={() => inputRef.current?.click()} className={SLOT_ACTION_CLASS} data-slot="te-image-upload">
              {busy ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" />
                : image ? <RefreshCw aria-hidden="true" /> : <Upload aria-hidden="true" />}
              {busy ? (phase === 'prep' ? t('te.img.preparing') : t('te.img.uploading')) : image ? t('te.img.replace') : t('te.img.upload')}
              <span className="sr-only">{`: ${fullLabel}`}</span>
            </Button>
            {cap.snipButton}
            {cap.pasteButton}
            {image && (
              <Button type="button" size="sm" variant="outline" disabled={busy} onClick={remove}
                className={cn(SLOT_ACTION_CLASS, 'text-destructive hover:text-destructive')} data-slot="te-image-remove">
                <Trash2 aria-hidden="true" /> {t('te.img.remove')}
                <span className="sr-only">{`: ${fullLabel}`}</span>
              </Button>
            )}
          </div>
        </div>
      )}
      {error && <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert" data-slot="te-image-error">{error}</p>}
      {/* Bekletilen görsel (kilit çakışması / ağ hatası): yeniden almadan yeniden dene ya da bırak */}
      {kept && !busy && !readOnly && (
        <div className="flex min-w-0 flex-col gap-2" data-slot="te-image-kept">
          <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.img.keptHint')}</p>
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" size="sm" variant="outline" onClick={retryKept} className="pointer-coarse:min-h-10"
              data-slot="te-image-retry">
              <RefreshCw aria-hidden="true" /> {t('te.img.retryKept')}
              <span className="sr-only">{`: ${fullLabel}`}</span>
            </Button>
            <Button type="button" size="sm" variant="ghost" className="pointer-coarse:min-h-10" data-slot="te-image-kept-drop"
              onClick={() => { setKept(null); setError(null) }}>
              {t('te.img.dropKept')}
              <span className="sr-only">{`: ${fullLabel}`}</span>
            </Button>
          </div>
        </div>
      )}
      {cap.notes}
    </div>
    {cap.dialog}
    {/* Büyük görünüm yuvanın bırakma alanının DIŞINDA: portal olayları React ağacında kabarcıklanır — içerideyken açık
        pencereye bırakılan dosya bu yuvaya sessizce yükleniyordu (inceleme 2026-10-16) */}
    {(image || prevImage) && (
      <ImageLightbox open={zoomed} onOpenChange={setZoomed} src={image ? api.techErrors.imageUrl(image.id) : null} label={fullLabel}
        width={image?.width} height={image?.height} returnFocusRef={zoomFrom === 'prev' && prevImage ? prevThumbRef : zoomRef}
        // Geçen ay (C3): görünüm açıksa büyük görünümde "Bu ay | Geçen ay" (ikisi de varsa)
        prev={prevImage ? { src: api.techErrors.imageUrl(prevImage.id), width: prevImage.width, height: prevImage.height,
          caption: t('te.prev.caption', prevPeriod), toggle: prevExact ? t('te.prev.lastMonth') : t('te.prev.toggleOther') } : null}
        view={zoomView} onViewChange={setZoomView} />
    )}
    </>
  )
}
