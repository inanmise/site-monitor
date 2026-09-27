import { useId, useState } from 'react'
import { Siren, UserCheck, CheckCircle2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/shadcn/tabs'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import IncidentCard from './IncidentCard.jsx'
import { LANES, laneOf } from './incidentsModel.js'

const LANE_META = {
  open:     { Icon: Siren,        labelKey: 'incov.lane.open',     emptyKey: 'incov.laneEmpty.open',     ink: 'text-destructive' },
  ack:      { Icon: UserCheck,    labelKey: 'incov.lane.ack',      emptyKey: 'incov.laneEmpty.ack',      ink: 'text-sky-700 dark:text-sky-300' },
  resolved: { Icon: CheckCircle2, labelKey: 'incov.lane.resolved', emptyKey: 'incov.laneEmpty.resolved', ink: 'text-success' },
}

function LaneHeader({ lane, count, id }) {
  const t = useT()
  const m = LANE_META[lane]
  return (
    <div className="flex items-center gap-2 px-1 pb-2">
      <m.Icon aria-hidden="true" className={cn('size-4 shrink-0', m.ink)} />
      <h3 id={id} className="text-sm font-bold">{t(m.labelKey)}</h3>
      <Badge variant="secondary" data-slot="lane-count" className="ml-auto h-5 min-w-5 justify-center px-1.5 tabular-nums">{count}</Badge>
    </div>
  )
}

function LaneBody({ lane, rows, nowMs, onOpen, selectedId, wide = false }) {
  const t = useT()
  if (rows.length === 0) {
    return <p data-slot="lane-empty" className="rounded-lg border border-dashed px-3 py-6 text-center text-xs text-muted-foreground">{t(LANE_META[lane].emptyKey)}</p>
  }
  return (
    // wide (tablet, iki sütuna yayılan şerit): kartlar iki sütunda; geniş ekranda tek sütun
    <ul className={cn('m-0 flex list-none flex-col gap-2 p-0', wide && 'md:grid md:grid-cols-2 md:items-start lg:flex lg:flex-col')}>
      {rows.map((inc) => (
        <li key={inc.id} className="min-w-0">
          <IncidentCard inc={inc} nowMs={nowMs} onOpen={() => onOpen(inc)} selected={String(selectedId) === String(inc.id)} />
        </li>
      ))}
    </ul>
  )
}

/** Yükleme iskeleti — gerçek kart boyutunda üç şerit (zıplama yok). */
export function BoardSkeleton() {
  return (
    <div data-slot="incidents-skeleton" className="grid items-start gap-3 md:grid-cols-2 lg:grid-cols-3" aria-hidden="true">
      {LANES.map((lane) => (
        <div key={lane} className="rounded-xl border bg-muted/30 p-2">
          <Skeleton className="mb-2 h-5 w-32" />
          {[0, 1, 2].map((k) => <Skeleton key={k} className="mb-2 h-[118px] w-full rounded-xl" />)}
        </div>
      ))}
    </div>
  )
}

/**
 * Pano görünümü — durum şeritleri: Açık → Üzerinde çalışılıyor (onaylı) → Çözüldü. Yüklenen sayfanın satırları
 * şeritlere dağıtılır (sayfalama çubuğu altta aynen durur). Telefonda şeritler shadcn Tabs olur (sekme başına sayı);
 * tablette 2, geniş ekranda 3 sütun. Şeritlerin kendi kaydırması yok: sayfa kayar (mobilde iç kaydırma tuzak).
 */
export default function IncidentBoard({ rows, nowMs, onOpen, selectedId, phone = false }) {
  const t = useT()
  const uid = useId()
  const [tab, setTab] = useState('open')
  const byLane = Object.fromEntries(LANES.map((l) => [l, []]))
  for (const r of rows) byLane[laneOf(r)].push(r)

  if (phone) {
    return (
      <Tabs value={tab} onValueChange={setTab} data-slot="incident-board" data-layout="tabs" className="min-w-0">
        <TabsList className="grid h-auto w-full grid-cols-3">
          {LANES.map((lane) => {
            const m = LANE_META[lane]
            return (
              <TabsTrigger key={lane} value={lane} className="min-h-10 min-w-0 flex-col gap-0.5 px-1 py-1.5 leading-tight sm:flex-row">
                <span className="inline-flex items-center gap-1"><m.Icon aria-hidden="true" className={cn('size-3.5', m.ink)} />{t(m.labelKey)}</span>
                <Badge variant="secondary" className="h-4 min-w-4 px-1 text-[10px] tabular-nums">{byLane[lane].length}</Badge>
              </TabsTrigger>
            )
          })}
        </TabsList>
        {LANES.map((lane) => (
          <TabsContent key={lane} value={lane} className="mt-1 min-w-0">
            <LaneBody lane={lane} rows={byLane[lane]} nowMs={nowMs} onOpen={onOpen} selectedId={selectedId} />
          </TabsContent>
        ))}
      </Tabs>
    )
  }

  return (
    <div data-slot="incident-board" data-layout="columns" className="grid items-start gap-3 md:grid-cols-2 lg:grid-cols-3">
      {LANES.map((lane) => {
        const hid = `${uid}-${lane}`
        // Tablette (2 sütun) üçüncü şerit iki sütuna yayılır — yoksa altta dar ve upuzun tek sütun kalıyordu (768 ölçümü)
        const wide = lane === 'resolved'
        return (
          <section key={lane} data-slot="incident-lane" data-lane={lane} aria-labelledby={hid}
            className={cn('min-w-0 rounded-xl border bg-muted/30 p-2', wide && 'md:col-span-2 lg:col-span-1')}>
            <LaneHeader lane={lane} count={byLane[lane].length} id={hid} />
            <LaneBody lane={lane} rows={byLane[lane]} nowMs={nowMs} onOpen={onOpen} selectedId={selectedId} wide={wide} />
          </section>
        )
      })}
    </div>
  )
}
