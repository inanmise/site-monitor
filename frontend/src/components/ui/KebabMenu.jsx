import { useState, useRef, useEffect, useLayoutEffect } from 'react'
import { createPortal } from 'react-dom'
import { Menu } from 'lucide-react'

/**
 * Tablo "İşlem" sütunu / kart başlığı için 3-çizgili (hamburger) menü. Butonları tek bir
 * açılır menüde toplar. items: [{ label, icon?, onClick, danger?, hidden? }].
 *
 * <h3>Neden PORTAL + fixed</h3>
 * Menü `document.body`'ye portal'lanır. `position: fixed` bir kutuyu viewport'a göre
 * konumlandırır — AMA atalardan birinde `transform`/`filter`/`backdrop-filter` varsa o ata
 * yeni bir konumlandırma bağlamı kurar ve fixed artık VIEWPORT'a değil O ATAYA göre çözülür.
 * Şablon kartlarında (`.sc-tpl-card:hover { transform: translateY(-1px) }`) tam bu oldu:
 * menü kartın hover'ına göre kayıyor, fare kartı terk edince (transform kalkınca) yerine
 * zıplıyordu — "bazen açılmıyor, bazen başka yerde açılıyor". Portal atayı devreden çıkarır;
 * yeni bir hover animasyonu eklendiğinde de sorun geri gelmez.
 *
 * Konum önce buton dikdörtgeninden tahmin edilir, sonra menü ÖLÇÜLÜP viewport'a sığacak
 * şekilde düzeltilir (altta yer yoksa butonun üstüne çevrilir, kenarlara taşarsa yaslanır).
 * Ölçüm turunda menü `visibility:hidden` durur — kullanıcı yanlış konumda bir kare görmez.
 *
 * <h3>placement</h3>
 * `'bottom'` (varsayılan): butonun ALTINA, sağ kenarları hizalı — tablo "İşlem" sütununun
 * alışılmış davranışı. `'right'`: butonun SAĞINA, üst kenarları hizalı. İkincisi kart
 * ızgaralarında gerekiyor: aşağı açılan menü kartın kendi içeriğini örtüyor ve kullanıcı
 * hangi kaydın menüsünü açtığını göremiyordu. Sağda yer yoksa sola, o da yoksa viewport'a
 * yaslanır — yani dar ekranda eski davranışa güvenli biçimde düşer.
 *
 * Mevcut .wr-menu-wrap / .wr-menu-pop class'ları yeniden kullanılır (yeni CSS yok).
 * Görünür item yoksa hiçbir şey render etmez (yetkisiz kullanıcıda boş hücre).
 */
const GAP = 4    // buton ile menü arası
const EDGE = 8   // viewport kenar payı

export default function KebabMenu({ items = [], label = 'İşlemler', placement = 'bottom' }) {
  const [open, setOpen] = useState(false)
  const [pos, setPos] = useState(null)   // null = henüz ölçülmedi
  const btnRef = useRef(null)
  const popRef = useRef(null)

  useEffect(() => {
    if (!open) return
    const close = () => setOpen(false)
    /**
     * Dışarı tıklama. Kapsam kontrolü SINIF adına değil BU örneğin ref'lerine bakar:
     * ".kebab-trigger" yazan eski sürüm, BAŞKA bir kartın tetiğine tıklandığında da
     * "benim tetiğim" sanıp açık kalıyordu — ekranda aynı anda iki menü olurdu.
     */
    const onDown = (e) => {
      if (btnRef.current?.contains(e.target) || popRef.current?.contains(e.target)) return
      setOpen(false)
    }
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false) }
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  // Ölçüm + viewport düzeltmesi (boyama ÖNCESİ çalışsın diye layout effect).
  useLayoutEffect(() => {
    if (!open || !btnRef.current || !popRef.current) return
    const b = btnRef.current.getBoundingClientRect()
    const m = popRef.current.getBoundingClientRect()
    const vw = window.innerWidth || 0
    const vh = window.innerHeight || 0

    let top;
    let left;
    if (placement === 'right') {
      // Butonun SAĞINA aç: kartın içeriğini örtmesin. Sağda yer yoksa soluna geç, o da
      // sığmıyorsa viewport'a yasla (dar ekranda kaçınılmaz örtüşme, ama menü hep görünür).
      left = b.right + GAP
      if (m.width && left + m.width > vw - EDGE) {
        const leftSide = b.left - GAP - m.width
        left = leftSide >= EDGE ? leftSide : Math.max(EDGE, vw - EDGE - m.width)
      }
      // Dikeyde butonun ÜST kenarıyla hizalı; alta taşarsa yukarı çekilir.
      top = b.top
      if (m.height && top + m.height > vh - EDGE) top = Math.max(EDGE, vh - EDGE - m.height)
    } else {
      top = b.bottom + GAP
      if (m.height && top + m.height > vh - EDGE) {
        const above = b.top - GAP - m.height
        top = above >= EDGE ? above : Math.max(EDGE, vh - EDGE - m.height)
      }
      // Sağ kenarı butonla hizala (menü sola doğru açılır), sonra viewport'a sıkıştır.
      left = b.right - m.width
      left = Math.min(Math.max(EDGE, left), Math.max(EDGE, vw - EDGE - m.width))
    }

    setPos((p) => (p && Math.abs(p.top - top) < 0.5 && Math.abs(p.left - left) < 0.5
      ? p
      : { top, left }))
  }, [open, placement])

  const visible = (items || []).filter((it) => it && !it.hidden)
  if (visible.length === 0) return null

  function toggle() {
    if (open) { setOpen(false); return }
    setPos(null)      // her açılışta yeniden ölç (sayfa kaymış/boyut değişmiş olabilir)
    setOpen(true)
  }

  const pop = (
    <div ref={popRef} className="wr-menu-pop"
      style={{
        position: 'fixed', top: pos ? pos.top : 0, left: pos ? pos.left : 0,
        right: 'auto', zIndex: 900, visibility: pos ? 'visible' : 'hidden',
      }}>
      {visible.map((it, i) => (
        <button key={i} type="button" className={it.danger ? 'danger' : ''}
          onClick={() => { setOpen(false); it.onClick() }}>
          {it.icon}{it.label}
        </button>
      ))}
    </div>
  )

  return (
    <div className="wr-menu-wrap">
      <button ref={btnRef} type="button" className="btn-sm kebab-trigger"
        title={label} aria-label={label} aria-expanded={open} aria-haspopup="menu"
        style={{ background: '#eef2f7', color: '#334155' }} onClick={toggle}>
        <Menu size={15} />
      </button>
      {open && createPortal(pop, document.body)}
    </div>
  )
}
