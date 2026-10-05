import { useEffect, useState } from 'react'
import { ChevronRight, Timer, Bug } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Spinner, ProgressBar } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/shadcn/accordion'
import { cn } from '@/lib/utils'
import { CodeBlock, Kv, KvList, stateMeta } from '../http/diagnose/HttpDiagnoseParts.jsx'
import { detailRows, errorText, skipReasonText, stepLabel } from './netDiagnoseModel.js'

/**
 * Ping / Port / DNS uçtan uca tanılama penceresinin küçük sunum parçaları (2026-10-05) — adım hattı, adım zaman çizelgesi
 * (shadcn Accordion), ayrıntı listesi, koşu paneli, geçen süre sayacı, hız sınırı şeridi. Tümü shadcn + Tailwind; legacy
 * sınıf yok; durum SOL ŞERİTLE DEĞİL ikon + rozet + `data-status` ile. Telefonda hiçbir parça yatay taşmaz: uzun değerler
 * kırılır (`[overflow-wrap:anywhere]` / `break-all`), kod blokları satır sarar ve yalnız kendi kabında dikey kayar.
 */

/** Adım durumu → rozet tonu. */
export const STATE_TONE = { ok: 'success', fail: 'danger', warn: 'warning', skip: 'muted', pending: 'muted' }

/**
 * Kompakt adım hattı (Port yol kartı) — haplar SARAR (telefonda alt satıra iner); takılan adım kırmızı çerçeve.
 * Ekran okuyucu her hapı "ad: durum" diye okur.
 */
export function NetStepPipeline({ steps, label, className }) {
  const t = useT()
  if (!Array.isArray(steps) || !steps.length) return <p className="text-xs text-muted-foreground">{t('ndx.steps.none')}</p>
  return (
    <ol data-slot="ndx-pipeline" aria-label={label || t('ndx.steps.title')} className={cn('flex min-w-0 flex-wrap items-center gap-1', className)}>
      {steps.map((s, i) => {
        const m = stateMeta(s.state)
        const failed = s.state === 'fail'
        return (
          <li key={`${s.key}-${i}`} data-step={s.key} data-status={s.state} className="flex min-w-0 items-center gap-1">
            <span className={cn('inline-flex min-w-0 items-center gap-1 rounded-md border bg-card px-1.5 py-0.5 text-[11px]',
              s.state === 'skip' && 'opacity-60', failed && 'border-destructive/60 bg-destructive/10 font-semibold text-destructive')}>
              <m.Icon aria-hidden="true" className={cn('size-3 shrink-0', m.icon)} />
              <span className="min-w-0 truncate">{stepLabel(s.key, t)}</span>
              {s.ms != null && <span className={cn('tabular-nums', failed ? 'text-destructive' : 'text-muted-foreground')}>{s.ms} ms</span>}
              <span className="sr-only">: {t(`httpdx.stepState.${s.state}`)}</span>
            </span>
            {i < steps.length - 1 && <ChevronRight aria-hidden="true" className="size-3 shrink-0 text-muted-foreground/60" />}
          </li>
        )
      })}
    </ol>
  )
}

/** Ayrıntı satırları (detailRows çıktısı) — metin / satır listesi / kod bloğu. */
export function DetailList({ rows, className }) {
  if (!rows?.length) return null
  return (
    <KvList className={className}>
      {rows.map((r) => (
        <Kv key={r.key} label={r.label} mono={r.mono}>
          {r.kind === 'code' ? (
            <CodeBlock maxH="max-h-60" data-slot="ndx-code" data-key={r.key}>{r.value}</CodeBlock>
          ) : r.kind === 'list' ? (
            <ul data-key={r.key} className="flex min-w-0 flex-col gap-0.5">
              {r.items.map((x, i) => <li key={i} className="min-w-0 font-mono text-xs [overflow-wrap:anywhere]">{x}</li>)}
            </ul>
          ) : (
            <span data-key={r.key} className={cn('min-w-0 [overflow-wrap:anywhere]', r.tone === 'bad' && 'font-medium text-destructive')}>{r.value}</span>
          )}
        </Kv>
      ))}
    </KvList>
  )
}

/**
 * Adım zaman çizelgesi — her adım bir Accordion öğesi: başlıkta durum ikonu + ad + durum rozeti + süre (atlanan adımda
 * gerekçe); içerikte ayrıntılar (anahtar/değer), hata. Takılan / uyarı veren adımlar açık başlar. Dokunma hedefi ≥ 44 px.
 *
 * @param {Array}  steps   normalizeSteps çıktısı
 * @param {string} pathKey Port yol sekmesi ("monitor" | "alternate") — test kancası
 */
export function StepTimeline({ steps, pathKey = null }) {
  const t = useT()
  const [open, setOpen] = useState(() => steps
    .map((s, i) => (s.state === 'fail' || s.state === 'warn' ? `s-${i}` : null)).filter(Boolean))
  if (!steps.length) return <p className="text-xs text-muted-foreground">{t('ndx.steps.none')}</p>
  return (
    <Accordion type="multiple" value={open} onValueChange={setOpen} data-slot="ndx-steps" data-path={pathKey || undefined}
      className="min-w-0 rounded-lg border">
      {steps.map((s, i) => <StepItem key={`${s.key}-${i}`} step={s} value={`s-${i}`} />)}
    </Accordion>
  )
}

function StepItem({ step, value }) {
  const t = useT()
  const m = stateMeta(step.state)
  const rows = detailRows(step.detail, t, { skip: step.state === 'skip' })
  const reason = step.state === 'skip' ? skipReasonText(step.reason, t) : null
  const err = errorText(step.error)
  const empty = !rows.length && !err
  return (
    <AccordionItem value={value} data-slot="ndx-step" data-step={step.key} data-status={step.state} className="min-w-0">
      <AccordionTrigger data-slot="ndx-step-trigger" className="min-h-11 min-w-0 items-center gap-2 px-3 py-2.5 hover:no-underline">
        <span className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1 text-left">
          <m.Icon aria-hidden="true" className={cn('size-4 shrink-0', m.icon)} />
          <span className="min-w-0 text-sm font-semibold break-words">{stepLabel(step.key, t)}</span>
          <ToneBadge tone={STATE_TONE[step.state] || 'muted'} data-status={step.state}>{t(`httpdx.stepState.${step.state}`)}</ToneBadge>
          {step.ms != null && <span className="text-xs text-muted-foreground tabular-nums">{step.ms} ms</span>}
          {reason && <span data-slot="ndx-skip-reason" className="min-w-0 text-xs font-normal text-muted-foreground [overflow-wrap:anywhere]">· {reason}</span>}
        </span>
      </AccordionTrigger>
      <AccordionContent className="flex min-w-0 flex-col gap-3 px-3">
        {empty && <p className="text-xs text-muted-foreground">{reason || t('ndx.steps.noDetail')}</p>}
        <DetailList rows={rows} />
        {err && (
          <div data-slot="ndx-step-error" className="flex min-w-0 flex-col gap-1">
            <span className="flex items-center gap-1.5 text-xs font-semibold text-destructive"><Bug aria-hidden="true" className="size-3.5" />{t('ndx.steps.error')}</span>
            <CodeBlock maxH="max-h-40" copy={err} copyLabel={t('ndx.steps.copyError')} className="text-destructive">{err}</CodeBlock>
          </div>
        )}
      </AccordionContent>
    </AccordionItem>
  )
}

/** Koşuyor: geçen saniye (yalnız sayaç her saniye çizilir) + belirsiz çubuk + sonuç iskeleti. */
export function RunningPanel({ since, hint }) {
  const t = useT()
  return (
    <div data-slot="ndx-running" className="flex min-w-0 flex-col gap-4">
      <div className="flex min-w-0 items-start gap-3 rounded-lg border bg-muted/40 px-3 py-3">
        <Spinner size={18} inline decorative className="mt-0.5" />
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span role="status" className="text-sm font-medium">{t('httpdx.running.title')}</span>
          {hint && <span className="text-xs text-muted-foreground">{hint}</span>}
          <span className="text-xs text-muted-foreground">{t('httpdx.running.cancelNote')}</span>
        </div>
        <Elapsed since={since} />
      </div>
      <ProgressBar decorative size="sm" />
      <div className="grid grid-cols-1 gap-3" aria-hidden="true">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    </div>
  )
}

/** Geçen süre sayacı — saniyede bir YALNIZ kendisi çizilir (pencerenin geri kalanı değil). */
export function Elapsed({ since }) {
  const t = useT()
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])
  const s = Math.max(0, Math.floor((now - (since || now)) / 1000))
  return <span data-slot="ndx-elapsed" data-seconds={s} className="shrink-0 text-sm font-semibold tabular-nums">{t('httpdx.running.elapsed', s)}</span>
}

/** Sunucu penceresi 60 sn (kullanıcı ve izleme başına dakikada 6) — Retry-After gelmez, tam pencere beklenir. */
export const RATE_WINDOW_S = 60

/** 429 şeridi: dostça açıklama + geri sayım + süre dolunca "yeniden dene". */
export function RateLimitStrip({ onRetry }) {
  const t = useT()
  const [left, setLeft] = useState(RATE_WINDOW_S)
  useEffect(() => {
    const id = setInterval(() => setLeft((s) => (s <= 1 ? 0 : s - 1)), 1000)
    return () => clearInterval(id)
  }, [])
  const ready = left <= 0
  return (
    <div data-slot="ndx-rate-limit">
      <AlertBanner tone="warning" icon={Timer} role="alert" className="mb-0" title={t('httpdx.rateLimited')}
        actions={ready ? <Button type="button" size="sm" className="pointer-coarse:h-10" onClick={onRetry}>{t('httpdx.err.retry')}</Button> : null}>
        <span aria-live="polite" className={cn(!ready && 'tabular-nums')}>{ready ? t('httpdx.rateLimitedReady') : t('ndx.rateLimitedBody', left)}</span>
      </AlertBanner>
    </div>
  )
}
