import { createContext, useContext, useState, useCallback, useEffect, useMemo, useRef } from 'react'
import { createPortal } from 'react-dom'
import { Check, X, AlertCircle } from 'lucide-react'

const ToastCtx = createContext(null)

let _id = 0

/** Aynı anda ekranda durabilecek en fazla bildirim — üstü en ESKİsini düşürür. */
const MAX_VISIBLE = 4

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])
  /**
   * Açık otomatik-kapanma zamanlayıcıları (id → timeout).
   *
   * Neden gerekli: bunlar temizlenmediğinde, sağlayıcı unmount olduktan SONRA da çalışıp
   * `setToasts` çağırıyorlar. Tarayıcıda bu yalnız sessiz bir "unmounted component" güncellemesi;
   * ama jsdom kapatıldıktan sonra React'in `getCurrentEventPriority`'si `window`'a dokunduğu için
   * **yakalanmamış `ReferenceError: window is not defined`** fırlıyor ve vitest tüm koşuyu
   * 1 çıkış koduyla düşürüyor (2026-08-14'te CI'ı bu kırdı: 673 test geçti, koşu yine kırmızı).
   */
  const timers = useRef(new Map())

  /**
   * Görünen listenin EŞ ZAMANLI aynası. Karar burada verilir; {@code toasts} yalnız çizim içindir.
   *
   * <p><b>Neden ref, neden güncelleyicinin içi değil.</b> {@code setToasts(prev => …)} güncelleyicisi
   * React'te her zaman anında çalışmaz: aynı partideki İKİNCİ çağrıda render'a ertelenir. Bu yüzden
   * güncelleyicinin içinde id üretmek ya da "aynısı zaten var mı" diye bakmak o an yanlış cevap
   * verir — ve arıza tam da bu özelliğin çözmeye çalıştığı senaryoda çıkıyordu: sayfanın paralel
   * yükleyicileri aynı turda arka arkaya toast açtığında ikinci kutunun zamanlayıcısı BİRİNCİnin
   * id'sine yazılıyor, ikinci kutu hiç kapanmadan ekranda kalıyordu. (StrictMode güncelleyiciyi
   * bilerek iki kez çağırdığı için {@code _id} sayacı da orada kayıyordu.)
   */
  const visible = useRef([])

  const commit = useCallback((next) => { visible.current = next; setToasts(next) }, [])

  const clearTimer = useCallback((id) => {
    const t = timers.current.get(id)
    if (t) { clearTimeout(t); timers.current.delete(id) }
  }, [])

  const remove = useCallback((id) => {
    clearTimer(id)
    const next = visible.current.filter(x => x.id !== id)
    if (next.length !== visible.current.length) commit(next)
  }, [clearTimer, commit])

  /**
   * Bildirim gösterir.
   *
   * <p><b>Aynı mesaj TEKRARLAMAZ, sayaçlanır.</b> Eskiden her çağrı yeni bir kutu ekliyordu:
   * backend bir an cevap veremediğinde (deploy/restart/ağ) sayfanın paralel yükleyicileri ve
   * 30 sn'lik oto-yenilemesi aynı hatayı arka arkaya raporluyor, ekranın sağı 15–20 özdeş
   * "Sunucu hatası (HTTP 500)" kutusuyla kaplanıyor ve altındaki içerik görünmez oluyordu
   * (kullanıcı bildirimi 2026-09-01). Bilgi bir kez gösterilir; tekrar sayısı "×N" olarak
   * eklenir ve süre yeniden başlar.
   *
   * <p>Tavan da var: farklı mesajlar da olsa aynı anda {@code MAX_VISIBLE} kutudan fazlası
   * ekranı kaplar — en eskisi düşer (zamanlayıcısıyla birlikte).
   */
  const show = useCallback((type, message, duration = 3500) => {
    const cur = visible.current
    const same = cur.find(x => x.type === type && x.message === message)
    let id
    let next
    if (same) {
      id = same.id
      next = cur.map(x => (x.id === id ? { ...x, count: (x.count || 1) + 1 } : x))
    } else {
      id = ++_id
      next = [...cur, { id, type, message, count: 1 }]
      // Düşen kutunun zamanlayıcısı da ölmeli: kalsaydı ekranda olmayan bir kutu için işleyip
      // haritayı şişirirdi (unmount temizliğinin kapsamı da gereksiz yere büyürdü).
      while (next.length > MAX_VISIBLE) clearTimer(next.shift().id)
    }
    commit(next)
    clearTimer(id)                          // tekrar geldi → süre baştan
    if (duration > 0) timers.current.set(id, setTimeout(() => remove(id), duration))
    return id
  }, [clearTimer, commit, remove])

  // Unmount: bekleyen her zamanlayıcı iptal edilir — sağlayıcı gittikten sonra hiçbir
  // güncelleme tetiklenmemeli.
  useEffect(() => {
    const pending = timers.current
    return () => { for (const t of pending.values()) clearTimeout(t); pending.clear() }
  }, [])

  /**
   * Kimliği SABİT tutulur: tüketiciler bu nesneyi bağımlılık dizisine koyuyor
   * (CertInventoryReportSettings, RetentionSettings, SqlPlayground). Her render'da yeniden
   * kurulsaydı, her bildirim o sayfalarda yeniden yükleme tetiklerdi.
   */
  const api = useMemo(() => ({
    success: (msg, d) => show('success', msg, d),
    error:   (msg, d) => show('error',   msg, d ?? 5000),
    info:    (msg, d) => show('info',    msg, d),
    dismiss: remove,
  }), [show, remove])

  return (
    <ToastCtx.Provider value={api}>
      {children}
      {createPortal(
        <div className="toast-container">
          {toasts.map(t => (
            <div key={t.id} className={`toast toast-${t.type}`} role="status"
                 onClick={() => remove(t.id)}>
              <span className="toast-icon">
                {t.type === 'success' && <Check size={16} strokeWidth={3} />}
                {t.type !== 'success' && <AlertCircle size={16} />}
              </span>
              <span className="toast-msg">{t.message}</span>
              {t.count > 1 && <span className="toast-count">×{t.count}</span>}
              <button className="toast-close" onClick={(e) => { e.stopPropagation(); remove(t.id) }}>
                <X size={14} />
              </button>
            </div>
          ))}
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
