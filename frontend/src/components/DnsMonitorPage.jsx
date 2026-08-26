import { useState, useEffect, useCallback, useMemo } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import { CheckNowButton, CheckRunningStrip } from './ui/CheckRunning.jsx'
import { monitorDeepLink } from '../utils/monitorDeepLink.js'
import PaginationBar from './ui/PaginationBar.jsx'
import { useToast } from './ui/Toast.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import { Play, Pencil, Copy, Trash2, Plus, ChevronDown, Globe, Info, Network, AlertTriangle, FlaskConical, Check, Layers, RefreshCw, Pause, BellDot, ArrowLeftRight } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import DnsDetailModal from './DnsDetailModal.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotificationGroupSelect from './ui/NotificationGroupSelect.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import { LoadingBlock } from './ui/Progress.jsx'

import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState } from '../utils/monitorFilters.js'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
const RECORD_TYPES = ['A', 'AAAA', 'CNAME', 'MX', 'TXT', 'NS']

const INTERVALS = [
  { value: 300,  labelKey: 'dns.interval5m'  },
  { value: 900,  labelKey: 'dns.interval15m' },
  { value: 3600, labelKey: 'dns.interval1h'  },
]

const REFRESH_INTERVAL = 60

const INFO_ITEMS = [
  { type: 'A',     descKey: 'dns.recA'     },
  { type: 'AAAA',  descKey: 'dns.recAAAA'  },
  { type: 'CNAME', descKey: 'dns.recCNAME' },
  { type: 'MX',    descKey: 'dns.recMX'    },
  { type: 'TXT',   descKey: 'dns.recTXT'   },
  { type: 'NS',    descKey: 'dns.recNS'    },
  { type: 'SOA',   descKey: 'dns.recSOA'   },
  { type: 'TTL',   descKey: 'dns.ttlExplain' },
]

const emptyForm = { name: '', domain: '', recordType: 'A', intervalSeconds: 300, teamId: '', groupName: '', notificationGroupId: '', expectedValue: '', slowThresholdMs: '', propagationCheck: false, dnsChangeAlertEnabled: true, active: true }

function truncateValue(val, max = 50) {
  if (!val) return '—'
  return val.length > max ? val.substring(0, max) + '…' : val
}

export default function DnsMonitorPage({ systemRole, teamId, teamName }) {
  const t = useT()
  const toast = useToast()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'   // USER ve üstü: kendi takımı için standalone DNS ekler
  const myTeam = teamId != null ? String(teamId) : null
  const isOwnTeam = (m) => myTeam != null && String(m.team_id) === myTeam
  // Envanter-türevi monitörü yalnız admin yönetir; standalone'u (sertifikadan bağımsız) sahip takım yönetir.
  const canManageRow = (m) => isAdmin || (m.standalone && isOwnTeam(m))
  const canDeleteRow = (m) => m.standalone && (isAdmin || isOwnTeam(m))
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [teams, setTeams] = useState([])
  const [detailMonitor, setDetailMonitor] = useState(null)
  const [modal, setModal] = useState(null)
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (sızıntısız, server-scoped)
  const [saving, setSaving] = useState(false)
  // Tek kimlik yerine KUME: uzun suren bir kontrol digerlerini bekletmesin ve
  // once biten, hala sureni kilitten cikarmasin.
  const { isRunning, track } = useRunningChecks()
  const [deleting, setDeleting] = useState(null)
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [infoOpen, setInfoOpen] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [testing, setTesting] = useState(false)
  const [defaults, setDefaults] = useState(null)
  const [secondsSince, setSecondsSince] = useState(0)
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  const [statsVisible, setStatsVisible] = useState(false)

  const load = useCallback(async () => {
    const res = await api.monitoring.getDnsMonitors()
    // HATA DALI: eskiden else yoktu → API düşünce liste boş kalıyor ve ekran
    // "Henüz izleme yok, ekleyin" diyordu; kullanıcı monitörlerinin SİLİNDİĞİNİ sanıyordu.
    // Ayrıca useVisibleInterval her 60 sn sessizce başarısız olmaya devam ediyordu.
    if (res?.success) { setMonitors(res.data); setLoadError(null) }
    else setLoadError(res?.error || 'load failed')
    setLoading(false)
    setSecondsSince(0)
  }, [])

  useVisibleInterval(load, REFRESH_INTERVAL * 1000)   // gizli sekmede polling durur
  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)   // countdown da durur

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  // Form açıkken seçili takımın + bu türün gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'dns').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    return () => { alive = false }
  }, [modal, form.teamId])

  // Yeni monitör için varsayılan kontrol aralığı (Genel Ayarlar → Kontrol Sıklığı).
  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.dns) })
  }, [])

  // E-posta CTA deep-link: ?monitor=<id> → ilgili DNS monitörünün detayını aç (bir kez), paramı temizle.
  useMonitorDeepLink(monitors, setDetailMonitor)

  const teamSelectOptions = [{ value: '', label: t('app.noTeam') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))]

  function openNew() {
    setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds })
    setTestResult(null)
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return {
      name: m.name || '',
      domain: m.domain || '',
      recordType: m.record_type,
      intervalSeconds: m.interval_seconds,
      teamId: m.team_id != null ? String(m.team_id) : '',
      groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '',
      expectedValue: m.expected_value || '',
      slowThresholdMs: m.slow_threshold_ms ?? '',
      propagationCheck: m.propagation_check === true,
      dnsChangeAlertEnabled: m.dns_change_alert_enabled !== false,   // null/undefined = açık
      active: m.active !== false,
    }
  }
  function openEdit(m) {
    setDupSource(null)
    setForm(formFrom(m))
    setTestResult(null)
    setChangeNote('')
    setModal(m)
  }
  /** Kopyala: kaynağın birebir kopyası, YENİ kayıt modunda (create). Ad "(Kopya)" sonekli;
   *  kullanıcı genelde yalnız domain alanını değiştirip kaydeder. Mükerrer koruması backend'de. */
  function openDuplicate(m) {
    setDupSource(m)
    setForm({ ...formFrom(m), name: duplicateName(m.name || m.domain) })
    setTestResult(null)
    setModal('new')
  }
  function closeEditModal() { setModal(null); setTestResult(null); setDupSource(null); setChangeNote('') }

  async function save() {
    // Takım alanı yalnız yeni/standalone'da görünür ve zorunlu; envanter-türevi düzenlemede takım envanterden gelir.
    if ((modal === 'new' || modal?.standalone) && (form.teamId === '' || form.teamId == null)) {
      toast.error(t('mon.teamRequired')); return
    }
    setSaving(true)
    const isNew = modal === 'new'
    const payload = {
      name: (form.name || '').trim(),
      recordType: form.recordType,
      intervalSeconds: form.intervalSeconds,
      expectedValue: (form.expectedValue || '').trim(),
      slowThresholdMs: form.slowThresholdMs === '' ? null : Number(form.slowThresholdMs),
      groupName: form.groupName?.trim() || null,
      // Bos = takim varsayilani -> takim adresi (zincirin kalani).
      notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
        ? null : Number(form.notificationGroupId),
      propagationCheck: !!form.propagationCheck,
      dnsChangeAlertEnabled: !!form.dnsChangeAlertEnabled,
      active: form.active,
    }
    // Not yalnız YAZILDIYSA gönderilir — boş alan payload'a girmez.
    if (changeNote.trim()) payload.changeNote = changeNote.trim()
    let res
    if (isNew) {
      payload.domain = (form.domain || '').trim()
      payload.teamId = form.teamId === '' ? null : Number(form.teamId)
      res = await api.monitoring.createDnsMonitor(payload)
    } else {
      payload.domain = (form.domain || '').trim()   // domain artık düzenlenebilir
      if (modal.standalone) payload.teamId = form.teamId === '' ? null : Number(form.teamId)
      res = await api.monitoring.updateDnsMonitor(modal.id, payload)
    }
    await load()
    setSaving(false)
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('dns.saved'))
    closeEditModal()
  }

  async function checkNow(m) {
    await track(m.id, async () => {
      const res = await api.monitoring.triggerDnsCheck(m.id)
      if (res?.success) {
        setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
      }
    })
  }

  async function deleteMonitor(m) {
    if (!window.confirm(t('dns.deleteConfirm'))) return
    setDeleting(m.id)
    const res = await api.monitoring.deleteDnsMonitor(m.id)
    if (res?.success) { toast.success(t('dns.deleted')); await load() }
    else toast.error(res?.error || 'Error')
    setDeleting(null)
  }

  // Canlı DNS testi — kaydetmeden formdaki domain/kayıt-tipi ile bir kez çözer; URL girilse host ayıklanır.
  async function runTest() {
    if (!form.domain.trim()) return
    setTesting(true); setTestResult(null)
    const res = await api.monitoring.testDnsMonitor({
      domain: form.domain.trim(), recordType: form.recordType,
      expectedValue: (form.expectedValue || '').trim() || null,
      slowThresholdMs: form.slowThresholdMs === '' ? null : Number(form.slowThresholdMs),
    })
    setTestResult(res?.success ? res.data : { error: res?.error || t('dns.testError') })
    setTesting(false)
  }

  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('dns.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
  }

  // Takım filtresi seçenekleri — listeden türetilir (dashboard deseni).
  const teamOptions = (() => {
    const names = new Set()
    let hasNone = false
    for (const m of monitors) { if (m.team_name) names.add(m.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach((n) => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  })()
  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')

  // Grup seçenekleri — yüklü monitörlerden türetilir (takım-kapsamlı: admin hepsini, diğerleri kendi takımı) — ping/keyword deseni.
  const groupMonitors = isAdmin ? monitors : monitors.filter(m => myTeam != null && String(m.team_id) === myTeam)
  const groupNames = [...new Set(groupMonitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b))
  const hasGroupOptions = groupNames.length > 0
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
  const groupSelectOptions = teamGroups.map(g => ({ value: g.name, label: g.name }))
  const groupFilterOptions = [{ value: 'all', label: t('dns.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('dns.noGroup') }] : [])]

  const dnsCounts = {
    total: monitors.length,
    ok: monitors.filter(m => m.active !== false && !m.active_alarm).length,
    alarm: monitors.filter(m => m.active_alarm).length,
    unacked: monitors.filter(m => m.active_alarm && !m.alarm_acknowledged).length,
    changed: monitors.filter(m => m.changed).length,
    paused: monitors.filter(m => m.active === false).length,
  }
  const statItems = [
    { key: 'total',   Icon: Network,        label: t('dns.statTotal'),    value: dnsCounts.total,   cls: 'total'    },
    { key: 'ok',      Icon: Check,          label: t('dns.statOk'),       value: dnsCounts.ok,      cls: 'valid'    },
    { key: 'alarm',   Icon: AlertTriangle,  label: t('dns.statAlarm'),    value: dnsCounts.alarm,   cls: 'critical' },
    { key: 'unacked', Icon: BellDot,        label: t('dns.statUnacked'),  value: dnsCounts.unacked, cls: 'warning'  },
    { key: 'changed', Icon: ArrowLeftRight, label: t('dns.statChanged'),  value: dnsCounts.changed, cls: 'alert'    },
    { key: 'paused',  Icon: Pause,          label: t('dns.statPaused'),   value: dnsCounts.paused,  cls: 'paused'   },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  const filtered = useMemo(() => monitors.filter(m => {
    if (!matchesTeamAndGroup(m, teamFilter, groupFilter)) return false
    if (statFilter && statFilter !== 'total') {
      if (statFilter === 'alarm' && !m.active_alarm) return false
      if (statFilter === 'ok' && (m.active === false || m.active_alarm)) return false
      if (statFilter === 'unacked' && !(m.active_alarm && !m.alarm_acknowledged)) return false
      if (statFilter === 'changed' && !m.changed) return false
      if (statFilter === 'paused' && m.active !== false) return false
    }
    if (!search.trim()) return true
    const s = search.toLowerCase()
    return m.domain?.toLowerCase().includes(s) || m.record_type?.toLowerCase().includes(s)
  }), [monitors, teamFilter, groupFilter, statFilter, search])

  // Değişiklik geçmişi `teamId` farkını ADA çevirebilsin — çıplak sayı okunmuyor.
  const teamNameById = useMemo(
    () => Object.fromEntries(teams.map(tm => [tm.id, tm.name])), [teams])

  // Sayfalama filtrelenmiş listenin ÜZERİNE; sayaç/istatistikler tam listeden hesaplanmaya devam eder.
  const pager = usePagination(filtered, {
    listKey: 'dns-monitors', resetDeps: [search, teamFilter, groupFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: filtre/arama/sayfa + açık detay modalı (mtab/range DnsDetailModal içinde sync'lenir).
  useUrlQuerySync({
    ...monitorUrlState({ teamFilter, groupFilter, search, statFilter, pager }),
    monitor: detailMonitor?.id ?? null,
    // Modal AÇIKKEN mtab/range'i DnsDetailModal yönetir (anahtarlar mapping'de olmaz → dokunulmaz);
    // modal kapanınca burada null'a düşer ve URL'den silinir (modal unmount'ta silme yapamaz).
    ...(detailMonitor ? {} : { mtab: null, range: null }),
  })

  return (
    <div className="dns-page">
      <div className="dns-header">
        <div className="dns-title-row">
          <Globe size={22} />
          <div>
            <h2 className="dns-title">{t('dns.title')}</h2>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginLeft: 'auto' }}>
          <span className="upt-last-check">{t('dns.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}</span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}><RefreshCw size={14} />{t('dns.refreshBtn')}</button>
          <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
          <MonitorGuideButton type="dns" />
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('dns.addMonitor')}
            </button>
          )}
        </div>
      </div>

      <MonitorHowBox bullets={[t('dns.how1'), t('dns.how2'), t('dns.how3'), t('dns.how4'), t('dns.how5')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={filtered.length} />

      <div className={`dns-info-card${infoOpen ? ' dns-info-open' : ''}`}>
        <button className="dns-info-toggle" onClick={() => setInfoOpen(v => !v)} type="button">
          <Info size={16} />
          <span className="dns-info-title">{t('dns.infoTitle')}</span>
          <ChevronDown size={14} className={`dns-info-chevron${infoOpen ? ' open' : ''}`} />
        </button>
        {infoOpen && (
          <div className="dns-info-body">
            <p className="dns-info-intro">{t('dns.infoIntro')}</p>
            <div className="dns-info-grid">
              {INFO_ITEMS.map(item => (
                <div key={item.type} className="dns-info-row">
                  <span className="dns-info-key">{item.type}</span>
                  <span className="dns-info-desc">{t(item.descKey)}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      <div className="dns-toolbar">
        {hasTeamOptions && (
          <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />
        )}
        {hasGroupOptions && (
          <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />
        )}
        <input
          className="dns-search-input"
          type="text"
          placeholder={t('dns.searchPlaceholder')}
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
        {search && (
          <button className="dns-search-clear" onClick={() => setSearch('')}>✕</button>
        )}
        <div className="dns-counter">{t('dns.monitorCount', filtered.length)}</div>
      </div>

      {loading ? (
        <LoadingBlock label={t('dns.loading')} fullWidth />
      ) : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<button className="btn btn-sm btn-secondary" onClick={load}>{t('hist.retry')}</button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <div className="mon-empty">{t('dns.noMonitors')}</div>
      ) : (
        <div className="admin-table-wrap dns-table-wrap">
          <table className="admin-table dns-table">
            <thead>
              <tr>
                <th>{t('dns.domain')}</th>
                <th>{t('dns.colTeam')}</th>
                <th>{t('dns.recordType')}</th>
                <th>{t('dns.currentValue')}</th>
                <th>{t('dns.ttl')}</th>
                <th>{t('dns.responseMs')}</th>
                <th>{t('dns.lastCheck')}</th>
                <th>{t('dns.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {pager.pageItems.map(m => (
                <tr
                  key={m.id}
                  className={`dns-row${!m.active ? ' dns-row-inactive' : ''}${m.active_alarm ? ' dns-row--alarm' : ''}`}
                  onClick={() => setDetailMonitor(m)}
                >
                  <td className="dns-cell-mono">
                    {alarmBadge(m)}<MaintenanceBadge target={m.domain} />
                    <strong>{m.domain}</strong>
                    {m.standalone && (
                      <span className="dns-standalone-badge" title={t('dns.standaloneHint')}>{t('dns.standalone')}</span>
                    )}
                  </td>
                  <td>
                    {m.team_name || '—'}
                    {m.group_name && (
                      <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: '.78em', color: 'var(--text-muted)', marginTop: 2 }}>
                        <Layers size={12} />{m.group_name}
                      </div>
                    )}
                  </td>
                  <td>
                    <span className="dns-type-badge">{m.record_type}</span>
                  </td>
                  <td className="dns-cell-value">
                    {m.changed && <span className="dns-changed-badge">{t('dns.changed')}</span>}
                    {!m.changed && m.rotated && (
                      <span className="dns-rotated-badge" title={t('dns.rotationTitle')}>{t('dns.rotated')}</span>
                    )}
                    <span className="dns-cell-mono">{truncateValue(m.value)}</span>
                  </td>
                  <td className="dns-cell-num">{m.ttl != null ? `${m.ttl}s` : '—'}</td>
                  <td className="dns-cell-num">{m.response_ms != null ? `${m.response_ms}ms` : '—'}</td>
                  <td className="dns-cell-time">{m.checked_at ? formatDate(m.checked_at) : '—'}</td>
                  <td className="dns-cell-actions" onClick={e => e.stopPropagation()}>
                    {/* Kart değil TABLO satırı: paylaşım düğmesi eylem hücresine girer.
                        Yetkiden bağımsız — bağlantı kopyalamak salt-okunur bir iştir. */}
                    <CheckRunningStrip running={isRunning(m.id)} />
                    <CopyLinkButton iconOnly url={monitorDeepLink('dns', m.id)} className="mon-act mon-act--copy" />
                    {canManageRow(m) && (
                      <CheckNowButton running={isRunning(m.id)} onClick={() => checkNow(m)} title={t('dns.check')} />
                    )}
                    {canManageRow(m) && (
                      <button type="button" className="mon-act mon-act--edit" onClick={() => openEdit(m)}
                        title={t('dns.edit')} aria-label={t('dns.edit')}>
                        <Pencil size={13} />
                      </button>
                    )}
                    {canManageRow(m) && (
                      <button type="button" className="mon-act mon-act--copy"
                        onClick={e => { e.stopPropagation(); openDuplicate(m) }}
                        title={t('mon.duplicate')} aria-label={t('mon.duplicate')}>
                        <Copy size={13} />
                      </button>
                    )}
                    {canDeleteRow(m) && (
                      <button type="button" className="mon-act mon-act--danger" disabled={deleting === m.id}
                        onClick={() => deleteMonitor(m)} title={t('dns.delete')} aria-label={t('dns.delete')}>
                        <Trash2 size={13} />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <PaginationBar {...pager} />
        </div>
      )}

      {detailMonitor && (
        <DnsDetailModal monitor={detailMonitor} onClose={() => setDetailMonitor(null)} teamNames={teamNameById}
          canManage={canManageRow(detailMonitor)} />
      )}

      {modal && (
        // Dış/overlay tıklamada KAPANMAZ — veri kaybı önlenir; yalnız İptal/Kaydet (keyword/ping ile aynı).
        <div className="modal-overlay">
          <div className="modal-box" onClick={e => e.stopPropagation()}>
            <div className="modal-icon-hdr modal-icon-hdr--dns">
              <div className="modal-icon-hdr-badge">
                <Network size={20} />
              </div>
              <h3>{modal === 'new' ? t('dns.modalNew') : t('dns.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
            </div>
            {dupSource && <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>}
            <div className="form-grid form-grid--top">
              <label>
                <span>{t('dns.domain')} <span className="req-star">*</span></span>
                <input
                  value={form.domain}
                  onChange={e => setForm(f => ({ ...f, domain: e.target.value }))}
                  placeholder={t('dns.domainPlaceholder')}
                  autoFocus={modal === 'new' || !!dupSource}
                />
              </label>
              {(modal === 'new' || modal.standalone) && (
                <label>
                  <span>{t('dns.team')} <span className="req-star">*</span></span>
                  {isAdmin
                    ? <SearchableSelect
                        value={form.teamId}
                        onChange={v => setForm(f => ({ ...f, teamId: v }))}
                        options={teamSelectOptions}
                        searchThreshold={2}
                      />
                    : <input value={teamName || t('app.noTeam')} disabled />}
                </label>
              )}
              <label>
                <span>{t('dns.name')}</span>
                <input
                  value={form.name}
                  onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                  placeholder={form.domain || (modal !== 'new' ? modal.domain : '')}
                />
              </label>
              <label>
                <span>{t('dns.group')}</span>
                <SearchableSelect
                  value={form.groupName}
                  onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('dns.noGroup') }, ...groupSelectOptions]}
                  creatable
                  onCreate={() => {}}
                  searchThreshold={2}
                  placeholder={t('dns.noGroup')}
                />
              </label>
              <NotificationGroupSelect teamId={form.teamId} value={form.notificationGroupId}
                onChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
              <label>
                <span>{t('dns.recordType')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.recordType}
                  onChange={v => setForm(f => ({ ...f, recordType: v }))}
                  options={RECORD_TYPES.map(rt => ({ value: rt, label: rt }))}
                />
              </label>
              <label>
                <span>{t('dns.interval')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.intervalSeconds}
                  onChange={v => setForm(f => ({ ...f, intervalSeconds: Number(v) }))}
                  options={INTERVALS.map(opt => ({ value: opt.value, label: t(opt.labelKey) }))}
                />
              </label>
              <label>
                <span>{t('dns.slowThresholdField')}</span>
                <input
                  type="number" min="100" max="60000"
                  value={form.slowThresholdMs}
                  onChange={e => setForm(f => ({ ...f, slowThresholdMs: e.target.value }))}
                  placeholder={t('dns.slowThresholdPlaceholder')}
                />
                <span className="field-hint">{t('dns.slowThresholdHint')}</span>
              </label>
              <label className="full-width">
                <span className="dns-expected-label">
                  {t('dns.expectedValue')}
                  {modal !== 'new' && modal.value && (
                    <span className="dns-pin-btns">
                      <button type="button" className="dns-pin-btn"
                        onClick={() => setForm(f => ({ ...f, expectedValue: modal.value }))}>
                        {t('dns.pinCurrent')}
                      </button>
                      <button type="button" className="dns-pin-btn"
                        title={t('dns.addCurrentHint')}
                        onClick={() => setForm(f => {
                          // Mevcut değer(ler)i listeye EKLE (replace değil) — dedupe'lu; iki bilinen IP birden sabitlenebilir.
                          const existing = (f.expectedValue || '').split('\n').map(s => s.trim()).filter(Boolean)
                          const incoming = (modal.value || '').split('\n').map(s => s.trim()).filter(Boolean)
                          const merged = [...existing, ...incoming.filter(v => !existing.includes(v))]
                          return { ...f, expectedValue: merged.join('\n') }
                        })}>
                        {t('dns.addCurrent')}
                      </button>
                    </span>
                  )}
                </span>
                <textarea
                  rows={3}
                  value={form.expectedValue}
                  onChange={e => setForm(f => ({ ...f, expectedValue: e.target.value }))}
                  placeholder={t('dns.expectedPlaceholder')}
                />
                <span className="field-hint">{t('dns.expectedHint')}</span>
              </label>
              <label className="checkbox-label full-width">
                <input
                  type="checkbox"
                  checked={form.dnsChangeAlertEnabled}
                  onChange={e => setForm(f => ({ ...f, dnsChangeAlertEnabled: e.target.checked }))}
                />
                {t('dns.changeAlertEnabled')}
              </label>
              <span className="field-hint full-width">{t('dns.changeAlertHint')}</span>
              <label className="checkbox-label full-width">
                <input
                  type="checkbox"
                  checked={form.propagationCheck}
                  onChange={e => setForm(f => ({ ...f, propagationCheck: e.target.checked }))}
                />
                {t('dns.propagationCheck')}
              </label>
              <span className="field-hint full-width dns-prop-hint">{t('dns.propagationHint')}</span>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={form.active}
                  onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                />
                {t('dns.formActive')}
              </label>
            </div>
            {testResult && (
              <div style={{ margin: '2px 0 12px', padding: '10px 12px', borderRadius: 8, fontSize: '.86em', lineHeight: 1.5,
                display: 'flex', alignItems: 'flex-start', gap: 8, border: '1px solid',
                ...(testResult.error
                  ? { background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }
                  : (testResult.unexpected?.length || testResult.slow)
                    ? { background: '#fff7ed', borderColor: '#fed7aa', color: '#b45309' }
                    : { background: '#f0fdf4', borderColor: '#bbf7d0', color: '#15803d' }) }}>
                {(testResult.error || testResult.unexpected?.length || testResult.slow)
                  ? <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />
                  : <Check size={16} style={{ flexShrink: 0, marginTop: 1 }} />}
                <span>
                  {testResult.error
                    ? <><strong>{t('dns.testError')}:</strong> {testResult.error}</>
                    : <>
                        <strong>{testResult.unexpected?.length ? t('dns.testUnexpected')
                          : testResult.slow ? t('dns.testSlow') : t('dns.testSuccess')}</strong>
                        {testResult.host && <> · {testResult.host}</>}
                        {testResult.values?.length > 0 && <> · {testResult.values.join(', ')}</>}
                        {testResult.ttl != null && <> · TTL {testResult.ttl}s</>}
                        {testResult.response_ms != null && <> · {testResult.response_ms}ms</>}
                      </>}
                </span>
              </div>
            )}
            {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
            {modal !== 'new' && (
              <ChangeNoteField t={t} id="dns-change-note" value={changeNote} onChange={setChangeNote} />
            )}
            <div className="modal-actions">
              <button className="btn btn-secondary" style={{ marginRight: 'auto' }} onClick={runTest}
                disabled={testing || !form.domain.trim()}>
                <FlaskConical size={14} />{testing ? t('dns.testing') : t('dns.test')}
              </button>
              <button className="btn btn-secondary" onClick={closeEditModal}>{t('dns.cancel')}</button>
              <button
                className="btn btn-primary"
                onClick={save}
                disabled={saving || !form.recordType || !form.domain.trim() || ((modal === 'new' || modal?.standalone) && !form.teamId)}
              >
                {saving ? t('dns.saving') : t('dns.save')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
