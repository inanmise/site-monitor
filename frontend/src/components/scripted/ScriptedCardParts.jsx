import { CircleAlert, CircleX, Gauge, Globe, TimerOff, Timer } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDateSec } from '../../api/client'
import { relativeTime } from '../admin/audit/auditFormat.js'
import { tagsOf } from '../../utils/monitorFilters.js'
import { toUtc } from '../../utils/localDay.js'
import { Badge } from '@/components/shadcn/badge'
import CopyButton from '../ui/CopyButton.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { MonitorCardMetrics, MonitorPendingText, CARD_LAYER, CARD_COPY } from '../monitoring/MonitorCard.jsx'
import { cn } from '@/lib/utils'
import { exitLabel, stuckLabel } from '../scriptedExitCodes.js'
import { intervalText } from '../ping/pingCardModel.js'
import { VersionChip } from './VersionTimeline.jsx'
import { checkCounts, durationAssessment, humanizeMs, runFailure, versionDrift } from './scriptedCardModel.js'

/** Kart durum sözlüğü (MonitorCard: up|down|warn|unknown) → sonuç panelinin tonu. */
const RESULT_TONE = { up: 'ok', down: 'bad', warn: 'warn', unknown: 'none' }
const PANEL = {
  ok: 'border-success/25 bg-success/5 dark:bg-success/10',
  bad: 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10',
  warn: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  none: 'bg-muted/40 dark:bg-muted/20',
}
const CHIP = 'h-5 rounded-md px-1.5 text-[10.5px] font-semibold'
// Açıklamalı çip tetiği: örtünün üstünde (CARD_LAYER); dokunmatikte HintPopover'ın 32 px tabanı ::after ile dikeyde 40 px'e
// genişler (yatayda genişlemez — yan yana çiplerin dokunma alanları çakışmasın).
const HINT_CHIP = cn(CARD_LAYER, 'pointer-coarse:after:absolute pointer-coarse:after:inset-x-0 pointer-coarse:after:-inset-y-1')
const msText = (ms) => {
  const p = humanizeMs(ms)
  return p ? `${p.num} ${p.unit}` : null
}

/**
 * Senaryonun hedefi — adın altında soluk, eş aralıklı host (+ farklı host sayısı) ve hedef ADRESİ kopyala düğmesi
 * (köşedeki düğme izlemenin BAĞLANTISINI kopyalar). Tam adres host metninin ipucunda ve detayda.
 */
export function ScenarioTarget({ target, rowLabel }) {
  const t = useT()
  if (!target) return null
  const more = target.hosts - 1
  return (
    <div data-slot="scripted-target" className="mt-1 flex min-w-0 items-center gap-1 text-xs text-muted-foreground">
      <Globe aria-hidden="true" className="size-3.5 shrink-0" />
      <span className="sr-only">{t('scripted.card.target')} </span>
      <span className="min-w-0 truncate font-mono" title={target.url}>{target.host}</span>
      {more > 0 && (
        <span data-slot="scripted-more-hosts" className="shrink-0 font-medium">
          <span aria-hidden="true">+{more}</span><span className="sr-only">{more === 1 ? t('scripted.card.moreHost1') : t('scripted.card.moreHosts', more)}</span>
        </span>
      )}
      <CopyButton value={target.url} label={t('a11y.rowAction', rowLabel, t('scripted.card.copyTarget'))} copiedLabel={t('scripted.card.targetCopied')}
        variant="ghost" buttonSize="icon-xs" size={12} className={CARD_COPY} />
    </div>
  )
}

/**
 * Senaryonun künye çipleri: sistem kapattı / hiç başarılı olmadı UYARILARI (önce — kaçırılmasın; açıklama dokunmatikte de
 * açılan HintPopover'da), script sürümü (VersionChip; son koşum eski sürümle yapıldıysa amber "son koşu v3" çipi) ve
 * koşu sıklığı. Test kancaları: `data-slot="scripted-chips"`, uyarı rozetleri eski adlarıyla (`autodisabled-badge`,
 * `never-succeeded-badge`), çip başına `data-chip="version|drift|interval"`.
 *
 * <p>`warningsOnly` (Kompakt kart, 2026-09-27): yalnız iki UYARI rozeti — sürüm / sürüm kayması / sıklık künye çipleri
 * Zengin'de kalır. Uyarı yoksa hiçbir şey çizilmez.
 */
export function ScenarioChips({ monitor: m, className, warningsOnly = false }) {
  const t = useT()
  const drift = warningsOnly ? null : versionDrift(m)
  const every = warningsOnly ? null : intervalText(m.interval_seconds, t)
  const version = warningsOnly ? null : m.script_version
  if (!m.disabled_reason && !m.never_succeeded && !version && !every) return null
  return (
    <div data-slot="scripted-chips" className={cn('flex min-w-0 flex-wrap items-center gap-1.5', className)}>
      {/* Sistem kapattı: kullanıcının kendi duraklattığı izlemeden AYRI görünür; tam sebep açıklamada ve detayda. */}
      {m.disabled_reason && (
        <HintPopover content={m.disabled_reason} triggerClassName={HINT_CHIP}>
          <Badge variant="outline" data-slot="autodisabled-badge"
            className={cn(CHIP, 'border-destructive/35 bg-destructive/10 tracking-[.03em] text-red-700 uppercase dark:border-red-400/40 dark:text-red-300')}>
            {t('scripted.autoDisabledBadge')}
          </Badge>
        </HintPopover>
      )}
      {/* Hiç yeşile dönmemiş: arıza değil yapılandırma/erişim sinyali (mor — arıza kırmızısından ayrışır). */}
      {m.never_succeeded && (
        <HintPopover content={t('scripted.neverSucceededHint')} triggerClassName={HINT_CHIP}>
          <Badge variant="outline" data-slot="never-succeeded-badge"
            className={cn(CHIP, 'border-violet-600/30 bg-violet-600/10 tracking-[.03em] text-violet-700 uppercase dark:border-violet-400/40 dark:text-violet-300')}>
            {t('scripted.neverSucceeded')}
          </Badge>
        </HintPopover>
      )}
      {version && (
        <VersionChip className={cn(CHIP, 'font-mono')}>
          <span className="sr-only">{t('scripted.sumVersion')} </span>v{m.script_version}
        </VersionChip>
      )}
      {drift && (
        <HintPopover content={t('scripted.verDriftHint', drift)} triggerClassName={HINT_CHIP}>
          <Badge variant="outline" data-chip="drift"
            className={cn(CHIP, 'border-amber-500/40 bg-amber-500/10 font-medium text-amber-800 dark:text-amber-300')}>
            {t('scripted.card.ranOn', drift)}
          </Badge>
        </HintPopover>
      )}
      {every && (
        <Badge variant="outline" data-chip="interval" className={cn(CHIP, 'font-medium text-muted-foreground')}>
          <Timer aria-hidden="true" />{every}
        </Badge>
      )}
    </div>
  )
}

const REASON_ICON = { check: CircleX, timeout: TimerOff, threshold: Gauge, error: CircleAlert, fail: CircleAlert }

/**
 * Nedenin başlık cümlesi (Zengin sonuç paneli ve Kompakt neden satırı AYNI metni kullanır). 'error' için başlık yok —
 * hatanın özü (ör. "TypeError: …") kendisi başlık; çıkış kodu etiketi Zengin'de panelin üst satırında.
 */
function failureHead(t, failure) {
  if (failure.kind === 'check') return t('scripted.card.failedCheck', failure.name)
  if (failure.kind === 'timeout') return failure.limit ? t('scripted.card.reasonTimeout', msText(failure.limit * 1000)) : t('scripted.card.reasonTimeoutNoLimit')
  if (failure.kind === 'threshold') return t('scripted.card.reasonThreshold')
  if (failure.kind === 'fail') return t('scripted.card.reasonFail')
  return null
}

/** Başarısız koşumun nedeni — tek satır (en fazla iki satıra sarar); yalnız-hover bilgi yok. */
function RunFailureLine({ failure }) {
  const t = useT()
  const Icon = REASON_ICON[failure.kind] || CircleAlert
  const head = failureHead(t, failure)
  const detail = failure.kind === 'check' ? null : failure.detail
  return (
    <div data-slot="scripted-reason" data-reason={failure.kind} role="note"
      className="mt-2 flex min-w-0 items-start gap-1.5 text-xs leading-snug font-medium text-destructive">
      <Icon aria-hidden="true" className="mt-px size-3.5 shrink-0" />
      <span className="min-w-0">
        {head && <span className="line-clamp-2 [overflow-wrap:anywhere]">{head}
          {failure.kind === 'check' && failure.more > 0 && (
            <span className="ml-1 font-normal text-muted-foreground">{t('scripted.card.moreChecks', failure.more)}</span>
          )}
        </span>}
        {detail && (
          <span data-slot="scripted-reason-detail"
            className={cn('line-clamp-2 [overflow-wrap:anywhere]', head && 'mt-0.5 font-normal text-muted-foreground')}>{detail}</span>
        )}
      </span>
    </div>
  )
}

/**
 * SON KOŞU — kartın kalbi. Doğrulama sayaçları büyük ve okunur ("12 / 14 doğrulama geçti") + oran çubuğu (shadcn
 * Progress → ui/Progress ProgressBar; el yapımı yüzde çubuğu YASAK), üst satırda k6 çıkış etiketi (ör. "Eşikler aşıldı")
 * ve takılınan faz çipleri, başarısızsa NEDEN satırı (düşen check'in adı / zaman aşımı / script hatasının özü).
 * NO_CHECKS: "hiçbir şey doğrulanmadı" cümlesi (amber). Hiç koşmamış: sade "ilk koşusu bekleniyor" paneli.
 * Test kancaları: `data-slot="scripted-result"` + `data-tone` (ok|bad|warn|none), `scripted-checks`, `scripted-exit`,
 * `scripted-stuck`, `scripted-reason` + `data-reason`.
 */
export function ScriptedRunResult({ monitor: m, status }) {
  const t = useT()
  const neverRun = !m.checked_at && (!m.status || m.status === 'unknown')
  if (neverRun) {
    return (
      <div data-slot="scripted-result" data-tone="none"
        className={cn('mb-2.5 rounded-lg border border-dashed px-3 py-2.5 text-xs text-muted-foreground', PANEL.none)}>
        <MonitorPendingText idle={t('scripted.card.awaitingFirstRun')} icon={null} />
      </div>
    )
  }
  const tone = RESULT_TONE[status] ?? 'none'
  const counts = checkCounts(m)
  const failure = runFailure(m)
  const passed = m.status === 'PASS'
  const exit = !passed && m.status !== 'TIMEOUT' ? exitLabel(t, m.exit_code) : null
  // Takılınan faz yalnız zaman aşımı / hata koşusunda anlamlı: doğrulaması düşen olağan bir koşuda k6 son fazı 0 ms
  // basabilir ve "yanıt alınırken takıldı" diye yanlış teşhis üretirdi (geçmiş tablosu da yalnız hatalı satırda gösterir).
  const stuck = m.status === 'TIMEOUT' || m.status === 'ERROR' ? stuckLabel(t, m) : null
  const noChecks = m.status === 'NO_CHECKS'
  return (
    <section data-slot="scripted-result" data-tone={tone} aria-label={t('scripted.card.lastRun')}
      className={cn('mb-2.5 min-w-0 rounded-lg border px-3 py-2.5', PANEL[tone])}>
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-2 gap-y-1">
        <span className="text-[10px] font-semibold tracking-[.06em] text-muted-foreground uppercase">{t('scripted.card.lastRun')}</span>
        {(exit || stuck) && (
          <span className="flex min-w-0 flex-wrap items-center justify-end gap-1">
            {exit && (
              <Badge variant="outline" data-slot="scripted-exit"
                className="h-5 max-w-full rounded-md border-destructive/35 bg-card px-1.5 text-[10.5px] font-semibold text-destructive">
                <span className="truncate">{exit}</span>
              </Badge>
            )}
            {stuck && (
              <Badge variant="outline" data-slot="scripted-stuck" className="h-5 max-w-full rounded-md bg-card px-1.5 text-[10.5px] font-medium text-muted-foreground">
                <span className="truncate">{stuck}</span>
              </Badge>
            )}
          </span>
        )}
      </div>
      {counts && counts.total > 0 && (
        <>
          <p data-slot="scripted-checks" className="mt-1 mb-1.5 flex min-w-0 flex-wrap items-baseline gap-x-1.5 tabular-nums">
            <span aria-hidden="true" className={cn('text-xl leading-tight font-bold', counts.failed ? 'text-destructive' : 'text-success')}>
              {counts.passed}<span className="text-sm font-semibold text-muted-foreground"> / {counts.total}</span>
            </span>
            <span aria-hidden="true" className="text-xs font-medium text-foreground">{t('scripted.card.checksPassed')}</span>
            {counts.failed > 0 && (
              <span aria-hidden="true" className="text-xs font-semibold text-destructive">· {t('scripted.card.failedCount', counts.failed)}</span>
            )}
            <span className="sr-only">{t('scripted.card.checksOf', counts.passed, counts.total)}</span>
          </p>
          <ProgressBar size="sm" className="h-1.5" decorative tone={counts.failed ? 'crit' : 'ok'} value={counts.passed} max={counts.total} />
        </>
      )}
      {noChecks && <p data-slot="scripted-no-checks" className="mt-1 text-xs leading-snug font-medium text-amber-800 dark:text-amber-300">{t('scripted.noChecksHint')}</p>}
      {!noChecks && passed && counts && counts.total === 0 && (
        <p className="mt-1 text-xs leading-snug text-muted-foreground">{t('scripted.card.thresholdsOnly')}</p>
      )}
      {failure && <RunFailureLine failure={failure} />}
    </section>
  )
}

const VALUE_TONE = { ok: 'text-success', warn: 'text-amber-700 dark:text-amber-400', bad: 'text-destructive', neutral: 'text-foreground' }

/**
 * KOMPAKT kartın son koşu özeti (2026-09-27, kart yoğunluğu) — Zengin'deki sonuç paneli + ölçü kutularının TEK satırlık
 * karşılığı: "SON KOŞU  12 / 14 doğrulama geçti · 2.4 s" (sayaç tonu düşen varsa kırmızı; süre yavaş koşum eşiğine göre
 * tonlu, aşınca görünür "Yavaş" rozeti, zaman aşımında kırmızı). Başarısızsa altında TEK satırlık neden (düşen check'in
 * adı / zaman aşımı / eşik / çıkış etiketi + hatanın özü); NO_CHECKS'te amber "hiçbir şey doğrulanmadı" satırı. Tam metin
 * dokun-gör açıklamada. Hiç koşmamış izlemede yalnız "ilk koşusu bekleniyor".
 * Test kancaları: `data-slot="scripted-compact-run"` + `data-tone`, `scripted-compact-checks`, `scripted-compact-duration`
 * (+ `data-tone`), neden `scripted-compact-reason` + `data-reason` (check|timeout|threshold|error|fail|no-checks).
 */
export function ScriptedCompactRun({ monitor: m, status }) {
  const t = useT()
  const neverRun = !m.checked_at && (!m.status || m.status === 'unknown')
  if (neverRun) {
    return (
      <p data-slot="scripted-compact-run" data-tone="none" className="mb-2.5 text-xs text-muted-foreground">
        <MonitorPendingText idle={t('scripted.card.awaitingFirstRun')} icon={null} />
      </p>
    )
  }
  const counts = checkCounts(m)
  const dur = durationAssessment(m)
  const durParts = humanizeMs(m.duration_ms)
  const failure = runFailure(m)
  let reason = null
  if (failure) {
    const head = failureHead(t, failure) || exitLabel(t, m.exit_code)
    const more = failure.kind === 'check' && failure.more > 0 ? ` ${t('scripted.card.moreChecks', failure.more)}` : ''
    const detail = failure.kind === 'check' ? null : failure.detail
    reason = { kind: failure.kind, tone: 'bad', text: [head ? head + more : null, detail].filter(Boolean).join(' — ') }
  } else if (m.status === 'NO_CHECKS') {
    reason = { kind: 'no-checks', tone: 'warn', text: t('scripted.noChecksHint') }
  }
  const hasChecks = counts && counts.total > 0
  return (
    <>
      {/* `@container`: dar kartta (Kompakt ızgara tabanı 250 px, 2026-09-27) satır sarar ve süre ikinci satıra iner — ayraç
          nokta o zaman satır başında sarkıyordu ("· 2.4 s"). Nokta yalnız satırın sığdığı genişlikte (≥17rem) çizilir. */}
      <div data-slot="scripted-compact-run" data-tone={RESULT_TONE[status] ?? 'none'}
        className="@container mb-2.5 flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5 tabular-nums">
        <span className="shrink-0 text-[10px] font-semibold tracking-[.06em] text-muted-foreground uppercase">{t('scripted.card.lastRun')}</span>
        {hasChecks && (
          <span data-slot="scripted-compact-checks" className="flex min-w-0 items-baseline gap-1">
            <span aria-hidden="true" className={cn('text-base leading-tight font-bold', counts.failed ? 'text-destructive' : 'text-success')}>
              {counts.passed}<span className="text-xs font-semibold text-muted-foreground"> / {counts.total}</span>
            </span>
            <span aria-hidden="true" className="truncate text-xs font-medium text-foreground">{t('scripted.card.checksPassed')}</span>
            <span className="sr-only">{t('scripted.card.checksOf', counts.passed, counts.total)}</span>
          </span>
        )}
        {durParts && (
          <span data-slot="scripted-compact-duration" data-tone={dur.tone} className="flex shrink-0 items-baseline gap-1.5">
            {hasChecks && <span aria-hidden="true" data-slot="scripted-compact-sep" className="hidden text-muted-foreground @min-[17rem]:inline">·</span>}
            <span className="sr-only">{t('scripted.card.duration')}: </span>
            <span className={cn('text-sm leading-tight font-bold', VALUE_TONE[dur.tone])}>
              {durParts.num}<span className="ml-0.5 text-xs font-semibold text-muted-foreground">{durParts.unit}</span>
            </span>
            {dur.tone === 'warn' && (
              <Badge variant="outline" data-slot="scripted-metric-verdict"
                className="h-4 self-center rounded px-1 text-[9.5px] font-bold tracking-[.04em] uppercase border-amber-500/40 bg-amber-500/15 text-amber-800 dark:text-amber-300">
                {t('scripted.card.slow')}
              </Badge>
            )}
          </span>
        )}
      </div>
      {reason && reason.text && <CompactReasonLine reason={reason} />}
    </>
  )
}

const REASON_TEXT = {
  bad: 'text-destructive hover:text-destructive dark:hover:text-destructive',
  warn: 'text-amber-800 hover:text-amber-800 dark:text-amber-300 dark:hover:text-amber-300',
}

/**
 * Kompakt neden satırı — TEK satır, kırpılır; tam metin dokun-gör açıklamada (HintPopover: fare, klavye ve dokunmatik —
 * yalnız-hover ipucu telefonda açılmazdı). Düğmenin erişilebilir adı tam metindir. Dokunmatikte 32 px taban ::after ile
 * dikeyde 40 px'e genişler.
 */
function CompactReasonLine({ reason }) {
  const Icon = reason.kind === 'no-checks' ? CircleAlert : (REASON_ICON[reason.kind] || CircleAlert)
  return (
    <HintPopover content={reason.text}
      // has-[>svg]:px-0: shadcn Button'ın ikonlu düğme dolgusu (has-[>svg]:px-3) p-0'a rağmen kalır → satır içeri kayardı.
      triggerClassName={cn(CARD_LAYER, 'mb-2 flex w-full min-w-0 justify-start gap-1.5 rounded-md text-left text-xs leading-snug font-medium has-[>svg]:px-0',
        REASON_TEXT[reason.tone], 'pointer-coarse:after:absolute pointer-coarse:after:inset-x-0 pointer-coarse:after:-inset-y-1')}>
      <Icon aria-hidden="true" className="size-3.5 shrink-0" />
      <span data-slot="scripted-compact-reason" data-reason={reason.kind} className="min-w-0 truncate">{reason.text}</span>
    </HintPopover>
  )
}
const TILE_TONE = {
  ok: '', neutral: '',
  warn: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  bad: 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10',
}

/** Tek ölçü kutusu (Ping kartıyla aynı dil): etiket · büyük değer + birim [+ hüküm rozeti] · alt satır. */
function MetricTile({ metric, tone, label, parts, verdict, srVerdict, sub }) {
  return (
    <div data-slot="scripted-metric" data-metric={metric} data-tone={tone}
      className={cn('flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/40 px-2.5 py-1.5 dark:bg-muted/20', TILE_TONE[tone])}>
      <span className="truncate text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
      <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
        <span data-slot="scripted-metric-value" className={cn('text-lg leading-tight font-bold tabular-nums', VALUE_TONE[tone])}>
          {parts ? <>{parts.num}<span className="ml-0.5 text-xs font-semibold text-muted-foreground">{parts.unit}</span></> : '—'}
        </span>
        {verdict && (
          <Badge variant="outline" data-slot="scripted-metric-verdict"
            className="h-4 rounded px-1 text-[9.5px] font-bold tracking-[.04em] uppercase border-amber-500/40 bg-amber-500/15 text-amber-800 dark:text-amber-300">
            {verdict}
          </Badge>
        )}
      </span>
      {(sub || srVerdict) && (
        <span data-slot="scripted-metric-sub" className={cn('truncate text-[10.5px] leading-snug font-medium', tone === 'neutral' || tone === 'ok' ? 'text-muted-foreground' : VALUE_TONE[tone])}>
          {srVerdict && <span className="sr-only">{srVerdict}{sub ? ' · ' : ''}</span>}{sub}
        </span>
      )}
    </div>
  )
}

/**
 * Ölçüler: koşum SÜRESİ (yavaş koşum eşiğine göre tonlu — eşik izlemenin kendi `slow_threshold_ms`'i, alarmla aynı kural;
 * aşınca görünür "Yavaş" rozeti; zaman aşımında kırmızı) ve istek başına ORTALAMA süre. Telefonda da iki sütun.
 * Hiç koşmamış izlemede çizilmez (iki boş "—" kutusu yer kaplardı; sonuç paneli durumu zaten söylüyor).
 */
export function ScriptedMetricTiles({ monitor: m }) {
  const t = useT()
  if (!m.checked_at && m.duration_ms == null) return null
  const dur = durationAssessment(m)
  const limit = dur.limitMs != null ? msText(dur.limitMs) : null
  const durSub = dur.tone === 'bad' ? (limit ? t('scripted.card.timeoutLimit', limit) : null)
    : dur.tone === 'warn' ? t('scripted.card.overLimit', limit)
    : dur.tone === 'ok' ? t('scripted.card.limit', limit)
    : t('scripted.card.wholeRun')
  const avg = humanizeMs(m.http_req_avg_ms)
  return (
    <MonitorCardMetrics className="mb-2.5 grid grid-cols-2 gap-2">
      <MetricTile metric="duration" tone={dur.tone} label={t('scripted.card.duration')} parts={humanizeMs(m.duration_ms)}
        verdict={dur.tone === 'warn' ? t('scripted.card.slow') : null} srVerdict={dur.tone === 'ok' ? t('scripted.card.normal') : null} sub={durSub} />
      <MetricTile metric="request" tone="neutral" label={t('scripted.card.avgRequest')} parts={avg}
        sub={avg ? t('scripted.card.perRequest') : t('scripted.card.notMeasured')} />
    </MonitorCardMetrics>
  )
}

/** Etiketler — ilk üçü rozet, fazlası "+N" (tam liste ekran okuyucuda). */
export function ScriptedTags({ monitor, max = 3 }) {
  const t = useT()
  const tags = tagsOf(monitor)
  if (!tags.length) return null
  const shown = tags.slice(0, max)
  const rest = tags.slice(max)
  return (
    <span data-slot="scripted-tags" className="flex min-w-0 flex-wrap items-center gap-1">
      <span className="sr-only">{t('scripted.card.tags')}</span>
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
 * Son koşu — göreli ("2 dk önce"), tam zaman ipucunda ve ekran okuyucuda; hiç koşmadıysa bunu açıkça söyler.
 * İpucu fareyle açılsın diye örtünün üstünde (CARD_LAYER).
 */
export function ScriptedLastRun({ at, now = Date.now() }) {
  const t = useT()
  if (!at) return <span data-slot="scripted-last-run" data-never="true">{t('scripted.card.neverRun')}</span>
  const exact = formatDateSec(at)
  return (
    <SimpleTooltip content={exact}>
      <time data-slot="scripted-last-run" dateTime={toUtc(at)} className={cn(CARD_LAYER, 'cursor-default')}>
        {relativeTime(at, t, now) || exact}
        <span className="sr-only"> ({exact})</span>
      </time>
    </SimpleTooltip>
  )
}
