import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import { readPageSize, writePageSize } from '../../hooks/usePagination.js'
import UserBadge from '../ui/UserBadge.jsx'
import { mailPreviewSrcDoc, mailLogoVariant, MAIL_PREVIEW_SANDBOX } from '../../utils/mailPreview.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import { ALERT_TYPES, alertTypeMeta, alertTypeLabel } from '../../utils/alertTypeMeta.js'
import MonitorStatsSection from '../MonitorStatsSection.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import {
  Check, ShieldAlert, TrendingUp, RefreshCcw, Bell, CheckCircle, AlertCircle, ChevronUp,
  ChevronDown, Mail, MailX, Clock, Users, Calendar
} from 'lucide-react'

const levelColor = { WARNING: '#f0a500', HIGH: '#e07b00', CRITICAL: '#c0392b' }
const TIER_COLOR = { 1: '#4f46e5', 2: '#0284c7', 3: '#0891b2', 4: '#6b7280' }

function tierColor(t) { return TIER_COLOR[t] ?? '#94a3b8' }

function formatDuration(end, start) {
  const ms = new Date(end) - new Date(start)
  if (isNaN(ms) || ms < 0) return '—'
  const totalMin = Math.floor(ms / 60000)
  const days  = Math.floor(totalMin / 1440)
  const hours = Math.floor((totalMin % 1440) / 60)
  const mins  = totalMin % 60
  if (days  > 0) return `${days}g ${hours}s ${mins}d`
  if (hours > 0) return `${hours}s ${mins}d`
  return `${mins}d`
}

// Snapshot expiry date at alarm-creation time: created_at + days_remaining × 1 day.
// Reflects the cert's not_after as it was when the alert fired, not the current value.
function alertExpiryDate(a) {
  if (!a?.created_at || a.days_remaining == null) return null
  const created = new Date(a.created_at)
  if (isNaN(created)) return null
  return new Date(created.getTime() + a.days_remaining * 86_400_000)
}

function AuditRow({ label, by, at, variant }) {
  const colors = {
    ack:     { bg: '#f0fdf4', border: '#86efac', text: '#15803d', iconBg: '#dcfce7' },
    resolve: { bg: '#eff6ff', border: '#93c5fd', text: '#1d4ed8', iconBg: '#dbeafe' },
    system:  { bg: '#f8fafc', border: '#cbd5e1', text: '#475569', iconBg: '#f1f5f9' },
  }
  const c = colors[variant] ?? colors.system
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10,
      background: c.bg, border: `1px solid ${c.border}`,
      borderRadius: 8, padding: '8px 12px', fontSize: '.84em',
    }}>
      <span style={{
        width: 28, height: 28, borderRadius: '50%', background: c.iconBg,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}><Check size={14} color={c.text} /></span>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
        <span style={{ color: c.text, fontWeight: 700 }}>{label}</span>
        <span style={{ color: '#64748b' }}>
          <UserBadge username={by} inline size="sm" />
          {at && <> &nbsp;·&nbsp; {formatDate(at)}</>}
        </span>
      </div>
    </div>
  )
}

function EmailStatusBadge({ status }) {
  const t = useT()
  if (!status) return null
  if (status === 'SENT')
    return <span className="nl-status nl-status-ok">{t('alh.status.sent')}</span>
  if (status === 'SKIPPED_DISABLED')
    return <span className="nl-status nl-status-warn">{t('alh.status.skip')}</span>
  if (status.startsWith('FAILED'))
    return <span className="nl-status nl-status-err" title={status}>{t('alh.status.failed')}</span>
  return <span className="nl-status nl-status-muted">{status}</span>
}

function NotifLogCard({ log: l, alertLevel }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)

  const triggerMeta = {
    INITIAL:       { Icon: ShieldAlert,  textKey: 'alh.trigger.initial',    bg: '#fef2f2', border: '#fca5a5', color: '#c0392b' },
    ESCALATION:    { Icon: TrendingUp,   textKey: 'alh.trigger.escalation', bg: '#fff7ed', border: '#fdba74', color: '#c2410c' },
    DAILY_REALERT: { Icon: RefreshCcw,   textKey: 'alh.trigger.daily',      bg: '#fffbeb', border: '#fcd34d', color: '#92400e' },
    MANUAL:        { Icon: Bell,         textKey: 'alh.trigger.manual',     bg: '#eff6ff', border: '#93c5fd', color: '#1d4ed8' },
    RESOLUTION:    { Icon: CheckCircle,  textKey: 'alh.trigger.resolution', bg: '#f0fdf4', border: '#86efac', color: '#15803d' },
  }

  const trigBase = triggerMeta[l.trigger]
  const trig = trigBase
    ? { ...trigBase, text: t(trigBase.textKey) }
    : { Icon: Mail, text: l.trigger, bg: '#f8fafc', border: '#e2e8f0', color: '#475569' }

  function fmtDateTime(iso) {
    if (!iso) return '—'
    try {
      const s = iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z'
      return new Date(s).toLocaleString(locale, {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
      })
    } catch { return iso }
  }

  const sentDate = fmtDateTime(l.sent_at)

  return (
    <div className="nl-card" style={{ borderColor: trig.border, background: open ? trig.bg : undefined }}>
      <div className="nl-card-header" onClick={() => setOpen(o => !o)}>
        <span className="nl-trigger-badge" style={{ background: trig.bg, color: trig.color, borderColor: trig.border }}>
          <trig.Icon size={11} /> {trig.text}
        </span>
        <div className="nl-recipient">
          <strong>{l.recipient_name}</strong>
          {l.recipient_role && l.recipient_role !== 'COMBINED' && (
            <span className="role-badge" style={{ marginLeft: 6, fontSize: '.75em' }}>{l.recipient_role}</span>
          )}
          <span className="nl-email">{l.recipient_email}</span>
        </div>
        <div className="nl-right">
          <EmailStatusBadge status={l.email_status} />
          <span className="nl-time">{sentDate}</span>
          <span className="nl-chevron">{open ? <ChevronUp size={12} /> : <ChevronDown size={12} />}</span>
        </div>
      </div>

      {open && (
        <div className="nl-card-body">
          {l.email_from && (
            <div className="nl-detail-row">
              <span className="nl-detail-label">{t('alh.notif.from')}</span>
              <span className="nl-detail-val">{l.email_from}</span>
            </div>
          )}
          {l.recipient_email && (
            <div className="nl-detail-row">
              <span className="nl-detail-label">{t('alh.notif.to')}</span>
              <span className="nl-detail-val">{l.recipient_email}</span>
            </div>
          )}
          {l.cc && (
            <div className="nl-detail-row">
              <span className="nl-detail-label">{t('alh.notif.cc')}</span>
              <span className="nl-detail-val">{l.cc}</span>
            </div>
          )}
          <div className="nl-detail-row">
            <span className="nl-detail-label">{t('alh.notif.subject')}</span>
            <span className="nl-detail-val nl-subject">{l.subject || '—'}</span>
          </div>
          <div className="nl-detail-row nl-detail-row--body">
            <span className="nl-detail-label">{t('alh.notif.content')}</span>
            {l.message && l.message.trimStart().startsWith('<') ? (
              <iframe
                className="nl-message-iframe"
                srcDoc={mailPreviewSrcDoc(l.message, {
                  // Gönderimde hangi logo varyantı iliştirildiyse önizlemede de o gösterilir
                  // (çözülme mailleri daima "ok"). Bkz. mailLogoVariant.
                  logoVariant: mailLogoVariant({ trigger: l.trigger, level: alertLevel }),
                })}
                sandbox={MAIL_PREVIEW_SANDBOX}
                title={l.subject}
              />
            ) : (
              <span className="nl-detail-val nl-message">{l.message || '—'}</span>
            )}
          </div>
          <div className="nl-detail-row">
            <span className="nl-detail-label">{t('alh.notif.emailStatus')}</span>
            <span className="nl-detail-val">
              <EmailStatusBadge status={l.email_status} />
              {l.email_status?.startsWith('FAILED') && (
                <span className="nl-error-detail">{l.email_status.replace('FAILED: ', '')}</span>
              )}
            </span>
          </div>
          {l.webhook_status && l.webhook_status !== 'SKIPPED' && (
            <div className="nl-detail-row">
              <span className="nl-detail-label">{t('alh.notif.webhook')}</span>
              <span className="nl-detail-val">{l.webhook_status}</span>
            </div>
          )}
          <div className="nl-detail-row">
            <span className="nl-detail-label">{t('alh.notif.sentAt')}</span>
            <span className="nl-detail-val">{sentDate}</span>
          </div>
        </div>
      )}
    </div>
  )
}

function NotifyResultModal({ alertId, alertInfo, currentResult, onClose }) {
  const t = useT()
  const [history, setHistory]       = useState([])
  const [loadingHistory, setLoading] = useState(true)

  useEffect(() => {
    api.admin.getAlertNotifications(alertId).then(res => {
      setLoading(false)
      if (res?.success) setHistory(res.data)
    })
  }, [alertId])

  const { notifications = [], contacts_attempted } = currentResult?.data ?? {}

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box nl-modal" onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
          <h3 style={{ margin: 0 }}>{t('alh.notifModal.title')}</h3>
          {alertInfo && (
            <span style={{
              fontSize: '.8em', fontWeight: 700, padding: '2px 9px',
              borderRadius: 12, background: alertInfo.resolved ? '#eff6ff' : '#fef2f2',
              color: alertInfo.resolved ? '#1d4ed8' : '#c0392b',
              border: `1px solid ${alertInfo.resolved ? '#93c5fd' : '#fca5a5'}`,
            }}>
              {alertInfo.resolved ? t('alh.notifModal.closed') : t('alh.notifModal.open')} · {alertInfo.domain}
            </span>
          )}
        </div>

        {notifications.length > 0 && (
          <div className="nl-section">
            <div className="nl-section-title">
              {t('alh.notifModal.lastSent')}
              <span className="nl-count">{t('alh.notifModal.recipients', contacts_attempted)}</span>
            </div>
            {notifications.some(n => n.email_status === 'SKIPPED_DISABLED') && (
              <div className="nl-banner-warn">{t('alh.notifModal.emailOff')}</div>
            )}
            {notifications.map((n, i) => (
              <div key={n.email ?? `nq-${i}`} className="nl-quick-row">
                <strong>{n.name}</strong>
                <span className="role-badge">{n.role}</span>
                <span className="nl-email">{n.email}</span>
                <EmailStatusBadge status={n.email_status} />
              </div>
            ))}
          </div>
        )}

        <div className="nl-section" style={{ marginTop: notifications.length > 0 ? 20 : 0 }}>
          <div className="nl-section-title">
            {t('alh.notifModal.allHistory')}
            {!loadingHistory && <span className="nl-count">{t('alh.notifModal.records', history.length)}</span>}
          </div>

          {loadingHistory && <div className="nl-empty">{t('alh.loading')}</div>}

          {!loadingHistory && history.length === 0 && (
            <div className="nl-empty">{t('alh.notifModal.noNotifs')}</div>
          )}

          {!loadingHistory && history.map((l, i) => (
            <NotifLogCard key={l.id ?? i} log={l} alertLevel={alertInfo?.alert_level} />
          ))}
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>{t('alh.notifModal.close')}</button>
        </div>
      </div>
    </div>
  )
}

/** "Tekrar Bildir" onay pop-up'ı: gönderim ÖNCESİ alıcı listesi; kullanıcı istemediklerini
 *  işaretten çıkarır (çıkarılana e-posta da webhook da gitmez). Hepsi çıkarılırsa Gönder pasif. */
function ReNotifyConfirmModal({ domain, recipients, sending, onSend, onClose }) {
  const t = useT()
  const [unchecked, setUnchecked] = useState(() => new Set())
  const toggle = (email) => setUnchecked(s => {
    const n = new Set(s); if (n.has(email)) n.delete(email); else n.add(email); return n
  })
  const selectedCount = recipients.length - unchecked.size

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box nl-modal" onClick={e => e.stopPropagation()}>
        <h3 style={{ marginTop: 0 }}>{t('alh.renotifyModal.title')}</h3>
        <p style={{ fontSize: '.88em', color: 'var(--text-light)' }}>{t('alh.renotifyModal.desc', domain)}</p>

        {recipients.length === 0 ? (
          <div className="nl-empty">{t('alh.renotifyModal.noRecipients')}</div>
        ) : (
          <div className="nl-section">
            {recipients.map(r => (
              <label key={r.email} className="checkbox-label nl-quick-row" style={{ width: '100%' }}>
                <input
                  type="checkbox"
                  checked={!unchecked.has(r.email)}
                  onChange={() => toggle(r.email)}
                />
                <strong>{r.name || r.email}</strong>
                <span className="role-badge">{r.kind === 'TEAM' ? t('alh.renotifyModal.kindTeam') : (r.role || '')}</span>
                <span className="nl-email">{r.email}</span>
              </label>
            ))}
          </div>
        )}

        <div style={{ fontSize: '.82em', color: 'var(--text-light)', marginTop: 10 }}>
          {t('alh.renotifyModal.selected', selectedCount)}
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose} disabled={sending}>
            {t('alh.renotifyModal.cancel')}
          </button>
          <button
            className="btn btn-warning"
            disabled={sending || selectedCount === 0}
            onClick={() => onSend([...unchecked])}
          >
            {t('alh.renotifyModal.send')}
          </button>
        </div>
      </div>
    </div>
  )
}

export default function AlertHistory({ domain = null, urlSync = false }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const toast = useToast()
  const [alerts,       setAlerts]       = useState([])
  const [tab,          setTab]          = useState(() => (urlSync && readUrlParam('tab', null) === 'closed' ? 'closed' : 'open'))
  const [page,         setPage]         = useState(() => (urlSync ? readUrlInt('page', 1) - 1 : 0))
  const [pageSize,     setPageSize]     = useState(() => (urlSync && readUrlInt('ps', null)) || readPageSize('alert-history'))
  const [total,        setTotal]        = useState(0)
  const [closedFrom,   setClosedFrom]   = useState(() => (urlSync ? readUrlParam('from', null) : null))
  const [closedTo,     setClosedTo]     = useState(() => (urlSync ? readUrlParam('to', null) : null))
  const [loading,      setLoading]      = useState(false)
  const [notifyModal,  setNotifyModal]  = useState(null)
  const [notifying,    setNotifying]    = useState(null)
  const [renotifyModal,   setRenotifyModal]   = useState(null)   // Tekrar Bildir onay pop-up'ı
  const [renotifySending, setRenotifySending] = useState(false)
  const [typeFilter,   setTypeFilter]   = useState(() => (urlSync ? readUrlParam('type', '') : ''))   // '' = tüm tipler
  const [typeCounts,   setTypeCounts]   = useState({})
  const [selected,     setSelected]     = useState(() => new Set())   // toplu seçim (yalnız açık sekme)
  const [bulkBusy,     setBulkBusy]     = useState(false)
  // ── Arama ve filtreler (yalnız bağımsız sayfada; gömülü modda domain zaten sabit) ──
  // Hepsi SUNUCUYA gider: istemci tarafında süzmek yalnız açık sayfayı süzer ve sayfalamayla
  // "3 sonuç" derken aslında 90 sonuç olur.
  const [search,       setSearch]       = useState(() => (urlSync ? readUrlParam('q', '') : ''))
  const [searchTerm,   setSearchTerm]   = useState(search)   // debounce'lanmış hâli (isteğe giden)
  const [levelFilter,  setLevelFilter]  = useState(() => (urlSync ? readUrlParam('level', '') : ''))
  const [teamFilter,   setTeamFilter]   = useState(() => (urlSync ? readUrlParam('team', '') : ''))
  const [ackFilter,    setAckFilter]    = useState(() => (urlSync ? readUrlParam('ack', '') : ''))
  const [levelCounts,  setLevelCounts]  = useState({})
  const [unackedTotal, setUnackedTotal] = useState(0)
  const [staleTotal,   setStaleTotal]   = useState(0)
  const [staleHours,   setStaleHours]   = useState(24)
  const [teams,        setTeams]        = useState([])
  const [statsVisible, setStatsVisible] = useState(true)

  // Yazarken her tuşta istek atma — 300 ms sessizlikten sonra tek istek.
  useEffect(() => {
    const id = setTimeout(() => setSearchTerm(search), 300)
    return () => clearTimeout(id)
  }, [search])

  // Takım listesi yalnız bağımsız sayfada ve bir kez.
  useEffect(() => {
    if (!urlSync) return
    api.admin.getTeams().then(res => { if (res?.success) setTeams(res.data ?? []) }).catch(() => {})
  }, [urlSync])

  const levelLabel = {
    WARNING: t('alh.level.warning'), HIGH: t('alh.level.high'), CRITICAL: t('alh.level.critical'),
  }
  // Tip etiketi/ikonu/rengi ARTIK YEREL DEĞİL: utils/alertTypeMeta.js tek kaynak.
  // Buradaki yerel harita yalnız 11 tip tanıyordu (backend'de 28) — keyword/ping/HTTP/sayfa/
  // sentetik/alan-adı alarmları ham enum adıyla görünüyor ve FİLTRELENEMİYORDU.
  function TypeChip({ type, size = 13 }) {
    const { icon: Icon, color } = alertTypeMeta(type)
    return (
      <span className="alert-type alh-type-chip" style={{ color }}>
        <Icon size={size} />
        {alertTypeLabel(t, type)}
      </span>
    )
  }

  // ── İstatistik kartları ────────────────────────────────────────────────────
  // Sayılar SUNUCUDAN gelir (level_counts / unacked_total). Sayfa içinden hesaplanamaz:
  // 81 alarmın 20'si ekranda dururken "Kritik: 12" yazmak yanıltıcı olurdu.
  const levelTotal = Object.values(levelCounts).reduce((a, b) => a + b, 0)
  const statItems = [
    { key: '',         Icon: Bell,        label: t('alh.statTotal'),    value: levelTotal,                  cls: 'total'    },
    { key: 'CRITICAL', Icon: AlertCircle, label: t('alh.statCritical'), value: levelCounts.CRITICAL ?? 0,   cls: 'critical' },
    { key: 'HIGH',     Icon: TrendingUp,  label: t('alh.statHigh'),     value: levelCounts.HIGH ?? 0,       cls: 'high'     },
    { key: 'WARNING',  Icon: ShieldAlert, label: t('alh.statWarning'),  value: levelCounts.WARNING ?? 0,    cls: 'warning'  },
    { key: 'unacked',  Icon: Bell,        label: t('alh.statUnacked'),  value: unackedTotal,                cls: 'warning',
      hint: t('mondash.unackedHint') },
    // YALNIZ açık sekmede: kapalı sekmede bu sayı "24 saatten eski" demek olurdu,
    // "24 saattir AÇIK" değil — iki farklı şey ve ikincisi kullanıcının sorduğu.
    ...(tab === 'open' ? [{ key: 'stale', Icon: Clock, label: t('alh.statStale', staleHours),
                    value: staleTotal, cls: 'high', hint: t('alh.statStaleHint', staleHours) }] : []),
  ]

  // "Sahiplenilmemiş" kartı seviye DEĞİL, sahiplenme boyutunu filtreler — tek kart şeridinde
  // iki farklı boyut olduğu için tıklama burada ayrıştırılır.
  function onLevelCardClick(key) {
    // "Uzun süredir açık" kartı SAYAÇ — tıklanınca filtre uygulamaz. Sunucuda karşılığı olan bir
    // parametre yok; sahte bir istemci-tarafı süzme eklemek sayfalamayla yanıltıcı olurdu
    // (ekrandaki 20 satırdan 3'ünü gösterip "3 tane" demek). Bilinçli olarak gösterge bırakıldı.
    if (key === 'stale') return
    if (key === 'unacked') { setAckFilter(a => (a === 'unack' ? '' : 'unack')); setLevelFilter(''); return }
    setAckFilter('')
    setLevelFilter(l => (l === key ? '' : key))
  }

  const levelOptions = [
    { value: '',         label: t('alh.allLevels') },
    { value: 'CRITICAL', label: levelLabel.CRITICAL },
    { value: 'HIGH',     label: levelLabel.HIGH },
    { value: 'WARNING',  label: levelLabel.WARNING },
  ]
  const ackOptions = [
    { value: '',      label: t('alh.allAckStates') },
    { value: 'unack', label: t('alh.unackedOnly') },
    { value: 'ack',   label: t('alh.ackOnly') },
  ]
  const teamOptions = [
    { value: '', label: t('alh.allTeams') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name })),
  ]

  const hasActiveFilters = !!(search || levelFilter || teamFilter || ackFilter || typeFilter
                              || closedFrom || closedTo)
  function clearFilters() {
    setSearch(''); setLevelFilter(''); setTeamFilter(''); setAckFilter('')
    setTypeFilter(''); setClosedFrom(null); setClosedTo(null)
  }

  const load = useCallback(async () => {
    setLoading(true)
    const params = {
      resolved: tab === 'closed' ? 'true' : 'false',
      page,
      size: pageSize,
    }
    if (tab === 'closed') {
      if (closedFrom) params.resolvedSince = closedFrom
      if (closedTo)   params.resolvedUntil = closedTo
    }
    if (domain) params.domain = domain
    if (typeFilter) params.alertType = typeFilter
    if (searchTerm.trim()) params.q = searchTerm.trim()
    if (levelFilter) params.level = levelFilter
    if (teamFilter)  params.teamId = teamFilter
    if (ackFilter)   params.acknowledged = ackFilter === 'ack' ? 'true' : 'false'
    const res = await api.admin.getAlerts(params)
    setLoading(false)
    if (res?.success) {
      setAlerts(res.data ?? [])
      setTotal(res.total ?? 0)
      setTypeCounts(res.type_counts ?? {})
      setLevelCounts(res.level_counts ?? {})
      setUnackedTotal(res.unacked_total ?? 0)
      setStaleTotal(res.stale_total ?? 0)
      setStaleHours(res.stale_hours ?? 24)
    } else if (res != null) {
      toast.error(res?.error || t('alh.loadError'))
    }
  }, [tab, page, pageSize, closedFrom, closedTo, domain, typeFilter,
      searchTerm, levelFilter, teamFilter, ackFilter, t, toast])

  useEffect(() => { load() }, [load])
  // Filtre değişince 1. sayfaya dön — aksi halde 5. sayfada daralan sonuçta BOŞ ekran kalır.
  useEffect(() => { setPage(0) }, [tab, pageSize, closedFrom, closedTo, typeFilter,
                                   searchTerm, levelFilter, teamFilter, ackFilter])
  // Liste bağlamı değişince seçim sıfırlansın (sekme/sayfa/filtre) — bayat id'ler seçili kalmasın
  useEffect(() => { setSelected(new Set()) }, [tab, page, pageSize, closedFrom, closedTo, typeFilter, domain])

  function applyQuickRange(days) {
    const now = new Date()
    const from = new Date(now.getTime() - days * 86400000)
    setClosedFrom(from.toISOString().slice(0, 19))
    setClosedTo(now.toISOString().slice(0, 19))
  }

  async function ack(id) {
    const alert = alerts.find(a => a.id === id)
    const confirmed = await showConfirm({
      title: t('alh.ackDialog.title'),
      message: t('alh.ackDialog.msg', alert?.domain ?? ''),
      variant: 'warning',
      confirmText: t('alh.ackDialog.confirm'),
      cancelText: t('alh.ackDialog.cancel'),
    })
    if (!confirmed) return
    await api.admin.acknowledgeAlert(id)
    load()
  }

  async function resolve(id) {
    const alert = alerts.find(a => a.id === id)
    const confirmed = await showConfirm({
      title: t('alh.resolveDialog.title'),
      message: t('alh.resolveDialog.msg', alert?.domain ?? ''),
      variant: 'success',
      confirmText: t('alh.resolveDialog.confirm'),
      cancelText: t('alh.resolveDialog.cancel'),
    })
    if (!confirmed) return
    try {
      const res = await api.admin.resolveAlert(id)
      if (res?.success === false) {
        toast.error(res?.error || t('alh.resolveError'))
      } else {
        toast.success(t('alh.resolveSuccess'))
      }
    } catch (err) {
      toast.error(t('alh.resolveError'))
    } finally {
      load()
    }
  }

  // Tekrar Bildir: önce alıcı önizlemesi → onay pop-up'ı; gönderim sendReNotify ile yapılır.
  async function reNotify(id) {
    setNotifying(id)
    const res = await api.admin.previewReNotify(id)
    setNotifying(null)
    if (res?.success) {
      const alert = alerts.find(a => a.id === id)
      setRenotifyModal({ alertId: id, domain: alert?.domain || '', recipients: res.data?.recipients || [] })
    } else {
      toast.error(res?.error || t('alh.renotifyModal.previewError'))
    }
  }

  async function sendReNotify(excludeEmails) {
    if (!renotifyModal) return
    setRenotifySending(true)
    const res = await api.admin.reNotifyAlert(renotifyModal.alertId,
      excludeEmails.length ? { excludeEmails } : undefined)
    setRenotifySending(false)
    if (res?.success) {
      const count = res.data?.recipients_queued ?? res.data?.contacts_queued ?? 0
      toast.success(t('alh.notifyQueued', count))
      setRenotifyModal(null)
      load()
    } else {
      toast.error(res?.error || 'Error')
    }
  }

  // ── Toplu seçim + toplu işlem (yalnız açık sekme) ──
  const toggleSelect = (id) => setSelected(s => {
    const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n
  })
  const allSelected = alerts.length > 0 && alerts.every(a => selected.has(a.id))
  const toggleSelectAll = () => setSelected(() => allSelected ? new Set() : new Set(alerts.map(a => a.id)))

  async function bulkAction(action) {
    const ids = [...selected]
    if (ids.length === 0) return
    const meta = {
      acknowledge: { title: t('alh.bulk.ackTitle'),      msg: t('alh.bulk.ackMsg', ids.length),      variant: 'warning', confirm: t('alh.ack') },
      resolve:     { title: t('alh.bulk.resolveTitle'),  msg: t('alh.bulk.resolveMsg', ids.length),  variant: 'success', confirm: t('alh.resolve') },
      're-notify': { title: t('alh.bulk.renotifyTitle'), msg: t('alh.bulk.renotifyMsg', ids.length), variant: 'warning', confirm: t('alh.renotify') },
    }[action]
    const confirmed = await showConfirm({
      title: meta.title, message: meta.msg, variant: meta.variant,
      confirmText: meta.confirm, cancelText: t('alh.resolveDialog.cancel'),
    })
    if (!confirmed) return
    setBulkBusy(true)
    let res
    try { res = await api.admin.bulkAlertAction(action, ids) }
    finally { setBulkBusy(false) }
    if (res?.success) {
      const { processed = 0, skipped = 0, failed = 0 } = res.data ?? {}
      let msg = t('alh.bulk.done', processed)
      if (skipped) msg += ' · ' + t('alh.bulk.skipped', skipped)
      if (failed)  msg += ' · ' + t('alh.bulk.failed', failed)
      if (failed) toast.error(msg); else toast.success(msg)
      setSelected(new Set())
      load()
    } else {
      toast.error(res?.error || t('alh.resolveError'))
    }
  }

  function openNotifyHistory(id) {
    const alert = alerts.find(a => a.id === id)
    setNotifyModal({ alertId: id, alertInfo: alert, result: null })
  }

  function parseContacts(json) {
    if (!json) return []
    try {
      const v = JSON.parse(json)
      return Array.isArray(v) ? v : []
    } catch { return [] }
  }

  const isOpen   = tab === 'open'
  const isClosed = tab === 'closed'
  const totalPages = Math.max(1, Math.ceil(total / pageSize))

  // Paylaşılabilir URL (yalnız Alarm Geçmişi SEKMESİ — gömülü modallarda kapalı: enabled guard).
  // Paylaşılabilir bağlantı: görünümü ÜRETEN her şey adres çubuğunda yaşar; varsayılan değer
  // param üretmez (temiz URL). Bağlantıyı alan kişi AYNI listeyi açar — eskiden yalnız sayfa
  // numarası taşınıyordu, filtreler kayboluyordu.
  useUrlQuerySync({
    tab: tab !== 'open' ? tab : null,
    type: typeFilter || null,
    q: searchTerm.trim() || null,
    level: levelFilter || null,
    team: teamFilter || null,
    ack: ackFilter || null,
    from: closedFrom || null,
    to: closedTo || null,
    page: page > 0 ? page + 1 : null,
    ps: (pageSize !== 50 || page > 0) ? pageSize : null,
  }, { enabled: urlSync })

  return (
    <div className="admin-section">
      <div className="alh-header">
        <div className="alh-tabs">
          <button
            type="button"
            className={`alh-tab alh-tab-open${isOpen ? ' is-active' : ''}`}
            onClick={() => setTab('open')}
          >
            <AlertCircle size={13} />
            {t('alh.tabOpen')}
          </button>
          <button
            type="button"
            className={`alh-tab alh-tab-closed${isClosed ? ' is-active' : ''}`}
            onClick={() => setTab('closed')}
          >
            <CheckCircle size={13} />
            {t('alh.tabClosed')}
          </button>
        </div>
        <button className="btn btn-secondary btn-sm-p" onClick={load} disabled={loading}>
          <RefreshCcw size={13} /> {t('alh.refresh')}
        </button>
      </div>

      {/* ── İstatistik şeridi + filtre çubuğu — YALNIZ bağımsız sayfada ──
             Gömülü modda (monitör/sertifika modalının "Alarm Geçmişi" sekmesi) domain zaten
             sabit; orada takım/arama filtresi anlamsız olur ve modalı gereksiz uzatır. ── */}
      {urlSync && (
        <>
          <MonitorStatsSection
            loading={loading} total={statItems[0].value}
            statsVisible={statsVisible} onToggle={() => setStatsVisible(v => !v)}
            items={statItems} activeFilter={levelFilter || null}
            onStatClick={onLevelCardClick}
            onClearFilter={() => setLevelFilter('')}
            shownCount={alerts.length} />

          <div className="upt-toolbar alh-toolbar">
            <SearchableSelect value={levelFilter} onChange={setLevelFilter} options={levelOptions} />
            {teamOptions.length > 1 && (
              <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} searchThreshold={2} />
            )}
            <SearchableSelect value={ackFilter} onChange={setAckFilter} options={ackOptions} />
            <input className="upt-search" type="text" placeholder={t('alh.searchPlaceholder')}
              value={search} onChange={e => setSearch(e.target.value)} />
            {hasActiveFilters && (
              <button type="button" className="btn btn-secondary btn-sm-p" onClick={clearFilters}>
                {t('alh.clearFilters')}
              </button>
            )}
          </div>
        </>
      )}

      {/* ── Tip filtre pill'leri — canlı sayılarla ── */}
      <div className="inv-stats-pills" style={{ marginBottom: 14 }}>
        <button
          type="button"
          className={`inv-stat-pill${typeFilter === '' ? ' is-selected' : ''}`}
          style={typeFilter === '' ? { borderColor: 'var(--text-muted)', background: 'rgba(100,116,139,.12)' } : undefined}
          onClick={() => setTypeFilter('')}
        >
          {t('alh.typeAll')}: <strong>{Object.values(typeCounts).reduce((s, n) => s + n, 0)}</strong>
        </button>
        {ALERT_TYPES
          .filter(type => (typeCounts[type] ?? 0) > 0 || typeFilter === type)
          .map(type => {
            const meta = alertTypeMeta(type)
            const Icon = meta.icon
            const selected = typeFilter === type
            return (
              <button
                key={type}
                type="button"
                className={`inv-stat-pill${selected ? ' is-selected' : ''}`}
                style={selected ? { borderColor: meta.color, background: meta.color + '1a' } : undefined}
                onClick={() => setTypeFilter(selected ? '' : type)}
              >
                <Icon size={12} style={{ color: meta.color, flexShrink: 0 }} />
                {alertTypeLabel(t, type)}: <strong>{typeCounts[type] ?? 0}</strong>
              </button>
            )
          })}
      </div>

      {isClosed && (
        <div className="alh-filter-bar">
          <div className="alh-quick-pills">
            {[
              { key: '24h', days: 1 },
              { key: '7d',  days: 7 },
              { key: '30d', days: 30 },
              { key: '90d', days: 90 },
            ].map(({ key, days }) => (
              <button
                key={key}
                type="button"
                className="alh-quick-pill"
                onClick={() => applyQuickRange(days)}
              >
                {t(`alh.quick.${key}`)}
              </button>
            ))}
            {(closedFrom || closedTo) && (
              <button
                type="button"
                className="alh-quick-pill alh-quick-clear"
                onClick={() => { setClosedFrom(null); setClosedTo(null) }}
              >
                {t('alh.quick.clear')}
              </button>
            )}
          </div>
          {(closedFrom || closedTo) && (
            <span className="alh-filter-summary">
              {closedFrom && <>{formatDate(closedFrom)}</>}
              {closedFrom && closedTo && ' → '}
              {closedTo && <>{formatDate(closedTo)}</>}
            </span>
          )}
        </div>
      )}

      {loading && <LoadingBlock label={t('alh.loading')} fullWidth />}

      {!loading && alerts.length === 0 && isOpen && (
        <div className="empty-state">{t('alh.noOpen')}</div>
      )}

      {!loading && alerts.length === 0 && isClosed && (
        <div className="empty-state">{t('alh.noClosed')}</div>
      )}

      {isOpen && alerts.length > 0 && (
        <div className="alh-bulk-bar">
          <label className="alh-bulk-all">
            <input
              type="checkbox"
              checked={allSelected}
              ref={el => { if (el) el.indeterminate = selected.size > 0 && !allSelected }}
              onChange={toggleSelectAll}
            />
            <span>{selected.size > 0 ? t('alh.bulk.selected', selected.size) : t('alh.bulk.selectAll')}</span>
          </label>
          {selected.size > 0 && (
            <div className="alh-bulk-actions">
              <button className="btn btn-secondary btn-sm-p" disabled={bulkBusy} onClick={() => bulkAction('acknowledge')}>{t('alh.ack')}</button>
              <button className="btn btn-warning btn-sm-p"   disabled={bulkBusy} onClick={() => bulkAction('re-notify')}>{t('alh.renotify')}</button>
              <button className="btn btn-primary btn-sm-p"   disabled={bulkBusy} onClick={() => bulkAction('resolve')}>{t('alh.resolve')}</button>
              <button className="btn btn-secondary btn-sm-p" disabled={bulkBusy} onClick={() => setSelected(new Set())}>{t('alh.bulk.clear')}</button>
            </div>
          )}
        </div>
      )}

      {isOpen && alerts.length > 0 && (
        <div className="alert-list">
          {alerts.map(a => {
            const notifiedList = parseContacts(a.notified_contacts)
            return (
              <div key={a.id} className={`alert-card alert-${a.alert_level?.toLowerCase()}`}>

                <div className="alert-card-header">
                  <input
                    type="checkbox"
                    className="alh-card-check"
                    checked={selected.has(a.id)}
                    onChange={() => toggleSelect(a.id)}
                    aria-label={t('alh.bulk.selectOne')}
                  />
                  <span className="alert-level-badge" style={{ background: levelColor[a.alert_level] }}>
                    {levelLabel[a.alert_level] || a.alert_level}
                  </span>
                  <TypeChip type={a.alert_type} />
                  <strong className="alert-domain">{a.domain}</strong>
                  {(a.email_failed_count ?? 0) > 0 && (
                    <span className="alert-send-failed" title={t('alh.sendFailedTip')}>
                      <MailX size={12} /> {t('alh.sendFailed')}
                    </span>
                  )}
                  {a.days_remaining != null && (
                    <span className="alert-days">{t('alh.days', a.days_remaining)}</span>
                  )}
                </div>

                <p className="alert-message">{a.message}</p>

                <div className="alert-meta">
                  <span>{t('alh.created')} {formatDate(a.created_at)}</span>
                  {a.last_re_alert_at && (
                    <span>{t('alh.lastNotif')} {formatDate(a.last_re_alert_at)}</span>
                  )}
                </div>

                {notifiedList.length > 0 && (
                  <div className="alert-notified">
                    <span className="notified-label">{t('alh.notified')}</span>
                    {notifiedList.map((c, i) => (
                      <span key={c.email ?? `nc-${i}`} className="notified-chip" title={c.email}>
                        <UserBadge displayName={c.name} email={c.email} inline size="sm" /> <em>({c.role})</em>
                      </span>
                    ))}
                  </div>
                )}

                {a.acknowledged && (
                  <div style={{ margin: '10px 0' }}>
                    <AuditRow
                      label={t('alh.acknowledged')}
                      by={a.acknowledged_by}
                      at={a.acknowledged_at}
                      variant="ack"
                    />
                  </div>
                )}

                <div className="alert-actions">
                  {!a.acknowledged && (
                    <button className="btn btn-secondary btn-sm-p" onClick={() => ack(a.id)}>
                      {t('alh.ack')}
                    </button>
                  )}
                  <button
                    className="btn btn-warning btn-sm-p"
                    onClick={() => reNotify(a.id)}
                    disabled={notifying === a.id}
                  >
                    {notifying === a.id ? t('alh.sending') : t('alh.renotify')}
                  </button>
                  <button
                    className="btn btn-secondary btn-sm-p"
                    onClick={() => openNotifyHistory(a.id)}
                    title={t('alh.notifHistory')}
                  >
                    {t('alh.history')}
                  </button>
                  <button className="btn btn-primary btn-sm-p" onClick={() => resolve(a.id)}>
                    {t('alh.resolve')}
                  </button>
                </div>

              </div>
            )
          })}
        </div>
      )}

      {isClosed && alerts.length > 0 && (
        <div className="alert-history-wrap">
          <div className="alert-history-cards">
            {alerts.map(a => (
              <div key={a.id} className="alert-history-card">
                <div className="ahc-stripe" style={{ background: levelColor[a.alert_level] ?? '#ccc' }} />

                <div className="ahc-body">
                  <div className="ahc-top">
                    <strong className="ahc-domain">{a.domain}</strong>
                    <TypeChip type={a.alert_type} size={12} />
                    <span className="ahc-level" style={{ color: levelColor[a.alert_level] }}>
                      {levelLabel[a.alert_level] || a.alert_level}
                    </span>
                    {a.days_remaining != null && (
                      <span className="ahc-days">{t('alh.days', a.days_remaining)}</span>
                    )}
                  </div>

                  {(() => {
                    const expDate = a.alert_type === 'EXPIRY' ? alertExpiryDate(a) : null
                    const hasMeta = a.sy_team_name || a.ug_team_name || a.cert_tier != null || expDate
                    if (!hasMeta) return null
                    return (
                      <div className="ahc-meta">
                        {expDate && (
                          <span className="ahc-chip ahc-chip-expiry">
                            <Calendar size={11}/> {t('alh.expiryWas')}: <strong>{formatDate(expDate.toISOString())}</strong>
                          </span>
                        )}
                        {a.sy_team_name && (
                          <span className="ahc-chip ahc-chip-team">
                            <Users size={11}/> {t('alh.syTeam')}: <strong>{a.sy_team_name}</strong>
                          </span>
                        )}
                        {a.ug_team_name && (
                          <span className="ahc-chip ahc-chip-team">
                            <Users size={11}/> {t('alh.ugTeam')}: <strong>{a.ug_team_name}</strong>
                          </span>
                        )}
                        {a.cert_tier != null && (
                          <span className="ahc-chip ahc-chip-tier" style={{ background: tierColor(a.cert_tier) }}>
                            T{a.cert_tier}
                          </span>
                        )}
                      </div>
                    )
                  })()}

                  <div className="ahc-timeline">
                    <div className="ahc-tl-item">
                      <span className="ahc-tl-icon"><ShieldAlert size={13} /></span>
                      <div>
                        <div className="ahc-tl-label">{t('alh.tlCreated')}</div>
                        <div className="ahc-tl-val">{formatDate(a.created_at)}</div>
                      </div>
                    </div>

                    {a.acknowledged ? (
                      <div className="ahc-tl-item ahc-tl-ack">
                        <span className="ahc-tl-icon"><Check size={13} /></span>
                        <div>
                          <div className="ahc-tl-label">{t('alh.tlAck')}</div>
                          <div className="ahc-tl-val">
                            <UserBadge username={a.acknowledged_by} inline size="sm" />
                            {a.acknowledged_at && <> &nbsp;·&nbsp; {formatDate(a.acknowledged_at)}</>}
                          </div>
                        </div>
                      </div>
                    ) : (
                      <div className="ahc-tl-item ahc-tl-noack">
                        <span className="ahc-tl-icon" style={{ color: '#9ca3af' }}>—</span>
                        <div>
                          <div className="ahc-tl-label">{t('alh.tlAckLabel')}</div>
                          <div className="ahc-tl-val" style={{ color: '#9ca3af' }}>{t('alh.tlNotAcked')}</div>
                        </div>
                      </div>
                    )}

                    <div className="ahc-tl-item ahc-tl-resolve">
                      <span className="ahc-tl-icon"><CheckCircle size={13} /></span>
                      <div>
                        <div className="ahc-tl-label">{t('alh.tlResolved')}</div>
                        <div className="ahc-tl-val">
                          {a.resolved_by === 'system'
                            ? <strong>{t('alh.autoResolved')}</strong>
                            : <UserBadge username={a.resolved_by} inline size="sm" />}
                          {a.resolved_at && <> &nbsp;·&nbsp; {formatDate(a.resolved_at)}</>}
                        </div>
                      </div>
                    </div>
                  </div>

                  <div className="ahc-stats">
                    <span className="ahc-stat">
                      <Mail size={12}/> {t('alh.mailsSent', a.email_sent_count ?? 0)}
                    </span>
                    <span className={`ahc-stat${(a.email_failed_count ?? 0) > 0 ? ' ahc-stat-failed' : ''}`}>
                      <MailX size={12}/> {t('alh.mailsFailed', a.email_failed_count ?? 0)}
                    </span>
                    {a.resolved_at && a.created_at && (
                      <span className="ahc-stat ahc-stat-duration">
                        <Clock size={12}/> {t('alh.openDuration')}: <strong>{formatDuration(a.resolved_at, a.created_at)}</strong>
                      </span>
                    )}
                  </div>

                  <div className="ahc-footer">
                    <button
                      className="btn btn-secondary btn-sm-p"
                      onClick={() => openNotifyHistory(a.id)}
                    >
                      {t('alh.notifHistory')}
                    </button>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {!loading && (
        <PaginationBar
          page={page + 1} totalPages={totalPages} totalItems={total}
          rangeStart={total === 0 ? 0 : page * pageSize + 1}
          rangeEnd={Math.min((page + 1) * pageSize, total)}
          pageSize={pageSize}
          onPageChange={p => setPage(p - 1)}
          onPageSizeChange={n => { setPageSize(n); writePageSize('alert-history', n) }}
        />
      )}

      {notifyModal && (
        <NotifyResultModal
          alertId={notifyModal.alertId}
          alertInfo={notifyModal.alertInfo}
          currentResult={notifyModal.result}
          onClose={() => setNotifyModal(null)}
        />
      )}
      {renotifyModal && (
        <ReNotifyConfirmModal
          domain={renotifyModal.domain}
          recipients={renotifyModal.recipients}
          sending={renotifySending}
          onSend={sendReNotify}
          onClose={() => { if (!renotifySending) setRenotifyModal(null) }}
        />
      )}
    </div>
  )
}
