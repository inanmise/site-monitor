import { createContext, useContext, useState, useCallback, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { Check, X, AlertCircle } from 'lucide-react'

const ToastCtx = createContext(null)

let _id = 0

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

  const remove = useCallback((id) => {
    const t = timers.current.get(id)
    if (t) { clearTimeout(t); timers.current.delete(id) }
    setToasts(prev => prev.filter(x => x.id !== id))
  }, [])

  const show = useCallback((type, message, duration = 3500) => {
    const id = ++_id
    setToasts(prev => [...prev, { id, type, message }])
    if (duration > 0) timers.current.set(id, setTimeout(() => remove(id), duration))
    return id
  }, [remove])

  // Unmount: bekleyen her zamanlayıcı iptal edilir — sağlayıcı gittikten sonra hiçbir
  // güncelleme tetiklenmemeli.
  useEffect(() => {
    const pending = timers.current
    return () => { for (const t of pending.values()) clearTimeout(t); pending.clear() }
  }, [])

  const api = {
    success: (msg, d) => show('success', msg, d),
    error:   (msg, d) => show('error',   msg, d ?? 5000),
    info:    (msg, d) => show('info',    msg, d),
    dismiss: remove,
  }

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
