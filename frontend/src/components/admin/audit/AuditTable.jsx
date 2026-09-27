import { useRef } from 'react'
import { ChevronRight } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { EventBadge, OutcomeBadge } from '../ToneBadge.jsx'
import { ActorLabel, AnomalyChips, AuditTime, CopyInline, useExactTime } from './AuditBits.jsx'
import { eventClass, eventLabel, summaryLine, OUTCOME_KEYS } from './auditFormat.js'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const TH = 'h-9 px-3 text-xs font-medium tracking-wide text-muted-foreground uppercase'
const TD = 'px-3 py-2 align-top whitespace-normal'
/**
 * Sütun önceliği KAP genişliğine göre (Tailwind v4 kap sorguları, ata `@container`): liste bazen tam genişlik,
 * bazen xl'de ayrıntı bölmesinin yanında, bazen tablette kenar çubuğunun yanında — görünüm alanı kırılımı
 * (`lg:`) bunların hiçbirini doğru ölçmez. Dar kapta aktör olay hücresinin içine iner.
 */
const SHOW_ACTOR = 'hidden @min-[40rem]:table-cell'
const INLINE_ACTOR = '@min-[40rem]:hidden'
const SHOW_TARGET = 'hidden @min-[52rem]:table-cell'
const SHOW_IP = 'hidden @min-[60rem]:table-cell'

/**
 * Denetim olayları — masaüstü/tablet veri tablosu (shadcn Table). Satıra tıklamak ayrıntıyı açar; her satırın GERÇEK
 * bir düğmesi var (son sütun, adı olay + aktör + kesin zaman — satırları ayırt eder). Klavye: gezici tabindex —
 * Tab tabloya tek durakta girer, ↑/↓ (Home/End) seçimi taşır ve odağı o satırın düğmesine götürür, Enter açar,
 * Esc seçimi bırakır. Sunucu sayfalaması: sayfa sınırında durur (sayfalayıcı çubukta).
 */
export default function AuditTable({ rows, selectedId, onSelect, onOpen, onClearSelection, loading, initialLoading, skeletonRows = 8 }) {
  const t = useT()
  const exact = useExactTime()
  const btnRefs = useRef(new Map())
  const tabStopId = rows.some(r => r.id === selectedId) ? selectedId : rows[0]?.id

  function focusRow(id) {
    const el = btnRefs.current.get(id)
    if (el) { el.focus({ preventScroll: false }); el.scrollIntoView?.({ block: 'nearest' }) }
  }

  function onKeyDown(e) {
    if (!rows.length) return
    const idx = rows.findIndex(r => r.id === selectedId)
    let next = null
    if (e.key === 'ArrowDown') next = idx < 0 ? 0 : Math.min(rows.length - 1, idx + 1)
    else if (e.key === 'ArrowUp') next = idx < 0 ? 0 : Math.max(0, idx - 1)
    else if (e.key === 'Home') next = 0
    else if (e.key === 'End') next = rows.length - 1
    else if (e.key === 'Escape' && selectedId != null) { e.preventDefault(); onClearSelection?.(); return }
    if (next == null) return
    e.preventDefault()
    const id = rows[next].id
    onSelect(id)
    focusRow(id)
  }

  return (
    <div className={cn('transition-opacity motion-reduce:transition-none', loading && !initialLoading && 'opacity-60')}>
      <Table className="text-sm" aria-busy={loading || undefined}>
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            <TableHead className={cn(TH, 'w-[1%]')}>{t('audit.colTime')}</TableHead>
            <TableHead className={TH}>{t('audit.colEvent')}</TableHead>
            <TableHead className={cn(TH, SHOW_ACTOR)}>{t('audit.colActor')}</TableHead>
            <TableHead className={TH}>{t('audit.colOutcome')}</TableHead>
            <TableHead className={cn(TH, SHOW_TARGET)}>{t('audit.colResource')}</TableHead>
            <TableHead className={cn(TH, SHOW_IP)}>{t('audit.colIp')}</TableHead>
            <TableHead className={cn(TH, 'w-12')}><span className="sr-only">{t('audit.colDetail')}</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody onKeyDown={onKeyDown}>
          {initialLoading && Array.from({ length: skeletonRows }, (_, i) => (
            <TableRow key={`sk-${i}`} data-skeleton="true" className="hover:bg-transparent">
              <TableCell className={TD}><Skeleton className="h-4 w-16" /></TableCell>
              <TableCell className={TD}><Skeleton className="mb-1.5 h-5 w-28" /><Skeleton className="h-3 w-44" /></TableCell>
              <TableCell className={cn(TD, SHOW_ACTOR)}><Skeleton className="h-5 w-24" /></TableCell>
              <TableCell className={TD}><Skeleton className="h-5 w-16" /></TableCell>
              <TableCell className={cn(TD, SHOW_TARGET)}><Skeleton className="h-4 w-24" /></TableCell>
              <TableCell className={cn(TD, SHOW_IP)}><Skeleton className="h-4 w-24" /></TableCell>
              <TableCell className={TD}><Skeleton className="size-8" /></TableCell>
            </TableRow>
          ))}
          {!initialLoading && rows.map(row => {
            const selected = row.id === selectedId
            const label = eventLabel(row.event_type, t)
            const summary = summaryLine(row)
            const outcomeKey = OUTCOME_KEYS[row.outcome]
            const actorName = row.actor || t('audit.systemActor')
            const geo = [row.ip_city, row.ip_country].filter(Boolean).join(', ')
            return (
              <TableRow key={row.id}
                data-state={selected ? 'selected' : undefined}
                data-anomaly={row.anomaly_flags ? 'true' : undefined}
                aria-selected={selected}
                onClick={() => onOpen(row.id)}
                className={cn('cursor-pointer data-[state=selected]:bg-primary/10 data-[state=selected]:hover:bg-primary/10',
                  row.anomaly_flags && 'bg-amber-500/5')}>
                <TableCell className={cn(TD, 'whitespace-nowrap text-muted-foreground')}>
                  <AuditTime iso={row.event_time} />
                </TableCell>
                <TableCell className={cn(TD, 'min-w-44')}>
                  {/* Ham tür `title`'da KALIR: denetçi ham kodla filtreler ve kopyalar. */}
                  <EventBadge kind={eventClass(row.event_type)} title={row.event_type}>{label}</EventBadge>
                  <div className={cn('mt-1', INLINE_ACTOR)}><ActorLabel row={row} showRole={false} /></div>
                  {summary && <div className="mt-1 line-clamp-2 text-xs break-words text-muted-foreground" title={summary}>{summary}</div>}
                </TableCell>
                <TableCell className={cn(TD, SHOW_ACTOR)}><ActorLabel row={row} /></TableCell>
                <TableCell className={TD}>
                  <OutcomeBadge outcome={row.outcome} title={row.outcome || ''}>{outcomeKey ? t(outcomeKey) : (row.outcome || '—')}</OutcomeBadge>
                  <AnomalyChips flags={row.anomaly_flags} className="mt-1" />
                </TableCell>
                <TableCell className={cn(TD, SHOW_TARGET, 'max-w-56')}>
                  {row.resource_type || row.resource_id ? (
                    <div className="min-w-0">
                      {row.resource_type && <div className="text-xs text-muted-foreground">{row.resource_type}</div>}
                      {row.resource_id && <div className="truncate font-mono text-xs" title={row.resource_id}>{row.resource_id}</div>}
                    </div>
                  ) : <span className="text-muted-foreground">—</span>}
                </TableCell>
                <TableCell className={cn(TD, SHOW_IP)}>
                  {row.ip_address ? (
                    <div className="min-w-0">
                      <span className="inline-flex items-center gap-1">
                        <span className="font-mono text-xs">{row.ip_address}</span>
                        {/* Fare kısayolu (tabIndex -1): klavye kullanıcısı kopyalamayı ayrıntıdan yapar — satır başına ek Tab durağı olmasın. */}
                        <CopyInline value={row.ip_address} label={t('a11y.rowAction', row.ip_address, t('audit.copyIp'))} />
                      </span>
                      {geo && <div className="truncate text-xs text-muted-foreground" title={[geo, row.ip_org].filter(Boolean).join(' · ')}>{geo}</div>}
                    </div>
                  ) : <span className="text-muted-foreground">—</span>}
                </TableCell>
                <TableCell className={cn(TD, 'text-right')}>
                  <Button type="button" variant="ghost" size="icon-sm"
                    ref={(el) => { if (el) btnRefs.current.set(row.id, el); else btnRefs.current.delete(row.id) }}
                    tabIndex={row.id === tabStopId ? 0 : -1}
                    aria-label={t('audit.openEvent', `${label} — ${actorName} — ${exact(row.event_time)}`)}
                    onClick={(e) => { e.stopPropagation(); onOpen(row.id) }}
                    className={cn('text-muted-foreground', selected && 'text-primary')}>
                    <ChevronRight className={cn('transition-transform motion-reduce:transition-none', selected && 'translate-x-0.5')} />
                  </Button>
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
