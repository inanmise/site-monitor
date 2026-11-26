/**
 * Nöbet Rutinleri — önceki nöbetin devri: özet (tamamlanan gün, atlanan / kaçırılan görev, açık sorun), devreden kişinin
 * notu, gün notları ve "Devraldım". Devralındıktan sonra tek satıra iner ("Devralındı · Ayşe · 09:12"); istenirse açılır.
 */
import { useState } from 'react'
import { ArrowRightLeft, CheckCircle2, ChevronDown, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/shadcn/card'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Spinner } from '@/components/shadcn/spinner'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { hmOf, shortDate } from './routineModel.js'
import { serverText } from './routineUi.jsx'

export default function HandoverPanel({ teamId, handover, isResponsibleMe = false, onAcked, onShowProblems }) {
  const t = useT()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(!handover?.acknowledged)
  const [daysOpen, setDaysOpen] = useState(false)
  if (!handover?.show) return null
  // Özet yoksa (okunamadı) sayılar "0" değil, BİLİNMİYOR: tek gri satır; alan yoksa "—"
  const known = handover.summary != null && typeof handover.summary === 'object'
  const s = known ? handover.summary : {}
  const n = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? '—' : Number(v))
  const names = (handover.responsible_names || []).filter(Boolean)
  const acked = !!handover.acknowledged

  const ack = async () => {
    setBusy(true)
    try {
      const res = await api.oncallRoutines.ackHandover(teamId, handover.period_key)
      if (res === null) return
      if (res?.success) {
        const h = res.data?.handover || res.data || {}
        onAcked?.({ ...handover, acknowledged: true, ack_by_name: h.ack_by_name ?? handover.ack_by_name, ack_at: h.ack_at ?? new Date().toISOString(), can_ack: false })
        setOpen(false)
        toast.success(t('or.hand.acked'))
      } else {
        toast.error(serverText(res, t, 'or.hand.ackFailed'), { details: res })
      }
    } catch (e) { toast.error(e?.message || t('or.hand.ackFailed')) } finally { setBusy(false) }
  }

  const range = `${shortDate(handover.from)} – ${shortDate(handover.to, true)}`
  return (
    <Card data-slot="or-handover" data-acked={acked ? 'true' : 'false'} className="gap-0 overflow-hidden rounded-xl py-0">
      <Collapsible open={open} onOpenChange={setOpen}>
        <div className={cn('flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3', !acked && 'bg-primary/5')}>
          <span aria-hidden="true" className={cn('inline-flex size-8 items-center justify-center rounded-lg',
            acked ? 'bg-success/15 text-success' : 'bg-primary/12 text-primary')}>
            {acked ? <CheckCircle2 className="size-4" /> : <ArrowRightLeft className="size-4" />}
          </span>
          <div className="flex min-w-0 flex-1 basis-48 flex-col">
            <p className="m-0 text-sm font-semibold [overflow-wrap:anywhere]">
              {names.length ? t('or.hand.title', names.join(', '), range) : t('or.hand.titleNoName', range)}
            </p>
            {acked && (
              <p className="m-0 text-xs text-muted-foreground" data-slot="or-handover-acked">
                {t('or.hand.ackedLine', handover.ack_by_name || '—', hmOf(handover.ack_at) || '')}
              </p>
            )}
            {!acked && !isResponsibleMe && handover.can_ack !== false && (
              <p className="m-0 text-xs text-muted-foreground">{t('or.hand.notYet')}</p>
            )}
          </div>
          <div className="flex items-center gap-1.5">
            {!acked && handover.can_ack !== false && (
              <Button type="button" size="sm" variant={isResponsibleMe ? 'default' : 'outline'} disabled={busy}
                data-slot="or-handover-ack" onClick={ack} className="pointer-coarse:h-10">
                {busy && <Spinner />}{t('or.hand.ack')}
              </Button>
            )}
            <CollapsibleTrigger asChild>
              <Button type="button" variant="ghost" size="icon" className="size-8 pointer-coarse:size-10"
                aria-label={open ? t('or.hand.collapse') : t('or.hand.expand')}>
                <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
              </Button>
            </CollapsibleTrigger>
          </div>
        </div>
        <CollapsibleContent>
          <div className="flex flex-col gap-3 border-t px-4 py-3">
            {handover.summary_ready === false && <p className="m-0 text-xs text-muted-foreground">{t('or.hand.live')}</p>}
            {known ? (
              <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0" data-slot="or-handover-stats">
                <li><Stat label={t('or.hand.days', n(s.completed), n(s.workdays))}
                  tone={Number(s.workdays) > 0 && Number(s.completed) >= Number(s.workdays) ? 'ok' : 'muted'} /></li>
                {Number(s.partial) > 0 && <li><Stat label={t('or.hand.partial', s.partial)} tone="warn" /></li>}
                {Number(s.incomplete || 0) + Number(s.missed || 0) > 0 && <li><Stat label={t('or.hand.missedDays', Number(s.incomplete || 0) + Number(s.missed || 0))} tone="bad" /></li>}
                {/* Henüz kapanmamış iş günleri (devir günü dönemin içinde ama açık): kaçırılmış SAYILMAZ, nötr */}
                {Number(s.open_days) > 0 && (
                  <li><Stat slot="or-handover-open-days" label={t(Number(s.open_days) === 1 ? 'or.hand.openDays.one' : 'or.hand.openDays', Number(s.open_days))} tone="muted" /></li>
                )}
                <li><Stat label={t('or.hand.skipped', n(s.skipped_items))} tone="muted" /></li>
                <li><Stat label={t('or.hand.missedItems', n(s.missed_items))} tone={Number(s.missed_items) > 0 ? 'bad' : 'muted'} /></li>
                <li><Stat label={t('or.hand.openProblems', n(s.problems_open))} tone={Number(s.problems_open) > 0 ? 'warn' : 'muted'} /></li>
              </ul>
            ) : (
              <p className="m-0 text-xs text-muted-foreground" data-slot="or-handover-unknown">{t('or.hand.summaryUnknown')}</p>
            )}
            {handover.note && (
              <div className="flex flex-col gap-1">
                <p className="m-0 text-xs font-medium text-muted-foreground">{t('or.hand.noteFrom', handover.note_by_name || '—')}</p>
                <p data-slot="or-handover-text" className="m-0 rounded-lg bg-muted/50 px-3 py-2 text-sm whitespace-pre-wrap [overflow-wrap:anywhere]">{handover.note}</p>
              </div>
            )}
            {Array.isArray(s.day_notes) && s.day_notes.length > 0 && (
              <Collapsible open={daysOpen} onOpenChange={setDaysOpen}>
                <CollapsibleTrigger asChild>
                  <Button type="button" variant="link" size="sm" className="h-auto p-0 text-xs pointer-coarse:min-h-10">
                    {daysOpen ? t('or.hand.hideDayNotes') : t('or.hand.showDayNotes', s.day_notes.length)}
                  </Button>
                </CollapsibleTrigger>
                <CollapsibleContent>
                  <ul className="m-0 mt-2 flex list-none flex-col gap-2 p-0">
                    {s.day_notes.map((n, i) => (
                      <li key={i} className="rounded-lg border px-3 py-2 text-xs">
                        <p className="m-0 font-medium">{shortDate(n.date, true)}{n.by ? ` · ${n.by}` : ''}</p>
                        <p className="m-0 mt-0.5 whitespace-pre-wrap text-muted-foreground [overflow-wrap:anywhere]">{n.note}</p>
                      </li>
                    ))}
                  </ul>
                </CollapsibleContent>
              </Collapsible>
            )}
            {Number(s.problems_open) > 0 && (
              <Button type="button" variant="outline" size="sm" className="w-fit pointer-coarse:h-10" onClick={onShowProblems}>
                <TriangleAlert aria-hidden="true" />{t('or.hand.showProblems')}
              </Button>
            )}
          </div>
        </CollapsibleContent>
      </Collapsible>
    </Card>
  )
}

function Stat({ label, tone, slot }) {
  return (
    <Badge variant="outline" data-stat={slot || undefined} className={cn('h-auto min-h-6 rounded-md px-2 font-normal tabular-nums whitespace-normal',
      tone === 'ok' && 'border-success/30 bg-success/10 text-success',
      tone === 'warn' && 'border-transparent bg-amber-500/15 text-amber-800 dark:text-amber-200',
      tone === 'bad' && 'border-transparent bg-destructive/12 text-destructive',
      tone === 'muted' && 'text-muted-foreground')}>
      {label}
    </Badge>
  )
}
