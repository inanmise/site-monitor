import { useMemo } from 'react'
import { CircleCheck, CircleX, ListChecks, Workflow, OctagonPause } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { humanizeMs } from './scriptedCardModel.js'
import { ROOT_STEP, normalizeSteps, parseChecks, stepState, stoppedAfter } from './runDetailModel.js'

const msText = (ms) => {
  const p = humanizeMs(ms)
  return p ? `${p.num} ${p.unit}` : null
}
// Ölçü çipi: sarılır (whitespace-normal), hap köşesi yok — uzun metin kesilmez, kart dışına taşmaz.
const CHIP = 'h-auto min-h-5 overflow-visible rounded-md px-1.5 py-0 text-[11px] font-medium whitespace-normal [overflow-wrap:anywhere]'

/**
 * Koşumun ADIMLARI (k6 `group`) — ayrıntı penceresi (CheckDetail) ve form test paneli AYNI bileşeni kullanır
 * (2026-10-31, k6r D4; sözleşme: fe1 test panelinde `<StepList steps truncated checksJson status />` olarak çağırır).
 *
 * <ul>
 *   <li>`steps` (dizi ya da sunucunun `steps_json` metni) varsa adım adım: ad (grup yolu " › "; kök istekler "Kök"),
 *       süre, istek sayısı (+ başarısız), p95, doğrulama ✓/✗ sayısı, en sık k6 hata kodu. Durum ikonunun anlamı ekran
 *       okuyucu metninde de söylenir (renk + ikon tek başına anlam taşımaz).</li>
 *   <li>`steps` yoksa (eski satır / eski sunucu) `checksJson`'dan düz doğrulama listesi — bugünkü görünüm, ✓/✗ glifleri
 *       artık ekran okuyucuya "geçti / başarısız" der (E1).</li>
 *   <li>ERROR / TIMEOUT'ta "Senaryo şu adımdan sonra durdu" (son başarılı adım) notu.</li>
 * </ul>
 * Gösterilecek bir şey yoksa hiçbir şey çizilmez. Test kancaları: `data-slot="step-list"` + `data-mode` (steps|checks),
 * satır `data-slot="step-row"` + `data-state` (ok|fail) + `data-root`, `step-stopped`, `step-truncated`, `check-row`.
 *
 * @param {Array|string|null} steps      sunucunun adım listesi
 * @param {boolean}           truncated  sunucu adım listesini kırptı mı
 * @param {string|Array}      checksJson eski satırın doğrulama listesi (`checks_json`)
 * @param {string}            status     PASS|FAIL|ERROR|TIMEOUT|NO_CHECKS
 */
export default function StepList({ steps, truncated = false, checksJson, status, className }) {
  const t = useT()
  const list = useMemo(() => normalizeSteps(steps), [steps])
  const checks = useMemo(() => (list && list.length ? null : parseChecks(checksJson)), [list, checksJson])
  const stopped = list && list.length ? stoppedAfter(list, status) : null

  if (list && list.length) {
    return (
      <section data-slot="step-list" data-mode="steps" aria-label={t('scripted.steps.title')}
        className={cn('@container flex min-w-0 flex-col gap-1.5', className)}>
        <h4 className="m-0 flex items-center gap-1.5 text-[.92em] font-semibold">
          <Workflow aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          {t('scripted.steps.title')}
          <span className="font-normal text-muted-foreground tabular-nums">({list.length})</span>
        </h4>
        <ol className="m-0 flex list-none flex-col gap-1 p-0">
          {list.map((s, i) => <StepRow key={`${i}:${s.name}`} t={t} step={s} />)}
        </ol>
        {stopped && (
          <p data-slot="step-stopped" data-kind={stopped.kind} className="m-0 flex items-start gap-1.5 text-xs font-medium text-amber-800 dark:text-amber-300">
            <OctagonPause aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span className="min-w-0 [overflow-wrap:anywhere]">
              {stopped.kind === 'after'
                ? t('scripted.steps.stoppedAfter', stopped.name === ROOT_STEP ? t('scripted.steps.root') : stopped.name)
                : t('scripted.steps.stoppedAtStart')}
            </span>
          </p>
        )}
        {truncated && <p data-slot="step-truncated" className="m-0 text-xs text-muted-foreground">{t('scripted.steps.truncated')}</p>}
      </section>
    )
  }

  if (checks && checks.items.length) {
    return (
      <section data-slot="step-list" data-mode="checks" aria-label={t('scripted.steps.checksTitle')}
        className={cn('flex min-w-0 flex-col gap-1', className)}>
        <h4 className="m-0 flex items-center gap-1.5 text-[.92em] font-semibold">
          <ListChecks aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{t('scripted.steps.checksTitle')}
        </h4>
        <ul className="m-0 list-none p-0">
          {checks.items.map((c, i) => (
            <li key={i} data-slot="check-row" data-passed={c.passed ? 'true' : 'false'}
              className="flex min-w-0 items-start gap-1.5 border-b border-border py-1 text-[.92em] last:border-b-0">
              <span aria-hidden="true" className={cn('shrink-0 font-bold', c.passed ? 'text-success' : 'text-destructive')}>{c.passed ? '✓' : '✗'}</span>
              <span className="sr-only">{c.passed ? t('scripted.steps.srOk') : t('scripted.steps.srBad')}: </span>
              <span className="min-w-0 [overflow-wrap:anywhere]">{c.name}</span>
            </li>
          ))}
        </ul>
        {checks.truncated && <p data-slot="step-truncated" className="m-0 text-xs text-muted-foreground">{t('scripted.steps.checksTruncated')}</p>}
      </section>
    )
  }
  return null
}

function StepRow({ t, step: s }) {
  const state = stepState(s)
  const root = s.name === ROOT_STEP
  const Icon = state === 'fail' ? CircleX : CircleCheck
  const dur = msText(s.durationMs)
  const p95 = msText(s.p95Ms)
  const totalChecks = s.checksPassed + s.checksFailed
  return (
    <li data-slot="step-row" data-state={state} data-root={root ? 'true' : undefined}
      className={cn('flex min-w-0 flex-wrap items-start gap-x-3 gap-y-1 rounded-md border px-2.5 py-1.5 text-xs',
        state === 'fail' ? 'border-destructive/30 bg-destructive/5 dark:bg-destructive/10' : 'bg-muted/30')}>
      <span className="flex min-w-0 flex-[1_1_14rem] items-start gap-1.5">
        <Icon aria-hidden="true" className={cn('mt-px size-4 shrink-0', state === 'fail' ? 'text-destructive' : 'text-success')} />
        <span className="sr-only">{state === 'fail' ? t('scripted.steps.srBad') : t('scripted.steps.srOk')}: </span>
        <span data-slot="step-name" className={cn('min-w-0 font-semibold [overflow-wrap:anywhere]', root && 'font-medium text-muted-foreground italic')}>
          {root ? t('scripted.steps.root') : s.name}
        </span>
      </span>
      <span className="flex min-w-0 flex-wrap items-center gap-1">
        {dur && <Badge variant="outline" data-chip="duration" className={cn(CHIP, 'tabular-nums')}>{t('scripted.steps.duration', dur)}</Badge>}
        {s.requests != null && (
          <Badge variant="outline" data-chip="requests" className={cn(CHIP, 'tabular-nums', s.failedRequests > 0 && 'border-destructive/40 text-destructive')}>
            {s.failedRequests > 0 ? t('scripted.steps.requestsBad', s.requests, s.failedRequests) : t('scripted.steps.requests', s.requests)}
          </Badge>
        )}
        {p95 && <Badge variant="outline" data-chip="p95" className={cn(CHIP, 'tabular-nums text-muted-foreground')}>{t('scripted.steps.p95', p95)}</Badge>}
        {totalChecks > 0 && (
          <Badge variant="outline" data-chip="checks" className={cn(CHIP, 'tabular-nums', s.checksFailed > 0 ? 'border-destructive/40 text-destructive' : 'text-success')}>
            {s.checksFailed > 0 ? t('scripted.steps.checksBad', s.checksFailed, totalChecks) : t('scripted.steps.checksPassed', s.checksPassed)}
          </Badge>
        )}
        {s.errorCode != null && (
          <Badge variant="outline" data-chip="error-code" className={cn(CHIP, 'font-mono text-destructive')}>{t('scripted.steps.k6Code', s.errorCode)}</Badge>
        )}
      </span>
    </li>
  )
}
