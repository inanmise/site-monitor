import {
  Ban, CircleAlert, CircleCheck, CircleHelp, CirclePlus, CircleX, Database, Hourglass, LockKeyhole, MessageSquareWarning,
  Network, SearchX, ShieldAlert, Snail, Timer, TimerOff, TriangleAlert, Unplug,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatPercent } from '../../i18n/dateLocale.js'
import { Badge } from '@/components/shadcn/badge'
import HintPopover from '../ui/HintPopover.jsx'
import { CARD_LAYER, MonitorCardMetrics } from '../monitoring/MonitorCard.jsx'
import { intervalText, ipFamily, signedPercent } from '../ping/pingCardModel.js'
import { msParts, msText } from '../keyword/keywordCardModel.js'
import { cn } from '@/lib/utils'
import { HTTP_DEFAULT_EXPECT, clip, connectAssessment, protocolOf, serviceName } from './portCardModel.js'

/** Kart içindeki dokun-gör tetiği: örtünün üstünde, dokunmatikte en az 40×40 px dokunma alanı (kısa "TCP" rozeti de). */
const CHIP_TRIGGER = cn(CARD_LAYER, 'max-w-full rounded-md pointer-coarse:min-h-10 pointer-coarse:min-w-10')
const CHIP = 'h-5 rounded-md px-1.5 text-[10.5px] font-semibold'

/**
 * Kontrol türü rozetinin tonu — eski `.port-ep-proto--*` paletinin shadcn Badge karşılığı (koyu tema dâhil). Renk tek
 * sinyal değil: rozet metni türü söyler, açıklaması dokun-gör balonunda.
 */
const PROTO_TONE = {
  TCP: 'border-blue-600/30 bg-blue-500/10 text-blue-800 dark:border-blue-400/30 dark:text-blue-300',
  TLS: 'border-emerald-600/30 bg-emerald-500/10 text-emerald-800 dark:border-emerald-400/30 dark:text-emerald-300',
  HTTP: 'border-violet-600/30 bg-violet-500/10 text-violet-800 dark:border-violet-400/30 dark:text-violet-300',
  BANNER: 'border-amber-600/30 bg-amber-500/10 text-amber-800 dark:border-amber-400/30 dark:text-amber-300',
  UDP: 'border-orange-600/30 bg-orange-500/10 text-orange-800 dark:border-orange-400/30 dark:text-orange-300',
}
const KNOWN_PROTOCOLS = new Set(Object.keys(PROTO_TONE))

/**
 * UÇ NOKTA satırı — kartın kimliği (host başlıkta): büyük eş aralıklı `:port`, kontrol türü rozeti (ne denetlendiği
 * dokun-gör balonunda), HTTP türünde denetlenen yol, bilinen portun YAYGIN hizmeti ("usually PostgreSQL" — kesinlik
 * iddia etmez), seçilmişse IP ailesi (otomatikte yazılmaz) ve kontrol sıklığı.
 * Test kancaları: `data-slot` = port-endpoint · port-number · port-protocol (`data-protocol`) · port-path · port-service;
 * çip başına `data-chip="family|interval"`.
 */
export function PortEndpointChips({ monitor: m, rowLabel, className }) {
  const t = useT()
  const proto = protocolOf(m)
  const svc = serviceName(m.port)
  const fam = ipFamily(m.ip_version)
  const every = intervalText(m.interval_seconds, t)
  const path = proto === 'HTTP' ? String(m.send_data || '').trim() : ''
  const known = KNOWN_PROTOCOLS.has(proto)
  return (
    <div data-slot="port-endpoint" className={cn('flex min-w-0 flex-wrap items-center gap-1.5', className)}>
      <Badge variant="outline" data-slot="port-number"
        className="h-6 rounded-md border-foreground/15 bg-muted/60 px-2 font-mono text-[13px] font-bold tabular-nums text-foreground dark:bg-muted/40">
        :{m.port}
      </Badge>
      <HintPopover content={known ? t(`port.type.${proto}`) : null} triggerClassName={CHIP_TRIGGER}
        aria-label={known ? t('a11y.rowAction', rowLabel, t(`port.type.${proto}`)) : undefined}>
        <Badge variant="outline" data-slot="port-protocol" data-protocol={proto}
          className={cn(CHIP, 'font-mono font-bold tracking-[.03em]', PROTO_TONE[proto] || 'text-muted-foreground')}>
          {proto}
        </Badge>
      </HintPopover>
      {path && (
        <span data-slot="port-path" title={path} className="max-w-full min-w-0 truncate font-mono text-[11.5px] text-muted-foreground">{path}</span>
      )}
      {svc && <span data-slot="port-service" className="text-[11.5px] text-muted-foreground">{t('port.card.usually', svc)}</span>}
      {fam && <Badge variant="outline" data-chip="family" className={cn(CHIP, 'font-mono')}>{fam === 'v6' ? 'IPv6' : 'IPv4'}</Badge>}
      {every && (
        <Badge variant="outline" data-chip="interval" className={cn(CHIP, 'font-medium text-muted-foreground')}>
          <Timer aria-hidden="true" />{every}
        </Badge>
      )}
    </div>
  )
}

/**
 * Kaynak rozeti — Port izlemeleri İKİ kaynaklıdır (DNS gibi): envanterden türetilen (host:port envanterden, takım
 * envanterden; "silmek" yalnız izlemeyi durdurur) ve Port sayfasından eklenen bağımsız izleme. Açıklama dokun-gör.
 */
export function PortSourceBadge({ source, rowLabel }) {
  const t = useT()
  const standalone = source === 'standalone'
  const Icon = standalone ? CirclePlus : Database
  const label = standalone ? t('port.card.standalone') : t('port.card.fromInventory')
  return (
    <HintPopover content={standalone ? t('port.card.standaloneHint') : t('port.card.fromInventoryHint')} triggerClassName={CHIP_TRIGGER}
      aria-label={t('a11y.rowAction', rowLabel, label)}>
      <Badge variant={standalone ? 'secondary' : 'outline'} data-slot="port-source" data-source={source}
        className={cn('rounded-sm px-1.5 text-[10.5px] font-semibold',
          standalone ? 'bg-primary/10 text-primary dark:bg-primary/20' : 'text-muted-foreground')}>
        <Icon aria-hidden="true" />{label}
      </Badge>
    </HintPopover>
  )
}

/** Sonuç tonu → panel zemini (Anahtar Kelime / Sayfa Bütünlüğü kartlarıyla aynı palet). */
const PANEL = {
  ok: 'border-success/25 bg-success/5 dark:bg-success/10',
  bad: 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10',
  neutral: 'border-dashed bg-muted/40 dark:bg-muted/20',
}
const TITLE_TONE = { ok: 'text-success', bad: 'text-destructive', neutral: 'text-muted-foreground' }
const RESULT_ICON = {
  open: CircleCheck, refused: Ban, filtered: TimerOff, dns: SearchX, unreachable: Unplug, blocked: ShieldAlert,
  proxy: Network, tls: LockKeyhole, http: TriangleAlert, banner: MessageSquareWarning, error: CircleAlert,
  closed: CircleX, pending: Hourglass, unknown: CircleHelp,
}

/** Başlık (durum) + tek satırlık neden/kanıt metni — `result` = portCardModel.portResult. */
function resultText(m, result, t) {
  const { kind, proto } = result
  const port = m.port
  const timeout = msText(m.timeout_ms)
  if (kind === 'open') {
    const title = t(`port.card.state.ok${KNOWN_PROTOCOLS.has(proto) ? proto : 'TCP'}`)
    if (proto === 'HTTP') return { title, why: t('port.card.why.okHTTP', String(m.send_data || '').trim() || '/', String(m.expect || '').trim() || HTTP_DEFAULT_EXPECT) }
    if (proto === 'BANNER') return { title, why: String(m.expect || '').trim() ? t('port.card.why.okBANNER', clip(m.expect)) : t('port.card.why.okBANNERany') }
    return { title, why: t(`port.card.why.ok${KNOWN_PROTOCOLS.has(proto) ? proto : 'TCP'}`) }
  }
  switch (kind) {
    case 'refused': return { title: t('port.card.state.refused'), why: result.udp ? t('port.card.why.refusedUdp') : t('port.card.why.refused', port) }
    case 'filtered': return {
      title: t('port.card.state.filtered'),
      why: result.udp ? t('port.card.why.filteredUdp', timeout || '—')
        : timeout ? t('port.card.why.filtered', timeout) : t('port.card.why.filteredBare'),
    }
    case 'dns': return {
      title: t('port.card.state.dns'),
      why: result.family ? t('port.card.why.dnsFamily', result.family === 'v6' ? '6' : '4') : t('port.card.why.dns', m.host),
    }
    case 'unreachable': return { title: t('port.card.state.unreachable'), why: t('port.card.why.unreachable') }
    case 'blocked': return { title: t('port.card.state.blocked'), why: t('port.card.why.blocked') }
    case 'proxy': return { title: t('port.card.state.proxy'), why: t('port.card.why.proxy', port) }
    case 'tls': return { title: t('port.card.state.tls'), why: t('port.card.why.tls', result.detail) }
    case 'http': return { title: t('port.card.state.http'), why: t('port.card.why.http', result.code, result.expected || HTTP_DEFAULT_EXPECT) }
    case 'banner': return {
      title: t('port.card.state.banner'),
      why: result.expected == null ? t('port.card.why.bannerNone')
        : result.got ? t('port.card.why.banner', clip(result.expected, 24), clip(result.got, 32)) : t('port.card.why.bannerEmpty', clip(result.expected, 24)),
    }
    case 'error': return { title: t('port.card.state.closed'), why: t('port.card.why.error', result.detail) }
    case 'closed': return { title: t('port.card.state.closed'), why: t('port.card.why.closed') }
    case 'unknown': return { title: t('port.card.state.unknown'), why: null }
    default: return { title: t('port.card.state.pending'), why: t('port.card.why.pending') }
  }
}

/**
 * SONUÇ PANELİ — kartın kalbi: son kontrolde ne oldu? Başlık = durum (Bağlantı kabul ediyor / Bağlantı reddedildi /
 * Filtreli — zaman aşımı / DNS hatası …), altında tek satırlık neden ya da kanıt (dinleyen yok / N sn içinde yanıt yok /
 * host çözümlenemedi / beklenen kod …). Zemin sonucun tonunda; hiç kontrol yoksa kesik kenarlı nötr. En fazla iki satır,
 * yalnız-hover bilgi yok (dokunmatikte de okunur); ham sunucu metninin tamamı detayın Kontrol Geçmişi'nde.
 * Test kancaları: `data-slot="port-result"` + `data-state` (portResult.kind) + `data-tone`; `port-result-title`,
 * `port-result-why`.
 */
export function PortResultPanel({ monitor: m, result }) {
  const t = useT()
  const { title, why } = resultText(m, result, t)
  const Icon = RESULT_ICON[result.kind] || CircleAlert
  return (
    <div data-slot="port-result" data-state={result.kind} data-tone={result.tone} role="group" aria-label={t('port.card.result')}
      className={cn('mb-2.5 min-w-0 rounded-lg border px-3 py-2', PANEL[result.tone])}>
      <p data-slot="port-result-title" className={cn('flex min-w-0 items-center gap-1.5 text-sm leading-tight font-bold', TITLE_TONE[result.tone])}>
        <Icon aria-hidden="true" className="size-4 shrink-0" />
        <span className="min-w-0">{title}</span>
      </p>
      {why && (
        <p data-slot="port-result-why"
          className={cn('mt-0.5 line-clamp-2 min-w-0 pl-[22px] text-xs leading-snug [overflow-wrap:anywhere]',
            result.tone === 'bad' ? 'font-medium text-foreground/80' : 'text-muted-foreground')}>
          {why}
        </p>
      )}
    </div>
  )
}

/**
 * KOMPAKT uç nokta satırı (yoğunluk = Kompakt) — kartın TEK ikincil satırı: `:port` · kontrol türü · (varsa) ad. Sarmaz;
 * ad kırpılır. Yol / "usually …" / IP ailesi / sıklık çipleri Zengin görünümde ({@link PortEndpointChips}).
 * Satır örtünün altında: dokunmak detayı açar. Test kancaları Zengin'le ortak: port-endpoint (`data-density="compact"`) ·
 * port-number · port-protocol · port-name.
 */
export function PortCompactEndpoint({ monitor: m, name, className }) {
  const proto = protocolOf(m)
  return (
    <div data-slot="port-endpoint" data-density="compact" className={cn('flex min-w-0 items-center gap-1.5', className)}>
      <Badge variant="outline" data-slot="port-number"
        className="h-5 rounded-md border-foreground/15 bg-muted/60 px-1.5 font-mono text-[12px] font-bold tabular-nums text-foreground dark:bg-muted/40">
        :{m.port}
      </Badge>
      <Badge variant="outline" data-slot="port-protocol" data-protocol={proto}
        className={cn(CHIP, 'font-mono font-bold tracking-[.03em]', PROTO_TONE[proto] || 'text-muted-foreground')}>
        {proto}
      </Badge>
      {name && <span data-slot="port-name" className="min-w-0 truncate text-xs text-muted-foreground">{name}</span>}
    </div>
  )
}

/**
 * KOMPAKT SONUÇ satırı — kartın ana ölçüsü: bağlantı hükmü (kabul ediyor / reddedildi / filtreli / DNS …) + süre (sağda;
 * izlemenin yavaşlık eşiği aşıldıysa amber + salyangoz). Sonuç kötüyse altında TEK satır neden (tam metni dokun-gör
 * balonunda — telefonda da açılır). Paneldeki ikinci satır, ölçü kutuları ve trend Zengin görünümde.
 * Test kancaları: `data-slot="port-compact"` + `data-state` + `data-tone`; port-compact-verdict · port-compact-time
 * (`data-tone="warn"` yavaşta) · port-compact-reason.
 */
export function PortCompactSummary({ monitor: m, result, baseline }) {
  const t = useT()
  const { title, why } = resultText(m, result, t)
  const Icon = RESULT_ICON[result.kind] || CircleAlert
  const proto = KNOWN_PROTOCOLS.has(result.proto) ? result.proto : 'TCP'
  const time = msText(m.response_ms)
  const slow = time != null && connectAssessment(m, result, baseline).tone === 'warn'
  return (
    <div data-slot="port-compact" data-state={result.kind} data-tone={result.tone}
      className={cn('mb-2 min-w-0 rounded-md border px-2.5 py-1.5', PANEL[result.tone])}>
      <p className="flex min-w-0 items-center gap-1.5 text-[13px] leading-5 font-semibold">
        <Icon aria-hidden="true" className={cn('size-3.5 shrink-0', TITLE_TONE[result.tone])} />
        <span data-slot="port-compact-verdict" className={cn('min-w-0 truncate', TITLE_TONE[result.tone])}>{title}</span>
        {time && (
          <span data-slot="port-compact-time" data-tone={slow ? 'warn' : undefined}
            className={cn('ml-auto flex shrink-0 items-center gap-1 text-xs tabular-nums', slow ? 'text-amber-700 dark:text-amber-400' : 'text-foreground')}>
            {slow && <Snail aria-hidden="true" className="size-3.5" />}
            <span className="sr-only">{t(`port.card.connect.${proto}`)}: </span>{time}
            {slow && <span className="sr-only"> — {t('port.card.slow')}</span>}
          </span>
        )}
      </p>
      {result.tone === 'bad' && why && (
        <HintPopover content={why} className="max-h-64 overflow-y-auto [overflow-wrap:anywhere]"
          triggerClassName={cn(CARD_LAYER, 'mt-0.5 flex w-full min-w-0 justify-start rounded-sm pl-5 text-left text-xs font-medium text-foreground/80 hover:text-foreground/80 pointer-coarse:min-h-10')}>
          <span data-slot="port-compact-reason" className="min-w-0 truncate">{why}</span>
        </HintPopover>
      )}
    </div>
  )
}

const VALUE_TONE = { ok: 'text-success', warn: 'text-amber-700 dark:text-amber-400', bad: 'text-destructive', neutral: 'text-foreground' }
const TILE_TONE = {
  ok: '',
  warn: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  bad: 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10',
  neutral: '',
}

/** Tek ölçü kutusu (Ping / Anahtar Kelime kartlarıyla aynı dil): etiket · büyük değer (+ birim) [+ hüküm rozeti] · alt satır. */
function MetricTile({ metric, tone, label, parts, verdict, sub, className }) {
  return (
    <div data-slot="port-metric" data-metric={metric} data-tone={tone}
      className={cn('flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/40 px-2.5 py-1.5 dark:bg-muted/20', TILE_TONE[tone], className)}>
      <span className="truncate text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
      <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
        <span data-slot="port-metric-value" className={cn('text-lg leading-tight font-bold tabular-nums', VALUE_TONE[tone])}>
          {parts ? <>{parts.num}<span className="ml-0.5 text-xs font-semibold text-muted-foreground">{parts.unit}</span></> : '—'}
        </span>
        {verdict && (
          <Badge variant="outline" data-slot="port-metric-verdict"
            className="h-4 rounded px-1 text-[9.5px] font-bold tracking-[.04em] uppercase border-amber-500/40 bg-amber-500/15 text-amber-800 dark:text-amber-300">
            {verdict}
          </Badge>
        )}
      </span>
      {sub && (
        <span data-slot="port-metric-sub"
          className={cn('truncate text-[10.5px] leading-snug font-medium', tone === 'neutral' || tone === 'ok' ? 'text-muted-foreground' : VALUE_TONE[tone])}>
          {sub}
        </span>
      )}
    </div>
  )
}

/** Saatlik ortalamaların aralığı — iki uç da 1 sn altındaysa "11–18 ms", değilse "0.9 s–2.4 s". */
function rangeText(min, max) {
  return min < 1000 && max < 1000 ? `${min}–${max} ms` : `${msText(min)}–${msText(max)}`
}

/**
 * Ölçü kutuları — telefonda da İKİ sütun. (1) Bağlantı süresi (türe göre adı: bağlantı / el sıkışma / yanıt süresi):
 * yavaşlık eşiği açıksa üstü amber "Yavaş" + sınır, altı yeşil; kapalıysa nötr + 24 sa ortalamasına göre sapma;
 * zaman aşımında kırmızı "N sn içinde yanıt yok", bağlantı kurulamadıysa kırmızı "Bağlantı yok". (2) 24 sa ortalaması
 * (kart trendinin saatlik ortalamalarından; en az 3 ölçüm yoksa çizilmez ve ilk kutu tam genişlik olur).
 * Hiç kontrol yoksa kutular çizilmez (panel "İlk kontrol bekleniyor" diyor).
 */
export function PortMetricTiles({ monitor: m, result, baseline }) {
  const t = useT()
  if (result.kind === 'pending' || (result.kind === 'unknown' && m.response_ms == null)) return null
  const a = connectAssessment(m, result, baseline)
  const proto = KNOWN_PROTOCOLS.has(result.proto) ? result.proto : 'TCP'
  const sub = a.timedOut ? (msText(m.timeout_ms) ? t('port.card.noReplyWithin', msText(m.timeout_ms)) : t('port.card.timedOut'))
    : a.noConnection ? t('port.card.noConnection')
      : a.limit != null ? (a.tone === 'warn' ? t('port.card.limit', msText(a.limit)) : t('port.card.withinLimit', msText(a.limit)))
        : a.delta != null ? t('port.card.vs24h', signedPercent(a.delta, formatPercent)) : null
  return (
    <MonitorCardMetrics className="mb-2.5 grid grid-cols-2 gap-2">
      <MetricTile metric="connect" tone={a.tone} label={t(`port.card.connect.${proto}`)} parts={msParts(m.response_ms)}
        verdict={a.tone === 'warn' ? t('port.card.slow') : null} sub={sub} className={cn(!baseline && 'col-span-2')} />
      {baseline && (
        <MetricTile metric="avg24h" tone="neutral" label={t('port.card.avg24h')} parts={msParts(baseline.avg)}
          sub={t('port.card.hourly', rangeText(baseline.min, baseline.max))} />
      )}
    </MonitorCardMetrics>
  )
}
