import { useState } from 'react'
import { ChevronDown, MessageSquareText, PanelRightOpen } from 'lucide-react'
import TeamBadge from '../../ui/TeamBadge.jsx'
import ChangeDiffChips from '../../history/ChangeDiffChips.jsx'
import { parseChanges } from '../../history/changeFields.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import ChangeDiff from './ChangeDiff.jsx'
import { ActorBadge, EventBadge, IpCopy, KindIcon, MonitorName, TimeStamp, kindLabel } from './changeParts.jsx'
import { activeToggle, countChanges, dayLabel, groupByDay, isDeleted, linkFor, resourceName, rowId } from './changeModel.js'

/**
 * İzleme Değişiklikleri — GÜNE GÖRE gruplanmış zaman çizelgesi (2026-09-28 yeniden tasarım; eski tablo + telefon kartı
 * ikilisinin yerini aldı). Her gün yapışkan bir başlık (telefonda üst çubuğun altında: `top-14`), altında tek bir
 * kart içinde satırlar. Aynı yerleşim her genişlikte — mobil-önce, geniş ekranda künye tek satıra açılır.
 *
 * Satır: olay rozeti (+ duraklatma/sürdürme), izleme (tür ikonu + derin bağlantı; silinmişse düz metin + "silinmiş"),
 * saat (dokun-gör tam zaman), kim · tür · takım · IP künyesi, değişiklik nedeni, alan çipleri ve satır içi açılan
 * FARK ("Farkı göster"). Satırın geri kalanına tıklamak ayrıntı panelini açar: "stretched button" deseni
 * (monitoring/MonitorCard) — düğmenin `::after` örtüsü satırı kaplar, bağlantı/rozet/fark düğmesi `relative z-10`
 * ile örtünün üstünde. Sol renk şeridi YOK: durum rozetle.
 *
 * Test kancaları: kap `data-slot="chg-timeline"` (`aria-busy`, `data-stale`), gün `data-slot="chg-day"` +
 * `data-day=<YYYY-MM-DD>`, satır `data-chg-row=<id>`, künye `data-slot="chg-row-meta"`, ayrıntıyı açan öğe
 * `data-open-detail`.
 */

/** Örtünün üstünde kalması gereken etkileşimli öğeler. */
const LAYER = 'relative z-10'

function ChangeItem({ r, t, now, onOpen, selected }) {
  const [open, setOpen] = useState(false)
  const id = rowId(r)
  const name = resourceName(r)
  const deleted = isDeleted(r)
  const toggle = activeToggle(r.changes)
  const fields = parseChanges(r.changes).length
  const link = linkFor(r)

  return (
    <li data-chg-row={id} data-state={selected ? 'selected' : undefined}
      className="relative min-w-0 px-3 py-3 transition-colors hover:bg-muted/40 motion-reduce:transition-none data-[state=selected]:bg-primary/5 sm:px-4">
      <Collapsible open={open} onOpenChange={setOpen} className="flex min-w-0 flex-col gap-1.5">
        {/* 1. satır: ne oldu + hangi izleme + ne zaman */}
        <div className="flex min-w-0 items-start gap-2">
          <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1">
            <EventBadge t={t} ev={r.event_type} />
            {toggle && <EventBadge t={t} ev={toggle} />}
            <span className="flex min-w-0 max-w-full items-center gap-1.5">
              <KindIcon kind={r.kind} />
              <span className={cn('min-w-0', link && LAYER)}>
                {/* Telefonda bağlantının dokunma alanı görünmez ::after ile dikeyde 40 px'i aşar (satır yüksekliği değişmez) */}
                <MonitorName r={r} link={link} wrap className="relative max-md:after:absolute max-md:after:inset-x-0 max-md:after:-inset-y-2.5" />
              </span>
              {deleted && r.event_type !== 'DELETE' && (
                <Badge variant="outline" data-slot="chg-deleted" className="shrink-0 font-normal text-muted-foreground">{t('chg.deletedMonitor')}</Badge>
              )}
            </span>
          </div>
          <span className="-mt-1 -mr-1.5 flex shrink-0 items-center">
            <TimeStamp at={r.at} t={t} now={now} />
            {/* Stretched button: `::after` örtüsü satırın TAMAMINI kaplar (en yakın konumlu ata `li`) — satırın boş
                alanına dokunmak ayrıntı panelini açar; klavyede odak halkası tüm satırda çizilir. Düğme statik kalmalı
                (relative/absolute OLMAZ), yoksa örtü satıra değil düğmeye göre konumlanır. */}
            <Button type="button" variant="ghost" size="icon" data-open-detail="" aria-haspopup="dialog"
              aria-label={t('a11y.rowAction', name, t('chg.details'))} title={t('chg.details')}
              onClick={() => onOpen(r)}
              className={cn('size-8 text-muted-foreground hover:text-foreground max-md:size-10',
                'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50 focus-visible:after:ring-inset')}>
              <PanelRightOpen aria-hidden="true" />
            </Button>
          </span>
        </div>

        {/* 2. satır: kim · tür · takım · IP */}
        <div data-slot="chg-row-meta" className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1 text-xs text-muted-foreground">
          <span className="flex min-w-0 max-w-full text-foreground"><ActorBadge r={r} t={t} /></span>
          <span aria-hidden="true">·</span>
          <span>{kindLabel(t, r.kind)}</span>
          {r.team_name && <>
            <span aria-hidden="true">·</span>
            {/* Telefonda dokunma alanı görünmez ::after ile ≥ 40 px (satır yüksekliği değişmez) */}
            <span className={LAYER}><TeamBadge teamId={r.team_id} teamName={r.team_name} size={11} className="max-md:after:absolute max-md:after:inset-x-0 max-md:after:-inset-y-3" /></span>
          </>}
          {(r.ip_address || r.identity_masked === true) && (
            <span className="hidden items-center gap-1 md:inline-flex">
              <span aria-hidden="true">·</span>
              <span className={LAYER}><IpCopy ip={r.ip_address} t={t} masked={r.identity_masked === true} /></span>
            </span>
          )}
        </div>

        {r.note && (
          <p className="m-0 flex min-w-0 items-start gap-1.5 text-sm text-foreground/90">
            <MessageSquareText aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
            <span className="min-w-0 break-words">
              <span className="sr-only">{t('chg.noteTitle')}: </span>{r.note}
            </span>
          </p>
        )}

        {/* 3. satır: alan çipleri (kapalıyken özet) + "Farkı göster" */}
        {fields > 0 && (
          <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1.5">
            {!open && <ChangeDiffChips t={t} changes={r.changes} limit={3} wrap className="min-w-0" />}
            <CollapsibleTrigger asChild>
              <Button type="button" variant="ghost" size="sm" data-diff-toggle=""
                className={cn(LAYER, 'h-8 gap-1 px-2 text-xs text-muted-foreground hover:text-foreground max-md:h-10')}>
                <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
                {open ? t('chg.hideDiff') : t('chg.showDiff', fields)}
              </Button>
            </CollapsibleTrigger>
          </div>
        )}
        <CollapsibleContent className={cn(LAYER, 'min-w-0')}>
          <ChangeDiff changes={r.changes} t={t} dense className="pt-1" />
        </CollapsibleContent>
      </Collapsible>
    </li>
  )
}

export default function ChangeTimeline({ rows, t, now, lang, onOpen, selectedId, loading, stale }) {
  const groups = groupByDay(rows)
  return (
    <div data-slot="chg-timeline" aria-busy={loading || undefined} data-stale={stale ? '' : undefined}
      className={cn('flex min-w-0 flex-col gap-4 transition-opacity motion-reduce:transition-none', stale && 'opacity-60')}>
      {groups.map(g => {
        const { label, sub } = dayLabel(g.key, now, lang, t)
        const headId = `chg-day-${g.key}`
        return (
          <section key={g.key} data-slot="chg-day" data-day={g.key} aria-labelledby={headId} className="min-w-0">
            <h3 id={headId}
              className="sticky top-14 z-20 m-0 flex items-baseline gap-2 bg-card/95 px-1 py-2 text-sm font-semibold backdrop-blur-sm md:top-0">
              <span>{label}</span>
              {sub && <span className="font-normal text-muted-foreground">{sub}</span>}
              <span className="ml-auto text-xs font-normal text-muted-foreground tabular-nums">{countChanges(t, g.rows.length)}</span>
            </h3>
            <Card className="gap-0 overflow-hidden py-0 shadow-none">
              <ol className="m-0 list-none divide-y p-0">
                {g.rows.map(r => (
                  <ChangeItem key={rowId(r)} r={r} t={t} now={now} onOpen={onOpen} selected={selectedId === rowId(r)} />
                ))}
              </ol>
            </Card>
          </section>
        )
      })}
    </div>
  )
}

/** İlk yükleme iskeleti — gerçek gün başlığı + satır ölçüsünde (veri gelince sayfa zıplamasın). */
export function ChangeTimelineSkeleton({ t }) {
  return (
    <div data-slot="chg-timeline" aria-busy="true" className="flex min-w-0 flex-col gap-4">
      <span className="sr-only" role="status">{t('modal.loading')}</span>
      {[3, 2].map((n, gi) => (
        <section key={gi} className="min-w-0">
          <div className="flex items-center gap-2 px-1 py-2"><Skeleton className="h-4 w-24" /><Skeleton className="ml-auto h-3 w-16" /></div>
          <Card className="gap-0 overflow-hidden py-0 shadow-none">
            {Array.from({ length: n }, (_, i) => (
              <div key={i} data-skeleton="" className="flex flex-col gap-2 border-b px-3 py-3 last:border-b-0 sm:px-4">
                <div className="flex items-center gap-2"><Skeleton className="h-5 w-20" /><Skeleton className="h-4 flex-1 sm:max-w-64" /><Skeleton className="ml-auto h-4 w-12" /></div>
                <div className="flex items-center gap-2"><Skeleton className="size-5 rounded-full" /><Skeleton className="h-3 w-40" /></div>
                <div className="flex gap-1"><Skeleton className="h-5 w-28" /><Skeleton className="h-5 w-24" /></div>
              </div>
            ))}
          </Card>
        </section>
      ))}
    </div>
  )
}
