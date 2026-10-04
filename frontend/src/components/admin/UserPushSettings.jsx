import { useCallback, useEffect, useMemo, useState, useRef, useId } from 'react'
import {
  Save, Send, BellRing, Plus, Trash2, Copy, RefreshCw, Search as SearchIcon,
  Crown, UserCog, Briefcase, Globe, Network, Target, Radio, CalendarDays,
  ScanSearch, FlaskConical, Gauge, ShieldCheck, WifiOff, Timer, CalendarClock,
  ArrowLeftRight, CheckCircle2, OctagonPause, Check, Landmark, Building2, ChevronDown, TriangleAlert,
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
import { useServerPagination } from '../../hooks/useServerPagination.js'
import UserBadge from '../ui/UserBadge.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import HelpTip from '../ui/HelpTip.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import { helpLabel, SETTINGS_STACK, SettingsHeader, ToggleRow } from './SettingsControls.jsx'
import ToneBadge, { DecisionBadge } from './ToneBadge.jsx'
import { formatDateSec } from '../../api/client'
import { copyText } from '../../utils/copyText.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { Toggle } from '@/components/shadcn/toggle'
import { cn } from '@/lib/utils'

/**
 * Kişi-bazlı Webhook Bildirimleri — mail hattından TAMAMEN bağımsız ikinci kanalın yönetimi.
 *
 * Tasarım dili (shadcn/ui, 2026-09-25 geçişi):
 * - Aç/kapa durumları CHECKBOX değil shadcn SWITCH: "Switch vs Checkbox" ayrımı — bunlar anında
 *   etkiyen durum anahtarları, form seçimi değil. (Başlıktaki "sır" işareti form seçeneği → Checkbox.)
 * - Bölümler shadcn Collapsible + Card: başlık = CollapsibleTrigger; kapalıyken içerik DOM'da kalır
 *   (forceMount + hidden) — girilen değerler kaybolmaz.
 * - Unvan grupları SEÇİLEBİLİR KART (Card): ikon + ad + açıklama + Switch + seviye rozeti (Badge);
 *   kart açıkken vurgu kenarlığı alır.
 * - Tip/takım matrisi shadcn TOGGLE grubu: ikonlu, basılabilir çipler (aria-pressed) — 10 tip
 *   Nav'daki ikonlarıyla; kapalı çip soluk kalır, açık çip dolgulu.
 * - Şablon önizlemesi PUSH BİLDİRİM MAKETİ (Card): kullanıcı metni tam olarak telefonda görüneceği
 *   biçimde görür — düz italik satırdan çok daha az soyut.
 * - İstatistik şeridi KPI kartları (Card); kanal kapalıyken uyarı (AlertBanner).
 *
 * Sır sözleşmesi değişmedi: başlık değerleri sunucudan MASKELİ gelir; kullanıcı değiştirmedikçe
 * maskeli değer geri gönderilir ve sunucu eski şifreli değeri korur (write-only).
 */

const KEY = (k) => `site.monitor.userpush.${k}`
/** Eskalasyon adımı push anahtarı (2026-10-04) — userpush önekli değil; ayar grubu userpush. */
const STEP_PUSH_KEY = 'site.monitor.escalation.step-push-enabled'
// 'cert' sunucuda VAR (UserPushService.DEFAULT_TEMPLATES) ama listede yoktu: sertifika
// guvenlik alarminin {ip}/{cn} kanitini tasiyan sablon duzenlenemiyor, onizlenemiyor ve
// test gonderiminde secilemiyordu.
// 'degraded' (2026-10-04): sunucuda vardı ama ekranda yoktu — sayfa bütünlüğü / alan adı durumu push'u düzenlenemiyordu.
const TEMPLATE_KEYS = ['down', 'slow', 'expiry', 'changed', 'cert', 'degraded', 'resolved', 'test']
const STATUS_OPTIONS = ['SENT', 'FAILED', 'PENDING', 'RATE_LIMITED', 'CIRCUIT_OPEN',
  'SKIPPED_TYPE_OFF', 'SKIPPED_TEAM_OFF', 'SKIPPED_MONITOR_OFF', 'SKIPPED_QUIET_HOURS',
  'SKIPPED_REALERT_OFF', 'SKIPPED_NO_RECIPIENTS', 'SKIPPED_USER_OPT_OUT', 'SKIPPED_NO_PRIOR',
  'SKIPPED_TEAM_QUIET', 'SKIPPED_USER_QUIET_HOURS',   // 2026-10-01: takım / kişisel sessiz saat
  'SKIPPED_SYSTEM_MAINTENANCE',   // 2026-10-02: sistem bakımı
  // 2026-10-04: kişisel tercihler + eskalasyon adımı kişi eşlemesi
  'SKIPPED_USER_LEVEL', 'SKIPPED_USER_TYPE', 'SKIPPED_USER_SNOOZE', 'SKIPPED_USER_INACTIVE',
  'SKIPPED_NO_USER_MATCH', 'SKIPPED_AMBIGUOUS_USER']
const TRIGGERS = ['OPEN', 'ESCALATION', 'RE_ALERT', 'RESOLVE', 'RESEND', 'WEAK_ALGO', 'WEEKLY_REPORT', 'TEST',
  'STORM', 'STORM_RESOLVED', 'SCRIPTED_DISABLED', 'DOMAIN_EXPIRY_REMINDER', 'OVERFLOW_SUMMARY', 'ESCALATION_STEP']   // 2026-10-04   // WEAK_ALGO: rapor 'takıma bildir' (2026-09-12); WEEKLY_REPORT: onay → takıma (2026-09-13)

/** İzleme tipleri — ikonlar Nav/ChangeKindCards ile AYNI: kullanıcı yeni görsel dil öğrenmez. */
const TYPES = [
  { key: 'cert', Icon: ShieldCheck }, { key: 'http', Icon: Globe }, { key: 'port', Icon: Network },
  { key: 'dns', Icon: SearchIcon }, { key: 'keyword', Icon: Target }, { key: 'ping', Icon: Radio },
  { key: 'domain', Icon: CalendarDays }, { key: 'page', Icon: ScanSearch },
  { key: 'scripted', Icon: FlaskConical }, { key: 'pagespeed', Icon: Gauge },
]

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

/** Teslimat durumu rozeti — ToneBadge (shadcn Badge); `data-tone` test kancası. */
function StatusBadge({ status, tone }) {
  return <ToneBadge tone={tone === 'ok' ? 'success' : tone} className="font-bold tracking-wide">{status}</ToneBadge>
}

/** Alt başlık (bölüm içi) — eski `.userpush-subsub`. */
function SubHead({ children, className = '' }) {
  return <h5 className={cn('mt-3.5 mb-1 flex items-center text-[0.95em] font-semibold', className)}>{children}</h5>
}

/**
 * Açılır/kapanır bölüm (2026-09-11, kullanıcı: "başlıkları açılır kapanır menüye dönüştür,
 * istediğim bölümü açıp değiştireyim"). shadcn Collapsible + Card: başlık = CollapsibleTrigger
 * (Button); yardım ipucu (HelpTip) kendi düğmesi olduğu için tetiğin DIŞINDA, aynı satırda
 * (iç içe button olmaz — TeamBadge dersi). İçerik forceMount + `hidden`: kapalıyken DOM'da kalır
 * (girilen değerler korunur) ama görünmez ve erişilebilirlik ağacı dışındadır.
 * Test kancaları: kök `data-section` + `data-state`, tetik `data-section-toggle`.
 */
function Section({ id, title, open, onToggle, help, description, className = '', children }) {
  return (
    <Collapsible open={open} onOpenChange={onToggle} data-section={id} className={className}>
      <Card className="gap-0 py-0">
        <CardHeader className="flex items-center gap-1.5 px-4 py-2">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" data-section-toggle={id}
              className="h-auto min-w-0 flex-1 justify-start gap-2 px-1 py-1 text-[1.02em] font-bold hover:bg-transparent hover:text-primary dark:hover:bg-transparent">
              <ChevronDown aria-hidden="true"
                className={cn('text-muted-foreground transition-transform motion-reduce:transition-none', !open && '-rotate-90')} />
              <span className="truncate">{title}</span>
            </Button>
          </CollapsibleTrigger>
          {help}
        </CardHeader>
        <CollapsibleContent forceMount hidden={!open}>
          <CardContent className="border-t px-4 pt-4 pb-5 border-border">
            {description && <p className="mb-2 text-sm text-muted-foreground">{description}</p>}
            {children}
          </CardContent>
        </CollapsibleContent>
      </Card>
    </Collapsible>
  )
}

/**
 * Teslimat günlüğü satırı — başlık (durum · kişi · takım · izleme · tetik · zaman) + açılır ayrıntı.
 * Günlük listesi ve KPI pencere modalı AYNI satırı çizer; iki kopya zamanla ayrışırdı.
 * shadcn Collapsible: başlık tetik (button), ayrıntı CollapsibleContent (kapalıyken DOM'da yok).
 * Dar kapta (≤ 720px, `@container` liste kabı) başlık İKİ satıra kırılır.
 */
function DeliveryRow({ r, isOpen, onToggle, userTeams, t, statusTone }) {
  return (
    <Collapsible open={isOpen} onOpenChange={onToggle} className="overflow-hidden rounded-lg border border-border">
      <CollapsibleTrigger
        className="grid w-full cursor-pointer grid-cols-[auto_minmax(0,1fr)] items-center gap-2.5 gap-y-1 px-2.5 py-[7px] text-left hover:bg-muted/50 @min-[720px]:grid-cols-[auto_minmax(160px,1.3fr)_minmax(120px,1fr)_auto_auto] @min-[720px]:gap-y-2.5">
        <StatusBadge status={r.status} tone={statusTone(r.status)} />
        <span className="flex min-w-0 flex-wrap items-center gap-1.5 whitespace-normal">
          {r.username === '-' ? <em>{t('userpush.systemRow')}</em>
            : <UserBadge username={r.username} displayName={r.display_name} size="sm" inline nameOnly />}
          {/* Kişinin takım(lar)ı — satır bir <button>, bu yüzden TeamBadge span modunda. */}
          {(userTeams[(r.username || '').toUpperCase()] || []).map((tn) => (
            <TeamBadge key={tn} teamName={tn} size={11} as="span" className="ml-1.5 align-middle" />
          ))}
        </span>
        <span className="col-start-2 truncate @min-[720px]:col-start-auto">{r.monitor_name || '—'}</span>
        <span className="col-start-2 text-[0.8em] text-muted-foreground @min-[720px]:col-start-auto">{t('userpush.trigger.' + r.trigger)}</span>
        <span className="col-start-2 font-mono text-[0.8em] whitespace-nowrap text-muted-foreground @min-[720px]:col-start-auto">{formatDateSec(r.created_at)}</span>
      </CollapsibleTrigger>
      <CollapsibleContent className="border-t border-dashed px-3 pt-2 pb-2.5 border-border">
        {r.message && <NotifPreview title={r.title} message={r.message}
          tone={statusTone(r.status) === 'danger' ? 'danger' : 'info'} />}
        <dl className="mt-1.5 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-[0.85em] [&_dt]:text-muted-foreground">
          {r.http_status != null && <><dt>HTTP</dt><dd>{r.http_status}</dd></>}
          {r.attempts != null && <><dt>{t('userpush.attempts')}</dt><dd>{r.attempts}</dd></>}
          {r.notification_id && (
            <><dt>notificationId</dt>
              <dd>
                <Button type="button" variant="ghost" size="xs" className="h-auto px-1 py-0 font-mono"
                  onClick={() => copyText(r.notification_id)}>
                  {r.notification_id}<Copy size={10} aria-hidden="true" />
                </Button>
              </dd></>
          )}
          {r.batch_id && <><dt>batch</dt><dd className="font-mono">{r.batch_id}</dd></>}
          {r.error && <><dt>{t('userpush.error')}</dt><dd className="break-all text-destructive">{r.error}</dd></>}
        </dl>
      </CollapsibleContent>
    </Collapsible>
  )
}

/** Teslimat listesi kabı — `@container`: satırlar dar kapta (modal) iki satıra kırılır. */
function DeliveryList({ children }) {
  return <div className="@container flex flex-col gap-1">{children}</div>
}

/**
 * Basılabilir seçim çipi — shadcn Toggle (aria-pressed; kapalı = soluk, açık = dolgulu).
 * Tip/takım matrisi ve KPI modalının durum/takım süzgeçleri aynı çipi kullanır.
 */
function ChipToggle({ pressed, onPress, title, children }) {
  return (
    <Toggle variant="outline" size="sm" pressed={!!pressed} onPressedChange={() => onPress()} title={title}
      className="group/chip h-8 rounded-full px-3 text-[0.86em] font-normal text-muted-foreground hover:border-primary hover:bg-transparent hover:text-foreground data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:font-bold data-[state=on]:text-primary">
      {children}
    </Toggle>
  )
}

/** Çip içi sayaç — açık çipte birincil tona geçer. */
function ChipCount({ fail = false, children }) {
  return (
    <Badge variant="secondary"
      className={cn('rounded-full px-1.5 py-0 text-[0.82em] group-data-[state=on]/chip:bg-primary/15 group-data-[state=on]/chip:text-primary',
        fail && 'bg-destructive/15 text-destructive group-data-[state=on]/chip:bg-destructive/15 group-data-[state=on]/chip:text-destructive')}>
      {children}
    </Badge>
  )
}

/**
 * KPI pencere modalı (2026-09-11, kullanıcı): "Son 24 saat / Son 7 gün" kartına tıklayınca ayrıntı
 * bir POP-UP'ta açılır — özet (durum başına sayı), durum süzgeci ve o penceredeki teslimatların listesi.
 * Eski davranış (günlüğü süzüp kaydırmak) "Günlükte aç" düğmesinde bilinçli bir eylem olarak kaldı.
 */
function WindowModal({ win, status, counts, teams: teamRows, windowFrom, statusTone, userTeamsHint, onStatus, onOpenInLog, onClose, t }) {
  const [rows, setRows] = useState(null)
  const [userTeams, setUserTeams] = useState(userTeamsHint || {})
  const [openRow, setOpenRow] = useState(null)
  // Takım süzgeci (2026-09-12, kullanıcı: "kutular takım bazlı adet versin"): çipler alarmın TAKIMINA
  // göre (teslimat satırındaki team_id) — kişinin üyelikleri değil. null = tüm takımlar.
  const [team, setTeam] = useState(null)
  // Pencere başlangıcı açılışta (ya da pencere değişince) BİR KEZ hesaplanır (2026-09-27 regresyon B2): her çizimde
  // yeniden hesaplanan saniye hassasiyetli değer yükleme efektinin bağımlılığındaydı → yanıt → yeni çizim → yeni
  // saniye → yeni istek döngüsü (liste yanıp sönüyor, uç boşuna dövülüyordu). `windowFrom` yalnız `win` + saate bağlı.
  const from = useMemo(() => windowFrom(win), [win])   // eslint-disable-line react-hooks/exhaustive-deps
  // Sayfalama standardı (2026-09-26): pencere içi liste → modal ön ayarı. Eskiden `SIZE = 25` + boyut seçicisi
  // çizilen ama bağlı olmayan (ÖLÜ) çubuk ve mount'ta da koşan `useEffect(() => setPage(0), …)` vardı.
  const winPager = useServerPagination({ listKey: 'userpush-window', preset: 'modal', resetDeps: [win, status, team], apiBase: 0 })
  const { apiPage: page, pageSize: winSize, setTotal: setWinTotal } = winPager

  useEffect(() => {
    let alive = true
    setRows(null)
    const params = { page, size: winSize, from }
    if (status) params.status = status
    if (team != null) params.teamId = team
    Promise.resolve(api.admin.userPush.getDeliveries(params))
      .then((res) => {
        if (!alive) return
        if (res?.success) {
          setRows(res.data?.deliveries || [])
          setWinTotal(res.data?.total || 0)
          setUserTeams((prev) => ({ ...prev, ...(res.data?.user_teams || {}) }))
        } else setRows([])
      })
      .catch(() => { if (alive) setRows([]) })
    return () => { alive = false }
  }, [win, status, team, page, winSize, from, setWinTotal])

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
      <p className="mb-1.5 text-sm text-muted-foreground">{t('userpush.winSince', formatDateSec(from + 'Z'))}</p>
      <div className="mt-2 mb-1 flex flex-wrap gap-2" role="group" aria-label={t('userpush.winStatusFilter')}>
        {chips.map(([value, label, n]) => (
          <ChipToggle key={value || 'all'} pressed={status === value} onPress={() => onStatus(value)}>
            <span>{label}</span><ChipCount>{n}</ChipCount>
          </ChipToggle>
        ))}
      </div>
      {Array.isArray(teamRows) && teamRows.length > 0 && (
        <div className="mt-0.5 mb-1 flex flex-wrap gap-2" role="group" aria-label={t('userpush.winTeamFilter')}>
          <ChipToggle pressed={team == null} onPress={() => setTeam(null)}>
            <span>{t('userpush.winAllTeams')}</span>
          </ChipToggle>
          {teamRows.filter((tr) => tr.team_id != null).map((tr) => (
            <ChipToggle key={tr.team_id} pressed={team === tr.team_id}
              onPress={() => setTeam(team === tr.team_id ? null : tr.team_id)}
              title={`SENT ${tr.SENT} · FAILED ${tr.FAILED}`}>
              <span>{tr.team_name}</span><ChipCount>{tr.total}</ChipCount>
              {tr.FAILED > 0 && <ChipCount fail>{tr.FAILED}</ChipCount>}
            </ChipToggle>
          ))}
        </div>
      )}
      {rows === null ? <LoadingBlock label={t('modal.loading')} />
        : rows.length === 0 ? (
          <StatusBlock tone="neutral" icon={BellRing} title={t('userpush.winEmpty')} description={t('userpush.logEmpty')} />
        ) : (
          <DeliveryList>
            {rows.map((r) => (
              <DeliveryRow key={r.id} r={r} isOpen={openRow === r.id} onToggle={() => setOpenRow(openRow === r.id ? null : r.id)}
                userTeams={userTeams} t={t} statusTone={statusTone} />
            ))}
          </DeliveryList>
        )}
      <PaginationBar {...winPager.bar} />
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
  degraded: { Icon: TriangleAlert, tone: 'warn' },
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
/** İngilizce önizleme örnekleri (2026-10-04) — sunucu İngilizce kurucularıyla aynı biçim (seviye, süre birimi). */
const PREVIEW_VALS_EN = {
  ...PREVIEW_VALS, seviye: 'CRITICAL', ad: 'Example monitor', neden: 'connection timed out', metrik: 'response time',
  ne: 'SSL certificate', degisen: 'record', sure: '25 min', cn: 'example.example.com',
}

function preview(template, lang = 'tr') {
  let out = template || ''
  for (const [k, v] of Object.entries(lang === 'en' ? PREVIEW_VALS_EN : PREVIEW_VALS)) out = out.replaceAll(`{${k}}`, v)
  return out.replace(/\s{2,}/g, ' ').trim()
}

/** Basılabilir ikonlu seçim çipi (tip/takım kapsamı) — açıkken onay işareti. */
function ToggleChip({ on, onToggle, Icon, label }) {
  return (
    <ChipToggle pressed={on} onPress={onToggle}>
      {Icon && <Icon size={14} aria-hidden="true" />}
      <span>{label}</span>
      {on && <Check size={13} className="flex-none" aria-hidden="true" />}
    </ChipToggle>
  )
}

/** Önizleme maketinin uygulama simgesi tonu (sol renk şeridi YOK — kullanıcı kararı 2026-09-26). */
const NOTIF_ICON = {
  danger: 'bg-destructive text-white', warn: 'bg-amber-500 text-white', ok: 'bg-success text-white', info: 'bg-primary text-primary-foreground',
}

/** Push bildirim maketi — şablonun telefonda görüneceği hâli (shadcn Card). */
function NotifPreview({ title, message, tone }) {
  const t = useT()
  const key = NOTIF_ICON[tone] ? tone : 'info'
  return (
    <Card data-slot="notif-preview" data-tone={key}
      className="w-full max-w-[420px] gap-0.5 rounded-xl bg-muted px-3 py-2 shadow-none">
      <div className="flex items-center gap-1.5">
        <span className={cn('inline-flex size-[18px] flex-none items-center justify-center rounded-[5px]', NOTIF_ICON[key])}>
          <BellRing size={11} aria-hidden="true" />
        </span>
        <span data-slot="notif-app" className="text-[0.74em] font-bold tracking-wide text-muted-foreground uppercase">{title || 'Site Monitor'}</span>
        <span className="ml-auto text-[0.72em] text-muted-foreground">{t('userpush.previewNow')}</span>
      </div>
      <div data-slot="notif-msg" className="text-[0.9em] leading-snug break-words">{message || '—'}</div>
    </Card>
  )
}

/** Şablon kartındaki aile ikonu kutusu (ton). */
const ICON_TONE = {
  danger: 'bg-destructive/15 text-destructive',
  warn: 'bg-amber-500/20 text-amber-700 dark:text-amber-300',
  ok: 'bg-success/15 text-success',
  info: 'bg-primary/10 text-primary',
}

/** Başlık satırındaki "sır" işareti — form seçeneği (Kaydet'le gider) → shadcn Checkbox + bağlı Label. */
function SecretCheckbox({ checked, onChange, label }) {
  const id = useId()
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
      <Checkbox id={id} checked={checked} onCheckedChange={(v) => onChange(v === true)} />
      <Label htmlFor={id} className="cursor-pointer font-normal">{label}</Label>
    </span>
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
  // 2026-10-04 (öneri 5): şablon düzenleyicisinin dil sekmesi + test gönderiminin dili
  const [tplLang, setTplLang] = useState('tr')
  const [testLang, setTestLang] = useState('tr')
  const [testResult, setTestResult] = useState(null)
  const [testing, setTesting] = useState(false)

  // Teslimat günlüğü
  const [rows, setRows] = useState(null)
  const [fUser, setFUser] = useState('')
  const [fStatus, setFStatus] = useState('')
  const [fTrigger, setFTrigger] = useState('')
  const [fNotifId, setFNotifId] = useState('')
  // 2026-09-11 (kullanıcı): KPI kartları tıklanabilir — "kime, ne zaman, ne içerikle gitti" sorusu
  // aynı sayfadaki teslimat günlüğünde cevaplanır: kart pencereyi (24h/7d) süzgeç olarak uygular,
  // SENT/FAILED sayıları ayrıca durumu seçer, liste o bloğa kaydırılır.
  const [fWindow, setFWindow] = useState('')   // '' | '24h' | '7d'
  // Sayfalama standardı (2026-09-26): süzgeç değişince sayfa 1'e dönüşü kanca yapar (her süzgeç düğmesine elle
  // `setPage(0)` yazılmaz). Panel ön ayarı (25), API 0-tabanlı.
  const logPager = useServerPagination({ listKey: 'userpush-log', preset: 'panel', resetDeps: [fUser, fStatus, fTrigger, fNotifId, fWindow], apiBase: 0 })
  const { apiPage: page, pageSize } = logPager
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
  const allOpen = SECTIONS.every(isOpen)
  const pickWindow = (w, status) => setWinModal({ win: w, status: status || '' })
  const openInLog = () => {
    if (!winModal) return
    setFWindow(winModal.win)
    setFStatus(winModal.status || '')
    logPager.reset()
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
      // Dil yalnız İngilizce seçiliyse gönderilir — Türkçe gövde bugünküyle aynı kalır.
      const res = await api.admin.userPush.sendTest({ usernames, template: testTemplate, ...(testLang === 'en' ? { lang: 'en' } : {}) })
      if (res?.success) {
        setTestResult(res.data?.data ?? res.data)
        toast.success(t('userpush.testQueued'))
        logPager.reset()      // yeni satır ilk sayfada doğar
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
      logPager.setTotal(res.data?.total || 0)
      setUserTeams(res.data?.user_teams || {})
    } else {
      setRows([])
    }
  }, [page, pageSize, fUser, fStatus, fTrigger, fNotifId, fWindow, logNonce])   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { loadDeliveries() }, [loadDeliveries])

  if (loading) {
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  const statusTone = (st) => st === 'SENT' ? 'ok' : (st === 'FAILED' || st === 'CIRCUIT_OPEN') ? 'danger'
    : st === 'PENDING' ? 'info' : 'muted'
  // Beş pencere: yeni sözleşme stats.windows[key] = { counts, teams }; eski last24h/last7d yedek.
  const winStats = (w) => stats?.windows?.[w] || (w === '24h' ? { counts: stats?.last24h || {} } : w === '7d' ? { counts: stats?.last7d || {} } : { counts: {} })

  const section = (id) => ({ id, open: isOpen(id), onToggle: () => toggleSec(id) })
  const numField = (key, labelKey, helpKey, min, max, fallback) => (
    <Field label={helpLabel(t(labelKey), helpKey)} className="mb-0">
      {({ id, describedBy }) => (
        <Input id={id} aria-describedby={describedBy} type="number" min={min} max={max} value={val(key, fallback)}
          onChange={(e) => setVal(key, e.target.value)} />
      )}
    </Field>
  )

  return (
    <div className={cn(SETTINGS_STACK, 'gap-4')} data-testid="userpush-settings">
      {/* ── Başlık (SettingsHeader) + KPI şeridi ── */}
      <SettingsHeader icon={BellRing} title={t('userpush.title')} description={t('userpush.desc')}
        actions={(
          <Button type="button" variant="outline" size="sm" className="flex-none" onClick={() => setAllSections(!allOpen)}>
            {allOpen ? t('userpush.collapseAll') : t('userpush.expandAll')}
          </Button>
        )}>
        {stats && (
          // Düzenli ızgara (2026-09-27): telefonda 2, ≥640 px 3, masaüstünde 5 sütun — flex-wrap 2+1+1+1 sarıyordu
          <div data-slot="userpush-kpis" className="grid grid-cols-2 gap-2.5 text-[0.9em] sm:grid-cols-3 lg:grid-cols-5">
            {WINDOWS.map(({ key, label }) => {
              const ws = winStats(key)
              const c = ws.counts || {}
              const top = (ws.teams || []).filter((tr) => tr.team_id != null).slice(0, 3)
              const winName = t(label)
              // Kart = pencere (tümü); içindeki SENT/FAILED sayıları ayrı düğme (durumu da seçer).
              // "Stretched button" (monitoring/MonitorCard deseni): pencere adı GERÇEK düğme, ::after örtüsü kartı
              // kaplar → kartın herhangi bir yerine basmak pencereyi açar; sayı düğmeleri örtünün ÜSTÜNDE (z-10).
              // Eskiden kartın kendisi role="button" idi ve içinde gerçek düğmeler vardı (iç içe etkileşim).
              return (
                <Card key={key} data-window={key}
                  title={t('userpush.kpiHint')}
                  className={cn('relative min-w-0 gap-1 px-3.5 py-2.5 shadow-none transition-[border-color,box-shadow] hover:border-primary has-[[data-window-pick]:focus-visible]:border-primary motion-reduce:transition-none',
                    fWindow === key && 'border-primary ring-2 ring-primary/25')}>
                  <Button type="button" variant="ghost" data-window-pick="" aria-pressed={fWindow === key}
                    onClick={() => pickWindow(key, '')}
                    className="h-auto justify-start rounded-none p-0 text-[0.74em] font-bold tracking-wider text-muted-foreground uppercase hover:bg-transparent hover:text-muted-foreground focus-visible:ring-0 after:absolute after:inset-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50 dark:hover:bg-transparent">
                    {winName}
                  </Button>
                  <span className="relative z-10 flex items-baseline gap-1 self-start">
                    <Button type="button" variant="ghost" size="xs" className="h-auto px-1 py-0 text-[1.25em] font-bold text-success hover:text-success"
                      aria-label={t('a11y.rowAction', winName, `SENT ${c.SENT || 0}`)}
                      onClick={(e) => { e.stopPropagation(); pickWindow(key, 'SENT') }}>{c.SENT || 0}</Button>
                    <small className="mr-2 text-[0.68em] text-muted-foreground">SENT</small>
                    <Button type="button" variant="ghost" size="xs" className="h-auto px-1 py-0 text-[1.25em] font-bold text-destructive hover:text-destructive"
                      aria-label={t('a11y.rowAction', winName, `FAILED ${c.FAILED || 0}`)}
                      onClick={(e) => { e.stopPropagation(); pickWindow(key, 'FAILED') }}>{c.FAILED || 0}</Button>
                    <small className="text-[0.68em] text-muted-foreground">FAILED</small>
                  </span>
                  {/* Takım kırılımı: en yoğun 3 takım (toplam · başarısız). Tamamı modalda. */}
                  {top.length > 0 && (
                    <span className="mt-1 flex flex-col gap-0.5 border-t border-dashed pt-1 text-[0.74em] text-muted-foreground border-border">
                      {top.map((tr) => (
                        <span key={tr.team_id} className="flex min-w-0 items-center gap-1.5" title={`SENT ${tr.SENT} · FAILED ${tr.FAILED}`}>
                          <span className="min-w-0 flex-1 truncate">{tr.team_name}</span>
                          <b className="font-bold text-foreground">{tr.total}</b>{tr.FAILED > 0 && <b className="font-bold text-destructive">{tr.FAILED}</b>}
                        </span>
                      ))}
                      {(ws.teams || []).filter((tr) => tr.team_id != null).length > 3 && <span className="text-muted-foreground">…</span>}
                    </span>
                  )}
                </Card>
              )
            })}
            {health.circuit_open && (
              <AlertBanner tone="danger" icon={OctagonPause} className="col-span-full mb-0 font-bold">
                {t('userpush.circuitOpen')}
              </AlertBanner>
            )}
          </div>
        )}
      </SettingsHeader>

      {/* ── Global anahtar — switch + durum uyarısı ── */}
      <Card className="gap-2.5 py-4">
        <CardContent className="flex flex-col gap-1.5 px-4">
          <ToggleRow major checked={enabled} onChange={(v) => setVal('enabled', v ? 'true' : 'false')}
            label={t('userpush.enabled')} helpKey="help.set.site.monitor.userpush.enabled" />
          <p className="text-xs text-muted-foreground">{t('userpush.enabledHint')}</p>
          {!enabled && (
            <AlertBanner tone="danger" icon={OctagonPause} className="mt-1.5 mb-0">{t('userpush.disabledWarn')}</AlertBanner>
          )}
        </CardContent>
      </Card>

      <div className={cn('flex flex-col gap-4', !enabled && 'opacity-55')}>
        {/* ── Bağlantı ── */}
        <Section {...section('conn')} title={t('userpush.connTitle')} description={t('userpush.connDesc')}>
          <Field label={helpLabel(t('userpush.url'), 'help.set.site.monitor.userpush.url')}>
            {({ id, describedBy }) => (
              <Input id={id} aria-describedby={describedBy} type="text" value={val('url')} placeholder="http://..."
                onChange={(e) => setVal('url', e.target.value)} />
            )}
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={helpLabel(t('userpush.pipeline'), 'help.set.site.monitor.userpush.pipeline')}>
              {({ id, describedBy }) => (
                <Input id={id} aria-describedby={describedBy} type="text" value={val('pipeline')} placeholder=""
                  onChange={(e) => setVal('pipeline', e.target.value)} />
              )}
            </Field>
            <Field label={helpLabel(t('userpush.titleField'), 'help.set.site.monitor.userpush.title')}>
              {({ id, describedBy }) => (
                <Input id={id} aria-describedby={describedBy} type="text" value={val('title', 'Site Monitor')}
                  onChange={(e) => setVal('title', e.target.value)} />
              )}
            </Field>
          </div>

          <SubHead>{t('userpush.headers')}<HelpTip helpKey="help.set.site.monitor.userpush.headers" label={t('userpush.headers')} /></SubHead>
          <p className="mb-2 text-xs text-muted-foreground">{t('userpush.headersHint')}</p>
          {headers.map((h, i) => {
            const rowName = h.name || String(i + 1)
            return (
              <div key={i} className="mb-2 grid grid-cols-[minmax(0,1fr)_auto] items-center gap-2 sm:grid-cols-[1fr_1.4fr_auto_auto] [&>input]:col-span-2 sm:[&>input]:col-span-1">
                <Input type="text" placeholder={t('userpush.headerName')} value={h.name || ''}
                  aria-label={t('a11y.rowAction', String(i + 1), t('userpush.headerName'))}
                  onChange={(e) => setHeaders(headers.map((x, j) => j === i ? { ...x, name: e.target.value } : x))} />
                <Input type={h.secret ? 'password' : 'text'} placeholder={t('userpush.headerValue')} autoComplete="new-password"
                  value={h.value || ''} aria-label={t('a11y.rowAction', rowName, t('userpush.headerValue'))}
                  onChange={(e) => setHeaders(headers.map((x, j) => j === i ? { ...x, value: e.target.value } : x))} />
                <span className="inline-flex items-center">
                  <SecretCheckbox checked={!!h.secret} label={t('userpush.headerSecret')}
                    onChange={(v) => setHeaders(headers.map((x, j) => j === i ? { ...x, secret: v } : x))} />
                  <HelpTip helpKey="help.userpush.headerRow" label={t('userpush.headerSecret')} />
                </span>
                <Button type="button" variant="outline" size="icon-sm" aria-label={t('userpush.headerDelete', rowName)}
                  onClick={() => setHeaders(headers.filter((_, j) => j !== i))}><Trash2 size={14} /></Button>
              </div>
            )
          })}
          <Button type="button" variant="outline" size="sm" onClick={() => setHeaders([...headers, { name: '', value: '', secret: true }])}>
            <Plus size={14} /> {t('userpush.addHeader')}
          </Button>

          <div className="mt-3.5 grid grid-cols-2 gap-3 md:grid-cols-4">
            {numField('timeout-connect-seconds', 'userpush.timeoutConnect', 'help.set.site.monitor.userpush.timeout-connect-seconds', 1, 30, '3')}
            {numField('timeout-total-seconds', 'userpush.timeoutTotal', 'help.set.site.monitor.userpush.timeout-total-seconds', 1, 60, '5')}
            {numField('retry-max', 'userpush.retryMax', 'help.set.site.monitor.userpush.retry-max', 0, 5, '2')}
            {numField('hourly-cap', 'userpush.hourlyCap', 'help.set.site.monitor.userpush.hourly-cap', 1, 500, '30')}
            {/* Mesaj uzunlugu: ikisi de gomulu sabitti. Ust sinirlar bilerek dar - max-message
                urun sozlesmesi (K6: <=200 karakter, tek satir), kaldirmak kanali sessizce deler. */}
            {numField('max-message-chars', 'userpush.maxMessageChars', 'help.set.site.monitor.userpush.max-message-chars', 80, 320, '200')}
            {numField('reason-max-chars', 'userpush.reasonMaxChars', 'help.set.site.monitor.userpush.reason-max-chars', 40, 280, '160')}
          </div>

          {/* Saat tavanı özeti + kritik muafiyeti (2026-10-04, onaylı öneri 2) — tavanın hemen yanında */}
          <div data-slot="userpush-overflow" className="mt-3.5 flex min-w-0 flex-col gap-2 rounded-lg border p-3">
            <SubHead className="m-0">{t('userpush.overflowTitle')}</SubHead>
            <ToggleRow checked={val('overflow-summary-enabled', 'true') !== 'false'}
              onChange={(v) => setVal('overflow-summary-enabled', v ? 'true' : 'false')}
              label={t('userpush.overflowEnabled')} helpKey="help.set.site.monitor.userpush.overflow-summary-enabled" />
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 md:grid-cols-4">
              {numField('overflow-summary-minutes', 'userpush.overflowMinutes', 'help.set.site.monitor.userpush.overflow-summary-minutes', 5, 120, '15')}
            </div>
            <ToggleRow checked={val('critical-bypass-cap', 'false') === 'true'}
              onChange={(v) => setVal('critical-bypass-cap', v ? 'true' : 'false')}
              label={t('userpush.criticalBypass')} helpKey="help.set.site.monitor.userpush.critical-bypass-cap" />
            <p className="m-0 text-xs text-muted-foreground">{t('userpush.overflowHint')}</p>
          </div>
        </Section>

        {/* ── Unvan grupları — SEÇİLEBİLİR KARTLAR ── */}
        <Section {...section('groups')} title={t('userpush.groupsTitle')} description={t('userpush.groupsDesc')}
          help={<HelpTip helpKey="help.set.site.monitor.userpush.role-groups" label={t('userpush.groupsTitle')} />}>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(min(260px,100%),1fr))] gap-3">
            {Object.entries(groupsSafe).map(([key, g]) => {
              const Icon = GROUP_META[key]?.Icon || UserCog
              const name = t(`userpush.group.${key}`)
              return (
                <Card key={key} data-state={g.enabled ? 'on' : 'off'}
                  className={cn('gap-0 p-3.5 shadow-none transition-[border-color,box-shadow] motion-reduce:transition-none',
                    g.enabled && 'border-primary ring-[3px] ring-primary/10')}>
                  <div className="flex items-center gap-2">
                    <span className={cn('inline-flex size-8 flex-none items-center justify-center rounded-[9px]',
                      g.enabled ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground')}>
                      <Icon size={17} aria-hidden="true" />
                    </span>
                    <CardTitle className="font-bold">{name}</CardTitle>
                    <Switch className="ml-auto" checked={!!g.enabled} aria-label={name}
                      onCheckedChange={() => setRoleGroups({ ...groupsSafe, [key]: { ...g, enabled: !g.enabled } })} />
                  </div>
                  <p className="mt-2 mb-1.5 min-h-[2.4em] text-[0.82em] text-muted-foreground">{t(`userpush.groupDesc.${key}`)}</p>
                  {/* Kademe = tek org rolü; rozet olarak yazılır, seçilemez (TIERS). */}
                  <div role="group" className="mb-2 flex flex-wrap gap-1.5" aria-label={t('userpush.groupRolesLabel')}>
                    <Badge variant="secondary" className="font-bold text-muted-foreground">{t('userpush.groupOrgRole')}</Badge>
                    {(g.patterns || []).map((code) => (
                      <Badge key={code} variant="secondary" className="font-bold text-muted-foreground">{t(`usr.orgRoleVal.${code}`)}</Badge>
                    ))}
                  </div>
                  {/* Asgari seviye ARTIK DUZENLENEBILIR. Eskiden yalniz `minLevel === 'HIGH'`
                      oldugunda salt-okunur bir rozet ciziliyordu: ayar kaliciydi ve davranisi
                      belirliyordu (UserPushRecipientResolver:61 siddet kurali) ama arayuzden
                      DEGISTIRILEMIYORDU. Uretimde UYARI seviyesindeki bir sertifika alarmi bu
                      yuzden hic push alici bulamiyor, ekran da nedenini soylemiyordu.
                      Merdiven backend ile ayni: KRITIK > YUKSEK > digerleri (UYARI). */}
                  <div className="mt-0.5 mb-2 flex flex-col gap-1.5">
                    <span className="flex items-center text-[0.78em] font-bold tracking-wide text-muted-foreground uppercase">
                      {t('userpush.groupMinLevel')}
                      <HelpTip helpKey="help.userpush.groupMinLevel" label={t('userpush.groupMinLevel')} />
                    </span>
                    <SegmentedControl
                      value={g.minLevel || 'WARNING'}
                      ariaLabel={`${name} — ${t('userpush.groupMinLevel')}`}
                      onChange={(v) => setRoleGroups({ ...groupsSafe, [key]: { ...g, minLevel: v } })}
                      options={[
                        { value: 'WARNING',  label: t('userpush.levelWarning') },
                        { value: 'HIGH',     label: t('userpush.levelHigh') },
                        { value: 'CRITICAL', label: t('userpush.levelCritical') },
                      ]} />
                    <span className="text-[0.78em] text-muted-foreground">{t('userpush.groupMinLevelHint')}</span>
                  </div>
                </Card>
              )
            })}
          </div>
        </Section>

        {/* ── Tip + Takım kapsamı — TOGGLE çip grupları ── */}
        <Section {...section('scopes')} title={t('userpush.scopesTitle')} description={t('userpush.scopesDesc')}>
          <SubHead>{t('userpush.typeMatrix')}<HelpTip helpKey="help.userpush.typeMatrix" label={t('userpush.typeMatrix')} /></SubHead>
          <div className="mt-2 mb-1 flex flex-wrap gap-2" role="group" aria-label={t('userpush.typeMatrix')}>
            {TYPES.map(({ key, Icon }) => (
              <ToggleChip key={key} Icon={Icon} label={t('userpush.type.' + key)}
                on={scopeOn('TYPE', key)}
                onToggle={() => toggleScope('TYPE', key, scopeOn('TYPE', key))} />
            ))}
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-2.5">
            <SubHead className="m-0">{t('userpush.teamMatrix')}
              <HelpTip helpKey="help.userpush.teamMatrix" label={t('userpush.teamMatrix')} /></SubHead>
            <Input type="text" className="w-full sm:w-auto sm:max-w-[220px]" value={teamQuery}
              placeholder={t('userpush.searchTeam')} aria-label={t('userpush.searchTeam')}
              onChange={(e) => setTeamQuery(e.target.value)} />
            <Button type="button" variant="outline" size="sm" onClick={() => bulkTeams(true)}>{t('userpush.enableAll')}</Button>
            <Button type="button" variant="outline" size="sm" onClick={() => bulkTeams(false)}>{t('userpush.disableAll')}</Button>
          </div>
          <div className="mt-2 mb-1 flex flex-wrap gap-2" role="group" aria-label={t('userpush.teamMatrix')}>
            {visibleTeams.map((tm) => (
              <ToggleChip key={tm.id} label={tm.name}
                on={scopeOn('TEAM', tm.id)}
                onToggle={() => toggleScope('TEAM', tm.id, scopeOn('TEAM', tm.id))} />
            ))}
            {visibleTeams.length === 0 && <span className="text-xs text-muted-foreground">{t('userpush.noTeamMatch')}</span>}
          </div>
        </Section>

        {/* ── Sessiz saatler + tekrar kuralı ── */}
        <Section {...section('quiet')} title={t('userpush.quietTitle')} description={t('userpush.quietDesc')}>
          {/* 2026-10-04: 768 px tablette kenar çubuğuyla daralan kapta üç sabit sütun seviye seçicisini kabın dışına itiyordu
              (sayfa 97 px yatay kayıyordu) — iki sütun, geniş ekranda üç; seçici sarar. */}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-[repeat(3,minmax(140px,220px))]">
            <Field label={helpLabel(t('userpush.quietStart'), 'help.set.site.monitor.userpush.quiet-start')}>
              {({ id, describedBy }) => (
                <Input id={id} aria-describedby={describedBy} type="time" value={val('quiet-start')}
                  onChange={(e) => setVal('quiet-start', e.target.value)} />
              )}
            </Field>
            <Field label={helpLabel(t('userpush.quietEnd'), 'help.set.site.monitor.userpush.quiet-end')}>
              {({ id, describedBy }) => (
                <Input id={id} aria-describedby={describedBy} type="time" value={val('quiet-end')}
                  onChange={(e) => setVal('quiet-end', e.target.value)} />
              )}
            </Field>
            <div className="flex flex-col gap-1.5">
              <span className="flex items-center text-sm font-semibold">{t('userpush.quietMinLevel')}
                <HelpTip helpKey="help.set.site.monitor.userpush.quiet-min-level" label={t('userpush.quietMinLevel')} /></span>
              <SegmentedControl value={val('quiet-min-level', 'CRITICAL')} className="flex-wrap"
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
                <span className="text-xs text-muted-foreground">{t('userpush.quietMinLevelWarnHint')}</span>
              )}
            </div>
          </div>
          <ToggleRow className="mt-3" checked={val('realert-enabled', 'true') !== 'false'}
            onChange={(v) => setVal('realert-enabled', v ? 'true' : 'false')}
            label={t('userpush.realertEnabled')} helpKey="help.set.site.monitor.userpush.realert-enabled" />
          {/* Eskalasyon adımı push'u (2026-10-04, onaylı öneri 6) — anahtar escalation.* öneklidir (KEY() değil) */}
          <ToggleRow className="mt-2" checked={(settings[STEP_PUSH_KEY] ?? 'true') !== 'false'}
            onChange={(v) => setSettings((s) => ({ ...s, [STEP_PUSH_KEY]: v ? 'true' : 'false' }))}
            label={t('userpush.stepPush')} helpKey="help.set.site.monitor.escalation.step-push-enabled" />
          <p className="m-0 text-xs text-muted-foreground">{t('userpush.stepPushHint')}</p>
        </Section>

        {/* ── Haftalık rapor onayı (2026-09-13): takıma + müdüre push; e-posta ile aynı anda ── */}
        <Section {...section('weekly')} title={t('userpush.weeklyTitle')} description={t('userpush.weeklyDesc')}>
          <div className="flex flex-col gap-2">
            <ToggleRow checked={val('weekly.team-enabled', 'true') !== 'false'}
              onChange={(v) => setVal('weekly.team-enabled', v ? 'true' : 'false')}
              label={t('userpush.weeklyTeam')} helpKey="help.set.site.monitor.userpush.weekly.team-enabled" />
            <ToggleRow checked={val('weekly.manager-enabled', 'true') !== 'false'}
              onChange={(v) => setVal('weekly.manager-enabled', v ? 'true' : 'false')}
              label={t('userpush.weeklyManager')} helpKey="help.set.site.monitor.userpush.weekly.manager-enabled" />
          </div>
          <p className="mt-2 text-xs text-muted-foreground">{t('userpush.weeklyHint')}</p>
        </Section>

        {/* ── Şablonlar — push bildirim MAKETİ önizlemeli ── */}
        <Section {...section('templates')} title={t('userpush.templatesTitle')} description={t('userpush.templatesDesc')}>
          <p className="mb-2 text-xs break-all text-muted-foreground">
            {t('userpush.placeholders')}: {(defaults.placeholders || []).map((p) => `{${p}}`).join(' ')}
          </p>
          {/* TR / EN sekmeleri (2026-10-04, onaylı öneri 5): push dili İngilizce olan kişiye EN şablonu gider; iki dilin de
              önizlemesi var. EN anahtarları `template.<k>.en`; boşsa gömülü İngilizce varsayılan (defaults.templates_en). */}
          <Tabs value={tplLang} onValueChange={setTplLang} className="min-w-0 gap-3">
            <TabsList aria-label={t('userpush.tplLangAria')} className="h-auto! w-full flex-row! sm:w-fit" data-slot="userpush-tpl-tabs">
              {['tr', 'en'].map((l) => (
                <TabsTrigger key={l} value={l} data-tab={l} className="min-h-10 w-auto! flex-1 justify-center! px-3 sm:min-h-8 sm:flex-none">
                  {t(`userpush.tplLang.${l}`)}
                </TabsTrigger>
              ))}
            </TabsList>
            {['tr', 'en'].map((l) => (
              <TabsContent key={l} value={l} className="mt-0 flex min-w-0 flex-col gap-3" data-slot={`userpush-tpl-${l}`}>
                {l === 'en' && (
                  <>
                    <p className="m-0 text-xs text-muted-foreground">{t('userpush.templatesEnHint')}</p>
                    <Field label={helpLabel(t('userpush.titleEn'), 'help.set.site.monitor.userpush.title.en')} hint={t('userpush.titleEnHint')} className="mb-0 max-w-md">
                      {({ id, describedBy }) => (
                        <Input id={id} aria-describedby={describedBy} type="text" value={val('title.en')} placeholder={val('title', 'Site Monitor')}
                          onChange={(e) => setVal('title.en', e.target.value)} />
                      )}
                    </Field>
                  </>
                )}
                <div className="grid grid-cols-[repeat(auto-fill,minmax(min(320px,100%),1fr))] gap-3">
                  {TEMPLATE_KEYS.map((k) => {
                    // HAM ayar okunur: val() kendi icinde `?? ''` uyguladigi icin hic kaydedilmemis
                    // bir sablonda BOS DIZE dondurur — `??` zinciri o zaman defaults dalina HIC
                    // gecmez ve temiz kurulumda kutular bos cizilirdi (2026-08-30 regresyonu).
                    // Ham deger: kaydedilmemis -> undefined (varsayilan gelir), kullanici sildi -> ''
                    // (bos KALIR). Iki durum ancak boyle ayrilabilir.
                    const sk = l === 'en' ? `template.${k}.en` : `template.${k}`
                    const saved = settings[KEY(sk)]
                    const def = l === 'en' ? defaults.templates_en?.[k] : defaults.templates?.[k]
                    const cur = saved ?? def ?? ''
                    const meta = TEMPLATE_META[k]
                    const MIcon = meta.Icon
                    const name = t(`userpush.template.${k}`)
                    const title = l === 'en' ? (val('title.en') || val('title', 'Site Monitor')) : val('title', 'Site Monitor')
                    return (
                      <Card key={k} className="gap-2 p-3 shadow-none" data-template={k} data-lang={l}>
                        <div className="flex items-center gap-2">
                          <span className={cn('inline-flex size-7 flex-none items-center justify-center rounded-lg', ICON_TONE[meta.tone])}>
                            <MIcon size={15} aria-hidden="true" />
                          </span>
                          <CardTitle className="flex items-center text-[0.92em] font-bold">{name}{l === 'en' && <Badge variant="secondary" className="ml-1.5 text-[0.7em]">EN</Badge>}
                            <HelpTip helpKey={`help.set.site.monitor.userpush.${sk}`} label={name} /></CardTitle>
                        </div>
                        <Input type="text" value={cur} maxLength={220} aria-label={l === 'en' ? `${name} (${t('userpush.tplLang.en')})` : name}
                          onChange={(e) => setVal(sk, e.target.value)} />
                        <NotifPreview title={title} message={preview(cur, l)} tone={meta.tone} />
                      </Card>
                    )
                  })}
                </div>
              </TabsContent>
            ))}
          </Tabs>
        </Section>
      </div>

      {/* ── Test gönderimi ── */}
      <Section {...section('test')} title={t('userpush.testTitle')} description={t('userpush.testDesc')}>
        <TagInput label={t('userpush.testSicils')} value={testSicils} onChange={setTestSicils}
          placeholder="N00001" />
        <div className="mt-2.5 flex flex-wrap items-center gap-2.5">
          <SearchableSelect value={testTemplate} onChange={setTestTemplate}
            options={TEMPLATE_KEYS.map((k) => ({ value: k, label: t(`userpush.template.${k}`) }))}
            ariaLabel={t('userpush.testTemplateAria')} />
          <SegmentedControl value={testLang} onChange={setTestLang} ariaLabel={t('userpush.testLang')} itemClassName="max-sm:min-h-10"
            options={[{ value: 'tr', label: t('userpush.tplLang.tr') }, { value: 'en', label: t('userpush.tplLang.en') }]} />
          <Button type="button" variant="outline" onClick={sendTest} disabled={testing || !enabled}
            aria-busy={testing || undefined} title={!enabled ? t('userpush.disabledWarn') : undefined}>
            {testing ? <Spinner size={14} inline decorative /> : <Send size={14} />} {t('userpush.testSend')}
          </Button>
        </div>
        {testResult && (
          <Card className="mt-2.5 gap-2 px-3 py-2.5 shadow-none">
            <div>{t('userpush.testQueuedN', testResult.queued)}</div>
            <NotifPreview title={testLang === 'en' ? (val('title.en') || val('title', 'Site Monitor')) : val('title', 'Site Monitor')} message={testResult.message} tone="info" />
          </Card>
        )}
      </Section>

      {/* ── Kim alır? (alıcı çözümü açıklaması) ── */}
      <Section {...section('explain')} title={t('userpush.explainTitle')} description={t('userpush.explainDesc')}>
        <div className="mb-2.5 flex flex-wrap items-center gap-2">
          <SearchableSelect value={exTeam} onChange={(v) => setExTeam(v)} placeholder={t('userpush.explainPickTeam')} searchThreshold={4} ariaLabel={t('userpush.explainPickTeam')}
            options={[{ value: '', label: t('userpush.explainPickTeam') }, ...(teams || []).map(tm => ({ value: String(tm.id), label: tm.name }))]} />
          <SegmentedControl value={exLevel} onChange={setExLevel} ariaLabel={t('userpush.explainLevel')}
            options={['WARNING', 'HIGH', 'CRITICAL'].map(l => ({ value: l, label: l }))} />
        </div>
        {exTeam && exLoading && <LoadingBlock label={t('modal.loading')} />}
        {exTeam && !exLoading && exRows && exRows.length === 0 && (
          <StatusBlock tone="neutral" description={t('userpush.explainEmpty')} className="py-6" />
        )}
        {exTeam && !exLoading && exRows && exRows.length > 0 && (
          <div className="overflow-hidden rounded-lg border border-border">
            <Table>
              <TableHeader className="bg-muted/50">
                <TableRow>
                  <TableHead>{t('userpush.explainMember')}</TableHead>
                  <TableHead>{t('userpush.explainTitleCol')}</TableHead>
                  <TableHead>{t('userpush.explainGroup')}</TableHead>
                  <TableHead>{t('userpush.explainDecision')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {exRows.map((m, i) => (
                  <TableRow key={m.username + i} data-decision={m.decision}
                    className={m.decision === 'RECIPIENT' ? undefined : 'text-muted-foreground'}>
                    <TableCell className="whitespace-normal"><strong>{m.display_name || m.username}</strong> <span className="font-mono text-xs text-muted-foreground">{m.username}</span></TableCell>
                    <TableCell className="text-xs whitespace-normal">{m.title || '—'}{m.org_role ? <span className="text-muted-foreground"> · {m.org_role}</span> : null}</TableCell>
                    <TableCell className="text-xs whitespace-normal">{m.group ? <>{m.group}{m.min_level ? <span className="text-muted-foreground"> · ≥ {m.min_level}</span> : null}{m.group_enabled === false ? <span className="text-muted-foreground"> · {t('userpush.explainGroupOff')}</span> : null}</> : '—'}</TableCell>
                    <TableCell>
                      <DecisionBadge decision={m.decision}>{t('userpush.decision.' + m.decision)}</DecisionBadge>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>

      {/* ── Teslimat günlüğü ── */}
      <div ref={logRef} className="scroll-mt-4">
        <Section {...section('log')} title={t('userpush.logTitle')} description={t('userpush.logDesc')}>
          <div className="mb-2.5 flex flex-wrap items-center gap-2">
            {fWindow && (
              <Button type="button" variant="outline" size="sm" className="border-primary text-primary hover:text-primary"
                onClick={() => { setFWindow('') }} title={t('userpush.windowClear')}>
                {t('userpush.windowActive', t(winLabelKey(fWindow)))} ✕
              </Button>
            )}
            <Input type="text" className="w-full sm:w-auto sm:max-w-[200px]" placeholder={t('userpush.filterSicil')} value={fUser}
              aria-label={t('userpush.filterSicil')}
              onChange={(e) => { setFUser(e.target.value) }} />
            <SearchableSelect value={fStatus} onChange={(v) => { setFStatus(v) }}
              options={[{ value: '', label: t('userpush.allStatuses') },
                ...STATUS_OPTIONS.map((s) => ({ value: s, label: s }))]} searchThreshold={8} ariaLabel={t('flt.status')} />
            <SearchableSelect value={fTrigger} onChange={(v) => { setFTrigger(v) }}
              options={[{ value: '', label: t('userpush.allTriggers') },
                ...TRIGGERS.map((s) => ({ value: s, label: t('userpush.trigger.' + s) }))]} searchThreshold={8} ariaLabel={t('flt.trigger')} />
            <Input type="text" className="w-full sm:w-auto sm:max-w-[200px]" placeholder="notificationId" value={fNotifId}
              aria-label={t('userpush.filterNotifId')}
              onChange={(e) => { setFNotifId(e.target.value) }} />
            <Button type="button" variant="outline" size="icon-sm" onClick={loadDeliveries} aria-label={t('app.refresh')}>
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
              <DeliveryList>
                {rows.map((r) => (
                  <DeliveryRow key={r.id} r={r} isOpen={openRow === r.id} onToggle={() => setOpenRow(openRow === r.id ? null : r.id)}
                    userTeams={userTeams} t={t} statusTone={statusTone} />
                ))}
              </DeliveryList>
            )}
          {/* "Sayfa basina" secicisi ONCEDEN OLU kontroldu: onPageSizeChange verilmediginden
              50/100/200'e tiklamak hicbir sey yapmiyor, secici yine de goruluyordu. */}
          <PaginationBar {...logPager.bar} />
        </Section>
      </div>

      {/* Yapışkan kayıt şeridi — HER ZAMAN görünür (2026-09-12, kullanıcı: "kaydet butonunu göremiyorum" —
          yalnız-değişince-beliren şerit keşfedilemiyordu). Temiz durumda "kaydedildi" + pasif Kaydet;
          değişiklik varken vurgulu şerit + Geri al + etkin Kaydet. Sayfa nereye kaydırılırsa kaydırılsın altta.
          Test kancası: role="region" + `data-dirty`. */}
      {!loading && (
        <Card role="region" data-dirty={dirty ? 'true' : undefined}
          aria-label={dirty ? t('userpush.unsavedTitle') : t('userpush.savedTitle')}
          className={cn('sticky bottom-3 z-[5] mt-2 flex-row flex-wrap items-center justify-between gap-3 px-4 py-2.5 shadow-lg',
            dirty && 'border-primary')}>
          <span className={cn('inline-flex items-center gap-2 font-semibold', dirty ? 'text-foreground' : 'text-muted-foreground')}>
            {dirty ? <Save size={15} aria-hidden="true" /> : <Check size={15} aria-hidden="true" />}
            {' '}{dirty ? t('userpush.unsaved') : t('userpush.allSaved')}
          </span>
          <div className="flex gap-2">
            {dirty && (
              <Button type="button" variant="secondary" size="sm" onClick={discard} disabled={saving}>{t('userpush.discard')}</Button>
            )}
            <Button type="button" size="sm" onClick={save} disabled={saving || !dirty} aria-busy={saving || undefined}>
              {saving ? <Spinner size={14} inline decorative /> : <Save size={14} />} {saving ? t('settings.saving') : t('settings.save')}
            </Button>
          </div>
        </Card>
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
