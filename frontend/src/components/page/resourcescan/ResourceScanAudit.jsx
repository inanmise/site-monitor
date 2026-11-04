import { useMemo } from 'react'
import { Accessibility, Images, Info, ListChecks, Network, Recycle, RotateCw, Search, ShieldCheck } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import {
  AREA_STATE_TONE, AUDIT_AREAS, areaRecs, areaSummary, formatNumber, hasDeepAnalysis, plural,
} from './resourceScanModel.js'
import { RecItem } from './ResourceScanDashboard.jsx'
import { StateChip } from './ResourceScanAuditParts.jsx'
import { LoadingArea, MediaArea, WasteArea } from './ResourceScanAuditPerf.jsx'
import { ChecksArea, SecurityArea } from './ResourceScanAuditTrust.jsx'
import { TOUCH, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

const AREA_ICON = { loading: Network, waste: Recycle, media: Images, security: ShieldCheck, seo: Search, a11y: Accessibility }

/**
 * Kaynak analizi → AYRINTILI DENETİM (prs3, 2026-11-04 kullanıcı kararı: A yükleme ve ağ, B israf ve önbellek, C görsel ve
 * font, D güvenlik ve uyumluluk, E SEO + erişilebilirlik). Panoyu boğmasın diye ayrı bölüm: panodaki kategori puanı
 * kutucukları buraya ilgili alanla gelir; alan URL'de (`prs_v=audit-<alan>`, paylaşılabilir). Üstte altı alanlı gezinme
 * (her alanın bulgu sayısı / "sorun yok" / "bu analizde yok"), altında seçili alanın önerileri ve ayrıntılı blokları.
 *
 * Sunucu JavaScript ÇALIŞTIRMADAN, HTML ve yanıt başlıklarından hesaplar — tahmin / sezgisel olan her blok rozetle
 * işaretlidir. Eski analiz (algoritma 1 / 2) bu verileri taşımaz → "bu analizde yok — yeniden analiz edin" + yeniden
 * analiz düğmesi; asla "sorun yok". Tablolar KAP genişliğine göre tablo ↔ kart; adlar ve adresler kesilmez.
 */
export default function ResourceScanAudit({
  scan, analysis, items, area, onAreaChange, objectsAvailable, onShowFinding, onShowObjects, onOpenItem, canRun, running, onStart, starting,
}) {
  const t = useT()
  const locale = useDateLocale()
  const deep = hasDeepAnalysis(analysis, scan)
  const list = useMemo(() => (Array.isArray(items) ? items : []), [items])
  const byUrl = useMemo(() => {
    const m = new Map()
    for (const it of list) if (it?.url && !m.has(it.url)) m.set(it.url, it)
    return m
  }, [list])
  const findingCounts = useMemo(() => {
    const out = {}
    for (const it of list) for (const c of it?.findings || []) out[c] = (out[c] || 0) + 1
    return out
  }, [list])

  if (!deep) {
    return (
      // Panodan gelince odak buraya (alan başlığı yok) — yalnız programla odaklanır
      <div data-slot="prs-audit-old" data-focus-target="prs-audit" tabIndex={-1} className="rounded-xl focus-visible:outline-2 focus-visible:outline-ring">
        <StatusBlock tone="neutral" icon={ListChecks} title={t('prs.deep.old.title')} description={t('prs.deep.old.body')}
          actions={canRun && !running ? (
            <Button type="button" variant="outline" className={TOUCH} onClick={onStart} disabled={starting} data-slot="prs-audit-restart">
              <RotateCw aria-hidden="true" />{t('prs.restart')}
            </Button>
          ) : undefined} />
      </div>
    )
  }

  const ctx = {
    scan, analysis, items: list, t, locale, objectsAvailable,
    itemOf: (url) => (objectsAvailable && url ? byUrl.get(url) || null : null),
    findingCount: (code) => (objectsAvailable ? findingCounts[code] || 0 : 0),
    onShowFinding, onOpenItem: objectsAvailable ? onOpenItem : null,
  }
  const current = AUDIT_AREAS.includes(area) ? area : AUDIT_AREAS[0]

  return (
    <div data-slot="prs-audit" data-area={current} className="flex min-w-0 flex-col gap-3">
      <p data-slot="prs-audit-method" className={cn('m-0 flex items-start gap-1.5 text-xs text-muted-foreground', WRAP)}>
        <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />
        <span className={WRAP}>{t('prs.deep.method')}</span>
      </p>
      <Tabs value={current} onValueChange={onAreaChange} data-slot="prs-audit-tabs" className="min-w-0 gap-3">
        <div className="@container min-w-0">
          <TabsList aria-label={t('prs.deep.nav')}
            className="grid h-auto w-full min-w-0 grid-cols-2 gap-2 bg-transparent p-0 group-data-[orientation=horizontal]/tabs:h-auto @lg:grid-cols-3 @5xl:grid-cols-6">
            {AUDIT_AREAS.map((a) => <AreaTrigger key={a} area={a} analysis={analysis} scan={scan} t={t} locale={locale} />)}
          </TabsList>
        </div>
        {AUDIT_AREAS.map((a) => (
          <TabsContent key={a} value={a} className="min-w-0">
            <AreaView area={a} ctx={ctx} onShowObjects={onShowObjects} />
          </TabsContent>
        ))}
      </Tabs>
    </div>
  )
}

/** Gezinme kutucuğu: ikon + alan adı + durum rozeti (bulgu sayısı / sorun yok / bu analizde yok). */
function AreaTrigger({ area, analysis, scan, t, locale }) {
  const Icon = AREA_ICON[area] || ListChecks
  const sum = areaSummary(analysis, scan, area)
  const chip = sum.state === 'issues' ? plural(t, 'prs.deep.state.issues', sum.count, formatNumber(sum.count, locale))
    : t(`prs.deep.state.${sum.state}`)
  return (
    <TabsTrigger value={area} data-slot="prs-audit-tab" data-area={area} data-state-summary={sum.state}
      className={cn('h-auto min-w-0 flex-col items-start justify-start gap-1 rounded-lg border bg-card px-3 py-2 text-left whitespace-normal',
        'data-[state=active]:border-primary data-[state=active]:bg-primary/5 data-[state=active]:shadow-none', TOUCH)}>
      <span className="flex min-w-0 items-center gap-1.5 text-sm font-semibold text-foreground">
        <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /><span className={WRAP}>{t(`prs.deep.area.${area}`)}</span>
      </span>
      <StateChip tone={AREA_STATE_TONE[sum.state] || 'muted'} data-slot="prs-audit-tab-state" className="text-[11px]">{chip}</StateChip>
    </TabsTrigger>
  )
}

/** Seçili alan: başlık (bölüm geçişinde odak hedefi), açıklama, alanın önerileri, ayrıntılı bloklar. */
function AreaView({ area, ctx, onShowObjects }) {
  const { t, locale, analysis, items, objectsAvailable } = ctx
  const recs = areaRecs(analysis, area)
  const Icon = AREA_ICON[area] || ListChecks
  return (
    <div data-slot="prs-audit-area" data-area={area} className="flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-col gap-1">
        <h4 data-focus-target="prs-audit" tabIndex={-1}
          className="m-0 flex min-w-0 items-center gap-2 text-base font-semibold text-foreground focus-visible:rounded-sm focus-visible:outline-2 focus-visible:outline-ring">
          <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /><span className={WRAP}>{t(`prs.deep.area.${area}`)}</span>
        </h4>
        <p className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t(`prs.deep.area.${area}.desc`)}</p>
      </div>
      {recs.length > 0 && (
        <section data-slot="prs-audit-recs" aria-label={t('prs.deep.recsTitle')} className="flex min-w-0 flex-col gap-2">
          <h5 className="m-0 text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">
            {plural(t, 'prs.deep.recsCount', recs.length, formatNumber(recs.length, locale))}
          </h5>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {recs.map((r, i) => (
              <RecItem key={`${r.code}#${i}`} rec={r} items={items} t={t} locale={locale} objectsAvailable={objectsAvailable} onShowObjects={onShowObjects} />
            ))}
          </ul>
        </section>
      )}
      {area === 'loading' && <LoadingArea ctx={ctx} />}
      {area === 'waste' && <WasteArea ctx={ctx} />}
      {area === 'media' && <MediaArea ctx={ctx} />}
      {area === 'security' && <SecurityArea ctx={ctx} />}
      {(area === 'seo' || area === 'a11y') && <ChecksArea ctx={ctx} area={area} />}
    </div>
  )
}
