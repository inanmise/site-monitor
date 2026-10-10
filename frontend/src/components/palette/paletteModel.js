import {
  LayoutDashboard, FileBadge, Boxes, FileKey2, MonitorCheck, CalendarRange, RefreshCw, BookOpenText,
  Globe, Radio, EthernetPort, Waypoints, CalendarClock, TextSearch, FileCheck, Gauge, Workflow,
  TriangleAlert, Siren, Wrench, History, Headset,
  ChartPie, ShieldAlert, FileChartColumn, FileClock,
  Logs, UserCheck, Fingerprint, FilePenLine,
  Building2, HeartPulse, KeyRound, Database, MessageSquareWarning,
  LifeBuoy, Settings, LayoutGrid, ShieldCheck, Users, UserRound, Activity, CloudLightning, SignalHigh, ClipboardCheck, Presentation,
} from 'lucide-react'
import { personalKey } from '../../utils/personalStorage.js'

/**
 * Komut paleti veri modeli (2026-09-26 yeniden tasarım). Saf işlevler + tablolar; React yok, böylece
 * `CommandPalette.jsx` yalnız akışı taşır ve bu dosya birim testte doğrudan sınanabilir.
 *
 * Sekme → ikon/bölüm tablosu `Nav.jsx` SECTIONS ile AYNI ikon seti (Nav bunu dışa vermiyor; iki tablo
 * bilinçli olarak kopya — kenar çubuğu başka bir ajanın elinde). İzleme türü kimlikleri sekme kimlikleriyle
 * aynı (`http`, `ping`, …) → izleme sonucunun ikonu da buradan gelir.
 */
export const TAB_META = {
  dashboard:          { Icon: LayoutDashboard, section: null },
  all:                { Icon: FileBadge,     section: 'nav.groupCertificates' },
  domains:            { Icon: Boxes,         section: 'nav.groupCertificates' },
  manualcerts:        { Icon: FileKey2,      section: 'nav.groupCertificates' },   // Manuel Sertifikalar (2026-10-06)
  uptime:             { Icon: MonitorCheck,  section: 'nav.groupCertificates' },
  forecast:           { Icon: CalendarRange, section: 'nav.groupCertificates' },
  renewal:            { Icon: RefreshCw,     section: 'nav.groupCertificates' },
  'renewal-guide':    { Icon: BookOpenText,  section: 'nav.groupCertificates' },
  status:             { Icon: SignalHigh,    section: 'nav.groupMonitoring' },   // Durum Sayfası (2026-10-01)
  http:               { Icon: Globe,         section: 'nav.groupMonitoring' },
  ping:               { Icon: Radio,         section: 'nav.groupMonitoring' },
  port:               { Icon: EthernetPort,  section: 'nav.groupMonitoring' },
  dns:                { Icon: Waypoints,     section: 'nav.groupMonitoring' },
  domain:             { Icon: CalendarClock, section: 'nav.groupMonitoring' },
  keyword:            { Icon: TextSearch,    section: 'nav.groupMonitoring' },
  page:               { Icon: FileCheck,     section: 'nav.groupMonitoring' },
  pagespeed:          { Icon: Gauge,         section: 'nav.groupMonitoring' },
  scripted:           { Icon: Workflow,      section: 'nav.groupMonitoring' },
  warnings:           { Icon: TriangleAlert, section: 'nav.groupAlerts' },
  incidents:          { Icon: Siren,         section: 'nav.groupAlerts' },
  maintenance:        { Icon: Wrench,        section: 'nav.groupAlerts' },
  alerthistory:       { Icon: History,       section: 'nav.groupAlerts' },
  storms:             { Icon: CloudLightning, section: 'nav.groupAlerts' },
  noc:                { Icon: Headset,       section: 'nav.groupAlerts' },
  stats:              { Icon: ChartPie,        section: 'nav.groupReports' },
  weakalgo:           { Icon: ShieldAlert,     section: 'nav.groupReports' },
  dataquality:        { Icon: ClipboardCheck,  section: 'nav.groupReports' },   // Veri Kalitesi (2026-10-10)
  weeklyreports:      { Icon: FileChartColumn, section: 'nav.groupReports' },
  'incident-history': { Icon: FileClock,       section: 'nav.groupReports' },
  executive:          { Icon: Presentation,    section: 'nav.groupReports' },   // Aylık Yönetici Özeti (2026-10-10)
  activity:           { Icon: Logs,        section: 'nav.groupLogs' },
  myactivity:         { Icon: UserCheck,   section: 'nav.groupLogs' },
  system:             { Icon: Fingerprint, section: 'nav.groupLogs' },
  monitorchanges:     { Icon: FilePenLine, section: 'nav.groupLogs' },
  admin:              { Icon: Building2,  section: 'nav.groupAdmin' },
  health:             { Icon: HeartPulse, section: 'nav.groupAdmin' },
  permissions:        { Icon: KeyRound,   section: 'nav.groupAdmin' },
  sqlplayground:      { Icon: Database,   section: 'nav.groupAdmin' },
  'login-issues':     { Icon: MessageSquareWarning, section: 'nav.groupAdmin' },
  help:               { Icon: LifeBuoy, section: null },
  settings:           { Icon: Settings, section: null },
}

/** Sunucu araması (`/api/search`) izleme tür kimlikleri — GlobalSearchService.MONITOR_KINDS ile aynı. */
export const MONITOR_KINDS = ['http', 'ping', 'port', 'dns', 'keyword', 'page', 'pagespeed', 'scripted', 'domain']

/** İzleme türü → kenar çubuğundaki sekme etiketi (sonuç satırının sağındaki tür adı). */
export const MONITOR_LABEL_KEY = {
  http: 'nav.http', ping: 'nav.ping', port: 'nav.port', dns: 'nav.dns', domain: 'nav.domainmon',
  keyword: 'nav.keyword', page: 'nav.page', pagespeed: 'nav.pagespeed', scripted: 'nav.scripted',
}

/** Sonuç türü → ikon (izleme türleri TAB_META'dan). */
export const KIND_ICON = { tab: LayoutGrid, certificate: ShieldCheck, team: Users, user: UserRound }

export function iconFor(kind) {
  if (KIND_ICON[kind]) return KIND_ICON[kind]
  return TAB_META[kind]?.Icon || Activity
}

/** Sonuç türü → grup anahtarı (dokuz izleme türü tek "İzlemeler" grubunda). */
export function groupOf(kind) {
  if (kind === 'tab' || kind === 'certificate' || kind === 'team' || kind === 'user' || kind === 'action') return kind
  return MONITOR_KINDS.includes(kind) ? 'monitor' : 'monitor'
}

/** Canlı sonuç gruplarının sabit sırası. */
export const LIVE_GROUP_ORDER = ['certificate', 'monitor', 'team', 'user']

/** Grup başına gösterilen üst sınır; fazlası "+N daha" satırıyla açılır. */
export const GROUP_CAP = 6

export const MIN_QUERY = 2

/** Türkçe duyarlı küçük harf (İ/ı) — sekme ve eylem eşleşmesi istemcide bununla yapılır. */
export function normalise(s) {
  return String(s ?? '').trim().toLocaleLowerCase('tr')
}

/**
 * Alan(lar) iğneyi içeriyor mu? İki küçük-harf biçimi birden denenir: Türkçe (İ→i, I→ı) ve düz
 * (I→i) — İngilizce arayüzde "Monitoring" içindeki I'yı arayan kullanıcı "i" yazar, Türkçe kural
 * onu ı'ya çevirip kaçırırdı. İğne boşsa her şey eşleşir (boş sorgu = tam liste).
 */
export function matches(needle, ...fields) {
  const n = normalise(needle)
  if (!n) return true
  const plain = String(needle ?? '').trim().toLowerCase()
  return fields.some((f) => f != null && (normalise(f).includes(n) || String(f).toLowerCase().includes(plain)))
}

/** Sunucu vuruşlarını sıralı gruplara böler: [{ key, items }]. */
export function groupHits(hits) {
  const map = new Map()
  for (const h of hits || []) {
    const key = groupOf(h.kind)
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(h)
  }
  return LIVE_GROUP_ORDER.filter((k) => map.has(k)).map((key) => ({ key, items: map.get(key) }))
}

/**
 * Sertifika durumu — CertificateCard ile aynı eşikler (hata > süresi geçmiş > ≤7 kritik > ≤15 yüksek > geçerli).
 * `alert_level` varsa o kazanır. Dönen `state`: error | expired | critical | high | valid.
 */
export function certificateState(cert) {
  if (!cert) return null
  const days = cert.days_remaining
  const hasDays = days !== null && days !== undefined
  const al = cert.alert_level
  const isError = al ? al === 'error' : cert.status === 'error'
  const isExpired = al ? al === 'expired' : (!isError && hasDays && days < 0)
  const isCritical = al ? al === 'critical' : (!isError && hasDays && days >= 0 && days <= 7)
  const isHigh = al ? al === 'high' : (!isError && !isExpired && !isCritical && hasDays && days <= 15)
  const state = isError ? 'error' : isExpired ? 'expired' : isCritical ? 'critical' : isHigh ? 'high' : 'valid'
  return { state, days: hasDays ? days : null }
}

/** Durum → shadcn Badge varyantı + i18n anahtarı. */
export const CERT_STATE_BADGE = {
  error:    { variant: 'destructive', labelKey: 'card.error' },
  expired:  { variant: 'destructive', labelKey: 'app.expired' },
  critical: { variant: 'destructive', labelKey: 'card.critical' },
  high:     { variant: 'warning',     labelKey: 'card.high' },
  valid:    { variant: 'secondary',   labelKey: 'card.valid' },
}

// ── Son kullanılanlar (localStorage; bu tarayıcıya ait kolaylık) ──
// Kişisel veri taşıyabilir (yöneticinin kullanıcı aramaları, başka takımların alan adları): anahtar KULLANICIYA göre
// ayrılır ve çıkışta silinir — utils/personalStorage.js (2026-09-27 regresyon B9).
export const RECENT_KEY = 'sm.palette.recent'
export const RECENT_MAX = 8

export function readRecents() {
  try {
    const raw = localStorage.getItem(personalKey(RECENT_KEY))
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list.filter((x) => x && x.kind && x.id != null && x.label).slice(0, RECENT_MAX) : []
  } catch { return [] }
}

export function writeRecents(list) {
  try {
    if (!list || list.length === 0) localStorage.removeItem(personalKey(RECENT_KEY))
    else localStorage.setItem(personalKey(RECENT_KEY), JSON.stringify(list.slice(0, RECENT_MAX)))
  } catch { /* depolama yok/kapalı: son kullanılanlar bu oturumla sınırlı */ }
}

/** Öğeyi listenin başına alır (aynı tür+kimlik tekilleşir), tavanı uygular. Saklanan alanlar sınırlı. */
export function pushRecent(list, item) {
  const keep = { kind: item.kind, id: String(item.id), label: item.label, sub: item.sub ?? null, tab: item.tab ?? null, params: item.params ?? null }
  const rest = (list || []).filter((x) => !(x.kind === keep.kind && String(x.id) === keep.id))
  return [keep, ...rest].slice(0, RECENT_MAX)
}

/** cmdk `value` (tekil olmalı; süzmeyi biz yaptığımız için içerik anlam taşımaz). */
export function itemValue(kind, id) {
  return `${kind}:${id}`
}
