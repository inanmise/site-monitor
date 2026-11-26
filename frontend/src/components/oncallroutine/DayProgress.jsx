/**
 * Nöbet Rutinleri — günün ilerleme kartı: halka gösterge (çözülen / zorunlu), sayaçlar (atlanan, geciken, açık sorun) ve
 * durum. Yeşil YALNIZ gün tamamlandı ve her zorunlu görev yapıldıysa; bilinmeyen gri.
 */
import { memo } from 'react'
import { CircleAlert, SkipForward, TriangleAlert, CheckCheck, RotateCcw } from 'lucide-react'
import { Card } from '@/components/shadcn/card'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import CircularGauge from '../ui/CircularGauge.jsx'
import { hmOf, progressPct, progressState } from './routineModel.js'
import { DayStatusBadge } from './routineUi.jsx'

const GAUGE_COLOR = {
  done: 'var(--success)',
  partial: 'color-mix(in oklab, var(--success) 60%, var(--warning))',
  open: 'var(--primary)',
  unknown: 'var(--muted-foreground)',
}

function DayProgressImpl({ sheet, progress, onComplete, onReopen, canComplete, canReopen, completeSlot = 'or-complete-rail' }) {
  const t = useT()
  const state = progressState(sheet, progress)
  const pct = progressPct(progress)
  const resolved = Number(progress?.done || 0) + Number(progress?.skipped || 0)
  // Bilinmeyen zorunlu sayısı "0/0" değildir (Number(null) = 0 tuzağı)
  const required = progress?.required == null ? NaN : Number(progress.required)
  const day = sheet?.day || {}
  const completed = state === 'done' || state === 'partial'
  return (
    <Card data-slot="or-progress" data-state={state} className="gap-3 rounded-xl px-4 py-4">
      <div className="flex flex-wrap items-center gap-4">
        <div className="relative shrink-0">
          <CircularGauge value={state === 'unknown' ? 0 : (pct ?? 0)} size={76} stroke={7}
            color={state === 'open' && pct === 100 ? GAUGE_COLOR.open : GAUGE_COLOR[state]}
            label={Number.isFinite(required) ? t('or.prog.gaugeAria', resolved, required) : t('or.prog.unknown')} />
          <span className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center" aria-hidden="true">
            <span className="text-base leading-none font-semibold tabular-nums">{Number.isFinite(required) ? `${resolved}/${required}` : '—'}</span>
            {pct != null && <span className="mt-0.5 text-[10px] leading-none text-muted-foreground tabular-nums">{t('or.prog.pct', pct)}</span>}
          </span>
        </div>
        <div className="flex min-w-[9rem] flex-1 basis-0 flex-col gap-1.5">
          <DayStatusBadge status={sheet?.workday ? (day.status || 'NONE') : 'NOT_WORKDAY'} />
          <p className="m-0 text-sm font-medium" data-slot="or-progress-text">
            {Number.isFinite(required) && required > 0
              ? t('or.prog.text', resolved, required)
              : sheet?.workday === false ? t('or.prog.notWorkday')
                : Number.isFinite(required) ? t('or.prog.noRequired') : t('or.prog.unknown')}
          </p>
          {completed && (
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="or-progress-completed">
              {t('or.prog.completedBy', day.completed_by_name || '—', hmOf(day.completed_at) || '')}
              {day.completed_late && <span className="ml-1 font-medium text-amber-700 dark:text-amber-300">· {t('or.prog.late')}</span>}
            </p>
          )}
        </div>
      </div>
      <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0" aria-label={t('or.prog.countersLabel')}>
        <li><Chip icon={SkipForward} label={t('or.prog.skipped', progress?.skipped ?? 0)} /></li>
        <li><Chip icon={CircleAlert} danger={Number(progress?.overdue) > 0} slot="or-progress-overdue"
          label={t('or.prog.overdue', progress?.overdue ?? 0)} /></li>
        <li><Chip icon={TriangleAlert} warn={Number(progress?.problems_open) > 0}
          label={progress?.problems_open == null ? t('or.prog.problemsUnknown') : t('or.prog.problems', progress.problems_open)} /></li>
      </ul>
      {(canComplete || (completed && canReopen)) && (
        <div className="hidden @min-[900px]/page:flex">
          {completed ? (
            <Button type="button" variant="outline" size="sm" className="w-full pointer-coarse:h-10" data-slot="or-reopen-rail" onClick={onReopen}>
              <RotateCcw aria-hidden="true" />{t('or.complete.reopen')}
            </Button>
          ) : (
            <Button type="button" size="sm" className="w-full pointer-coarse:h-10" data-slot={completeSlot} onClick={onComplete}>
              <CheckCheck aria-hidden="true" />{t('or.complete.button')}
            </Button>
          )}
        </div>
      )}
    </Card>
  )
}

function Chip({ icon: Icon, label, danger = false, warn = false, slot }) {
  return (
    <Badge variant="outline" data-slot={slot}
      className={cn('h-auto min-h-6 gap-1 rounded-md px-2 font-normal tabular-nums whitespace-normal',
        danger ? 'border-transparent bg-destructive/12 text-destructive' : warn ? 'border-transparent bg-amber-500/15 text-amber-800 dark:text-amber-200' : 'text-muted-foreground')}>
      <Icon aria-hidden="true" />{label}
    </Badge>
  )
}

export default memo(DayProgressImpl)
