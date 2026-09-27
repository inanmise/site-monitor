import { ChevronRight, PanelRightOpen } from 'lucide-react'
import TeamBadge from '../../ui/TeamBadge.jsx'
import ChangeDiffChips from '../../history/ChangeDiffChips.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { ActorBadge, EventBadge, IpCopy, KindIcon, MonitorName, TimeAgo, kindLabel, resourceName } from './changeParts.jsx'

/**
 * İzleme Değişiklikleri listesi — masaüstü/tablet shadcn Table (Data Table görünümü: soluk başlık, sıkı satır,
 * satır vurgusu), telefon Card listesi. Çağıran `useIsMobile()` ile TEK varyantı çizer (jsdom medya sorgusu
 * uygulamaz; iki varyantı CSS ile gizlemek testlerde ikisini birden gösterirdi).
 *
 * Satır/kart ayrıntıyı (fark paneli, `ChangeDetailSheet`) açar; izlemenin adı ayrı bir bağlantıdır. Test kancaları:
 * kap `data-slot="chg-rows"`, satır/kart `data-chg-row`, künye `data-slot="chg-row-meta"`, ayrıntıyı açan öğe
 * `data-open-detail`.
 */

export const rowId = (r) => `${r.kind}-${r.resource_id}-${r.seq}`

const TH = 'h-9 px-3 text-[0.75em] font-semibold tracking-wide text-muted-foreground uppercase'
const TD = 'px-3 py-2'
const ROW_FOCUS = 'cursor-pointer outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary'

/** Tür · takım künyesi — "nerede" sorusunun cevabı; takım adı üye listesini açan rozet. */
function Meta({ r, t, className }) {
  return (
    <span data-slot="chg-row-meta" className={cn('flex min-w-0 flex-wrap items-center gap-x-1 text-xs text-muted-foreground', className)}>
      {kindLabel(t, r.kind)}
      {r.team_name ? <> · <TeamBadge teamId={r.team_id} teamName={r.team_name} size={11} /></> : ''}
    </span>
  )
}

/**
 * Sütunlar KAP genişliğine göre (`@container/chg`, MonitorChangesConsole): kenar çubuğu açık tablette içerik
 * ~460 px — Kullanıcı sütunu 672 px altında gizlenir (kim bilgisi izleme künyesinin altına iner), IP 896 px altında.
 */
function ChangeTableHead({ t }) {
  return (
    <TableHeader className="bg-muted/50">
      <TableRow className="hover:bg-transparent">
        <TableHead className={cn(TH, 'w-px')}>{t('chg.colTime')}</TableHead>
        <TableHead className={cn(TH, 'hidden @2xl/chg:table-cell')} data-col="user">{t('chg.colUser')}</TableHead>
        <TableHead className={cn(TH, 'w-px')}>{t('chg.colAction')}</TableHead>
        <TableHead className={TH}>{t('chg.colMonitor')}</TableHead>
        <TableHead className={TH}>{t('chg.colChanges')}</TableHead>
        <TableHead className={cn(TH, 'hidden w-px @4xl/chg:table-cell')} data-col="ip">{t('chg.colIp')}</TableHead>
        <TableHead className={cn(TH, 'w-8 px-1')}><span className="sr-only">{t('chg.details')}</span></TableHead>
      </TableRow>
    </TableHeader>
  )
}

/** Kaydı olmayan olay (oluşturma/silme) — alan farkı yok; tam durum ayrıntı panelinde. */
const NoDiff = () => <span className="text-muted-foreground">—</span>

export function ChangeTable({ rows, t, now, linkFor, onOpen, selectedId, loading }) {
  const open = (e, r) => {
    // Portal'lı içerik (takım üyeleri penceresi) React ağacında satırın içinde: tıklaması satıra kabarcıklanır.
    // Yalnız DOM'da satırın içinde kalan tıklama ayrıntıyı açar.
    if (!e.currentTarget.contains(e.target)) return
    onOpen(r)
  }
  const onKey = (e, r) => {
    if (e.target !== e.currentTarget || (e.key !== 'Enter' && e.key !== ' ')) return
    e.preventDefault()
    onOpen(r)
  }
  return (
    <div data-slot="chg-rows" aria-busy={loading || undefined}
      className={cn('overflow-hidden rounded-lg border bg-card transition-opacity', loading && 'opacity-60')}>
      <Table className="text-[0.85em]">
        <ChangeTableHead t={t} />
        <TableBody>
          {rows.map(r => {
            const id = rowId(r)
            const name = resourceName(r)
            const selected = selectedId === id
            return (
              <TableRow key={id} data-chg-row={id} data-open-detail="" tabIndex={0} aria-haspopup="dialog"
                aria-label={t('a11y.rowAction', name, t('chg.details'))}
                data-state={selected ? 'selected' : undefined}
                onClick={(e) => open(e, r)} onKeyDown={(e) => onKey(e, r)}
                className={cn(ROW_FOCUS, 'data-[state=selected]:bg-primary/5')}>
                <TableCell className={cn(TD, 'text-muted-foreground')}><TimeAgo at={r.at} t={t} now={now} /></TableCell>
                <TableCell className={cn(TD, 'hidden max-w-[13rem] @2xl/chg:table-cell')} data-col="user"><ActorBadge r={r} t={t} /></TableCell>
                <TableCell className={TD}><EventBadge t={t} ev={r.event_type} /></TableCell>
                <TableCell className={cn(TD, 'max-w-[20rem] min-w-[11rem] whitespace-normal')}>
                  <span className="flex min-w-0 items-start gap-2">
                    <KindIcon kind={r.kind} className="mt-0.5" />
                    <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                      <MonitorName r={r} link={linkFor(r)} />
                      <Meta r={r} t={t} />
                      {/* Dar kapta (kenar çubuğu açık tablet) Kullanıcı sütunu gizli — kim bilgisi künyenin altına iner. */}
                      <span data-slot="chg-row-actor" className="flex min-w-0 text-xs @2xl/chg:hidden"><ActorBadge r={r} t={t} /></span>
                    </span>
                  </span>
                </TableCell>
                {/* `w-full max-w-0`: otomatik tablo düzeninde hücre yalnız ARTAN genişliği alır — aksi halde çiplerin
                    min-content genişliği tabloyu kabın dışına taşırıp IP sütununu kaydırmanın arkasına itiyordu */}
                <TableCell className={cn(TD, 'w-full max-w-0 min-w-[12rem] whitespace-normal')}>
                  {/* Çipler sarar: izleme hücresi zaten iki satır (ad + künye), ikinci çip satırı yükseklik eklemez */}
                  {r.changes ? <ChangeDiffChips t={t} changes={r.changes} limit={2} wrap /> : <NoDiff />}
                </TableCell>
                <TableCell className={cn(TD, 'hidden @4xl/chg:table-cell')} data-col="ip"><IpCopy ip={r.ip_address} t={t} /></TableCell>
                <TableCell className="w-8 px-1 text-muted-foreground"><ChevronRight aria-hidden="true" className="size-4" /></TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}

/** Telefon kartı — üstte olay + kim + ne zaman, sonra izleme, künye, alan çipleri (sarar), "Ayrıntılar" (40 px). */
export function ChangeCards({ rows, t, now, linkFor, onOpen, selectedId, loading }) {
  return (
    <ul data-slot="chg-rows" aria-busy={loading || undefined}
      className={cn('m-0 flex list-none flex-col gap-2 p-0 transition-opacity', loading && 'opacity-60')}>
      {rows.map(r => {
        const id = rowId(r)
        const name = resourceName(r)
        return (
          <li key={id} data-chg-row={id} className="min-w-0">
            <Card data-state={selectedId === id ? 'selected' : undefined}
              className="min-w-0 gap-2 px-3 py-3 shadow-none data-[state=selected]:border-primary/50">
              <div className="flex min-w-0 items-center gap-2 text-xs">
                <EventBadge t={t} ev={r.event_type} />
                <span className="min-w-0 flex-1 truncate"><ActorBadge r={r} t={t} /></span>
                <TimeAgo at={r.at} t={t} now={now} className="shrink-0 text-muted-foreground" />
              </div>
              <div className="flex min-w-0 items-start gap-2">
                <KindIcon kind={r.kind} className="mt-0.5" />
                <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <MonitorName r={r} link={linkFor(r)} wrap />
                  <Meta r={r} t={t} />
                </div>
              </div>
              {r.changes && <ChangeDiffChips t={t} changes={r.changes} limit={3} wrap />}
              <Button type="button" variant="outline" size="sm" data-open-detail="" aria-haspopup="dialog"
                aria-label={t('a11y.rowAction', name, t('chg.details'))}
                className="h-10 w-full" onClick={() => onOpen(r)}>
                <PanelRightOpen aria-hidden="true" /> {t('chg.details')}
              </Button>
            </Card>
          </li>
        )
      })}
    </ul>
  )
}

/** İlk yükleme iskeleti — gerçek satır/kart ölçüsünde (veri gelince sayfa zıplamasın). */
export function ChangeListSkeleton({ mobile, t }) {
  if (mobile) {
    return (
      <ul data-slot="chg-rows" aria-busy="true" className="m-0 flex list-none flex-col gap-2 p-0">
        <li className="sr-only" role="status">{t('modal.loading')}</li>
        {Array.from({ length: 4 }, (_, i) => (
          <li key={i} data-skeleton="">
            <Card className="gap-2.5 px-3 py-3 shadow-none">
              <div className="flex items-center gap-2"><Skeleton className="h-5 w-20" /><Skeleton className="h-4 flex-1" /><Skeleton className="h-4 w-14" /></div>
              <Skeleton className="h-4 w-3/4" />
              <Skeleton className="h-3 w-1/3" />
              <div className="flex gap-1"><Skeleton className="h-5 w-28" /><Skeleton className="h-5 w-24" /></div>
              <Skeleton className="h-10 w-full" />
            </Card>
          </li>
        ))}
      </ul>
    )
  }
  return (
    <div data-slot="chg-rows" aria-busy="true" className="overflow-hidden rounded-lg border bg-card">
      <span className="sr-only" role="status">{t('modal.loading')}</span>
      <Table className="text-[0.85em]">
        <ChangeTableHead t={t} />
        <TableBody>
          {Array.from({ length: 6 }, (_, i) => (
            <TableRow key={i} data-skeleton="" className="hover:bg-transparent">
              <TableCell className={TD}><Skeleton className="h-4 w-16" /></TableCell>
              <TableCell className={cn(TD, 'hidden @2xl/chg:table-cell')}><Skeleton className="h-4 w-28" /></TableCell>
              <TableCell className={TD}><Skeleton className="h-5 w-20" /></TableCell>
              <TableCell className={TD}><Skeleton className="h-8 w-44 max-w-full" /></TableCell>
              <TableCell className={TD}><Skeleton className="h-5 w-52 max-w-full" /></TableCell>
              <TableCell className={cn(TD, 'hidden @4xl/chg:table-cell')}><Skeleton className="h-4 w-24" /></TableCell>
              <TableCell className="w-8 px-1" />
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
