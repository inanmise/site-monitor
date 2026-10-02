import { useCallback, useEffect, useId, useMemo, useState } from 'react'
import { SignalHigh, RefreshCw, CircleAlert, Boxes } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { usePagination } from '../hooks/usePagination.js'
import PageHeader from './ui/PageHeader.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import { LiveIndicator } from './monitoring/OverviewParts.jsx'
import MaintenanceStatusNote from './maintenance/MaintenanceStatusNote.jsx'   // Sistem Bakım Modu notu (2026-10-02)
import { groupByTeam, defaultOpenGroups, isStatusPayload } from './statuspage/statusPageModel.js'
import {
  OverallBanner, TeamGroups, ActiveIncidents, ResolvedIncidents, MaintenanceCard, Legend,
} from './statuspage/StatusPageParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'

/**
 * KURUM İÇİ DURUM SAYFASI (2026-10-01, onaylı öneri 18) — operasyon ekipleri dışındakiler (diğer takımlar, yönetim) için
 * "hizmetler ayakta mı?" sorusunun tek sayfalık cevabı. Giriş gerektirir, dışarıya açılmaz (`GET /api/status-page`,
 * oturum açmış herkes). Hiçbir mevcut ekranı değiştirmez — yeni sekme (`?tab=status`).
 *
 * <p><b>Düzen (mobil-önce, shadcn):</b> başlık (canlı tazelik çipi + Yenile) → kurum durumu şeridi (ikon + başlık +
 * özet + durumlara göre sayılar) → ≥ 1280 px iki sütun: solda açık olaylar + takımlara göre hizmetler (Accordion; sorunlu
 * gruplar önce ve açık, sonra takım adı A→Z), sağda bakım pencereleri + son 7 günde çözülenler + lejant; daha dar
 * ekranlarda aynı sırayla alt alta. Hizmet satırında durum rozeti (ikon + metin, `data-state`), sayılar, sorunun
 * başlangıcı, bakım bitişi, 7 günlük kullanılabilirlik; izleme listesi (ad + durum) yalnız kendi takımlarının
 * hizmetlerinde (sunucu `monitors_visible`).
 *
 * <p>Veri dakikada bir yoklanır (`useVisibleInterval` — sekme gizliyken durur); sunucu 30 sn paylaşır, Yenile `fresh=1`
 * ile belleği en fazla 5 sn'de bir atlar. URL durumu `sp_` önekiyle (yalnız takım listesi sayfalaması: `sp_page`, `sp_ps`).
 *
 * <p>Test kancaları: `data-slot="sp-page"`, `sp-skeleton`, `sp-error`, `sp-stale-data`, `sp-banner` (`data-state`),
 * `sp-headline`, `sp-by-state`, `sp-state` (`data-state`), `sp-teams`, `sp-team` (`data-team`, `data-worst`),
 * `sp-team-name`, `sp-service` (`data-state`, `data-key`), `sp-service-name`, `sp-uptime`, `sp-monitors`, `sp-monitor`
 * (`data-status`), `sp-incidents`, `sp-incident` (`data-severity`), `sp-resolved`, `sp-resolved-item`, `sp-maintenance`,
 * `sp-window` (`data-state`), `sp-legend`, `sp-legend-item` (`data-state`), `sp-empty`.
 */

/** Takım listesinin paylaşılabilir sayfa/boyut adresi (usePagination `url`; sabit referans). */
const SP_PAGE_URL = Object.freeze({ pageKey: 'sp_page', sizeKey: 'sp_ps' })
/** Yoklama aralığı (ms) — sunucu belleği 30 sn; sekme gizliyken durur. */
export const STATUS_POLL_MS = 60_000

function StatusSkeleton({ label }) {
  return (
    <div role="status" aria-live="polite" data-slot="sp-skeleton" className="flex flex-col gap-4">
      <span className="sr-only">{label}</span>
      <Skeleton className="h-28 w-full rounded-xl motion-reduce:animate-none" />
      <div className="grid gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-3">
          {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-14 w-full motion-reduce:animate-none" />)}
        </div>
        <Skeleton className="h-48 w-full motion-reduce:animate-none" />
      </div>
    </div>
  )
}

export default function StatusPage() {
  const t = useT()
  const servicesTitleId = useId()
  const [state, setState] = useState({ loading: true, error: null, data: null, at: null })
  // Kullanıcı bir grubu açıp kapatana dek açık gruplar veriden türetilir (sorunlu gruplar kendiliğinden açılır).
  const [openGroups, setOpenGroups] = useState(null)

  // fresh: Yenile düğmesi — sunucu belleğini atlar (en fazla 5 sn'de bir); yoklama bellekten okur.
  const load = useCallback(async (fresh = false) => {
    try {
      const res = await api.statusPage.get(fresh === true)
      if (res?.success && isStatusPayload(res.data)) setState({ loading: false, error: null, data: res.data, at: new Date() })
      else setState((s) => ({ ...s, loading: false, error: res?.error || t('sp.loadError') }))
    } catch (e) {
      setState((s) => ({ ...s, loading: false, error: e?.message || t('sp.loadError') }))
    }
  }, [t])
  useEffect(() => { load() }, [load])
  useVisibleInterval(load, STATUS_POLL_MS, false)
  const refresh = () => { setState((s) => ({ ...s, loading: true })); load(true) }

  const data = state.data
  const services = useMemo(() => (Array.isArray(data?.services) ? data.services : []), [data])
  const groups = useMemo(() => groupByTeam(services, t), [services, t])
  const open = openGroups ?? defaultOpenGroups(groups)
  const pager = usePagination(groups, { listKey: 'status-page-teams', preset: 'panel', url: SP_PAGE_URL })
  const nowMs = useMemo(() => (state.at ? state.at.getTime() : Date.now()), [state.at])

  return (
    <div data-slot="sp-page" className="min-w-0" aria-busy={state.loading || undefined}>
      <PageHeader icon={SignalHigh} title={t('sp.title')} description={t('sp.subtitle')}
        meta={data ? <LiveIndicator generatedAt={data.generated_at} fetchedAt={state.at} failed={!!state.error} loading={state.loading} /> : null}
        actions={
          <Button type="button" variant="outline" size="sm" onClick={refresh} aria-busy={state.loading || undefined} className="pointer-coarse:h-10">
            <RefreshCw aria-hidden="true" className={cn(state.loading && 'animate-spin motion-reduce:animate-none')} />{t('sp.refresh')}
          </Button>
        } />

      {state.loading && !data && <StatusSkeleton label={t('sp.loading')} />}
      {state.error && !data && (
        <div data-slot="sp-error">
          <StatusBlock tone="danger" icon={CircleAlert} title={t('sp.loadError')} description={state.error}
            actions={<Button type="button" variant="outline" onClick={refresh} className="pointer-coarse:h-10">{t('sp.retry')}</Button>} />
        </div>
      )}

      {data && (
        <div className={cn('flex min-w-0 flex-col gap-4 transition-opacity motion-reduce:transition-none', state.loading && 'opacity-70')}>
          {state.error && (
            <div data-slot="sp-stale-data">
              <AlertBanner tone="warning" title={t('sp.staleData.title')}
                actions={<Button type="button" variant="outline" size="sm" onClick={refresh} className="pointer-coarse:h-10">{t('sp.retry')}</Button>}>
                {t('sp.staleData.text', state.error)}
              </AlertBanner>
            </div>
          )}

          <MaintenanceStatusNote note={data.system_maintenance} />
          <OverallBanner data={data} />

          <div className="grid min-w-0 gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
            <div className="flex min-w-0 flex-col gap-4">
              <ActiveIncidents incidents={data.incidents} nowMs={nowMs} />
              <section aria-labelledby={servicesTitleId} className="flex min-w-0 flex-col gap-3">
                <div className="min-w-0">
                  <h3 id={servicesTitleId} className="m-0 text-base font-semibold tracking-tight">{t('sp.services.title')}</h3>
                  <p className="m-0 mt-0.5 text-xs text-muted-foreground">{t('sp.services.desc')}</p>
                </div>
                {groups.length === 0 ? (
                  <div data-slot="sp-empty">
                    <StatusBlock tone="neutral" icon={Boxes} title={t('sp.empty.title')} description={t('sp.empty.text')} className="rounded-xl border py-10" />
                  </div>
                ) : (
                  <>
                    <TeamGroups groups={pager.pageItems} open={open} onOpenChange={setOpenGroups} nowMs={nowMs} />
                    <PaginationBar {...pager} />
                  </>
                )}
              </section>
            </div>
            <aside className="flex min-w-0 flex-col gap-4" aria-label={t('sp.aside')}>
              <MaintenanceCard maintenance={data.maintenance} />
              <ResolvedIncidents incidents={data.incidents} />
              <Legend />
            </aside>
          </div>
        </div>
      )}
    </div>
  )
}
