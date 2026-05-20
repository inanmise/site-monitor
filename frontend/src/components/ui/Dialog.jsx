import { createContext, useContext, useState, useCallback, useRef, useEffect } from 'react'
import { createPortal } from 'react-dom'
import { Trash2, AlertTriangle, Info, CheckCircle, XCircle, LogOut, Pencil } from 'lucide-react'

const DialogCtx = createContext(null)

const VARIANTS = {
  danger:  { Icon: Trash2,        color: '#c0392b', bg: '#fdf2f2', border: '#f5c6cb' },
  warning: { Icon: AlertTriangle, color: '#d97706', bg: '#fffbeb', border: '#fcd34d' },
  info:    { Icon: Info,          color: '#1d6fbf', bg: '#eff6ff', border: '#bfdbfe' },
  success: { Icon: CheckCircle,   color: '#15803d', bg: '#f0fdf4', border: '#bbf7d0' },
  error:   { Icon: XCircle,       color: '#b91c1c', bg: '#fef2f2', border: '#fecaca' },
  logout:  { Icon: LogOut,        color: '#dc2626', bg: '#fef2f2', border: '#fecaca' },
  prompt:  { Icon: Pencil,        color: '#1d6fbf', bg: '#eff6ff', border: '#bfdbfe' },
}

function DialogModal({ dialog, onConfirm, onCancel }) {
  const [inputVal, setInputVal] = useState(dialog.defaultValue ?? '')
  const inputRef  = useRef(null)
  const confirmRef = useRef(null)
  const v = VARIANTS[dialog.variant] ?? VARIANTS.info

  useEffect(() => {
    if (dialog.type === 'prompt') {
      inputRef.current?.focus()
      inputRef.current?.select()
    } else {
      confirmRef.current?.focus()
    }
  }, [dialog.type])

  function handleOverlayClick(e) {
    if (e.target === e.currentTarget) onCancel()
  }

  function handleKey(e) {
    if (e.key === 'Escape') { onCancel(); return }
    if (e.key === 'Enter' && dialog.type !== 'prompt') onConfirm(true)
  }

  return createPortal(
    <div className="dlg-overlay" onClick={handleOverlayClick} onKeyDown={handleKey} tabIndex={-1}>
      <div className="dlg-box" style={{ '--dlg-accent': v.color, '--dlg-bg': v.bg, '--dlg-border': v.border }}>

        <div className="dlg-icon-ring">
          <v.Icon size={30} color={v.color} />
        </div>

        <h3 className="dlg-title">{dialog.title}</h3>

        {dialog.message && (
          <p className="dlg-message">{dialog.message}</p>
        )}

        {dialog.type === 'prompt' && (
          <input
            ref={inputRef}
            className="dlg-input"
            value={inputVal}
            onChange={e => setInputVal(e.target.value)}
            placeholder={dialog.placeholder ?? ''}
            onKeyDown={e => e.key === 'Enter' && onConfirm(inputVal || null)}
          />
        )}

        <div className="dlg-actions">
          {dialog.type !== 'alert' && (
            <button className="dlg-btn dlg-btn-cancel" onClick={onCancel}>
              {dialog.cancelText ?? 'İptal'}
            </button>
          )}
          <button
            ref={confirmRef}
            className="dlg-btn dlg-btn-confirm"
            onClick={() => dialog.type === 'prompt' ? onConfirm(inputVal || null) : onConfirm(true)}
          >
            {dialog.confirmText ?? 'Tamam'}
          </button>
        </div>

      </div>
    </div>,
    document.body
  )
}

export function DialogProvider({ children }) {
  const [dialog, setDialog] = useState(null)

  const _show = useCallback((config) =>
    new Promise(resolve => setDialog({ ...config, resolve })),
  [])

  const showConfirm = useCallback((opts) =>
    _show({ type: 'confirm', variant: 'danger', confirmText: 'Sil', cancelText: 'İptal', ...opts }),
  [_show])

  const showPrompt = useCallback((opts) =>
    _show({ type: 'prompt', variant: 'prompt', confirmText: 'Tamam', cancelText: 'İptal', ...opts }),
  [_show])

  const showAlert = useCallback((opts) =>
    _show({ type: 'alert', variant: 'info', confirmText: 'Tamam', ...opts }),
  [_show])

  function handleConfirm(value) {
    const resolve = dialog.resolve
    setDialog(null)
    resolve(value)
  }

  function handleCancel() {
    const resolve = dialog.resolve
    const type    = dialog.type
    setDialog(null)
    resolve(type === 'prompt' ? null : false)
  }

  return (
    <DialogCtx.Provider value={{ showConfirm, showPrompt, showAlert }}>
      {children}
      {dialog && (
        <DialogModal dialog={dialog} onConfirm={handleConfirm} onCancel={handleCancel} />
      )}
    </DialogCtx.Provider>
  )
}

export function useDialog() {
  const ctx = useContext(DialogCtx)
  if (!ctx) throw new Error('useDialog must be called inside <DialogProvider>')
  return ctx
}
