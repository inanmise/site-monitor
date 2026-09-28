import { useEffect, useMemo, useState } from 'react'
import { Braces, ChevronDown, ExternalLink, ListFilter, X } from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import { navigateTo } from '../../../utils/navigate.js'
import { tabDeepLink } from '../../../utils/monitorDeepLink.js'
import { flushUrlQuerySync } from '../../../hooks/useUrlQuerySync.js'
import TeamBadge from '../../ui/TeamBadge.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import CopyLinkButton from '../../ui/CopyLinkButton.jsx'
import { fieldLabel, parseChanges, parseSnapshot, shortUserAgent } from '../../history/changeFields.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import ChangeDiff from './ChangeDiff.jsx'
import { ActorBadge, EventBadge, IpCopy, KindIcon, TimeAgo, kindLabel } from './changeParts.jsx'
import { URL_KEYS, activeToggle, detailKey, fullText, isDeleted, linkFor, resourceName } from './changeModel.js'

/**
 * Tek değişikliğin ayrıntısı — yan panel (shadcn Sheet; masaüstünde sağda, telefonda TAM EKRAN). İçerik: künye (ne
 * zaman, kim, tür, takım, IP, tarayıcı, kayıt no), değişiklik nedeni notu (tam çerçeveli kutu — sol renk şeridi YOK),
 * alan farkı (`ChangeDiff`, zaman çizelgesiyle AYNI dil — kırpılmadan), o anki TAM ayarlar (ayrı uç `getChangeDetail` —
 * liste yanıtı snapshot taşımaz; panel açılınca bir kez istenir) ve HAM kayıt (JSON, katlanır, kopyalanır).
 *
 * Alt çubuk: bağlantıyı kopyala (`?tab=monitorchanges&ch_id=tür:id:sıra` — kopyalanan bağlantı bu paneli açar),
 * "Yalnız bu izleme" (listeyi bu izlemeye daraltır), "İzlemeye git" (izleme silinmişse YOK), Kapat.
 */

function Fact({ label, children, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-1', className)}>
      <dt className="text-[0.72rem] font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

function SnapshotList({ items, t }) {
  return (
    <dl data-slot="chg-snapshot" className="m-0 grid grid-cols-1 gap-x-5 gap-y-1.5 sm:grid-cols-2">
      {items.map(f => (
        <div key={f.key} className="flex min-w-0 flex-col gap-0.5 border-b border-dashed pb-1.5">
          <dt className="text-xs text-muted-foreground">{fieldLabel(t, f.key)}</dt>
          <dd className="m-0 min-w-0 text-sm break-words whitespace-pre-wrap [overflow-wrap:anywhere]">{fullText(f.key, f.value, t)}</dd>
        </div>
      ))}
    </dl>
  )
}

const parseJson = (raw) => {
  if (raw == null || raw === '') return null
  if (typeof raw !== 'string') return raw
  try { return JSON.parse(raw) } catch { return raw }
}

export default function ChangeDetailSheet({ row, t, now, onClose, onFilterResource }) {
  const [detail, setDetail] = useState({ key: null, state: 'idle', snapshot: [], raw: null })
  const [stateOpen, setStateOpen] = useState(false)
  const [rawOpen, setRawOpen] = useState(false)
  const key = row ? `${row.kind}-${row.resource_id}-${row.seq}` : null
  const preloaded = row && row.snapshot !== undefined ? row.snapshot : undefined

  // Anın TAM ayarları — ayrı uç (liste yanıtı snapshot taşımaz). Panel her açıldığında o satır için bir kez; derin
  // bağlantıyla açılan satır ayrıntı ucundan geldiği için snapshot'ı zaten taşır (ikinci istek yok).
  useEffect(() => {
    setStateOpen(false)
    setRawOpen(false)
    if (!row) return undefined
    if (preloaded !== undefined) {
      setDetail({ key, state: 'ok', snapshot: parseSnapshot(preloaded), raw: preloaded })
      return undefined
    }
    let alive = true
    setDetail({ key, state: 'loading', snapshot: [], raw: null })
    Promise.resolve()
      .then(() => api.monitoring.getChangeDetail(String(row.kind).toLowerCase(), row.resource_id, row.seq))
      .then(res => {
        if (!alive) return
        setDetail(res?.success
          ? { key, state: 'ok', snapshot: parseSnapshot(res.data?.snapshot), raw: res.data?.snapshot ?? null }
          : { key, state: 'error', snapshot: [], raw: null })
      })
      .catch(() => { if (alive) setDetail({ key, state: 'error', snapshot: [], raw: null }) })
    return () => { alive = false }
  }, [key]) // eslint-disable-line react-hooks/exhaustive-deps

  const r = row
  const diff = r ? parseChanges(r.changes) : []
  const link = r ? linkFor(r) : null
  const toggle = r ? activeToggle(r.changes) : null
  const deleted = isDeleted(r)
  const snapshotTitle = !r ? '' : r.event_type === 'CREATE' ? t('chg.initialValues')
    : r.event_type === 'DELETE' ? t('chg.stateBeforeDelete') : t('chg.stateAfter')
  const snap = detail.key === key ? detail : { state: 'loading', snapshot: [], raw: null }

  const rawJson = useMemo(() => {
    if (!r) return ''
    const { snapshot: _omit, ...rest } = r   // eslint-disable-line no-unused-vars
    return JSON.stringify({ ...rest, changes: parseJson(r.changes), snapshot: parseJson(snap.raw) }, null, 2)
  }, [r, snap.raw])

  const snapshotBody = snap.state === 'loading' ? (
    <div className="flex flex-col gap-2" aria-busy="true">
      <span className="sr-only" role="status">{t('modal.loading')}</span>
      <Skeleton className="h-4 w-2/3" /><Skeleton className="h-4 w-1/2" /><Skeleton className="h-4 w-3/5" />
    </div>
  ) : snap.state === 'error' ? (
    <p className="m-0 text-sm text-muted-foreground">{t('chg.snapshotError')}</p>
  ) : snap.snapshot.length === 0 ? (
    <p className="m-0 text-sm text-muted-foreground">—</p>
  ) : <SnapshotList items={snap.snapshot} t={t} />

  return (
    <Sheet open={!!r} onOpenChange={(o) => { if (!o) onClose() }}>
      {r && (
        <SheetContent side="right" showCloseButton={false} data-slot="chg-detail"
          className="h-dvh w-full gap-0 p-0 sm:max-w-2xl">
          <SheetHeader className="flex-row items-start gap-3 border-b p-4">
            <div className="flex min-w-0 flex-1 flex-col gap-1.5">
              <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <EventBadge t={t} ev={r.event_type} />
                {toggle && <EventBadge t={t} ev={toggle} />}
                {deleted && r.event_type !== 'DELETE' && (
                  <Badge variant="outline" data-slot="chg-deleted" className="font-normal text-muted-foreground">{t('chg.deletedMonitor')}</Badge>
                )}
                <TimeAgo at={r.at} t={t} now={now} />
              </div>
              <SheetTitle className="flex min-w-0 items-start gap-2 text-base leading-snug">
                <KindIcon kind={r.kind} className="mt-1" />
                <span className="min-w-0 break-words [overflow-wrap:anywhere]">{resourceName(r)}</span>
              </SheetTitle>
              <SheetDescription className="text-xs">
                {kindLabel(t, r.kind)} · {t('chg.detailRecord')} #{r.seq}
              </SheetDescription>
            </div>
            <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-1 size-10 shrink-0 text-muted-foreground"
              aria-label={t('app.close')} onClick={onClose}>
              <X aria-hidden="true" />
            </Button>
          </SheetHeader>

          <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto p-4">
            <dl className="m-0 grid grid-cols-2 gap-x-4 gap-y-3">
              <Fact label={t('chg.colTime')} className="col-span-2 sm:col-span-1">
                <time dateTime={r.at} className="tabular-nums">{formatDateSec(r.at)}</time>
              </Fact>
              <Fact label={t('chg.colUser')} className="col-span-2 sm:col-span-1"><ActorBadge r={r} t={t} full /></Fact>
              <Fact label={t('chg.filterTeam')}>
                {r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} size={12} className="max-md:after:absolute max-md:after:inset-x-0 max-md:after:-inset-y-3" /> : <span className="text-muted-foreground">—</span>}
              </Fact>
              <Fact label={t('chg.colIp')}><IpCopy ip={r.ip_address} t={t} className="-ml-1 max-md:min-h-10" /></Fact>
              {r.user_agent && (
                <Fact label={t('chg.detailBrowser')} className="col-span-2">
                  <SimpleTooltip content={r.user_agent}><span className="cursor-default">{shortUserAgent(r.user_agent)}</span></SimpleTooltip>
                </Fact>
              )}
            </dl>

            {r.note && (
              <section className="rounded-md border bg-muted/40 px-3 py-2.5">
                <h3 className="m-0 mb-1 text-xs font-semibold text-muted-foreground">{t('chg.noteTitle')}</h3>
                <p className="m-0 text-sm break-words">{r.note}</p>
              </section>
            )}

            {diff.length > 0 ? (
              <section className="flex min-w-0 flex-col gap-2">
                <h3 className="m-0 text-sm font-semibold">{t('chg.diffTitle')} <span className="font-normal text-muted-foreground tabular-nums">({diff.length})</span></h3>
                <ChangeDiff changes={r.changes} t={t} />
              </section>
            ) : (
              r.event_type !== 'CREATE' && r.event_type !== 'DELETE' && (
                <p className="m-0 text-sm text-muted-foreground">{t('chg.noDiff')}</p>
              )
            )}

            {/* Tam ayarlar: fark yoksa (oluşturma/silme) doğrudan açık; düzenlemede katlanır — fark asıl bilgi. */}
            {diff.length === 0 ? (
              <section className="flex min-w-0 flex-col gap-2">
                <h3 className="m-0 text-sm font-semibold">{snapshotTitle}</h3>
                {snapshotBody}
              </section>
            ) : (
              <Collapsible open={stateOpen} onOpenChange={setStateOpen} className="flex min-w-0 flex-col gap-2">
                <CollapsibleTrigger asChild>
                  <Button type="button" variant="outline" size="sm" className="h-10 self-start">
                    <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', stateOpen && 'rotate-180')} />
                    {stateOpen ? t('chg.hideFullState') : snapshotTitle}
                  </Button>
                </CollapsibleTrigger>
                <CollapsibleContent>{snapshotBody}</CollapsibleContent>
              </Collapsible>
            )}

            {/* Ham kayıt: denetim için "sunucu ne yazdı" — alan farkı ve anlık durum çözülmüş JSON olarak, kopyalanabilir. */}
            <Collapsible open={rawOpen} onOpenChange={setRawOpen} className="flex min-w-0 flex-col gap-2">
              <div className="flex items-center gap-2">
                <CollapsibleTrigger asChild>
                  <Button type="button" variant="ghost" size="sm" data-action="chg-raw" className="h-10 gap-1.5 self-start px-2 text-muted-foreground hover:text-foreground">
                    <Braces aria-hidden="true" />
                    {rawOpen ? t('chg.hideRaw') : t('chg.showRaw')}
                    <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', rawOpen && 'rotate-180')} />
                  </Button>
                </CollapsibleTrigger>
                {rawOpen && (
                  <CopyButton value={rawJson} label={t('chg.copyRaw')} copiedLabel={t('chg.copiedRaw')} buttonSize="icon" size={15}
                    className="size-10 sm:size-8" />
                )}
              </div>
              <CollapsibleContent>
                <pre data-slot="chg-raw" className="m-0 max-h-96 overflow-auto rounded-md border bg-muted/40 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]">{rawJson}</pre>
              </CollapsibleContent>
            </Collapsible>
          </div>

          <SheetFooter className="flex-row flex-wrap justify-end gap-2 border-t p-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
            <CopyLinkButton variant="ghost" size="icon" iconOnly className="mr-auto size-10"
              url={tabDeepLink('monitorchanges', { [URL_KEYS.open]: detailKey(r) })} targetName={resourceName(r)} />
            {onFilterResource && (
              <Button type="button" variant="outline" className="h-10" onClick={() => { onFilterResource(r); onClose() }}>
                <ListFilter aria-hidden="true" /> {t('chg.filterThisMonitor')}
              </Button>
            )}
            {link && (
              <Button asChild variant="outline" className="h-10">
                <a href={link.href} onClick={(e) => {
                  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
                  e.preventDefault()
                  onClose()
                  flushUrlQuerySync()
                  navigateTo(link.tab, link.params)
                }}>
                  <ExternalLink aria-hidden="true" /> {t('chg.openMonitor')}
                </a>
              </Button>
            )}
            {/* Telefonda başlıktaki 40 px X yeterli — alt çubuk tek satırda kalsın */}
            <Button type="button" variant="secondary" className="h-10 max-sm:hidden" onClick={onClose}>{t('app.close')}</Button>
          </SheetFooter>
        </SheetContent>
      )}
    </Sheet>
  )
}
