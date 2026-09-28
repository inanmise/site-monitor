import { CircleAlert, CircleCheck, FileText, Layers3, Link2Off, ServerCrash, ShieldAlert, TimerOff, Timer } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDateSec } from '../../api/client'
import { relativeTime } from '../admin/audit/auditFormat.js'
import { tagsOf } from '../../utils/monitorFilters.js'
import { toUtc } from '../../utils/localDay.js'
import { Badge } from '@/components/shadcn/badge'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { MonitorCardMetrics, MonitorPendingText, CARD_LAYER } from '../monitoring/MonitorCard.jsx'
import { cn } from '@/lib/utils'
import { intervalText } from '../ping/pingCardModel.js'
import { exclusionCount, httpTone, humanizeMs, integrityResult, pageFailure, urlParts } from './pageCardModel.js'

const CHIP = 'h-5 rounded-md px-1.5 text-[10.5px] font-semibold'
const msText = (ms) => {
  const p = humanizeMs(ms)
  return p ? `${p.num} ${p.unit}` : null
}

/** Başlık URL'i: şema yalnız https DEĞİLSE (amber — düz http bir bulgudur), host vurgulu, yol + sorgu soluk. Kırpma MonitorCardTitle'da. */
export function PageUrlText({ url }) {
  const { scheme, host, path } = urlParts(url)
  return (
    <>
      {scheme && <span data-slot="page-url-scheme" className="font-normal text-amber-700 dark:text-amber-400">{scheme}</span>}
      <span data-slot="page-url-host" className="font-semibold text-foreground">{host}</span>
      {path && <span data-slot="page-url-path" className="font-normal text-muted-foreground">{path}</span>}
    </>
  )
}

/**
 * NE DENETLENİYOR — yapılandırma çipleri: kip (tek sayfa / site taraması · derinlik · en çok N sayfa), sıklık, üçüncü
 * taraf kaynaklar alarma dahil mi, hariç tutma kuralı sayısı. Test kancaları: `data-slot="page-scope"`, çip başına
 * `data-chip="mode|interval|third-party|exclusions"`.
 */
export function PageScopeChips({ monitor: m, className }) {
  const t = useT()
  const crawl = m.mode === 'SITE_CRAWL'
  const every = intervalText(m.interval_seconds, t)
  const excluded = exclusionCount(m.exclude_patterns)
  const crawlBits = crawl ? [
    m.crawl_depth != null && t('page.card.depth', m.crawl_depth),
    m.crawl_max_pages != null && t('page.card.maxPages', m.crawl_max_pages),
  ].filter(Boolean) : []
  return (
    <div data-slot="page-scope" className={cn('flex min-w-0 flex-wrap items-center gap-1.5', className)}>
      <Badge variant="outline" data-chip="mode"
        className={cn(CHIP, 'max-w-full border-sky-600/30 bg-sky-500/10 text-sky-800 dark:border-sky-400/30 dark:text-sky-300')}>
        {crawl ? <Layers3 aria-hidden="true" /> : <FileText aria-hidden="true" />}
        <span className="truncate">{crawl ? t('page.modeCrawl') : t('page.modeSingle')}{crawlBits.length ? ` · ${crawlBits.join(' · ')}` : ''}</span>
      </Badge>
      {every && (
        <Badge variant="outline" data-chip="interval" className={cn(CHIP, 'font-medium text-muted-foreground')}>
          <Timer aria-hidden="true" />{every}
        </Badge>
      )}
      {m.alert_third_party && (
        <Badge variant="outline" data-chip="third-party" className={cn(CHIP, 'font-medium text-muted-foreground')}>{t('page.card.thirdParty')}</Badge>
      )}
      {excluded > 0 && (
        <Badge variant="outline" data-chip="exclusions" className={cn(CHIP, 'font-medium text-muted-foreground')}>
          {excluded === 1 ? t('page.card.exclusion1') : t('page.card.exclusions', excluded)}
        </Badge>
      )}
    </div>
  )
}

const PANEL = {
  ok: 'border-success/25 bg-success/5 dark:bg-success/10',
  crit: 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10',
  warn: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  config: 'border-violet-500/35 bg-violet-500/5 dark:bg-violet-500/10',
  none: 'bg-muted/40 dark:bg-muted/20',
}
const ISSUE = {
  broken: { Icon: Link2Off, key: 'page.card.broken', cls: 'border-destructive/35 bg-destructive/10 text-destructive dark:bg-destructive/20' },
  timeout: { Icon: TimerOff, key: 'page.card.timedOut', cls: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300' },
  mixed: { Icon: ShieldAlert, key: 'page.card.mixed', cls: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300' },
}

/** Sorun çipi — sayı + tür ("7 kırık"). */
function IssueChip({ kind, count, className }) {
  const t = useT()
  const { Icon, key, cls } = ISSUE[kind]
  return (
    <Badge variant="outline" data-slot="page-issue" data-kind={kind} className={cn(CHIP, 'tabular-nums', cls, className)}>
      <Icon aria-hidden="true" />{t(key, count)}
    </Badge>
  )
}

/**
 * BÜTÜNLÜK SONUCU — kartın kalbi. Tarandıysa: sorunsuz yüklenen kaynak oranı büyük ve okunur ("125 / 132 kaynak
 * sorunsuz") + oran çubuğu (shadcn Progress → ui/Progress ProgressBar; el yapımı yüzde çubuğu YASAK) + sorun çipleri
 * (kırık · zaman aşımı · mixed content; hiç yoksa yeşil "sorun yok" satırı); site taramasında gezilen sayfa sayısı üst
 * satırda. Sayfa yüklenemediyse (DOWN) kırmızı "sayfa yüklenemedi" + sunucunun nedeni; yapılandırma hatasında mor panel;
 * hiç kontrol edilmemişse sade "ilk kontrolü bekleniyor". Test kancaları: `data-slot="page-integrity"` + `data-tone`
 * (ok|warn|crit|config|none), `page-counts`, `page-issue` + `data-kind`, `page-reason` + `data-reason`, `page-pages`.
 */
export function PageIntegrityResult({ monitor: m }) {
  const t = useT()
  const failure = pageFailure(m)
  const result = integrityResult(m)
  if (!failure && (!result || !m.checked_at)) {
    return (
      <div data-slot="page-integrity" data-tone="none"
        className={cn('mb-2.5 rounded-lg border border-dashed px-3 py-2.5 text-xs text-muted-foreground', PANEL.none)}>
        <MonitorPendingText idle={t('page.card.awaitingFirstCheck')} icon={null} />
      </div>
    )
  }
  if (failure) {
    const config = failure.kind === 'config'
    const Icon = config ? CircleAlert : ServerCrash
    return (
      <section data-slot="page-integrity" data-tone={config ? 'config' : 'crit'} aria-label={t('page.card.integrity')}
        className={cn('mb-2.5 min-w-0 rounded-lg border px-3 py-2.5', config ? PANEL.config : PANEL.crit)}>
        <div data-slot="page-reason" data-reason={failure.kind} role="note"
          className={cn('flex min-w-0 items-start gap-1.5 text-sm leading-snug font-semibold',
            config ? 'text-violet-700 dark:text-violet-300' : 'text-destructive')}>
          <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="min-w-0">
            {config ? t('page.card.configError') : t('page.card.pageDown')}
            {(failure.detailKey || failure.detail) && (
              <span data-slot="page-reason-detail" className="mt-0.5 line-clamp-2 text-xs font-normal text-muted-foreground [overflow-wrap:anywhere]">
                {failure.detailKey ? t(failure.detailKey) : failure.detail}
              </span>
            )}
          </span>
        </div>
      </section>
    )
  }
  const issues = [['broken', result.broken], ['timeout', result.timeouts], ['mixed', result.mixed]].filter(([, n]) => n > 0)
  return (
    <section data-slot="page-integrity" data-tone={result.tone} aria-label={t('page.card.integrity')}
      className={cn('mb-2.5 min-w-0 rounded-lg border px-3 py-2.5', PANEL[result.tone])}>
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-2 gap-y-1">
        <span className="text-[10px] font-semibold tracking-[.06em] text-muted-foreground uppercase">{t('page.card.resources')}</span>
        {result.pages != null && (
          <Badge variant="outline" data-slot="page-pages" className="h-5 rounded-md bg-card px-1.5 text-[10.5px] font-medium text-muted-foreground tabular-nums">
            <Layers3 aria-hidden="true" />{result.pages === 1 ? t('page.card.page1') : t('page.card.pages', result.pages)}
          </Badge>
        )}
      </div>
      {result.total > 0 ? (
        <>
          <p data-slot="page-counts" className="mt-1 mb-1.5 flex min-w-0 flex-wrap items-baseline gap-x-1.5 tabular-nums">
            <span aria-hidden="true" className={cn('text-xl leading-tight font-bold',
              result.broken ? 'text-destructive' : result.problems ? 'text-amber-700 dark:text-amber-400' : 'text-success')}>
              {result.healthy}<span className="text-sm font-semibold text-muted-foreground"> / {result.total}</span>
            </span>
            <span aria-hidden="true" className="text-xs font-medium text-foreground">{t('page.card.resourcesOk')}</span>
            <span className="sr-only">{t('page.card.resourcesOf', result.healthy, result.total)}</span>
          </p>
          <ProgressBar size="sm" className="h-1.5" decorative tone={result.broken ? 'crit' : result.problems ? 'warn' : 'ok'}
            value={result.healthy} max={result.total} />
          {issues.length > 0 ? (
            <div data-slot="page-issues" className="mt-2 flex min-w-0 flex-wrap items-center gap-1.5">
              {issues.map(([kind, n]) => <IssueChip key={kind} kind={kind} count={n} />)}
            </div>
          ) : (
            <p data-slot="page-no-issues" className="mt-2 flex items-center gap-1.5 text-xs font-medium text-success">
              <CircleCheck aria-hidden="true" className="size-3.5 shrink-0" />{t('page.card.noIssues')}
            </p>
          )}
        </>
      ) : (
        <p className="mt-1 text-xs text-muted-foreground">{t('page.card.noResources')}</p>
      )}
    </section>
  )
}

const COUNT_TONE = { crit: 'text-destructive', warn: 'text-amber-700 dark:text-amber-400', ok: 'text-success' }

/**
 * KOMPAKT BÜTÜNLÜK satırı (yoğunluk = Kompakt) — kartın ana ölçüsü tek satırda: sorunsuz kaynak oranı ("123 / 132 kaynak
 * sorunsuz") + EN AĞIR sorunun çipi (kırık > zaman aşımı > mixed; yoksa yeşil onay). Sayfa yüklenemediyse kırmızı
 * "sayfa yüklenemedi", yapılandırma hatasında mor "URL denetlenemiyor" + altında TEK satır neden (tamamı dokun-gör
 * balonunda). Oran çubuğu, tüm sorun çipleri, gezilen sayfa, HTTP/süre kutuları ve trend Zengin görünümde.
 * Test kancaları: `data-slot="page-compact"` + `data-tone`; page-compact-counts · page-compact-verdict ·
 * page-compact-reason · page-compact-ok · page-issue (tek çip).
 */
export function PageCompactSummary({ monitor: m }) {
  const t = useT()
  const failure = pageFailure(m)
  const result = integrityResult(m)
  const box = 'mb-2 min-w-0 rounded-md border px-2.5 py-1.5'
  if (!failure && (!result || !m.checked_at)) {
    return (
      <div data-slot="page-compact" data-tone="none" className={cn(box, 'border-dashed text-xs text-muted-foreground', PANEL.none)}>
        <MonitorPendingText idle={t('page.card.awaitingFirstCheck')} icon={null} />
      </div>
    )
  }
  if (failure) {
    const config = failure.kind === 'config'
    const Icon = config ? CircleAlert : ServerCrash
    const detail = failure.detailKey ? t(failure.detailKey) : failure.detail
    return (
      <div data-slot="page-compact" data-tone={config ? 'config' : 'crit'} className={cn(box, config ? PANEL.config : PANEL.crit)}>
        <p className={cn('flex min-w-0 items-center gap-1.5 text-[13px] leading-5 font-semibold',
          config ? 'text-violet-700 dark:text-violet-300' : 'text-destructive')}>
          <Icon aria-hidden="true" className="size-3.5 shrink-0" />
          <span data-slot="page-compact-verdict" className="min-w-0 truncate">{config ? t('page.card.configError') : t('page.card.pageDown')}</span>
        </p>
        {detail && (
          <HintPopover content={detail} className="max-h-64 overflow-y-auto [overflow-wrap:anywhere]"
            triggerClassName={cn(CARD_LAYER, 'mt-0.5 flex w-full min-w-0 justify-start rounded-sm pl-5 text-left text-xs font-medium text-foreground/80 hover:text-foreground/80 pointer-coarse:min-h-10')}>
            <span data-slot="page-compact-reason" className="min-w-0 truncate">{detail}</span>
          </HintPopover>
        )}
      </div>
    )
  }
  const worst = result.broken ? ['broken', result.broken] : result.timeouts ? ['timeout', result.timeouts] : result.mixed ? ['mixed', result.mixed] : null
  return (
    <div data-slot="page-compact" data-tone={result.tone} className={cn(box, PANEL[result.tone])}>
      {result.total > 0 ? (
        // flex-wrap (2026-09-27, Kompakt ızgara tabanı 250 px): dar kartta sorun çipi etiketi kesmek yerine alt satıra
        // iner (sağa yaslı) — "123 / 132 kaynak …" yerine tam metin.
        <p className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1 text-[13px] leading-5">
          <span data-slot="page-compact-counts" className="flex min-w-0 items-baseline gap-1.5 tabular-nums">
            <span aria-hidden="true" className={cn('shrink-0 font-bold', result.broken ? COUNT_TONE.crit : result.problems ? COUNT_TONE.warn : COUNT_TONE.ok)}>
              {result.healthy}<span className="text-xs font-semibold text-muted-foreground"> / {result.total}</span>
            </span>
            <span aria-hidden="true" className="min-w-0 truncate text-xs font-medium text-foreground">{t('page.card.resourcesOk')}</span>
            <span className="sr-only">{t('page.card.resourcesOf', result.healthy, result.total)}</span>
          </span>
          {worst
            ? <IssueChip kind={worst[0]} count={worst[1]} className="ml-auto" />
            : <CircleCheck aria-hidden="true" data-slot="page-compact-ok" className="ml-auto size-3.5 shrink-0 text-success" />}
        </p>
      ) : (
        <p className="text-xs text-muted-foreground">{t('page.card.noResources')}</p>
      )}
    </div>
  )
}

const VALUE_TONE = { ok: 'text-success', warn: 'text-amber-700 dark:text-amber-400', bad: 'text-destructive', neutral: 'text-foreground' }
const TILE_TONE = {
  ok: '', neutral: '',
  warn: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  bad: 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10',
}

/** Tek ölçü kutusu (Ping kartıyla aynı dil): etiket · büyük değer (+ birim) · alt satır. */
function MetricTile({ metric, tone, label, value, unit, sub }) {
  return (
    <div data-slot="page-metric" data-metric={metric} data-tone={tone}
      className={cn('flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/40 px-2.5 py-1.5 dark:bg-muted/20', TILE_TONE[tone])}>
      <span className="truncate text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
      <span data-slot="page-metric-value" className={cn('text-lg leading-tight font-bold tabular-nums', VALUE_TONE[tone])}>
        {value}{unit && value !== '—' && <span className="ml-0.5 text-xs font-semibold text-muted-foreground">{unit}</span>}
      </span>
      {sub && <span data-slot="page-metric-sub" className={cn('truncate text-[10.5px] leading-snug font-medium', tone === 'bad' ? VALUE_TONE.bad : 'text-muted-foreground')}>{sub}</span>}
    </div>
  )
}

/**
 * Sayfanın kendisi: HTTP durum kodu (2xx yeşil · 3xx nötr · 4xx/5xx kırmızı) ve TARAMA süresi (sunucu ölçümü: ana sayfa +
 * kaynak doğrulaması birlikte — yalnız HTML yanıtı değil). Telefonda da iki sütun. Hiç kontrol edilmemiş izlemede ve
 * yapılandırma hatasında (istek hiç atılmadı — iki boş "—" kutusu yanıltırdı) çizilmez.
 */
export function PageMetricTiles({ monitor: m }) {
  const t = useT()
  if (!m.checked_at || m.status === 'CONFIG_ERROR') return null
  const code = m.http_status
  const tone = httpTone(code)
  const httpSub = code == null ? t('page.card.noResponse')
    : tone === 'bad' ? t('page.card.httpError') : tone === 'ok' ? t('page.card.httpOk') : t('page.card.httpRedirect')
  const scan = humanizeMs(m.response_ms)
  return (
    <MonitorCardMetrics className="mb-2.5 grid grid-cols-2 gap-2">
      <MetricTile metric="http" tone={tone} label={t('page.card.http')} value={code != null ? String(code) : '—'} sub={httpSub} />
      <MetricTile metric="scan" tone="neutral" label={t('page.card.scanTime')} value={scan ? scan.num : '—'} unit={scan?.unit}
        sub={m.timeout_ms ? t('page.card.timeoutPer', msText(m.timeout_ms)) : t('page.card.scanSub')} />
    </MonitorCardMetrics>
  )
}

/** Etiketler — ilk üçü rozet, fazlası "+N" (tam liste ekran okuyucuda). */
export function PageTags({ monitor, max = 3 }) {
  const t = useT()
  const tags = tagsOf(monitor)
  if (!tags.length) return null
  const shown = tags.slice(0, max)
  const rest = tags.slice(max)
  return (
    <span data-slot="page-tags" className="flex min-w-0 flex-wrap items-center gap-1">
      <span className="sr-only">{t('page.card.tags')}</span>
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
 * Son kontrol — göreli ("2 dk önce"), tam zaman ipucunda ve ekran okuyucuda; hiç kontrol edilmediyse bunu açıkça söyler
 * (eskiden alt çubuk boş kalıyordu). İpucu fareyle açılsın diye örtünün üstünde (CARD_LAYER).
 */
export function PageCheckedAt({ at, now = Date.now() }) {
  const t = useT()
  if (!at) return <span data-slot="page-checked-at" data-never="true">{t('page.card.neverChecked')}</span>
  const exact = formatDateSec(at)
  return (
    <SimpleTooltip content={exact}>
      <time data-slot="page-checked-at" dateTime={toUtc(at)} className={cn(CARD_LAYER, 'cursor-default')}>
        {relativeTime(at, t, now) || exact}
        <span className="sr-only"> ({exact})</span>
      </time>
    </SimpleTooltip>
  )
}
