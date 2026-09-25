import { useCallback, useEffect, useMemo, useState, useRef } from 'react'
import {
  Save, Send, BellRing, Plus, Trash2, Copy, RefreshCw, Search as SearchIcon,
  Crown, UserCog, Briefcase, Globe, Network, Target, Radio, CalendarDays,
  ScanSearch, FlaskConical, Gauge, ShieldCheck, WifiOff, Timer, CalendarClock,
  ArrowLeftRight, CheckCircle2, OctagonPause, Check, Landmark, Building2, ChevronDown,
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
import ModalShell from '../ui/ModalShell.jsx'
import { formatDateSec } from '../../api/client'
import { copyText } from '../../utils/copyText.js'
import { Button } from '@/components/shadcn/button'

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
const TRIGGERS = ['OPEN', 'ESCALATION', 'RE_ALERT', 'RESOLVE', 'RESEND', 'WEAK_ALGO', 'WEEKLY_REPORT', 'TEST']   // WEAK_ALGO: rapor 'takıma bildir' (2026-09-12); WEEKLY_REPORT: onay → takıma (2026-09-13)

/** İzleme tipleri — ikonlar Nav/ChangeKindCards ile AYNI: kullanıcı yeni görsel dil öğrenmez. */
const TYPES = [
  { key: 'cert', Icon: ShieldCheck }, { key: 'http', Icon: Globe }, { key: 'port', Icon: Network },
  { key: 'dns', Icon: SearchIcon }, { key: 'keyword', Icon: Target }, { key: 'ping', Icon: Radio },
  { key: 'domain', Icon: CalendarDays }, { key: 'page', Icon: ScanSearch },
  { key: 'scripted', Icon: FlaskConical }, { key: 'pagespeed', Icon: Gauge },
]

/** Unvan grubu kartları: ikon + kalıcı görsel kimlik. */

/** KPI pencereleri (2026-09-12): anahtar = backend STAT_WINDOWS ile aynı; ms + etiket anahtarı. */
const WINDOWS = [
  { key: '24h', ms: 24 * 3600e3, label: 'userpush.stat24h' },
  { key: '7d',  ms: 7 * 86400e3, label: 'userpush.stat7d' },
  { key: '15d', ms: 15 * 86400e3, label: 'userpush.stat15d' },
  { key: '30d', ms: 30 * 86400e3, label: 'userpush.stat30d' },
  { key: '60d', ms: 60 * 86400e3, label: 'userpush.stat60d' },
]
const winLabelKey = (w) => (WINDOWS.find((x) => x.key === w) || WINDOWS[0]).label

/** Açılır/kapanır bölüm anahtarları — sıra sayfadaki sıra; localStorage'da hatırlanır. */
const SECTIONS = ['conn', 'groups', 'scopes', 'quiet', 'weekly', 'templates', 'test', 'explain', 'log']
const SECTIONS_KEY = 'sm.userpush.sections'
function readSections() {
  try {
    const raw = localStorage.getItem(SECTIONS_KEY)
    if (!raw) return null
    const o = JSON.parse(raw)
    return o && typeof o === 'object' ? o : null
  } catch { return null }
}

/**
 * Bölüm başlığı = açılır/kapanır düğme (2026-09-11, kullanıcı: "başlıkları açılır kapanır menüye
 * dönüştür, istediğim bölümü açıp değiştireyim"). Başlık düğmesi <button>; yardım ipucu (HelpTip)
 * kendi düğmesi olduğu için başlığın DIŞINDA, aynı satırda (iç içe button olmaz — TeamBadge dersi).
 * Kapalıyken bölüm içeriği CSS ile gizlenir (.cs-section:not(.is-open) > :not(.cs-head)).
 */
function SectionHead({ id, title, open, onToggle, children }) {
  return (
    <div className="cs-head">
      <button type="button" className="cs-toggle" aria-expanded={open} aria-controls={`cs-${id}`} onClick={onToggle}>
        <ChevronDown size={16} className={`cs-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
        <span className="cs-title">{title}</span>
      </button>
      {children}
    </div>
  )
}

/**
 * Teslimat günlüğü satırı — başlık (durum · kişi · takım · izleme · tetik · zaman) + açılır ayrıntı.
 * Günlük listesi ve KPI pencere modalı AYNI satırı çizer; iki kopya zamanla ayrışırdı.
 */
function DeliveryRow({ r, isOpen, onToggle, userTeams, t, statusTone }) {
  return (
    <div className={`userpush-log-row${isOpen ? ' is-open' : ''}`}>
      <button type="button" className="userpush-log-head" aria-expanded={isOpen} onClick={onToggle}>
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
}

/**
 * KPI pencere modalı (2026-09-11, kullanıcı): "Son 24 saat / Son 7 gün" kartına tıklayınca ayrıntı
 * bir POP-UP'ta açılır — özet (durum başına sayı), durum süzgeci ve o penceredeki teslimatların listesi.
 * Eski davranış (günlüğü süzüp kaydırmak) "Günlükte aç" düğmesinde bilinçli bir eylem olarak kaldı.
 */
function WindowModal({ win, status, counts, teams: teamRows, windowFrom, statusTone, userTeamsHint, onStatus, onOpenInLog, onClose, t }) {
  const [rows, setRows] = useState(null)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [userTeams, setUserTeams] = useState(userTeamsHint || {})
  const [openRow, setOpenRow] = useState(null)
  // Takım süzgeci (2026-09-12, kullanıcı: "kutular takım bazlı adet versin"): çipler alarmın TAKIMINA
  // göre (teslimat satırındaki team_id) — kişinin üyelikleri değil. null = tüm takımlar.
  const [team, setTeam] = useState(null)
  const SIZE = 25
  const from = windowFrom(win)

  useEffect(() => { setPage(0) }, [win, status, team])
  useEffect(() => {
    let alive = true
    setRows(null)
    const params = { page, size: SIZE, from }
    if (status) params.status = status
    if (team != null) params.teamId = team
    Promise.resolve(api.admin.userPush.getDeliveries(params))
      .then((res) => {
        if (!alive) return
        if (res?.success) {
          setRows(res.data?.deliveries || [])
          setTotal(res.data?.total || 0)
          setUserTeams((prev) => ({ ...prev, ...(res.data?.user_teams || {}) }))
        } else setRows([])
      })
      .catch(() => { if (alive) setRows([]) })
    return () => { alive = false }
  }, [win, status, team, page, from])

  const title = t(winLabelKey(win))
  const sum = Object.entries(counts || {}).reduce((s, [, v]) => s + (Number(v) || 0), 0)
  // Durum çipleri: Tümü + SENT + FAILED sabit; sayısı olan diğer durumlar (SKIPPED_*, PENDING…) dinamik.
  const others = Object.keys(counts || {}).filter((k) => k !== 'SENT' && k !== 'FAILED' && Number(counts[k]) > 0)
  const chips = [['', t('userpush.winAll'), sum], ['SENT', 'SENT', counts?.SENT || 0], ['FAILED', 'FAILED', counts?.FAILED || 0],
    ...others.map((k) => [k, k, counts[k]])]

  return (
    <ModalShell open onClose={onClose} title={title} icon={BellRing} size="xl" scrollBody
      closeLabel={t('userpush.winClose')}
      footer={(
        <>
          <Button asChild variant="outline" size="sm"><a href={api.admin.userPush.exportUrl({ from, ...(status ? { status } : {}) })} download>CSV</a></Button>
          <Button type="button" variant="secondary" size="sm" onClick={onOpenInLog}>{t('userpush.winOpenInLog')}</Button>
          <Button type="button" size="sm" onClick={onClose}>{t('userpush.winClose')}</Button>
        </>
      )}>
      <p className="section-desc">{t('userpush.winSince', formatDateSec(from + 'Z'))}</p>
      <div className="up-chip-grid" role="group" aria-label={t('userpush.winStatusFilter')}>
        {chips.map(([value, label, n]) => (
          <button key={value || 'all'} type="button" className={`up-chip${status === value ? ' up-chip--on' : ''}`}
            aria-pressed={status === value} onClick={() => onStatus(value)}>
            <span>{label}</span><b className="up-chip-count">{n}</b>
          </button>
        ))}
      </div>
      {Array.isArray(teamRows) && teamRows.length > 0 && (
        <div className="up-chip-grid up-team-chips" role="group" aria-label={t('userpush.winTeamFilter')}>
          <button type="button" className={`up-chip${team == null ? ' up-chip--on' : ''}`} aria-pressed={team == null}
            onClick={() => setTeam(null)}><span>{t('userpush.winAllTeams')}</span></button>
          {teamRows.filter((tr) => tr.team_id != null).map((tr) => (
            <button key={tr.team_id} type="button" className={`up-chip${team === tr.team_id ? ' up-chip--on' : ''}`}
              aria-pressed={team === tr.team_id} onClick={() => setTeam(team === tr.team_id ? null : tr.team_id)}
              title={`SENT ${tr.SENT} · FAILED ${tr.FAILED}`}>
              <span>{tr.team_name}</span><b className="up-chip-count">{tr.total}</b>
              {tr.FAILED > 0 && <b className="up-chip-count up-chip-count--fail">{tr.FAILED}</b>}
            </button>
          ))}
        </div>
      )}
      {rows === null ? <LoadingBlock label={t('modal.loading')} />
        : rows.length === 0 ? (
          <StatusBlock tone="neutral" icon={BellRing} title={t('userpush.winEmpty')} description={t('userpush.logEmpty')} />
        ) : (
          <div className="userpush-log">
            {rows.map((r) => (
              <DeliveryRow key={r.id} r={r} isOpen={openRow === r.id} onToggle={() => setOpenRow(openRow === r.id ? null : r.id)}
                userTeams={userTeams} t={t} statusTone={statusTone} />
            ))}
          </div>
        )}
      <PaginationBar page={page + 1} totalPages={Math.max(1, Math.ceil(total / SIZE))}
        totalItems={total} pageSize={SIZE}
        rangeStart={total === 0 ? 0 : page * SIZE + 1}
        rangeEnd={Math.min(total, (page + 1) * SIZE)}
        onPageChange={(p) => setPage(p - 1)} />
    </ModalShell>
  )
}

/**
 * Sabit kademeler (ürün kararı 2026-09-11): kartlar sistemdeki org rolü listesinin birebir karşılığı
 * (Kullanıcı ekranındaki "Organizasyonel Rol" seçenekleri) — her kart TEK org rolü: Uzman = TECH,
 * PO = PO, Yönetici = MANAGER, Bölüm Başkanı = BOLUM_BASKANI, C-Level = CLEVEL. Yönetici yalnız aç/kapa
 * + asgari seviye seçer; rol rozet olarak yazılır. Backend (UserPushRecipientResolver.TIER_ROLES) aynı
 * eşlemeyi zorunlu kılar. "Rol yok (üye)" hiçbir karta girmez.
 */
const TIERS = {
  uzman:         { role: 'TECH',          minLevel: 'WARNING',  Icon: UserCog },
  po:            { role: 'PO',            minLevel: 'WARNING',  Icon: Briefcase },
  yonetici:      { role: 'MANAGER',       minLevel: 'HIGH',     Icon: Crown },
  bolum_baskani: { role: 'BOLUM_BASKANI', minLevel: 'CRITICAL', Icon: Landmark },
  clevel:        { role: 'CLEVEL',        minLevel: 'CRITICAL', Icon: Building2 },
}
const GROUP_META = TIERS

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
  // Kaydedilmiş hâlin anlık görüntüsü — "kaydedilmemiş değişiklik var mı" bundan türer (2026-09-12,
  // kullanıcı: Kaydet düğmesi sayfanın ortasında kayboluyordu → yalnız değişiklik varken görünen,
  // alta yapışık kayıt şeridi). Kapsam (tür/takım) ve ana anahtar ANINDA kaydedildiği için şeride girmez.
  const [savedSnap, setSavedSnap] = useState(null)
  const snapshotOf = (s, h, g) => JSON.stringify({ s, h, g })
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
    const ms = (WINDOWS.find((x) => x.key === w) || {}).ms || 0
    return ms ? new Date(Date.now() - ms).toISOString().slice(0, 19) : null   // createdAt biçimi yyyy-MM-ddTHH:mm:ss (UTC, Z'siz)
  }
  // KPI kartı → pencere MODALI (2026-09-11, kullanıcı: "tıklayınca pop-up'ta ayrıntı sunmalı").
  // Günlüğü süzüp kaydırmak artık modaldaki "Günlükte aç" eylemi (openInLog).
  const [winModal, setWinModal] = useState(null)   // null | { win: '24h'|'7d', status: '' | 'SENT' | 'FAILED' | … }
  // Bölüm açık/kapalı durumu — varsayılan hepsi KAPALI (kullanıcı kararı 2026-09-11: sayfa bir menü gibi
  // açılsın, istenen bölüm açılıp düzenlensin); seçim tarayıcıda hatırlanır; "Tümünü daralt / genişlet"
  // başlık satırında. KPI kartları, ana anahtar ve Kaydet düğmesi bölüm dışında, hep görünür.
  const [sections, setSections] = useState(() => readSections() || {})
  const isOpen = (id) => sections[id] === true
  const setAllSections = (open) => {
    const next = Object.fromEntries(SECTIONS.map((k) => [k, open]))
    setSections(next)
    try { localStorage.setItem(SECTIONS_KEY, JSON.stringify(next)) } catch { /* yoksay */ }
  }
  const toggleSec = (id) => {
    setSections((prev) => {
      const next = { ...prev, [id]: !isOpen(id) }
      try { localStorage.setItem(SECTIONS_KEY, JSON.stringify(next)) } catch { /* yoksay */ }
      return next
    })
  }
  const sec = (id, extra = '') => `admin-section cs-section${isOpen(id) ? ' is-open' : ''}${extra ? ' ' + extra : ''}`
  const allOpen = SECTIONS.every(isOpen)
  const pickWindow = (w, status) => setWinModal({ win: w, status: status || '' })
  const openInLog = () => {
    if (!winModal) return
    setFWindow(winModal.win)
    setFStatus(winModal.status || '')
    setPage(0)
    setWinModal(null)
    if (!isOpen('log')) toggleSec('log')
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
        let g = {}
        try { g = JSON.parse(d.settings?.[KEY('role-groups')] || '') || {} } catch { g = defaultGroups() }
        setRoleGroups(g)
        const h = Array.isArray(d.settings?.[KEY('headers')]) ? d.settings[KEY('headers')] : []
        setSavedSnap(snapshotOf(d.settings || {}, h, Object.keys(g).length ? normalizeGroups(g) : defaultGroups()))
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
    const out = {}
    for (const [key, tier] of Object.entries(TIERS)) {
      out[key] = { enabled: false, source: 'orgRole', patterns: [tier.role], minLevel: tier.minLevel }
    }
    return out
  }
  // Kayıt ekrana gelince kademelere SABİTLENİR: bilinen anahtarın rolü TIERS'tan (eski unvan desenleri
  // ya da ara sürümün çoklu-rol kümesi yok sayılır), eksik kademe kapalı eklenir, bilinmeyen anahtar
  // olduğu gibi kalır. Backend okurken aynı çeviriyi yapar; kaydet'e basınca yeni biçim kalıcılaşır.
  function normalizeGroups(g) {
    const out = defaultGroups()
    for (const [key, v] of Object.entries(g || {})) {
      out[key] = TIERS[key]
        ? { ...out[key], ...v, source: 'orgRole', patterns: [TIERS[key].role] }
        : { ...v, source: 'orgRole' }
    }
    return out
  }

  const groupsSafe = Object.keys(roleGroups).length ? normalizeGroups(roleGroups) : defaultGroups()
  const dirty = savedSnap !== null && snapshotOf(settings, headers, groupsSafe) !== savedSnap
  function discard() {
    if (!savedSnap) return
    const snap = JSON.parse(savedSnap)
    setSettings(snap.s); setHeaders(snap.h); setRoleGroups(snap.g)
  }

  async function save() {
    setSaving(true)
    try {
      const body = { ...settings }
      body[KEY('headers')] = headers
      body[KEY('role-groups')] = JSON.stringify(groupsSafe)
      const res = await api.admin.userPush.saveSettings(body)
      if (res?.success) {
        toast.success(t('userpush.saved'))
        const s2 = res.data?.settings || {}
        const h2 = Array.isArray(s2[KEY('headers')]) ? s2[KEY('headers')] : []
        setSettings(s2)
        setHeaders(h2)
        setSavedSnap(snapshotOf(s2, h2, groupsSafe))
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
  // Beş pencere: yeni sözleşme stats.windows[key] = { counts, teams }; eski last24h/last7d yedek.
  const winStats = (w) => stats?.windows?.[w] || (w === '24h' ? { counts: stats?.last24h || {} } : w === '7d' ? { counts: stats?.last7d || {} } : { counts: {} })

  return (
    <div className="ldap-settings userpush-settings">
      {/* ── Başlık + KPI şeridi ── */}
      <div className="admin-section">
        <div className="cs-page-head">
          <h3><BellRing size={18} style={{ verticalAlign: '-3px' }} /> {t('userpush.title')}</h3>
          <Button type="button" variant="outline" size="sm" className="cs-all" onClick={() => setAllSections(!allOpen)}>
            {allOpen ? t('userpush.collapseAll') : t('userpush.expandAll')}
          </Button>
        </div>
        <p className="section-desc">{t('userpush.desc')}</p>
        {stats && (
          <div className="userpush-stats-row up-kpis">
            {WINDOWS.map(({ key, label }) => {
              const ws = winStats(key)
              const c = ws.counts || {}
              const top = (ws.teams || []).filter((tr) => tr.team_id != null).slice(0, 3)
              return (
                <div key={key} className={`up-kpi up-kpi--btn${fWindow === key ? ' is-active' : ''}`} role="button" tabIndex={0}
                  title={t('userpush.kpiHint')} aria-pressed={fWindow === key}
                  onClick={() => pickWindow(key, '')}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pickWindow(key, '') } }}>
                  <span className="up-kpi-label">{t(label)}</span>
                  <span className="up-kpi-nums">
                    <b className="up-kpi-ok up-kpi-num" role="button" tabIndex={0} onClick={(e) => { e.stopPropagation(); pickWindow(key, 'SENT') }}>{c.SENT || 0}</b><small>SENT</small>
                    <b className="up-kpi-fail up-kpi-num" role="button" tabIndex={0} onClick={(e) => { e.stopPropagation(); pickWindow(key, 'FAILED') }}>{c.FAILED || 0}</b><small>FAILED</small>
                  </span>
                  {/* Takım kırılımı: en yoğun 3 takım (toplam · başarısız). Tamamı modalda. */}
                  {top.length > 0 && (
                    <span className="up-kpi-teams">
                      {top.map((tr) => (
                        <span key={tr.team_id} className="up-kpi-team" title={`SENT ${tr.SENT} · FAILED ${tr.FAILED}`}>
                          <span className="up-kpi-team-name">{tr.team_name}</span>
                          <b>{tr.total}</b>{tr.FAILED > 0 && <b className="up-kpi-fail">{tr.FAILED}</b>}
                        </span>
                      ))}
                      {(ws.teams || []).filter((tr) => tr.team_id != null).length > 3 && <span className="up-kpi-team-more">…</span>}
                    </span>
                  )}
                </div>
              )
            })}
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
        <div className={sec('conn')}>
          <SectionHead id="conn" title={t('userpush.connTitle')} open={isOpen('conn')} onToggle={() => toggleSec('conn')}></SectionHead>
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
              <Button type="button" variant="outline" size="sm" aria-label={t('userpush.headerDelete', h.name || String(i + 1))}
                onClick={() => setHeaders(headers.filter((_, j) => j !== i))}><Trash2 size={14} /></Button>
            </div>
          ))}
          <Button type="button" variant="outline" size="sm" onClick={() => setHeaders([...headers, { name: '', value: '', secret: true }])}>
            <Plus size={14} /> {t('userpush.addHeader')}
          </Button>

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
        <div className={sec('groups')}>
          <SectionHead id="groups" title={t('userpush.groupsTitle')} open={isOpen('groups')} onToggle={() => toggleSec('groups')}><HelpTip helpKey="help.set.site.monitor.userpush.role-groups" label={t('userpush.groupsTitle')} /></SectionHead>
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
                  {/* Kademe = tek org rolü; rozet olarak yazılır, seçilemez (TIERS). */}
                  <div className="up-group-badges" aria-label={t('userpush.groupRolesLabel')}>
                    <span className="up-badge up-badge--muted">{t('userpush.groupOrgRole')}</span>
                    {(g.patterns || []).map((code) => (
                      <span key={code} className="up-badge up-badge--muted">{t(`usr.orgRoleVal.${code}`)}</span>
                    ))}
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
        <div className={sec('scopes')}>
          <SectionHead id="scopes" title={t('userpush.scopesTitle')} open={isOpen('scopes')} onToggle={() => toggleSec('scopes')}></SectionHead>
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
            <Button type="button" variant="outline" size="sm" onClick={() => bulkTeams(true)}>{t('userpush.enableAll')}</Button>
            <Button type="button" variant="outline" size="sm" onClick={() => bulkTeams(false)}>{t('userpush.disableAll')}</Button>
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
        <div className={sec('quiet')}>
          <SectionHead id="quiet" title={t('userpush.quietTitle')} open={isOpen('quiet')} onToggle={() => toggleSec('quiet')}></SectionHead>
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

        {/* ── Haftalık rapor onayı (2026-09-13): takıma + müdüre push; e-posta ile aynı anda ── */}
        <div className={sec('weekly')}>
          <SectionHead id="weekly" title={t('userpush.weeklyTitle')} open={isOpen('weekly')} onToggle={() => toggleSec('weekly')}></SectionHead>
          <p className="section-desc">{t('userpush.weeklyDesc')}</p>
          <div className="up-master-row">
            <PillSwitch on={val('weekly.team-enabled', 'true') !== 'false'} label={t('userpush.weeklyTeam')}
              onToggle={() => setVal('weekly.team-enabled', val('weekly.team-enabled', 'true') !== 'false' ? 'false' : 'true')} />
            <span className="help-label-row">{t('userpush.weeklyTeam')}
              <HelpTip helpKey="help.set.site.monitor.userpush.weekly.team-enabled" label={t('userpush.weeklyTeam')} /></span>
          </div>
          <div className="up-master-row" style={{ marginTop: 8 }}>
            <PillSwitch on={val('weekly.manager-enabled', 'true') !== 'false'} label={t('userpush.weeklyManager')}
              onToggle={() => setVal('weekly.manager-enabled', val('weekly.manager-enabled', 'true') !== 'false' ? 'false' : 'true')} />
            <span className="help-label-row">{t('userpush.weeklyManager')}
              <HelpTip helpKey="help.set.site.monitor.userpush.weekly.manager-enabled" label={t('userpush.weeklyManager')} /></span>
          </div>
          <p className="hint">{t('userpush.weeklyHint')}</p>
        </div>

        {/* ── Şablonlar — push bildirim MAKETİ önizlemeli ── */}
        <div className={sec('templates')}>
          <SectionHead id="templates" title={t('userpush.templatesTitle')} open={isOpen('templates')} onToggle={() => toggleSec('templates')}></SectionHead>
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

      {/* ── Test gönderimi ── */}
      <div className={sec('test')}>
        <SectionHead id="test" title={t('userpush.testTitle')} open={isOpen('test')} onToggle={() => toggleSec('test')}></SectionHead>
        <p className="section-desc">{t('userpush.testDesc')}</p>
        <TagInput label={t('userpush.testSicils')} value={testSicils} onChange={setTestSicils}
          placeholder="N00001" />
        <div className="userpush-test-row">
          <SearchableSelect value={testTemplate} onChange={setTestTemplate}
            options={TEMPLATE_KEYS.map((k) => ({ value: k, label: t(`userpush.template.${k}`) }))}
            ariaLabel={t('userpush.testTemplateAria')} />
          <Button type="button" variant="outline" onClick={sendTest} disabled={testing || !enabled}
            title={!enabled ? t('userpush.disabledWarn') : undefined}>
            {testing ? <Spinner size={14} inline decorative /> : <Send size={14} />} {t('userpush.testSend')}
          </Button>
        </div>
        {testResult && (
          <div className="userpush-test-result">
            <div>{t('userpush.testQueuedN', testResult.queued)}</div>
            <NotifPreview title={val('title', 'Site Monitor')} message={testResult.message} tone="info" />
          </div>
        )}
      </div>

      {/* ── Kim alır? (alıcı çözümü açıklaması) ── */}
      <div className={sec('explain', 'up-explain')}>
        <SectionHead id="explain" title={t('userpush.explainTitle')} open={isOpen('explain')} onToggle={() => toggleSec('explain')}></SectionHead>
        <p className="section-desc">{t('userpush.explainDesc')}</p>
        <div className="userpush-log-filters">
          <SearchableSelect value={exTeam} onChange={(v) => setExTeam(v)} placeholder={t('userpush.explainPickTeam')} searchThreshold={4} ariaLabel={t('userpush.explainPickTeam')}
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
      <div className={sec('log')} ref={logRef}>
        <SectionHead id="log" title={t('userpush.logTitle')} open={isOpen('log')} onToggle={() => toggleSec('log')} />
        <p className="section-desc">{t('userpush.logDesc')}</p>
        <div className="userpush-log-filters">
          {fWindow && (
            <Button type="button" variant="outline" size="sm" className="up-window-chip" onClick={() => { setFWindow(''); setPage(0) }}
              title={t('userpush.windowClear')}>
              {t('userpush.windowActive', t(winLabelKey(fWindow)))} ✕
            </Button>
          )}
          <input type="text" className="upt-search" placeholder={t('userpush.filterSicil')} value={fUser}
            onChange={(e) => { setFUser(e.target.value); setPage(0) }} />
          <SearchableSelect value={fStatus} onChange={(v) => { setFStatus(v); setPage(0) }}
            options={[{ value: '', label: t('userpush.allStatuses') },
              ...STATUS_OPTIONS.map((s) => ({ value: s, label: s }))]} searchThreshold={8} ariaLabel={t('flt.status')} />
          <SearchableSelect value={fTrigger} onChange={(v) => { setFTrigger(v); setPage(0) }}
            options={[{ value: '', label: t('userpush.allTriggers') },
              ...TRIGGERS.map((s) => ({ value: s, label: t('userpush.trigger.' + s) }))]} searchThreshold={8} ariaLabel={t('flt.trigger')} />
          <input type="text" className="upt-search" placeholder="notificationId" value={fNotifId}
            onChange={(e) => { setFNotifId(e.target.value); setPage(0) }} />
          <Button type="button" variant="outline" size="sm" onClick={loadDeliveries} aria-label={t('app.refresh')}>
            <RefreshCw size={14} />
          </Button>
          <Button asChild variant="outline" size="sm"><a href={api.admin.userPush.exportUrl({
            ...(fUser ? { username: fUser } : {}), ...(fStatus ? { status: fStatus } : {}),
            ...(fTrigger ? { trigger: fTrigger } : {}), ...(fNotifId ? { notificationId: fNotifId } : {}),
            ...(windowFrom(fWindow) ? { from: windowFrom(fWindow) } : {}),
          })} download>CSV</a></Button>
        </div>

        {rows === null ? <LoadingBlock label={t('modal.loading')} />
          : rows.length === 0 ? (
            <StatusBlock tone="neutral" icon={BellRing} title={t('userpush.logEmptyTitle')}
              description={t('userpush.logEmpty')} />
          ) : (
              <div className="userpush-log">
                {rows.map((r) => (
                  <DeliveryRow key={r.id} r={r} isOpen={openRow === r.id} onToggle={() => setOpenRow(openRow === r.id ? null : r.id)}
                    userTeams={userTeams} t={t} statusTone={statusTone} />
                ))}
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

      {/* Yapışkan kayıt şeridi — HER ZAMAN görünür (2026-09-12, kullanıcı: "kaydet butonunu göremiyorum" —
          yalnız-değişince-beliren şerit keşfedilemiyordu). Temiz durumda "kaydedildi" + pasif Kaydet;
          değişiklik varken vurgulu şerit + Geri al + etkin Kaydet. Sayfa nereye kaydırılırsa kaydırılsın altta. */}
      {!loading && (
        <div className={`up-savebar${dirty ? ' up-savebar--dirty' : ''}`} role="region"
             aria-label={dirty ? t('userpush.unsavedTitle') : t('userpush.savedTitle')}>
          <span className="up-savebar-msg">
            {dirty ? <Save size={15} aria-hidden="true" /> : <Check size={15} aria-hidden="true" />}
            {' '}{dirty ? t('userpush.unsaved') : t('userpush.allSaved')}
          </span>
          <div className="up-savebar-actions">
            {dirty && (
              <Button type="button" variant="secondary" size="sm" onClick={discard} disabled={saving}>{t('userpush.discard')}</Button>
            )}
            <Button type="button" size="sm" onClick={save} disabled={saving || !dirty}>
              {saving ? <Spinner size={14} inline decorative /> : <Save size={14} />} {saving ? t('settings.saving') : t('settings.save')}
            </Button>
          </div>
        </div>
      )}

      {winModal && (
        <WindowModal win={winModal.win} status={winModal.status}
          counts={winStats(winModal.win).counts} teams={winStats(winModal.win).teams} windowFrom={windowFrom} statusTone={statusTone}
          userTeamsHint={userTeams} t={t}
          onStatus={(s) => setWinModal({ ...winModal, status: s })}
          onOpenInLog={openInLog} onClose={() => setWinModal(null)} />
      )}
    </div>
  )
}
