import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Bell, CheckCheck, X, Siren, CheckCircle2, Wrench, CalendarDays, ClipboardX } from 'lucide-react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { navigateTo } from '../utils/navigate.js'

/**
 * Bildirim kutusu (2026-09-12, zenginleştirme #2): Nav'daki zil — açık alarm, son 24 saatte çözülen,
 * bakım penceresi (aktif / yaklaşan), bugün son giriş günüyse eksik haftalık rapor, süresi dolan istisna.
 * Okundu durumu localStorage'da (anahtar kümesi; kullanıcı adına göre ayrık). 60 sn'de bir görünürken tazelenir.
 */
const KIND_ICON = { alert_open: Siren, alert_resolved: CheckCircle2, maintenance_active: Wrench, maintenance_soon: Wrench, weekly_due: CalendarDays, exception_expired: ClipboardX }
const STORE = (u) => `inbox-seen:${u || 'anon'}`

function readSeen(u) { try { return new Set(JSON.parse(localStorage.getItem(STORE(u)) || '[]')) } catch { return new Set() } }
function writeSeen(u, set) { try { localStorage.setItem(STORE(u), JSON.stringify([...set].slice(-500))) } catch { /* yoksay */ } }

export default function InboxBell({ username, compact = false }) {
  const t = useT()
  const [items, setItems] = useState([])
  const [open, setOpen] = useState(false)
  const [seen, setSeen] = useState(() => readSeen(username))
  const btnRef = useRef(null)

  const load = useCallback(async () => {
    try { const r = await api.me.inbox(); if (r?.success && Array.isArray(r.data)) setItems(r.data) } catch { /* zil süs */ }
  }, [])
  useVisibleInterval(load, 60_000, true)

  useEffect(() => {
    if (!open) return undefined
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  const unread = useMemo(() => items.filter((i) => !seen.has(i.key)).length, [items, seen])

  function markAll() {
    const next = new Set(seen); items.forEach((i) => next.add(i.key)); setSeen(next); writeSeen(username, next)
  }
  function go(it) {
    const next = new Set(seen); next.add(it.key); setSeen(next); writeSeen(username, next)
    setOpen(false)
    navigateTo(it.tab, it.params)
  }

  return (
    <>
      <button ref={btnRef} type="button" className={`sb-bell${compact ? ' sb-bell--mini' : ''}`} onClick={() => setOpen((o) => !o)}
        aria-label={t('inbox.title')} aria-expanded={open} title={unread ? t('inbox.unread', unread) : t('inbox.title')}>
        <Bell size={15} aria-hidden="true" />
        {!compact && <span className="sb-bell-text">{t('inbox.title')}</span>}
        {unread > 0 && <span className="sb-bell-badge" aria-hidden="true">{unread > 99 ? '99+' : unread}</span>}
      </button>
      {open && createPortal(
        <div className="inbox-overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) setOpen(false) }}>
          <div className="inbox" role="dialog" aria-modal="true" aria-label={t('inbox.title')}>
            <div className="inbox-head">
              <Bell size={16} aria-hidden="true" />
              <span className="inbox-title">{t('inbox.title')}</span>
              <span className="inbox-count">{unread > 0 ? t('inbox.unread', unread) : t('inbox.allRead')}</span>
              <button type="button" className="btn btn-sm btn-secondary" onClick={markAll} disabled={!unread}><CheckCheck size={13} /> {t('inbox.markAll')}</button>
              <button type="button" className="inbox-close" onClick={() => setOpen(false)} aria-label={t('app.close')}><X size={14} /></button>
            </div>
            <div className="inbox-body">
              {items.length === 0 && <div className="inbox-empty">{t('inbox.empty')}</div>}
              {items.map((it) => {
                const Icon = KIND_ICON[it.kind] || Bell
                const isNew = !seen.has(it.key)
                return (
                  <button type="button" key={it.key} className={`inbox-item inbox-item--${it.kind}${isNew ? ' is-new' : ''}`} onClick={() => go(it)}>
                    <Icon size={15} aria-hidden="true" />
                    <span className="inbox-item-main">
                      <span className="inbox-item-title">{t(`inbox.kind.${it.kind}`)} · <b>{it.title}</b></span>
                      {it.sub && <span className="inbox-item-sub">{it.sub}</span>}
                    </span>
                    <span className="inbox-item-at">{it.at ? formatDateSec(it.at) : ''}</span>
                    {it.level && it.kind === 'alert_open' && <span className={`inbox-level inbox-level--${String(it.level).toLowerCase()}`}>{it.level}</span>}
                  </button>
                )
              })}
            </div>
          </div>
        </div>,
        document.body,
      )}
    </>
  )
}
