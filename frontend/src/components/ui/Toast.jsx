import { createContext, useContext, useCallback, useEffect, useId, useMemo, useRef } from 'react'
import { createPortal } from 'react-dom'
import { toast as sonner } from 'sonner'
import { Toaster } from '@/components/shadcn/sonner'
import { Badge } from '@/components/shadcn/badge'
import { useT } from '@/i18n/index.jsx'

const ToastCtx = createContext(null)

let _seq = 0

/** Aynı anda ekranda durabilecek en fazla bildirim — üstü en ESKİsini düşürür. */
const MAX_VISIBLE = 4

/**
 * Sonner'a verilen başlık: mesaj + tekrar sayacı ("×N").
 *
 * <p>{@code data-toast-id} kutuyu bizim kimliğimize bağlar: Sonner'ın `<li>`'si kimlik taşımıyor,
 * gövdeye tıklayınca kapatma (aşağıda) hangi kaydı düşüreceğini buradan okur.
 */
function ToastBody({ id, message, count }) {
  return (
    <span data-toast-id={id} className="flex items-center gap-2">
      <span className="min-w-0">{message}</span>
      {count > 1 && (
        <Badge variant="outline" className="border-current/30 text-current tabular-nums">×{count}</Badge>
      )}
    </span>
  )
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

  const clearTimer = useCallback((id) => {
    const tm = timers.current.get(id)
    if (tm) { clearTimeout(tm); timers.current.delete(id) }
  }, [])

  /** Bizim kapattığımız bildirim: defterden düşer, Sonner'a kapanış animasyonu söylenir. */
  const remove = useCallback((id) => {
    clearTimer(id)
    const cur = visible.current
    if (!cur.some(x => x.id === id)) return
    visible.current = cur.filter(x => x.id !== id)
    sonner.dismiss(id)
  }, [clearTimer])

  /**
   * Sonner'ın KENDİ kapattığı bildirim (X düğmesi, kaydırma) — ve bizim {@link remove}'umuzun
   * ardından da çağrılır (idempotent). Yalnız defteri senkronlar: Sonner kutuyu zaten kaldırıyor.
   */
  const forget = useCallback((id) => {
    clearTimer(id)
    visible.current = visible.current.filter(x => x.id !== id)
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
  const show = useCallback((type, message, duration = 3500) => {
    const cur = visible.current
    const same = cur.find(x => x.type === type && x.message === message)
    let entry
    if (same) {
      entry = { ...same, count: same.count + 1 }
      visible.current = cur.map(x => (x.id === same.id ? entry : x))
    } else {
      entry = { id: `sm-toast-${++_seq}`, type, message, count: 1 }
      const next = [...cur, entry]
      // Düşen kutunun zamanlayıcısı da ölmeli: kalsaydı ekranda olmayan bir kutu için işleyip
      // haritayı şişirirdi (unmount temizliğinin kapsamı da gereksiz yere büyürdü).
      while (next.length > MAX_VISIBLE) {
        const dropped = next.shift()
        clearTimer(dropped.id)
        sonner.dismiss(dropped.id)
      }
      visible.current = next
    }
    sonner[type](<ToastBody id={entry.id} message={message} count={entry.count} />, {
      id: entry.id,
      toasterId,
      duration: Infinity,
      onDismiss: (st) => forget(st.id),
    })
    clearTimer(entry.id)                    // tekrar geldi → süre baştan
    if (duration > 0) timers.current.set(entry.id, setTimeout(() => remove(entry.id), duration))
    return entry.id
  }, [clearTimer, forget, remove, toasterId])

  // Unmount: bekleyen her zamanlayıcı iptal edilir — sağlayıcı gittikten sonra hiçbir
  // güncelleme tetiklenmemeli. Görünen bildirimler Sonner'ın global deposunda da kapatılır:
  // kalsalardı sonra abone olan bir Toaster (yeniden mount, sıradaki test) onları geri çizerdi.
  useEffect(() => {
    const pending = timers.current
    const ledger = visible
    return () => {
      for (const tm of pending.values()) clearTimeout(tm)
      pending.clear()
      for (const x of ledger.current) sonner.dismiss(x.id)
      ledger.current = []
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
    if (!el || el.closest('[data-close-button]')) return
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
    error:   (msg, d) => show('error',   msg, d ?? 5000),
    info:    (msg, d) => show('info',    msg, d),
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
