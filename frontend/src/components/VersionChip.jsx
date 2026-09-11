import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useAppVersion } from '../contexts/BrandingProvider.jsx'
import { useT } from '../i18n/index.jsx'
import { isNewVersion, readLastSeenVersion, writeLastSeenVersion } from '../utils/releaseUi.js'
import VersionPopover from './VersionPopover.jsx'

/**
 * Nav'daki sürüm çipi (K1): tıklanınca "en son geçerli sürüm hangisi ve ne zaman devreye alındı?"
 * sorusunu tek popover'da cevaplar. Eskiden düz bir <span> idi.
 *
 * <p>E1 — yeni-sürüm noktası: son görülen sürüm damgası localStorage'da (try/catch; kapalı depolamada
 * özellik sessizce devre dışı). İlk ziyarette karşılaştırılacak damga yok → nokta ÇIKMAZ, damga
 * yazılır. Popover açılınca damga güncellenir (nokta söner).
 *
 * <p>Popover kullanıcı-menüsü deseniyle: body'ye portal, dış tıklama + Escape kapatır. Veri YALNIZ
 * açılınca çekilir (Nav her render'da istek atmasın; VersionPopover 60 sn modül önbelleği tutar).
 */
export default function VersionChip({ onTabChange }) {
  const t = useT()
  const appVersion = useAppVersion()
  const [open, setOpen] = useState(false)
  const [pos, setPos] = useState(null)
  const [seen, setSeen] = useState(readLastSeenVersion)
  const [sinceVersion, setSinceVersion] = useState('')   // popover açılırken yakalanan eski damga
  const triggerRef = useRef(null)
  const popRef = useRef(null)

  const fresh = isNewVersion(seen, appVersion)

  // İlk ziyaret: damga yok → bu sürümü sessizce yaz (bir sonraki sürümde nokta çıkabilsin).
  useEffect(() => {
    if (appVersion && !seen) { writeLastSeenVersion(appVersion); setSeen(appVersion) }
  }, [appVersion, seen])

  useEffect(() => {
    if (!open) return undefined
    const r = triggerRef.current?.getBoundingClientRect()
    if (r) setPos({ left: Math.round(r.left), top: Math.round(r.bottom + 8) })
    function onDocClick(e) {
      const el = e.target
      if (triggerRef.current?.contains(el) || popRef.current?.contains(el)) return
      setOpen(false)
    }
    function onKey(e) { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  function toggle() {
    const next = !open
    setOpen(next)
    if (next && appVersion) {
      setSinceVersion(fresh ? seen : '')            // damga güncellenmeden ÖNCE yakala
      writeLastSeenVersion(appVersion); setSeen(appVersion)
    }
  }

  function go(tab, extra) {
    setOpen(false)
    onTabChange?.(tab, extra)
  }

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className={`sb-brand-version sb-brand-version-btn${open ? ' is-open' : ''}`}
        onClick={toggle}
        title={t('version.chipTitle')}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        v{appVersion}
        {fresh && <span className="sb-version-dot" aria-label={t('version.newDot')} title={t('version.newDot')} />}
      </button>
      {open && pos && createPortal(
        <div ref={popRef} className="sb-version-pop" role="dialog" aria-label={t('version.chipTitle')}
          style={{ left: pos.left, top: pos.top }}>
          <VersionPopover appVersion={appVersion} previousSeen={sinceVersion} onNavigate={go} />
        </div>,
        document.body
      )}
    </>
  )
}
