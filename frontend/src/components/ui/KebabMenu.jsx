import { useState, useRef, useEffect } from 'react'
import { Menu } from 'lucide-react'

/**
 * Tablo "İşlem" sütunu için 3-çizgili (hamburger) menü. Butonları tek bir açılır
 * menüde toplar. items: [{ label, icon?, onClick, danger?, hidden? }].
 *
 * Konum FIXED hesaplanır → tablo `overflow:auto` ile kırpılmaz (WeeklyReports deseni).
 * Mevcut .wr-menu-wrap / .wr-menu-pop class'ları yeniden kullanılır (yeni CSS yok).
 * Görünür item yoksa hiçbir şey render etmez (yetkisiz kullanıcıda boş hücre).
 */
export default function KebabMenu({ items = [], label = 'İşlemler' }) {
  const [open, setOpen] = useState(false)
  const [pos, setPos] = useState({ top: 0, left: 0 })
  const btnRef = useRef(null)

  useEffect(() => {
    if (!open) return
    const close = () => setOpen(false)
    const onDown = (e) => {
      if (!e.target.closest('.wr-menu-pop') && !e.target.closest('.kebab-trigger')) setOpen(false)
    }
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    document.addEventListener('mousedown', onDown)
    return () => {
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
      document.removeEventListener('mousedown', onDown)
    }
  }, [open])

  const visible = (items || []).filter((it) => it && !it.hidden)
  if (visible.length === 0) return null

  function toggle(e) {
    if (open) { setOpen(false); return }
    const r = e.currentTarget.getBoundingClientRect()
    setPos({ top: r.bottom + 4, left: Math.max(8, r.right - 180) })
    setOpen(true)
  }

  return (
    <div className="wr-menu-wrap">
      <button ref={btnRef} type="button" className="btn-sm kebab-trigger"
        title={label} aria-label={label}
        style={{ background: '#eef2f7', color: '#334155' }} onClick={toggle}>
        <Menu size={15} />
      </button>
      {open && (
        <div className="wr-menu-pop" style={{ position: 'fixed', top: pos.top, left: pos.left, right: 'auto' }}>
          {visible.map((it, i) => (
            <button key={i} type="button" className={it.danger ? 'danger' : ''}
              onClick={() => { setOpen(false); it.onClick() }}>
              {it.icon}{it.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
