import { useCallback, useEffect, useMemo, useState, useRef } from 'react'
import {
  Save, Send, BellRing, Plus, Trash2, Copy, RefreshCw, Search as SearchIcon,
  Crown, UserCog, Briefcase, Globe, Network, Target, Radio, CalendarDays,
  ScanSearch, FlaskConical, Gauge, ShieldCheck, WifiOff, Timer, CalendarClock,
  ArrowLeftRight, CheckCircle2, OctagonPause, Check,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner, LoadingBlock } from '../ui/Progress.jsx'
import TagInput from '../ui/TagInput.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import HelpTip from '../ui/HelpTip.jsx'
import { formatDateSec } from '../../api/client'
import { copyText } from '../../utils/copyText.js'

/**
 * Kişi-bazlı Webhook Bildirimleri — mail hattından TAMAMEN bağımsız ikinci kanalın yönetimi.
 *
 * Tasarım dili (namethatui desenleri, proje dağarcığına uyarlanmış):
 * - Aç/kapa durumları CHECKBOX değil SWITCH (perm-pill — PermissionMatrix'teki pill switch'in
 *   aynısı): "Switch vs Checkbox" ayrımı — bunlar anında etkiyen durum anahtarları, form seçimi değil.
 * - Unvan grupları SEÇİLEBİLİR KART: ikon + ad + açıklama + switch + seviye rozeti; kart
 *   açıkken vurgu kenarlığı alır. Desen listesi kartın içinde (Token Field = TagInput).
 * - Tip/takım matrisi TOGGLE CHIP GRUBU: ikonlu, basılabilir çipler (aria-pressed) — 10 tip
 *   Nav'daki ikonlarıyla; kapalı çip soluk kalır, açık çip dolgulu.
 * - Şablon önizlemesi PUSH BİLDİRİM MAKETİ: kullanıcı metni tam olarak telefonda görüneceği
 *   biçimde görür — düz italik satırdan çok daha az soyut.
 * - İstatistik şeridi KPI kartları (chg-kpi görsel dili); kanal kapalıyken CALLOUT.
 *
 * Sır sözleşmesi değişmedi: başlık değerleri sunucudan MASKELİ gelir; kullanıcı değiştirmedikçe
 * maskeli değer geri gönderilir ve sunucu eski şifreli değeri korur (write-only).
 */

const KEY = (k) => `site.monitor.userpush.${k}`
// 'cert' sunucuda VAR (UserPushService.DEFAULT_TEMPLATES) ama listede yoktu: sertifika
// guvenlik alarminin {ip}/{cn} kanitini tasiyan sablon duzenlenemiyor, onizlenemiyor ve
// test gonderiminde secilemiyordu.
const TEMPLATE_KEYS = ['down', 'slow', 'expiry', 'changed', 'cert', 'resolved', 'test']
const STATUS_OPTIONS = ['SENT', 'FAILED', 'PENDING', 'RATE_LIMITED', 'CIRCUIT_OPEN',
  'SKIPPED_TYPE_OFF', 'SKIPPED_TEAM_OFF', 'SKIPPED_MONITOR_OFF', 'SKIPPED_QUIET_HOURS',
  'SKIPPED_REALERT_OFF', 'SKIPPED_NO_RECIPIENTS', 'SKIPPED_USER_OPT_OUT', 'SKIPPED_NO_PRIOR']
const TRIGGERS = ['OPEN', 'ESCALATION', 'RE_ALERT', 'RESOLVE', 'RESEND', 'TEST']

/** İzleme tipleri — ikonlar Nav/ChangeKindCards ile AYNI: kullanıcı yeni görsel dil öğrenmez. */
const TYPES = [
  { key: 'cert', Icon: ShieldCheck }, { key: 'http', Icon: Globe }, { key: 'port', Icon: Network },
  { key: 'dns', Icon: SearchIcon }, { key: 'keyword', Icon: Target }, { key: 'ping', Icon: Radio },
  { key: 'domain', Icon: CalendarDays }, { key: 'page', Icon: ScanSearch },
  { key: 'scripted', Icon: FlaskConical }, { key: 'pagespeed', Icon: Gauge },
]

/** Unvan grubu kartları: ikon + kalıcı görsel kimlik. */
/** Org rolü kodları — UserEditModal'daki seçenek listesiyle birebir (usr.orgRoleVal.*). */
const ORG_ROLES = ['TECH', 'PO', 'MANAGER', 'BOLUM_BASKANI', 'CLEVEL']

const GROUP_META = {
  yonetici: { Icon: Crown },
  uzman: { Icon: UserCog },
  po: { Icon: Briefcase },
}

/** Şablon aileleri: ikon + ton — önizleme maketinin vurgu rengi buradan. */
const TEMPLATE_META = {
  down: { Icon: WifiOff, tone: 'danger' },
  slow: { Icon: Timer, tone: 'warn' },
  expiry: { Icon: CalendarClock, tone: 'warn' },
  changed: { Icon: ArrowLeftRight, tone: 'info' },
  cert: { Icon: ShieldCheck, tone: 'danger' },
  resolved: { Icon: CheckCircle2, tone: 'ok' },
  test: { Icon: FlaskConical, tone: 'info' },
}

/** Önizleme örnek değerleri — sunucudaki sendTest örnekleriyle aynı dil. */
const PREVIEW_VALS = {
  seviye: 'KRİTİK', ad: 'Örnek İzleme', hedef: 'example.com', neden: 'bağlantı zaman aşımı',
  metrik: 'yanıt süresi', deger: '1200ms', esik: '1000ms', ne: 'sertifika', gun: '30',
  tarih: '2026-12-31', degisen: 'kayıt', sure: '25 dk', saat: '14:03',
  baslangic: '13:38', bitis: '14:03', ip: '192.0.2.10', cn: 'ornek.example.com',
}

function preview(template) {
  let out = template || ''
  for (const [k, v] of Object.entries(PREVIEW_VALS)) out = out.replaceAll(`{${k}}`, v)
  return out.replace(/\s{2,}/g, ' ').trim()
}

/** perm-pill switch — PermissionMatrix/TeamManager'daki pill'in birebir aynısı. */
function PillSwitch({ on, onToggle, label, disabled }) {
  return (
    <button type="button" role="switch" aria-checked={!!on} aria-label={label} title={label}
      disabled={disabled}
      className={`perm-pill ${on ? 'perm-pill-on' : 'perm-pill-off'}`}
      onClick={onToggle}>
      <span className="perm-pill-knob" />
    </button>
  )
}

/** Toggle chip — basılabilir ikonlu seçim çipi (aria-pressed; kapalı = soluk). */
function ToggleChip({ on, onToggle, Icon, label }) {
  return (
    <button type="button" className={`up-chip${on ? ' up-chip--on' : ''}`}
      aria-pressed={!!on} onClick={onToggle}>
      {Icon && <Icon size={14} aria-hidden="true" />}
      <span>{label}</span>
      {on && <Check size={13} className="up-chip-check" aria-hidden="true" />}
    </button>
  )
}

/** Push bildirim maketi — şablonun telefonda görüneceği hâli. */
function NotifPreview({ title, message, tone }) {
  const t = useT()
  return (
    <div className={`up-notif up-notif--${tone || 'info'}`}>
      <div className="up-notif-head">
        <span className="up-notif-appdot"><BellRing size={11} aria-hidden="true" /></span>
        <span className="up-notif-app">{title || 'Site Monitor'}</span>
        <span className="up-notif-when">{t('userpush.previewNow')}</span>
      </div>
      <div className="up-notif-msg">{message || '—'}</div>
    </div>
  )
}

export default function UserPushSettings() {
  const t = useT()
  const toast = useToast()

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [settings, setSettings] = useState({})
  const [headers, setHeaders] = useState([])
  const [scopes, setScopes] = useState([])
  const [defaults, setDefaults] = useState({ templates: {}, placeholders: [] })
  const [health, setHealth] = useState({})
  const [roleGroups, setRoleGroups] = useState({})
  const [teams, setTeams] = useState([])
  const [teamQuery, setTeamQuery] = useState('')
  const [stats, setStats] = useState(null)

  // Test kartı
  const [testSicils, setTestSicils] = useState('')
  const [testTemplate, setTestTemplate] = useState('test')
  const [testResult, setTestResult] = useState(null)
  const [testing, setTesting] = useState(false)

  // Teslimat günlüğü
  const [rows, setRows] = useState(null)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(25)   // secici artik CANLI (bkz. PaginationBar)
  const [fUser, setFUser] = useState('')
  const [fStatus, setFStatus] = useState('')
  const [fTrigger, setFTrigger] = useState('')
  const [fNotifId, setFNotifId] = useState('')
  // 2026-09-11 (kullanıcı): KPI kartları tıklanabilir — "kime, ne zaman, ne içerikle gitti" sorusu
  // aynı sayfadaki teslimat günlüğünde cevaplanır: kart pencereyi (24h/7d) süzgeç olarak uygular,
  // SENT/FAILED sayıları ayrıca durumu seçer, liste o bloğa kaydırılır.
  const [fWindow, setFWindow] = useState('')   // '' | '24h' | '7d'
  // 2026-09-11 (kullanıcı): "aynı takımdaki üyeye push gitmiyor, nedenini göremiyorum". Alıcı çözümünün
  // açıklamalı hâli: takım + seviye seç → her üye için karar (alır / grup yok / seviye altı / opt-out / pasif).
  const [exTeam, setExTeam] = useState('')
  const [exLevel, setExLevel] = useState('HIGH')
  const [exRows, setExRows] = useState(null)
  const [exLoading, setExLoading] = useState(false)
  useEffect(() => {
    if (!exTeam) { setExRows(null); return }
    let alive = true
    setExLoading(true)
    Promise.resolve(api.admin.userPush.explain?.(exTeam, exLevel))
      .then(r => { if (alive) setExRows(r?.success ? (r.data?.members || []) : []) })
      .catch(() => { if (alive) setExRows([]) })
      .finally(() => { if (alive) setExLoading(false) })
    return () => { alive = false }
  }, [exTeam, exLevel])
  const logRef = useRef(null)
  const windowFrom = (w) => {
    const ms = w === '24h' ? 24 * 3600e3 : w === '7d' ? 7 * 86400e3 : 0
    return ms ? new Date(Date.now() - ms).toISOString().slice(0, 19) : null   // createdAt biçimi yyyy-MM-ddTHH:mm:ss (UTC, Z'siz)
  }
  const pickWindow = (w, status) => {
    setFWindow(w)
    if (status !== undefined) setFStatus(status)
    setPage(0)
    setTimeout(() => logRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }), 0)
  }
  const [openRow, setOpenRow] = useState(null)
  const [userTeams, setUserTeams] = useState({})
  // 2026-09-11 (kullanıcı): test gönderiminden sonra günlüğü ELLE tazelemek gerekiyordu. Gönderim
  // ASENKRON (kuyruk → HTTP → SENT/FAILED), tek tazeleme PENDING'i yakalar; bu yüzden kısa bir
  // zincir: hemen, 1,5 sn ve 4 sn sonra. Poll'lar SESSİZ (liste boşalmaz, yalnız içerik değişir).
  const [logNonce, setLogNonce] = useState(0)
  const quietRef = useRef(false)
  const pollRef = useRef([])
  useEffect(() => () => pollRef.current.forEach(clearTimeout), [])
  const refreshLogSoon = useCallback(() => {
    pollRef.current.forEach(clearTimeout)
    pollRef.current = [0, 1500, 4000].map((ms) => setTimeout(() => {
      quietRef.current = true
      setLogNonce((n) => n + 1)
    }, ms))
  }, [])

  const enabled = settings[KEY('enabled')] === 'true'

  const val = (k, fallback = '') => settings[KEY(k)] ?? fallback
  const setVal = (k, v) => setSettings((s) => ({ ...s, [KEY(k)]: v }))

  useEffect(() => { load() }, [])   // eslint-disable-line react-hooks/exhaustive-deps

  async function load() {
    setLoading(true)
    try {
      const [res, teamsRes, statsRes] = await Promise.all([
        api.admin.userPush.getSettings(),
        api.admin.getTeams().catch(() => null),
        api.admin.userPush.getStats().catch(() => null),
      ])
      if (res?.success) {
        const d = res.data
        setSettings(d.settings || {})
        setHeaders(Array.isArray(d.settings?.[KEY('headers')]) ? d.settings[KEY('headers')] : [])
        setScopes(d.scopes || [])
        setDefaults(d.defaults || { templates: {}, placeholders: [] })
        setHealth(d.health || {})
        try { setRoleGroups(JSON.parse(d.settings?.[KEY('role-groups')] || '') || {}) }
        catch { setRoleGroups(defaultGroups()) }
      } else {
        toast.error(res?.error || t('settings.loadError'))
      }
      if (teamsRes?.success) setTeams(Array.isArray(teamsRes.data) ? teamsRes.data : [])
      if (statsRes?.success) setStats(statsRes.data)
    } finally {
      setLoading(false)
    }
  }

  // Gruplar ORG ROLÜYLE eşleşir (ürün kararı 2026-09-11): unvan metni okunmaz — aynı rolde onlarca
  // farklı unvan var, desen listesi hiç tam olmuyordu. Org rolü AD kademesinden türer (PO / D6 →
  // MANAGER / D7 → BOLUM_BASKANI / diğer → TECH) ve kullanıcı ekranından elle sabitlenebilir.
  function defaultGroups() {
    return {
      yonetici: { enabled: false, source: 'orgRole', patterns: ['MANAGER', 'BOLUM_BASKANI', 'CLEVEL'], minLevel: 'HIGH' },
      uzman: { enabled: false, source: 'orgRole', patterns: ['TECH'], minLevel: 'WARNING' },
      po: { enabled: false, source: 'orgRole', patterns: ['PO'], minLevel: 'WARNING' },
    }
  }
  // Eski kayıt (source:'title' + unvan desenleri) ekrana geldiğinde grup anahtarının org-rol kümesine
  // çevrilir — backend aynı çeviriyi okurken yapıyor; kaydet'e basınca yeni biçim kalıcılaşır.
  function normalizeGroups(g) {
    const defs = defaultGroups()
    const out = {}
    for (const [key, v] of Object.entries(g || {})) {
      out[key] = v?.source === 'orgRole'
        ? { ...v }
        : { ...v, source: 'orgRole', patterns: defs[key]?.patterns || v?.patterns || [] }
    }
    return out
  }

  const groupsSafe = Object.keys(roleGroups).length ? normalizeGroups(roleGroups) : defaultGroups()

  async function save() {
    setSaving(true)
    try {
      const body = { ...settings }
      body[KEY('headers')] = headers
      body[KEY('role-groups')] = JSON.stringify(groupsSafe)
      const res = await api.admin.userPush.saveSettings(body)
      if (res?.success) {
        toast.success(t('userpush.saved'))
        setSettings(res.data?.settings || {})
        setHeaders(Array.isArray(res.data?.settings?.[KEY('headers')]) ? res.data.settings[KEY('headers')] : [])
      } else {
        toast.error(res?.error || t('settings.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  async function saveScopeRows(rowsToSave) {
    const res = await api.admin.userPush.saveScopes(rowsToSave)
    if (res?.success) setScopes(res.data?.data ?? res.data ?? [])
    else toast.error(res?.error || t('settings.saveError'))
  }

  const toggleScope = (scopeType, scopeKey, current) =>
    saveScopeRows([{ scopeType, scopeKey: String(scopeKey), enabled: !current }])

  const scopeOn = useCallback((type, key) => {
    // Sunucu yanıtı SNAKE_CASE (global Jackson ayarı) — İSTEK gövdesi Map olduğu için camelCase kalır.
    const row = scopes.find((s) => s.scope_type === type && s.scope_key === String(key))
    return row ? !!row.enabled : true   // kayıt yok = AÇIK (sunucuyla aynı kural)
  }, [scopes])

  /** Toplu aç/kapa — yalnız GÖRÜNEN (süzülmüş) takımlar; süzgeç açıkken sürpriz kapsam yok. */
  const visibleTeams = useMemo(() => {
    const q = teamQuery.trim().toLowerCase()
    return q ? teams.filter((tm) => (tm.name || '').toLowerCase().includes(q)) : teams
  }, [teams, teamQuery])

  const bulkTeams = (enabledVal) =>
    saveScopeRows(visibleTeams.map((tm) => ({ scopeType: 'TEAM', scopeKey: String(tm.id), enabled: enabledVal })))

  async function sendTest() {
    const usernames = testSicils.split(',').map((s) => s.trim()).filter(Boolean)
    if (!usernames.length) { toast.error(t('userpush.testNeedSicil')); return }
    setTesting(true)
    try {
      const res = await api.admin.userPush.sendTest({ usernames, template: testTemplate })
      if (res?.success) {
        setTestResult(res.data?.data ?? res.data)
        toast.success(t('userpush.testQueued'))
        setPage(0)            // yeni satır ilk sayfada doğar
        refreshLogSoon()      // durum PENDING → SENT/FAILED akışını ekranda göster
      }
      else toast.error(res?.error || t('userpush.testFailed'))
    } finally {
      setTesting(false)
    }
  }

  // SON-ISTEK-KAZANIR: fUser/fNotifId metin filtreleri HER TUSTA istek atiyor. Yavas (eski) yanit
  // hizlidan sonra donerse bayat listeyi yaziyordu. Sira sayaci, gecikmis yanitlari sessizce eler.
  const seqRef = useRef(0)

  const loadDeliveries = useCallback(async () => {
    const mySeq = ++seqRef.current
    if (!quietRef.current) setRows(null)   // sessiz poll listeyi boşaltmaz (titreme olmasın)
    quietRef.current = false
    const params = { page, size: pageSize }
    if (fUser) params.username = fUser
    if (fStatus) params.status = fStatus
    if (fTrigger) params.trigger = fTrigger
    if (fNotifId) params.notificationId = fNotifId
    const from = windowFrom(fWindow)
    if (from) params.from = from
    const res = await api.admin.userPush.getDeliveries(params)
    if (mySeq !== seqRef.current) return   // daha yeni bir istek var -> bu yaniti YOK SAY
    if (res?.success) {
      setRows(res.data?.deliveries || [])
      setTotal(res.data?.total || 0)
      setUserTeams(res.data?.user_teams || {})
    } else {
      setRows([])
    }
  }, [page, pageSize, fUser, fStatus, fTrigger, fNotifId, fWindow, logNonce])   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { loadDeliveries() }, [loadDeliveries])

  if (loading) {
    return <div className="admin-section"><Spinner size={20} inline decorative /> {t('settings.loading')}</div>
  }

  const statusTone = (st) => st === 'SENT' ? 'ok' : (st === 'FAILED' || st === 'CIRCUIT_OPEN') ? 'danger'
    : st === 'PENDING' ? 'info' : 'muted'
  const k24 = stats?.last24h || {}
  const k7 = stats?.last7d || {}

  return (
    <div className="ldap-settings userpush-settings">
      {/* ── Başlık + KPI şeridi ── */}
      <div className="admin-section">
        <h3><BellRing size={18} style={{ verticalAlign: '-3px' }} /> {t('userpush.title')}</h3>
        <p className="section-desc">{t('userpush.desc')}</p>
        {stats && (
          <div className="userpush-stats-row up-kpis">
            <div className={`up-kpi up-kpi--btn${fWindow === '24h' ? ' is-active' : ''}`} role="button" tabIndex={0}
              title={t('userpush.kpiHint')} aria-pressed={fWindow === '24h'}
              onClick={() => pickWindow('24h', '')}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pickWindow('24h', '') } }}>
              <span className="up-kpi-label">{t('userpush.stat24h')}</span>
              <span className="up-kpi-nums">
                <b className="up-kpi-ok up-kpi-num" role="button" tabIndex={0} onClick={(e) => { e.stopPropagation(); pickWindow('24h', 'SENT') }}>{k24.SENT || 0}</b><small>SENT</small>
                <b className="up-kpi-fail up-kpi-num" role="button" tabIndex={0} onClick={(e) => { e.stopPropagation(); pickWindow('24h', 'FAILED') }}>{k24.FAILED || 0}</b><small>FAILED</small>
              </span>
            </div>
            <div className={`up-kpi up-kpi--btn${fWindow === '7d' ? ' is-active' : ''}`} role="button" tabIndex={0}
              title={t('userpush.kpiHint')} aria-pressed={fWindow === '7d'}
              onClick={() => pickWindow('7d', '')}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pickWindow('7d', '') } }}>
              <span className="up-kpi-label">{t('userpush.stat7d')}</span>
              <span className="up-kpi-nums">
                <b className="up-kpi-ok up-kpi-num" role="button" tabIndex={0} onClick={(e) => { e.stopPropagation(); pickWindow('7d', 'SENT') }}>{k7.SENT || 0}</b><small>SENT</small>
                <b className="up-kpi-fail up-kpi-num" role="button" tabIndex={0} onClick={(e) => { e.stopPropagation(); pickWindow('7d', 'FAILED') }}>{k7.FAILED || 0}</b><small>FAILED</small>
              </span>
            </div>
            {health.circuit_open && (
              <div className="up-kpi up-kpi--circuit">
                <OctagonPause size={16} aria-hidden="true" />
                <span>{t('userpush.circuitOpen')}</span>
              </div>
            )}
          </div>
        )}
      </div>

      {/* ── Global anahtar — switch + durum callout'u ── */}
      <div className="admin-section up-master">
        <div className="up-master-row">
          <PillSwitch on={enabled} label={t('userpush.enabled')}
            onToggle={() => setVal('enabled', enabled ? 'false' : 'true')} />
          <div>
            <div className="up-master-title">{t('userpush.enabled')}
              <HelpTip helpKey="help.set.site.monitor.userpush.enabled" label={t('userpush.enabled')} /></div>
            <p className="hint" style={{ margin: 0 }}>{t('userpush.enabledHint')}</p>
          </div>
        </div>
        {!enabled && (
          <div className="alert-msg ldap-lookup-error up-callout" style={{ marginTop: 10 }}>
            <OctagonPause size={15} aria-hidden="true" /> {t('userpush.disabledWarn')}
          </div>
        )}
      </div>

      <div className={enabled ? undefined : 'up-dimmed'}>
        {/* ── Bağlantı ── */}
        <div className="admin-section">
          <h4 className="ldap-subhdr">{t('userpush.connTitle')}</h4>
          <p className="section-desc">{t('userpush.connDesc')}</p>
          <label className="threshold-field"><span className="help-label-row">{t('userpush.url')}<HelpTip helpKey="help.set.site.monitor.userpush.url" label={t('userpush.url')} /></span>
            <input type="text" className="input" value={val('url')} placeholder="http://..."
              onChange={(e) => setVal('url', e.target.value)} />
          </label>
          <div className="userpush-grid2">
            <label className="threshold-field"><span className="help-label-row">{t('userpush.pipeline')}<HelpTip helpKey="help.set.site.monitor.userpush.pipeline" label={t('userpush.pipeline')} /></span>
              <input type="text" className="input" value={val('pipeline')} placeholder=""
                onChange={(e) => setVal('pipeline', e.target.value)} />
            </label>
            <label className="threshold-field"><span className="help-label-row">{t('userpush.titleField')}<HelpTip helpKey="help.set.site.monitor.userpush.title" label={t('userpush.titleField')} /></span>
              <input type="text" className="input" value={val('title', 'Site Monitor')}
                onChange={(e) => setVal('title', e.target.value)} />
            </label>
          </div>

          <h5 className="userpush-subsub">{t('userpush.headers')}<HelpTip helpKey="help.set.site.monitor.userpush.headers" label={t('userpush.headers')} /></h5>
          <p className="hint">{t('userpush.headersHint')}</p>
          {headers.map((h, i) => (
            <div key={i} className="userpush-header-row">
              <input type="text" className="input" placeholder={t('userpush.headerName')} value={h.name || ''}
                onChange={(e) => setHeaders(headers.map((x, j) => j === i ? { ...x, name: e.target.value } : x))} />
              <input type={h.secret ? 'password' : 'text'} className="input" placeholder={t('userpush.headerValue')}
                value={h.value || ''}
                onChange={(e) => setHeaders(headers.map((x, j) => j === i ? { ...x, value: e.target.value } : x))} />
              <label className="checkbox-label userpush-secret-toggle">
                <input type="checkbox" checked={!!h.secret}
                  onChange={(e) => setHeaders(headers.map((x, j) => j === i ? { ...x, secret: e.target.checked } : x))} />
                <span>{t('userpush.headerSecret')}</span>
              </label><HelpTip helpKey="help.userpush.headerRow" label={t('userpush.headerSecret')} />
              <button type="button" className="btn btn-sm" aria-label="Sil"
                onClick={() => setHeaders(headers.filter((_, j) => j !== i))}><Trash2 size={14} /></button>
            </div>
          ))}
          <button type="button" className="btn btn-sm" onClick={() => setHeaders([...headers, { name: '', value: '', secret: true }])}>
            <Plus size={14} /> {t('userpush.addHeader')}
          </button>

          <div className="userpush-grid4" style={{ marginTop: 14 }}>
            <label className="threshold-field"><span className="help-label-row">{t('userpush.timeoutConnect')}<HelpTip helpKey="help.set.site.monitor.userpush.timeout-connect-seconds" label={t('userpush.timeoutConnect')} /></span>
              <input type="number" className="input" min={1} max={30} value={val('timeout-connect-seconds', '3')}
                onChange={(e) => setVal('timeout-connect-seconds', e.target.value)} />
            </label>
            <label className="threshold-field"><span className="help-label-row">{t('userpush.timeoutTotal')}<HelpTip helpKey="help.set.site.monitor.userpush.timeout-total-seconds" label={t('userpush.timeoutTotal')} /></span>
              <input type="number" className="input" min={1} max={60} value={val('timeout-total-seconds', '5')}
                onChange={(e) => setVal('timeout-total-seconds', e.target.value)} />
            </label>
            <label className="threshold-field"><span className="help-label-row">{t('userpush.retryMax')}<HelpTip helpKey="help.set.site.monitor.userpush.retry-max" label={t('userpush.retryMax')} /></span>
              <input type="number" className="input" min={0} max={5} value={val('retry-max', '2')}
                onChange={(e) => setVal('retry-max', e.target.value)} />
            </label>
            <label className="threshold-field"><span className="help-label-row">{t('userpush.hourlyCap')}<HelpTip helpKey="help.set.site.monitor.userpush.hourly-cap" label={t('userpush.hourlyCap')} /></span>
              <input type="number" className="input" min={1} max={500} value={val('hourly-cap', '30')}
                onChange={(e) => setVal('hourly-cap', e.target.value)} />
            </label>
            {/* Mesaj uzunlugu: ikisi de gomulu sabitti. Ust sinirlar bilerek dar - max-message
                urun sozlesmesi (K6: <=200 karakter, tek satir), kaldirmak kanali sessizce deler. */}
            <label className="threshold-field"><span className="help-label-row">{t('userpush.maxMessageChars')}<HelpTip helpKey="help.set.site.monitor.userpush.max-message-chars" label={t('userpush.maxMessageChars')} /></span>
              <input type="number" className="input" min={80} max={320} value={val('max-message-chars', '200')}
                onChange={(e) => setVal('max-message-chars', e.target.value)} />
            </label>
            <label className="threshold-field"><span className="help-label-row">{t('userpush.reasonMaxChars')}<HelpTip helpKey="help.set.site.monitor.userpush.reason-max-chars" label={t('userpush.reasonMaxChars')} /></span>
              <input type="number" className="input" min={40} max={280} value={val('reason-max-chars', '160')}
                onChange={(e) => setVal('reason-max-chars', e.target.value)} />
            </label>
          </div>
        </div>

        {/* ── Unvan grupları — SEÇİLEBİLİR KARTLAR ── */}
        <div className="admin-section">
          <h4 className="ldap-subhdr">{t('userpush.groupsTitle')}<HelpTip helpKey="help.set.site.monitor.userpush.role-groups" label={t('userpush.groupsTitle')} /></h4>
          <p className="section-desc">{t('userpush.groupsDesc')}</p>
          <div className="up-group-grid">
            {Object.entries(groupsSafe).map(([key, g]) => {
              const Icon = GROUP_META[key]?.Icon || UserCog
              return (
                <div key={key} className={`up-group-card${g.enabled ? ' up-group-card--on' : ''}`}>
                  <div className="up-group-head">
                    <span className="up-group-icon"><Icon size={17} aria-hidden="true" /></span>
                    <span className="up-group-name">{t(`userpush.group.${key}`)}</span>
                    <PillSwitch on={!!g.enabled} label={t(`userpush.group.${key}`)}
                      onToggle={() => setRoleGroups({ ...groupsSafe, [key]: { ...g, enabled: !g.enabled } })} />
                  </div>
                  <p className="up-group-desc">{t(`userpush.groupDesc.${key}`)}</p>
                  <div className="up-group-badges">
                    <span className="up-badge up-badge--muted">{t('userpush.groupOrgRole')}</span>
                  </div>
                  {/* Org rolü çipleri: bu gruba hangi org rolleri girer. Bir rol birden çok grupta
                      olabilir; alıcı çözümü İLK açık eşleşmeyi alır (backend ile aynı sıra). */}
                  <div className="up-chip-grid up-group-roles" aria-label={t('userpush.groupRolesLabel')}>
                    {ORG_ROLES.map((code) => {
                      const on = (g.patterns || []).includes(code)
                      return (
                        <ToggleChip key={code} on={on} label={t(`usr.orgRoleVal.${code}`)}
                          onToggle={() => setRoleGroups({ ...groupsSafe, [key]: { ...g, source: 'orgRole',
                            patterns: on ? (g.patterns || []).filter((c) => c !== code) : [...(g.patterns || []), code] } })} />
                      )
                    })}
                  </div>
                  {/* Asgari seviye ARTIK DUZENLENEBILIR. Eskiden yalniz `minLevel === 'HIGH'`
                      oldugunda salt-okunur bir rozet ciziliyordu: ayar kaliciydi ve davranisi
                      belirliyordu (UserPushRecipientResolver:61 siddet kurali) ama arayuzden
                      DEGISTIRILEMIYORDU. Uretimde UYARI seviyesindeki bir sertifika alarmi bu
                      yuzden hic push alici bulamiyor, ekran da nedenini soylemiyordu.
                      Merdiven backend ile ayni: KRITIK > YUKSEK > digerleri (UYARI). */}
                  <div className="up-group-level">
                    <span className="up-group-level-label">{t('userpush.groupMinLevel')}
                      <HelpTip helpKey="help.userpush.groupMinLevel" label={t('userpush.groupMinLevel')} /></span>
                    <SegmentedControl
                      value={g.minLevel || 'WARNING'}
                      ariaLabel={`${t(`userpush.group.${key}`)} — ${t('userpush.groupMinLevel')}`}
                      onChange={(v) => setRoleGroups({ ...groupsSafe, [key]: { ...g, minLevel: v } })}
                      options={[
                        { value: 'WARNING',  label: t('userpush.levelWarning') },
                        { value: 'HIGH',     label: t('userpush.levelHigh') },
                        { value: 'CRITICAL', label: t('userpush.levelCritical') },
                      ]} />
                    <span className="hint">{t('userpush.groupMinLevelHint')}</span>
                  </div>
                </div>
              )
            })}
          </div>
        </div>

        {/* ── Tip + Takım kapsamı — TOGGLE CHIP grupları ── */}
        <div className="admin-section">
          <h4 className="ldap-subhdr">{t('userpush.scopesTitle')}</h4>
          <p className="section-desc">{t('userpush.scopesDesc')}</p>
          <h5 className="userpush-subsub">{t('userpush.typeMatrix')}<HelpTip helpKey="help.userpush.typeMatrix" label={t('userpush.typeMatrix')} /></h5>
          <div className="up-chip-grid" role="group" aria-label={t('userpush.typeMatrix')}>
            {TYPES.map(({ key, Icon }) => (
              <ToggleChip key={key} Icon={Icon} label={t('userpush.type.' + key)}
                on={scopeOn('TYPE', key)}
                onToggle={() => toggleScope('TYPE', key, scopeOn('TYPE', key))} />
            ))}
          </div>

          <div className="up-team-toolbar">
            <h5 className="userpush-subsub" style={{ margin: 0 }}>{t('userpush.teamMatrix')}
              <HelpTip helpKey="help.userpush.teamMatrix" label={t('userpush.teamMatrix')} /></h5>
            <input type="text" className="upt-search up-team-search" value={teamQuery}
              placeholder={t('userpush.searchTeam')}
              onChange={(e) => setTeamQuery(e.target.value)} />
            <button type="button" className="btn btn-sm" onClick={() => bulkTeams(true)}>{t('userpush.enableAll')}</button>
            <button type="button" className="btn btn-sm" onClick={() => bulkTeams(false)}>{t('userpush.disableAll')}</button>
          </div>
          <div className="up-chip-grid" role="group" aria-label={t('userpush.teamMatrix')}>
            {visibleTeams.map((tm) => (
              <ToggleChip key={tm.id} label={tm.name}
                on={scopeOn('TEAM', tm.id)}
                onToggle={() => toggleScope('TEAM', tm.id, scopeOn('TEAM', tm.id))} />
            ))}
            {visibleTeams.length === 0 && <span className="hint">{t('userpush.noTeamMatch')}</span>}
          </div>
        </div>

        {/* ── Sessiz saatler + tekrar kuralı ── */}
        <div className="admin-section">
          <h4 className="ldap-subhdr">{t('userpush.quietTitle')}</h4>
          <p className="section-desc">{t('userpush.quietDesc')}</p>
          <div className="up-quiet-row">
            <label className="threshold-field"><span className="help-label-row">{t('userpush.quietStart')}<HelpTip helpKey="help.set.site.monitor.userpush.quiet-start" label={t('userpush.quietStart')} /></span>
              <input type="time" className="input" value={val('quiet-start')}
                onChange={(e) => setVal('quiet-start', e.target.value)} />
            </label>
            <label className="threshold-field"><span className="help-label-row">{t('userpush.quietEnd')}<HelpTip helpKey="help.set.site.monitor.userpush.quiet-end" label={t('userpush.quietEnd')} /></span>
              <input type="time" className="input" value={val('quiet-end')}
                onChange={(e) => setVal('quiet-end', e.target.value)} />
            </label>
            <div className="threshold-field">
              <span className="help-label-row">{t('userpush.quietMinLevel')}
                <HelpTip helpKey="help.set.site.monitor.userpush.quiet-min-level" label={t('userpush.quietMinLevel')} /></span>
              <SegmentedControl value={val('quiet-min-level', 'CRITICAL')}
                ariaLabel={t('userpush.quietMinLevel')}
                onChange={(v) => setVal('quiet-min-level', v)}
                options={[
                  { value: 'CRITICAL', label: t('userpush.levelCritical') },
                  { value: 'HIGH',     label: t('userpush.levelHigh') },
                  { value: 'WARNING',  label: t('userpush.levelWarning') },
                ]} />
              {/* UYARI en alt basamak (levelValue: KRITIK 3 > YUKSEK 2 > digerleri 1), yani
                  secilirse sessiz saatte hicbir sey bastirilmaz — bu bilincli bir tercih
                  olabilir ama surpriz olmamali, ipucu bunu soyluyor. */}
              {val('quiet-min-level', 'CRITICAL') === 'WARNING' && (
                <span className="hint">{t('userpush.quietMinLevelWarnHint')}</span>
              )}
            </div>
          </div>
          <div className="up-master-row" style={{ marginTop: 12 }}>
            <PillSwitch on={val('realert-enabled', 'true') !== 'false'} label={t('userpush.realertEnabled')}
              onToggle={() => setVal('realert-enabled', val('realert-enabled', 'true') !== 'false' ? 'false' : 'true')} />
            <span className="help-label-row">{t('userpush.realertEnabled')}
              <HelpTip helpKey="help.set.site.monitor.userpush.realert-enabled" label={t('userpush.realertEnabled')} /></span>
          </div>
        </div>

        {/* ── Şablonlar — push bildirim MAKETİ önizlemeli ── */}
        <div className="admin-section">
          <h4 className="ldap-subhdr">{t('userpush.templatesTitle')}</h4>
          <p className="section-desc">{t('userpush.templatesDesc')}</p>
          <p className="hint userpush-placeholders">
            {t('userpush.placeholders')}: {(defaults.placeholders || []).map((p) => `{${p}}`).join(' ')}
          </p>
          <div className="up-template-grid">
            {TEMPLATE_KEYS.map((k) => {
              // HAM ayar okunur: val() kendi icinde `?? ''` uyguladigi icin hic kaydedilmemis
              // bir sablonda BOS DIZE dondurur — `??` zinciri o zaman defaults dalina HIC
              // gecmez ve temiz kurulumda kutular bos cizilirdi (2026-08-30 regresyonu).
              // Ham deger: kaydedilmemis -> undefined (varsayilan gelir), kullanici sildi -> ''
              // (bos KALIR). Iki durum ancak boyle ayrilabilir.
              const saved = settings[KEY(`template.${k}`)]
              const cur = saved ?? defaults.templates?.[k] ?? ''
              const meta = TEMPLATE_META[k]
              const MIcon = meta.Icon
              return (
                <div key={k} className="up-template-card">
                  <div className="up-template-head">
                    <span className={`up-template-icon up-template-icon--${meta.tone}`}>
                      <MIcon size={15} aria-hidden="true" />
                    </span>
                    <span className="up-template-name">{t(`userpush.template.${k}`)}
                      <HelpTip helpKey={`help.set.site.monitor.userpush.template.${k}`} label={t(`userpush.template.${k}`)} /></span>
                  </div>
                  <input type="text" className="input" value={cur} maxLength={220}
                    onChange={(e) => setVal(`template.${k}`, e.target.value)} />
                  <NotifPreview title={val('title', 'Site Monitor')} message={preview(cur)} tone={meta.tone} />
                </div>
              )
            })}
          </div>
        </div>
      </div>

      <div className="admin-section">
        <button type="button" className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? <Spinner size={14} inline decorative /> : <Save size={15} />} {t('settings.save')}
        </button>
      </div>

      {/* ── Test gönderimi ── */}
      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('userpush.testTitle')}</h4>
        <p className="section-desc">{t('userpush.testDesc')}</p>
        <TagInput label={t('userpush.testSicils')} value={testSicils} onChange={setTestSicils}
          placeholder="N00001" />
        <div className="userpush-test-row">
          <SearchableSelect value={testTemplate} onChange={setTestTemplate}
            options={TEMPLATE_KEYS.map((k) => ({ value: k, label: t(`userpush.template.${k}`) }))} />
          <button type="button" className="btn" onClick={sendTest} disabled={testing || !enabled}
            title={!enabled ? t('userpush.disabledWarn') : undefined}>
            {testing ? <Spinner size={14} inline decorative /> : <Send size={14} />} {t('userpush.testSend')}
          </button>
        </div>
        {testResult && (
          <div className="userpush-test-result">
            <div>{t('userpush.testQueuedN', testResult.queued)}</div>
            <NotifPreview title={val('title', 'Site Monitor')} message={testResult.message} tone="info" />
          </div>
        )}
      </div>

      {/* ── Kim alır? (alıcı çözümü açıklaması) ── */}
      <div className="admin-section up-explain">
        <h4 className="ldap-subhdr">{t('userpush.explainTitle')}</h4>
        <p className="section-desc">{t('userpush.explainDesc')}</p>
        <div className="userpush-log-filters">
          <SearchableSelect value={exTeam} onChange={(v) => setExTeam(v)} placeholder={t('userpush.explainPickTeam')} searchThreshold={4}
            options={[{ value: '', label: t('userpush.explainPickTeam') }, ...(teams || []).map(tm => ({ value: String(tm.id), label: tm.name }))]} />
          <SegmentedControl value={exLevel} onChange={setExLevel} ariaLabel={t('userpush.explainLevel')}
            options={['WARNING', 'HIGH', 'CRITICAL'].map(l => ({ value: l, label: l }))} />
        </div>
        {exTeam && exLoading && <LoadingBlock label={t('modal.loading')} />}
        {exTeam && !exLoading && exRows && exRows.length === 0 && (
          <div className="sqlpg-td-empty">{t('userpush.explainEmpty')}</div>
        )}
        {exTeam && !exLoading && exRows && exRows.length > 0 && (
          <div className="admin-table-wrap">
            <table className="admin-table up-explain-table">
              <thead>
                <tr>
                  <th>{t('userpush.explainMember')}</th>
                  <th>{t('userpush.explainTitleCol')}</th>
                  <th>{t('userpush.explainGroup')}</th>
                  <th>{t('userpush.explainDecision')}</th>
                </tr>
              </thead>
              <tbody>
                {exRows.map((m, i) => (
                  <tr key={m.username + i} className={m.decision === 'RECIPIENT' ? 'is-recipient' : 'is-skipped'}>
                    <td><strong>{m.display_name || m.username}</strong> <span className="sys-muted sys-mono sys-small">{m.username}</span></td>
                    <td className="sys-small">{m.title || '—'}{m.org_role ? <span className="sys-muted"> · {m.org_role}</span> : null}</td>
                    <td className="sys-small">{m.group ? <>{m.group}{m.min_level ? <span className="sys-muted"> · ≥ {m.min_level}</span> : null}{m.group_enabled === false ? <span className="sys-muted"> · {t('userpush.explainGroupOff')}</span> : null}</> : '—'}</td>
                    <td><span className={`up-decision up-decision--${m.decision}`}>{t('userpush.decision.' + m.decision)}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* ── Teslimat günlüğü ── */}
      <div className="admin-section" ref={logRef}>
        <h4 className="ldap-subhdr">{t('userpush.logTitle')}</h4>
        <p className="section-desc">{t('userpush.logDesc')}</p>
        <div className="userpush-log-filters">
          {fWindow && (
            <button type="button" className="btn btn-sm up-window-chip" onClick={() => { setFWindow(''); setPage(0) }}
              title={t('userpush.windowClear')}>
              {t('userpush.windowActive', fWindow === '24h' ? t('userpush.stat24h') : t('userpush.stat7d'))} ✕
            </button>
          )}
          <input type="text" className="upt-search" placeholder={t('userpush.filterSicil')} value={fUser}
            onChange={(e) => { setFUser(e.target.value); setPage(0) }} />
          <SearchableSelect value={fStatus} onChange={(v) => { setFStatus(v); setPage(0) }}
            options={[{ value: '', label: t('userpush.allStatuses') },
              ...STATUS_OPTIONS.map((s) => ({ value: s, label: s }))]} searchThreshold={8} />
          <SearchableSelect value={fTrigger} onChange={(v) => { setFTrigger(v); setPage(0) }}
            options={[{ value: '', label: t('userpush.allTriggers') },
              ...TRIGGERS.map((s) => ({ value: s, label: t('userpush.trigger.' + s) }))]} searchThreshold={8} />
          <input type="text" className="upt-search" placeholder="notificationId" value={fNotifId}
            onChange={(e) => { setFNotifId(e.target.value); setPage(0) }} />
          <button type="button" className="btn btn-sm" onClick={loadDeliveries} aria-label="Yenile">
            <RefreshCw size={14} />
          </button>
          <a className="btn btn-sm" href={api.admin.userPush.exportUrl({
            ...(fUser ? { username: fUser } : {}), ...(fStatus ? { status: fStatus } : {}),
            ...(fTrigger ? { trigger: fTrigger } : {}), ...(fNotifId ? { notificationId: fNotifId } : {}),
            ...(windowFrom(fWindow) ? { from: windowFrom(fWindow) } : {}),
          })} download>CSV</a>
        </div>

        {rows === null ? <LoadingBlock label={t('modal.loading')} />
          : rows.length === 0 ? (
            <StatusBlock tone="neutral" icon={BellRing} title={t('userpush.logEmptyTitle')}
              description={t('userpush.logEmpty')} />
          ) : (
              <div className="userpush-log">
                {rows.map((r) => {
                  const isOpen = openRow === r.id
                  return (
                    <div key={r.id} className={`userpush-log-row${isOpen ? ' is-open' : ''}`}>
                      <button type="button" className="userpush-log-head" aria-expanded={isOpen}
                        onClick={() => setOpenRow(isOpen ? null : r.id)}>
                        <span className={`userpush-badge userpush-badge--${statusTone(r.status)}`}>{r.status}</span>
                        <span className="userpush-log-who">
                          {r.username === '-' ? <em>{t('userpush.systemRow')}</em>
                            : <UserBadge username={r.username} displayName={r.display_name} size="sm" inline nameOnly />}
                          {/* Kişinin takım(lar)ı — satır bir <button>, bu yüzden TeamBadge span modunda. */}
                          {(userTeams[(r.username || '').toUpperCase()] || []).map((tn) => (
                            <TeamBadge key={tn} teamName={tn} size={11} as="span" className="userpush-log-team" />
                          ))}
                        </span>
                        <span className="userpush-log-mon">{r.monitor_name || '—'}</span>
                        <span className="userpush-log-trigger">{t('userpush.trigger.' + r.trigger)}</span>
                        <span className="userpush-log-when sys-mono">{formatDateSec(r.created_at)}</span>
                      </button>
                      {isOpen && (
                        <div className="userpush-log-detail">
                          {r.message && <NotifPreview title={r.title} message={r.message}
                            tone={statusTone(r.status) === 'danger' ? 'danger' : 'info'} />}
                          <dl className="userpush-log-meta">
                            {r.http_status != null && <><dt>HTTP</dt><dd>{r.http_status}</dd></>}
                            {r.attempts != null && <><dt>{t('userpush.attempts')}</dt><dd>{r.attempts}</dd></>}
                            {r.notification_id && (
                              <><dt>notificationId</dt>
                                <dd>
                                  <button type="button" className="chg-ip sys-mono"
                                    onClick={() => copyText(r.notification_id)}>
                                    {r.notification_id}<Copy size={10} aria-hidden="true" />
                                  </button>
                                </dd></>
                            )}
                            {r.batch_id && <><dt>batch</dt><dd className="sys-mono">{r.batch_id}</dd></>}
                            {r.error && <><dt>{t('userpush.error')}</dt><dd className="userpush-log-err">{r.error}</dd></>}
                          </dl>
                        </div>
                      )}
                    </div>
                  )
                })}
              </div>
            )}
        {/* "Sayfa basina" secicisi ONCEDEN OLU kontroldu: onPageSizeChange verilmediginden
            50/100/200'e tiklamak hicbir sey yapmiyor, secici yine de goruluyordu. */}
        <PaginationBar page={page + 1} totalPages={Math.max(1, Math.ceil(total / pageSize))}
          totalItems={total} pageSize={pageSize}
          rangeStart={total === 0 ? 0 : page * pageSize + 1}
          rangeEnd={Math.min(total, (page + 1) * pageSize)}
          onPageChange={(p) => setPage(p - 1)}
          onPageSizeChange={(n) => { setPageSize(n); setPage(0) }} />
      </div>
    </div>
  )
}
