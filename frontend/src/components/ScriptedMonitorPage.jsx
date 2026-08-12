import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDateSec } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { useToast } from './ui/Toast.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import CodeEditor from './ui/CodeEditor.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import TagInput from './ui/TagInput.jsx'
import { SCRIPTED_TEMPLATES } from './scriptedTemplates.js'
import { FlaskConical, Play, Pencil, Plus, Trash2, X, RefreshCw, Eye, EyeOff, Copy, Users, Layers,
  AlertTriangle, LayoutDashboard, CheckCircle2, WifiOff, Siren, BellDot, PauseCircle, BarChart3, ChevronDown,
  Terminal } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import AlertHistory from './admin/AlertHistory.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { LoadingBlock, Spinner } from './ui/Progress.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import CopyButton from './ui/CopyButton.jsx'
import { exitLabel, exitHint, diagnosisHint } from './scriptedExitCodes.js'
// recharts ağır — yalnız "Süre Grafiği" sekmesi açılınca yüklensin.
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))

const INTERVALS = [
  { value: 60, k: 'scripted.iv1m' }, { value: 300, k: 'scripted.iv5m' }, { value: 600, k: 'scripted.iv10m' },
  { value: 900, k: 'scripted.iv15m' }, { value: 1800, k: 'scripted.iv30m' }, { value: 3600, k: 'scripted.iv1h' },
]
function intervalIdx(secs) {
  let idx = 0, best = Infinity
  INTERVALS.forEach((iv, i) => { const d = Math.abs(iv.value - secs); if (d < best) { best = d; idx = i } })
  return idx
}
const REFRESH_INTERVAL = 60

const emptyForm = {
  name: '', description: '', groupName: '', teamId: '', tags: '', notifyEmail: true,
  intervalSeconds: 300, timeoutSeconds: 60, confirmAttempts: 3, confirmIntervalSeconds: 30,
  recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true, script: '', env: [], template: '',
}

const STATUS_COLOR = { PASS: '#16a34a', FAIL: '#d97706', ERROR: '#dc2626', TIMEOUT: '#b45309', NO_CHECKS: '#d97706', unknown: '#9ca3af' }
function statusLabel(t, s) { return t(`scripted.status_${s || 'unknown'}`) }
/** PASS/FAIL/ERROR/TIMEOUT/NO_CHECKS alfabesi → kanonik up/down eşlemesi (upt-card/upt-badge aileleri). */
function isPass(s) { return s === 'PASS' }
function isFailLike(s) { return s === 'FAIL' || s === 'ERROR' || s === 'TIMEOUT' }
/**
 * NO_CHECKS = koştu ama hiçbir şey doğrulanmadı. Bilinçli olarak isFailLike'a KONMADI:
 * arıza değil yapılandırma kusurudur, alarm üretmez ve "down" sayaçlarını/filtresini şişirmemeli.
 */
function isWarnLike(s) { return s === 'NO_CHECKS' }
/** Backend çok satırlı hata döndürdüyse bu bir k6/Babel kod çerçevesidir (hizalı caret taşır). */
function isCodeFrame(error) { return typeof error === 'string' && error.includes('\n') }

/**
 * Kontrol Geçmişi gruplama imzası (CheckHistoryTab `rowSignature`).
 *
 * Yalnız BAŞARISIZ satırlar gruplanır — PASS satırlarını katlamak normal zaman çizgisini gizlerdi.
 * Sebep olarak hatanın İLK satırı yeterli: kod çerçevesinin tamamı aynıysa ilk satır da aynıdır,
 * farklı bir hataysa zaten ilk satırda ayrışır.
 *
 * Modül düzeyinde tanımlı (bileşen içinde arrow DEĞİL): `rowSignature` CheckHistoryTab'ın satır
 * useMemo'sunun bağımlılığı; her render'da yeni bir referans üretmek memo'yu boşa çıkarırdı.
 */
function scriptedRowSignature(c) {
  if (!c || isPass(c.status)) return null
  const first = String(c.error || '').split('\n')[0].slice(0, 200)
  return `${c.status}|${c.exit_code ?? ''}|${first}`
}

export default function ScriptedMonitorPage({ systemRole, teamId, teamName }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const isAdminish = isAdmin || isTeamAdmin            // form/team-select davranışı (mevcut semantik korunur)
  const myTeam = teamId != null ? String(teamId) : null
  const isOwnTeam = (m) => myTeam != null && String(m.team_id) === myTeam

  const [monitors, setMonitors] = useState([])
  const [k6, setK6] = useState({ available: true, version: null, canManage: false })
  const [loading, setLoading] = useState(true)
  const [teams, setTeams] = useState([])
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  const [statsVisible, setStatsVisible] = useState(false)
  const [secondsSince, setSecondsSince] = useState(0)
  const [modal, setModal] = useState(null)      // create/edit form monitor (or {} for new)
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (server-scoped, sızıntısız)
  const [defaults, setDefaults] = useState(null)     // per-tip varsayılan aralık/timeout (Kontrol Sıklığı ayarı)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [saveWarnings, setSaveWarnings] = useState([])   // kaydetme sonrası engellemeyen uyarılar
  const [checking, setChecking] = useState(null)
  const [selected, setSelected] = useState(null) // detail monitor
  const [summary, setSummary] = useState({ total: 0, down: 0 })   // CheckHistoryTab onCounts besler
  const [detailTab, setDetailTab] = useState('control')
  const [selCheck, setSelCheck] = useState(null)
  const deepLinkDone = useRef(false)

  // Satır-bazlı yetki: global monitoring.scripted izni (can_manage) + takım sahipliği (kanonik desen).
  const canManageRow = (m) => k6.canManage && (isAdmin || isOwnTeam(m))
  const canDeleteRow = (m) => k6.canManage && (isAdmin || (isTeamAdmin && isOwnTeam(m)))

  const load = useCallback(async () => {
    const res = await api.monitoring.getScriptedMonitors()
    if (res?.success) {
      const d = res.data || {}
      setMonitors(d.monitors || [])
      setK6({ available: d.k6_available !== false, version: d.k6_version, canManage: !!d.can_manage })
    }
    setLoading(false); setSecondsSince(0)
  }, [])

  useVisibleInterval(load, REFRESH_INTERVAL * 1000)   // gizli sekmede polling durur
  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)   // countdown da durur

  useEffect(() => { if (isAdminish) api.admin.getTeams?.().then(r => setTeams(r?.success ? r.data || [] : [])) }, [isAdminish])

  // Yeni monitör için per-tip varsayılan kontrol aralığı + timeout (Genel Ayarlar → Kontrol Sıklığı).
  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.scripted) })
  }, [])

  // Form açıkken seçili takımın + bu türün gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'scripted').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    return () => { alive = false }
  }, [modal, form.teamId])

  // Deep-link: ?monitor=<id> → detay modalını aç (bir kez) — diğer izleme türleriyle parite.
  useEffect(() => {
    if (deepLinkDone.current || monitors.length === 0) return
    deepLinkDone.current = true
    const id = readUrlParam('monitor', null)
    if (!id) return
    const m = monitors.find(x => String(x.id) === String(id))
    if (m) openDetail(m)
  }, [monitors]) // eslint-disable-line react-hooks/exhaustive-deps

  function openDetail(m) { setSelected(m); setSelCheck(null); setSummary({ total: 0, down: 0 }); setDetailTab('control') }
  function closeDetail() { setSelected(null); setSelCheck(null) }

  // Türetilmiş listeler memoize — 1sn countdown her saniye render tetikler.
  const teamOptions = useMemo(() => {
    const names = new Set(); let hasNone = false
    for (const m of monitors) { if (m.team_name) names.add(m.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach(n => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  }, [monitors, t])
  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')
  const teamSelectOptions = useMemo(() => teams.map(tm => ({ value: String(tm.id), label: tm.name })), [teams])
  // Gruplar takıma özgü: kullanıcı yalnız kendi takımının gruplarını görür/seçer (admin tümünü).
  const groupMonitors = useMemo(
    () => (isAdmin ? monitors : monitors.filter(m => myTeam != null && String(m.team_id) === myTeam)),
    [monitors, isAdmin, myTeam])
  const groupNames = useMemo(
    () => [...new Set(groupMonitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [groupMonitors])
  const hasGroupOptions = groupNames.length > 0
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('scripted.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('scripted.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
  const groupSelectOptions = useMemo(() => teamGroups.map(g => ({ value: g.name, label: g.name })), [teamGroups])

  const scoped = useMemo(() => monitors.filter(m => {
    if (teamFilter !== 'all') {
      if (teamFilter === '__none__') { if (m.team_name) return false }
      else if (m.team_name !== teamFilter) return false
    }
    if (groupFilter !== 'all') {
      if (groupFilter === '__none__') { if (m.group_name) return false }
      else if (m.group_name !== groupFilter) return false
    }
    const q = search.trim().toLowerCase()
    if (!q) return true
    return (m.name || '').toLowerCase().includes(q) || (m.group_name || '').toLowerCase().includes(q)
  }), [monitors, teamFilter, groupFilter, search])

  const counts = useMemo(() => {
    const c = { total: scoped.length, up: 0, down: 0, alarm: 0, unacked: 0, paused: 0 }
    for (const m of scoped) {
      if (isPass(m.status)) c.up++
      else if (isFailLike(m.status)) c.down++
      if (m.active_alarm) { c.alarm++; if (!m.alarm_acknowledged) c.unacked++ }
      if (m.active === false) c.paused++
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    const pred = {
      up:      m => isPass(m.status),
      down:    m => isFailLike(m.status),
      alarm:   m => m.active_alarm,
      unacked: m => m.active_alarm && !m.alarm_acknowledged,
      paused:  m => m.active === false,
    }[statFilter]
    return pred ? scoped.filter(pred) : scoped
  }, [scoped, statFilter])

  // Sayfalama filtrelenmiş listenin ÜZERİNE; sayaç/istatistikler tam listeden hesaplanmaya devam eder.
  const pager = usePagination(displayMonitors, {
    listKey: 'scripted-monitors', resetDeps: [search, teamFilter, groupFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })
  // Paylaşılabilir URL: filtre/arama/sayfa + açık detay modalı adres çubuğunda yaşar (varsayılanlar param üretmez).
  useUrlQuerySync({
    team: teamFilter === 'all' ? null : teamFilter,
    group: groupFilter === 'all' ? null : groupFilter,
    q: search.trim() || null,
    stat: statFilter && statFilter !== 'total' ? statFilter : null,
    page: pager.page > 1 ? pager.page : null,
    ps: (pager.pageSize !== 50 || pager.page > 1) ? pager.pageSize : null,
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'control' ? detailTab : null,
    // range/hfrom/hto/hst artık CheckHistoryTab'ın kendi URL senkronunda
  })

  const statItems = [
    { key: 'total',   Icon: LayoutDashboard, label: t('scripted.dashTotal'),   value: counts.total,   cls: 'total'    },
    { key: 'up',      Icon: CheckCircle2,    label: t('scripted.dashUp'),      value: counts.up,      cls: 'valid'    },
    { key: 'down',    Icon: WifiOff,         label: t('scripted.dashDown'),    value: counts.down,    cls: 'critical' },
    { key: 'alarm',   Icon: Siren,           label: t('scripted.dashAlarm'),   value: counts.alarm,   cls: 'high'     },
    { key: 'unacked', Icon: BellDot,         label: t('scripted.dashUnacked'), value: counts.unacked, cls: 'warning'  },
    { key: 'paused',  Icon: PauseCircle,     label: t('scripted.dashPaused'),  value: counts.paused,  cls: 'paused'   },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  function cardClass(m) {
    if (isPass(m.status)) return 'upt-card--up'
    if (isFailLike(m.status)) return 'upt-card--down'
    if (isWarnLike(m.status)) return 'upt-card--warn'
    return 'upt-card--unknown'
  }
  function statusBadge(m) {
    const s = m?.status
    const cls = isPass(s) ? 'upt-badge--up' : isFailLike(s) ? 'upt-badge--down'
      : isWarnLike(s) ? 'upt-badge--warn' : 'upt-badge--unknown'
    return <span className={`upt-badge ${cls}`}><span className="upt-badge-dot" />{statusLabel(t, s)}</span>
  }
  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('scripted.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
  }

  function openNew() {
    setForm({ ...emptyForm, teamId: isAdminish ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutSeconds: defaults?.timeoutSeconds ?? emptyForm.timeoutSeconds })
    setTestResult(null); setDupSource(null); setModal({})
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return {
      name: m.name || '', description: m.description || '', groupName: m.group_name || '',
      teamId: m.team_id != null ? String(m.team_id) : '', tags: m.tags || '', notifyEmail: m.notify_email !== false,
      intervalSeconds: m.interval_seconds ?? 300, timeoutSeconds: m.timeout_seconds ?? 60,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      active: m.active !== false, script: m.script || '',
      // env: secret satırlar value_set taşır (değer geri okunamaz); non-secret value taşır
      env: (m.env || []).map(e => ({ name: e.name, secret: !!e.secret, value: e.secret ? '' : (e.value || ''), value_set: !!e.value_set })),
    }
  }
  function openEdit(m) {
    setForm(formFrom(m))
    setTestResult(null); setDupSource(null); setModal(m)
  }
  /** Kopyala: kaynağın birebir kopyası, YENİ kayıt modunda (create). Ad "(Kopya)" sonekli.
   *  GİZLİ env değerleri geri okunamadığından kopyaya taşınamaz — value_set=false yapılır ki
   *  kullanıcı bu satırları yeniden doldurması gerektiğini görsün. Mükerrer koruması backend'de (ad+takım). */
  function openDuplicate(m) {
    const base = formFrom(m)
    setForm({ ...base, name: duplicateName(m.name),
      env: base.env.map(e => e.secret ? { ...e, value: '', value_set: false } : e) })
    setTestResult(null); setDupSource(m); setModal({})
  }
  function closeEdit() { setModal(null); setTestResult(null); setDupSource(null); setSaveWarnings([]) }

  function setEnvRow(i, patch) { setForm(f => ({ ...f, env: f.env.map((e, j) => j === i ? { ...e, ...patch } : e) })) }
  function addEnvRow() { setForm(f => ({ ...f, env: [...f.env, { name: '', secret: false, value: '' }] })) }
  function delEnvRow(i) { setForm(f => ({ ...f, env: f.env.filter((_, j) => j !== i) })) }

  function applyTemplate(id) {
    const tpl = SCRIPTED_TEMPLATES.find(x => x.id === id)
    if (!tpl) return
    setForm(f => ({
      ...f,
      script: tpl.script,
      env: tpl.env.map(e => ({ name: e.name, secret: !!e.secret, value: '' })),
    }))
  }

  function envPayload() {
    // secret: value yalnız kullanıcı yazdıysa gönder (aksi halde name+secret → sunucu eski enc'i korur)
    return form.env.filter(e => (e.name || '').trim()).map(e => {
      const row = { name: e.name.trim(), secret: !!e.secret }
      if (!e.secret) row.value = e.value || ''
      else if (e.value) row.value = e.value      // yeni secret değeri
      return row
    })
  }

  async function save() {
    if (!form.name.trim()) { toast.error(t('scripted.nameRequired')); return }
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    if (!form.groupName.trim()) { toast.error(t('scripted.groupRequired')); return }
    setSaving(true)
    const payload = {
      name: form.name.trim(), description: form.description?.trim() || null,
      groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
      tags: form.tags?.trim() || null, notifyEmail: form.notifyEmail,
      intervalSeconds: Number(form.intervalSeconds), timeoutSeconds: Number(form.timeoutSeconds),
      confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
      recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
      active: form.active, script: form.script, env: envPayload(),
    }
    const res = modal?.id ? await api.monitoring.updateScriptedMonitor(modal.id, payload)
                          : await api.monitoring.createScriptedMonitor(payload)
    setSaving(false)
    if (res?.success) {
      // Engellemeyen uyarılar (eksik/kullanılmayan __ENV, sonuçsuz sözdizimi doğrulaması) KALICI
      // gösterilir — toast kaybolur, bu bilgi kaydettikten sonra da lazım.
      const w = res.data?.warnings
      if (Array.isArray(w) && w.length) setSaveWarnings(w)
      else { toast.success(t('scripted.saved')); closeEdit() }
      load()
    }
    else toast.error(res?.error || t('scripted.saveError'))
  }

  async function del() {
    if (!modal?.id) return
    if (!window.confirm(t('scripted.confirmDelete'))) return
    const res = await api.monitoring.deleteScriptedMonitor(modal.id)
    if (res?.success) { toast.success(t('scripted.deleted')); closeEdit(); load() }
    else toast.error(res?.error || t('scripted.deleteError'))
  }

  async function runTest() {
    if (!form.script.trim()) { toast.error(t('scripted.scriptRequired')); return }
    setTesting(true); setTestResult(null)
    const res = await api.monitoring.testScripted({
      script: form.script, timeoutSeconds: Number(form.timeoutSeconds),
      env: form.env.filter(e => (e.name || '').trim()).map(e => ({ name: e.name.trim(), value: e.value || '' })),
    })
    setTestResult(res?.success ? res.data : { status: 'ERROR', error: res?.error || t('scripted.testError') })
    setTesting(false)
  }

  async function checkNow(m) {
    setChecking(m.id)
    const res = await api.monitoring.triggerScriptedCheck(m.id)
    if (res?.success) {
      // queued: koşum sunucunun bekleme penceresini aştı, arka planda sürüyor. Satırı ESKİ sonuçla
      // güncellemek yanıltıcı olurdu (kullanıcı bunu yeni sonuç sanar) — dokunmayıp haber veriyoruz.
      // Sonuç kendiliğinden gelir: liste 60 sn'de, Kontrol Geçmişi 30 sn'de canlı yeniliyor.
      if (res.data?.queued) {
        toast.success(t('scripted.triggerQueued'))
      } else {
        setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
        // Geçmiş yenilemesi BİLİNÇLİ olarak yok: CheckHistoryTab kendi live polling'ini yapıyor.
        // Buradaki eski loadHistory(m.id, rangeDays) çağrısı geçmiş yönetimi o bileşene taşınırken
        // temizlenmemişti; ikisi de tanımsız olduğu için modal açıkken "Şimdi Çalıştır" ReferenceError
        // atıyor, aşağıdaki setChecking(null) hiç çalışmıyor ve buton kalıcı kilitleniyordu.
        if (selected?.id === m.id) setSelected(res.data)
      }
    } else if (res) toast.error(res.error || t('scripted.triggerError'))
    setChecking(null)
  }

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title"><FlaskConical size={20} style={{ verticalAlign: '-4px' }} /> {t('scripted.title')}</h2>
          <p className="upt-subtitle">{t('scripted.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('scripted.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}>
            <RefreshCw size={14} />{t('scripted.refresh')}
          </button>
          <CopyLinkButton />
          <MonitorGuideButton type="scripted" />
          {k6.canManage && k6.available &&
            <button className="btn btn-sm btn-primary" onClick={openNew}><Plus size={14} />{t('scripted.addMonitor')}</button>}
        </div>
      </div>

      <MonitorHowBox bullets={[t('scripted.how1'), t('scripted.how2'), t('scripted.how3'), t('scripted.how4'), t('scripted.how5')]} />

      {/* .alert-msg YEŞİL "başarı" kutusuydu ve emoji taşıyordu (proje kuralı: yalnız lucide). */}
      {!k6.available && <AlertBanner tone="warning">{t('scripted.k6Disabled')}</AlertBanner>}

      {!loading && monitors.length > 0 && (
        <div className="stats-collapse-bar" onClick={toggleStats}
          title={statsVisible ? t('app.collapseStats') : t('app.expandStats')}>
          <span className="stats-collapse-icon"><BarChart3 size={18} /></span>
          <span className="stats-collapse-label">{t('app.statistics')}</span>
          {!statsVisible && <span className="stats-collapse-hint">{t('app.expandStats')}</span>}
          <span className={`stats-collapse-chevron${statsVisible ? ' open' : ''}`}><ChevronDown size={18} /></span>
        </div>
      )}
      {statsVisible && !loading && monitors.length > 0 && (
        <MonitorStatsBar items={statItems} activeFilter={statFilter} onStatClick={onStatClick} />
      )}
      {statsVisible && statFilter && statFilter !== 'total' && (
        <div className="stats-filter-bar" style={{ marginBottom: 16 }}>
          <span>{statItems.find(s => s.key === statFilter)?.label} — {t('mondash.showing', displayMonitors.length)}</span>
          <button className="stats-filter-clear" onClick={() => setStatFilter(null)}>{t('app.clearFilter')}</button>
        </div>
      )}

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />}
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />}
          <input className="upt-search" type="text" placeholder={t('scripted.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : monitors.length === 0 ? (
        /* Boş durum: eskiden LoadingBlock ile (dönen spinner) gösteriliyordu — "yükleniyor" ile
           "hiç kayıt yok" görsel olarak ayrışmıyordu. */
        <StatusBlock icon={FlaskConical}
          title={k6.canManage ? t('scripted.noMonitorsAdmin') : t('scripted.noMonitors')} />
      ) : (
        <>
        <div className="upt-grid">
          {pager.pageItems.map(m => (
            <div key={m.id} className={`upt-card ${cardClass(m)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.name} />
                <span className="upt-port-tag">k6</span>
              </div>
              <div className="upt-card-domain" title={m.name}>{m.name}</div>
              {m.team_name && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: '.78em', color: 'var(--text-muted)', marginTop: 2 }}>
                  <Users size={12} />{m.team_name}
                </div>
              )}
              {m.group_name && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: '.78em', color: 'var(--text-muted)', marginTop: 2 }}>
                  <Layers size={12} />{m.group_name}
                </div>
              )}
              <div className="upt-card-divider" />
              <div className="upt-card-metrics">
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.duration_ms != null ? `${m.duration_ms}ms` : '—'}</span>
                  <span className="upt-metric-lbl">{t('scripted.lastDuration')}</span>
                </div>
                {(m.checks_passed != null || m.checks_failed != null) && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{m.checks_passed ?? 0}✓/{m.checks_failed ?? 0}✗</span>
                    <span className="upt-metric-lbl">{t('scripted.checks')}</span>
                  </div>
                )}
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDateSec(m.checked_at) : t('scripted.neverRun')}</span>
                {canManageRow(m) && (
                  <span style={{ display: 'flex', gap: 6 }} onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm mon-btn-check" disabled={checking === m.id || !k6.available}
                      onClick={() => checkNow(m)} title={t('scripted.runNow')}><Play size={12} /></button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('scripted.edit')}><Pencil size={12} /></button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openDuplicate(m)} title={t('mon.duplicate')} aria-label={t('mon.duplicate')}><Copy size={12} /></button>
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detail Modal — kanonik upt-modal + 4 sekme (Kontrol / Alarm / Grafik / Rehber&Notlar) ── */}
      {selected && createPortal(
        <div className="upt-modal-overlay" onClick={closeDetail}>
          <div className={`upt-modal upt-modal--${isPass(selected.status) ? 'up' : isFailLike(selected.status) ? 'down' : isWarnLike(selected.status) ? 'warn' : 'unknown'}`} onClick={e => e.stopPropagation()}>
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                {statusBadge(selected)}
                <span className="upt-modal-domain">{selected.name}</span>
              </div>
              {canManageRow(selected) && (
                <button className="btn btn-sm btn-primary" disabled={checking === selected.id || !k6.available}
                  onClick={() => checkNow(selected)}><Play size={14} />{t('scripted.runNow')}</button>
              )}
              {/* CSV butonu kaldırıldı: mükerrerdi ve bozuktu (tanımsız `history` → window.history →
                  "history.map is not a function"). Çalışan, sunucu-taraflı CSV linkini Kontrol
                  Geçmişi sekmesi zaten sunuyor (CheckHistoryTab). */}
              <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
              <button className="upt-modal-close" onClick={closeDetail}><X size={18} /></button>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric" title={t('scripted.sumUptimeHint')}>
                <span className="upt-modal-metric-val">{summary.total > 0 ? `%${Math.round((summary.total - summary.down) * 1000 / summary.total) / 10}` : '—'}</span>
                <span className="upt-modal-metric-lbl">{t('scripted.sumUptime')}{summary.total > 0 ? ` · ${summary.total - summary.down}/${summary.total}` : ''}</span>
              </div>
              <div className="upt-modal-metric" title={t('scripted.sumTotalHint')}><span className="upt-modal-metric-val">{summary.total}</span><span className="upt-modal-metric-lbl">{t('scripted.sumTotal')}</span></div>
              <div className="upt-modal-metric" title={t('scripted.sumIncidentsHint')}><span className="upt-modal-metric-val">{summary.down}</span><span className="upt-modal-metric-lbl">{t('scripted.sumIncidents')}</span></div>
              {selected.duration_ms != null && <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.duration_ms}ms</span><span className="upt-modal-metric-lbl">{t('scripted.lastDuration')}</span></div>}
              {(selected.checks_passed != null || selected.checks_failed != null) &&
                <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.checks_passed ?? 0}✓/{selected.checks_failed ?? 0}✗</span><span className="upt-modal-metric-lbl">{t('scripted.checks')}</span></div>}
              {selected.checked_at && <div className="upt-modal-metric"><span className="upt-modal-metric-val upt-modal-metric-time">{formatDateSec(selected.checked_at)}</span><span className="upt-modal-metric-lbl">{t('scripted.lastCheck')}</span></div>}
            </div>
            <div className="upt-modal-divider" />
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('hist.tab')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('scripted.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('scripted.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('scripted.tabGuide')}</button>
            </div>

            {detailTab === 'control' && (<>
              <CheckHistoryTab kind="scripted" monitorId={selected.id} listKey="scripted-history"
                columns={[t('scripted.colTime'), t('scripted.colStatus'), t('scripted.colDuration'), t('scripted.colDetail')]}
                onCounts={(c) => setSummary({ total: c.total, down: c.fail })}
                groupIdenticalErrors
                rowSignature={scriptedRowSignature}
                renderRow={(c) => {
                  const isSel = selCheck?.id === c.id
                  return (<>
                    <span className="upt-rt-time" style={{ cursor: 'pointer' }} onClick={() => setSelCheck(isSel ? null : c)}>{formatDateSec(c.checked_at)}</span>
                    <span className={isPass(c.status) ? 'upt-rt-up' : isWarnLike(c.status) ? 'upt-rt-warn' : 'upt-rt-down'} style={{ cursor: 'pointer', fontWeight: isSel ? 700 : undefined }}
                      onClick={() => setSelCheck(isSel ? null : c)}>{statusLabel(t, c.status)}</span>
                    <span className="upt-rt-ms">{c.duration_ms != null ? `${c.duration_ms}ms` : '—'}</span>
                    {c.error
                      /* Hata hücresi de paneli açar: kullanıcının ilk tıkladığı yer burası. */
                      ? <span className="upt-rt-error" title={c.error} style={{ cursor: 'pointer' }}
                          onClick={() => setSelCheck(isSel ? null : c)}>{c.error}</span>
                      : <span className="upt-rt-ms">{(c.checks_passed != null || c.checks_failed != null) ? `${c.checks_passed ?? 0}✓/${c.checks_failed ?? 0}✗` : '—'}</span>}
                  </>)
                }} />
              {selCheck && <CheckDetail t={t} check={selCheck} k6Version={k6.version} />}
            </>)}

            {detailTab === 'alerts' && <AlertHistory domain={selected.name} />}

            {detailTab === 'chart' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="scripted" />
              </Suspense>
            )}

            {detailTab === 'notes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="SCRIPTED" target={selected.name} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {modal && createPortal(<EditModal {...{ t, lang, k6Version: k6.version, form, setForm, modal, dupSource, saving, testing, testResult, saveWarnings, save, del, closeEdit, runTest, isAdminish, canDelete: modal?.id ? canDeleteRow(modal) : false, teamSelectOptions, teamName, groupSelectOptions, setEnvRow, addEnvRow, delEnvRow, applyTemplate }} />, document.body)}
    </div>
  )
}

/**
 * Seçili koşumun tanısı — İKİ KATMANLI sunum.
 *
 * Üstte insan-okur tek cümle (durum + çıkış kodu etiketi + varsa çare), altta katlanır teknik
 * detay (tam k6 çıktısı, kopyalanabilir). Eskiden ham backend mesajı düz kırmızı metin olarak
 * basılıyor, çıktı paneli ise sabit siyah zeminli satır-içi stille yazılıyordu (açık temada
 * sayfanın geri kalanıyla çelişen bir blok).
 */
function CheckDetail({ t, check, k6Version }) {
  const [openTech, setOpenTech] = useState(false)
  const [showAll, setShowAll] = useState(false)
  let checks = []
  // API snake_case; savunmacı çift-okuma.
  const checksJson = check.checks_json ?? check.checksJson
  const outputTail = check.output_tail ?? check.outputTail
  const exitCode = check.exit_code ?? check.exitCode
  try { if (checksJson) checks = JSON.parse(checksJson) } catch { /* bozuk json → boş liste */ }

  const label = exitLabel(t, exitCode)
  const hint = exitHint(t, exitCode)
  const engineHint = diagnosisHint(t, check, k6Version)
  const tone = isPass(check.status) ? 'success' : isWarnLike(check.status) ? 'warning' : 'danger'
  const lines = outputTail ? outputTail.split('\n') : []
  const CLAMP = 12
  const clamped = !showAll && lines.length > CLAMP
  const shown = clamped ? lines.slice(0, CLAMP).join('\n') : outputTail

  return (
    <div className="sc-detail">
      {checks.length > 0 && (
        <ul className="sc-checks">
          {checks.map((c, i) => (
            <li key={i} className="sc-check-row">
              <span className={c.passed ? 'sc-check-ok' : 'sc-check-bad'}>{c.passed ? '✓' : '✗'}</span> {c.name}
            </li>
          ))}
        </ul>
      )}

      {(check.error || label) && (
        <AlertBanner tone={tone} title={t('scripted.errTitle')}>
          {/* Çok satırlı hata = k6/Babel kod çerçevesi → monospace + yatay kaydırma, yoksa caret kayar. */}
          {check.error && <div className={`sc-err-msg${isCodeFrame(check.error) ? ' sc-err-msg--frame' : ''}`}>{check.error}</div>}
          {label && <div className="sc-err-exit">{label}{exitCode != null ? ` · exit ${exitCode}` : ''}</div>}
          {hint && <div className="sc-err-hint">{hint}</div>}
          {engineHint && <div className="sc-err-hint">{engineHint}</div>}
        </AlertBanner>
      )}

      {outputTail && (
        <div className="sc-tech">
          <button type="button" className="mhow-toggle sc-tech-toggle" aria-expanded={openTech}
            onClick={() => setOpenTech(o => !o)}>
            <Terminal size={15} /><span>{t('scripted.errTech')}</span>
            <ChevronDown size={15} className={`mhow-chev${openTech ? ' open' : ''}`} />
          </button>
          {openTech && (
            <>
              <div className="sc-tech-actions">
                {/* Tam metin her zaman kopyalanabilir — kırpılmış hâli değil. */}
                <CopyButton value={outputTail} label={t('scripted.errCopy')} copiedLabel={t('scripted.errCopied')} />
                {lines.length > CLAMP && (
                  <button type="button" className="btn btn-sm" onClick={() => setShowAll(v => !v)}>
                    {showAll ? t('scripted.outShowLess') : t('scripted.outShowAll', lines.length)}
                  </button>
                )}
              </div>
              <pre className="show-pre sc-output">{shown}{clamped ? '\n…' : ''}</pre>
            </>
          )}
        </div>
      )}
    </div>
  )
}

// ── Create/Edit modal ────────────────────────────────────────────────────────
function EditModal({ t, lang, k6Version, form, setForm, modal, dupSource, saving, testing, testResult, saveWarnings, save, del, closeEdit, runTest, isAdminish, canDelete, teamSelectOptions, teamName, groupSelectOptions, setEnvRow, addEnvRow, delEnvRow, applyTemplate }) {
  const ivIdx = intervalIdx(Number(form.intervalSeconds))
  return (
    <div className="modal-overlay">
      <div className="modal-box" style={{ maxWidth: 860, width: '92vw', maxHeight: '92vh', overflowY: 'auto' }} onClick={e => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--port">
          <div className="modal-icon-hdr-badge"><FlaskConical size={20} /></div>
          <h3>{modal.id ? t('scripted.modalEdit') : t('scripted.modalNew')}
            {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
        </div>
        {dupSource && <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>}
        <div className="form-grid form-grid--top">
          <label className="full-width"><span>{t('scripted.name')} <span className="req-star">*</span></span>
            <input value={form.name} autoFocus={!!dupSource}
              onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
          <label className="full-width"><span>{t('scripted.description')}</span>
            <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} /></label>

          <label><span>{t('scripted.team')} <span className="req-star">*</span></span>
            {isAdminish
              ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))}
                  options={[{ value: '', label: t('scripted.selectTeam') }, ...teamSelectOptions]} searchThreshold={2} />
              : <input value={teamName || t('scripted.selectTeam')} disabled />}</label>
          <label><span>{t('scripted.group')} <span className="req-star">*</span></span>
            <SearchableSelect
              value={form.groupName}
              onChange={v => setForm(f => ({ ...f, groupName: v }))}
              options={groupSelectOptions}
              creatable
              onCreate={() => {}}
              searchThreshold={2}
              placeholder={t('scripted.groupPick')} /></label>

          <label><span>{t('scripted.interval')}</span>
            <input type="range" min="0" max={INTERVALS.length - 1} value={ivIdx}
              onChange={e => setForm(f => ({ ...f, intervalSeconds: INTERVALS[Number(e.target.value)].value }))} />
            <span className="field-hint">{t(INTERVALS[ivIdx].k)}</span></label>
          <label><span>{t('scripted.timeout')}</span>
            <input type="number" min="5" max="180" value={form.timeoutSeconds}
              onChange={e => setForm(f => ({ ...f, timeoutSeconds: e.target.value }))} />
            <span className="field-hint">{t('scripted.timeoutHint')}</span></label>
          <label><span>{t('scripted.confirmAttempts')}</span>
            <input type="number" min="0" max="10" value={form.confirmAttempts}
              onChange={e => setForm(f => ({ ...f, confirmAttempts: e.target.value }))} />
            <span className="field-hint">{t('scripted.confirmHint')}</span></label>
          <label><span>{t('scripted.recoveryChecks')}</span>
            <input type="number" min="1" max="10" value={form.recoveryChecks}
              onChange={e => setForm(f => ({ ...f, recoveryChecks: e.target.value }))} />
            <span className="field-hint">{t('scripted.recoveryHint')}</span></label>

          {/* Etiketler — kanonik TagInput (diğer tiplerle parite; payload'daki tags alanını doldurur) */}
          <div className="full-width">
            <div className="kw-block-title">{t('scripted.tagsTitle')}</div>
            <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('scripted.tagsPlaceholder')} />
            <span className="field-hint">{t('scripted.tagsHint')}</span>
          </div>

          {/* Şablon seçici */}
          <div className="full-width">
            <div className="kw-block-title">{t('scripted.template')}</div>
            <select value={form.template || ''} onChange={e => {
              const v = e.target.value
              setForm(f => ({ ...f, template: v }))
              if (v) applyTemplate(v)
              else setForm(f => ({ ...f, script: '', env: [] }))   // "Bir şablon seçin" → script + env temizlenir
            }}>
              <option value="">{t('scripted.templatePick')}</option>
              {SCRIPTED_TEMPLATES.map(tp => <option key={tp.id} value={tp.id}>{tp.name[lang] || tp.name.en}</option>)}
            </select>
          </div>

          {/* Script editörü */}
          <div className="full-width">
            <div className="kw-block-title">{t('scripted.script')}</div>
            <CodeEditor value={form.script} onChange={code => setForm(f => ({ ...f, script: code }))} placeholder={t('scripted.scriptPlaceholder')} />
            <span className="field-hint">{t('scripted.scriptHint')}</span>
          </div>

          {/* Env değişkenleri */}
          <div className="full-width">
            <div className="kw-block-title">{t('scripted.env')}</div>
            {form.env.length > 0 &&
              <div className="env-list">
                {form.env.map((e, i) => (
                  <div key={i} className="env-row">
                    <input className="input env-name" placeholder={t('scripted.envName')} value={e.name}
                      onChange={ev => setEnvRow(i, { name: ev.target.value })} />
                    <input className="input env-val" type={e.secret ? 'password' : 'text'} autoComplete="new-password"
                      placeholder={e.secret ? (e.value_set ? t('scripted.envSecretSet') : t('scripted.envSecretEmpty')) : t('scripted.envValue')}
                      value={e.value} onChange={ev => setEnvRow(i, { value: ev.target.value })} />
                    <label className="checkbox-label env-secret" title={t('scripted.envSecret')}>
                      <input type="checkbox" checked={e.secret} onChange={ev => setEnvRow(i, { secret: ev.target.checked, value: '' })} />
                      {e.secret ? <EyeOff size={14} /> : <Eye size={14} />} {t('scripted.envSecret')}
                    </label>
                    <button type="button" className="icon-btn env-del" title={t('scripted.delete')} onClick={() => delEnvRow(i)}><Trash2 size={15} /></button>
                  </div>
                ))}
              </div>}
            <button type="button" className="btn btn-secondary btn-sm env-add" onClick={addEnvRow}><Plus size={13} /> {t('scripted.envAdd')}</button>
            <span className="field-hint">{t('scripted.envHint')}</span>
          </div>

          <label className="checkbox-label full-width">
            <input type="checkbox" checked={form.notifyEmail} onChange={e => setForm(f => ({ ...f, notifyEmail: e.target.checked }))} /> {t('scripted.notifyEmail')}</label>
          <label className="checkbox-label full-width">
            <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} /> {t('scripted.active')}</label>

          {/* Kaydetme uyarıları — inline ve KALICI (toast değil): kullanıcı düzeltene kadar durmalı. */}
          {saveWarnings?.length > 0 &&
            <div className="full-width">
              <AlertBanner tone="warning" title={t('scripted.saveWarnTitle')}>
                <ul className="sc-warn-list">{saveWarnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
              </AlertBanner>
            </div>}

          {/* Test sonucu — kaydetmeden önce iterasyon yapmanın TEK yolu, gerçek bir konsol olmalı.
              Çıktı bloğu tema-uyumlu .show-pre; hata metni insan-okur özet + çıkış kodu etiketi. */}
          {testResult &&
            <div className="full-width sc-testrun">
              <div className="sc-testrun-head">
                <span className="sc-testrun-status" style={{ color: STATUS_COLOR[testResult.status] || 'inherit' }}>
                  {statusLabel(t, testResult.status)}</span>
                {testResult.checks_passed != null &&
                  <span className="sc-testrun-meta">{testResult.checks_passed}✓/{testResult.checks_failed ?? 0}✗</span>}
                {testResult.duration_ms != null && <span className="sc-testrun-meta">· {testResult.duration_ms} ms</span>}
                {exitLabel(t, testResult.exit_code) &&
                  <span className="sc-testrun-meta">· {exitLabel(t, testResult.exit_code)}</span>}
                {testResult.output_tail &&
                  <CopyButton value={testResult.output_tail} label={t('scripted.errCopy')} copiedLabel={t('scripted.errCopied')} />}
              </div>
              {testResult.error &&
                <AlertBanner tone={isPass(testResult.status) ? 'success' : isWarnLike(testResult.status) ? 'warning' : 'danger'}>
                  <div className={`sc-err-msg${isCodeFrame(testResult.error) ? ' sc-err-msg--frame' : ''}`}>{testResult.error}</div>
                  {exitHint(t, testResult.exit_code) && <div className="sc-err-hint">{exitHint(t, testResult.exit_code)}</div>}
                  {/* Kaydetmeden ÖNCE görülmeli: sözdizimi duvarına çarpan kullanıcı burada anlasın. */}
                  {diagnosisHint(t, testResult, k6Version) &&
                    <div className="sc-err-hint">{diagnosisHint(t, testResult, k6Version)}</div>}
                </AlertBanner>}
              {testResult.output_tail && <pre className="show-pre sc-console">{testResult.output_tail}</pre>}
            </div>}
        </div>
        <div className="modal-actions">
          <div style={{ display: 'flex', gap: 8, marginRight: 'auto' }}>
            <button className="btn btn-secondary" onClick={runTest} disabled={testing} aria-busy={testing}>
              {testing ? <Spinner size={14} inline decorative /> : <FlaskConical size={14} />}
              {testing ? t('scripted.testing') : t('scripted.testRun')}</button>
            {modal.id && canDelete && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('scripted.delete')}</button>}
          </div>
          <button className="btn btn-secondary" onClick={closeEdit}>{t('scripted.cancel')}</button>
          <button className="btn btn-primary" onClick={save} disabled={saving || !form.name.trim() || !form.teamId || !form.groupName.trim()}>{saving ? '...' : t('scripted.save')}</button>
        </div>
      </div>
    </div>
  )
}
