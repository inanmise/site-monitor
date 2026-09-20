import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Bell, CheckCheck, X, Siren, CheckCircle2, Wrench, CalendarDays, ClipboardX, Activity, Trash2, History, ChevronLeft, ChevronRight, UsersRound } from 'lucide-react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { navigateTo } from '../utils/navigate.js'

/**
 * Bildirim kutusu (2026-09-12, zenginleştirme #2; v2 2026-09-20): Nav'daki zil — açık alarm, son 24 saatte çözülen,
 * bakım penceresi (aktif / yaklaşan), bugün son giriş günüyse eksik haftalık rapor, süresi dolan istisna.
 *
 * <p>v2 (kullanıcı bildirimi): her satırda TAKIM, başlangıç zamanı ve canlı süre ("3 sa 12 dk açık" / "sürdü 2 sa");
 * "İzlemeye git" ikinci eylem (alarm sayfası yerine izlemenin kendisi); çoklu seçim → seçilenleri okundu say / temizle;
 * "Tümünü temizle"; "Geçmiş" sekmesi (çözülmüş alarmlar 30 gün, sayfalı). Okundu ve temizlendi kümeleri localStorage'da
 * (kullanıcı adına göre ayrık). 60 sn'de bir görünürken tazelenir.
 */
const KIND_ICON = { alert_open: Siren, alert_resolved: CheckCircle2, maintenance_active: Wrench, maintenance_soon: Wrench, weekly_due: CalendarDays, exception_expired: ClipboardX }
const STORE = (u) => `inbox-seen:${u || 'anon'}`
const STORE_DISMISSED = (u) => `inbox-dismissed:${u || 'anon'}`
function readSet(k) { try { return new Set(JSON.parse(localStorage.getItem(k) || '[]')) } catch { return new Set() } }
function writeSet(k, set) { try { localStorage.setItem(k, JSON.stringify([...set].slice(-500))) } catch { /* yoksay */ } }

function toMs(iso) {
  if (!iso) return NaN
  return new Date(iso + (iso.endsWith('Z') ? '' : 'Z')).getTime()
}
/** Süre metni: "12 dk" / "3 sa 12 dk" / "2 g 5 sa". */
export function fmtDuration(ms, t) {
  if (!Number.isFinite(ms) || ms < 0) return ''
  const m = Math.floor(ms / 60000)
  if (m < 60) return t('inbox.durMin', m)
  const h = Math.floor(m / 60)
  if (h < 48) return t('inbox.durHour', h, m % 60)
  return t('inbox.durDay', Math.floor(h / 24), h % 24)
}

export default function InboxBell({ username, compact = false }) {
  const t = useT()
  const [items, setItems] = useState([])
  const [open, setOpen] = useState(false)
  const [seen, setSeen] = useState(() => readSet(STORE(username)))
  const [dismissed, setDismissed] = useState(() => readSet(STORE_DISMISSED(username)))
  const [showDismissed, setShowDismissed] = useState(false)
  const [selected, setSelected] = useState(() => new Set())
  const [view, setView] = useState('current')          // current | history
  const [hist, setHist] = useState(null)               // { data, total, page, total_pages }
  const [histPage, setHistPage] = useState(0)
  const [histLoading, setHistLoading] = useState(false)
  const [tick, setTick] = useState(0)                  // canlı süre için dakikada bir yeniden çizim
  const btnRef = useRef(null)

  const load = useCallback(async () => {
    try { const r = await api.me.inbox(); if (r?.success && Array.isArray(r.data)) setItems(r.data) } catch { /* zil süs */ }
  }, [])
  useVisibleInterval(load, 60_000, true)
  useVisibleInterval(() => setTick(x => x + 1), open ? 60_000 : 0)

  useEffect(() => {
    if (!open) return undefined
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  // Geçmiş sekmesi: açılınca / sayfa değişince yüklenir.
  useEffect(() => {
    if (!open || view !== 'history') return undefined
    let alive = true
    setHistLoading(true)
    Promise.resolve(api.me.inboxHistory?.(histPage, 25))
      .then(r => { if (alive && r?.success) setHist(r) })
      .catch(() => {})
      .finally(() => { if (alive) setHistLoading(false) })
    return () => { alive = false }
  }, [open, view, histPage])

  const visible = useMemo(() => showDismissed ? items : items.filter((i) => !dismissed.has(i.key)), [items, dismissed, showDismissed])
  const unread = useMemo(() => items.filter((i) => !seen.has(i.key) && !dismissed.has(i.key)).length, [items, seen, dismissed])
  const dismissedCount = useMemo(() => items.filter((i) => dismissed.has(i.key)).length, [items, dismissed])

  function persistSeen(next) { setSeen(next); writeSet(STORE(username), next) }
  function persistDismissed(next) { setDismissed(next); writeSet(STORE_DISMISSED(username), next) }
  function markAll() { const next = new Set(seen); items.forEach((i) => next.add(i.key)); persistSeen(next) }
  function markSelected() { const next = new Set(seen); selected.forEach((k) => next.add(k)); persistSeen(next); setSelected(new Set()) }
  function dismissKeys(keys) {
    const next = new Set(dismissed); keys.forEach((k) => next.add(k)); persistDismissed(next)
    const s2 = new Set(seen); keys.forEach((k) => s2.add(k)); persistSeen(s2)
    setSelected(new Set())
  }
  function clearAll() { dismissKeys(visible.map((i) => i.key)) }
  function toggleSel(key) { setSelected((prev) => { const n = new Set(prev); if (n.has(key)) n.delete(key); else n.add(key); return n }) }
  function go(it, target) {
    const next = new Set(seen); next.add(it.key); persistSeen(next)
    setOpen(false)
    if (target === 'monitor' && it.monitor_tab) navigateTo(it.monitor_tab, it.monitor_params || undefined)
    else navigateTo(it.tab, it.params)
  }

  /** Zaman satırı: başladı · süre (açıksa canlı) · çözüldü. `tick` bağımlılığı: dakikada bir yeniden hesap. */
  function timeline(it) {
    void tick
    const parts = []
    const start = it.started_at || null
    if (it.kind === 'alert_open' && start) {
      parts.push(t('inbox.startedAt', formatDateSec(start)))
      const d = fmtDuration(Date.now() - toMs(start), t)
      if (d) parts.push(t('inbox.openFor', d))
    } else if (it.kind === 'alert_resolved' && start) {
      parts.push(t('inbox.startedAt', formatDateSec(start)))
      const end = it.ended_at || it.at
      const d = fmtDuration(toMs(end) - toMs(start), t)
      if (d) parts.push(t('inbox.lasted', d))
      if (end) parts.push(t('inbox.resolvedAt', formatDateSec(end)))
    } else if (it.at) {
      parts.push(formatDateSec(it.at))
    }
    return parts.join(' · ')
  }

  function renderItem(it, { selectable }) {
    const Icon = KIND_ICON[it.kind] || Bell
    const isNew = !seen.has(it.key)
    const isDismissed = dismissed.has(it.key)
    const sel = selected.has(it.key)
    return (
      <div key={it.key} className={`inbox-row inbox-item--${it.kind}${isNew && !isDismissed ? ' is-new' : ''}${isDismissed ? ' is-dismissed' : ''}${sel ? ' is-selected' : ''}`}>
        {selectable && (
          <input type="checkbox" className="inbox-check" checked={sel} onChange={() => toggleSel(it.key)} aria-label={t('inbox.select', it.title)} />
        )}
        <button type="button" className="inbox-item" onClick={() => go(it, 'alert')} title={t('inbox.goAlert')}>
          <Icon size={15} aria-hidden="true" />
          <span className="inbox-item-main">
            <span className="inbox-item-title">{t(`inbox.kind.${it.kind}`)} · <b>{it.title}</b></span>
            <span className="inbox-item-meta">
              {it.team_name && <span className="inbox-team"><UsersRound size={11} aria-hidden="true" /> {it.team_name}</span>}
              {it.sub && <span className="inbox-item-sub">{it.sub}</span>}
              {it.monitor_name && it.monitor_name !== it.title && <span className="inbox-item-sub">{it.monitor_name}</span>}
            </span>
            <span className="inbox-item-time">{timeline(it)}</span>
          </span>
          {it.level && it.kind === 'alert_open' && <span className={`inbox-level inbox-level--${String(it.level).toLowerCase()}`}>{it.level}</span>}
        </button>
        {it.monitor_tab && (
          <button type="button" className="inbox-go-monitor" onClick={() => go(it, 'monitor')} title={t('inbox.goMonitor')} aria-label={t('inbox.goMonitor')}>
            <Activity size={14} aria-hidden="true" />
          </button>
        )}
      </div>
    )
  }

  const histItems = hist?.data || []
  const histPages = Math.max(1, Number(hist?.total_pages ?? 1))

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
          <div className="inbox inbox--v2" role="dialog" aria-modal="true" aria-label={t('inbox.title')}>
            <div className="inbox-head">
              <Bell size={16} aria-hidden="true" />
              <span className="inbox-title">{t('inbox.title')}</span>
              <span className="inbox-count">{unread > 0 ? t('inbox.unread', unread) : t('inbox.allRead')}</span>
              {view === 'current' && (
                <>
                  <button type="button" className="btn btn-sm btn-secondary" onClick={markAll} disabled={!unread}><CheckCheck size={13} /> {t('inbox.markAll')}</button>
                  <button type="button" className="btn btn-sm btn-secondary" onClick={clearAll} disabled={visible.length === 0} title={t('inbox.clearAllTip')}><Trash2 size={13} /> {t('inbox.clearAll')}</button>
                </>
              )}
              <button type="button" className="inbox-close" onClick={() => setOpen(false)} aria-label={t('app.close')}><X size={14} /></button>
            </div>
            <div className="inbox-tabs" role="tablist">
              <button type="button" role="tab" aria-selected={view === 'current'} className={`inbox-tab${view === 'current' ? ' is-active' : ''}`} onClick={() => setView('current')}>
                <Bell size={13} /> {t('inbox.tabCurrent')}{visible.length > 0 ? ` (${visible.length})` : ''}
              </button>
              <button type="button" role="tab" aria-selected={view === 'history'} className={`inbox-tab${view === 'history' ? ' is-active' : ''}`} onClick={() => setView('history')}>
                <History size={13} /> {t('inbox.tabHistory')}
              </button>
            </div>

            {view === 'current' && selected.size > 0 && (
              <div className="inbox-selbar" data-testid="inbox-selbar">
                <span>{t('inbox.selected', selected.size)}</span>
                <button type="button" className="btn btn-sm btn-secondary" onClick={markSelected}><CheckCheck size={13} /> {t('inbox.markSelected')}</button>
                <button type="button" className="btn btn-sm btn-secondary" onClick={() => dismissKeys([...selected])}><Trash2 size={13} /> {t('inbox.clearSelected')}</button>
                <button type="button" className="btn btn-sm btn-secondary" onClick={() => setSelected(new Set())}>{t('inbox.cancelSelect')}</button>
              </div>
            )}

            <div className="inbox-body">
              {view === 'current' && (
                <>
                  {visible.length === 0 && <div className="inbox-empty">{dismissedCount > 0 ? t('inbox.emptyCleared', dismissedCount) : t('inbox.empty')}</div>}
                  {visible.map((it) => renderItem(it, { selectable: true }))}
                </>
              )}
              {view === 'history' && (
                <>
                  {histLoading && histItems.length === 0 && <div className="inbox-empty">{t('inbox.loading')}</div>}
                  {!histLoading && histItems.length === 0 && <div className="inbox-empty">{t('inbox.historyEmpty')}</div>}
                  {histItems.map((it) => renderItem(it, { selectable: false }))}
                </>
              )}
            </div>

            <div className="inbox-foot">
              {view === 'current' && dismissedCount > 0 && (
                <button type="button" className="inbox-link" onClick={() => setShowDismissed((v) => !v)}>
                  {showDismissed ? t('inbox.hideCleared') : t('inbox.showCleared', dismissedCount)}
                </button>
              )}
              {view === 'history' && hist && (
                <span className="inbox-pager">
                  <button type="button" className="btn btn-sm btn-secondary" disabled={histPage <= 0} onClick={() => setHistPage((p) => p - 1)} aria-label={t('app.prevPage')}><ChevronLeft size={13} /></button>
                  <span>{t('inbox.pageInfo', histPage + 1, histPages, hist.total ?? 0)}</span>
                  <button type="button" className="btn btn-sm btn-secondary" disabled={histPage + 1 >= histPages} onClick={() => setHistPage((p) => p + 1)} aria-label={t('app.nextPage')}><ChevronRight size={13} /></button>
                </span>
              )}
              {view === 'history' && <span className="inbox-foot-note">{t('inbox.historyNote')}</span>}
            </div>
          </div>
        </div>,
        document.body,
      )}
    </>
  )
}
