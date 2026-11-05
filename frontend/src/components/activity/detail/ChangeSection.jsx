import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronDown, FileDiff, History, RefreshCw } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { errorInfoOf } from '../../../utils/errorMessages.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import DiffTable from '../../admin/audit/DiffTable.jsx'
import { ActorBadge, EventBadge } from '../../admin/monitorchanges/changeParts.jsx'
import { fieldLabel, formatValue, parseChanges, parseSnapshot } from '../../history/changeFields.js'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { ApproxBadge, LinkButton, SectionCard } from './detailParts.jsx'
import { formatIstanbul } from './activityDetailModel.js'

/** Tam değer: çip biçimi 120 karakterde kırpar (`formatValue`); ayrıntıda metin kırpılmaz (ChangeHistoryTab ile aynı). */
function fullValue(key, value, ctx) {
  if (typeof value === 'string' && value.length > 120) return value
  return formatValue(key, value, ctx)
}

/**
 * DEĞİŞİKLİK (C): aktivitenin bağlı olduğu yapılandırma değişikliği — VAR OLAN uçtan (`GET /api/monitoring/changes/{kind}/
 * {id}/{seq}`; kendi kapısı ve kimlik maskelemesi orada). Gösterim izleme penceresinin "Değişiklikler" sekmesiyle aynı
 * parçalar: olay rozeti, kim, ne zaman, not, ALAN BAZLI önce → sonra (Denetim Kaydı `DiffTable`) ve o anki tam ayarlar
 * (oluşturma / silmede açık, düzenlemede katlanır). Okunamazsa "okunamadı" + tekrar dene — asla "değişiklik yok".
 * Eşleşme yaklaşıksa (eski satır: değişiklik kaydı ± 2 dk zaman yakınlığıyla bulundu — inceleme CT O5) rozet + açıklama;
 * kesin bağ gibi gösterilmez. `refreshNonce` (sayfanın Yenile düğmesi) kaydı yeniden okutur.
 * Test kancaları: `act-dt-change` (`data-state` loading|ready|notFound|error, `data-match`), `act-dt-change-snapshot`.
 */
export default function ChangeSection({ change, changesLink, locale, refreshNonce = 0 }) {
  const t = useT()
  const [st, setSt] = useState({ phase: 'loading', row: null })
  const [stateOpen, setStateOpen] = useState(false)
  const seq = useRef(0)
  const load = useCallback(() => {
    const my = ++seq.current
    setSt({ phase: 'loading', row: null })
    Promise.resolve().then(() => api.monitoring.getChangeDetail(change.kind, change.id, change.seq)).catch((e) => e).then((res) => {
      if (my !== seq.current) return
      if (res?.success && res.data) setSt({ phase: 'ready', row: res.data })
      else setSt({ phase: errorInfoOf(res)?.status === 404 ? 'notFound' : 'error', row: null })
    })
  }, [change.kind, change.id, change.seq, refreshNonce]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); const s = seq; return () => { s.current++ } }, [load])

  const r = st.row
  const diff = r ? parseChanges(r.changes) : []
  const ctx = { t, teamNames: r?.team_id != null && r?.team_name ? { [r.team_id]: r.team_name } : {} }
  const snapshot = r ? parseSnapshot(r.snapshot) : []
  const snapshotTitle = r?.event_type === 'CREATE' ? t('chg.initialValues')
    : r?.event_type === 'DELETE' ? t('chg.stateBeforeDelete') : t('chg.stateAfter')
  const snapshotList = (
    <dl data-slot="act-dt-change-snapshot" className="m-0 grid grid-cols-1 gap-x-5 gap-y-1.5 sm:grid-cols-2">
      {snapshot.map((f) => (
        <div key={f.key} className="flex min-w-0 flex-col gap-0.5 border-b border-dashed pb-1.5">
          <dt className="text-xs text-muted-foreground">{fieldLabel(t, f.key)}</dt>
          <dd className="m-0 min-w-0 text-sm break-words [overflow-wrap:anywhere]">{fullValue(f.key, f.value, ctx)}</dd>
        </div>
      ))}
    </dl>
  )

  return (
    <SectionCard slot="act-dt-change" icon={FileDiff} title={t('act.dt.chg.title')}
      description={change.match === 'approximate' ? t('act.dt.chg.approximate') : t('act.dt.chg.desc')}
      data-state={st.phase} data-match={change.match || 'exact'}
      action={(change.match === 'approximate' || changesLink) ? (<>
        {change.match === 'approximate' && <ApproxBadge />}
        {changesLink && <LinkButton target={changesLink} slot="changes-all"><History aria-hidden="true" />{t('act.dt.chg.all')}</LinkButton>}
      </>) : null}>
      {st.phase === 'loading' && (
        <div className="flex flex-col gap-2" aria-busy="true">
          <span className="sr-only" role="status">{t('act.dt.chg.loading')}</span>
          <Skeleton className="h-4 w-2/3" /><Skeleton className="h-4 w-1/2" /><Skeleton className="h-16 w-full" />
        </div>
      )}
      {st.phase === 'notFound' && <p className="m-0 text-sm text-muted-foreground">{t('act.dt.chg.notFound')}</p>}
      {st.phase === 'error' && (
        <AlertBanner tone="danger" role="alert" className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={load}><RefreshCw aria-hidden="true" />{t('act.dt.retry')}</Button>}>
          {t('act.dt.chg.loadError')}
        </AlertBanner>
      )}
      {st.phase === 'ready' && r && (
        <div className="flex min-w-0 flex-col gap-3 text-sm">
          <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-muted-foreground">
            <EventBadge t={t} ev={r.event_type} />
            <ActorBadge r={r} t={t} />
            <time dateTime={r.at} className="tabular-nums">{formatIstanbul(r.at, locale)}</time>
            <span className="font-semibold text-foreground tabular-nums">{t('chg.detailRecord')} #{r.seq}</span>
          </div>
          {r.note && (
            <section className="rounded-md border bg-muted/40 px-3 py-2">
              <h4 className="m-0 mb-0.5 text-xs font-semibold text-muted-foreground">{t('chg.noteTitle')}</h4>
              <p className="m-0 text-sm break-words">{r.note}</p>
            </section>
          )}
          {diff.length > 0 ? (
            <section className="flex min-w-0 flex-col gap-1.5">
              <h4 className="m-0 text-xs font-semibold text-muted-foreground">{t('chg.diffTitle')} <span className="font-normal tabular-nums">({diff.length})</span></h4>
              <DiffTable className="w-full" fieldLabel={t('audit.diffField')} fromLabel={t('audit.diffFrom')} toLabel={t('audit.diffTo')}
                rows={diff.map((d) => [d.key, fieldLabel(t, d.key), fullValue(d.key, d.from, ctx), fullValue(d.key, d.to, ctx)])} />
            </section>
          ) : r.event_type !== 'CREATE' && r.event_type !== 'DELETE' && (
            <p className="m-0 text-xs text-muted-foreground">{t('chg.noDiff')}</p>
          )}
          {snapshot.length > 0 && (diff.length === 0 ? (
            <section className="flex min-w-0 flex-col gap-1.5">
              <h4 className="m-0 text-xs font-semibold text-muted-foreground">{snapshotTitle}</h4>
              {snapshotList}
            </section>
          ) : (
            <Collapsible open={stateOpen} onOpenChange={setStateOpen} className="flex min-w-0 flex-col gap-2">
              <CollapsibleTrigger asChild>
                <Button type="button" variant="outline" size="sm" className="h-9 self-start pointer-coarse:h-10">
                  <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', stateOpen && 'rotate-180')} />
                  {stateOpen ? t('chg.hideFullState') : snapshotTitle}
                </Button>
              </CollapsibleTrigger>
              <CollapsibleContent>{snapshotList}</CollapsibleContent>
            </Collapsible>
          ))}
        </div>
      )}
    </SectionCard>
  )
}
