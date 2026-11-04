import { Ban, Copy, Database, FileCode2, Image as ImageIcon, Link2, Map as MapIcon, Server, Signal, Type } from 'lucide-react'
import { ProgressBar } from '../../ui/Progress.jsx'
import {
  CACHE_BUCKET_TONE, areaData, cacheText, cachingView, duplicateRows, fontView, formatMs, formatNumber, formatPct, formatSize, hintMore, hintRows,
  HINT_STATE_TONE, HOST_SKIP_REASONS, hostRows, htmlStats, imageIssueGroups, isHttp1, libraryVersionRows, plural, profileRows,
  renderBlockingView, sourceMapRows, unminifiedRows,
} from './resourceScanModel.js'
import {
  EmptyLine, KindBadge, NotInScan, ResponsiveRows, ShowObjectsButton, StatTiles, StateChip, UrlCell, UrlList,
} from './ResourceScanAuditParts.jsx'
import { Section, SmallBadge, UrlText, WRAP } from './ResourceScanParts.jsx'
import { cn } from '@/lib/utils'

/**
 * Ayrıntılı denetim — PERFORMANS alanları (prs3): A yükleme ve ağ (oluşturmayı engelleyenler + tahmini ilk boyama,
 * kaynak ipuçları, sunucular ve bağlantı maliyeti, ağ profilleri), B israf ve önbellek (yinelenenler, çok sürümlü
 * kütüphane, küçültülmemiş, açık kaynak haritası, HTML, önbellek + ikinci ziyaret), C görsel ve yazı tipi. Her blok kendi
 * verisi yoksa "bu analizde yok" der; tahmin / sezgisel bloklar rozetle işaretlidir. `ctx` = ResourceScanAudit bağlamı.
 */

const fmtSize = (v, locale) => formatSize(v, { locale })

// ── A — yükleme ve ağ ──────────────────────────────────────────────────────

export function LoadingArea({ ctx }) {
  const data = areaData(ctx.analysis, 'loading')
  if (!data) return <NotInScan t={ctx.t} slot="prs-area-missing" />
  return (
    <>
      <RenderBlockingBlock data={data} ctx={ctx} />
      <HintsBlock data={data} ctx={ctx} />
      <HostsBlock data={data} ctx={ctx} />
      <ProfilesBlock data={data} ctx={ctx} />
    </>
  )
}

function RenderBlockingBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = renderBlockingView(data)
  return (
    <Section slot="prs-a-rb" icon={Ban} title={t('prs.deep.rb.title')} description={t('prs.deep.rb.desc')}>
      {!v ? <NotInScan t={t} /> : (
        <>
          <StatTiles tiles={[
            { k: 'count', label: t('prs.deep.rb.count'), value: formatNumber(v.count, locale), tone: v.count > 0 ? 'warning' : undefined },
            { k: 'bytes', label: t('prs.deep.rb.bytes'), value: fmtSize(v.bytes, locale) },
            { k: 'paint', label: t('prs.deep.rb.firstPaint'), value: v.firstPaintMs != null ? `~${formatMs(v.firstPaintMs, locale)}` : '—',
              sub: <><KindBadge kind="estimate" t={t} /> {t('prs.deep.rb.firstPaintSub')}</> },
          ]} />
          {v.rows.length === 0 ? <EmptyLine good>{t('prs.deep.rb.none')}</EmptyLine> : (
            <ResponsiveRows slot="prs-a-rb-list" rowSlot="prs-a-rb-row" t={t} locale={locale} rows={v.rows} more={v.more}
              rowKey={(r) => r.url} rowAttrs={(r) => ({ 'data-kind': r.kind })} caption={t('prs.deep.rb.title')}
              columns={[
                { key: 'url', primary: true, head: t('prs.deep.col.resource'), cell: (r) => <UrlCell url={r.url} t={t} item={ctx.itemOf(r.url)} onOpenItem={ctx.onOpenItem} /> },
                { key: 'kind', head: t('prs.deep.col.kind'), className: 'w-[15%]', cell: (r) => t(`prs.deep.rb.kind.${r.kind}`) },
                { key: 'reason', head: t('prs.deep.col.reason'), className: 'w-[22%]', cell: (r) => t(`prs.deep.rb.reason.${r.reason}`) },
                { key: 'bytes', head: t('prs.col.size'), align: 'right', className: 'w-[12%]', cell: (r) => fmtSize(r.bytes, locale) },
                { key: 'dur', head: t('prs.col.time'), align: 'right', className: 'w-[11%]', cell: (r) => formatMs(r.durationMs, locale) },
              ]} />
          )}
          <ShowObjectsButton count={ctx.findingCount('RENDER_BLOCKING')} onClick={() => ctx.onShowFinding?.('RENDER_BLOCKING')} t={t} locale={locale}
            label={t('prs.deep.rb.title')} />
        </>
      )}
    </Section>
  )
}

function HintsBlock({ data, ctx }) {
  const { t, locale } = ctx
  const rows = hintRows(data)
  return (
    <Section slot="prs-a-hints" icon={Link2} title={t('prs.deep.hints.title')} description={t('prs.deep.hints.desc')}>
      {!rows ? <NotInScan t={t} /> : rows.length === 0 ? <EmptyLine>{t('prs.deep.hints.none')}</EmptyLine> : (
        <ResponsiveRows slot="prs-a-hints-list" rowSlot="prs-a-hint" t={t} locale={locale} rows={rows} more={hintMore(data)} rowKey={(r) => `${r.type}:${r.target}`}
          rowAttrs={(r) => ({ 'data-type': r.type, 'data-state': r.state })} caption={t('prs.deep.hints.title')}
          columns={[
            { key: 'target', primary: true, head: t('prs.deep.col.target'),
              cell: (r) => (/^https?:\/\//i.test(r.target) ? <UrlText url={r.target} t={t} className="text-xs" /> : <span className={cn('text-xs font-medium', WRAP)}>{r.target}</span>) },
            { key: 'type', head: t('prs.deep.col.hint'), className: 'w-[20%]', cell: (r) => <code className={cn('font-mono text-xs', WRAP)}>{t(`prs.deep.hints.type.${r.type}`)}</code> },
            { key: 'state', head: t('prs.col.status'), className: 'w-[18%]',
              cell: (r) => <StateChip tone={HINT_STATE_TONE[r.state]} data-state={r.state}>{t(`prs.deep.hints.state.${r.state}`)}</StateChip> },
            { key: 'detail', head: t('prs.deep.col.detail'), className: 'w-[24%]', cell: (r) => hintDetail(r, t, locale) },
          ]} />
      )}
    </Section>
  )
}

function hintDetail(r, t, locale) {
  if (r.type === 'missing_preconnect') {
    const base = plural(t, 'prs.deep.hints.missingDetail', r.requests ?? 0, formatNumber(r.requests, locale), fmtSize(r.bytes, locale))
    return r.blocking ? `${base} · ${t('prs.deep.hints.blocking')}` : base
  }
  if (r.type === 'preload') return r.as ? t('prs.deep.hints.as', r.as) : '—'
  if (r.type === 'fetchpriority') return r.value ? t('prs.deep.hints.priority', r.value) : '—'
  return '—'
}

function HostsBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = hostRows(data)
  return (
    <Section slot="prs-a-hosts" icon={Server} title={t('prs.deep.hosts.title')} description={t('prs.deep.hosts.desc')}>
      {!v ? <NotInScan t={t} /> : (
        <>
          <p data-slot="prs-a-hosts-summary" className={cn('m-0 text-sm text-foreground', WRAP)}>
            {plural(t, 'prs.deep.hosts.summary', v.total, formatNumber(v.total, locale))}
            {v.http1FirstParty != null && <> {plural(t, 'prs.deep.hosts.http1', v.http1FirstParty, formatNumber(v.http1FirstParty, locale))}</>}
          </p>
          {v.rows.length === 0 ? <EmptyLine>{t('prs.deep.hosts.none')}</EmptyLine> : (
            <ResponsiveRows slot="prs-a-hosts-list" rowSlot="prs-a-host" minPx={760} t={t} locale={locale} rows={v.rows} more={v.more}
              rowKey={(r) => r.host} rowAttrs={(r) => ({ 'data-host': r.host, 'data-measured': String(r.measured) })} caption={t('prs.deep.hosts.title')}
              columns={[
                { key: 'host', primary: true, head: t('prs.deep.col.host'), cell: (r) => (
                  <span className="flex min-w-0 flex-col items-start gap-1">
                    <span className={cn('text-xs font-medium', WRAP)}>{r.host}</span>
                    <span className="flex flex-wrap gap-1">
                      {r.thirdParty && <SmallBadge>{t('prs.obj.thirdParty')}</SmallBadge>}
                      {r.h3 && <SmallBadge tone="info">{t('prs.deep.hosts.h3')}</SmallBadge>}
                    </span>
                  </span>
                ) },
                { key: 'req', head: t('prs.deep.col.requests'), align: 'right', className: 'w-[10%]', cell: (r) => formatNumber(r.requests, locale) },
                { key: 'bytes', head: t('prs.col.size'), align: 'right', className: 'w-[12%]', cell: (r) => fmtSize(r.bytes, locale) },
                { key: 'http', head: t('prs.deep.col.http'), className: 'w-[11%]', cell: (r) => (r.httpVersion
                  ? <span className={cn(isHttp1(r.httpVersion) && 'font-semibold text-amber-700 dark:text-amber-300')}>{r.httpVersion}</span> : '—') },
                { key: 'conn', head: t('prs.deep.col.connection'), className: 'w-[26%]', cell: (r) => connectionText(r, t, locale) },
                { key: 'total', head: t('prs.deep.col.connTotal'), align: 'right', className: 'w-[11%]', cell: (r) => (r.totalMs != null ? formatMs(r.totalMs, locale) : '—') },
              ]} />
          )}
        </>
      )}
    </Section>
  )
}

function connectionText(r, t, locale) {
  if (!r.measured) return t('prs.deep.hosts.notMeasured', t(`prs.deep.hosts.reason.${HOST_SKIP_REASONS.includes(r.reason) ? r.reason : 'other'}`))
  const part = (v) => (v != null ? formatMs(v, locale) : '—')
  return t('prs.deep.hosts.parts', part(r.dnsMs), part(r.connectMs), part(r.tlsMs))
}

function ProfilesBlock({ data, ctx }) {
  const { t, locale } = ctx
  const rows = profileRows(data)
  return (
    <Section slot="prs-a-profiles" icon={Signal} title={t('prs.deep.profiles.title')} description={t('prs.deep.profiles.desc')}
      actions={<KindBadge kind="estimate" t={t} />}>
      {!rows ? <NotInScan t={t} /> : rows.length === 0 ? <EmptyLine>{t('prs.deep.profiles.none')}</EmptyLine> : (
        <ul data-slot="prs-a-profiles-list" className="m-0 grid min-w-0 list-none grid-cols-1 gap-2 p-0">
          {rows.map((r) => (
            <li key={r.key} data-slot="prs-a-profile" data-key={r.key} className="flex min-w-0 flex-col gap-1 rounded-lg border px-3 py-2">
              <div className="flex min-w-0 flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
                <span className="text-sm font-semibold text-foreground">{t(`prs.deep.profiles.${r.key}`)}</span>
                <span className="text-xs text-muted-foreground tabular-nums">
                  {t('prs.deep.profiles.net', r.rttMs != null ? formatMs(r.rttMs, locale) : '—', r.mbps != null ? formatNumber(r.mbps, locale, 1) : '—')}
                </span>
              </div>
              <ProgressBar value={r.bar} max={100} size="sm" decorative />
              <span className={cn('text-xs text-foreground tabular-nums', WRAP)}>
                {t('prs.deep.profiles.result', r.loadMs != null ? formatMs(r.loadMs, locale) : '—', r.firstPaintMs != null ? formatMs(r.firstPaintMs, locale) : '—')}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

// ── B — israf ve önbellek ──────────────────────────────────────────────────

export function WasteArea({ ctx }) {
  const data = areaData(ctx.analysis, 'waste')
  if (!data) return <NotInScan t={ctx.t} slot="prs-area-missing" />
  return (
    <>
      <DuplicatesBlock data={data} ctx={ctx} />
      <LibraryVersionsBlock data={data} ctx={ctx} />
      <UnminifiedBlock data={data} ctx={ctx} />
      <SourceMapsBlock data={data} ctx={ctx} />
      <HtmlBlock data={data} ctx={ctx} />
      <CachingBlock data={data} ctx={ctx} />
    </>
  )
}

function DuplicatesBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = duplicateRows(data)
  return (
    <Section slot="prs-b-dup" icon={Copy} title={t('prs.deep.dup.title')} description={t('prs.deep.dup.desc')}>
      {!v ? <NotInScan t={t} /> : v.rows.length === 0 ? <EmptyLine good>{t('prs.deep.dup.none')}</EmptyLine> : (
        <>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {v.rows.map((r, i) => (
              <li key={`${r.urls[0]}#${i}`} data-slot="prs-b-dup-row" data-kind={r.kind} className="flex min-w-0 flex-col gap-1.5 rounded-lg border px-3 py-2">
                <span className="flex min-w-0 flex-wrap items-center gap-2">
                  <StateChip tone="warning">{t(`prs.deep.dup.kind.${r.kind}`)}</StateChip>
                  {r.wasted != null && <span className="text-xs text-muted-foreground tabular-nums">{t('prs.deep.dup.wasted', fmtSize(r.wasted, locale))}</span>}
                </span>
                <ul className="m-0 flex list-none flex-col gap-1 p-0">
                  {r.urls.map((u, j) => <li key={`${u}#${j}`}><UrlCell url={u} t={t} item={ctx.itemOf(u)} onOpenItem={ctx.onOpenItem} /></li>)}
                </ul>
              </li>
            ))}
          </ul>
          {v.more > 0 && <p className="m-0 text-xs text-muted-foreground">{plural(t, 'prs.deep.serverMore', v.more, formatNumber(v.more, locale))}</p>}
          <ShowObjectsButton count={ctx.findingCount('DUPLICATE')} onClick={() => ctx.onShowFinding?.('DUPLICATE')} t={t} locale={locale} label={t('prs.deep.dup.title')} />
        </>
      )}
    </Section>
  )
}

function LibraryVersionsBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = libraryVersionRows(data)
  return (
    <Section slot="prs-b-libs" icon={FileCode2} title={t('prs.deep.libv.title')} description={t('prs.deep.libv.desc')}>
      {!v ? <NotInScan t={t} /> : v.rows.length === 0 ? <EmptyLine good>{t('prs.deep.libv.none')}</EmptyLine> : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {v.rows.map((r, i) => (
            <li key={`${r.name}#${i}`} data-slot="prs-b-lib-row" data-name={r.name} className="flex min-w-0 flex-col gap-1.5 rounded-lg border px-3 py-2">
              <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                <span className={cn('text-sm font-semibold text-foreground', WRAP)}>{r.name}</span>
                {r.versions.map((ver) => <SmallBadge key={ver} tone="warning">{ver}</SmallBadge>)}
              </span>
              <ul className="m-0 flex list-none flex-col gap-1 p-0">
                {r.urls.map((u, j) => <li key={`${u}#${j}`}><UrlCell url={u} t={t} item={ctx.itemOf(u)} onOpenItem={ctx.onOpenItem} /></li>)}
              </ul>
            </li>
          ))}
        </ul>
      )}
      {v && v.rows.length > 0 && v.more > 0 && <p className="m-0 text-xs text-muted-foreground">{plural(t, 'prs.deep.serverMore', v.more, formatNumber(v.more, locale))}</p>}
    </Section>
  )
}

function UnminifiedBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = unminifiedRows(data)
  return (
    <Section slot="prs-b-unmin" icon={FileCode2} title={t('prs.deep.unmin.title')} description={t('prs.deep.unmin.desc')}
      actions={<KindBadge kind="heuristic" t={t} />}>
      {!v ? <NotInScan t={t} /> : v.rows.length === 0 ? <EmptyLine good>{t('prs.deep.unmin.none')}</EmptyLine> : (
        <>
          <ResponsiveRows slot="prs-b-unmin-list" rowSlot="prs-b-unmin-row" t={t} locale={locale} rows={v.rows} more={v.more} rowKey={(r) => r.url}
            caption={t('prs.deep.unmin.title')}
            columns={[
              { key: 'url', primary: true, head: t('prs.deep.col.resource'), cell: (r) => <UrlCell url={r.url} t={t} item={ctx.itemOf(r.url)} onOpenItem={ctx.onOpenItem} /> },
              { key: 'bytes', head: t('prs.col.size'), align: 'right', className: 'w-[16%]', cell: (r) => fmtSize(r.bytes, locale) },
              { key: 'saving', head: t('prs.deep.col.estSaving'), align: 'right', className: 'w-[22%]', cell: (r) => (r.saving != null ? `~${fmtSize(r.saving, locale)}` : '—') },
            ]} />
          <ShowObjectsButton count={ctx.findingCount('UNMINIFIED')} onClick={() => ctx.onShowFinding?.('UNMINIFIED')} t={t} locale={locale} label={t('prs.deep.unmin.title')} />
        </>
      )}
    </Section>
  )
}

function SourceMapsBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = sourceMapRows(data)
  const exposed = v ? v.rows.filter((r) => r.exposed === true).length : 0
  return (
    <Section slot="prs-b-maps" icon={MapIcon} title={t('prs.deep.maps.title')} description={t('prs.deep.maps.desc')}>
      {!v ? <NotInScan t={t} /> : v.rows.length === 0 ? <EmptyLine good>{t('prs.deep.maps.none')}</EmptyLine> : (
        <>
          <ResponsiveRows slot="prs-b-maps-list" rowSlot="prs-b-map-row" t={t} locale={locale} rows={v.rows} more={v.more} rowKey={(r) => r.url}
            rowAttrs={(r) => ({ 'data-exposed': r.exposed === null ? 'unknown' : String(r.exposed) })} caption={t('prs.deep.maps.title')}
            columns={[
              { key: 'url', primary: true, head: t('prs.deep.col.resource'), cell: (r) => <UrlCell url={r.url} t={t} item={ctx.itemOf(r.url)} onOpenItem={ctx.onOpenItem} /> },
              { key: 'map', head: t('prs.deep.col.map'), className: 'w-[34%]', cell: (r) => (r.mapUrl ? <UrlText url={r.mapUrl} t={t} className="text-xs" /> : '—') },
              { key: 'state', head: t('prs.col.status'), className: 'w-[18%]', cell: (r) => (
                <StateChip tone={r.exposed === true ? 'danger' : r.exposed === false ? 'success' : 'muted'}>
                  {t(r.exposed === true ? 'prs.deep.maps.exposed' : r.exposed === false ? 'prs.deep.maps.closed' : 'prs.deep.maps.unknown')}
                </StateChip>
              ) },
            ]} />
          {exposed > 0 && <ShowObjectsButton count={ctx.findingCount('SOURCE_MAP_EXPOSED')} onClick={() => ctx.onShowFinding?.('SOURCE_MAP_EXPOSED')} t={t} locale={locale} label={t('prs.deep.maps.title')} />}
        </>
      )}
    </Section>
  )
}

function HtmlBlock({ data, ctx }) {
  const { t, locale } = ctx
  const h = htmlStats(data)
  return (
    <Section slot="prs-b-html" icon={FileCode2} title={t('prs.deep.html.title')} description={t('prs.deep.html.desc')}>
      {!h ? <NotInScan t={t} /> : (
        <StatTiles tiles={[
          { k: 'html', label: t('prs.deep.html.bytes'), value: fmtSize(h.htmlBytes, locale) },
          { k: 'script', label: t('prs.deep.html.inlineScript'), value: fmtSize(h.inlineScriptBytes, locale) },
          { k: 'style', label: t('prs.deep.html.inlineStyle'), value: fmtSize(h.inlineStyleBytes, locale) },
          { k: 'nodes', label: t('prs.deep.html.nodes'), value: formatNumber(h.domNodes, locale) },
          { k: 'depth', label: t('prs.deep.html.depth'), value: formatNumber(h.maxDepth, locale) },
          { k: 'datauri', label: t('prs.deep.html.dataUri'), value: formatNumber(h.dataUriCount, locale), sub: h.dataUriBytes != null ? fmtSize(h.dataUriBytes, locale) : null },
        ]} />
      )}
    </Section>
  )
}

/** Önbellek süresi kovası rengi (sıralı kalite düzeyi — durum jetonları). */
const BUCKET_BG = { danger: 'bg-destructive', warning: 'bg-amber-500', info: 'bg-primary/70', success: 'bg-success', muted: 'bg-muted-foreground' }

function CachingBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = cachingView(data)
  return (
    <Section slot="prs-b-cache" icon={Database} title={t('prs.deep.cache.title')} description={t('prs.deep.cache.desc')}>
      {!v ? <NotInScan t={t} /> : (
        <>
          {v.total > 0 ? (
            <div data-slot="prs-b-cache-buckets" className="flex min-w-0 flex-col gap-2">
              {/* Kova dağılımı (süs — değerler aşağıdaki listede yazılı); genişlik flex-grow ile (sayıyla orantılı) */}
              <div aria-hidden="true" className="flex h-3 w-full overflow-hidden rounded-full bg-muted">
                {v.buckets.filter((b) => (b.count ?? 0) > 0).map((b) => (
                  <span key={b.key} data-bucket={b.key} className={cn('h-full', BUCKET_BG[CACHE_BUCKET_TONE[b.key]])} style={{ flexGrow: b.count }} />
                ))}
              </div>
              <ul className="m-0 grid min-w-0 list-none grid-cols-1 gap-1 p-0 min-[420px]:grid-cols-2">
                {v.buckets.map((b) => (
                  <li key={b.key} data-slot="prs-b-cache-bucket" data-bucket={b.key} data-count={b.count ?? ''} className="flex min-w-0 items-center gap-2 text-xs">
                    <span aria-hidden="true" className={cn('size-2.5 shrink-0 rounded-sm', BUCKET_BG[CACHE_BUCKET_TONE[b.key]])} />
                    <span className={cn('flex-1 text-foreground', WRAP)}>{t(`prs.deep.cache.bucket.${b.key}`)}</span>
                    <span className="shrink-0 text-muted-foreground tabular-nums">
                      {formatNumber(b.count, locale)}{b.share != null ? ` · ${formatPct(b.share, locale, 0)}` : ''}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ) : <EmptyLine>{t('prs.deep.cache.noBuckets')}</EmptyLine>}
          <StatTiles tiles={[
            { k: 'novalidator', label: t('prs.deep.cache.noValidator'), value: formatNumber(v.noValidator, locale),
              tone: (v.noValidator ?? 0) > 0 ? 'warning' : undefined, sub: t('prs.deep.cache.noValidatorSub') },
            v.cdn && { k: 'cdn', label: t('prs.deep.cache.cdn'), value: t('prs.deep.cache.cdnValue', formatNumber(v.cdn.hit, locale), formatNumber(v.cdn.miss, locale)),
              sub: t('prs.deep.cache.cdnUnknown', formatNumber(v.cdn.unknown, locale)) },
            { k: 'repeat', label: t('prs.deep.cache.repeat'), value: v.repeatBytes != null ? `~${fmtSize(v.repeatBytes, locale)}` : '—',
              sub: <><KindBadge kind="estimate" t={t} /> {v.repeatPct != null ? t('prs.deep.cache.repeatSub', formatPct(v.repeatPct, locale, 0)) : t('prs.deep.cache.repeatSubUnknown')}</> },
          ]} />
          {v.versionedShort && v.versionedShort.rows.length > 0 && (
            <div className="flex min-w-0 flex-col gap-1.5">
              <h5 className="m-0 text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{t('prs.deep.cache.versionedTitle')}</h5>
              <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('prs.deep.cache.versionedDesc')}</p>
              <ResponsiveRows slot="prs-b-cache-versioned" rowSlot="prs-b-versioned-row" t={t} locale={locale} rows={v.versionedShort.rows}
                more={v.versionedShort.more} rowKey={(r) => r.url} caption={t('prs.deep.cache.versionedTitle')}
                columns={[
                  { key: 'url', primary: true, head: t('prs.deep.col.resource'), cell: (r) => <UrlCell url={r.url} t={t} item={ctx.itemOf(r.url)} onOpenItem={ctx.onOpenItem} /> },
                  // null = önbellek başlığı yok, -1 = no-store / no-cache (be1) — model metniyle
                  { key: 'ttl', head: t('prs.deep.col.ttl'), align: 'right', className: 'w-[24%]', cell: (r) => cacheText({ cache_ttl_s: r.ttlS }, t, locale) },
                ]} />
            </div>
          )}
          <ShowObjectsButton count={ctx.findingCount('NO_VALIDATOR')} onClick={() => ctx.onShowFinding?.('NO_VALIDATOR')} t={t} locale={locale} label={t('prs.deep.cache.noValidator')} />
        </>
      )}
    </Section>
  )
}

// ── C — görsel ve yazı tipi ─────────────────────────────────────────────────

/** Görsel sorun grubu → nesne bulgu kodu (nesne listesine geçiş). */
const IMAGE_GROUP_FINDING = { missing_dimensions: 'MISSING_DIMENSIONS', not_lazy: 'NOT_LAZY', animated_gif: 'ANIMATED_GIF' }

export function MediaArea({ ctx }) {
  const data = areaData(ctx.analysis, 'media')
  if (!data) return <NotInScan t={ctx.t} slot="prs-area-missing" />
  return (
    <>
      <ImagesBlock data={data} ctx={ctx} />
      <FontsBlock data={data} ctx={ctx} />
    </>
  )
}

function ImagesBlock({ data, ctx }) {
  const { t, locale } = ctx
  const groups = imageIssueGroups(data)
  return (
    <Section slot="prs-c-images" icon={ImageIcon} title={t('prs.deep.img.title')} description={t('prs.deep.img.desc')}>
      {!groups ? <NotInScan t={t} /> : groups.every((g) => g.total === 0) ? <EmptyLine good>{t('prs.deep.img.none')}</EmptyLine> : (
        <div className="flex min-w-0 flex-col gap-4">
          {groups.map((g) => (
            <section key={g.key} data-slot="prs-c-img-group" data-key={g.key} data-total={g.total} className="flex min-w-0 flex-col gap-1.5">
              <h5 className="m-0 flex min-w-0 flex-wrap items-center gap-2 text-sm font-semibold text-foreground">
                <span className={WRAP}>{t(`prs.deep.img.${g.key}`)}</span>
                <StateChip tone={g.total > 0 ? 'warning' : 'success'}>{formatNumber(g.total, locale)}</StateChip>
                {g.heuristic && <KindBadge kind="heuristic" t={t} />}
              </h5>
              <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t(`prs.deep.img.${g.key}.desc`)}</p>
              {g.rows.length > 0 && (
                <UrlList slot={`prs-c-img-${g.key}`} urls={g.rows} t={t} locale={locale} more={g.more} itemOf={ctx.itemOf} onOpenItem={ctx.onOpenItem}
                  renderMeta={g.key === 'missing_srcset' ? (r) => (r.width != null ? t('prs.deep.img.width', formatNumber(r.width, locale)) : null)
                    : g.key === 'animated_gif' ? (r) => [r.bytes != null ? fmtSize(r.bytes, locale) : null,
                      r.frames != null ? plural(t, 'prs.deep.img.frames', r.frames, formatNumber(r.frames, locale)) : null].filter(Boolean).join(' · ') || null
                      : undefined} />
              )}
              {IMAGE_GROUP_FINDING[g.key] && (
                <ShowObjectsButton count={ctx.findingCount(IMAGE_GROUP_FINDING[g.key])} onClick={() => ctx.onShowFinding?.(IMAGE_GROUP_FINDING[g.key])}
                  t={t} locale={locale} label={t(`prs.deep.img.${g.key}`)} />
              )}
            </section>
          ))}
        </div>
      )}
    </Section>
  )
}

function FontsBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = fontView(data)
  return (
    <Section slot="prs-c-fonts" icon={Type} title={t('prs.deep.font.title')} description={t('prs.deep.font.desc')}>
      {!v ? <NotInScan t={t} /> : (
        <>
          <StatTiles tiles={[
            { k: 'count', label: t('prs.deep.font.count'), value: formatNumber(v.count, locale) },
            { k: 'bytes', label: t('prs.deep.font.bytes'), value: fmtSize(v.bytes, locale) },
            { k: 'preloaded', label: t('prs.deep.font.preloaded'), value: formatNumber(v.preloaded, locale) },
          ]} />
          {v.families && v.families.rows.length > 0 && (
            <ResponsiveRows slot="prs-c-font-families" rowSlot="prs-c-font-family" t={t} locale={locale} rows={v.families.rows} more={v.families.more}
              rowKey={(r) => r.family} caption={t('prs.deep.font.families')}
              columns={[
                { key: 'family', primary: true, head: t('prs.deep.col.family'), cell: (r) => <span className={cn('text-xs font-medium', WRAP)}>{r.family}</span> },
                { key: 'weights', head: t('prs.deep.col.weights'), className: 'w-[34%]', cell: (r) => (r.weights.length
                  ? <span className="flex flex-wrap gap-1">{r.weights.map((w) => <SmallBadge key={w}>{w}</SmallBadge>)}</span> : '—') },
                { key: 'files', head: t('prs.deep.col.files'), align: 'right', className: 'w-[14%]', cell: (r) => formatNumber(r.files, locale) },
              ]} />
          )}
          {v.nonWoff2 && v.nonWoff2.rows.length > 0 && (
            <div className="flex min-w-0 flex-col gap-1.5">
              <h5 className="m-0 text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{t('prs.deep.font.nonWoff2')}</h5>
              <UrlList slot="prs-c-font-nonwoff2" urls={v.nonWoff2.rows} t={t} locale={locale} more={v.nonWoff2.more} itemOf={ctx.itemOf} onOpenItem={ctx.onOpenItem}
                renderMeta={(r) => t('prs.deep.font.format', r.format === 'unknown' ? t('prs.deep.font.formatUnknown') : r.format.toUpperCase())} />
            </div>
          )}
          {v.missingDisplay && v.missingDisplay.rows.length > 0 && (
            <div className="flex min-w-0 flex-col gap-1.5">
              <h5 className="m-0 text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{t('prs.deep.font.missingDisplay')}</h5>
              <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('prs.deep.font.missingDisplayDesc')}</p>
              <ResponsiveRows slot="prs-c-font-display" rowSlot="prs-c-font-display-row" t={t} locale={locale} rows={v.missingDisplay.rows}
                more={v.missingDisplay.more} rowKey={(r) => r.family} caption={t('prs.deep.font.missingDisplay')}
                columns={[
                  { key: 'family', primary: true, head: t('prs.deep.col.family'), cell: (r) => <span className={cn('text-xs font-medium', WRAP)}>{r.family}</span> },
                  { key: 'css', head: t('prs.deep.col.css'), className: 'w-[50%]', cell: (r) => (r.cssUrl ? <UrlText url={r.cssUrl} t={t} className="text-xs" /> : '—') },
                ]} />
              <ShowObjectsButton count={ctx.findingCount('FONT_DISPLAY_MISSING')} onClick={() => ctx.onShowFinding?.('FONT_DISPLAY_MISSING')} t={t} locale={locale}
                label={t('prs.deep.font.missingDisplay')} />
            </div>
          )}
          {(!v.nonWoff2 || v.nonWoff2.rows.length === 0) && (!v.missingDisplay || v.missingDisplay.rows.length === 0) && (v.count ?? 0) > 0 && (
            <EmptyLine good>{t('prs.deep.font.allGood')}</EmptyLine>
          )}
        </>
      )}
    </Section>
  )
}

