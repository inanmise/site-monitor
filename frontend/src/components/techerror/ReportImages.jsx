import { memo, useCallback, useEffect, useRef, useState } from 'react'
import { ImagePlus, RefreshCw, Trash2, Upload, ZoomIn } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { CollapsibleCard } from './SectionCollapse.jsx'
import { cn } from '@/lib/utils'
import { LIMITS, formatBytes, imageRows } from './techErrorModel.js'
import { ImagePrepError, displayWidthPct, prepareImage } from './imagePrep.js'
import { CopyTextButton } from './TechErrorParts.jsx'
import { ImageLightbox } from './ImageLightbox.jsx'
import { useSlotCapture } from './capture/SlotCapture.jsx'
import {
  CaptureHint, CaptureSessionChip, CaptureStopNotice, ImagesPanelHeaderButton, useCaptureScope,
} from './capture/CaptureParts.jsx'
import { postImagesChanged } from './capture/imageSync.js'

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
  return (
    // Katlanır kart (2026-10-16): ipucu ve rapor toplamı yalnız açıkken
    <CollapsibleCard sectionId={section.id} slot="te-images" title={resolve(section.title)} contentClassName="flex flex-col gap-3"
      headerEnd={showTotals && !inPanel ? <ImagesPanelHeaderButton reportId={reportId} readOnly={readOnly} /> : null}
      hint={showTotals ? (
        <>
          <p className="text-sm text-muted-foreground">{t('te.img.hint')}</p>
          <p className="text-xs text-muted-foreground" data-slot="te-img-total">
            {t('te.img.total', formatBytes(total, uiLang), formatBytes(LIMITS.reportImageBytes, uiLang))}
          </p>
          <CaptureHint readOnly={readOnly} inPanel={inPanel} />
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
                className={slot.width === 'full' ? 'md:col-span-2' : row.length === 1 ? 'md:col-span-1' : ''} />
            ))}
          </div>
        ))}
    </CollapsibleCard>
  )
})

/**
 * Tek görsel yuvası. Anomali konusu görselleri de bunu kullanır (2026-10-18, `ImageSlotTile`): `beforeUpload` yüklemeden
 * önce çağrılır (konu henüz kaydedilmediyse düzenleyici raporu kaydeder) ve `{ ok, reason }` döner; olmazsa yükleme yapılmaz,
 * NEDENE göre metin yuvada (`UPLOAD_BLOCK_KEYS`). `hideWidth`: genişlik etiketi gösterilmez (konu görseli her zaman tam
 * genişlik). `contextLabel`: görselin erişilebilir adına eklenen bağlam (konu başlığı — "Görsel 1" iki konuda karışmasın).
 * `onRemoved`: görsel silindikten sonra (konu görselinin açıklaması taslaktan düşer).
 */
export function ImageSlotTile(props) {
  return <SlotTile {...props} />
}

function SlotTile({ slot, image, reportId, resolve, uiLang, readOnly, onImagesChange, className, otherBytes = () => 0, beforeUpload = null,
  hideWidth = false, contextLabel = null, onRemoved = null, inPanel = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const inputRef = useRef(null)
  const [busy, setBusy] = useState(false)
  const [phase, setPhase] = useState(null)        // 'prep' (tarayıcıda hazırlanıyor) | 'upload'
  const [error, setError] = useState(null)
  const [note, setNote] = useState(null)          // son yüklemenin küçültme / çevirme özeti
  const [zoomed, setZoomed] = useState(false)     // büyük görünüm (2026-10-16)
  const zoomRef = useRef(null)                    // kapanınca odak görsele döner
  const [drag, setDrag] = useState(false)
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

  async function upload(file) {
    const startId = imageRef.current?.id ?? null
    setError(null)
    setNote(null)
    setKept(null)
    setBusy(true)
    setPhase('prep')
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
      await send(prepared, startId)
    } finally {
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
  async function confirmBeforeRetry(since) {
    let current = imageRef.current ?? null
    try {
      const res = await api.techErrors.report(reportId)
      const list = res?.success ? res.data?.images : null
      if (Array.isArray(list)) current = list.find((i) => i?.slot_id === slot.id) ?? null
    } catch { /* okunamadı: eşitlenmiş liste */ }
    if (!alive.current) return { ok: false }
    if (!current || current.id == null || current.id === since) return { ok: true, since }
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
  async function send(prepared, startId, retry = false) {
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
      if (retry) {
        const go = await confirmBeforeRetry(since)
        if (!go.ok) return
        since = go.since
      }
      let res = await api.techErrors.uploadImage(reportId, slot.id, prepared.file)
      if (isLockConflict(res)) {
        await new Promise((r) => { setTimeout(r, lockRetryDelayMs(res)) })
        if (!alive.current) return
        const go = await confirmBeforeRetry(since)
        if (!go.ok) return
        since = go.since
        res = await api.techErrors.uploadImage(reportId, slot.id, prepared.file)
      }
      if (res?.success && res.data) {
        // Başarı üst listeye ve öteki pencerelere HER ZAMAN yazılır (inceleme 2026-10-19): yükleme sürerken yuva kalktıysa
        // (Vazgeç / yedeği geri yükleme / tam yeniden yükleme yeni kuşak çizer) görsel sunucuda yine de değişti — yeni yuva
        // ve Görsel paneli onu göstermeli. Yalnız bu yuvanın yerel durumu ve bildirim kurulu yuvada
        const uploaded = res.data
        onImagesChange((list) => [...list.filter((i) => i.slot_id !== slot.id), uploaded])
        if (!alive.current) return
        setKept(null)
        const changed = prepared.info.resized || prepared.info.converted || prepared.info.rotated
        // Not YÜKLENEN görsele bağlı: başka biri görseli değiştirip yeniden okunursa eski not o görselin altında kalmaz
        if (changed) setNote({ imageId: res.data.id, info: prepared.info })
        // Not bildirimde de okunur (yuvanın altındaki not görselle birlikte eklendiği için duyurulmaz); uzun metin → uzun süre
        if (changed) toast.success(`${t('te.img.uploaded', label)} ${noteText(prepared.info)}`, 8000)
        else toast.success(t('te.img.uploaded', label))
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
    const ok = await showConfirm({ title: t('te.img.removeTitle'), message: t('te.img.removeBody', fullLabel), variant: 'danger',
      confirmText: t('te.img.remove'), cancelText: t('te.cancel') })
    if (!ok) return
    setBusy(true)
    try {
      const res = await api.techErrors.deleteImage(reportId, image.id)
      if (res?.success) {
        setNote(null)
        onImagesChange((list) => list.filter((i) => i.id !== image.id))
        onRemoved?.()
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
  useEffect(() => {
    if (wasBusy.current && !busy) {
      try {
        const a = document.activeElement
        if (!a || a === document.body || !a.isConnected || a.disabled) zoneRef.current?.focus?.({ preventScroll: true })
      } catch { /* yok */ }
    }
    wasBusy.current = busy
  }, [busy, zoneRef])

  // Yarım yuvada (768 px'te iki yuva yan yana) yalnız "Yükle / Değiştir" ve "Ekrandan al" düğme kalır; "Panodan yapıştır" ve
  // "Kaldır" adlı menüde (inceleme 2026-10-18). Tam yuvada hepsi düğme.
  const half = slot.width !== 'full'
  const moreItems = half ? [
    cap.pasteMenuItem,
    image ? {
      label: t('te.img.remove'), ariaLabel: `${t('te.img.remove')}: ${fullLabel}`, icon: <Trash2 aria-hidden="true" />,
      danger: true, disabled: busy, onClick: remove,
    } : null,
  ].filter(Boolean) : []
  return (
    <>
    <div className={cn('flex min-w-0 flex-col gap-2 rounded-xl border p-3', cap.zoneClassName, drag && 'border-primary bg-primary/5', className)}
      {...cap.zoneProps}
      data-slot="te-image-slot" data-slot-id={slot.id} data-width={slot.width} data-has-image={image ? 'true' : 'false'}
      onDragOver={readOnly ? undefined : (e) => { e.preventDefault(); setDrag(true) }}
      onDragLeave={readOnly ? undefined : () => setDrag(false)}
      onDrop={readOnly ? undefined : (e) => {
        e.preventDefault()
        setDrag(false)
        const f = e.dataTransfer?.files?.[0]
        if (f && !busy) upload(f)
      }}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <p data-slot="te-copy-row" className="min-w-0 text-sm font-semibold leading-snug [overflow-wrap:anywhere]">
          <span data-slot="te-image-label">{label}</span>
          <CopyTextButton text={label} />
        </p>
        {!hideWidth && <span className="shrink-0 text-xs text-muted-foreground">{slot.width === 'full' ? t('te.width.full') : t('te.width.half')}</span>}
      </div>
      {image ? (
        <>
          {/* Yuvanın genişliğini doldurur, oran korunur (kullanıcı kararı 2026-10-16: "hepsi yüklendiği alanın boyutunu
              alsın") — eskiden 320 px'lik tavan görseli daraltıp yanlarda boşluk bırakıyordu. PDF ile aynı kural: yalnız
              sayfaya sığmayacak kadar uzun görsel daralır (displayWidthPct). width / height yer ayırır (yüklenince kayma yok) */}
          {/* Tıklayınca büyük görünüm (2026-10-16, kullanıcı isteği: "görsele tıklanınca büyüteç gibi pop-up açılsın,
              × ile kapansın") */}
          <Button ref={zoomRef} type="button" variant="ghost" onClick={() => setZoomed(true)} aria-label={t('te.img.zoom', fullLabel)}
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
      {!readOnly && (
        // Eylemler kendi genişliğine göre dizilir (@container): dar yuvada alt alta (tam genişlik düğmeler), @xs'ten (320 px)
        // itibaren tek satır — 768 px'te yan yana iki yuvada düğmeler kesilmez / taşmaz
        <div className="@container" data-slot="te-image-actions" data-layout={half ? 'compact' : 'full'}>
          <div className="flex flex-col gap-2 @xs:flex-row @xs:flex-wrap @xs:items-center">
            <Input ref={inputRef} id={inputId} type="file" accept={ACCEPT} hidden tabIndex={-1} aria-hidden="true"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f) }} />
            {/* Erişilebilir ad görünen metni İÇERİR (yuva adı yalnız ekran okuyucuya eklenir — etiket-adda kuralı) */}
            <Button type="button" size="sm" variant={image ? 'outline' : 'default'} disabled={busy}
              onClick={() => inputRef.current?.click()} className="pointer-coarse:min-h-10" data-slot="te-image-upload">
              {busy ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" />
                : image ? <RefreshCw aria-hidden="true" /> : <Upload aria-hidden="true" />}
              {busy ? (phase === 'prep' ? t('te.img.preparing') : t('te.img.uploading')) : image ? t('te.img.replace') : t('te.img.upload')}
              <span className="sr-only">{`: ${fullLabel}`}</span>
            </Button>
            {!half && cap.pasteButton}
            {cap.snipButton}
            {!half && image && (
              <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={remove}
                className="text-destructive pointer-coarse:min-h-10" data-slot="te-image-remove">
                <Trash2 aria-hidden="true" /> {t('te.img.remove')}
                <span className="sr-only">{`: ${fullLabel}`}</span>
              </Button>
            )}
            {half && moreItems.length > 0 && (
              <span className="flex [&>button]:w-full @xs:[&>button]:w-auto" data-slot="te-image-more">
                <KebabMenu variant="labeled" label={t('te.cap.moreActions')} ariaLabel={t('te.cap.moreActionsFor', fullLabel)}
                  items={moreItems} />
              </span>
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
    {image && (
      <ImageLightbox open={zoomed} onOpenChange={setZoomed} src={api.techErrors.imageUrl(image.id)} label={fullLabel}
        width={image.width} height={image.height} returnFocusRef={zoomRef} />
    )}
    </>
  )
}
