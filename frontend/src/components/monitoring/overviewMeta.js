// İzleme Panosu — tür ve durum tanımları (2026-10-01 yeniden tasarım: sayfa parçalara bölündü; tanımlar TEK yerde).
// Sayfa (`MonitoringOverviewPage.jsx`) bunları aynı adlarla yeniden dışa aktarır — testler ve diğer dosyalar oradan alır.
import {
  Globe, Radio, EthernetPort, Waypoints, CalendarClock, TextSearch, FileCheck, Gauge, Workflow,
  PauseCircle, CircleAlert, Clock, HelpCircle, CheckCircle2, Trash2,
} from 'lucide-react'

/** Tür → ikon, menü etiketi, izleme sayfası sekmesi. Sıra = Monitoring menüsü (MonitorTypeCatalog). */
export const TYPE_META = {
  http:      { Icon: Globe,         labelKey: 'nav.http',      tab: 'http' },
  ping:      { Icon: Radio,         labelKey: 'nav.ping',      tab: 'ping' },
  port:      { Icon: EthernetPort,  labelKey: 'nav.port',      tab: 'port' },
  dns:       { Icon: Waypoints,     labelKey: 'nav.dns',       tab: 'dns' },
  domain:    { Icon: CalendarClock, labelKey: 'nav.domainmon', tab: 'domain' },
  keyword:   { Icon: TextSearch,    labelKey: 'nav.keyword',   tab: 'keyword' },
  page:      { Icon: FileCheck,     labelKey: 'nav.page',      tab: 'page' },
  pagespeed: { Icon: Gauge,         labelKey: 'nav.pagespeed', tab: 'pagespeed' },
  scripted:  { Icon: Workflow,      labelKey: 'nav.scripted',  tab: 'scripted' },
}
export const TYPE_ORDER = Object.keys(TYPE_META)

/**
 * Durum → ikon, rozet tonu, dağılım çubuğu rengi (`bar`, `dot`) ve sıralama ağırlığı (düşük en üstte).
 * Ton yalnız rozet + çubuk + nokta; kartlarda SOL ŞERİT YOK (SHADCN.md §1).
 */
export const STATUS_META = {
  down:    { Icon: CircleAlert,  rank: 0, badge: 'border-transparent bg-destructive text-white',                          bar: 'bg-destructive',            labelKey: 'mo.status.down' },
  stale:   { Icon: Clock,        rank: 1, badge: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300', bar: 'bg-amber-500',              labelKey: 'mo.status.stale' },
  unknown: { Icon: HelpCircle,   rank: 2, badge: 'border-border bg-muted text-muted-foreground',                          bar: 'bg-slate-400 dark:bg-slate-500', labelKey: 'mo.status.unknown' },
  up:      { Icon: CheckCircle2, rank: 3, badge: 'border-success/40 bg-success/10 text-success',                           bar: 'bg-success',                labelKey: 'mo.status.up' },
  paused:  { Icon: PauseCircle,  rank: 4, badge: 'border-border bg-muted text-muted-foreground',                          bar: 'bg-slate-300 dark:bg-slate-600', labelKey: 'mo.status.paused' },
  deleted: { Icon: Trash2,       rank: 5, badge: 'border-border bg-muted text-muted-foreground line-through',             bar: 'bg-muted',                  labelKey: 'mo.status.deleted' },
}
export const STATUS_ORDER = Object.keys(STATUS_META)

/** Pencere seçici değerleri (saat). */
export const WINDOWS = [24, 168]

/** Tür kartındaki başarı oranı yüzdesi (0–100) — koşum yoksa null. */
export function successPct(type) {
  const v = type?.success_rate_window
  return v == null ? null : Math.max(0, Math.min(100, Number(v)))
}
