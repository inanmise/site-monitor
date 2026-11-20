import { createContext, useContext, useCallback, useEffect, useId, useMemo, useRef } from 'react'
import { createPortal } from 'react-dom'
import { toast as sonner } from 'sonner'
import { Toaster } from '@/components/shadcn/sonner'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { useT } from '@/i18n/index.jsx'
import ErrorDetails from './ErrorDetails.jsx'

// globalThis pin (2026-09-26): Vite HMR bu dosyayı iki modül örneği olarak yükleyince (yığında iki farklı `?t=`) sağlayıcı
// ile useToast ayrı context'lere düşüyor ve "useToast must be used within ToastProvider" ile çöküyordu — i18n/Sidebar deseni.
const ToastCtx = (globalThis.__smToastCtx ??= createContext(null))

let _seq = 0

/** Aynı anda ekranda durabilecek en fazla bildirim — üstü en ESKİsini düşürür. */
const MAX_VISIBLE = 4

/**
 * Sonner'a verilen başlık: mesaj + tekrar sayacı ("×N").
 *
 * <p>{@code data-toast-id} kutuyu bizim kimliğimize bağlar: Sonner'ın `<li>`'si kimlik taşımıyor,
 * gövdeye tıklayınca kapatma (aşağıda) hangi kaydı düşüreceğini buradan okur.
 */
function ToastBody({ id, type, message, count, details, onHold, action = null }) {
  return (
    <div data-toast-id={id} className="flex min-w-0 flex-col gap-1">
      <span className="flex items-center gap-2">
        <span className="min-w-0 [overflow-wrap:anywhere]">{message}</span>
        {count > 1 && (
          <Badge variant="outline" className="border-current/30 text-current tabular-nums">×{count}</Badge>
        )}
      </span>
      {/* Eylem düğmesi (2026-11-20, "Geri al"): tıklama eylemi çalıştırır, ardından kutu kapanır (sarmalayıcının tıklaması) */}
      {action && (
        <Button type="button" size="sm" variant="outline" className="mt-1 w-fit pointer-coarse:min-h-10" data-slot="toast-action"
          onClick={() => { try { action.onClick?.() } catch { /* eylem kendi hatasını bildirir */ } }}>
          {action.label}
        </Button>
      )}
      {/* Hata bildiriminde katlanır "Teknik ayrıntı" (2026-10-08): künye açıkça verildiyse o, yoksa istemcinin
          metin→künye kaydı. Açılınca kutu kendiliğinden kapanmaz — kullanıcı okuyup kopyalayabilsin. */}
      {type === 'error' && (
        <ErrorDetails info={details} message={typeof message === 'string' ? message : undefined}
          onOpenChange={(open) => { if (open) onHold?.() }} />
      )}
    </div>
  )
}

/** `toast.error(msg, 4000)` (eski) ya da `toast.error(msg, { duration, details })` — details: gövde / ApiError / künye. */
function splitOpts(opts, fallbackDuration) {
  if (opts != null && typeof opts === 'object') return { duration: opts.duration ?? fallbackDuration, details: opts.details ?? null }
  return { duration: opts ?? fallbackDuration, details: null }
}

/**
 * Bildirim sağlayıcısı — görünüm shadcn Sonner, KARAR bu dosyada.
 *
 * <p>Sonner'ın kendi zamanlayıcısı ({@code duration}) KULLANILMIYOR ({@code Infinity}): tekrarda
 * süreyi baştan başlatmak, tavanda düşen kutunun zamanlayıcısını öldürmek ve unmount'ta hepsini
 * iptal etmek aşağıdaki sözleşmeler; bunlar Sonner'ın iç zamanlayıcısına bırakılsaydı ne
 * gözlenebilir ne de test edilebilirdi. Sonner yalnız çizer ve kapanış animasyonunu oynatır.
 */
export function ToastProvider({ children }) {
  const t = useT()
  /**
   * Bu sağlayıcının Sonner kimliği. Sonner'ın durum deposu MODÜL düzeyinde tek (global); yeni
   * abone olan Toaster o an etkin olan HER bildirimi yeniden oynatır. Kimlikle süzülmezse başka
   * bir sağlayıcının (iç içe sarmalayan test/araç) kutuları burada da çizilirdi.
   */
  const toasterId = useId()
  /**
   * Açık otomatik-kapanma zamanlayıcıları (id → timeout).
   *
   * Neden gerekli: bunlar temizlenmediğinde, sağlayıcı unmount olduktan SONRA da çalışıp
   * güncelleme tetikliyorlar. Tarayıcıda bu yalnız sessiz bir "unmounted component" güncellemesi;
   * ama jsdom kapatıldıktan sonra React'in `getCurrentEventPriority`'si `window`'a dokunduğu için
   * **yakalanmamış `ReferenceError: window is not defined`** fırlıyor ve vitest tüm koşuyu
   * 1 çıkış koduyla düşürüyor (2026-08-14'te CI'ı bu kırdı: 673 test geçti, koşu yine kırmızı).
   */
  const timers = useRef(new Map())

  /**
   * Görünen bildirimlerin EŞ ZAMANLI defteri ({id, type, message, count}). Karar burada verilir;
   * çizim Sonner'ın işi.
   *
   * <p><b>Neden ref, neden React durumu değil.</b> Sayfanın paralel yükleyicileri aynı turda arka
   * arkaya toast açtığında "aynısı zaten var mı" sorusunun cevabı ANINDA doğru olmalı. Eskiden
   * karar {@code setToasts(prev => …)} güncelleyicisinin içindeydi; React aynı partideki İKİNCİ
   * güncelleyiciyi render'a ertelediği için ikinci kutunun zamanlayıcısı BİRİNCİnin id'sine
   * yazılıyor, ikinci kutu hiç kapanmadan ekranda kalıyordu. (StrictMode güncelleyiciyi bilerek
   * iki kez çağırdığı için id sayacı da orada kayıyordu.) Durum da tutulmuyor: sağlayıcı her
   * bildirimde yeniden çizilmez, altındaki uygulama ağacı da.
   */
  const visible = useRef([])
  /**
   * Eylemli bildirimin kapanış geri çağrıları (2026-11-20, "Geri al"): kutu hangi yoldan kapanırsa kapansın (süre, X,
   * gövdeye tıklama, tavanda düşme, sağlayıcının sökülmesi) BİR KEZ çağrılır — sahibi tuttuğu baytları bırakır.
   */
  const closers = useRef(new Map())
  const runCloser = useCallback((id) => {
    const fn = closers.current.get(id)
    if (!fn) return
    closers.current.delete(id)
    try { fn() } catch { /* kapanış geri çağrısı hatası bildirimi bozmaz */ }
  }, [])

  const clearTimer = useCallback((id) => {
    const tm = timers.current.get(id)
    if (tm) { clearTimeout(tm); timers.current.delete(id) }
  }, [])

  /** Bizim kapattığımız bildirim: defterden düşer, Sonner'a kapanış animasyonu söylenir. */
  const remove = useCallback((id) => {
    clearTimer(id)
    runCloser(id)
    const cur = visible.current
    if (!cur.some(x => x.id === id)) return
    visible.current = cur.filter(x => x.id !== id)
    sonner.dismiss(id)
  }, [clearTimer, runCloser])

  /**
   * Sonner'ın KENDİ kapattığı bildirim (X düğmesi, kaydırma) — ve bizim {@link remove}'umuzun
   * ardından da çağrılır (idempotent). Yalnız defteri senkronlar: Sonner kutuyu zaten kaldırıyor.
   */
  const forget = useCallback((id) => {
    clearTimer(id)
    runCloser(id)
    visible.current = visible.current.filter(x => x.id !== id)
  }, [clearTimer, runCloser])

  /** Kullanıcı kutuyla ilgileniyor ("Teknik ayrıntı" açıldı): kendiliğinden kapanma durur, X ile kapanır. */
  const hold = useCallback((id) => {
    clearTimer(id)
    visible.current = visible.current.map(x => (x.id === id ? { ...x, held: true } : x))
  }, [clearTimer])

  /**
   * Bildirim gösterir.
   *
   * <p><b>Aynı mesaj TEKRARLAMAZ, sayaçlanır.</b> Eskiden her çağrı yeni bir kutu ekliyordu:
   * backend bir an cevap veremediğinde (deploy/restart/ağ) sayfanın paralel yükleyicileri ve
   * 30 sn'lik oto-yenilemesi aynı hatayı arka arkaya raporluyor, ekranın sağı 15–20 özdeş
   * "Sunucu hatası (HTTP 500)" kutusuyla kaplanıyor ve altındaki içerik görünmez oluyordu
   * (kullanıcı bildirimi 2026-09-01). Bilgi bir kez gösterilir; tekrar sayısı "×N" olarak
   * eklenir ve süre yeniden başlar. Sonner'a AYNI kimlikle yapılan çağrı yeni kutu açmaz,
   * mevcut kutuyu günceller.
   *
   * <p>Tavan da var: farklı mesajlar da olsa aynı anda {@code MAX_VISIBLE} kutudan fazlası
   * ekranı kaplar — en eskisi düşer (zamanlayıcısıyla birlikte).
   */
  const show = useCallback((type, message, duration = 3500, details = null, extra = null) => {
    const cur = visible.current
    // Eylemli bildirim (extra.action) hiçbir zaman birleştirilmez: her biri kendi geri alınacak şeyini taşır
    const same = extra?.action ? null : cur.find(x => x.type === type && x.message === message && !x.action)
    let entry
    if (same) {
      entry = { ...same, count: same.count + 1, details: details ?? same.details }
      visible.current = cur.map(x => (x.id === same.id ? entry : x))
    } else {
      entry = { id: `sm-toast-${++_seq}`, type, message, count: 1, details, action: extra?.action || null }
      if (typeof extra?.onClose === 'function') closers.current.set(entry.id, extra.onClose)
      const next = [...cur, entry]
      // Düşen kutunun zamanlayıcısı da ölmeli: kalsaydı ekranda olmayan bir kutu için işleyip
      // haritayı şişirirdi (unmount temizliğinin kapsamı da gereksiz yere büyürdü).
      while (next.length > MAX_VISIBLE) {
        const dropped = next.shift()
        clearTimer(dropped.id)
        runCloser(dropped.id)
        sonner.dismiss(dropped.id)
      }
      visible.current = next
    }
    const id = entry.id
    sonner[type](
      <ToastBody id={id} type={type} message={message} count={entry.count} details={entry.details} onHold={() => hold(id)}
        action={entry.action} />, {
        id,
        toasterId,
        duration: Infinity,
        onDismiss: (st) => forget(st.id),
      })
    clearTimer(id)                          // tekrar geldi → süre baştan
    // "Teknik ayrıntı" açıldıysa (held) kutu kullanıcı kapatana dek kalır — tekrar gelen aynı hata da süreyi kurmaz.
    if (duration > 0 && !entry.held) timers.current.set(id, setTimeout(() => remove(id), duration))
    return id
  }, [clearTimer, forget, remove, toasterId, hold, runCloser])

  // Unmount: bekleyen her zamanlayıcı iptal edilir — sağlayıcı gittikten sonra hiçbir
  // güncelleme tetiklenmemeli. Görünen bildirimler Sonner'ın global deposunda da kapatılır:
  // kalsalardı sonra abone olan bir Toaster (yeniden mount, sıradaki test) onları geri çizerdi.
  useEffect(() => {
    const pending = timers.current
    const ledger = visible
    const closing = closers.current
    return () => {
      for (const tm of pending.values()) clearTimeout(tm)
      pending.clear()
      for (const x of ledger.current) sonner.dismiss(x.id)
      ledger.current = []
      // Eylemli bildirimlerin sahipleri tuttuklarını bırakır (geri alma baytları vb.)
      for (const fn of closing.values()) { try { fn() } catch { /* yok */ } }
      closing.clear()
    }
  }, [])

  /**
   * Kutunun gövdesine tıklamak kapatır (eski davranış). Sonner'ın `<li>`'si tıklama almıyor;
   * olay sarmalayıcıda yakalanıp kutudaki {@code data-toast-id}'den kayda bağlanır. X düğmesi
   * burada ATLANIR: onu Sonner'ın kendisi kapatır ve {@code onDismiss} defteri senkronlar —
   * ikisi birden çalışsa kapanış iki kez tetiklenirdi.
   */
  const dismissFromClick = useCallback((e) => {
    const el = e.target instanceof Element ? e.target : null
    // "Teknik ayrıntı" aç/kopyala tıklaması kutuyu kapatmaz.
    if (!el || el.closest('[data-close-button]') || el.closest('[data-slot="error-details"]')) return
    const id = el.closest('[data-sonner-toast]')?.querySelector('[data-toast-id]')?.getAttribute('data-toast-id')
    if (id) remove(id)
  }, [remove])

  /**
   * Kimliği SABİT tutulur: tüketiciler bu nesneyi bağımlılık dizisine koyuyor
   * (CertInventoryReportSettings, RetentionSettings, SqlPlayground). Her render'da yeniden
   * kurulsaydı, her bildirim o sayfalarda yeniden yükleme tetiklerdi. (Dil `t` bilinçli olarak
   * burada değil: dil değişimi de kimliği değiştirmemeli.)
   */
  const api = useMemo(() => ({
    success: (msg, d) => show('success', msg, d),
    error:   (msg, opts) => { const o = splitOpts(opts, 5000); return show('error', msg, o.duration, o.details) },
    info:    (msg, d) => show('info',    msg, d),
    /**
     * Eylemli bildirim (2026-11-20, "Geri al"): `{ label, onAction, onClose, duration = 10000, type = 'info' }`. Düğme eylemi
     * çalıştırır ve kutuyu kapatır; `onClose` kutu HER kapanışta bir kez çağrılır (eylemden sonra da). Kimliği döner
     * (`dismiss` ile erken kapatılır).
     */
    action:  (msg, { label, onAction, onClose, duration = 10000, type = 'info' } = {}) =>
      show(type === 'success' ? 'success' : 'info', msg, duration, null, { action: { label, onClick: onAction }, onClose }),
    dismiss: remove,
  }), [show, remove])

  const toastOptions = useMemo(() => ({
    closeButtonAriaLabel: t('toast.close'),
    className: 'cursor-pointer',
  }), [t])

  // Portal document.body'ye: sağlayıcının atası bir yığın bağlamı (transform/z-index) kursa bile
  // bildirim modal/dialog'ların (onlar da body'ye portal'lanır) üstünde kalsın.
  // `expand`: tavan kadar (4) kutu üst üste katlanmadan, hepsi okunur halde durur — eski liste gibi.
  return (
    <ToastCtx.Provider value={api}>
      {children}
      {createPortal(
        <div className="contents" onClick={dismissFromClick}>
          <Toaster
            id={toasterId}
            position="top-right"
            richColors
            expand
            closeButton
            visibleToasts={MAX_VISIBLE}
            containerAriaLabel={t('toast.region')}
            toastOptions={toastOptions}
          />
        </div>,
        document.body
      )}
    </ToastCtx.Provider>
  )
}

export function useToast() {
  const ctx = useContext(ToastCtx)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}
