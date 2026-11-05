import { useMemo, useState } from 'react'
import { Globe, Layers, Link2, TriangleAlert } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { categoryLabel, formatMs, formatNumber, formatSize, gradeTone, isCapped, plural, problemText, scoreTone } from './resourceScanModel.js'
import {
  SITE_SORTS, SITE_TABLE_MIN_PX, pageStatusText, pageStatusTone, recShortLabel, siteModel, sortSitePages,
} from './resourceScanReportModel.js'
import { CHIP, Section, UrlText, WRAP } from './ResourceScanParts.jsx'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Kaynak analizi → SAYFALAR (prs3 G, 2026-11-04): çok sayfalı (site) analizin sonucu. İzlemenin sayfası tam analiz edilir
 * (pano / nesneler bugünkü gibi); burada sitenin diğer sayfaları: toplamlar, en kötü sayfalar, sayfa tablosu (kap ≥ 760 px)
 * ya da kartlar (not, süre, boyut, istek, güvenlik / SEO / erişilebilirlik puanları, öne çıkan öneriler, benzersiz bayt),
 * ortak kaynaklar (≥ 2 sayfada, bir kez ölçülür). Açılamayan sayfa nedeniyle; süre dolunca taranmayan sayfa ayrı söylenir.
 * Puanı hesaplanamayan "—" (asla 0 / yeşil değil). Adresler kesilmez.
 */
export default function ResourceScanSite({ scan, analysis }) {
  const t = useT()
  const locale = useDateLocale()
  const model = useMemo(() => siteModel(analysis?.site), [analysis])
  const [sort, setSort] = useState('grade_asc')
  const [measureRef, width] = useElementWidth()
  const pages = useMemo(() => (model ? sortSitePages(model.pages, sort, locale) : []), [model, sort, locale])

  if (!model) {
    return (
      <div data-slot="prs-site" data-state="missing">
        <StatusBlock tone="neutral" icon={Globe} title={t('prs.site.missingTitle')} description={t('prs.site.missingBody')} />
      </div>
    )
  }
  const layout = width > 0 && width < SITE_TABLE_MIN_PX ? 'cards' : 'table'
  const tt = model.totals
  return (
    <div data-slot="prs-site" data-state="ok" className="flex min-w-0 flex-col gap-3">
      <Section slot="prs-site-summary" icon={Globe} title={t('prs.site.title')} titleFocusable
        description={plural(t, 'prs.site.desc', tt.pages, formatNumber(tt.pages, locale))}>
        <ul data-slot="prs-site-totals" className="m-0 grid min-w-0 list-none grid-cols-2 gap-2 p-0 sm:grid-cols-3 lg:grid-cols-5">
          <Tile k="scanned" label={t('prs.site.tile.scanned')} value={`${formatNumber(tt.scanned, locale)} / ${formatNumber(tt.pages, locale)}`} />
          <Tile k="failed" label={t('prs.site.tile.notOpened')} value={formatNumber(tt.failed, locale)} tone={tt.failed > 0 ? 'danger' : null} />
          <Tile k="avg" label={t('prs.site.tile.avgGrade')} value={tt.avgGrade == null ? '—' : formatNumber(tt.avgGrade, locale)}
            tone={tt.avgGrade == null ? null : scoreTone(tt.avgGrade)} />
          <Tile k="unique" label={t('prs.site.tile.unique')} value={formatSize(tt.uniqueBytes, { locale })} />
          <Tile k="shared" label={t('prs.site.tile.shared')} value={formatNumber(tt.sharedCount, locale)} />
        </ul>
        {isCapped(scan) && (
          <div data-slot="prs-site-capped">
            <AlertBanner tone="warning" className="mb-0" icon={TriangleAlert}>{t('prs.site.capped')}</AlertBanner>
          </div>
        )}
        {model.worst.length > 0 && (
          <div data-slot="prs-site-worst" className="flex min-w-0 flex-col gap-1.5">
            <h5 className="m-0 text-xs font-semibold tracking-[.04em] text-muted-foreground uppercase">{t('prs.site.worstTitle')}</h5>
            <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
              {model.worst.map((p) => (
                <li key={p.i} data-slot="prs-site-worst-row" className="flex min-w-0 flex-wrap items-center gap-2 rounded-lg border px-3 py-2">
                  <GradeBadge p={p} t={t} />
                  <UrlText url={p.url} t={t} className="min-w-0 flex-1 text-sm" />
                  {p.recs.length > 0 && <span className={cn('text-xs text-muted-foreground', WRAP)}>{recShortLabel(p.recs[0], t)}</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
      </Section>

      <Section slot="prs-site-pages" icon={Layers} title={t('prs.site.pagesTitle')}
        actions={(
          <label className="flex min-w-0 items-center gap-2 text-xs font-medium text-muted-foreground">
            <span>{t('prs.site.sort')}</span>
            <NativeSelect data-slot="prs-site-sort" value={sort} onChange={(e) => setSort(e.target.value)} className="pointer-coarse:h-10">
              {SITE_SORTS.map((s) => <NativeSelectOption key={s} value={s}>{t(`prs.site.sortBy.${s}`)}</NativeSelectOption>)}
            </NativeSelect>
          </label>
        )}>
        <div ref={measureRef} data-slot="prs-site-list" data-layout={layout} className="min-w-0">
          {pages.length === 0 ? (
            <p className="m-0 text-sm text-muted-foreground">{t('prs.site.noPages')}</p>
          ) : layout === 'table' ? (
            <Table className="table-fixed">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[34%] whitespace-normal">{t('prs.site.col.page')}</TableHead>
                  <TableHead className="w-[9%] whitespace-normal">{t('prs.grade')}</TableHead>
                  <TableHead className="w-[15%] whitespace-normal">{t('prs.site.col.loadSize')}</TableHead>
                  <TableHead className="w-[17%] whitespace-normal">{t('prs.site.col.scores')}</TableHead>
                  <TableHead className="w-[25%] whitespace-normal">{t('prs.site.col.recs')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pages.map((p) => (
                  <TableRow key={p.i} data-slot="prs-site-page" data-status={p.status} data-main={p.main ? 'true' : undefined}>
                    <TableCell className="align-top whitespace-normal"><PageCell p={p} t={t} locale={locale} /></TableCell>
                    <TableCell className="align-top whitespace-normal"><GradeBadge p={p} t={t} /></TableCell>
                    <TableCell className="align-top whitespace-normal text-xs tabular-nums">
                      <span className="block">{formatMs(p.load, locale)}</span>
                      <span className="block text-muted-foreground">{formatSize(p.size, { locale })} · {plural(t, 'prs.site.requests', p.requests ?? 0, formatNumber(p.requests, locale))}</span>
                    </TableCell>
                    <TableCell className="align-top whitespace-normal"><Scores p={p} t={t} locale={locale} /></TableCell>
                    <TableCell className="align-top whitespace-normal"><Recs p={p} t={t} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {pages.map((p) => (
                <li key={p.i} data-slot="prs-site-page" data-status={p.status} data-main={p.main ? 'true' : undefined}
                  className="flex min-w-0 flex-col gap-2 rounded-lg border px-3 py-2.5">
                  <div className="flex min-w-0 items-start gap-2">
                    <GradeBadge p={p} t={t} />
                    <div className="min-w-0 flex-1"><PageCell p={p} t={t} locale={locale} /></div>
                  </div>
                  {p.status === 'ok' && (
                    <span className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground tabular-nums">
                      <span>{t('prs.site.loadShort', formatMs(p.load, locale))}</span>
                      <span>{formatSize(p.size, { locale })}</span>
                      <span>{plural(t, 'prs.site.requests', p.requests ?? 0, formatNumber(p.requests, locale))}</span>
                    </span>
                  )}
                  <Scores p={p} t={t} locale={locale} />
                  <Recs p={p} t={t} />
                </li>
              ))}
            </ul>
          )}
        </div>
      </Section>

      <Section slot="prs-site-shared" icon={Link2} title={t('prs.site.sharedTitle')} description={t('prs.site.sharedDesc')}>
        {model.shared.length === 0 ? (
          <p data-slot="prs-site-shared-empty" className="m-0 text-sm text-muted-foreground">{t('prs.site.sharedNone')}</p>
        ) : (
          <ul className="m-0 grid min-w-0 list-none grid-cols-1 gap-1.5 p-0 lg:grid-cols-2">
            {model.shared.map((s, i) => (
              <li key={`${s.url}#${i}`} data-slot="prs-site-shared-row" className="flex min-w-0 flex-col gap-1 rounded-lg border px-3 py-2">
                <UrlText url={s.url} t={t} className="text-xs" />
                <span className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground tabular-nums">
                  <ToneBadge tone="muted" className={CHIP}>{categoryLabel(s.category, t)}</ToneBadge>
                  <span>{formatSize(s.bytes, { locale })}</span>
                  {s.pages != null && <span>{plural(t, 'prs.site.onPages', s.pages, formatNumber(s.pages, locale))}</span>}
                </span>
              </li>
            ))}
          </ul>
        )}
        {model.totals.sharedCount > model.shared.length && (
          <p className="m-0 text-xs text-muted-foreground">{t('prs.site.sharedMore', formatNumber(model.totals.sharedCount - model.shared.length, locale))}</p>
        )}
      </Section>
    </div>
  )
}

function Tile({ k, label, value, tone }) {
  return (
    <li data-slot="prs-site-tile" data-key={k} className="flex min-w-0 flex-col gap-1 rounded-lg border bg-muted/30 px-3 py-2.5">
      <span className={cn('text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs', WRAP)}>{label}</span>
      <span className={cn('text-lg leading-tight font-bold tabular-nums', tone === 'danger' ? 'text-destructive' : 'text-foreground')}>{value}</span>
    </li>
  )
}

/** Sayfanın notu (taranmışsa harfle; taranmamış / açılamamış sayfa durum rozeti). */
function GradeBadge({ p, t }) {
  if (p.status !== 'ok') {
    return <ToneBadge tone={pageStatusTone(p.status)} className={cn(CHIP, 'shrink-0')} data-slot="prs-site-status">{pageStatusText(p.status, t)}</ToneBadge>
  }
  return (
    <ToneBadge tone={p.grade == null ? 'muted' : gradeTone(p.letter)} className={cn(CHIP, 'shrink-0 font-bold tabular-nums')} data-slot="prs-site-grade">
      {p.grade ?? '—'}{p.letter ? ` · ${p.letter}` : ''}
    </ToneBadge>
  )
}

/** Sunucu hata alanı: kod ise çevrilir (ham kod gösterilmez), metinse olduğu gibi. */
function pageErrorText(error, t) {
  if (!error) return ''
  const s = String(error)
  const http = /^PAGE_HTTP:(\d{3})$/.exec(s)
  if (http) return t('prs.site.err.http', http[1])
  if (/^[A-Z][A-Z0-9_]*(?::[A-Za-z0-9_.-]+)?$/.test(s)) return problemText(s.split(':').pop(), t)
  return s
}

function PageCell({ p, t, locale }) {
  return (
    <span className="flex min-w-0 flex-col gap-1">
      <UrlText url={p.url} t={t} className="text-sm" />
      <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        {p.main && <ToneBadge tone="info" className={CHIP} data-slot="prs-site-main">{t('prs.site.main')}</ToneBadge>}
        {p.status === 'ok' && p.unique != null && <span className="tabular-nums">{t('prs.site.unique', formatSize(p.unique, { locale }))}</span>}
        {p.status === 'ok' && p.shared != null && <span className="tabular-nums">{plural(t, 'prs.site.sharedCount', p.shared, formatNumber(p.shared, locale))}</span>}
      </span>
      {p.status === 'failed' && <span data-slot="prs-site-error" className={cn('text-xs text-destructive', WRAP)}>{pageErrorText(p.error, t) || pageStatusText(p.status, t)}</span>}
      {p.status === 'skipped' && <span className={cn('text-xs text-muted-foreground', WRAP)}>{t('prs.site.skippedBody')}</span>}
    </span>
  )
}

function Scores({ p, t, locale }) {
  if (p.status !== 'ok') return null
  const list = [['security', p.security], ['seo', p.seo], ['a11y', p.a11y]]
  return (
    <span data-slot="prs-site-scores" className="flex flex-wrap gap-1">
      {list.map(([k, v]) => (
        <ToneBadge key={k} tone={v == null ? 'muted' : scoreTone(v)} className={cn(CHIP, 'tabular-nums')} data-score={k}>
          {t(`prs.site.score.${k}`)} {v == null ? '—' : formatNumber(v, locale)}
        </ToneBadge>
      ))}
    </span>
  )
}

function Recs({ p, t }) {
  if (p.status !== 'ok') return null
  if (!p.recs.length) return <span className="text-xs text-muted-foreground">{t('prs.site.recsNone')}</span>
  return (
    <span data-slot="prs-site-recs" className="flex flex-wrap gap-1">
      {p.recs.map((c) => <ToneBadge key={c} tone="warning" className={CHIP} data-code={c}>{recShortLabel(c, t)}</ToneBadge>)}
    </span>
  )
}
