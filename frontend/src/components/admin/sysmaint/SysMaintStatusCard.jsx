import { useEffect, useState } from 'react'
import { BellOff, CalendarClock, CalendarPlus, Pencil, Power, RefreshCw, Square, TimerReset, Wrench, XCircle } from 'lucide-react'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import { messageFor, windowText } from '../../../utils/systemMaintenance.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { ImpactBanner } from './SysMaintFormDialogs.jsx'
import { hm, phaseMeta } from './sysmaintModel.js'

/**
 * DURUM kartı (2026-10-02, kullanıcı kararı) — Ayarlar → Sistem Bakımı'nın en üstü: Bakım yok / Planlandı (geri sayım
 * "X sa Y dk sonra") / Duyuruda / Uyarı aşamasında / BAKIMDA (bitişe kalan). Durum Badge'i + `data-status`; sol renk şeridi
 * YOK (durum rozet ve kartın tonlu dış çizgisiyle). Birincil eylemler duruma göre: Bakım planla · Hemen bakıma al ·
 * Düzenle · İptal et · Uzat · Hemen bitir. Geri sayım SUNUCU saatine göre, saniyelik tik YALNIZ bu kartta (sayfa yeniden
 * çizilmez). Telefonda eylemler tam genişlik ve ≥ 40 px.
 *
 * Test kancaları: `data-slot="sysmaint-status"` (`data-status`), `sysmaint-status-badge`, `sysmaint-countdown`,
 * `sysmaint-plan`, `sysmaint-start-now`, `sysmaint-edit`, `sysmaint-cancel`, `sysmaint-extend`, `sysmaint-end-now`.
 */
export default function SysMaintStatusCard({ current, impact, offsetMs = 0, busy = false, onPlan, onStartNow, onEdit, onCancel,
  onExtend, onEndNow, onRefresh, refreshing = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const [now, setNow] = useState(() => Date.now() + offsetMs)
  useEffect(() => {
    setNow(Date.now() + offsetMs)
    if (!current) return undefined
    const id = setInterval(() => setNow(Date.now() + offsetMs), 1000)
    return () => clearInterval(id)
  }, [current, offsetMs])

  const phase = current?.phase || 'none'
  const meta = phaseMeta(phase)
  const start = Date.parse(current?.start_at || ''), end = Date.parse(current?.end_at || '')
  const active = phase === 'active'
  const beforeStart = phase === 'planned' || phase === 'announced' || phase === 'warning'
  const left = active ? end - now : start - now
  const { h, m } = hm(left)
  const msg = messageFor(current, lang)

  return (
    <Card data-slot="sysmaint-status" data-status={phase}
      className={cn('gap-3 py-4', active && 'border-destructive/60', phase === 'warning' && 'border-amber-500/60')}>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4 sm:px-6">
        <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex min-w-0 items-start gap-3">
            <span aria-hidden="true" className={cn('inline-flex size-10 shrink-0 items-center justify-center rounded-lg',
              active ? 'bg-destructive/10 text-destructive' : beforeStart ? 'bg-amber-500/15 text-amber-700 dark:text-amber-400' : 'bg-muted text-muted-foreground')}>
              {active ? <Wrench className="size-5" /> : <CalendarClock className="size-5" />}
            </span>
            <div className="flex min-w-0 flex-col gap-1">
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant={meta.tone} data-slot="sysmaint-status-badge" data-status={phase} className="font-bold">{t(meta.key)}</Badge>
                {current?.immediate && <Badge variant="outline">{t('sysmaint.status.immediate')}</Badge>}
                {current?.mute_notifications && (
                  <Badge variant="outline" className="gap-1"><BellOff aria-hidden="true" className="size-3" />{t('sysmaint.status.muted')}</Badge>
                )}
              </div>
              {current ? (
                <>
                  <p className="m-0 text-sm font-semibold [overflow-wrap:anywhere]">{t('sysmaint.windowLine', windowText(current))}</p>
                  <p data-slot="sysmaint-countdown" className="m-0 text-sm text-muted-foreground tabular-nums">
                    {active ? t('sysmaint.status.endsIn', h, m) : t('sysmaint.status.startsIn', h, m)}
                  </p>
                  {msg && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{msg}</p>}
                </>
              ) : (
                <p className="m-0 text-sm text-muted-foreground">{t('sysmaint.status.noneDesc')}</p>
              )}
            </div>
          </div>
          {onRefresh && (
            <Button type="button" variant="ghost" size="sm" onClick={onRefresh} aria-busy={refreshing || undefined}
              className="min-h-10 self-start sm:min-h-9">
              <RefreshCw aria-hidden="true" className={cn(refreshing && 'animate-spin motion-reduce:animate-none')} />{t('sysmaint.refresh')}
            </Button>
          )}
        </div>

        <ImpactBanner impact={impact} tone={active ? 'warning' : 'info'} />

        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:justify-end [&>button]:min-h-10">
          {!current && (
            <>
              <Button type="button" variant="outline" onClick={onStartNow} disabled={busy} data-slot="sysmaint-start-now"
                className="w-full border-destructive/50 text-destructive hover:bg-destructive/10 hover:text-destructive sm:w-auto">
                <Power aria-hidden="true" />{t('sysmaint.action.startNow')}
              </Button>
              <Button type="button" onClick={onPlan} disabled={busy} data-slot="sysmaint-plan" className="w-full sm:w-auto">
                <CalendarPlus aria-hidden="true" />{t('sysmaint.action.plan')}
              </Button>
            </>
          )}
          {current && beforeStart && (
            <>
              <Button type="button" variant="outline" onClick={() => onCancel?.(current)} disabled={busy} data-slot="sysmaint-cancel"
                className="w-full sm:w-auto">
                <XCircle aria-hidden="true" />{t('sysmaint.action.cancel')}
              </Button>
              {phase === 'warning' && (
                <Button type="button" variant="outline" onClick={() => onExtend?.(current)} disabled={busy} data-slot="sysmaint-extend"
                  className="w-full sm:w-auto">
                  <TimerReset aria-hidden="true" />{t('sysmaint.action.extend')}
                </Button>
              )}
              <Button type="button" onClick={() => onEdit?.(current)} disabled={busy} data-slot="sysmaint-edit" className="w-full sm:w-auto">
                <Pencil aria-hidden="true" />{t('sysmaint.action.edit')}
              </Button>
            </>
          )}
          {current && active && (
            <>
              <Button type="button" variant="outline" onClick={() => onExtend?.(current)} disabled={busy} data-slot="sysmaint-extend"
                className="w-full sm:w-auto">
                <TimerReset aria-hidden="true" />{t('sysmaint.action.extend')}
              </Button>
              <Button type="button" variant="destructive" onClick={() => onEndNow?.(current)} disabled={busy} data-slot="sysmaint-end-now"
                className="w-full sm:w-auto">
                <Square aria-hidden="true" />{t('sysmaint.action.endNow')}
              </Button>
            </>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
