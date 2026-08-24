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

/**
 * Gerekçe notu kuralı — backend {@code AlertActionNote} ile AYNI eşikler.
 *
 * <p>Buradaki amaç anında geri bildirim; garanti sunucudadır. İki taraf ayrışırsa kullanıcı
 * arayüzde geçen bir notla 400 yer, o yüzden sayılar bilerek yan yana yazılı.
 */
export const NOTE_RULE = { minWords: 3, minWordLen: 2, minChars: 10 }

export function isNoteValid(note) {
  const t = (note ?? '').trim()
  if (t.length < NOTE_RULE.minChars) return false
  return t.split(/\s+/).filter(w => w.length >= NOTE_RULE.minWordLen).length >= NOTE_RULE.minWords
}

/** Odak tuzagi icin: diyalog icindeki odaklanabilir ogeler (gizli/pasif olanlar HARIC). */
const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

function DialogModal({ dialog, onConfirm, onCancel }) {
  const [inputVal, setInputVal] = useState(dialog.defaultValue ?? '')
  const inputRef  = useRef(null)
  const confirmRef = useRef(null)
  const boxRef = useRef(null)
  // Diyalog acilmadan ONCEKI odak — kapaninca oraya geri verilir.
  const returnFocusRef = useRef(null)
  // Baslik/mesaj id'leri: aria-labelledby/describedby bunlara bagli. useId yerine sabit
  // id yeterli cunku ayni anda TEK diyalog acik olur (provider tek `dialog` state tutar).
  const titleId = 'dlg-title'
  const messageId = 'dlg-message'
  const v = VARIANTS[dialog.variant] ?? VARIANTS.info
  const isNote = dialog.type === 'note'
  const noteOk = !isNote || isNoteValid(inputVal)

  useEffect(() => {
    // Odagi GERI VERMEK icin nereden geldigimizi sakla. Bu olmadan, tablodaki bir satiri
    // silmeyi onaylayan klavye kullanicisi odagi body'de bulur ve listedeki yerini kaybeder.
    returnFocusRef.current = document.activeElement
    if (dialog.type === 'prompt' || dialog.type === 'note') {
      inputRef.current?.focus()
      inputRef.current?.select()
    } else {
      confirmRef.current?.focus()
    }
    return () => {
      const back = returnFocusRef.current
      // Tetikleyici bu arada DOM'dan kalkmis olabilir (ornegin silinen satirin dugmesi);
      // o zaman geri verme atlanir, hata firlatilmaz.
      if (back && typeof back.focus === 'function' && document.contains(back)) back.focus()
    }
  }, [dialog.type])

  function handleOverlayClick(e) {
    if (e.target === e.currentTarget) onCancel()
  }

  function handleKey(e) {
    if (e.key === 'Escape') { onCancel(); return }
    // ODAK TUZAGI: Tab diyalogdan CIKMAMALI. Aksi halde klavye/ekran-okuyucu kullanicisi
    // ortuk sayfadaki dugmelere ulasir ve modalin arkasindaki iceriklerle etkilesebilir.
    if (e.key === 'Tab') {
      const items = boxRef.current ? [...boxRef.current.querySelectorAll(FOCUSABLE)] : []
      if (items.length === 0) return
      const first = items[0]
      const last = items[items.length - 1]
      const active = document.activeElement
      if (e.shiftKey && (active === first || !boxRef.current.contains(active))) {
        e.preventDefault(); last.focus()
      } else if (!e.shiftKey && (active === last || !boxRef.current.contains(active))) {
        e.preventDefault(); first.focus()
      }
      return
    }
    // Not modalinde Enter ONAYLAMAZ: metin çok satırlı ve Enter yeni satır demek. Ayrıca
    // kural sağlanmadan Enter'la geçilmesi zorunluluğu delerdi.
    if (e.key === 'Enter' && dialog.type !== 'prompt' && dialog.type !== 'note') onConfirm(true)
  }

  return createPortal(
    <div className="dlg-overlay" onClick={handleOverlayClick} onKeyDown={handleKey} tabIndex={-1}>
      {/* role/aria-modal: ekran okuyucu bunu DIYALOG olarak duyurur ve sanal imleci iceriye
          hapseder. aria-labelledby/describedby olmadan role tek basina yetmez — diyalogun
          erisilebilir ADI ve ACIKLAMASI bu iki id'den gelir. */}
      <div
        ref={boxRef}
        className="dlg-box"
        role={dialog.type === 'alert' ? 'alertdialog' : 'dialog'}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={dialog.message ? messageId : undefined}
        style={{ '--dlg-accent': v.color, '--dlg-bg': v.bg, '--dlg-border': v.border }}
      >

        {/* Ikon DEKORATIF: anlami zaten baslikta yazili, SR'a iki kez okutmanin faydasi yok. */}
        <div className="dlg-icon-ring" aria-hidden="true">
          <v.Icon size={30} color={v.color} />
        </div>

        <h3 className="dlg-title" id={titleId}>{dialog.title}</h3>

        {dialog.message && (
          <p className="dlg-message" id={messageId}>{dialog.message}</p>
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

        {isNote && (
          <div className="dlg-note">
            {/* Hazır gerekçeler: metni DOLDURUR, kilitlemez — kullanıcı üzerine yazabilir.
                Çip seçmek tek başına yetmez; kural yine geçerli (çipler kuralı sağlayacak
                uzunlukta yazıldı ama kullanıcı silip kısaltırsa düğme yine pasifleşir). */}
            {dialog.chips?.length > 0 && (
              <div className="dlg-note-chips">
                {dialog.chips.map(c => (
                  <button key={c} type="button" className="dlg-note-chip"
                    onClick={() => { setInputVal(c); inputRef.current?.focus() }}>
                    {c}
                  </button>
                ))}
              </div>
            )}
            <textarea
              ref={inputRef}
              className="dlg-note-input"
              rows={3}
              value={inputVal}
              onChange={e => setInputVal(e.target.value)}
              placeholder={dialog.placeholder ?? ''}
              aria-label={dialog.noteLabel ?? 'Gerekçe'}
              aria-invalid={!noteOk}
              aria-describedby="dlg-note-hint"
            />
            {/* Neyin eksik olduğu YAZILI — düğmeyi pasif bırakıp sebebini söylememek,
                kullanıcıya "bozuk" hissi verir. */}
            <div id="dlg-note-hint" className={`dlg-note-hint${noteOk ? ' is-ok' : ''}`}>
              {noteOk ? (dialog.noteOkText ?? '') : (dialog.noteHint ?? '')}
            </div>
          </div>
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
            disabled={!noteOk}
            onClick={() => {
              if (dialog.type === 'prompt') return onConfirm(inputVal || null)
              if (isNote) return onConfirm({ confirmed: true, note: inputVal.trim() })
              onConfirm(true)
            }}
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

  /**
   * Zorunlu gerekçe notlu onay — {@code { confirmed, note }} ya da iptalde {@code { confirmed:false }}.
   *
   * <p>Neden AYRI bir metot: {@code showConfirm}'ün onlarca çağrısı var ve hepsi {@code true/false}
   * bekliyor. Dönüş şeklini oraya eklemek her birini riske atardı; yeni yol hiçbirine dokunmuyor.
   */
  const showNoteConfirm = useCallback((opts) =>
    _show({ type: 'note', variant: 'warning', confirmText: 'Onayla', cancelText: 'İptal', ...opts }),
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
    if (type === 'prompt') return resolve(null)
    // Not modali her zaman NESNE döner — çağıran `res.confirmed` okuyor; burada `false`
    // dönseydi iptalde `res.confirmed` okuması patlardı.
    if (type === 'note') return resolve({ confirmed: false, note: '' })
    resolve(false)
  }

  return (
    <DialogCtx.Provider value={{ showConfirm, showPrompt, showAlert, showNoteConfirm }}>
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
