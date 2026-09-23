import { Fragment, useState, useEffect, useCallback, useRef } from 'react'
import { ResponsiveContainer, AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip } from 'recharts'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useUrlQuerySync, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import { readPageSize, writePageSize } from '../../hooks/usePagination.js'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { useIsWide } from '../../hooks/useIsWide.js'
import { History, ChevronRight } from 'lucide-react'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import { ProgressBar, LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import AuditDetailPanel from './audit/AuditDetailPanel.jsx'
import { eventClass, eventLabel, parseDetail, actionSentence } from './audit/auditFormat.js'

/**
 * Sunucu kataloğu gelmezse kullanılacak YEDEK liste.
 *
 * <p>Bu liste eskiden tek kaynaktı ve sürüklenmişti: 162 türün yalnız 32'sini tanıyordu —
 * bütün `MAINTENANCE_*`, `SQL_EXECUTE`, `MONITOR_TRIGGER` ve 12 `WEEKLY_REPORT_*` türü
 * filtrede hiç yoktu, yani o olaylar arayüzden ARANAMIYORDU. Artık `/audit/event-types`
 * kanonik kaynak; bu liste yalnız uç düşerse filtrenin tamamen boş kalmaması içindir.
 */
const EVENT_TYPES_FALLBACK = [
  'LOGIN', 'LOGIN_FAILED', 'LOGOUT',
  'DOMAIN_ADD', 'DOMAIN_DELETE', 'DOMAIN_EDIT',
  'TEAM_CREATE', 'TEAM_UPDATE', 'TEAM_DELETE',
  'USER_CREATE', 'USER_UPDATE', 'USER_DELETE', 'USER_UNLOCK',
  'PERMISSION_UPDATE', 'PERMISSION_RESET',
  'MONITOR_CREATE', 'MONITOR_UPDATE', 'MONITOR_DELETE',
  'THRESHOLD_CREATE', 'THRESHOLD_UPDATE', 'CONTACT_CREATE', 'CONTACT_UPDATE', 'CONTACT_DELETE',
  'GUIDE_LINK_CREATE', 'GUIDE_LINK_UPDATE', 'GUIDE_LINK_DELETE',
  'ACCESS_DENIED', 'AUTH_REQUIRED', 'AUDIT_EXPORT',
  'SYSTEM_STARTUP', 'SYSTEM_SHUTDOWN',
]

const OUTCOMES = ['SUCCESS', 'FAILURE', 'BLOCKED']

/**
 * Sonuç etiketleri. Anahtarlar DÜZ string olarak `t(...)`'e geçer (şablon literali değil) —
 * `i18n-used-keys` kapısı ancak böyle görebiliyor; kart etiketi hatası tam da bu yüzden kaçmıştı.
 *
 * <p>`event_type` / `resource_type` / `actor_role` bilerek ham bırakıldı: onların sözlüğü
 * sunucudaki olay kataloğuyla birlikte doğacak (tek doğruluk kaynağı orada), burada ikinci bir
 * kopya kurmak sonradan uzlaştırılması gereken bir borç olurdu.
 */
function outcomeLabels(t) {
  return {
    SUCCESS: t('audit.outcome.success'),
    FAILURE: t('audit.outcome.failure'),
    BLOCKED: t('audit.outcome.blocked'),
  }
}

// Hazır görünümler (preset views) — bir tıkla sık denetim senaryoları.
const PRESETS = [
  { key: 'security',  filter: { outcome: 'BLOCKED', eventType: '' } },
  { key: 'authz',     filter: { eventType: 'PERMISSION_UPDATE,PERMISSION_RESET,USER_UPDATE,TEAM_UPDATE', outcome: '' } },
  { key: 'failed',    filter: { eventType: 'LOGIN_FAILED', outcome: '' } },
  { key: 'config',    filter: { eventType: 'THRESHOLD_CREATE,THRESHOLD_UPDATE,CONTACT_CREATE,CONTACT_UPDATE,CONTACT_DELETE,GENERAL_SETTINGS_SAVE,SMTP_SETTINGS_SAVE,STORM_SETTINGS_SAVE', outcome: '' } },
]

function isoMinus(seconds) {
  return new Date(Date.now() - seconds * 1000).toISOString().slice(0, 19)
}



// Basit dağılım çubuğu (recharts'sız, hafif) — özet paneli için.
function DistBar({ title, items }) {
  if (!items || !items.length) return null
  const max = Math.max(...items.map(i => i.count), 1)
  return (
    <div className="audit-dist">
      <div className="audit-dist-title">{title}</div>
      {items.slice(0, 6).map(i => (
        <div key={i.key} className="audit-dist-row">
          <span className="audit-dist-label" title={i.key}>{i.key || '—'}</span>
          {/* decorative: sayı hemen sağda görünüyor, çubuk onun görsel eşi. */}
          <ProgressBar value={i.count} max={max} size="sm" decorative className="audit-dist-bar" />
          <span className="audit-dist-count">{i.count}</span>
        </div>
      ))}
    </div>
  )
}

/**
 * İstatistik kartları. `labelKey` AÇIKÇA yazılır — üretilen anahtar (`audit.${card.key}`) DEĞİL.
 *
 * <p>Neden: `key` alanları sunucunun snake_case istatistik adlarıydı (`total_24h`), sözlükte ise
 * camelCase karşılıkları var (`audit.total24h`). Şablon literaliyle üretilen anahtar hiçbir
 * sözlükte bulunmadığı için `useT` anahtarın KENDİSİNİ döndürüyor ve altı kartın etiketi ekranda
 * ham `audit.total_24h` olarak yazıyordu — hem TR hem EN'de. `i18n-used-keys` kapısı şablon
 * literallerini taramadığı için de kaçmıştı. Düz string, kapının yeniden görebilmesini sağlar.
 */
const CARD_DEFS = [
  { key: 'total_24h',        labelKey: 'audit.total24h',      statKey: 'total_24h',         warn: false, filter: () => ({ since: isoMinus(86400),       anomalyOnly: false, eventType: '' }) },
  { key: 'anomalies_24h',    labelKey: 'audit.anomalies24h',  statKey: 'anomalies_24h',     warn: true,  filter: () => ({ since: isoMinus(86400),       anomalyOnly: true,  eventType: '' }) },
  { key: 'failed_logins_24h',labelKey: 'audit.failLogins24h', statKey: 'failed_logins_24h', warn: true,  filter: () => ({ since: isoMinus(86400),       anomalyOnly: false, eventType: 'LOGIN_FAILED' }) },
  { key: 'total_7d',         labelKey: 'audit.total7d',       statKey: 'total_7d',          warn: false, filter: () => ({ since: isoMinus(7*86400),     anomalyOnly: false, eventType: '' }) },
  { key: 'anomalies_7d',     labelKey: 'audit.anomalies7d',   statKey: 'anomalies_7d',      warn: true,  filter: () => ({ since: isoMinus(7*86400),     anomalyOnly: true,  eventType: '' }) },
  { key: 'failed_logins_7d', labelKey: 'audit.failLogins7d',  statKey: 'failed_logins_7d',  warn: true,  filter: () => ({ since: isoMinus(7*86400),     anomalyOnly: false, eventType: 'LOGIN_FAILED' }) },
]

/**
 * Anomali çipleri. Renk satır-içi hex DEĞİL sınıf üzerinden gelir (`an-<bayrak>`): satır-içi stil
 * `cssTokens` kapısına görünmüyordu ve koyu temada hiç uyarlanmıyordu. Etiket de artık ham
 * `OFF HOURS` değil — `dev.flag.*` çevirileri sözlükte YILLARDIR vardı, kullanılmıyordu.
 */
function AnomalyChips({ flags, t }) {
  if (!flags) return null
  return (
    <span className="audit-anomalies">
      {flags.split(',').filter(Boolean).map(f => {
        const flag = f.trim()
        // useT bilinmeyen anahtarda ANAHTARIN KENDİSİNİ döndürür; sunucu ileride yeni bir bayrak
        // eklerse ekranda "dev.flag.XYZ" yazmasın diye okunur biçime düşülür.
        const label = t(`dev.flag.${flag}`)
        return (
          <span key={flag} className={`audit-anomaly-chip an-${flag.toLowerCase()}`} title={flag}>
            {label === `dev.flag.${flag}` ? flag.replace(/_/g, ' ') : label}
          </span>
        )
      })}
    </span>
  )
}

function StatCard({ label, value, warn, active, onClick }) {
  const hasWarn = warn && value > 0
  return (
    <button
      className={`audit-stat-card${hasWarn ? ' warn' : ''}${active ? ' active' : ''}`}
      onClick={onClick}
      title={label}
    >
      <div className="audit-stat-value">{value ?? '—'}</div>
      <div className="audit-stat-label">{label}</div>
    </button>
  )
}

const EMPTY_FILTERS = { actor: '', eventType: '', outcome: '', since: '', until: '', anomalyOnly: false,
  resourceType: '', resourceId: '', ip: '', q: '' }


// ── URL senkronizasyonu: filtreler `a_` önekli query paramlarında yaşar (paylaşılabilir/derin-link) ──
const URL_PREFIX = 'a_'
function readUrlFilters() {
  const p = new URLSearchParams(window.location.search)
  const f = { ...EMPTY_FILTERS }
  for (const k of Object.keys(EMPTY_FILTERS)) {
    const v = p.get(URL_PREFIX + k)
    if (v != null) f[k] = (k === 'anomalyOnly') ? v === '1' : v
  }
  return f
}
function writeUrlFilters(f) {
  const p = new URLSearchParams(window.location.search)
  for (const k of Object.keys(EMPTY_FILTERS)) {
    const v = f[k]
    const has = (k === 'anomalyOnly') ? !!v : (v != null && v !== '')
    if (has) p.set(URL_PREFIX + k, (k === 'anomalyOnly') ? '1' : String(v))
    else p.delete(URL_PREFIX + k)
  }
  const qs = p.toString()
  window.history.replaceState(null, '', qs ? `${window.location.pathname}?${qs}` : window.location.pathname)
}

// ── Kaydedilebilir görünümler (localStorage) ──
const SAVED_VIEWS_KEY = 'sm.audit.savedViews'
/** Önek konvansiyonundan önce kullanılan ad — okunur, taşınır, sonra silinir (bir kerelik göç). */
const SAVED_VIEWS_KEY_LEGACY = 'auditSavedViews'

function loadSavedViews() {
  try {
    const cur = JSON.parse(localStorage.getItem(SAVED_VIEWS_KEY))
    if (Array.isArray(cur)) return cur
    // Göç: kullanıcı kaydettiği görünümleri anahtar yeniden adlandırıldı diye kaybetmemeli.
    const old = JSON.parse(localStorage.getItem(SAVED_VIEWS_KEY_LEGACY))
    if (Array.isArray(old) && old.length) {
      localStorage.setItem(SAVED_VIEWS_KEY, JSON.stringify(old))
      localStorage.removeItem(SAVED_VIEWS_KEY_LEGACY)
      return old
    }
    return []
  } catch { return [] }   // kota/gizli mod/bozuk JSON — görünümsüz devam
}
function persistSavedViews(views) {
  try { localStorage.setItem(SAVED_VIEWS_KEY, JSON.stringify(views)) } catch { /* kota/gizli mod — sessiz geç */ }
}

// ── Zaman-yoğunluğu grafiği (recharts): son 14 günün günlük olay hacmi ──
function TimeDensityChart({ data, title }) {
  if (!data || data.length < 2) return null
  const rows = data.map(d => ({ day: (d.key || '').slice(5), count: d.count }))   // MM-DD etiketi
  return (
    <div className="audit-density">
      <div className="audit-dist-title">{title}</div>
      <ResponsiveContainer width="100%" height={120}>
        <AreaChart data={rows} margin={{ top: 6, right: 8, bottom: 0, left: -18 }}>
          <defs>
            <linearGradient id="auditDensityFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="var(--primary)" stopOpacity={0.35} />
              <stop offset="100%" stopColor="var(--primary)" stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
          <XAxis dataKey="day" tick={{ fontSize: 10, fill: 'var(--text-light)' }} interval="preserveStartEnd" />
          <YAxis allowDecimals={false} width={34} tick={{ fontSize: 10, fill: 'var(--text-light)' }} />
          <Tooltip contentStyle={{ fontSize: 12, borderRadius: 8 }} />
          <Area type="monotone" dataKey="count" stroke="var(--primary)" strokeWidth={2} fill="url(#auditDensityFill)" />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}

export default function AuditLogViewer() {
  const t = useT()
  const outcomeText = outcomeLabels(t)
  const [eventCatalog, setEventCatalog] = useState(null)   // [{type, category, count}] | null
  const [stats, setStats]         = useState(null)
  const [rows, setRows]           = useState([])
  const [total, setTotal]         = useState(0)
  const [page, setPage]           = useState(0)
  const [size, setSize]           = useState(() => readUrlInt('ps', null) || readPageSize('audit-log'))
  const [loading, setLoading]     = useState(false)
  const [activeCard, setActiveCard] = useState(null)
  const [filters, setFilters]     = useState(readUrlFilters)   // derin-link: URL'den başlat
  const [selectedId, setSelectedId] = useState(() => readUrlInt(URL_PREFIX + "sel", null))
  const [integrity, setIntegrity] = useState(null)
  const [activePreset, setActivePreset] = useState(null)
  const [savedViews, setSavedViews] = useState(loadSavedViews)
  const [viewName, setViewName]   = useState('')
  const [timeline, setTimeline]   = useState(null)     // {type, id, rows, loading} | null
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [loadError, setLoadError] = useState(false)

  // Yan panel mi modal mi: TEK JS esigi. Yerlesim CSS-onceliklidir; bu esik yalnizca
  // "hangi bilesen render edilecek" sorusunu cevaplar (ikisi ayni anda DOM/da olmamali).
  const wide = useIsWide(1100)
  const selectedRowRef = useRef(null)

  const selectedRow = rows.find(r => r.id === selectedId) || null
  const hasActiveFilters = Object.entries(filters)
    .some(([, v]) => (typeof v === "boolean" ? v : v !== "" && v != null))

  /** Satir secimi — URL/de de yasar ki paylasilan bir baglanti dogru olayi acsin. */
  function selectRow(id) {
    setSelectedId(prev => (prev === id ? null : id))
  }

  /**
   * Tablo klavye gezinmesi. Odak SECILI satirdadir (gezici tabindex): Tab tabloyu tek
   * durakta gecer, ok tuslari satir degistirir. Sayfa sinirinda sonraki/onceki sayfaya
   * gecilmez (sunucu sayfalamasi) — sinirda durur, kullanici sayfalayiciyi kullanir.
   */
  function onRowsKeyDown(e) {
    // Satır-içi panel <tbody> ALTINDA yaşıyor: içindeki bir alanda ok tuşuna basılınca olay
    // buraya kabarır ve kullanıcının okuduğu kayıt altından değişirdi.
    if (e.target !== e.currentTarget && e.target.closest?.('.aud-inline')) return
    const idx = rows.findIndex(r => r.id === selectedId)
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault()
      if (!rows.length) return
      const next = idx < 0 ? 0 : Math.min(rows.length - 1, Math.max(0, idx + (e.key === "ArrowDown" ? 1 : -1)))
      setSelectedId(rows[next].id)
    } else if (e.key === "Home") {
      e.preventDefault(); if (rows.length) setSelectedId(rows[0].id)
    } else if (e.key === "End") {
      e.preventDefault(); if (rows.length) setSelectedId(rows[rows.length - 1].id)
    } else if (e.key === "Escape") {
      setSelectedId(null)
    }
  }

  function applyPreset(p) {
    setActiveCard(null)
    if (activePreset === p.key) { setActivePreset(null); clearFilters(); return }
    setActivePreset(p.key)
    const next = { ...EMPTY_FILTERS, ...p.filter }
    setFilters(next)
    writeUrlFilters(next)
    loadStats()
    loadLogs(0, next)
  }

  // ── Kaydedilebilir görünümler ──
  function saveCurrentView() {
    const name = viewName.trim()
    if (!name) return
    const next = [...savedViews.filter(v => v.name !== name), { name, filters }]
    setSavedViews(next); persistSavedViews(next); setViewName('')
  }
  function applySavedView(v) {
    setActiveCard(null); setActivePreset(null)
    const next = { ...EMPTY_FILTERS, ...v.filters }
    setFilters(next); writeUrlFilters(next); loadStats(); loadLogs(0, next)
  }
  function deleteSavedView(name, e) {
    e.stopPropagation()
    const next = savedViews.filter(v => v.name !== name)
    setSavedViews(next); persistSavedViews(next)
  }

  // ── Kaynak geçmişi dikey zaman-çizelgesi (drawer) ──
  function openTimeline(type, id) {
    if (!id) return
    setTimeline({ type, id, rows: [], loading: true })
    api.admin.getAuditResourceHistory(type || '', id, 100)
      .then(r => setTimeline(tl => tl && tl.id === id ? { ...tl, rows: r?.success ? r.data : [], loading: false } : tl))
      .catch(() => setTimeline(tl => tl && tl.id === id ? { ...tl, rows: [], loading: false } : tl))
  }

  function checkIntegrity() {
    setIntegrity({ loading: true })
    api.admin.getAuditIntegrity()
      .then(r => setIntegrity(r?.success ? r.data : { error: true }))
      .catch(() => setIntegrity({ error: true }))
  }

  function exportAudit(format) {
    window.open(api.admin.auditExportUrl(format, filters), '_blank')
  }

  const loadStats = useCallback(() => {
    api.admin.getAuditStats().then(r => { if (r?.success) setStats(r.data) })
  }, [])

  const loadLogs = useCallback((p = 0, f = filters, sz = size) => {
    setLoading(true)
    // Hata ARTIK SESSIZ DEGIL: eskiden `r.success` false ise hicbir sey olmuyordu ve
    // kullanici bos tabloya bakip "kayit yok" saniyordu.
    api.admin.getAuditLogs({ page: p, size: sz, ...f }).then(r => {
      if (r?.success) { setRows(r.data); setTotal(r.total); setPage(r.page); setLoadError(false) }
      else setLoadError(true)
    }).catch(() => setLoadError(true)).finally(() => setLoading(false))
  }, [filters, size])

  useEffect(() => { loadStats(); loadLogs(readUrlInt('page', 1) - 1) }, [])

  // Olay türü kataloğu — filtre listesinin kaynağı. Hata sessiz YUTULUR ama sonucu görünür:
  // katalog gelmezse yedek listeye düşülür, filtre çalışmaya devam eder.
  useEffect(() => {
    let alive = true
    api.admin.getAuditEventTypes?.()
      .then(r => { if (alive && r?.success && Array.isArray(r.data)) setEventCatalog(r.data) })
      .catch(() => { /* yedek liste devrede */ })
    return () => { alive = false }
  }, [])

  /**
   * Filtre seçenekleri: katalog varsa kategori BAŞLIKLARIYLA gruplanır (162 düz seçenek
   * kullanılamaz), hiç kaydı olmayan türler sayıyla işaretlenir ki kullanıcı baştan boş
   * döneceğini bildiği bir filtreyi uygulamasın.
   */
  const eventTypeOptions = eventCatalog
    ? eventCatalog.map(e => ({
        value: e.type,
        label: e.count ? `${e.type} (${e.count})` : e.type,
        group: e.category,
      }))
    : EVENT_TYPES_FALLBACK.map(et => ({ value: et, label: et }))

  // Canlı tazeleme: açıkken 15sn'de bir mevcut sayfayı + özeti yeniler (sekme gizliyken duraklar).
  // Secim degisince odak ve gorunurluk secili satira tasinir: ok tuslariyla gezerken
  // satirin ekran disina kaymasi gezinmeyi imkansiz kiliyordu.
  useEffect(() => {
    if (!selectedId || !selectedRowRef.current) return
    selectedRowRef.current.focus({ preventScroll: true })
    selectedRowRef.current.scrollIntoView({ block: 'nearest' })
  }, [selectedId])

  useVisibleInterval(() => { loadLogs(page, filters); loadStats() }, autoRefresh ? 15000 : 0, false)

  function handleCardClick(card) {
    if (activeCard === card.key) {
      // second click → deselect, reset to empty
      setActiveCard(null)
      const next = EMPTY_FILTERS
      setFilters(next)
      writeUrlFilters(next)
      loadLogs(0, next)
    } else {
      setActiveCard(card.key)
      const extra = card.filter()
      const next = { ...EMPTY_FILTERS, ...extra }
      setFilters(next)
      writeUrlFilters(next)
      loadLogs(0, next)
    }
  }

  function applyFilters() {
    setActiveCard(null)
    setActivePreset(null)
    writeUrlFilters(filters)
    loadStats()
    loadLogs(0, filters)
  }

  function clearFilters() {
    setActiveCard(null)
    setActivePreset(null)
    setFilters(EMPTY_FILTERS)
    writeUrlFilters(EMPTY_FILTERS)
    loadLogs(0, EMPTY_FILTERS)
    loadStats()
  }

  /** Kaynak/aktör hücresine tıklama → o kaynağın/aktörün geçmişine filtrele (drill-down). */
  function drillResource(type, id) {
    const next = { ...EMPTY_FILTERS, resourceType: type || '', resourceId: id || '' }
    setActiveCard(null); setActivePreset(null); setFilters(next); writeUrlFilters(next); loadLogs(0, next)
  }

  const totalPages = Math.ceil(total / size)

  // Paylaşılabilir URL: sayfa/boyut (URL'de HEP 1-tabanlı). İlk yükleme page paramını dikkate alır (aşağıdaki effect).
  // Secili satir URL/de: paylasilan bir baglanti dogru olayi acar (a_ ailesiyle tutarli).
  useUrlQuerySync({
    [URL_PREFIX + 'sel']: selectedId || null,
    page: page > 0 ? page + 1 : null,
    ps: (size !== 50 || page > 0) ? size : null,
  })

  return (
    <div className="audit-viewer">
      {/* Stats cards */}
      {stats && (
        <div className="audit-stats-row">
          {CARD_DEFS.map(card => (
            <StatCard
              key={card.key}
              label={t(card.labelKey)}
              value={stats[card.statKey]}
              warn={card.warn}
              active={activeCard === card.key}
              onClick={() => handleCardClick(card)}
            />
          ))}
        </div>
      )}

      {/* Zaman-yoğunluğu grafiği (son 14 gün) */}
      {stats?.by_day_14d?.length >= 2 && (
        <TimeDensityChart data={stats.by_day_14d} title={t('audit.densityTitle')} />
      )}

      {/* Dağılım paneli (özet: en sık olay türleri / sonuç dağılımı / en aktif kullanıcılar) */}
      {stats && (stats.by_event_type_7d?.length > 0 || stats.top_actors_7d?.length > 0) && (
        <div className="audit-dist-panel">
          <DistBar title={t('audit.distEvents')} items={stats.by_event_type_7d} />
          <DistBar title={t('audit.distOutcomes')} items={stats.by_outcome_7d} />
          <DistBar title={t('audit.distActors')} items={stats.top_actors_7d} />
        </div>
      )}

      {/* Preset görünümler + bütünlük + dışa aktarma */}
      <div className="audit-toolbar">
        <div className="audit-presets">
          {PRESETS.map(p => (
            <button key={p.key}
              className={`audit-preset-btn${activePreset === p.key ? ' active' : ''}`}
              onClick={() => applyPreset(p)}>
              {t('audit.preset.' + p.key)}
            </button>
          ))}
        </div>
        <div className="audit-toolbar-actions">
          {integrity && !integrity.loading && (
            <span className={`audit-integrity-badge ${integrity.ok ? 'ok' : 'bad'}`}
              title={integrity.error ? '' : `${t('audit.integrityChecked')}: ${integrity.checked}`}>
              {integrity.error ? t('audit.integrityErr')
                : integrity.ok ? `✓ ${t('audit.integrityOk')} (${integrity.checked})`
                : `⚠ ${t('audit.integrityBroken')} #${integrity.broken_seq}`}
            </span>
          )}
          <button
            className={`audit-filter-btn audit-live-btn${autoRefresh ? ' active' : ''}`}
            onClick={() => setAutoRefresh(a => !a)}
            title={t('audit.autoRefreshHint')}>
            <span className={`audit-live-dot${autoRefresh ? ' on' : ''}`} />{t('audit.autoRefresh')}
          </button>
          <button className="audit-filter-btn" onClick={checkIntegrity}>{t('audit.verifyIntegrity')}</button>
          <button className="audit-filter-btn" onClick={() => exportAudit('csv')}>CSV</button>
          <button className="audit-filter-btn" onClick={() => exportAudit('json')}>JSON</button>
        </div>
      </div>

      {/* Kaydedilebilir görünümler */}
      <div className="audit-saved-bar">
        <div className="audit-saved-views">
          {savedViews.map(v => (
            <button key={v.name} className="audit-view-chip" onClick={() => applySavedView(v)} title={v.name}>
              <span className="audit-view-name">{v.name}</span>
              <span className="audit-view-del" onClick={e => deleteSavedView(v.name, e)}
                    role="button" tabIndex={0}
                    title={t('audit.deleteView')} aria-label={`${v.name} — ${t('audit.deleteView')}`}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); deleteSavedView(v.name, e) } }}>×</span>
            </button>
          ))}
        </div>
        <div className="audit-save-view">
          <input
            className="audit-filter-input"
            placeholder={t('audit.viewNamePh')}
            value={viewName}
            onChange={e => setViewName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') saveCurrentView() }}
          />
          <button className="audit-filter-btn" disabled={!viewName.trim()} onClick={saveCurrentView}>
            {t('audit.saveView')}
          </button>
        </div>
      </div>

      {/* Filters */}
      <div className="audit-filters">
        <input
          className="audit-filter-input"
          placeholder={t('audit.searchPh')}
          value={filters.q}
          onChange={e => setFilters(f => ({ ...f, q: e.target.value }))}
          onKeyDown={e => { if (e.key === 'Enter') applyFilters() }}
        />
        <input
          className="audit-filter-input"
          placeholder={t('audit.filterActor')}
          value={filters.actor}
          onChange={e => setFilters(f => ({ ...f, actor: e.target.value }))}
        />
        <input
          className="audit-filter-input"
          placeholder={t('audit.filterResourceType')}
          value={filters.resourceType}
          onChange={e => setFilters(f => ({ ...f, resourceType: e.target.value }))}
        />
        <input
          className="audit-filter-input"
          placeholder={t('audit.filterIp')}
          value={filters.ip}
          onChange={e => setFilters(f => ({ ...f, ip: e.target.value }))}
        />
        <SearchableSelect
          value={filters.eventType}
          onChange={v => setFilters(f => ({ ...f, eventType: v }))}
          placeholder={t('audit.allEvents')}
          options={[
            { value: '', label: t('audit.allEvents') },
            ...eventTypeOptions,
          ]}
        />
        <SearchableSelect
          value={filters.outcome}
          onChange={v => setFilters(f => ({ ...f, outcome: v }))}
          placeholder={t('audit.allOutcomes')}
          options={[
            { value: '', label: t('audit.allOutcomes') },
            ...OUTCOMES.map(o => ({ value: o, label: outcomeText[o] || o })),
          ]}
        />
        <input
          type="datetime-local"
          className="audit-filter-input"
          value={filters.since ? filters.since.replace(' ', 'T') : ''}
          onChange={e => setFilters(f => ({ ...f, since: e.target.value ? e.target.value.slice(0, 19) : '' }))}
        />
        <input
          type="datetime-local"
          className="audit-filter-input"
          value={filters.until ? filters.until.replace(' ', 'T') : ''}
          onChange={e => setFilters(f => ({ ...f, until: e.target.value ? e.target.value.slice(0, 19) : '' }))}
        />
        <label className="audit-filter-check">
          <input
            type="checkbox"
            checked={!!filters.anomalyOnly}
            onChange={e => setFilters(f => ({ ...f, anomalyOnly: e.target.checked }))}
          />
          {t('audit.anomalyOnly')}
        </label>
        <button className="audit-filter-btn primary" onClick={applyFilters}>{t('audit.apply')}</button>
        <button className="audit-filter-btn" onClick={clearFilters}>{t('audit.clear')}</button>
      </div>

      {/* Table */}
      {/* ── Ana-detay — iki yerleşim, TEK içerik ──────────────────────────
          Geniş ekranda yapışkan yan panel: tablo yerinde kalır, satırdan satıra geçerken
          sağdaki içerik değişir. Dar ekranda yan panele yer yok — ayrıntı seçili satırın
          HEMEN ALTINDA açılır. Diyalog kullanılmıyor: modal ayrıntıyı bağlamından koparıyor,
          kullanıcı hangi satıra baktığını kaybediyordu.
          İkisi AYNI anda render EDİLMEZ (ekran okuyucu aynı içeriği iki kez okumasın). */}
      <div className="aud-split" data-open={selectedRow ? 'true' : 'false'}
        data-wide={wide ? 'true' : 'false'}>
        <div className="aud-list">
          {loadError && (
            <AlertBanner tone="danger" title={t('audit.loadErrorTitle')}
              actions={<button className="btn btn-sm" onClick={() => loadLogs(page)}>{t('audit.retry')}</button>}>
              {t('audit.loadErrorBody')}
            </AlertBanner>
          )}

          <div className="audit-table-wrap">
            {loading && <LoadingBlock label={t('app.loading')} className="audit-loading" />}
            <table className="audit-table">
              <thead>
                <tr>
                  <th>{t('audit.colTime')}</th>
                  <th>{t('audit.colEvent')}</th>
                  <th>{t('audit.colActor')}</th>
                  <th data-col="ip">{t('audit.colIp')}</th>
                  <th data-col="browser">{t('audit.colBrowser')}</th>
                  <th data-col="geo">{t('audit.colGeo')}</th>
                  <th>{t('audit.colResource')}</th>
                  <th>{t('audit.colOutcome')}</th>
                  <th data-col="anomalies">{t('audit.colAnomalies')}</th>
                  <th className="aud-col-chevron"><span className="sr-only">{t('audit.colDetail')}</span></th>
                </tr>
              </thead>
              <tbody onKeyDown={onRowsKeyDown}>
                {rows.map(row => {
                  const { changes } = parseDetail(row)
                  const selected = selectedId === row.id
                  const inline = selected && !wide
                  return (
                    <Fragment key={row.id}>
                    <tr ref={selected ? selectedRowRef : null}
                        aria-expanded={inline || undefined}
                        className={`aud-row${row.anomaly_flags ? ' audit-row-anomaly' : ''}${selected ? ' is-selected' : ''}`}
                        aria-selected={selected}
                        tabIndex={selected ? 0 : -1}
                        onClick={() => selectRow(row.id)}>
                      <td className="audit-cell-time">{formatDate(row.event_time)}</td>
                      <td>
                        {/* Ham tür `title`'da KALIR: denetçi ham kodla filtreler ve kopyalar. */}
                        <span className={`audit-event-badge ${eventClass(row.event_type)}`} title={row.event_type}>
                          {eventLabel(row.event_type, t)}
                        </span>
                      </td>
                      <td>
                        <div>{row.actor ? <UserBadge username={row.actor} inline size="sm" /> : '—'}</div>
                        {row.actor_role && <div className="audit-sub">{row.actor_role}</div>}
                      </td>
                      <td className="audit-mono" data-col="ip">{row.ip_address || '—'}</td>
                      <td data-col="browser">
                        {row.ua_summary
                          ? <span title={row.user_agent}>{row.ua_summary}</span>
                          : (row.user_agent ? <span title={row.user_agent}>—</span> : '—')}
                      </td>
                      <td data-col="geo">
                        {row.ip_country && (
                          <div>{row.ip_country}{row.ip_city ? `, ${row.ip_city}` : ''}</div>
                        )}
                        {row.ip_org && <div className="audit-sub">{row.ip_org}</div>}
                      </td>
                      <td>
                        {row.resource_id ? (
                          <span className="audit-resource-cell">
                            <button className="audit-link" title={t('audit.resourceHistory')}
                              onClick={e => { e.stopPropagation(); drillResource(row.resource_type, row.resource_id) }}>
                              {row.resource_type && <span className="audit-sub">{row.resource_type}: </span>}{row.resource_id}
                            </button>
                            <button className="audit-timeline-btn" title={t('audit.resourceTimeline')}
                              onClick={e => { e.stopPropagation(); openTimeline(row.resource_type, row.resource_id) }}>
                              <History size={13} />
                            </button>
                          </span>
                        ) : (row.resource_type ? <span className="audit-sub">{row.resource_type}</span> : '—')}
                      </td>
                      <td>
                        <span className={`audit-outcome-badge ${row.outcome?.toLowerCase()}`} title={row.outcome || ''}>
                          {outcomeText[row.outcome] || row.outcome || '—'}
                        </span>
                        {row.failure_reason && <div className="audit-sub">{row.failure_reason}</div>}
                      </td>
                      <td data-col="anomalies"><AnomalyChips flags={row.anomaly_flags} t={t} /></td>
                      <td className="aud-col-chevron">
                        {changes && <span className="aud-change-count">{changes.length}</span>}
                        <ChevronRight size={14} className="aud-row-chevron" />
                      </td>
                    </tr>
                    {inline && (
                      <tr className="aud-inline">
                        <td colSpan={10}>
                          <AuditDetailPanel row={row} onClose={() => setSelectedId(null)}
                            onOpenTimeline={openTimeline} onDrill={drillResource} />
                        </td>
                      </tr>
                    )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>

            {/* Boş durumun İKİ hâli ayrı: filtre yüzünden mi boş, gerçekten kayıt yok mu? */}
            {rows.length === 0 && !loading && !loadError && (
              hasActiveFilters ? (
                <StatusBlock tone="neutral" title={t('audit.emptyFiltered')}
                  description={t('audit.emptyFilteredHint')}
                  actions={<button className="btn btn-sm" onClick={clearFilters}>{t('audit.clear')}</button>} />
              ) : (
                <StatusBlock tone="neutral" title={t('audit.empty')} />
              )
            )}
          </div>

          <PaginationBar
            page={page + 1} totalPages={totalPages || 1} totalItems={total}
            rangeStart={total === 0 ? 0 : page * size + 1}
            rangeEnd={Math.min((page + 1) * size, total)}
            pageSize={size}
            onPageChange={p => loadLogs(p - 1)}
            onPageSizeChange={n => { setSize(n); writePageSize('audit-log', n); loadLogs(0, filters, n) }}
          />
        </div>

        {/* Yan panel bir DİYALOG DEĞİLDİR: odak tabloda kalmalı ki kullanıcı ok tuşlarıyla
            gezinmeye devam edebilsin. Bu yüzden focus trap/aria-modal YOK. */}
        {wide && selectedRow && (
          <aside className="aud-detail" role="complementary" aria-label={t('audit.detailPanel')}>
            <AuditDetailPanel row={selectedRow} onClose={() => setSelectedId(null)}
              onOpenTimeline={openTimeline} onDrill={drillResource} />
          </aside>
        )}
      </div>

      {/* Kaynak geçmişi — ModalShell tabanlı. Eskiden elle kurulmuş bir overlay'di: `role`
          yok, `aria-modal` yok, ESC kapatmıyor, focus trap yok. ModalShell bu sözleşmenin
          tamamını zaten taşıyor (DialogAccessibility kapısı da onu bekliyor). */}
      {timeline && (
        <ModalShell open onClose={() => setTimeline(null)} size="md" scrollBody
          title={t('audit.timelineTitle', `${timeline.type ? timeline.type + ':' : ''}${timeline.id}`)}>
          {timeline.loading ? (
            <LoadingBlock label={t('app.loading')} className="audit-loading" />
          ) : timeline.rows.length === 0 ? (
            <StatusBlock tone="neutral" title={t('audit.timelineEmpty')} />
          ) : (
            <ol className="audit-timeline-list">
              {timeline.rows.map(e => {
                const d = parseDetail(e).changes
                return (
                  <li key={e.id} className="audit-timeline-item">
                    <span className={`audit-timeline-dot ${eventClass(e.event_type)}`} />
                    <div className="audit-timeline-body">
                      <div className="audit-timeline-row1">
                        <span className={`audit-event-badge ${eventClass(e.event_type)}`} title={e.event_type}>
                          {eventLabel(e.event_type, t)}
                        </span>
                        <span className="audit-timeline-actor">{e.actor || t('audit.systemActor')}</span>
                        <span className="audit-timeline-time">{formatDate(e.event_time)}</span>
                      </div>
                      <div className="audit-timeline-sentence">{actionSentence(e, t, d)}</div>
                    </div>
                  </li>
                )
              })}
            </ol>
          )}
        </ModalShell>
      )}
    </div>
  )
}

