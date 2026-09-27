import { BellOff, CircleStop, Pencil } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { currentOccurrence, durationMs, parseIso } from './maintenanceSchedule.js'
import { RecurringMark, StatusBadge, TargetsSummary, useScheduleText } from './maintenanceUi.jsx'

/** Aktif pencerenin süren oluşumu; sunucu "active" dese de istemci saniye farkıyla bulamazsa çapa + süre. */
export function activeInterval(w, now) {
  const occ = currentOccurrence(w, now)
  if (occ) return occ
  const start = parseIso(w.start_at)
  return start == null ? null : { start, end: start + durationMs(w) }
}

/**
 * "Şu an susturulanlar" şeridi — süren pencereler: ad, hedefler (+ takım), kalan süre + ilerleme çubuğu, "Şimdi bitir".
 * Sayfanın ilk sorusu ("şu an ne susturuluyor, ne zamana kadar") burada cevaplanır; kart TÜM kenarıyla vurgulanır
 * (sol şerit YOK — kullanıcı kuralı 2026-09-26). Test kancası: `data-slot="mw-active-card"` + `data-id`.
 */
export default function MaintenanceActiveStrip({ windows, now, canManage, onEndNow, onEdit, teamId, teamName }) {
  const t = useT()
  const { timeOf, minutes } = useScheduleText()
  if (!windows?.length) return null
  return (
    <section data-slot="mw-active-strip" aria-labelledby="mw-active-title" className="flex min-w-0 flex-col gap-2">
      <h3 id="mw-active-title" className="flex items-center gap-2 text-sm font-bold">
        <BellOff aria-hidden="true" className="size-4 text-success" />
        {t('mw.activeStripTitle')}
        <Badge variant="secondary" className="tabular-nums">{windows.length}</Badge>
      </h3>
      <ul className="grid list-none grid-cols-[repeat(auto-fit,minmax(min(340px,100%),1fr))] gap-2.5 p-0">
        {windows.map((w) => {
          const iv = activeInterval(w, now)
          const total = iv ? iv.end - iv.start : 0
          const elapsed = iv ? Math.min(total, Math.max(0, now - iv.start)) : 0
          const remainingMin = iv ? Math.max(0, Math.ceil((iv.end - now) / 60_000)) : null
          return (
            <li key={w.id}>
              <Card data-slot="mw-active-card" data-id={w.id}
                className="h-full gap-2.5 border-success/50 bg-success/[0.04] px-3.5 py-3 shadow-none dark:bg-success/[0.07]">
                <div className="flex min-w-0 items-start justify-between gap-2">
                  <span className="flex min-w-0 items-center gap-1.5 font-semibold [overflow-wrap:anywhere]">
                    {w.name}<RecurringMark w={w} />
                  </span>
                  <StatusBadge status="active" />
                </div>
                <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-[0.86em]">
                  <TargetsSummary w={w} />
                  {w.team_id != null && (
                    <TeamBadge teamId={w.team_id} teamName={w.team_id === teamId ? teamName : undefined} size={11} className="text-[0.95em]" />
                  )}
                </div>
                {iv && (
                  <div className="flex flex-col gap-1">
                    <div className="flex items-baseline justify-between gap-2 text-[0.84em]">
                      <span data-slot="mw-remaining" className="font-semibold text-success">{t('mw.remaining', minutes(remainingMin))}</span>
                      <span className="text-muted-foreground tabular-nums">{t('mw.endsAt', timeOf(iv.end, w.timezone))}</span>
                    </div>
                    <ProgressBar value={elapsed} max={total} size="sm" tone="ok" decorative />
                  </div>
                )}
                {canManage && (
                  <div className="-mb-0.5 flex flex-wrap justify-end gap-1.5">
                    <Button type="button" variant="ghost" size="sm" onClick={() => onEdit(w)}
                      aria-label={t('a11y.rowAction', w.name, t('mw.edit'))} className="text-muted-foreground">
                      <Pencil aria-hidden="true" />{t('mw.edit')}
                    </Button>
                    <Button type="button" variant="outline" size="sm" onClick={() => onEndNow(w)}
                      aria-label={t('a11y.rowAction', w.name, t('mw.endNow'))}
                      className="border-success/40 text-success hover:bg-success/10 hover:text-success">
                      <CircleStop aria-hidden="true" />{t('mw.endNow')}
                    </Button>
                  </div>
                )}
              </Card>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
