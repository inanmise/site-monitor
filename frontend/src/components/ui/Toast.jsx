import { createContext, useContext, useState, useCallback } from 'react'
import { createPortal } from 'react-dom'
import { Check, X, AlertCircle } from 'lucide-react'

const ToastCtx = createContext(null)

let _id = 0

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])

  const remove = useCallback((id) => {
    setToasts(prev => prev.filter(t => t.id !== id))
  }, [])

  const show = useCallback((type, message, duration = 3500) => {
    const id = ++_id
    setToasts(prev => [...prev, { id, type, message }])
    if (duration > 0) setTimeout(() => remove(id), duration)
    return id
  }, [remove])

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
