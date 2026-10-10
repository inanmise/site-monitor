import {
  Ban, CaseSensitive, CircleAlert, CircleCheck, CircleX, Globe, Hash, Hourglass, LockKeyhole, Network, Search,
  SearchX, ServerCrash, ShieldAlert, Timer, TimerOff, TriangleAlert, Unplug,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import HintPopover from '../ui/HintPopover.jsx'
import { CARD_LAYER, MonitorCardMetrics, MonitorPendingText } from '../monitoring/MonitorCard.jsx'
import { intervalText } from '../ping/pingCardModel.js'
import { CompactValue, CompactVerdict } from '../ping/PingCardParts.jsx'
import { urlParts } from '../pagespeed/pageSpeedCardModel.js'
import { cn } from '@/lib/utils'
import {
  LONG_KEYWORD, LONG_SNIPPET, alertTrigger, highlightParts, httpClass, httpTone, msParts, msText, proxyMode,
  responseAssessment, ruleLabel,
} from './keywordCardModel.js'
import { cardReason } from './keywordFailureModel.js'

/**
 * Hüküm metni (2026-10-04): istek tamamlanmadıysa (`error`) sunucunun kısa nedeni ("Zaman aşımı", "DNS çözümlenemedi" …)
 * — eski satırda (kod yok) "Sayfa okunamadı". Diğer hükümler değişmedi.
 */
function resultText(kind, m, t) {
  if (kind === 'error') {
    const r = cardReason(m, t)
    if (r && !r.hint) return r.label
  }
  return t(RESULT_KEY[kind])
}

/**
 * Son başarısızlığın neden çipi (2026-10-04) — sayfa geldi ama hüküm satırının söylemediği bir neden var ("Boş yanıt",
 * "Okuma sınırı aşıldı", "Giriş sayfası geldi" …). İstek hatasında ve eski satırda çizilmez (hüküm metni / neden kutusu
 * zaten söylüyor). Ayrıntı: detay → Kontrol geçmişi.
 */
function ReasonChip({ monitor: m, verdict }) {
  const t = useT()
  if (verdict.kind === 'error' || verdict.kind === 'pending' || verdict.tone !== 'bad') return null
  const r = cardReason(m, t)
  if (!r) return null
  return (
    <Badge variant="outline" data-slot="keyword-reason-chip" data-code={r.code} title={r.label}
      className={cn(CHIP, 'h-auto max-w-full justify-start text-left whitespace-normal [overflow-wrap:anywhere]', r.tone === 'warning'
        ? 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300'
        : 'border-destructive/40 bg-destructive/10 text-destructive')}>
      <TriangleAlert aria-hidden="true" />{r.label}
    </Badge>
  )
}

/** Hüküm tonu → kural panelinin zemini (Sayfa Bütünlüğü / Sentetik kartlarıyla aynı palet). */
const PANEL = {
  ok: 'border-success/25 bg-success/5 dark:bg-success/10',
  bad: 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10',
  neutral: 'border-dashed bg-muted/40 dark:bg-muted/20',
}
const RESULT_TEXT = { ok: 'text-success', bad: 'text-destructive', neutral: 'text-muted-foreground' }
const RESULT_ICON = {
  found: CircleCheck, absent: CircleCheck, countOk: CircleCheck,
  missing: SearchX, forbidden: Ban, countFail: CircleX, error: CircleAlert, pending: Hourglass,
}
const RESULT_KEY = {
  found: 'keyword.found', missing: 'keyword.notFound', absent: 'keyword.card.absent', forbidden: 'keyword.card.forbidden',
  countOk: 'keyword.card.countOk', countFail: 'keyword.card.countFail', pending: 'keyword.card.pending', error: 'keyword.card.checkFailed',
}
/** Vurgu: yasak kelimede kırmızı (kanıt), diğerlerinde sarı fosfor. */
const MARK = {
  bad: 'bg-destructive/15 text-destructive dark:bg-destructive/25',
  other: 'bg-amber-300/60 text-foreground dark:bg-amber-400/30',
}
/** Kart içindeki dokun-gör tetiği: örtünün üstünde, dokunmatikte 40 px yüksek dokunma alanı. */
const CHIP_TRIGGER = cn(CARD_LAYER, 'max-w-full rounded-md pointer-coarse:min-h-10')
const CHIP = 'h-5 rounded-md px-1.5 text-[10.5px] font-semibold'

/** Başlık URL'i: şema yalnız https DEĞİLSE (soluk — düz http bir bulgudur), host vurgulu, yol + sorgu soluk. */
export function KeywordUrlText({ url }) {
  const { scheme, host, path } = urlParts(url)
  return (
    <>
      {scheme && <span className="font-normal text-muted-foreground">{scheme}</span>}
      <span className="font-semibold text-foreground">{host}</span>
      {path && <span className="font-normal text-muted-foreground">{path}</span>}
    </>
  )
}

/**
 * KURAL PANELİ — kartın kalbi (2026-09-27). Yukarıdan aşağıya: kural rozetleri (içermeli / içermemeli / adet · harf
 * duyarlı · sıklık) → aranan metin (eş aralıklı, « » arasında: baştaki/sondaki boşluk da görünür) → SON SONUÇ (Bulundu ✓ /
 * Bulunamadı ✗ / Bulundu — olmamalı … + eşleşme sayısı) → kanıt: ilk eşleşmenin çevresi (kelime vurgulu) ya da kapalıysa
 * NEDEN (zaman aşımı / DNS / TLS / HTTP 5xx …). Zemin hükmün tonunda; bekleyen izlemede kesik kenarlı nötr.
 *
 * <p>Test kancaları: `data-slot="keyword-panel"` + `data-result` (found|missing|absent|forbidden|countOk|countFail|
 * pending|error) + `data-tone`; `keyword-rule` (`data-rule` contains|absent|count), `keyword-case`, `keyword-chip`,
 * `keyword-result`, `keyword-count`, `keyword-snippet`, `keyword-reason` (`data-reason`).
 */
export function KeywordRulePanel({ monitor: m, verdict, reason, rowLabel }) {
  const t = useT()
  const { rule, tone, kind, count } = verdict
  const Icon = RESULT_ICON[kind]
  const showSnippet = !!m.snippet && count !== 0 && kind !== 'error' && kind !== 'pending'
  // Sayı: "Bulunamadı" / "Sayfada yok" zaten sıfır diyor; adet kuralında ve bulunduğunda anlamlı.
  const showCount = count != null && (count > 0 || rule.kind === 'count')
  return (
    <section data-slot="keyword-panel" data-result={kind} data-tone={tone} aria-label={t('keyword.card.rule')}
      className={cn('mb-2.5 min-w-0 rounded-lg border px-3 pt-2 pb-2.5', PANEL[tone])}>
      <div className="mb-1.5 flex min-w-0 flex-wrap items-center gap-1">
        <RuleBadge monitor={m} rule={rule} rowLabel={rowLabel} />
        {m.case_sensitive && (
          <HintPopover content={t('keyword.card.caseSensitiveHint')} triggerClassName={CHIP_TRIGGER}
            aria-label={t('a11y.rowAction', rowLabel, t('keyword.card.caseSensitive'))}>
            <Badge variant="outline" data-slot="keyword-case" className={cn(CHIP, 'bg-card font-medium text-muted-foreground')}>
              <CaseSensitive aria-hidden="true" />{t('keyword.card.caseSensitive')}
            </Badge>
          </HintPopover>
        )}
        {intervalText(m.interval_seconds, t) && (
          <span data-slot="keyword-interval" className="ml-auto flex shrink-0 items-center gap-1 text-[10.5px] text-muted-foreground">
            <Timer aria-hidden="true" className="size-3" />{intervalText(m.interval_seconds, t)}
          </span>
        )}
      </div>

      <KeywordChip keyword={m.keyword} rowLabel={rowLabel} />

      <p data-slot="keyword-result" className="mt-2 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
        <span className={cn('inline-flex min-w-0 items-center gap-1.5 text-sm leading-tight font-bold', RESULT_TEXT[tone])}>
          {kind === 'pending'
            ? <MonitorPendingText idle={t(RESULT_KEY.pending)} icon={Icon} iconClassName="size-4 shrink-0" spinnerSize={16} />
            : <><Icon aria-hidden="true" className="size-4 shrink-0" />{resultText(kind, m, t)}</>}
        </span>
        {showCount && (
          <span data-slot="keyword-count" className="text-xs font-medium text-muted-foreground tabular-nums">
            {count === 0 ? t('keyword.card.noMatches') : count === 1 ? t('keyword.card.match1') : t('keyword.card.matches', count)}
          </span>
        )}
        <ReasonChip monitor={m} verdict={verdict} />
      </p>

      {showSnippet && (
        <MatchSnippet snippet={m.snippet} keyword={m.keyword} caseSensitive={!!m.case_sensitive}
          forbidden={kind === 'forbidden'} rowLabel={rowLabel} />
      )}
      {reason && <KeywordReason reason={reason} />}
    </section>
  )
}

const RULE_ICON = { contains: Search, absent: Ban, count: Hash }

/** Kural rozeti — ne zaman alarm verdiği dokun-gör balonunda (formdaki canlı açıklamanın aynı cümlesi). */
function RuleBadge({ monitor: m, rule, rowLabel }) {
  const t = useT()
  const Icon = RULE_ICON[rule.kind]
  const label = ruleLabel(rule, t)
  return (
    <HintPopover content={t('keyword.card.alertsWhen', alertTrigger(rule, m.keyword, t))} triggerClassName={CHIP_TRIGGER}
      aria-label={t('a11y.rowAction', rowLabel, label)}>
      <Badge variant="outline" data-slot="keyword-rule" data-rule={rule.kind}
        className={cn(CHIP, 'border-primary/30 bg-primary/5 text-primary dark:bg-primary/15',
          rule.kind === 'absent' && 'tracking-[.01em]')}>
        <Icon aria-hidden="true" />{label}
      </Badge>
    </HintPopover>
  )
}

/**
 * Aranan metin — eş aralıklı çip, « » arasında; boşluklar korunur (sunucu metni birebir arar). Uzunsa iki satırda
 * kırpılır, tamamı dokun-gör balonunda (telefonda da açılır). Kısa çip örtünün altında kalır: tıklamak detayı açar.
 */
function KeywordChip({ keyword, rowLabel }) {
  const t = useT()
  const text = String(keyword ?? '')
  const chip = (
    <Badge variant="outline" data-slot="keyword-chip"
      className="h-auto max-w-full min-w-0 items-start justify-start gap-1 rounded-md bg-card px-2 py-1 text-left font-mono text-[13px] leading-snug font-semibold whitespace-normal text-foreground shadow-xs">
      <span aria-hidden="true" className="shrink-0 font-normal text-muted-foreground select-none">«</span>
      <span className="line-clamp-2 min-w-0 break-all whitespace-pre-wrap">{text}</span>
      <span aria-hidden="true" className="shrink-0 font-normal text-muted-foreground select-none">»</span>
    </Badge>
  )
  if (text.length <= LONG_KEYWORD) return chip
  return (
    <HintPopover content={text} className="max-h-64 overflow-y-auto font-mono break-all whitespace-pre-wrap"
      aria-label={t('a11y.rowAction', rowLabel, t('keyword.card.showKeyword'))}
      triggerClassName={cn(CHIP_TRIGGER, 'h-auto justify-start text-left whitespace-normal')}>
      {chip}
    </HintPopover>
  )
}

/** Metin parçaları → vurgulu satır içi öğeler (eşleşen kısım `<mark>`). */
function Highlighted({ parts, forbidden }) {
  return parts.map((p, i) => (p.match
    ? <mark key={i} className={cn('rounded-[3px] px-0.5 font-semibold', forbidden ? MARK.bad : MARK.other)}>{p.text}</mark>
    : <span key={i}>{p.text}</span>))
}

/**
 * İlk eşleşmenin çevresi (sunucunun `snippet`'i — ham gövde, ±50 karakter) — aranan kelime vurgulu, iki satırda kırpılır;
 * uzunsa tamamı dokun-gör balonunda. Yasak kelimede vurgu kırmızı: sorunun KANITI.
 */
function MatchSnippet({ snippet, keyword, caseSensitive, forbidden, rowLabel }) {
  const t = useT()
  const parts = highlightParts(snippet, keyword, caseSensitive)
  // Zemin + dolgu DIŞ kutuda, kırpma İÇ öğede: line-clamp dolgulu öğede taşmayı dolgu kutusunda keser → üçüncü satırın
  // üst yarısı alt dolguda görünüyordu (Playwright 390 px). `block` da clamp'in `display:-webkit-box`'ını ezer.
  const body = (
    <span className="mt-0.5 block min-w-0 rounded-md bg-background/80 px-2 py-1 text-left font-mono text-[11.5px] leading-snug font-normal text-muted-foreground dark:bg-background/40">
      <span className="line-clamp-2 break-all"><Highlighted parts={parts} forbidden={forbidden} /></span>
    </span>
  )
  return (
    <div data-slot="keyword-snippet" className="mt-1.5 min-w-0">
      <span className="text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{t('keyword.card.firstMatch')}</span>
      {String(snippet).length > LONG_SNIPPET ? (
        <HintPopover content={<Highlighted parts={parts} forbidden={forbidden} />}
          className="max-h-64 overflow-y-auto font-mono break-all"
          aria-label={t('a11y.rowAction', rowLabel, t('keyword.card.showMatch'))}
          triggerClassName={cn(CHIP_TRIGGER, 'block h-auto w-full justify-start whitespace-normal')}>
          {body}
        </HintPopover>
      ) : body}
    </div>
  )
}

const REASON_ICON = {
  timeout: TimerOff, dns: SearchX, tls: LockKeyhole, refused: Unplug, blocked: ShieldAlert, config: CircleAlert,
  http4xx: TriangleAlert, http5xx: ServerCrash, error: CircleAlert,
}

/** Neden metni — Zengin paneldeki iki satırlık kutu ve Kompakt'taki tek satır AYNI metni söyler. */
export function keywordReasonText(reason, t) {
  if (!reason) return null
  return {
    timeout: reason.detail != null ? t('keyword.card.reasonTimeout', msText(reason.detail)) : t('keyword.card.reasonTimeoutBare'),
    dns: t('keyword.card.reasonDns'),
    tls: t('keyword.card.reasonTls', reason.detail),
    refused: t('keyword.card.reasonRefused'),
    blocked: t('keyword.card.reasonBlocked'),
    config: t('keyword.card.reasonConfig'),
    http4xx: t('keyword.card.reasonHttp4xx', reason.detail),
    http5xx: t('keyword.card.reasonHttp5xx', reason.detail),
    error: t('keyword.card.reasonError', reason.detail),
  }[reason.kind] || t('keyword.card.reasonError', reason.detail)
}
export const keywordReasonIcon = (kind) => REASON_ICON[kind] || CircleAlert

/** Kapalı izlemenin nedeni — tek bakışta, en fazla iki satır, yalnız-hover ipucu yok (dokunmatikte de okunur). */
function KeywordReason({ reason }) {
  const t = useT()
  const Icon = REASON_ICON[reason.kind] || CircleAlert
  const text = keywordReasonText(reason, t)
  return (
    <p data-slot="keyword-reason" data-reason={reason.kind} role="note"
      className="mt-1.5 flex min-w-0 items-start gap-1.5 rounded-md bg-destructive/10 px-2 py-1.5 text-xs leading-snug font-medium text-destructive dark:bg-destructive/15">
      <Icon aria-hidden="true" className="mt-px size-3.5 shrink-0" />
      <span className="line-clamp-2 min-w-0 [overflow-wrap:anywhere]">{text}</span>
    </p>
  )
}

const VALUE_TONE = { ok: 'text-success', warn: 'text-amber-700 dark:text-amber-400', bad: 'text-destructive', neutral: 'text-foreground' }
const TILE_TONE = {
  ok: '',
  warn: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  bad: 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10',
  neutral: '',
}

/** Tek ölçü kutusu (Ping kartıyla aynı dil): küçük büyük harfli etiket · büyük değer (+ birim) [+ hüküm rozeti] · alt satır. */
function MetricTile({ metric, tone, label, parts, verdict, sub }) {
  return (
    <div data-slot="keyword-metric" data-metric={metric} data-tone={tone}
      className={cn('flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/40 px-2.5 py-1.5 dark:bg-muted/20', TILE_TONE[tone])}>
      <span className="truncate text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
      <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
        <span data-slot="keyword-metric-value" className={cn('text-lg leading-tight font-bold tabular-nums', VALUE_TONE[tone])}>
          {parts ? <>{parts.num}{parts.unit && <span className="ml-0.5 text-xs font-semibold text-muted-foreground">{parts.unit}</span>}</> : '—'}
        </span>
        {verdict && (
          <Badge variant="outline" data-slot="keyword-metric-verdict"
            className="h-4 rounded px-1 text-[9.5px] font-bold tracking-[.04em] uppercase border-amber-500/40 bg-amber-500/15 text-amber-800 dark:text-amber-300">
            {verdict}
          </Badge>
        )}
      </span>
      {sub && (
        <span data-slot="keyword-metric-sub"
          className={cn('truncate text-[10.5px] leading-snug font-medium', tone === 'neutral' || tone === 'ok' ? 'text-muted-foreground' : VALUE_TONE[tone])}>
          {sub}
        </span>
      )}
    </div>
  )
}

const HTTP_SUB = { '2xx': 'keyword.card.http2xx', '3xx': 'keyword.card.http3xx', '4xx': 'keyword.card.http4xx', '5xx': 'keyword.card.http5xx' }

/**
 * HTTP durumu + yanıt süresi — telefonda da İKİ sütun. HTTP tonu sınıfından (2xx yeşil · 3xx nötr · 4xx amber · 5xx kırmızı;
 * yanıt yoksa kırmızı "Yanıt yok"); süre tonu izlemenin yavaşlık eşiğinden (açıksa: üstü "Yavaş" + sınır, altı yeşil;
 * kapalıysa nötr — eşik uydurulmaz), zaman aşımında kırmızı. Hiç kontrol yoksa kutular çizilmez (boş "—" kutuları yer
 * kaplıyordu; panel "Henüz kontrol edilmedi" diyor). Sayfa boyutu satırda olmadığı için kutusu yok.
 */
export function KeywordMetricTiles({ monitor: m, verdict, reason }) {
  const t = useT()
  if (verdict.kind === 'pending') return null
  const failed = verdict.kind === 'error'
  const code = m.http_status != null ? String(m.http_status) : null
  const cls = httpClass(m.http_status)
  const httpToneKey = httpTone(m.http_status, failed)
  const resp = responseAssessment(m, reason)
  const respSub = resp.timedOut ? t('keyword.card.timedOut')
    : resp.limit == null ? null
      : resp.tone === 'warn' ? t('keyword.card.limit', msText(resp.limit)) : t('keyword.card.withinLimit', msText(resp.limit))
  return (
    <MonitorCardMetrics className="mb-2.5 grid grid-cols-2 gap-2">
      <MetricTile metric="http" tone={httpToneKey} label={t('keyword.card.http')}
        parts={code ? { num: code, unit: null } : null}
        sub={cls ? t(HTTP_SUB[cls]) : failed ? t('keyword.card.noResponse') : null} />
      <MetricTile metric="response" tone={resp.tone} label={t('keyword.card.response')} parts={msParts(m.response_ms)}
        verdict={resp.tone === 'warn' ? t('keyword.card.slow') : null} sub={respSub} />
    </MonitorCardMetrics>
  )
}

/**
 * Vekil KİPİ çipi — yalnız kip zorlanmışsa (ON: "Hep vekil üzerinden", OFF: "Hep doğrudan"); ON iken vekil tanımsız /
 * NO_PROXY yüzünden doğrudan çıkıldıysa amber "Vekil atlandı". Açıklama (formdaki kip ipucu + atlama nedeni) dokun-gör.
 * AUTO'da hiçbir şey çizilmez — yol rozeti MonitorCardMeta'da.
 */
export function KeywordProxyChip({ monitor: m, rowLabel }) {
  const t = useT()
  const pm = proxyMode(m)
  if (!pm) return null
  const Icon = pm.bypassed ? TriangleAlert : pm.mode === 'ON' ? Network : Globe
  const label = pm.bypassed ? t('keyword.card.proxyBypassed') : pm.mode === 'ON' ? t('keyword.card.proxyOn') : t('keyword.card.proxyOff')
  const hint = [t(`mon.proxy.hint.${pm.mode}`), pm.bypassed ? t('mon.proxy.bypassed') : null].filter(Boolean).join('\n')
  return (
    <HintPopover content={hint} triggerClassName={CHIP_TRIGGER} aria-label={t('a11y.rowAction', rowLabel, label)}>
      <Badge variant="outline" data-slot="keyword-proxy" data-mode={pm.mode} data-bypassed={pm.bypassed ? 'true' : undefined}
        className={cn(CHIP, pm.bypassed
          ? 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300'
          : pm.mode === 'ON' ? 'border-primary/30 bg-primary/5 text-primary dark:bg-primary/15' : 'bg-card text-muted-foreground')}>
        <Icon aria-hidden="true" />{label}
      </Badge>
    </HintPopover>
  )
}

/**
 * Anahtar Kelime KOMPAKT sonuç satırı (2026-09-27, kart yoğunluğu) — TEK satır: son sonuç (Bulundu ✓ / Bulunamadı ✗ /
 * Bulundu — olmamalı …, hükmün tonunda) · aranan metin (eş aralıklı « », kırpılır; tamamı `title`'da, detayda ve
 * Zengin'de) · sağda yanıt süresi (yavaşlık eşiği aşıldıysa amber + "Yavaş"). Kural rozetleri, eşleşme sayısı, ilk
 * eşleşmenin çevresi ve HTTP kutusu yalnız Zengin'de. Sayfa okunamadıysa ya da hiç kontrol yoksa süre yazılmaz.
 *
 * <p>Test kancaları: `data-slot="keyword-compact"` + `data-result` + `data-tone`; `keyword-result`, `keyword-compact-kw`,
 * süre `compact-value[data-metric=response]`.
 */
export function KeywordCompactResult({ monitor: m, verdict, reason }) {
  const t = useT()
  const { kind, tone } = verdict
  const Icon = RESULT_ICON[kind]
  const kw = String(m.keyword ?? '')
  const time = kind === 'pending' || kind === 'error' ? null : msParts(m.response_ms)
  const resp = time ? responseAssessment(m, reason) : null
  return (
    <div data-slot="keyword-compact" data-result={kind} data-tone={tone} className="mt-2 flex min-w-0 items-center gap-x-2">
      <span data-slot="keyword-result" className={cn('inline-flex shrink-0 items-center gap-1 text-sm leading-tight font-bold', RESULT_TEXT[tone])}>
        {kind === 'pending'
          ? <MonitorPendingText idle={t(RESULT_KEY.pending)} icon={Icon} iconClassName="size-4 shrink-0" spinnerSize={16} />
          : <><Icon aria-hidden="true" className="size-4 shrink-0" />{resultText(kind, m, t)}</>}
      </span>
      {kw && (
        <span data-slot="keyword-compact-kw" title={kw} className="min-w-0 overflow-hidden font-mono text-xs text-ellipsis whitespace-pre text-muted-foreground">
          <span aria-hidden="true">«</span>{kw}<span aria-hidden="true">»</span>
        </span>
      )}
      {time && (
        <span className="ml-auto inline-flex shrink-0 items-center gap-1.5">
          {resp.tone === 'warn' && <CompactVerdict>{t('keyword.card.slow')}</CompactVerdict>}
          <CompactValue metric="response" tone={resp.tone} value={time.num} unit={time.unit} />
        </span>
      )}
    </div>
  )
}
