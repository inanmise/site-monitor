/**
 * Nöbet Rutinleri — bölüm kartı (ör. "Gün başlangıcı"). Tek sakin çerçeve, başlık satırı tam genişlik: ok + başlık (sarar) +
 * `çözülen/toplam` çipi + hedef saat çipi ("10:30'a kadar"; geçtiyse kırmızı) + "Bölümü tamamla" (tek tık, geri alınabilir).
 * Açık / kapalı durumu sayfa tutar (kişisel depoda). Bekleyen süzgeci açıkken çözülmüş satırlar gizlenir; bölümün hepsi
 * bittiyse kısa bir "tamam" satırı kalır.
 */
import { memo, useId } from 'react'
import { ChevronRight, CheckCheck, CircleCheck, Clock3 } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import { Spinner } from '@/components/shadcn/spinner'
import { isPendingVisible, istanbulInstantMs, itemTiming, keyOf, sectionStats } from './routineModel.js'

/** Bölüm gecikmiş mi: bugün, hedef saat geçti ve hedefli zorunlu madde bekliyor. */
export function sectionOverdue(section, { date, today, nowMs }) {
  if (!section?.window_to || date !== today) return false
  const to = istanbulInstantMs(today, section.window_to)
  if (to == null || !Number.isFinite(nowMs) || nowMs <= to) return false
  return (section.items || []).some((it) => itemTiming(it, { today, nowMs }).overdue)
}

function SectionCardImpl({
  kind, section, date, today, nowMs, editable, open, onOpenChange, pendingOnly, busy = false, onCompleteSection, renderRow,
  keepKeys = null,
}) {
  const t = useT()
  const uid = useId()
  const stats = sectionStats(section, date)
  const allDone = stats.total > 0 && stats.resolved >= stats.total
  const overdue = section.overdue === true || sectionOverdue(section, { date, today, nowMs })
  const pendingItems = (section.items || []).filter((it) => !it.removed && isPendingVisible(it))
  const visible = pendingOnly ? (section.items || []).filter((it) => isPendingVisible(it, keepKeys)) : (section.items || [])
  const headId = `${uid}-head`

  return (
    <Collapsible open={open} onOpenChange={(v) => onOpenChange(kind, section.id, v)} asChild>
      <section data-slot="or-section" data-section={section.id} data-kind={kind} data-done={allDone || undefined}
        aria-labelledby={headId}
        className="@container rounded-xl border bg-card text-card-foreground">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5 px-2 py-1.5 @md:px-3">
          <h3 id={headId} className="m-0 min-w-0 flex-1 basis-56 text-sm font-semibold">
            <CollapsibleTrigger asChild>
              <Button type="button" variant="ghost" data-slot="or-section-toggle"
                className="h-auto min-h-9 w-full justify-start gap-2 px-1.5 py-1.5 text-left text-sm font-semibold whitespace-normal pointer-coarse:min-h-10">
                <ChevronRight aria-hidden="true" className={cn('size-4 shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none', open && 'rotate-90')} />
                <span className="min-w-0 [overflow-wrap:anywhere]">{section.title}</span>
              </Button>
            </CollapsibleTrigger>
          </h3>
          <div className="flex flex-wrap items-center gap-1.5 pl-1">
            <Badge variant="outline" data-slot="or-section-count" data-done={allDone || undefined}
              className={cn('h-auto min-h-6 gap-1 rounded-md px-2 tabular-nums', allDone ? 'border-success/30 bg-success/10 text-success' : 'text-muted-foreground')}>
              {allDone && <CircleCheck aria-hidden="true" />}
              <span aria-hidden="true">{stats.resolved}/{stats.total}</span>
              <span className="sr-only">{t('or.sec.countAria', stats.resolved, stats.total)}</span>
            </Badge>
            {section.window_to && kind === 'DAY' && (
              <Badge variant="outline" data-slot="or-section-deadline" data-overdue={overdue || undefined}
                className={cn('h-auto min-h-6 gap-1 rounded-md px-2 tabular-nums whitespace-normal', overdue ? 'border-transparent bg-destructive/12 text-destructive' : 'text-muted-foreground')}>
                <Clock3 aria-hidden="true" />
                {overdue ? t('or.sec.deadlinePassed', section.window_to) : t('or.sec.deadline', section.window_to)}
              </Badge>
            )}
            {editable && pendingItems.length > 0 && (
              <Button type="button" variant="ghost" size="sm" data-slot="or-section-complete" disabled={busy}
                aria-label={t('or.sec.completeAria', section.title, pendingItems.length)}
                onClick={() => onCompleteSection(kind, section)}
                className="h-8 gap-1.5 px-2 text-xs text-primary pointer-coarse:h-10">
                {busy ? <Spinner className="size-3.5" /> : <CheckCheck aria-hidden="true" />}
                {t('or.sec.complete')}
              </Button>
            )}
          </div>
        </div>
        <CollapsibleContent>
          <div className="border-t px-1 pb-1.5 @md:px-1.5">
            {visible.length > 0 ? (
              <ul className="m-0 flex list-none flex-col divide-y divide-border/60 p-0" aria-label={section.title}>
                {visible.map((it) => <RowSlot key={keyOf(it)} item={it} renderRow={renderRow} />)}
              </ul>
            ) : (
              <p data-slot="or-section-all-done" className="m-0 flex items-center gap-1.5 px-3 py-3 text-xs text-muted-foreground">
                <CircleCheck aria-hidden="true" className="size-3.5 text-success" />
                {(section.items || []).length ? t('or.sec.allDone') : t('or.sec.empty')}
              </p>
            )}
          </div>
        </CollapsibleContent>
      </section>
    </Collapsible>
  )
}

function RowSlot({ item, renderRow }) {
  return renderRow(item)
}

/** Bölüm kartı: başka bölümün açılıp kapanması / toplu işlem bekleyişi bu bölümü yeniden çizmez. */
export default memo(SectionCardImpl)
