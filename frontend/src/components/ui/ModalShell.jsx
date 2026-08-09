import { createContext, useContext, useEffect, useId, useRef } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'

/**
 * Modal KABUĞU — davranış, içerik değil. Yeni modallar buradan kurulur.
 *
 * Neden var: repodaki modallar `.modal-overlay` + `<div role="dialog">` elle kuruyor ve her
 * biri farklı bir alt küme uyguluyor — kimi Escape'i dinliyor, kimi dinlemiyor; hiçbirinde
 * odak tuzağı, odak iadesi ya da arka plan scroll kilidi yok. IssueReportModal'da bunların
 * tamamı eksikti (çökme sonrası açılan bir modaldan Tab ile arkadaki ölü ekrana düşülüyordu).
 *
 * Sağladıkları:
 *   • createPortal(document.body) — ağaçtaki yerine bağlı z-index/overflow sürprizi olmaz
 *   • role="dialog" + aria-modal + aria-labelledby (başlık otomatik bağlanır)
 *   • Escape — İÇ İÇE modallarda yalnız EN DERİNDEKİ kapanır
 *   • Odak — açılışta içeri, kapanışta tetikleyiciye geri; Tab/Shift+Tab döngüsü kabukta kalır
 *   • Arka plan scroll kilidi — sayaçlı (iç içe modalda erken açılmaz)
 *   • Katman — derinliğe göre --modal-z, lightbox parent modalın üstünde kalır
 *
 * `busy` iken kapatma yolları (Escape, scrim tıklaması, X) devre dışıdır — gönderim sürerken
 * yanlışlıkla kapatmayı önler.
 *
 * Kapsam notu: mevcut ~12 modal bilinçli olarak taşınmadı; her biri kendi iç düzenini
 * `.modal-box`/`.modal-content` üstüne kurmuş ve toplu geçiş ayrı bir iş.
 */

/**
 * İç içe derinlik REACT AĞACINDAN okunur, effect sırasından değil. React effect'leri
 * çocuktan ebeveyne çalıştırır: aynı anda mount edilen iki iç içe kabukta içteki ÖNCE
 * kaydolur, dolayısıyla "en son kaydolan en üsttedir" varsayımı ters sonuç verirdi.
 */
const DepthCtx = createContext(0)

// Açık kabuklar — Escape'i en derindeki alır (eşitlikte en son kaydolan).
const openShells = []

// Scroll kilidi sayacı: iç içe modalda dıştaki hâlâ açıkken kilit açılmamalı.
let scrollLocks = 0
let savedOverflow = ''

// [hidden] hariç tutuluyor: IssueReportModal'ın gizli <input type="file"> alanı seçiciye
// uyar ama odaklanamaz; tuzağın döngüsünü sessizce kırardı. Görünürlüğü offsetParent ile
// ölçmek DOĞRU DEĞİL — jsdom'da daima null, tarayıcıda ise position:fixed atalarda null.
const FOCUSABLE = [
  'a[href]', 'button', 'input', 'select', 'textarea', '[tabindex]',
].map((s) => `${s}:not([disabled]):not([hidden]):not([tabindex="-1"]):not([type="hidden"])`).join(',')

export default function ModalShell({
  open, onClose, title, icon: Icon, size = 'md', busy = false,
  closeLabel, footer, children, className = '',
}) {
  const parentDepth = useContext(DepthCtx)
  const boxRef = useRef(null)
  const selfRef = useRef({})
  const titleId = useId()

  selfRef.current.depth = parentDepth

  // Kayıt defteri + Escape. `busy`/`onClose` değişince yalnız dinleyici yenilenir.
  useEffect(() => {
    if (!open) return
    const self = selfRef.current
    openShells.push(self)
    return () => {
      const i = openShells.indexOf(self)
      if (i !== -1) openShells.splice(i, 1)
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    const self = selfRef.current

    function onKeyDown(e) {
      if (e.key !== 'Escape') return
      // En derindeki kabuk; eşit derinlikte en son kaydolan (>=).
      const top = openShells.reduce((a, b) => (b.depth >= a.depth ? b : a), openShells[0])
      if (top !== self) return
      // Dialog (showConfirm/showPrompt) kendi overlay'inde Escape'i zaten işliyor; o açıkken
      // buradan da kapatırsak tek tuşla iki katman birden kapanır. Sınıf adına bağlı olması
      // kırılgan — ortak bir katman yöneticisi bu işin kapsamı dışında bırakıldı.
      if (document.querySelector('.dlg-overlay')) return
      if (busy) return
      e.stopPropagation()
      onClose?.()
    }

    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, busy, onClose])

  // Odak: açılışta içeri al, kapanışta tetikleyiciye iade et.
  useEffect(() => {
    if (!open) return
    const previous = document.activeElement
    const box = boxRef.current
    const first = box?.querySelector(FOCUSABLE)
    ;(first ?? box)?.focus?.()
    return () => {
      if (previous && typeof previous.focus === 'function' && document.contains(previous)) {
        previous.focus()
      }
    }
  }, [open])

  // Arka plan scroll kilidi (sayaçlı).
  useEffect(() => {
    if (!open) return
    if (scrollLocks === 0) {
      savedOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
    }
    scrollLocks += 1
    return () => {
      scrollLocks -= 1
      if (scrollLocks === 0) document.body.style.overflow = savedOverflow
    }
  }, [open])

  if (!open) return null

  function onTrapKey(e) {
    if (e.key !== 'Tab') return
    const items = [...(boxRef.current?.querySelectorAll(FOCUSABLE) ?? [])]
    if (items.length === 0) { e.preventDefault(); return }
    const firstEl = items[0]
    const lastEl = items[items.length - 1]
    if (e.shiftKey && document.activeElement === firstEl) { e.preventDefault(); lastEl.focus() }
    else if (!e.shiftKey && document.activeElement === lastEl) { e.preventDefault(); firstEl.focus() }
  }

  return createPortal(
    <DepthCtx.Provider value={parentDepth + 1}>
      <div
        className="modal-overlay modal-shell-overlay"
        style={{ '--modal-z': 2000 + parentDepth * 10 }}
        onClick={(e) => { if (e.target === e.currentTarget && !busy) onClose?.() }}
      >
        <div
          ref={boxRef}
          className={['modal-box', `modal-shell--${size}`, className].filter(Boolean).join(' ')}
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          tabIndex={-1}
          onKeyDown={onTrapKey}
        >
          <div className="modal-shell-hdr">
            <h3 id={titleId} className="modal-shell-title">
              {Icon && <Icon size={18} aria-hidden="true" />}
              {title}
            </h3>
            <button type="button" className="modal-shell-close" onClick={() => onClose?.()}
              aria-label={closeLabel} disabled={busy}>
              <X size={18} />
            </button>
          </div>

          <div className="modal-shell-body">{children}</div>

          {footer && <div className="modal-shell-footer">{footer}</div>}
        </div>
      </div>
    </DepthCtx.Provider>,
    document.body
  )
}
