import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ClipboardPaste, RefreshCw, ScreenShare } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { useT } from '../../../i18n/index.jsx'
import { useIsMobile } from '../../../hooks/use-mobile.js'
import { markActivity } from '../../../utils/crossTabActivity.js'
import { captureFileName, imageFromDataTransfer, shortcutFamily } from './captureModel.js'
import { CaptureError, canCaptureScreen, captureSession, useCaptureState } from './captureSession.js'
import { ClipboardImageError, canReadClipboard, readClipboardImage, renameClipboardFile } from './clipboardImage.js'
import { cropFrameToFile, frameUniform, releaseFrame } from './frameReader.js'

// Kırpma penceresi yalnız "Ekrandan al" kullanılınca yüklenir (lazyChunks kapısı)
const CaptureCropDialog = lazy(() => import('./CaptureCropDialog.jsx'))

/** Yapıştırma ve ekran görüntüsü kısayolları (işletim sistemine göre). */
export function shortcutKeys(family = shortcutFamily()) {
  return family === 'mac'
    ? { paste: '⌘V', snip: '⌘⇧⌃4' }
    : family === 'win' ? { paste: 'Ctrl+V', snip: 'Win+Shift+S' } : { paste: 'Ctrl+V', snip: 'PrtSc' }
}

/**
 * Yuva eylemleri HER yuvada AYNI (2026-11-20, kullanıcı isteği: "görsel yükle, Ekrandan al, panodan yapıştır her bir panelde
 * eşit olmalı, aynı sırada olmalı, farklılık olmamalı"): Görsel yükle → Ekrandan al → Panodan yapıştır; eşit genişlikte
 * düğmeler. Yarım / tam yuva, konu görseli ve Görsel paneli aynı kodu kullanır ("Diğer işlemler" menüsü yok).
 *
 * <p>Diziliş eylem KABININ genişliğine bağlıdır (`@container`) ve kap bütün yuvalarda aynı genişlikte tutulur
 * (`SLOT_ACTIONS_WRAP` / `SLOT_ACTIONS_PAIRED`) — bu yüzden aynı ekranda yarım ve tam yuva HEP aynı görünür:
 * <ul>
 *   <li>yeterli genişlikte (3 eylem: ≥ 320 px, 2 eylem: ≥ 224 px) tek satırda eşit "karo"lar — simge üstte, etiket altta;</li>
 *   <li>daha dar kapta (ör. 768 px'te kenar çubuğu açıkken yarım yuva ≈ 160 px) alt alta tam genişlik düğmeler — üç karo
 *       oraya sığmaz, sözcükler harf harf bölünürdü.</li>
 * </ul>
 * Sıra iki dizilişte de aynıdır. Uzun etiket sarar (kesilmez).
 */
export const SLOT_ACTION_CLASS = 'h-auto min-h-8 w-full min-w-0 shrink gap-1.5 px-2 py-1.5 text-center text-sm whitespace-normal pointer-coarse:min-h-10'

/** Karo dizilişi (kap yeterince genişken): simge üstte, etiket altta, bir boy küçük yazı, en az 48 px yükseklik. */
const TILE_3 = '@xs:grid-cols-3 @xs:[&>button]:min-h-12 @xs:[&>button]:flex-col @xs:[&>button]:gap-1 @xs:[&>button]:text-xs'
const TILE_2 = '@[14rem]:grid-cols-2 @[14rem]:[&>button]:min-h-12 @[14rem]:[&>button]:flex-col @[14rem]:[&>button]:gap-1 @[14rem]:[&>button]:text-xs'

/** Ana eylemlerin ızgarası: eylem sayısı kadar EŞİT sütun (kap dar ise tek sütun). */
export const MAIN_ACTION_GRID = Object.freeze({ 1: 'grid-cols-1', 2: `grid-cols-1 ${TILE_2}`, 3: `grid-cols-1 ${TILE_3}` })

/**
 * Eylem kabı: en çok 28rem (448 px) — geniş yuvada düğmeler gereksiz büyümez. `SLOT_ACTIONS_PAIRED`: iki sütunlu görsel
 * ızgarasında (md ve üstü) satırın TAMAMINI kaplayan yuvada kap, yarım yuvanın iç genişliğine indirilir — yarım yuva içi
 * = tam yuva içi / 2 − 19 px (ızgara aralığı 12, yuva iç boşluğu 2 × 12, kenarlık 2 × 1). Böylece yarım ve tam yuvada
 * kap (dolayısıyla diziliş ve düğme genişliği) piksel piksel aynıdır (e2e ölçer).
 */
export const SLOT_ACTIONS_WRAP = '@container min-w-0 max-w-md'
export const SLOT_ACTIONS_PAIRED = 'md:max-w-[min(28rem,calc(50%-19px))]'

const isEditable = (el) => !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName || ''))

/** Canlı önizlemede kullanıcı hareketi boşta sayacını en çok bu aralıkla yeniler. */
const LIVE_KEEPALIVE_MS = 5000

/**
 * Kaydırma (tekerlek iletimi) çağrısının sonucu → arayüz durumu. `byGesture`: çağrı kullanıcının tıklamasıyla yapıldı
 * (izin penceresi gösterilebildi) — o zaman "izin gerek" yerine "açılamadı" denir, yeniden deneme düğmesi kalır.
 */
export function scrollStateOf(e, byGesture) {
  const kind = e instanceof CaptureError ? e.kind : 'scrollFailed'
  if (kind === 'ended') return 'off'
  if (kind === 'scrollDenied') return 'denied'
  if (kind === 'scrollUnsupported') return 'unsupported'
  // scrollNeedsGesture / scrollFailed: düğmeyle (tıklama içinde) denenmediyse önce düğme önerilir
  return byGesture ? 'failed' : 'ask'
}

/**
 * {@link scrollStateOf} + "reddedildi" ayrımı: tarayıcı NotAllowedError'ı hem KALICI engelde (kullanıcı "Engelle" dedi,
 * izin politikası) hem de izin sorusu yanıtlanmadan kapatıldığında verir. İkincisinde izin durumu hâlâ `prompt`tur —
 * kullanıcı yeniden deneyebilmelidir (düğme kalır); yalnız gerçek engelde "site ayarlarından açın" denir.
 * `permission()` → 'granted' | 'prompt' | 'denied' | 'unknown' (sınırlı süreli; hata = bilinmiyor).
 */
export async function scrollStateAfter(e, byGesture, permission) {
  const first = scrollStateOf(e, byGesture)
  if (first !== 'denied') return first
  let perm = 'unknown'
  try { perm = await permission() } catch { /* bilinmiyor */ }
  if (perm === 'prompt') return byGesture ? 'failed' : 'ask'
  return 'denied'
}

/**
 * Yuvanın HIZLI görsel alma yolları (2026-10-18) — hepsi yuvanın kendi yükleme yoluna (`onFile` = imagePrep →
 * yükleme, aynı sınırlar / notlar / hatalar) varır:
 * <ul>
 *   <li><b>Yapıştır:</b> yuva odaklanabilir; Ctrl/⌘+V ile panodaki görsel yüklenir (metin yapıştırması yok sayılır, kısa
 *   ipucu). Görsel olmayan dosya (dosya yöneticisinden kopyalanan PDF…) yükleme yoluna gider — türünü söyleyen hata yazar.
 *   DOLU yuvaya yapıştırma önce sorar (`confirmReplace`; geri alma yok — inceleme 2026-10-18). "Panodan yapıştır"
 *   `navigator.clipboard.read()` olan tarayıcıda görünür; izin yoksa "Ctrl+V ile yapıştırın" der, ham hata göstermez.</li>
 *   <li><b>Ekrandan al:</b> pencere başına paylaşım oturumu (captureSession); kare kırpma penceresinde seçilen alan PNG
 *   olarak yüklenir. Telefonda ve desteklemeyen tarayıcıda düğme yoktur (bozuk düğme bırakılmaz).</li>
 *   <li><b>Canlı görüntü + kaydırma (2026-11-19, kullanıcı isteği):</b> paylaşılan yüzey bir tarayıcı SEKMESİYSE kırpma
 *   penceresi CANLI açılır: paylaşılan sekme pencerenin tuvaline canlı çizilir ve (tarayıcı destekliyorsa) fare
 *   tekerleği sekmeyi buradan kaydırır. Seçim başlayınca / "Dondur" / "Bu alanı yükle" ile görüntü DONAR (çizim eşzamanlı
 *   durur — yüklenen piksel, görülen pikseldir); "Canlıya dön" yeniden kaydırmayı açar. Pencere / tüm ekran paylaşımında
 *   bugünkü donuk kare + "Yeniden çek" kalır (tarayıcılar o yüzeyleri kaydırmaya izin vermez) ve pencere bunu söyler.</li>
 * </ul>
 * Salt okunur yuvada hiçbiri yoktur; yükleme sürerken yuva yeni görsel almaz (bir yuvada tek yükleme).
 *
 * <p>Dönen parçalar: `zoneProps` (odaklanabilir bırakma alanı, `ref` dahil), `zoneRef`, `pasteButton`, `snipButton`,
 * `notes`, `emptyHint`, `dialog` (düğmeler her yuvada aynı sırada çizilir: yükle → `snipButton` → `pasteButton`). Kırpma penceresi
 * kapanınca odak "Ekrandan al"a döner; düğme o an kapalıysa (yükleme başladı) yuvanın kendisine.
 */
export function useSlotCapture({
  slotId, label, busy = false, readOnly = false, inPanel = false, hasImage = false, confirmReplace = null, onFile,
}) {
  const t = useT()
  const isMobile = useIsMobile()
  const keys = shortcutKeys()
  const [note, setNote] = useState(null)            // { tone: 'muted' | 'error', text }
  const [frame, setFrame] = useState(null)
  const [cropBusy, setCropBusy] = useState(false)
  const [cropError, setCropError] = useState(null)
  const [retaking, setRetaking] = useState(false)
  const [snipping, setSnipping] = useState(false)
  const [clipBusy, setClipBusy] = useState(false)
  // Canlı önizleme: `live` = tuvale canlı çiziliyor; `scroll` = tekerlek iletiminin durumu
  // (off | checking | asking | on | ask | denied | failed | unsupported); `padEl` = tekerleği dinleyen sahne katmanı
  const capture = useCaptureState()
  const [live, setLive] = useState(false)
  const [scroll, setScroll] = useState('off')
  const [padEl, setPadEl] = useState(null)
  const [liveEnded, setLiveEnded] = useState(false)
  // Açık kare bir SEKME paylaşımından mı geldi (paylaşım biterse / yüzey değişirse pencere bunu söyler)
  const [tab, setTab] = useState(false)
  const liveRef = useRef(false)
  liveRef.current = live
  const stopPaintRef = useRef(null)
  const wheelSeq = useRef(0)
  const lastKeep = useRef(0)
  // Pencere kapanınca artar: o sırada süren "Yeniden çek"in sonucu kapalı pencereyi yeniden AÇMAZ
  const cropSeq = useRef(0)
  const snipRef = useRef(null)
  const zoneRef = useRef(null)
  const frameRef = useRef(frame)
  frameRef.current = frame
  const onFileRef = useRef(onFile)
  onFileRef.current = onFile
  const busyRef = useRef(busy)
  busyRef.current = busy
  const hasImageRef = useRef(hasImage)
  hasImageRef.current = hasImage
  const confirmRef = useRef(confirmReplace)
  confirmRef.current = confirmReplace
  const asking = useRef(false)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; releaseFrame(frameRef.current) }
  }, [])

  // Kırpma kapanınca odak: "Ekrandan al" (Vazgeç) ya da — yükleme başladıysa düğme kapalıdır — yuvanın kendisi
  const returnFocusRef = useMemo(() => ({
    get current() {
      const b = snipRef.current
      return b && !b.disabled ? b : zoneRef.current || b
    },
  }), [])

  // Paylaşılan yüzey bir sekme ve paylaşım açık: pencere canlı çalışabilir
  const canLive = capture.status === 'live' && capture.surface === 'browser'
  const canvas = frame?.canvas || null

  /**
   * Canlı çizimi EŞZAMANLI durdurur (bu andan sonra tuval değişmez) ve tek renk denetimini DONAN kareye yapar. Tek yol:
   * kullanıcının dondurması, paylaşımın bitmesi, yüzeyin sekme olmaktan çıkması ve çizimin başlatılamaması buradan geçer.
   */
  const haltLive = useCallback(() => {
    liveRef.current = false
    const stop = stopPaintRef.current
    stopPaintRef.current = null
    try { stop?.() } catch { /* yok */ }
    setLive(false)
    const f = frameRef.current
    if (f) {
      const uniform = frameUniform(f)
      setFrame((cur) => (cur && cur.canvas === f.canvas && cur.uniform !== uniform ? { ...cur, uniform } : cur))
    }
  }, [])

  // Canlı çizim: yalnız pencere açık + canlıyken; dondurma / kapanış / ayrılma durdurur
  useEffect(() => {
    if (!canvas || !live) return undefined
    const stop = captureSession.paint(canvas, {
      onResize: (w, h) => setFrame((cur) => (cur && cur.canvas === canvas ? { ...cur, width: w, height: h } : cur)),
    })
    if (!stop) {
      // Paylaşım bu arada kapandı: eldeki kare donuk kalır
      haltLive()
      return undefined
    }
    stopPaintRef.current = stop
    return () => {
      // Dondurma / kapanış çizimi EŞZAMANLI durdurduysa burada yeniden durdurulmaz
      if (stopPaintRef.current !== stop) return
      stopPaintRef.current = null
      stop()
    }
  }, [canvas, live, haltLive])

  // Sekme paylaşımı pencere açıkken biterse (tarayıcıdan durdurma, 10 dk boşta) — canlıyken de DONUKKEN de: son görüntü
  // donar ve pencere bunu söyler ("Canlıya dön" artık yoktur; çubuk sessizce kaybolmaz)
  const hasFrame = !!frame
  useEffect(() => {
    if (!hasFrame || !tab || liveEnded || capture.status === 'live') return
    haltLive()
    setLiveEnded(true)
  }, [hasFrame, tab, liveEnded, capture.status, haltLive])

  // Canlıyken paylaşılan yüzey sekme olmaktan çıkarsa (tarayıcıdan başka kaynağa geçiş): pencere / ekran buradan
  // kaydırılamaz — görüntü donar, pencere nedenini söyler ("tarayıcınız desteklemiyor" denmez) ve "Yeniden çek" gelir
  useEffect(() => {
    if (live && capture.status === 'live' && !canLive) haltLive()
  }, [live, capture.status, canLive, haltLive])

  // Tekerlek iletimi: canlıyken katman hazır olunca ÖNCE izin durumuna bakılır. İzin verilmişse sessizce açılır; henüz
  // sorulmamışsa (`prompt`) çağrı YAPILMAZ — tarayıcının izin sorusu yalnız kullanıcı "Kaydırmayı etkinleştir"e basınca
  // açılır (ne istendiği önce pencerede yazar); engelliyse nedeni söylenir. Durum sorgulanamıyorsa (eski tarayıcı) çağrı
  // denenir. Donunca / kapanınca iletim durdurulur.
  useEffect(() => {
    if (!live || !padEl) { setScroll('off'); return undefined }
    if (!capture.scroll) { setScroll('unsupported'); return undefined }
    const my = ++wheelSeq.current
    const mine = () => alive.current && wheelSeq.current === my
    setScroll('checking')
    captureSession.scrollPermission().then((perm) => {
      if (!mine()) return
      if (perm === 'denied') { setScroll('denied'); return }
      if (perm === 'prompt') { setScroll('ask'); return }
      // Sorgulanamayan tarayıcıda çağrı izin sorusu açabilir: pencere beklediğini söyler
      if (perm !== 'granted') setScroll('asking')
      captureSession.forwardWheel(padEl).then(
        () => { if (mine()) setScroll('on') },
        (e) => scrollStateAfter(e, false, captureSession.scrollPermission).then((s) => { if (mine()) setScroll(s) }),
      )
    })
    return () => {
      wheelSeq.current++
      captureSession.forwardWheel(null).catch(() => {})
    }
  }, [live, padEl, capture.scroll, capture.label])

  const editable = !readOnly
  const desktop = inPanel || !isMobile
  const showSnip = editable && desktop && canCaptureScreen()
  const showClip = editable && canReadClipboard()

  const give = useCallback((file) => {
    markActivity()
    setNote(null)
    onFileRef.current?.(file)
  }, [])

  const captureNote = useCallback((e) => {
    const kind = e instanceof CaptureError ? e.kind : 'failed'
    if (kind === 'denied') return { tone: 'muted', text: t('te.cap.snipDenied') }
    if (kind === 'busy') return { tone: 'muted', text: t('te.cap.snipBusy') }
    if (kind === 'ended') return { tone: 'muted', text: t('te.cap.snipEnded') }
    if (kind === 'unsupported') return { tone: 'error', text: t('te.cap.snipUnsupported', keys.snip, keys.paste) }
    if (e?.cause?.kind === 'noFrame') return { tone: 'error', text: t('te.cap.snipNoFrame') }
    return { tone: 'error', text: t('te.cap.snipFailed', keys.snip, keys.paste) }
  }, [t, keys.snip, keys.paste])

  /** Dolu yuvada önce sorar (geri alma yok); onaylanınca yükleme hâlâ boşsa verir. */
  const giveAfterConfirm = useCallback(async (file) => {
    if (asking.current) return
    asking.current = true
    let ok = false
    try {
      ok = await confirmRef.current()
    } catch {
      ok = false
    } finally {
      asking.current = false
    }
    if (!ok || !alive.current) return
    if (busyRef.current) { setNote({ tone: 'muted', text: t('te.cap.pasteBusy') }); return }
    give(file)
  }, [give, t])

  const onPaste = useCallback((e) => {
    if (readOnly || isEditable(e.target)) return
    const { file, hasText, image } = imageFromDataTransfer(e.clipboardData)
    if (!file) {
      if (hasText) setNote({ tone: 'muted', text: t('te.cap.pasteNoImage', keys.snip) })
      return
    }
    e.preventDefault()
    if (busyRef.current) { setNote({ tone: 'muted', text: t('te.cap.pasteBusy') }); return }
    // Görsel türü taşımayan dosya kendi adıyla gider: imagePrep türünü baytlardan okur — görselse yüklenir, değilse
    // türünü söyleyen hatayı yazar. Dolu yuvada TÜRÜNE BAKILMADAN önce sorulur (türsüz bir PNG de görseli değiştirir —
    // 2. inceleme turu 2026-10-18)
    const next = image ? renameClipboardFile(file, slotId) : file
    if (hasImageRef.current && typeof confirmRef.current === 'function') { giveAfterConfirm(next); return }
    give(next)
  }, [readOnly, give, giveAfterConfirm, slotId, t, keys.snip])

  async function pasteFromClipboard() {
    if (busyRef.current || clipBusy) return
    setNote(null)
    markActivity()
    setClipBusy(true)
    try {
      const file = await readClipboardImage(slotId)
      if (!alive.current) return
      give(file)
    } catch (e) {
      if (!alive.current) return
      const kind = e instanceof ClipboardImageError ? e.kind : 'failed'
      setNote({ tone: 'muted', text: kind === 'empty' ? t('te.cap.clipEmpty', keys.snip) : kind === 'denied' ? t('te.cap.clipDenied', keys.paste)
        : t('te.cap.clipFailed', keys.paste) })
    } finally {
      if (alive.current) setClipBusy(false)
    }
  }

  async function snip() {
    if (busyRef.current || snipping) return
    setNote(null)
    markActivity()
    setSnipping(true)
    try {
      // Tıklama olayının içinde: paylaşım kapalıysa tarayıcının seçicisi açılır
      const f = await captureSession.grab()
      markActivity()
      if (!alive.current) { releaseFrame(f); return }
      releaseFrame(frameRef.current)
      setCropError(null)
      setLiveEnded(false)
      setFrame(f)
      // Sekme paylaşımında pencere CANLI açılır (kaydırıp doğru yeri bulmak için); pencere / ekranda donuk kare
      const isTab = captureSession.getState().surface === 'browser'
      setTab(isTab)
      setLive(isTab)
    } catch (e) {
      if (alive.current) setNote(captureNote(e))
    } finally {
      if (alive.current) setSnipping(false)
    }
  }

  /** Görüntüyü dondurur: çizim EŞZAMANLI durur (bu andan sonra tuval değişmez), tek renk denetimi donuk kareye yapılır. */
  const freeze = useCallback(() => {
    if (!liveRef.current) return
    haltLive()
    captureSession.keepAlive()
    markActivity()
  }, [haltLive])

  /** Canlıya dön: paylaşılan sekmede başka bir yere gitmek için (pencere seçimi tüm kareye döndürür). */
  function resume() {
    if (!frameRef.current || cropBusy || liveRef.current) return
    if (captureSession.getState().status !== 'live' || captureSession.getState().surface !== 'browser') return
    markActivity()
    setCropError(null)
    setLive(true)
  }

  /** "Kaydırmayı etkinleştir": tıklamanın içinde çağrılır — tarayıcı izin penceresini gösterebilir. */
  function enableScroll() {
    if (!padEl || !liveRef.current) return
    markActivity()
    const my = ++wheelSeq.current
    const mine = () => alive.current && wheelSeq.current === my
    setScroll('asking')
    captureSession.forwardWheel(padEl).then(
      () => { if (mine()) setScroll('on') },
      (e) => scrollStateAfter(e, true, captureSession.scrollPermission).then((s) => { if (mine()) setScroll(s) }),
    )
  }

  /** Canlı pencerede kullanıcı hareketi (tekerlek, işaretçi, tuş): paylaşımın boşta sayacı seyrek aralıkla yenilenir. */
  const liveActivity = useCallback(() => {
    const now = Date.now()
    if (now - lastKeep.current < LIVE_KEEPALIVE_MS) return
    lastKeep.current = now
    captureSession.keepAlive()
    markActivity()
  }, [])

  async function retake() {
    if (retaking) return
    markActivity()
    setRetaking(true)
    setCropError(null)
    const seq = cropSeq.current
    try {
      const f = await captureSession.grab()
      // Kare beklenirken pencere kapatıldıysa (Vazgeç / Esc) sonuç pencereyi yeniden AÇMAZ: kare bırakılır
      if (!alive.current || cropSeq.current !== seq) { releaseFrame(f); return }
      releaseFrame(frameRef.current)
      setLiveEnded(false)
      setFrame(f)
      // Paylaşım bitmişken "Yeniden çek" yeni paylaşım başlatır: bu kez sekme seçildiyse pencere canlıya geçer
      const isTab = captureSession.getState().surface === 'browser'
      setTab(isTab)
      setLive(isTab)
    } catch (e) {
      if (alive.current && cropSeq.current === seq) setCropError(captureNote(e).text)
    } finally {
      if (alive.current) setRetaking(false)
    }
  }

  function closeCrop() {
    cropSeq.current++
    // Çizim önce durur: bırakılan tuval bir sonraki karede yeniden büyümesin
    liveRef.current = false
    const stop = stopPaintRef.current
    stopPaintRef.current = null
    try { stop?.() } catch { /* yok */ }
    releaseFrame(frameRef.current)
    setFrame(null)
    setLive(false)
    setTab(false)
    setLiveEnded(false)
    setCropError(null)
  }

  async function confirmCrop(rect) {
    const f = frameRef.current
    if (!f || cropBusy) return
    // Canlıyken yükleme: önce dondur — kesilen pikseller kullanıcının o an gördüğü kare olur
    freeze()
    markActivity()
    setCropBusy(true)
    setCropError(null)
    let file
    try {
      file = await cropFrameToFile(f, rect, captureFileName(slotId, 'ekran'))
    } catch {
      if (alive.current) setCropError(t('te.cap.cropFailed'))
      return
    } finally {
      if (alive.current) setCropBusy(false)
    }
    if (!alive.current) return
    closeCrop()
    if (busyRef.current) { setNote({ tone: 'muted', text: t('te.cap.pasteBusy') }); return }
    give(file)
  }

  // Odaklanabilir bırakma alanı: ekran okuyucu yuvanın adını ve yapıştırma kısayolunu duyar
  const zoneProps = editable
    ? { ref: zoneRef, tabIndex: 0, onPaste, role: 'group', 'aria-label': t('te.cap.zoneLabel', label, keys.paste), 'data-paste': 'true' }
    : {}
  const zoneClassName = editable ? 'outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50' : ''

  const pasteButton = showClip ? (
    <Button type="button" size="sm" variant="outline" disabled={busy || clipBusy} onClick={pasteFromClipboard}
      className={SLOT_ACTION_CLASS} data-slot="te-image-paste">
      {clipBusy ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <ClipboardPaste aria-hidden="true" />}
      {t('te.cap.pasteButton')}
      <span className="sr-only">{`: ${label}`}</span>
    </Button>
  ) : null
  const snipButton = showSnip ? (
    <Button ref={snipRef} type="button" size="sm" variant="outline" disabled={busy || snipping} onClick={snip}
      className={SLOT_ACTION_CLASS} data-slot="te-image-snip">
      {snipping ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <ScreenShare aria-hidden="true" />}
      {t('te.cap.snipButton')}
      <span className="sr-only">{`: ${label}`}</span>
    </Button>
  ) : null

  const notes = note ? (
    <p className={cn('text-sm [overflow-wrap:anywhere]', note.tone === 'error' ? 'text-destructive' : 'text-muted-foreground')}
      role={note.tone === 'error' ? 'alert' : 'status'} data-slot="te-capture-note" data-tone={note.tone}>
      {note.text}
    </p>
  ) : null

  const emptyHint = editable && desktop ? (
    <span className="text-xs" data-slot="te-image-paste-hint">{t('te.cap.pasteInline', keys.paste)}</span>
  ) : null

  const dialog = frame ? (
    <Suspense fallback={null}>
      <CaptureCropDialog open frame={frame} label={label} busy={cropBusy} retaking={retaking} error={cropError}
        onRetake={retake} onConfirm={confirmCrop} onCancel={closeCrop} returnFocusRef={returnFocusRef}
        live={live} canLive={canLive} scroll={scroll} liveEnded={liveEnded}
        scrollHint={!canLive && capture.status === 'live'}
        onFreeze={freeze} onResume={resume} onEnableScroll={enableScroll} onPad={setPadEl} onActivity={liveActivity} />
    </Suspense>
  ) : null

  return { zoneProps, zoneRef, zoneClassName, pasteButton, snipButton, notes, emptyHint, dialog }
}
