import { Activity, Ban, CircleAlert, SearchX, TimerOff, Unplug } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatPercent } from '../../i18n/dateLocale.js'
import { formatDateSec } from '../../api/client'
import { relativeTime } from '../admin/audit/auditFormat.js'
import { tagsOf } from '../../utils/monitorFilters.js'
import { toUtc } from '../../utils/localDay.js'
import { Badge } from '@/components/shadcn/badge'
import { MonitorCardMetrics, CARD_LAYER } from '../monitoring/MonitorCard.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { cn } from '@/lib/utils'
import { lossTone, rttAssessment, signedPercent } from './pingCardModel.js'

/**
 * Ton → değer rengi / kutu zemini. Sorun tonları renk tek başına bilgi taşımaz: yavaşta "Yavaş" rozeti, kayıpta ve yanıt
 * yokken alt satır METNİ var; olağan (yeşil) RTT'de hüküm ekran okuyucuya ayrıca söylenir.
 */
const VALUE_TONE = {
  ok: 'text-success',
  warn: 'text-amber-700 dark:text-amber-400',
  bad: 'text-destructive',
  neutral: 'text-foreground',
}
const TILE_TONE = {
  ok: '',
  warn: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  bad: 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10',
  neutral: '',
}

/**
 * Tek ölçü kutusu: küçük büyük harfli etiket · büyük değer (+ birim) [+ hüküm rozeti] · alt satır.
 * `srVerdict`: yalnız ekran okuyucuya söylenen hüküm (ör. "Normal" — yeşil ton görsel olarak zaten söylüyor).
 */
function MetricTile({ metric, tone, label, value, unit, verdict, srVerdict, sub }) {
  return (
    <div data-slot="ping-metric" data-metric={metric} data-tone={tone}
      className={cn('flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/40 px-2.5 py-1.5 dark:bg-muted/20', TILE_TONE[tone])}>
      <span className="truncate text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
      <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
        <span data-slot="ping-metric-value" className={cn('text-lg leading-tight font-bold tabular-nums', VALUE_TONE[tone])}>
          {value}{unit && value !== '—' && <span className="ml-0.5 text-xs font-semibold text-muted-foreground">{unit}</span>}
        </span>
        {verdict && (
          <Badge variant="outline" data-slot="ping-metric-verdict"
            className="h-4 rounded px-1 text-[9.5px] font-bold tracking-[.04em] uppercase border-amber-500/40 bg-amber-500/15 text-amber-800 dark:text-amber-300">
            {verdict}
          </Badge>
        )}
      </span>
      {(sub || srVerdict) && (
        <span data-slot="ping-metric-sub" className={cn('text-[10.5px] leading-snug font-medium', tone === 'neutral' || tone === 'ok' ? 'text-muted-foreground' : VALUE_TONE[tone])}>
          {srVerdict && <span className="sr-only">{srVerdict}{sub ? ' · ' : ''}</span>}{sub}
        </span>
      )}
    </div>
  )
}

/**
 * Anahtar ölçüler: RTT (son kontrolün ortalaması) ve paket kaybı — telefonda da İKİ sütun, büyük ve okunur.
 * RTT tonu izlemenin yavaşlık eşiğinden (bkz. pingCardModel.rttAssessment): yavaşsa görünür "Yavaş" rozeti, alt satırda
 * 24 sa ortalamasına göre sapma. Kayıp tonu %0 / kısmi / %100, alt satırda metni. Hiç ölçüm yoksa (bekleyen, N/A) kutular
 * çizilmez — iki boş "—" kutusu yer kaplıyordu; durum rozeti ve neden satırı zaten anlatıyor.
 */
export function PingMetricTiles({ monitor, baseline }) {
  const t = useT()
  const m = monitor || {}
  if (m.rtt_ms == null && m.packet_loss == null && m.status !== 'down') return null
  const rtt = rttAssessment(m, baseline)
  const vs = rtt.delta != null ? t('ping.card.vs24h', signedPercent(rtt.delta, formatPercent)) : null
  const packets = Number(m.packet_count) > 0 ? Number(m.packet_count) : null
  const rttSub = rtt.tone === 'bad' ? t('ping.card.noReply')
    : m.rtt_ms != null ? (vs || (packets ? t('ping.card.avgOfPackets', packets) : null)) : null
  const loss = lossTone(m.packet_loss)
  const lossSub = { ok: t('ping.card.lossNone'), warn: t('ping.card.lossPartial'), bad: t('ping.card.lossAll'), neutral: null }[loss]
  return (
    <MonitorCardMetrics className="mb-2.5 grid grid-cols-2 gap-2">
      <MetricTile metric="rtt" tone={rtt.tone} label={t('ping.rtt')} value={m.rtt_ms != null ? String(m.rtt_ms) : '—'} unit="ms"
        verdict={rtt.tone === 'warn' ? t('ping.card.slow') : null} srVerdict={rtt.tone === 'ok' ? t('ping.card.normal') : null} sub={rttSub} />
      <MetricTile metric="loss" tone={loss} label={t('ping.loss')} value={m.packet_loss != null ? formatPercent(m.packet_loss) : '—'} sub={lossSub} />
    </MonitorCardMetrics>
  )
}

/**
 * 24 saatlik gecikme özeti — kart trendinin saatlik ortalamalarından: ortalama + saatlik aralık. Sunucu kontrol
 * başına min/max/sapma saklamadığı için "aralık" paket düzeyi DEĞİL, saatlik ortalamaların aralığıdır (metin böyle der).
 */
export function PingLatencyRange({ baseline }) {
  const t = useT()
  if (!baseline) return null
  return (
    <p data-slot="ping-range" className="mb-1 flex min-w-0 flex-wrap items-center gap-x-1.5 text-[11px] text-muted-foreground tabular-nums">
      <Activity aria-hidden="true" className="size-3 shrink-0" />
      <span>{t('ping.card.avg24h')} <span className="font-semibold text-foreground">{baseline.avg} ms</span></span>{' '}
      <span aria-hidden="true">·</span>{' '}
      <span>{t('ping.card.hourlyRange', baseline.min, baseline.max)}</span>
    </p>
  )
}

const REASON_ICON = { na: Ban, dns: SearchX, unreachable: Unplug, timeout: TimerOff, error: CircleAlert, down: CircleAlert }
const REASON_KEY = {
  na: 'ping.card.reasonNa', dns: 'ping.card.reasonDns', unreachable: 'ping.card.reasonUnreachable',
  timeout: 'ping.card.reasonTimeout', down: 'ping.card.reasonDown',
}

/** Neden metni + simgesi (Zengin'deki iki satırlık kutu ve Kompakt'taki tek satır AYNI metni söyler). */
export function pingReasonText(reason, t) {
  if (!reason) return null
  return reason.kind === 'error' ? t('ping.card.reasonError', reason.detail) : t(REASON_KEY[reason.kind])
}
export const pingReasonIcon = (kind) => REASON_ICON[kind] || CircleAlert

/**
 * Kapalı ya da ölçülemeyen izlemenin nedeni — tek bakışta (eskiden yalnız detaydaki geçmiş satırındaydı). Sınıflanamayan
 * sunucu metni olduğu gibi gösterilir; en fazla iki satır, yalnız-hover ipucu yok (dokunmatikte de okunur).
 */
export function PingFailureReason({ reason }) {
  const t = useT()
  if (!reason) return null
  const Icon = REASON_ICON[reason.kind] || CircleAlert
  const text = pingReasonText(reason, t)
  const na = reason.kind === 'na'
  return (
    <p data-slot="ping-reason" data-reason={reason.kind} role="note"
      className={cn('mb-2.5 flex min-w-0 items-start gap-1.5 rounded-md px-2 py-1.5 text-xs leading-snug font-medium',
        na ? 'bg-amber-500/10 text-amber-800 dark:text-amber-300' : 'bg-destructive/10 text-destructive dark:bg-destructive/15')}>
      <Icon aria-hidden="true" className="mt-px size-3.5 shrink-0" />
      <span className="line-clamp-2 min-w-0 break-words">{text}</span>
    </p>
  )
}

/** Etiketler — ilk üçü rozet, fazlası "+N" (tam liste ekran okuyucuda). */
export function PingTags({ monitor, max = 3 }) {
  const t = useT()
  const tags = tagsOf(monitor)
  if (!tags.length) return null
  const shown = tags.slice(0, max)
  const rest = tags.slice(max)
  return (
    <span data-slot="ping-tags" className="flex min-w-0 flex-wrap items-center gap-1">
      <span className="sr-only">{t('ping.card.tags')}</span>
      {shown.map((tag) => (
        <Badge key={tag} variant="secondary" className="h-5 max-w-40 rounded-md px-1.5 text-[10.5px] font-medium">
          <span className="truncate">{tag}</span>
        </Badge>
      ))}
      {rest.length > 0 && (
        <Badge variant="outline" className="h-5 rounded-md px-1.5 text-[10.5px] font-medium text-muted-foreground">
          <span aria-hidden="true">+{rest.length}</span><span className="sr-only">{rest.join(', ')}</span>
        </Badge>
      )}
    </span>
  )
}

/**
 * Son kontrol — göreli ("2 dk önce"), tam zaman ipucunda ve ekran okuyucuda. Kontrol hiç yapılmadıysa bunu açıkça söyler
 * (eskiden alt çubuk boş kalıyordu). İpucu fareyle açılsın diye örtünün üstünde (CARD_LAYER).
 */
export function PingCheckedAt({ at, now = Date.now() }) {
  const t = useT()
  if (!at) return <span data-slot="ping-checked-at" data-never="true">{t('ping.card.never')}</span>
  const exact = formatDateSec(at)
  return (
    <SimpleTooltip content={exact}>
      <time data-slot="ping-checked-at" dateTime={toUtc(at)} className={cn(CARD_LAYER, 'cursor-default')}>
        {relativeTime(at, t, now) || exact}
        <span className="sr-only"> ({exact})</span>
      </time>
    </SimpleTooltip>
  )
}

/* ── KOMPAKT görünüm (2026-09-27, kart yoğunluğu) ─────────────────────────────────────────────────────────────────────
 * Ping, HTTP ve Anahtar Kelime kartlarının ORTAK kompakt parçaları (HTTP / Anahtar Kelime kartları bu modülden zaten
 * PingCheckedAt / PingTags alıyor). Kompakt = 50+ kartlık ızgarada göz gezdirilecek temel bilgi: durum satırı ·
 * başlık + TEK ikincil satır (ad + takım) · TEK ana ölçü · düşükse TEK satır neden · alt çubuk. Trend, SLA, ayar
 * çipleri, grup/etiket/vekil ve ikincil ölçüler yalnız Zengin'de (MonitorCardRich). */

/**
 * Kompakt kartın İKİNCİL satırı (en fazla bir satır): başlıktan farklıysa izlemenin adı (kırpılır, tam metin
 * `title`'da ve detay penceresinde) + YALNIZ takım rozeti (grup, etiket, vekil Zengin'de). Takım rozeti tıklanınca üye
 * penceresini açar → örtünün üstünde (CARD_LAYER). `nameSlot`: Zengin'deki ad satırıyla AYNI test kancası (`ping-name` /
 * `http-name` / `keyword-name`).
 *
 * <p>Dokunma hedefi (Playwright 390, isabet testi): rozetin 40 px'lik alanı TeamBadge'in kendi ::after katmanı (±10 px).
 * `leading-5` şart — rozet satır yüksekliğini miras alır, 20 px'in altına inerse ±10 px 40'a yetmez. Dokunmatikte satır
 * 44 px (`pointer-coarse:min-h-11`, 2 px pay): katman satırın İÇİNDE kalır; yoksa altındaki ölçü/neden satırı (duraklatılmış kartta
 * ayrı opaklık katmanı olan kart içeriği) rozetin alt yarısını örtüyordu. Rozet ad olmasa da SAĞA yaslı: sola düşünce
 * katman hemen üstteki başlık düğmesinin dokunma alanını kesiyordu.
 */
export function CardCompactSub({ name, nameSlot, monitor }) {
  const team = monitor?.team_name
  if (!name && !team) return null
  return (
    <div data-slot="card-compact-sub" className="mt-1 flex min-w-0 items-center gap-2 text-xs leading-5 text-muted-foreground pointer-coarse:min-h-11">
      {name && <span data-slot={nameSlot} className="min-w-0 flex-1 truncate" title={name}>{name}</span>}
      {team && (
        <span data-slot="meta-team" className={cn(CARD_LAYER, 'ml-auto flex min-w-0 shrink items-center text-[11px]', name && 'max-w-[55%]')}>
          <TeamBadge teamId={monitor.team_id} teamName={team} />
        </span>
      )}
    </div>
  )
}

/** Kompakt ana ölçü satırı — tek satır, dar kartta sarar. `slot`: türün test kancası (`ping-compact` …). */
export function CardCompactMetric({ slot, className, children, ...rest }) {
  return (
    <div data-slot={slot} className={cn('mt-2 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1', className)} {...rest}>
      {children}
    </div>
  )
}

/**
 * Kompakt değer: tonlu değer (+ birim) · küçük büyük harfli etiket, yan yana. Ton Zengin kutularıyla AYNI renk dili;
 * renk tek başına bilgi taşımaz (yanında "Yavaş" rozeti ya da neden satırı durur). Değer yoksa '—' ve birim yazılmaz.
 * Test kancası: `data-slot="compact-value"` + `data-metric` + `data-tone`.
 */
export function CompactValue({ metric, tone = 'neutral', value, unit, label }) {
  return (
    <span data-slot="compact-value" data-metric={metric} data-tone={tone} className="inline-flex min-w-0 items-baseline gap-1">
      <span className={cn('text-base leading-tight font-bold tabular-nums', VALUE_TONE[tone] || VALUE_TONE.neutral)}>
        {value}{unit && value !== '—' && <span className="ml-0.5 text-xs font-semibold text-muted-foreground">{unit}</span>}
      </span>
      {label && <span className="truncate text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>}
    </span>
  )
}

/** Kompakt hüküm rozeti ("Yavaş") — Zengin kutudaki rozetin aynısı. */
export function CompactVerdict({ children }) {
  return (
    <Badge variant="outline" data-slot="compact-verdict"
      className="h-4 rounded px-1 text-[9.5px] font-bold tracking-[.04em] uppercase border-amber-500/40 bg-amber-500/15 text-amber-800 dark:text-amber-300">
      {children}
    </Badge>
  )
}

const COMPACT_REASON_TONE = {
  bad: 'bg-destructive/10 text-destructive hover:bg-destructive/15 hover:text-destructive dark:bg-destructive/15 dark:hover:bg-destructive/20',
  warn: 'bg-amber-500/10 text-amber-800 hover:bg-amber-500/15 hover:text-amber-800 dark:text-amber-300 dark:hover:bg-amber-500/20 dark:hover:text-amber-300',
}

/**
 * Kompakt NEDEN satırı — düşük/ölçülemeyen kartta TEK satır, kırpılır; tam metin dokun-gör balonunda (HintPopover:
 * fare, klavye VE dokunmatikte açılır — yalnız-hover ipucu yok). Tetik örtünün üstünde: tıklamak detayı değil balonu
 * açar. Erişilebilir ad satırı taşır ("<hedef> — <neden>"): 50 kartta 50 özdeş "Zaman aşımı" duyulmaz. Dokunmatikte
 * 40 px yükseklik. `slot` + `data-reason` Zengin'deki neden kutusuyla AYNI kanca; kompakt olan `data-compact` taşır.
 */
export function CardCompactReason({ slot, kind, icon: Icon = CircleAlert, text, rowLabel, tone = 'bad' }) {
  const t = useT()
  if (!text) return null
  return (
    <div data-slot={slot} data-reason={kind} data-compact="true" role="note" className="mt-2 min-w-0">
      <HintPopover content={text} aria-label={rowLabel ? t('a11y.rowAction', rowLabel, text) : text}
        triggerClassName={cn(CARD_LAYER, 'flex w-full min-w-0 justify-start gap-1.5 rounded-md px-2 py-1 text-left text-xs leading-snug font-medium pointer-coarse:min-h-10',
          COMPACT_REASON_TONE[tone] || COMPACT_REASON_TONE.bad)}>
        <Icon aria-hidden="true" className="size-3.5 shrink-0" />
        <span className="min-w-0 truncate">{text}</span>
      </HintPopover>
    </div>
  )
}

/**
 * Ping KOMPAKT ana ölçüsü — son kontrolün RTT'si; ton Zengin kutusuyla aynı kuraldan (yavaşlık eşiği).
 * Yavaşsa görünür "Yavaş" rozeti. Kısmi paket kaybı (durum "çalışıyor" görünse de sorunlu hat) amber "%25 kayıp" eki
 * olarak aynı satırda — %0'da yazılmaz. RTT yoksa (yanıt yok / N/A / bekleyen) satır HİÇ çizilmez: kırmızı "— RTT"
 * bilgi taşımıyordu; kapalı kartta neden satırı, bekleyende alt çubuk ("Henüz kontrol edilmedi") söyler.
 */
export function PingCompactMetric({ monitor, baseline }) {
  const t = useT()
  const m = monitor || {}
  if (m.rtt_ms == null) return null
  const rtt = rttAssessment(m, baseline)
  const partialLoss = lossTone(m.packet_loss) === 'warn'
  return (
    <CardCompactMetric slot="ping-compact">
      <span className="inline-flex min-w-0 items-center gap-1.5">
        <CompactValue metric="rtt" tone={rtt.tone} value={String(m.rtt_ms)} unit="ms" label={t('ping.rtt')} />
        {rtt.tone === 'warn' && <CompactVerdict>{t('ping.card.slow')}</CompactVerdict>}
      </span>
      {partialLoss && (
        <span data-slot="compact-value" data-metric="loss" data-tone="warn"
          className="text-xs font-semibold text-amber-700 tabular-nums dark:text-amber-400">
          {t('ping.card.lossShort', formatPercent(m.packet_loss))}
        </span>
      )}
    </CardCompactMetric>
  )
}
