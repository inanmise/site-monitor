import {
  BellRing, CircleAlert, CornerDownRight, LockKeyhole, LockKeyholeOpen, SearchX, ServerCrash, ShieldAlert,
  ShieldCheck, Target, Timer, TimerOff, TriangleAlert, Unplug,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatPercent } from '../../i18n/dateLocale.js'
import { Badge } from '@/components/shadcn/badge'
import HintPopover from '../ui/HintPopover.jsx'
import { CARD_LAYER, MonitorCardMetrics, MonitorPendingText } from '../monitoring/MonitorCard.jsx'
import { intervalText, latencyBaseline, signedPercent } from '../ping/pingCardModel.js'
import { CardCompactMetric, CompactValue, CompactVerdict } from '../ping/PingCardParts.jsx'
import { urlParts } from '../pagespeed/pageSpeedCardModel.js'
import { msText } from '../keyword/keywordCardModel.js'
import { cn } from '@/lib/utils'
import { daysText, methodOf, requestChips, responseView } from './httpCardModel.js'

/** Kart içindeki dokun-gör tetiği: örtünün üstünde, dokunmatikte 40 px yüksek dokunma alanı (Anahtar Kelime kartıyla aynı). */
const CHIP_TRIGGER = cn(CARD_LAYER, 'max-w-full rounded-md pointer-coarse:min-h-10')
const CHIP = 'h-5 rounded-md px-1.5 text-[10.5px] font-semibold'
const CHIP_TONE = {
  neutral: 'bg-card text-muted-foreground',
  primary: 'border-primary/30 bg-primary/5 text-primary dark:bg-primary/15',
  warn: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300',
}

/**
 * Başlık URL'i: `https://` gizli, düz `http://` AMBER (şifresiz trafik bir bulgudur), host vurgulu, yol + sorgu soluk.
 * Parçalama Sayfa Hızı modelinden (urlParts) — ayrıştırılamayan metin olduğu gibi host'a düşer.
 */
export function HttpUrlText({ url }) {
  const { scheme, host, path } = urlParts(url)
  const plain = scheme === 'http://'
  return (
    <>
      {scheme && (
        <span data-slot="http-scheme" data-plain={plain ? 'true' : undefined}
          className={cn('font-normal', plain ? 'font-semibold text-amber-700 dark:text-amber-400' : 'text-muted-foreground')}>{scheme}</span>
      )}
      <span className="font-semibold text-foreground">{host}</span>
      {path && <span className="font-normal text-muted-foreground">{path}</span>}
    </>
  )
}

const CHIP_VIEW = {
  plain: { Icon: LockKeyholeOpen, tone: 'warn', label: 'http.card.plainHttp', hint: () => 'http.card.plainHttpHint' },
  expected: { Icon: Target, tone: 'primary', label: 'http.card.expects', hint: () => 'http.card.expectsHint' },
  redirects: { Icon: CornerDownRight, tone: 'neutral', label: 'http.card.noRedirects', hint: () => 'http.card.noRedirectsHint' },
  strictTls: { Icon: ShieldCheck, tone: 'neutral', label: 'http.card.strictTls', hint: () => 'http.card.strictTlsHint' },
  alerts: { Icon: BellRing, tone: 'neutral', label: null, hint: null },
}
const ALERTS_LABEL = { both: 'http.card.tlsExpiryAlerts', tls: 'http.card.tlsAlerts', expiry: 'http.card.expiryAlerts' }

/** Ayar çipi — açıklaması dokun-gör balonunda (telefonda da açılır); tetik örtünün üstünde. */
function RequestChip({ chip, rowLabel }) {
  const t = useT()
  const view = CHIP_VIEW[chip.key]
  const label = chip.key === 'alerts' ? t(ALERTS_LABEL[chip.variant]) : t(view.label, chip.value)
  const hint = chip.key === 'alerts'
    ? [chip.tlsErrors && t('http.card.hintTlsErrors'),
      chip.sslExpiry && t('http.card.hintSslExpiry', daysText(chip.sslExpiry)),
      chip.domainExpiry && t('http.card.hintDomainExpiry', daysText(chip.domainExpiry))].filter(Boolean).join('\n')
    : t(view.hint(), chip.value)
  const Icon = view.Icon
  // Beklenen kod HER kartta (2026-09-30): varsayılan aralık nötr, elle değiştirilmiş vurgulu — `data-custom` test kancası.
  const tone = chip.key === 'expected' && !chip.custom ? 'neutral' : view.tone
  return (
    <HintPopover content={hint} triggerClassName={CHIP_TRIGGER} aria-label={t('a11y.rowAction', rowLabel, label)}>
      <Badge variant="outline" data-slot="http-chip" data-chip={chip.key} data-custom={chip.key === 'expected' ? String(!!chip.custom) : undefined}
        className={cn(CHIP, CHIP_TONE[tone])}>
        <Icon aria-hidden="true" />{label}
      </Badge>
    </HintPopover>
  )
}

/**
 * İSTEK SATIRI — izlemenin ne sorduğu, tek bakışta: yöntem rozeti (GET soluk; POST/HEAD vurgulu) · yalnız varsayılandan
 * farklı ayarların çipleri (şifresiz http · özel beklenen kod · yönlendirme takip edilmiyor · sıkı TLS · TLS/bitiş
 * alarmları) · sağda kontrol sıklığı. Dar kartta SARAR (rozet dizisi `flex-wrap`).
 *
 * <p>Test kancaları: `data-slot="http-request"`, `http-method` (`data-method`), `http-chip` (`data-chip`
 * plain|expected|redirects|strictTls|alerts), `http-interval`.
 */
export function HttpRequestRow({ monitor: m, rowLabel }) {
  const t = useT()
  const method = methodOf(m)
  const chips = requestChips(m)
  const every = intervalText(m.interval_seconds, t)
  return (
    <div data-slot="http-request" className="mb-2 flex min-w-0 flex-wrap items-center gap-1">
      <Badge variant="outline" data-slot="http-method" data-method={method}
        className={cn(CHIP, 'font-mono tracking-[.04em]', method === 'GET' ? CHIP_TONE.neutral : CHIP_TONE.primary)}>
        {method}
      </Badge>
      {chips.map((c) => <RequestChip key={c.key} chip={c} rowLabel={rowLabel} />)}
      {every && (
        <span data-slot="http-interval" className="ml-auto flex shrink-0 items-center gap-1 text-[10.5px] text-muted-foreground">
          <Timer aria-hidden="true" className="size-3" />{every}
        </span>
      )}
    </div>
  )
}

const VALUE_TONE = { ok: 'text-success', warn: 'text-amber-700 dark:text-amber-400', bad: 'text-destructive', neutral: 'text-foreground' }
const TILE_TONE = {
  ok: 'border-success/25 bg-success/5 dark:bg-success/10',
  warn: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  bad: 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10',
  neutral: 'bg-muted/40 dark:bg-muted/20',
}

/** Tek ölçü kutusu (Ping / Anahtar Kelime kartlarıyla aynı dil): küçük büyük harfli etiket · büyük değer (+ birim) [+ hüküm] · alt satır. */
function MetricTile({ metric, tone, label, parts, verdict, sub }) {
  return (
    <div data-slot="http-metric" data-metric={metric} data-tone={tone}
      className={cn('flex min-w-0 flex-col gap-0.5 rounded-lg border px-2.5 py-1.5', TILE_TONE[tone])}>
      <span className="truncate text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
      <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
        <span data-slot="http-metric-value" className={cn('text-xl leading-tight font-bold tabular-nums', VALUE_TONE[tone])}>
          {parts ? <>{parts.num}{parts.unit && <span className="ml-0.5 text-xs font-semibold text-muted-foreground">{parts.unit}</span>}</> : '—'}
        </span>
        {verdict && (
          <Badge variant="outline" data-slot="http-metric-verdict"
            className="h-4 rounded px-1 text-[9.5px] font-bold tracking-[.04em] uppercase border-amber-500/40 bg-amber-500/15 text-amber-800 dark:text-amber-300">
            {verdict}
          </Badge>
        )}
      </span>
      {sub && (
        <span data-slot="http-metric-sub"
          className={cn('truncate text-[10.5px] leading-snug font-medium', tone === 'neutral' ? 'text-muted-foreground' : VALUE_TONE[tone])}>
          {sub}
        </span>
      )}
    </div>
  )
}

const HTTP_SUB = { '2xx': 'keyword.card.http2xx', '3xx': 'keyword.card.http3xx', '4xx': 'keyword.card.http4xx', '5xx': 'keyword.card.http5xx' }

/**
 * KAHRAMAN ÖLÇÜLER — HTTP durumu + yanıt süresi, telefonda da İKİ sütun.
 * - HTTP kutusu: kod + sınıf metni (Başarılı / Yönlendirme / İstemci hatası / Sunucu hatası; yanıt yoksa "Yanıt yok").
 *   Ton SUNUCUNUN hükmünden: beklenen kod geldiyse yeşil (sınıf ne olursa olsun), gelmediyse ya da yanıt yoksa kırmızı.
 * - Süre kutusu (bkz. httpCardModel.responseView): zaman aşımında kırmızı "Zaman aşımı"; yanıt hiç gelmediyse değer yok
 *   + "N ms sonra başarısız"; yavaşlık eşiği satırda varsa "Yavaş · Sınır 3 s"; yoksa nötr ve alt satırda 24 sa
 *   ortalamasına göre sapma (kart trendinden) ya da izlemenin zaman aşımı sınırı — eşik uydurulmaz.
 * Hiç kontrol yoksa kutular yerine tek "İlk kontrol bekleniyor" satırı (iki boş "—" kutusu yer kaplıyordu).
 */
export function HttpMetricTiles({ monitor: m, verdict, reason, spark }) {
  const t = useT()
  if (verdict.kind === 'pending') {
    return (
      <p data-slot="http-pending" className="mb-2.5 flex min-w-0 items-center gap-1.5 rounded-lg border border-dashed bg-muted/40 px-2.5 py-2 text-xs font-medium text-muted-foreground dark:bg-muted/20">
        <MonitorPendingText idle={t('keyword.card.pending')} />
      </p>
    )
  }
  const resp = responseView(m, reason)
  const baseline = latencyBaseline(spark)
  const delta = resp.parts && baseline && baseline.avg > 0 ? Math.round(((Number(m.response_ms) - baseline.avg) / baseline.avg) * 100) : null
  // Beklenmeyen 2xx/3xx'e kırmızı "Başarılı" yazmak çelişkiydi → "Beklenmeyen kod" (ayrıntı neden satırında).
  const statusSub = verdict.kind === 'error' || !verdict.cls ? t('keyword.card.noResponse')
    : verdict.kind === 'mismatch' && (verdict.cls === '2xx' || verdict.cls === '3xx') ? t('http.card.unexpected')
      : t(HTTP_SUB[verdict.cls])
  const respSub = resp.timedOut ? t('keyword.card.timedOut')
    : resp.failedAfter ? t('http.card.failedAfter', resp.failedAfter)
      : resp.limit != null ? (resp.tone === 'warn' ? t('keyword.card.limit', msText(resp.limit)) : t('keyword.card.withinLimit', msText(resp.limit)))
        : delta != null ? t('ping.card.vs24h', signedPercent(delta, formatPercent))
          : msText(m.timeout_ms) ? t('http.card.timesOutAt', msText(m.timeout_ms)) : null
  return (
    <MonitorCardMetrics className="mb-2.5 grid grid-cols-2 gap-2">
      <MetricTile metric="status" tone={verdict.tone} label={t('keyword.card.http')}
        parts={verdict.code != null ? { num: String(verdict.code), unit: null } : null}
        sub={statusSub} />
      <MetricTile metric="response" tone={resp.tone} label={t('keyword.card.response')} parts={resp.parts}
        verdict={resp.tone === 'warn' ? t('keyword.card.slow') : null} sub={respSub} />
    </MonitorCardMetrics>
  )
}

const REASON_ICON = {
  timeout: TimerOff, dns: SearchX, tls: LockKeyhole, refused: Unplug, blocked: ShieldAlert, config: CircleAlert,
  http4xx: TriangleAlert, http5xx: ServerCrash, mismatch: Target, error: CircleAlert, down: CircleAlert,
}

/**
 * Neden metni — Zengin'deki iki satırlık kutu ve Kompakt'taki tek satır AYNI metni söyler. İstisna türleri Anahtar
 * Kelime kartıyla aynı metinlerden; yanıt geldiyse beklenen kodla birlikte ("Sunucu hatası 503 — beklenen 200-399").
 */
export function httpReasonText(reason, t) {
  if (!reason) return null
  return {
    timeout: reason.detail != null ? t('keyword.card.reasonTimeout', msText(reason.detail)) : t('keyword.card.reasonTimeoutBare'),
    dns: t('keyword.card.reasonDns'),
    tls: t('keyword.card.reasonTls', reason.detail),
    refused: t('keyword.card.reasonRefused'),
    blocked: t('keyword.card.reasonBlocked'),
    config: t('keyword.card.reasonConfig'),
    http4xx: t('http.card.reasonHttp4xx', reason.detail, reason.expected),
    http5xx: t('http.card.reasonHttp5xx', reason.detail, reason.expected),
    mismatch: t('http.card.reasonMismatch', reason.detail, reason.expected),
    error: t('keyword.card.reasonError', reason.detail),
    down: t('http.card.reasonDown'),
  }[reason.kind] || t('keyword.card.reasonError', reason.detail)
}
export const httpReasonIcon = (kind) => REASON_ICON[kind] || CircleAlert

/** Kapalı izlemenin nedeni — tek bakışta, en fazla iki satır, yalnız-hover ipucu yok (dokunmatikte de okunur). */
export function HttpReason({ reason }) {
  const t = useT()
  if (!reason) return null
  const Icon = REASON_ICON[reason.kind] || CircleAlert
  const text = httpReasonText(reason, t)
  return (
    <p data-slot="http-reason" data-reason={reason.kind} role="note"
      className="mb-2.5 flex min-w-0 items-start gap-1.5 rounded-md bg-destructive/10 px-2 py-1.5 text-xs leading-snug font-medium text-destructive dark:bg-destructive/15">
      <Icon aria-hidden="true" className="mt-px size-3.5 shrink-0" />
      <span className="line-clamp-2 min-w-0 [overflow-wrap:anywhere]">{text}</span>
    </p>
  )
}

/**
 * HTTP KOMPAKT ana ölçüsü (2026-09-27, kart yoğunluğu) — TEK satır: HTTP durum kodu + yanıt süresi, tonları Zengin
 * kutularla aynı kuraldan (sunucunun hükmü / zaman aşımı / yavaşlık eşiği). Yavaşsa görünür "Yavaş" rozeti.
 * Yanıt hiç gelmediyse (zaman aşımı / DNS / TLS / bağlantı) satır ÇİZİLMEZ — ölçülecek kod yok; kırmızı neden satırı
 * ("İstek 10 s içinde tamamlanmadı") söyler, "Yanıt yok" ikinci kez yazılmaz. Hiç kontrol yoksa soluk "İlk kontrol
 * bekleniyor". Sınıf metni, 24 sa sapması, zaman aşımı sınırı yalnız Zengin'de.
 */
export function HttpCompactMetric({ monitor: m, verdict, reason }) {
  const t = useT()
  if (verdict.kind === 'pending') {
    return (
      <p data-slot="http-pending" data-compact="true" className="mt-2 flex min-w-0 items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <MonitorPendingText idle={t('keyword.card.pending')} />
      </p>
    )
  }
  if (verdict.kind === 'error') return null
  const resp = responseView(m, reason)
  return (
    <CardCompactMetric slot="http-compact">
      <CompactValue metric="status" tone={verdict.tone} value={verdict.code != null ? String(verdict.code) : '—'} label={t('http.card.codeShort')} />
      <span className="inline-flex min-w-0 items-center gap-1.5">
        <CompactValue metric="response" tone={resp.tone} value={resp.parts ? resp.parts.num : '—'} unit={resp.parts?.unit} label={t('http.responseMs')} />
        {resp.tone === 'warn' && <CompactVerdict>{t('keyword.card.slow')}</CompactVerdict>}
      </span>
    </CardCompactMetric>
  )
}
