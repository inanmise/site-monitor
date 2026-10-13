import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { useElementWidthState } from '../../../hooks/useElementWidth.js'
import { agoText } from '../../../utils/relativeTime.js'
import { cn } from '@/lib/utils'
import { CPU_PCT, MEMORY_PCT, fmtNum, fmtPct, podState, toneOf, uptimeText, utcMs } from './metricsModel.js'

/**
 * Pod listesi (2026-10-13): her podun son örneği. Kapsayıcı ≥ 860 px → tablo (başlıklar sarar), altında kart. Pod adı KIRPILMAZ (sarar).
 * Sessiz pod (son örneği 3 dakikadan eski) "Sessiz" rozetiyle gösterilir, anlık değerleri "—" (eski değer güncel gibi
 * okunmaz). Bu sayfayı sunan pod "bu pod" rozeti taşır.
 */
const TABLE_MIN_PX = 860
const INK = { ok: 'text-foreground', warn: 'text-amber-700 dark:text-amber-300', crit: 'text-destructive', neutral: 'text-muted-foreground' }

/** Canlı (yeşil) · sessiz (amber — beklenmeyen) · durdu (nötr — dağıtım / yeniden başlatmayla yerine yenisi gelmiş). */
function StatusBadge({ state, t }) {
  if (state === 'live') {
    return <Badge variant="outline" data-slot="mx-pod-status" data-live="true" data-state="live" className="border-transparent bg-success/15 text-success">{t('mx.pod.live')}</Badge>
  }
  if (state === 'stopped') {
    return <Badge variant="secondary" data-slot="mx-pod-status" data-live="false" data-state="stopped">{t('mx.pod.stopped')}</Badge>
  }
  return <Badge variant="warning" data-slot="mx-pod-status" data-live="false" data-state="silent">{t('mx.pod.silent')}</Badge>
}

function Val({ tone = 'neutral', children }) {
  return <span className={cn('font-semibold tabular-nums', INK[tone])}>{children}</span>
}

function podCells(p, t, now) {
  const l = podState(p) === 'live' ? p.latest : null
  const seen = utcMs(p.last_seen)
  return {
    seen: seen == null ? '—' : agoText(seen, t, now),
    uptime: l ? uptimeText(l.uptime_s, t) : '—',
    cpu: l ? <Val tone={toneOf(l.cpu_process, CPU_PCT)}>{fmtPct(l.cpu_process)}</Val> : <Val>—</Val>,
    heap: l ? (
      <span className="inline-flex flex-wrap items-baseline gap-x-1">
        <Val tone={toneOf(l.heap_pct, MEMORY_PCT)}>{fmtPct(l.heap_pct)}</Val>
        <span className="text-xs text-muted-foreground tabular-nums">{t('mx.pod.mb', fmtNum(l.heap_used_mb), fmtNum(l.heap_max_mb))}</span>
      </span>
    ) : <Val>—</Val>,
    threads: l ? <Val tone="ok">{fmtNum(l.threads)}</Val> : <Val>—</Val>,
    db: l && l.db_max != null ? (
      <Val tone={(l.db_pending ?? 0) > 0 ? 'crit' : 'ok'}>{`${fmtNum(l.db_active)} / ${fmtNum(l.db_max)}`}</Val>
    ) : <Val>—</Val>,
    queue: l && l.exec_queue != null ? <Val tone="ok">{fmtNum(l.exec_queue)}</Val> : <Val>—</Val>,
  }
}

export default function PodList({ t, pods, servedBy, failed = false, now = Date.now() }) {
  const [width, setEl] = useElementWidthState()
  const asTable = width === 0 || width >= TABLE_MIN_PX
  const list = Array.isArray(pods) ? pods : []

  if (failed && !list.length) {
    return <p data-slot="mx-pods-failed" role="status" className="m-0 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">{t('mx.pod.failed')}</p>
  }
  if (!list.length) {
    return <p data-slot="mx-pods-empty" className="m-0 rounded-md bg-muted/40 px-3 py-2 text-sm text-muted-foreground">{t('mx.pod.none')}</p>
  }

  const name = (p) => (
    <span className="flex min-w-0 flex-wrap items-center gap-1.5">
      <span data-slot="mx-pod-name" className="font-medium text-foreground [overflow-wrap:anywhere]">{p.name}</span>
      {servedBy && String(servedBy) === String(p.id) && (
        <Badge variant="secondary" className="rounded-md">{t('mx.pod.thisPod')}</Badge>
      )}
    </span>
  )

  return (
    <div ref={setEl} data-slot="mx-pods" data-layout={asTable ? 'table' : 'cards'} className="min-w-0">
      {asTable ? (
        <Table className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="whitespace-normal align-bottom w-[22%]">{t('mx.pod.col.name')}</TableHead>
              <TableHead className="whitespace-normal align-bottom w-[9%]">{t('mx.pod.col.status')}</TableHead>
              <TableHead className="whitespace-normal align-bottom w-[10%]">{t('mx.pod.col.seen')}</TableHead>
              <TableHead className="whitespace-normal align-bottom w-[10%]">{t('mx.pod.col.uptime')}</TableHead>
              <TableHead className="whitespace-normal align-bottom w-[8%]">{t('mx.pod.col.cpu')}</TableHead>
              <TableHead className="whitespace-normal align-bottom w-[14%]">{t('mx.pod.col.heap')}</TableHead>
              <TableHead className="whitespace-normal align-bottom w-[9%]">{t('mx.pod.col.threads')}</TableHead>
              <TableHead className="whitespace-normal align-bottom w-[10%]">{t('mx.pod.col.db')}</TableHead>
              <TableHead className="whitespace-normal align-bottom w-[8%]">{t('mx.pod.col.queue')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {list.map((p) => {
              const c = podCells(p, t, now)
              return (
                <TableRow key={p.id} data-slot="mx-pod" data-pod={p.id}>
                  <TableCell className="whitespace-normal">{name(p)}</TableCell>
                  <TableCell><StatusBadge state={podState(p)} t={t} /></TableCell>
                  <TableCell className="whitespace-normal text-xs text-muted-foreground">{c.seen}</TableCell>
                  <TableCell className="whitespace-normal text-xs">{c.uptime}</TableCell>
                  <TableCell className="whitespace-normal">{c.cpu}</TableCell>
                  <TableCell className="whitespace-normal">{c.heap}</TableCell>
                  <TableCell className="whitespace-normal">{c.threads}</TableCell>
                  <TableCell className="whitespace-normal">{c.db}</TableCell>
                  <TableCell className="whitespace-normal">{c.queue}</TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      ) : (
        <ul className="m-0 grid list-none grid-cols-1 gap-2 p-0 sm:grid-cols-2">
          {list.map((p) => {
            const c = podCells(p, t, now)
            const field = (label, value) => (
              <div className="flex min-w-0 flex-col gap-0.5">
                <dt className="text-[11px] text-muted-foreground">{label}</dt>
                <dd className="m-0 text-sm">{value}</dd>
              </div>
            )
            return (
              <li key={p.id}>
                <Card data-slot="mx-pod" data-pod={p.id} className="min-w-0 gap-2 px-3 py-3 shadow-xs">
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    {name(p)}
                    <StatusBadge state={podState(p)} t={t} />
                  </div>
                  <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-2 min-[420px]:grid-cols-3">
                    {field(t('mx.pod.col.seen'), <span className="text-xs">{c.seen}</span>)}
                    {field(t('mx.pod.col.uptime'), <span className="text-xs">{c.uptime}</span>)}
                    {field(t('mx.pod.col.cpu'), c.cpu)}
                    {field(t('mx.pod.col.heap'), c.heap)}
                    {field(t('mx.pod.col.threads'), c.threads)}
                    {field(t('mx.pod.col.db'), c.db)}
                    {field(t('mx.pod.col.queue'), c.queue)}
                  </dl>
                </Card>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
