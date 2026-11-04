import { Bug, ClipboardCheck, Cookie, ExternalLink, Eye, FileWarning, Lock, Radar, ShieldCheck, ShieldHalf } from 'lucide-react'
import { formatDateOnly } from '../../../api/client'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import {
  CHECK_TONE, COOKIE_ISSUES, HEADER_STATUS_TONE, SECURITY_HEADER_NAMES, VULN_SEVERITY_TONE, areaData, checkCounts, checkRows,
  cookieRows, formatNumber, formatSize, gradeLetter, gradeTone, headerCounts, headerIssueKey, isCapped, mixedContentRows, plural,
  scoreTone, securityHeaderRows, sriRows, thirdPartyCategoryRows, trackingRows, vulnLibraryRows, vulnLink,
} from './resourceScanModel.js'
import {
  EmptyLine, KindBadge, NotInScan, ResponsiveRows, ShowObjectsButton, StateChip, UrlCell, UrlList,
} from './ResourceScanAuditParts.jsx'
import { Section, SmallBadge, TONE_BAR, TONE_TEXT, WRAP } from './ResourceScanParts.jsx'
import { cn } from '@/lib/utils'

/**
 * Ayrıntılı denetim — GÜVEN alanları (prs3): D güvenlik ve uyumluluk (puan, güvenlik başlıkları, SRI, karışık içerik,
 * çerezler — DEĞERİ asla —, bilinen açıklı kütüphaneler, üçüncü taraf kategorileri, sayfa açılışında çalışan izleme +
 * KVKK notu) ve E SEO + erişilebilirlik denetim listeleri. Tüm hesap statik (JavaScript çalışmaz): JavaScript'in yazdığı
 * çerezler, onay ekranı ve renk karşıtlığı görülemez — her blok bunu dürüstçe söyler.
 */

const fmtSize = (v, locale) => formatSize(v, { locale })

// ── D — güvenlik ve uyumluluk ──────────────────────────────────────────────

export function SecurityArea({ ctx }) {
  const data = areaData(ctx.analysis, 'security')
  if (!data) return <NotInScan t={ctx.t} slot="prs-area-missing" />
  return (
    <>
      <SecurityScoreBlock data={data} ctx={ctx} />
      <HeadersBlock data={data} ctx={ctx} />
      <SriBlock data={data} ctx={ctx} />
      <MixedBlock data={data} ctx={ctx} />
      <CookiesBlock data={data} ctx={ctx} />
      <LibrariesBlock data={data} ctx={ctx} />
      <ThirdPartyCategoriesBlock data={data} ctx={ctx} />
      <TrackingBlock data={data} ctx={ctx} />
    </>
  )
}

/** Puan + başlık durum sayıları. Hesaplanamayan puan "—" ve soluk; sınırlı (kısmi) ölçümde soluk + not. */
function ScoreHeader({ slot, score, letter, tone, partial, chips, t, title, children }) {
  return (
    <div data-slot={slot} data-tone={tone} className="flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 flex-wrap items-end gap-x-4 gap-y-2">
        <span className="flex items-baseline gap-1.5">
          <span className={cn('text-3xl leading-none font-bold tabular-nums', score == null ? 'text-muted-foreground' : TONE_TEXT[tone])}>{score ?? '—'}</span>
          <span className="text-xs text-muted-foreground">/100</span>
          {letter && <span className={cn('text-sm font-bold', TONE_TEXT[tone])}>{letter}</span>}
        </span>
        <span className="sr-only">{title}</span>
        {chips && <span className="flex min-w-0 flex-wrap gap-1.5">{chips}</span>}
      </div>
      {score != null && <ProgressBar value={score} max={100} size="sm" tone={TONE_BAR[tone]} decorative />}
      {score == null && <p className="m-0 text-xs text-muted-foreground">{t('prs.deep.score.notComputed')}</p>}
      {partial && <p data-slot="prs-deep-partial" className={cn('m-0 text-xs font-medium text-amber-700 dark:text-amber-300', WRAP)}>{t('prs.deep.score.partial')}</p>}
      {children}
    </div>
  )
}

function SecurityScoreBlock({ data, ctx }) {
  const { t, locale, scan } = ctx
  const score = data.score == null ? null : Number(data.score)
  const letter = score == null ? null : gradeLetter(score, data.letter)
  const partial = isCapped(scan)
  const tone = score == null || partial ? 'muted' : gradeTone(letter)
  const rows = securityHeaderRows(data)
  const c = rows ? headerCounts(rows) : null
  return (
    <Section slot="prs-d-score" icon={ShieldCheck} title={t('prs.deep.sec.scoreTitle')} description={t('prs.deep.sec.scoreDesc')}>
      <ScoreHeader slot="prs-d-score-value" score={Number.isFinite(score) ? score : null} letter={letter} tone={tone} partial={partial} t={t}
        title={t('prs.deep.sec.scoreTitle')}
        chips={c && (
          <>
            <StateChip tone="danger" data-slot="prs-d-count-missing">{plural(t, 'prs.deep.sec.missingCount', c.missing, formatNumber(c.missing, locale))}</StateChip>
            <StateChip tone="warning" data-slot="prs-d-count-weak">{plural(t, 'prs.deep.sec.weakCount', c.weak, formatNumber(c.weak, locale))}</StateChip>
            <StateChip tone="success" data-slot="prs-d-count-ok">{plural(t, 'prs.deep.sec.okCount', c.ok, formatNumber(c.ok, locale))}</StateChip>
          </>
        )} />
    </Section>
  )
}

function HeadersBlock({ data, ctx }) {
  const { t, locale } = ctx
  const rows = securityHeaderRows(data)
  return (
    <Section slot="prs-d-headers" icon={ShieldHalf} title={t('prs.deep.sec.headersTitle')} description={t('prs.deep.sec.headersDesc')}>
      {!rows ? <NotInScan t={t} /> : rows.length === 0 ? <EmptyLine>{t('prs.deep.sec.headersNone')}</EmptyLine> : (
        <ResponsiveRows slot="prs-d-headers-list" rowSlot="prs-d-header" minPx={720} t={t} locale={locale} rows={rows} preview={20}
          rowKey={(r) => r.key} rowAttrs={(r) => ({ 'data-key': r.key, 'data-status': r.status })} caption={t('prs.deep.sec.headersTitle')}
          columns={[
            { key: 'name', primary: true, head: t('prs.deep.col.header'), cell: (r) => (
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className={cn('text-xs font-semibold text-foreground', WRAP)}>{t(r.known ? `prs.check.header.${r.key}` : 'prs.check.header.other')}</span>
                {r.known && <code className={cn('font-mono text-[11px] text-muted-foreground', WRAP)}>{SECURITY_HEADER_NAMES[r.key]}</code>}
              </span>
            ) },
            { key: 'status', head: t('prs.col.status'), className: 'w-[14%]', cell: (r) => (
              <StateChip tone={HEADER_STATUS_TONE[r.status]} data-status={r.status}>{t(`prs.deep.sec.status.${r.status}`)}</StateChip>
            ) },
            { key: 'value', head: t('prs.deep.col.value'), className: 'w-[30%]', cell: (r) => (r.value
              ? <code className={cn('font-mono text-[11px]', WRAP)}>{r.value}</code> : <span className="text-muted-foreground">{t('prs.deep.sec.noValue')}</span>) },
            { key: 'note', head: t('prs.deep.col.whatToDo'), className: 'w-[28%]', cell: (r) => headerNote(r, t) },
          ]} />
      )}
    </Section>
  )
}

/** Başlık satırının açıklaması: zayıfsa sorun metni, eksikse ne işe yaradığı, tamamsa kısa onay. */
function headerNote(r, t) {
  if (r.status === 'weak' && r.issue) return t(`prs.check.headerIssue.${headerIssueKey(r.issue)}`)
  if (r.status === 'ok') return t('prs.deep.sec.noteOk')
  if (r.status === 'missing' || r.status === 'weak') return t(r.known ? `prs.check.header.${r.key}.why` : 'prs.check.header.other.why')
  return t('prs.deep.sec.noteUnknown')
}

function SriBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = sriRows(data)
  return (
    <Section slot="prs-d-sri" icon={Lock} title={t('prs.deep.sec.sriTitle')} description={t('prs.deep.sec.sriDesc')}>
      {!v ? <NotInScan t={t} /> : v.rows.length === 0 ? <EmptyLine good>{t('prs.deep.sec.sriNone')}</EmptyLine> : (
        <>
          <ResponsiveRows slot="prs-d-sri-list" rowSlot="prs-d-sri-row" t={t} locale={locale} rows={v.rows} more={v.more} rowKey={(r) => r.url}
            caption={t('prs.deep.sec.sriTitle')}
            columns={[
              { key: 'url', primary: true, head: t('prs.deep.col.resource'), cell: (r) => <UrlCell url={r.url} t={t} item={ctx.itemOf(r.url)} onOpenItem={ctx.onOpenItem} /> },
              { key: 'kind', head: t('prs.deep.col.kind'), className: 'w-[18%]', cell: (r) => t(`prs.deep.sec.sriKind.${r.kind}`) },
            ]} />
          <ShowObjectsButton count={ctx.findingCount('MISSING_SRI')} onClick={() => ctx.onShowFinding?.('MISSING_SRI')} t={t} locale={locale} label={t('prs.deep.sec.sriTitle')} />
        </>
      )}
    </Section>
  )
}

function MixedBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = mixedContentRows(data)
  return (
    <Section slot="prs-d-mixed" icon={FileWarning} title={t('prs.deep.sec.mixedTitle')} description={t('prs.deep.sec.mixedDesc')}>
      {!v ? <NotInScan t={t} /> : v.rows.length === 0 ? <EmptyLine good>{t('prs.deep.sec.mixedNone')}</EmptyLine> : (
        <>
          <UrlList slot="prs-d-mixed-list" urls={v.rows} t={t} locale={locale} more={v.more} itemOf={ctx.itemOf} onOpenItem={ctx.onOpenItem} />
          <ShowObjectsButton count={ctx.findingCount('MIXED_CONTENT')} onClick={() => ctx.onShowFinding?.('MIXED_CONTENT')} t={t} locale={locale} label={t('prs.deep.sec.mixedTitle')} />
        </>
      )}
    </Section>
  )
}

/** Çerez bayrağı: evet (başarı) / hayır (uyarı) / bilinmiyor (soluk). */
function Flag({ value, t }) {
  if (value === true) return <StateChip tone="success">{t('prs.export.yes')}</StateChip>
  if (value === false) return <StateChip tone="warning">{t('prs.export.no')}</StateChip>
  return <span className="text-muted-foreground">—</span>
}

function CookiesBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = cookieRows(data)
  return (
    <Section slot="prs-d-cookies" icon={Cookie} title={t('prs.deep.sec.cookiesTitle')} description={t('prs.deep.sec.cookiesDesc')}>
      <p data-slot="prs-d-cookies-js" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('prs.deep.sec.cookiesJs')}</p>
      {!v ? <NotInScan t={t} /> : v.rows.length === 0 ? <EmptyLine>{t('prs.deep.sec.cookiesNone')}</EmptyLine> : (
        <ResponsiveRows slot="prs-d-cookies-list" rowSlot="prs-d-cookie" minPx={760} t={t} locale={locale} rows={v.rows} more={v.more}
          rowKey={(r) => `${r.host}:${r.name}`} rowAttrs={(r) => ({ 'data-issues': r.issues.length })} caption={t('prs.deep.sec.cookiesTitle')}
          columns={[
            { key: 'name', primary: true, head: t('prs.deep.col.cookie'), cell: (r) => (
              <span className="flex min-w-0 flex-col items-start gap-0.5">
                <code className={cn('font-mono text-xs font-semibold text-foreground', WRAP)}>{r.name}</code>
                {r.host && <span className={cn('text-[11px] text-muted-foreground', WRAP)}>{r.host}</span>}
                {r.thirdParty && <SmallBadge>{t('prs.obj.thirdParty')}</SmallBadge>}
              </span>
            ) },
            { key: 'secure', head: 'Secure', className: 'w-[11%]', cell: (r) => <Flag value={r.secure} t={t} /> },
            { key: 'httponly', head: 'HttpOnly', className: 'w-[11%]', cell: (r) => <Flag value={r.httpOnly} t={t} /> },
            { key: 'samesite', head: 'SameSite', className: 'w-[12%]', cell: (r) => r.sameSite || <span className="text-muted-foreground">{t('prs.deep.sec.sameSiteNone')}</span> },
            { key: 'issues', head: t('prs.deep.col.issues'), className: 'w-[26%]', cell: (r) => (r.issues.length ? (
              <ul className="m-0 flex list-disc flex-col gap-0.5 pl-4">
                {r.issues.map((x) => <li key={x} className={WRAP}>{t(`prs.check.cookieIssue.${COOKIE_ISSUES.includes(x) ? x : 'other'}`)}</li>)}
              </ul>
            ) : <span className="text-success">{t('prs.deep.sec.cookieOk')}</span>) },
          ]} />
      )}
    </Section>
  )
}

function LibrariesBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = vulnLibraryRows(data)
  const vulnerable = v ? v.rows.filter((r) => r.vulns.length) : []
  const clean = v ? v.rows.filter((r) => !r.vulns.length) : []
  return (
    <Section slot="prs-d-libs" icon={Bug} title={t('prs.deep.sec.libsTitle')} description={t('prs.deep.sec.libsDesc')}>
      {!v ? <NotInScan t={t} /> : (
        <>
          {v.catalogDate && <p data-slot="prs-d-catalog" className="m-0 text-xs text-muted-foreground">{t('prs.deep.sec.catalog', formatDateOnly(v.catalogDate))}</p>}
          {vulnerable.length === 0 ? <EmptyLine good>{t('prs.deep.sec.libsNone')}</EmptyLine> : (
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {vulnerable.map((r, i) => (
                <li key={`${r.name}@${r.version}#${i}`} data-slot="prs-d-lib" data-name={r.name} data-severity={r.maxSeverity}
                  className="flex min-w-0 flex-col gap-1.5 rounded-lg border px-3 py-2.5">
                  <span className="flex min-w-0 flex-wrap items-center gap-2">
                    <span className={cn('text-sm font-semibold text-foreground', WRAP)}>{r.version ? `${r.name} ${r.version}` : r.name}</span>
                    <StateChip tone={VULN_SEVERITY_TONE[r.maxSeverity] || 'muted'}>{plural(t, 'prs.deep.sec.vulnCount', r.vulns.length, formatNumber(r.vulns.length, locale))}</StateChip>
                  </span>
                  {r.url && <UrlCell url={r.url} t={t} item={ctx.itemOf(r.url)} onOpenItem={ctx.onOpenItem} />}
                  <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
                    {r.vulns.map((x) => {
                      const href = vulnLink(x.id)
                      return (
                        <li key={x.id} data-slot="prs-d-vuln" data-id={x.id} className="flex min-w-0 flex-col gap-0.5 rounded-md bg-muted/40 px-2.5 py-1.5">
                          <span className="flex min-w-0 flex-wrap items-center gap-2">
                            <StateChip tone={VULN_SEVERITY_TONE[x.severity] || 'muted'}>{t(`prs.deep.sec.sev.${x.severity in VULN_SEVERITY_TONE ? x.severity : 'unknown'}`)}</StateChip>
                            {x.kind && <SmallBadge data-slot="prs-d-vuln-kind">{t(`prs.deep.sec.vulnKind.${x.kind}`)}</SmallBadge>}
                            {href ? (
                              <a href={href} target="_blank" rel="noopener noreferrer" data-slot="prs-d-vuln-link" aria-label={t('prs.deep.sec.vulnOpen', x.id)}
                                className="text-xs font-semibold text-foreground underline-offset-2 hover:underline focus-visible:rounded-sm focus-visible:outline-2 focus-visible:outline-ring pointer-coarse:inline-flex pointer-coarse:min-h-10 pointer-coarse:items-center">
                                {x.id}<ExternalLink aria-hidden="true" className="ml-1 inline size-3 align-[-1px] text-muted-foreground" />
                              </a>
                            ) : <code className="font-mono text-xs font-semibold">{x.id}</code>}
                          </span>
                          {x.summary && <span lang="en" className={cn('text-xs text-muted-foreground', WRAP)}>{x.summary}</span>}
                        </li>
                      )
                    })}
                  </ul>
                </li>
              ))}
            </ul>
          )}
          {clean.length > 0 && (
            <p data-slot="prs-d-libs-clean" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
              {t('prs.deep.sec.libsClean', clean.map((r) => (r.version ? `${r.name} ${r.version}` : r.name)).join(', '))}
            </p>
          )}
          <p className={cn('m-0 text-[11px] text-muted-foreground', WRAP)}>{t('prs.deep.sec.libsLimit')}</p>
          <ShowObjectsButton count={ctx.findingCount('VULNERABLE_LIBRARY')} onClick={() => ctx.onShowFinding?.('VULNERABLE_LIBRARY')} t={t} locale={locale} label={t('prs.deep.sec.libsTitle')} />
        </>
      )}
    </Section>
  )
}

function ThirdPartyCategoriesBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = thirdPartyCategoryRows(data)
  return (
    <Section slot="prs-d-tp" icon={Radar} title={t('prs.deep.sec.tpTitle')} description={t('prs.deep.sec.tpDesc')}>
      {!v ? <NotInScan t={t} /> : v.rows.length === 0 ? <EmptyLine>{t('prs.deep.sec.tpNone')}</EmptyLine> : (
        <ResponsiveRows slot="prs-d-tp-list" rowSlot="prs-d-tp-row" t={t} locale={locale} rows={v.rows} more={v.more} rowKey={(r) => r.category}
          rowAttrs={(r) => ({ 'data-category': r.category, 'data-tracking': String(r.tracking) })} caption={t('prs.deep.sec.tpTitle')}
          columns={[
            { key: 'cat', primary: true, head: t('prs.deep.col.category'), cell: (r) => (
              <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                <span className={cn('text-xs font-semibold text-foreground', WRAP)}>{t(`prs.deep.tpc.${r.category}`)}</span>
                {r.tracking && <SmallBadge tone="warning">{t('prs.deep.sec.trackingCat')}</SmallBadge>}
              </span>
            ) },
            { key: 'hosts', head: t('prs.deep.col.hosts'), className: 'w-[40%]', cell: (r) => (
              <ul className="m-0 flex list-none flex-col gap-0.5 p-0">{r.hosts.map((h) => <li key={h} className={WRAP}>{h}</li>)}</ul>
            ) },
            { key: 'req', head: t('prs.deep.col.requests'), align: 'right', className: 'w-[11%]', cell: (r) => formatNumber(r.requests, locale) },
            { key: 'bytes', head: t('prs.col.size'), align: 'right', className: 'w-[13%]', cell: (r) => fmtSize(r.bytes, locale) },
          ]} />
      )}
    </Section>
  )
}

function TrackingBlock({ data, ctx }) {
  const { t, locale } = ctx
  const v = trackingRows(data)
  return (
    <Section slot="prs-d-tracking" icon={Eye} title={t('prs.deep.sec.trackingTitle')} description={t('prs.deep.sec.trackingDesc')}
      actions={<KindBadge kind="heuristic" t={t} />}>
      {!v ? <NotInScan t={t} /> : v.rows.length === 0 ? <EmptyLine>{t('prs.deep.sec.trackingNone')}</EmptyLine> : (
        <>
          <div data-slot="prs-d-kvkk">
            <AlertBanner tone="warning" className="mb-0" title={plural(t, 'prs.deep.sec.trackingMay', v.total, formatNumber(v.total, locale))}>
              {t('prs.deep.sec.kvkk')}
            </AlertBanner>
          </div>
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {v.rows.map((r) => (
              <li key={r.host} data-slot="prs-d-tracking-row" data-category={r.category} className="flex min-w-0 flex-wrap items-center gap-2 rounded-md border px-3 py-1.5">
                <span className={cn('text-xs font-medium text-foreground', WRAP)}>{r.host}</span>
                <SmallBadge>{t(`prs.deep.tpc.${r.category}`)}</SmallBadge>
              </li>
            ))}
          </ul>
          {v.more > 0 && <p className="m-0 text-xs text-muted-foreground">{plural(t, 'prs.deep.serverMore', v.more, formatNumber(v.more, locale))}</p>}
        </>
      )}
    </Section>
  )
}

// ── E — SEO + erişilebilirlik ──────────────────────────────────────────────

export function ChecksArea({ ctx, area }) {
  const { t, locale } = ctx
  const data = areaData(ctx.analysis, area)
  if (!data) return <NotInScan t={t} slot="prs-area-missing" />
  const rows = checkRows(data, area) || []
  const c = checkCounts(data)
  const score = data.score == null || !Number.isFinite(Number(data.score)) ? null : Number(data.score)
  const tone = score == null ? 'muted' : scoreTone(score)
  const ns = area === 'a11y' ? 'a11y' : 'seo'
  return (
    <>
      <Section slot={`prs-e-${ns}-score`} icon={ClipboardCheck} title={t(`prs.deep.${ns}.scoreTitle`)} description={t(`prs.deep.${ns}.scoreDesc`)}>
        <ScoreHeader slot={`prs-e-${ns}-score-value`} score={score} letter={null} tone={tone} partial={false} t={t} title={t(`prs.deep.${ns}.scoreTitle`)}
          chips={c.total > 0 && (
            <>
              <StateChip tone="danger" data-slot="prs-e-count-fail">{plural(t, 'prs.deep.checks.failCount', c.fail, formatNumber(c.fail, locale))}</StateChip>
              <StateChip tone="warning" data-slot="prs-e-count-warn">{plural(t, 'prs.deep.checks.warnCount', c.warn, formatNumber(c.warn, locale))}</StateChip>
              <StateChip tone="success" data-slot="prs-e-count-pass">{plural(t, 'prs.deep.checks.passCount', c.pass, formatNumber(c.pass, locale))}</StateChip>
            </>
          )}>
          {area === 'a11y' && <p data-slot="prs-e-contrast" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('prs.deep.a11y.contrast')}</p>}
        </ScoreHeader>
      </Section>
      <Section slot={`prs-e-${ns}-checks`} icon={ClipboardCheck} title={t(`prs.deep.${ns}.checksTitle`)} description={t('prs.deep.checks.desc')}>
        {rows.length === 0 ? <EmptyLine>{t('prs.deep.checks.none')}</EmptyLine> : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {rows.map((r) => <CheckRow key={r.key} row={r} ns={ns} t={t} locale={locale} />)}
          </ul>
        )}
      </Section>
    </>
  )
}

function CheckRow({ row, ns, t, locale }) {
  const titleKey = row.known ? `prs.check.${ns}.${row.key}` : `prs.check.${ns}.other`
  const showWhy = row.status !== 'pass'
  return (
    <li data-slot="prs-e-check" data-key={row.key} data-status={row.status} className="flex min-w-0 flex-col gap-1 rounded-lg border px-3 py-2">
      <span className="flex min-w-0 flex-wrap items-center gap-2">
        <StateChip tone={CHECK_TONE[row.status]}>{t(`prs.check.status.${row.status}`)}</StateChip>
        <span className={cn('text-sm font-semibold text-foreground', WRAP)}>{t(titleKey)}</span>
        {row.issue && row.status !== 'pass' && <SmallBadge tone={CHECK_TONE[row.status]} data-slot="prs-e-check-issue">{t(`prs.check.issue.${row.issue}`)}</SmallBadge>}
        {row.count != null && (ns === 'a11y' ? row.status !== 'pass' : row.count !== 1) && (
          <span className="text-xs text-muted-foreground tabular-nums">{plural(t, 'prs.deep.checks.count', row.count, formatNumber(row.count, locale))}</span>
        )}
      </span>
      {row.value && (
        <span className="flex min-w-0 flex-col gap-0.5 sm:flex-row sm:gap-2">
          <span className="shrink-0 text-xs text-muted-foreground">{t('prs.deep.checks.found')}</span>
          <code className={cn('font-mono text-xs text-foreground', WRAP)}>{row.value}</code>
        </span>
      )}
      {showWhy && <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t(row.known ? `prs.check.${ns}.${row.key}.why` : `prs.check.${ns}.other.why`)}</p>}
      {row.samples.length > 0 && (
        <span className="flex min-w-0 flex-col gap-1">
          <span className="text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{t('prs.deep.checks.samples')}</span>
          <span className="flex min-w-0 flex-wrap gap-1">
            {row.samples.map((s, i) => <code key={`${s}#${i}`} className={cn('rounded-md bg-muted px-1.5 py-0.5 font-mono text-[11px]', WRAP)}>{s}</code>)}
          </span>
        </span>
      )}
    </li>
  )
}

