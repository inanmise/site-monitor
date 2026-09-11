import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { CircleHelp } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'

/**
 * Alan-bazlı açıklama baloncuğu — Ayarlar ekranlarındaki HER yapılandırma değerinin
 * "ne işe yarar / faydası / önerilen değer" metnini SAYFAYA YAZMADAN taşır.
 *
 * Neden böyle:
 *  • Metin sayfada görünür dursaydı 200'den fazla ayar taşıyan Genel Ayarlar okunamaz
 *    bir duvara dönerdi; ipucu isteğe bağlı olmalı (tıklayınca açılır).
 *  • Baloncuk `document.body`'ye PORTAL ile çizilir ve konum tetikleyicinin
 *    `getBoundingClientRect()` değerinden hesaplanır: `.threshold-field`, `.up-group-card`
 *    ya da `.admin-table-wrap` gibi `overflow` taşıyan ataların hiçbiri onu kırpamaz.
 *  • Metin `white-space: pre-line` ile çizilir; sözlükteki `\n` gerçek satır olur.
 *
 * ÇEVİRİ YOKSA HİÇBİR ŞEY ÇİZİLMEZ: `useT` eksik anahtarda anahtarın KENDİSİNİ döndürür
 * (i18n/index.jsx `dict[key] ?? key`), yani kontrolsüz bir ikon kullanıcıya
 * "help.set.site.monitor.x" ham anahtarını gösterirdi. Tetikleyici de çizilmez —
 * boş baloncuk açan bir ikon, yardımın var olmadığından daha kötüdür.
 */
export default function HelpTip({ helpKey, label }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [pos, setPos] = useState({ top: 0, left: 0 })
  const btnRef = useRef(null)
  const popRef = useRef(null)
  const popId = useId()

  const POP_W = 300

  /** Konumu tetikleyicinin viewport kutusundan kur (popover `position: fixed`). */
  const place = useCallback(() => {
    const el = btnRef.current
    if (!el) return
    const r = el.getBoundingClientRect()
    const vw = window.innerWidth || 0
    // Sağ kenardan taşmayı önle; dar ekranda sola yapıştır (min 8px kenar boşluğu).
    const left = Math.max(8, Math.min(r.left, Math.max(8, vw - POP_W - 8)))
    setPos({ top: r.bottom + 6, left })
  }, [])

  useEffect(() => {
    if (!open) return undefined
    place()
    const onDown = (e) => {
      if (btnRef.current?.contains(e.target)) return
      if (popRef.current?.contains(e.target)) return
      setOpen(false)
    }
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false) }
    // Kaydırma/yeniden boyutlandırmada konum tazelenir: `fixed` baloncuk aksi hâlde
    // tetikleyici kayarken yerinde donup kalırdı. capture=true → iç kaydırma kapları da.
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open, place])

  const text = helpKey ? t(helpKey) : ''
  // Eksik çeviri: t() anahtarın kendisini döndürür → ham anahtar göstermek yerine hiç çizme.
  if (!helpKey || !text || text === helpKey) return null

  const aria = label ? `${t('helptip.aria')}: ${label}` : t('helptip.aria')

  const toggle = (e) => {
    // Tetikleyici bir <label> İÇİNDE de durabiliyor; varsayılan davranış tıklamayı
    // etiketli kontrole iletir ve odağı kaçırırdı.
    e.preventDefault()
    e.stopPropagation()
    setOpen((o) => !o)
  }

  return (
    <>
      {/* <button> DEĞİL, role="button" taşıyan <span>: tetikleyici çoğu ekranda kontrolü
          SARAN bir <label> içinde duruyor ve <button> "labelable" bir elemandır — etiketin
          kontrolü sessizce bu düğme olur, asıl input ERİŞİLEBİLİR ADINI KAYBEDER ve etikete
          tıklamak alana odaklanmak yerine baloncuğu açardı. <span> labelable değildir;
          erişilebilirlik ağacında yine bir düğmedir (role + aria-label + aria-expanded). */}
      <span
        role="button"
        tabIndex={0}
        ref={btnRef}
        className="help-tip"
        aria-label={aria}
        aria-expanded={open}
        aria-describedby={open ? popId : undefined}
        onClick={toggle}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') toggle(e)
        }}
      >
        <CircleHelp size={14} aria-hidden="true" />
      </span>
      {open && typeof document !== 'undefined' && createPortal(
        <div
          ref={popRef}
          id={popId}
          role="tooltip"
          className="help-tip-pop"
          style={{ top: pos.top, left: pos.left, width: POP_W }}
        >
          {label ? <div className="help-tip-title">{label}</div> : null}
          <div className="help-tip-text">{text}</div>
        </div>,
        document.body,
      )}
    </>
  )
}
