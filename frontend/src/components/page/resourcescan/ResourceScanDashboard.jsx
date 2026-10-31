import { lazy, Suspense } from 'react'
import { ChevronDown, Gauge, Globe, Image as ImageIcon, Layers, Lightbulb, ListFilter, NotebookText, TrendingDown, TriangleAlert } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import ToneBadge from '../../admin/ToneBadge.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { LoadingBlock, ProgressBar } from '../../ui/Progress.jsx'
import {
  SEVERITIES, SEVERITY_TONE, algorithmVersion, categoryLabel, categoryRows, delta, deltasComparable, dimensionsText, estImageMs,
  formatBytesExact, formatMs, formatNumber, formatPct, formatScoreValue, formatSize, gradeLetter, isCapped, noteRows, optimizedImage,
  plural, recFiltersByFinding, recHostList, recItemCount, recItemUrls, recSaving, recTextKey, recommendationArgs, relatedRec, scanTone,
  scoreLabelKey, scoreRows, scoreTargetText, sortRecommendations, thirdPartyRows, topLosses, TOP_IMAGES_MAX,
} from './resourceScanModel.js'
import { CATEGORY_ICON, CHIP, DeltaChip, Section, signed, TONE_BAR, TONE_STROKE, TONE_TEXT, TOUCH, UrlText, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

// Grafik (recharts) yalnız pano açılınca iner — sekme parçası küçük kalsın
const ResourceScanTrend = lazy(() => import('./ResourceScanTrend.jsx'))

/** Kompozisyon çubuğunun kategori renkleri (durum renkleri DEĞİL — proje grafik jetonları). */
const STACK_COLOR = ['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)', 'var(--chart-5)']
/** Üçüncü taraf tablosu ↔ kart eşiği (kap genişliği, px). */
const THIRD_TABLE_MIN_PX = 640

/**
 * Kaynak analizi → PANO (2026-10-31; r177 yeniden düzen): ÖNCE ne yapmalı — not + "notu en çok düşüren 3 şey" (kayıp
 * puan, hedef, ilgili öneriye git) ve hemen altında öneriler; sonra tür dağılımı (sayfa yükü kompozisyonu, doğru ölçek)
 * ile not bileşenleri (kayıp puan + tam / sıfır puan hedefleri), büyük görsellerin etkisi (ölçülmüş kazanç), üçüncü
 * taraflar, yüklenme / boyut / not geçmişi (noktaya basınca o analiz açılır) ve ölçüm notları. Uzak görseller ÇİZİLMEZ.
 */
export default function ResourceScanDashboard({
  scan, analysis, prev, history, selectedId, onCategory, onShowObjects, objectsAvailable, items, onSelectScan, onGoRec,
}) {
  const t = useT()
  const locale = useDateLocale()
  return (
    <div data-slot="prs-dashboard" className="flex min-w-0 flex-col gap-3">
      <GradeHero scan={scan} analysis={analysis} prev={prev} t={t} locale={locale} onGoRec={onGoRec} />
      {scan?.capped === true && (
        <div data-slot="prs-capped">
          <AlertBanner tone="warning" className="mb-0" icon={TriangleAlert} title={t('prs.capped.title')}>{t('prs.capped.body')}</AlertBanner>
        </div>
      )}
      <Recommendations analysis={analysis} items={items} t={t} locale={locale} onShowObjects={onShowObjects} objectsAvailable={objectsAvailable} />
      <div className="grid min-w-0 grid-cols-1 gap-3 lg:grid-cols-2">
        <CategoryBreakdown analysis={analysis} scan={scan} t={t} locale={locale} onCategory={onCategory} objectsAvailable={objectsAvailable} />
        <ScoreBreakdown analysis={analysis} t={t} locale={locale} />
      </div>
      <ImageImpact analysis={analysis} scan={scan} t={t} locale={locale} />
      <ThirdParties analysis={analysis} items={items} t={t} locale={locale} />
      <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="py-6" />}>
        <ResourceScanTrend items={history} selectedId={selectedId} onSelect={onSelectScan} />
      </Suspense>
      <MeasurementNotes analysis={analysis} t={t} locale={locale} />
    </div>
  )
}

/** Not halkası (0–100) — ortadaki sayı HTML; renk notun tonundan. Bilinmeyen not soluk ("—"), asla yeşil değil.
 *  Telefonda küçük (80 px) ve metnin YANINDA (r177 — eskiden 112 px metnin üstündeydi). */
function GradeRing({ grade, letter, tone, partial, t }) {
  const size = 112
  const stroke = 10
  const r = (size - stroke) / 2
  const circ = 2 * Math.PI * r
  const v = grade == null ? 0 : Math.max(0, Math.min(100, grade))
  const dash = (v / 100) * circ
  const c = size / 2
  return (
    <div data-slot="prs-grade" data-tone={tone} data-letter={letter || undefined} data-partial={partial ? 'true' : undefined}
      className="relative size-20 shrink-0 sm:size-28">
      <svg viewBox={`0 0 ${size} ${size}`} role="img" className="block size-full"
        aria-label={grade == null ? t('prs.gradeUnknownAria')
          : partial ? t('prs.gradeAriaPartial', grade, letter || '—') : t('prs.gradeAria', grade, letter || '—')}>
        <circle cx={c} cy={c} r={r} fill="none" stroke="var(--border)" strokeWidth={stroke} />
        {grade != null && (
          <circle cx={c} cy={c} r={r} fill="none" stroke={TONE_STROKE[tone] || TONE_STROKE.muted} strokeWidth={stroke}
            strokeLinecap="round" strokeDasharray={`${dash} ${circ - dash}`} transform={`rotate(-90 ${c} ${c})`} />
        )}
      </svg>
      <div aria-hidden="true" className="absolute inset-0 flex flex-col items-center justify-center leading-none">
        <span className="text-2xl font-bold tracking-tight text-foreground tabular-nums sm:text-3xl">{grade ?? '—'}</span>
        <span className={cn('mt-1 text-[11px] font-bold sm:text-xs', TONE_TEXT[tone] || TONE_TEXT.muted)}>{letter ? `${letter} · /100` : '/100'}</span>
      </div>
    </div>
  )
}

function Kpi({ k, label, value, sub, d, deltaText, srDelta, t }) {
  return (
    <div data-slot="prs-kpi" data-key={k} className="flex min-w-0 flex-col gap-1 rounded-lg border bg-muted/30 px-3 py-2.5">
      <span className="text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{label}</span>
      <span className="text-lg leading-tight font-bold text-foreground tabular-nums">{value}</span>
      {sub && <span className={cn('text-xs text-muted-foreground', WRAP)}>{sub}</span>}
      {/* "Değişmedi" gürültüsü yok (r177): aynı kalan değerde rozet çizilmez; tek satır künyesi panoda */}
      {d && d.direction !== 'same' && <DeltaChip d={d} text={deltaText} srText={srDelta} sameText={t('prs.delta.same')} />}
    </div>
  )
}

function GradeHero({ scan, analysis, prev, t, locale, onGoRec }) {
  const s = scan || {}
  const grade = s.grade ?? null
  const letter = gradeLetter(s.grade, s.grade_letter)
  // Sınırlı (kısmi) ölçüm: not soluk, asla yeşil; hüküm "Kısmi ölçüm" (r177 — kısmi veriden "Mükemmel" denmez)
  const partial = isCapped(s)
  const tone = scanTone(s)
  const p = prev || null
  const cmp = deltasComparable(s, p)
  const algoChanged = cmp && algorithmVersion(s, analysis) !== algorithmVersion(p, null)
  const sr = (key, amount) => t('prs.delta.sr', t(key), amount)
  const dGrade = cmp && !algoChanged ? delta(s.grade, p.grade, { lowerIsBetter: false }) : null
  const dLoad = cmp ? delta(s.load_ms, p.load_ms) : null
  const dSize = cmp ? delta(s.page_bytes, p.page_bytes) : null
  const dReq = cmp ? delta(s.request_count, p.request_count) : null
  const dImg = cmp ? delta(s.image_bytes, p.image_bytes) : null
  const allSame = cmp && [dGrade, dLoad, dSize, dReq, dImg].every((d) => !d || d.direction === 'same')
  const fmtMs = (v) => formatMs(v, locale)
  const fmtSize = (v) => formatSize(v, { locale })
  const fmtNum = (v) => formatNumber(v, locale)
  const imgShare = s.page_bytes > 0 && s.image_bytes != null ? formatPct((s.image_bytes / s.page_bytes) * 100, locale, 0) : null
  const docLinks = (Number(s.document_count) || 0) + (Number(s.link_count) || 0)
  const losses = topLosses(analysis?.scores, 3)
  const recs = analysis?.recommendations
  const redirects = Number(s.redirect_count) > 0
  const ttfbSub = redirects
    ? t('prs.kpi.ttfbRedirSub', fmtMs(s.html_ttfb_ms), plural(t, 'prs.kpi.redirPart', s.redirect_count, fmtNum(s.redirect_count), fmtMs(s.redirect_ms ?? 0)))
    : t('prs.kpi.ttfbSub', fmtMs(s.html_ttfb_ms))
  return (
    <Section slot="prs-hero" contentClassName="gap-4" titleFocusable>
      <div className="flex min-w-0 flex-row items-center gap-3 sm:gap-4">
        <GradeRing grade={grade} letter={letter} tone={tone} partial={partial} t={t} />
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <span data-focus-target="prs-hero" tabIndex={-1}
            className="text-xs font-semibold tracking-[.04em] text-muted-foreground uppercase focus-visible:rounded-sm focus-visible:outline-2 focus-visible:outline-ring">{t('prs.grade')}</span>
          <div className="flex flex-wrap items-center gap-2">
            <span data-slot="prs-verdict" data-tone={tone} data-partial={partial ? 'true' : undefined}
              className={cn('text-lg leading-tight font-bold sm:text-xl', TONE_TEXT[tone], WRAP)}>
              {partial ? t('prs.verdict.partial', letter || '—') : letter ? t(`prs.verdict.${letter}`) : t('prs.verdict.unknown')}
            </span>
            {dGrade && dGrade.direction !== 'same' && (
              <DeltaChip d={dGrade} text={t('prs.delta.points', signed(dGrade.diff, fmtNum))}
                srText={sr('prs.grade', t('prs.delta.points', signed(dGrade.diff, fmtNum)))} sameText={t('prs.delta.same')} />
            )}
          </div>
          {partial && (
            <p data-slot="prs-partial-note" className="m-0 flex items-start gap-1.5 text-sm font-medium text-amber-700 dark:text-amber-300">
              <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              <span className={WRAP}>{t('prs.grade.partial')}</span>
            </p>
          )}
          {!p && <p data-slot="prs-no-prev" className="m-0 text-xs text-muted-foreground">{t('prs.delta.noPrev')}</p>}
          {p && !cmp && <p data-slot="prs-not-compared" className="m-0 text-xs text-muted-foreground">{t('prs.delta.notCompared')}</p>}
          {cmp && (
            <p data-slot="prs-delta-base" data-all-same={allSame ? 'true' : undefined} className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
              {allSame ? t('prs.delta.allSame', formatDate(p.started_at)) : t('prs.delta.base', formatDate(p.started_at))}
            </p>
          )}
          {algoChanged && <p data-slot="prs-algo-changed" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('prs.delta.algoChanged')}</p>}
        </div>
      </div>
      <Losses losses={losses} recs={recs} t={t} locale={locale} onGoRec={onGoRec} />
      <div data-slot="prs-kpis" className="grid min-w-0 grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-5">
        <Kpi k="load" t={t} label={t('prs.kpi.load')} value={fmtMs(s.load_ms)} sub={ttfbSub}
          d={dLoad} deltaText={dLoad ? signed(dLoad.diff, fmtMs) : ''} srDelta={dLoad ? sr('prs.kpi.load', signed(dLoad.diff, fmtMs)) : ''} />
        <Kpi k="size" t={t} label={t('prs.kpi.size')} value={fmtSize(s.page_bytes)} sub={formatBytesExact(s.page_bytes, locale)}
          d={dSize} deltaText={dSize ? signed(dSize.diff, fmtSize) : ''} srDelta={dSize ? sr('prs.kpi.size', signed(dSize.diff, fmtSize)) : ''} />
        <Kpi k="requests" t={t} label={t('prs.kpi.requests')} value={fmtNum(s.request_count)}
          sub={Number(s.failed_count) > 0 ? plural(t, 'prs.kpi.brokenSub', s.failed_count, fmtNum(s.failed_count))
            : t(partial ? 'prs.kpi.noBrokenCapped' : 'prs.kpi.noBroken')}
          d={dReq} deltaText={dReq ? signed(dReq.diff, fmtNum) : ''} srDelta={dReq ? sr('prs.kpi.requests', signed(dReq.diff, fmtNum)) : ''} />
        <Kpi k="images" t={t} label={t('prs.kpi.images')} value={fmtSize(s.image_bytes)}
          sub={imgShare ? plural(t, 'prs.kpi.imagesSub', s.image_count, fmtNum(s.image_count), imgShare)
            : plural(t, 'prs.kpi.imagesCount', s.image_count, fmtNum(s.image_count))}
          d={dImg} deltaText={dImg ? signed(dImg.diff, fmtSize) : ''} srDelta={dImg ? sr('prs.kpi.images', signed(dImg.diff, fmtSize)) : ''} />
        <Kpi k="docs" t={t} label={t('prs.kpi.docsLinks')} value={fmtNum(docLinks)}
          sub={t('prs.kpi.docsSub', plural(t, 'prs.kpi.docsPart', s.document_count, fmtNum(s.document_count), fmtSize(s.document_bytes)),
            plural(t, 'prs.kpi.linksPart', s.link_count, fmtNum(s.link_count)))} />
      </div>
    </Section>
  )
}

/** "Notu en çok düşüren 3 şey" — kayıp puan büyükten küçüğe; her biri ölçülen değer + hedef + ilgili öneriye git. */
function Losses({ losses, recs, t, locale, onGoRec }) {
  if (!losses.length) {
    return <p data-slot="prs-losses-none" className="m-0 text-sm text-muted-foreground">{t('prs.loss.none')}</p>
  }
  return (
    <div data-slot="prs-losses" className="flex min-w-0 flex-col gap-1.5">
      <span className="flex items-center gap-1.5 text-xs font-semibold tracking-[.04em] text-muted-foreground uppercase">
        <TrendingDown aria-hidden="true" className="size-3.5" />{t('prs.loss.title')}
      </span>
      <ol className="m-0 grid min-w-0 list-none grid-cols-1 gap-1.5 p-0 md:grid-cols-3">
        {losses.map((r) => {
          const rec = relatedRec(r.key, recs)
          const target = scoreTargetText(r, t, locale)
          return (
            <li key={r.key} data-slot="prs-loss" data-key={r.key} className="flex min-w-0 flex-col gap-1 rounded-lg border bg-card px-3 py-2">
              <span className="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5">
                <span className={cn('text-base font-bold tabular-nums', TONE_TEXT[r.tone])}>{t('prs.loss.points', formatNumber(r.lost, locale, 1))}</span>
                <span className={cn('text-sm font-semibold text-foreground', WRAP)}>{t(scoreLabelKey(r.key))}</span>
              </span>
              <span className={cn('text-xs text-muted-foreground', WRAP)}>
                {t(`prs.score.measured.${r.known ? r.key : 'other'}`, formatScoreValue(r, locale))}{target ? ` · ${target}` : ''}
              </span>
              {rec && onGoRec && (
                <Button type="button" variant="ghost" size="sm" data-slot="prs-loss-rec" onClick={() => onGoRec(rec.code)}
                  className={cn('h-7 w-fit px-1.5 text-xs', TOUCH)}>
                  <Lightbulb aria-hidden="true" />{t('prs.loss.goRec')}
                </Button>
              )}
            </li>
          )
        })}
      </ol>
    </div>
  )
}

/** Kategori kırılımı — sayfa yükü kompozisyon çubuğu + her satır bir düğme (nesne listesini o türe süzer). */
function CategoryBreakdown({ analysis, scan, t, locale, onCategory, objectsAvailable }) {
  const rows = categoryRows(analysis?.categories, scan?.page_bytes)
  const firstOther = rows.findIndex((r) => !r.pageLoad)
  const stack = rows.filter((r) => r.pageLoad && r.share != null && r.share > 0)
  return (
    <Section slot="prs-categories" icon={Layers} title={t('prs.cat.title')}
      description={objectsAvailable ? t('prs.cat.desc') : t('prs.cat.descNoObjects')}>
      {rows.length === 0 ? (
        <p className="m-0 text-sm text-muted-foreground">{t('prs.cat.none')}</p>
      ) : (
        <>
          {stack.length > 0 && (
            // Sayfa boyutunun kompozisyonu (%100 yığılmış) — süs; değerler aşağıdaki satırlarda yazılı
            <div data-slot="prs-cat-stack" aria-hidden="true" className="flex h-3 w-full overflow-hidden rounded-full bg-muted">
              {stack.map((r, i) => (
                <span key={r.category} data-category={r.category} className="h-full"
                  style={{ width: `${Math.min(100, r.share)}%`, background: STACK_COLOR[i % STACK_COLOR.length] }} />
              ))}
            </div>
          )}
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {rows.map((r, i) => {
              const Icon = CATEGORY_ICON[r.category] || CATEGORY_ICON.OTHER
              const label = categoryLabel(r.category, t)
              const si = stack.findIndex((x) => x.category === r.category)
              const meta = (
                <>
                  <span className="flex min-w-0 flex-1 items-center gap-2">
                    {si >= 0 && <span aria-hidden="true" className="size-2.5 shrink-0 rounded-sm" style={{ background: STACK_COLOR[si % STACK_COLOR.length] }} />}
                    <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                    <span className={cn('font-medium', WRAP)}>{label}</span>
                    <span className="text-xs text-muted-foreground tabular-nums">({formatNumber(r.count, locale)})</span>
                  </span>
                  <span className="shrink-0 text-right text-xs tabular-nums">
                    <span className="font-semibold text-foreground">{formatSize(r.bytes, { locale })}</span>
                    {r.share != null && <span className="text-muted-foreground"> · {formatPct(r.share, locale)}</span>}
                  </span>
                </>
              )
              return (
                <li key={r.category} data-slot="prs-cat-row" data-category={r.category} data-page-load={r.pageLoad ? 'true' : 'false'}
                  data-bar={Math.round(r.bar)} className="flex min-w-0 flex-col gap-1">
                  {i === firstOther && (
                    <p data-slot="prs-cat-other-head" className="m-0 mt-2 mb-1 text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase">
                      {t('prs.cat.notCounted')}
                    </p>
                  )}
                  {objectsAvailable ? (
                    <Button type="button" variant="ghost" onClick={() => onCategory?.(r.category)}
                      aria-label={plural(t, 'prs.cat.showFor', r.count, label, formatNumber(r.count, locale))}
                      className={cn('h-auto w-full justify-between gap-2 px-2 py-1.5 text-left text-sm font-normal whitespace-normal', TOUCH)}>
                      {meta}
                    </Button>
                  ) : (
                    <span className="flex min-w-0 items-center justify-between gap-2 px-2 py-1.5 text-sm">{meta}</span>
                  )}
                  {/* Çubuk (r177 H3): sayfa yükünde sayfa boyutundaki PAY (yandaki yüzdeyle aynı); sayılmayanlar kendi aralarında */}
                  <div className={cn('px-2', !r.pageLoad && 'opacity-55')}>
                    <ProgressBar value={Math.max(r.bytes > 0 ? 1.5 : 0, r.bar)} max={100} size="md" decorative />
                  </div>
                </li>
              )
            })}
          </ul>
          {firstOther >= 0 && <p className="m-0 text-[11px] text-muted-foreground">{t('prs.cat.otherScale')}</p>}
        </>
      )}
    </Section>
  )
}

/** Puan bileşenleri — her biri 0–100, ağırlığı, nottan götürdüğü puan ve tam / sıfır puan hedefi. */
function ScoreBreakdown({ analysis, t, locale }) {
  const rows = scoreRows(analysis?.scores)
  return (
    <Section slot="prs-scores" icon={Gauge} title={t('prs.score.title')} description={t('prs.score.desc')}>
      {rows.length === 0 ? (
        <p className="m-0 text-sm text-muted-foreground">{t('prs.score.none')}</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
          {rows.map((r) => {
            const target = scoreTargetText(r, t, locale)
            return (
              <li key={r.key} data-slot="prs-score" data-key={r.key} data-tone={r.tone} className="flex min-w-0 flex-col gap-1">
                <div className="flex min-w-0 items-baseline justify-between gap-2 text-sm">
                  <span className={cn('font-medium', WRAP)}>{t(scoreLabelKey(r.key))}</span>
                  <span className="shrink-0 tabular-nums">
                    <span className={cn('font-bold', TONE_TEXT[r.tone])}>{r.score ?? '—'}</span>
                    <span className="text-xs text-muted-foreground">/100</span>
                  </span>
                </div>
                <ProgressBar value={r.score ?? 0} max={100} size="sm" tone={TONE_BAR[r.tone]} decorative />
                <span className={cn('text-xs text-muted-foreground', WRAP)}>
                  {t(`prs.score.measured.${r.known ? r.key : 'other'}`, formatScoreValue(r, locale))}
                  {' · '}{t('prs.score.weightLost', r.weight != null ? formatPct(r.weight, locale, 1) : '—',
                    r.lost != null ? formatNumber(r.lost, locale, 1) : '—')}
                </span>
                {target && <span data-slot="prs-score-target" className={cn('text-xs text-muted-foreground', WRAP)}>{target}</span>}
              </li>
            )
          })}
        </ul>
      )}
    </Section>
  )
}

/** Öneriler — önem rozeti, başlık + gövde, olası kazanç ve ilgili nesneler (bulgu süzgeciyle TAM liste). */
function Recommendations({ analysis, items, t, locale, onShowObjects, objectsAvailable }) {
  const recs = sortRecommendations(analysis?.recommendations)
  return (
    <Section slot="prs-recs" icon={Lightbulb} title={t('prs.rec.title')} description={t('prs.rec.desc')}>
      {recs.length === 0 ? (
        <p className="m-0 text-sm text-muted-foreground">{t('prs.rec.none')}</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {recs.map((r, i) => <RecItem key={`${r.code}#${i}`} rec={r} items={items} t={t} locale={locale} onShowObjects={onShowObjects} objectsAvailable={objectsAvailable} />)}
        </ul>
      )}
    </Section>
  )
}

function RecItem({ rec, items, t, locale, onShowObjects, objectsAvailable }) {
  const args = recommendationArgs(rec, locale, 'title')
  const bodyArgs = recommendationArgs(rec, locale, 'body')
  const sev = SEVERITIES.includes(rec.severity) ? rec.severity : 'low'
  const good = rec.code === 'ALL_GOOD'
  const saving = recSaving(rec, locale)
  const hosts = recHostList(rec)
  const urls = recItemUrls(rec)
  const byFinding = recFiltersByFinding(rec)
  const count = good ? 0 : recItemCount(rec, objectsAvailable ? items : null)
  const canShow = objectsAvailable && !good && (byFinding ? count > 0 : urls.length > 0)
  const title = t(recTextKey(rec, 'title'), ...args)
  return (
    <li data-slot="prs-rec" data-code={rec.code} data-severity={sev} tabIndex={-1}
      className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5 focus-visible:outline-2 focus-visible:outline-ring">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <ToneBadge tone={good ? 'success' : SEVERITY_TONE[sev]} className={CHIP} data-slot="prs-rec-severity">
          {good ? t('prs.sev.good') : t(`prs.sev.${sev}`)}
        </ToneBadge>
        <span className={cn('text-sm font-semibold text-foreground', WRAP)}>{title}</span>
      </div>
      <p className={cn('m-0 text-sm text-muted-foreground', WRAP)}>
        {t(recTextKey(rec, 'body'), ...bodyArgs)}
      </p>
      {hosts.length > 0 && (
        <p data-slot="prs-rec-hosts" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
          {t('prs.rec.hosts', hosts.join(', '))}
        </p>
      )}
      {(saving || canShow) && (
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          {saving && (
            <ToneBadge tone="info" className={CHIP} data-slot="prs-rec-saving">
              {saving.bytes && saving.ms ? t('prs.rec.savingBoth', saving.bytes, saving.ms)
                : saving.bytes ? t('prs.rec.savingBytes', saving.bytes) : t('prs.rec.savingMs', saving.ms)}
            </ToneBadge>
          )}
          {canShow && (
            <Button type="button" variant="outline" size="sm" data-slot="prs-rec-show" data-count={byFinding ? count : urls.length} className={TOUCH}
              aria-label={t('prs.rec.showObjectsFor', title, byFinding ? count : urls.length)} onClick={() => onShowObjects?.(rec)}>
              <ListFilter aria-hidden="true" />{t('prs.rec.showObjects', formatNumber(byFinding ? count : urls.length, locale))}
            </Button>
          )}
        </div>
      )}
      {urls.length > 0 && !objectsAvailable && (
        <Collapsible>
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm" data-slot="prs-rec-urls-toggle"
              className={cn('group h-auto justify-start px-1 text-xs text-muted-foreground', TOUCH)}>
              <ChevronDown aria-hidden="true" className="transition-transform group-data-[state=open]:rotate-180" />
              {t('prs.rec.urlsToggle', urls.length)}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <ul data-slot="prs-rec-urls" className="m-0 mt-1 flex list-none flex-col gap-1 p-0 text-xs">
              {urls.map((u) => <li key={u} className="text-muted-foreground"><UrlText url={u} t={t} className="text-xs" /></li>)}
            </ul>
          </CollapsibleContent>
        </Collapsible>
      )}
    </li>
  )
}

/** Büyük görsellerin sayfa hızına etkisi — ayrıntılı rapor (görseller ÇİZİLMEZ; adres, ölçü, boyut, 4G süresi, pay). */
function ImageImpact({ analysis, scan, t, locale }) {
  const img = analysis?.images
  if (!img) return null
  const top = (Array.isArray(img.top) ? img.top : []).slice(0, TOP_IMAGES_MAX)
  const pageBytes = Number(scan?.page_bytes) || 0
  const share = img.share_pct ?? (pageBytes > 0 && img.bytes != null ? (img.bytes / pageBytes) * 100 : null)
  const shareTone = share == null ? 'muted' : share >= 60 ? 'danger' : share >= 40 ? 'warning' : 'success'
  const measured = img.saving_measured === true
  const tiles = [
    { k: 'count', label: t('prs.img.count'), value: formatNumber(img.count, locale) },
    { k: 'bytes', label: t('prs.img.bytes'), value: formatSize(img.bytes, { locale }) },
    { k: 'large', label: t('prs.img.large'), value: formatNumber(img.large_count, locale), sub: formatSize(img.large_bytes, { locale }) },
    { k: 'est', label: t('prs.img.est'), value: formatMs(img.est_ms_4g, locale), sub: t('prs.img.est3g', formatMs(img.est_ms_3g, locale)) },
    { k: 'saving', label: t(measured ? 'prs.img.savingMeasured' : 'prs.img.savingEstimated'), value: formatSize(img.saving_bytes, { locale }),
      sub: img.saving_ms_4g != null ? t('prs.img.savingMs', formatMs(img.saving_ms_4g, locale)) : null },
  ]
  return (
    <Section slot="prs-images" icon={ImageIcon} title={t('prs.img.title')} description={t(measured ? 'prs.img.descMeasured' : 'prs.img.desc')}>
      <p data-slot="prs-img-summary" className={cn('m-0 text-sm text-foreground', WRAP)}>
        {Number(img.count) > 0
          ? t('prs.img.summary', formatPct(share, locale, 0), formatSize(img.bytes, { locale }), formatMs(img.est_ms_4g, locale),
            formatMs(img.est_ms_3g, locale))
          : t('prs.img.summaryNone')}
        {Number(img.large_count) > 0 && Number(img.saving_bytes) > 0 && (
          <> {plural(t, measured ? 'prs.img.summarySavingMeasured' : 'prs.img.summarySaving', img.large_count, formatNumber(img.large_count, locale),
            formatSize(img.saving_bytes, { locale }), formatMs(img.saving_ms_4g, locale))}</>
        )}
      </p>
      {share != null && (
        <div data-slot="prs-img-share" data-tone={shareTone} className="flex flex-col gap-1">
          <div className="flex items-baseline justify-between gap-2 text-xs">
            <span className="font-semibold text-muted-foreground">{t('prs.img.shareLabel')}</span>
            <span className={cn('font-bold tabular-nums', TONE_TEXT[shareTone])}>{formatPct(share, locale, 1)}</span>
          </div>
          <ProgressBar value={Math.min(100, share)} max={100} size="md" tone={TONE_BAR[shareTone]} decorative />
        </div>
      )}
      <div className="grid min-w-0 grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-5">
        {tiles.map((x) => (
          <div key={x.k} data-slot="prs-img-tile" data-key={x.k} data-measured={x.k === 'saving' ? String(measured) : undefined}
            className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/30 px-3 py-2">
            <span className="text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{x.label}</span>
            <span className="text-base leading-tight font-bold tabular-nums">{x.value}</span>
            {x.sub && <span className={cn('text-xs text-muted-foreground', WRAP)}>{x.sub}</span>}
          </div>
        ))}
      </div>
      {top.length > 0 && (
        <div className="flex min-w-0 flex-col gap-1.5">
          <span className="text-xs font-semibold tracking-[.04em] text-muted-foreground uppercase">{t('prs.img.topTitle')}</span>
          <ol data-slot="prs-img-top" className="m-0 flex list-none flex-col gap-1.5 p-0">
            {top.map((it, i) => {
              const est = estImageMs(it.bytes, img)
              const pct = pageBytes > 0 && it.bytes != null ? (it.bytes / pageBytes) * 100 : null
              const dim = dimensionsText(it)
              const opt = optimizedImage(it)
              return (
                <li key={`${it.url}#${i}`} data-slot="prs-image-row" className="flex min-w-0 flex-col gap-1 rounded-md border px-3 py-2">
                  <div className="flex min-w-0 items-start gap-2">
                    <span className="mt-px w-5 shrink-0 text-right text-xs font-bold text-muted-foreground tabular-nums">{i + 1}.</span>
                    {/* Adres bağlantı (r177 H15 — Nesneler'deki gibi), görsel ÇİZİLMEZ */}
                    <UrlText url={it.url} t={t} className="flex-1 text-xs" />
                  </div>
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 pl-7 text-xs text-muted-foreground tabular-nums">
                    <span className="font-semibold text-foreground">{formatSize(it.bytes, { truncated: it.truncated === true, locale })}</span>
                    {opt && (
                      <span data-slot="prs-img-optimized" className="font-semibold text-success">
                        {t('prs.img.optimizedTo', formatSize(opt.bytes, { locale }), formatSize(opt.optimized, { locale }), formatPct(opt.savedPct, locale, 0))}
                      </span>
                    )}
                    <span>{dim ? t('prs.img.dims', dim) : t('prs.img.dimsUnknown')}</span>
                    {est != null && <span>{t('prs.img.est4gItem', formatMs(est, locale))}</span>}
                    {it.lazy === true && <span>{t('prs.obj.lazy')}</span>}
                    {pct != null && <span>{t('prs.img.sharePage', formatPct(pct, locale))}</span>}
                  </div>
                  {pct != null && (
                    <div className="pl-7">
                      <ProgressBar value={Math.max(1, Math.min(100, pct))} max={100} size="sm" decorative />
                    </div>
                  )}
                </li>
              )
            })}
          </ol>
        </div>
      )}
    </Section>
  )
}

/** Üçüncü taraf sunucular — sayı, bayt, açılmayan, türler (sunucu `third_parties`; eski analizde nesnelerden). */
function ThirdParties({ analysis, items, t, locale }) {
  const tp = thirdPartyRows(analysis, items)
  const [ref, width] = useElementWidth()
  if (!tp) return null
  const table = width === 0 || width >= THIRD_TABLE_MIN_PX
  return (
    <Section slot="prs-third" icon={Globe} title={t('prs.third.title')} description={t('prs.third.desc')}>
      <div ref={ref} data-slot="prs-third-list" data-layout={table ? 'table' : 'cards'} className="min-w-0">
        {tp.rows.length === 0 ? (
          <p data-slot="prs-third-none" className="m-0 text-sm text-muted-foreground">{t('prs.third.none')}</p>
        ) : table ? (
          <div className="overflow-hidden rounded-xl border">
            <Table className="table-fixed">
              <TableHeader>
                <TableRow>
                  <TableHead className="whitespace-normal">{t('prs.third.host')}</TableHead>
                  <TableHead className="w-[12%] whitespace-normal text-right">{t('prs.third.count')}</TableHead>
                  <TableHead className="w-[15%] whitespace-normal text-right">{t('prs.col.size')}</TableHead>
                  <TableHead className="w-[13%] whitespace-normal text-right">{t('prs.third.broken')}</TableHead>
                  <TableHead className="w-[26%] whitespace-normal">{t('prs.third.categories')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tp.rows.map((r) => (
                  <TableRow key={r.host} data-slot="prs-third-row" data-host={r.host}>
                    <TableCell className={cn('align-top text-xs whitespace-normal', WRAP)}>{r.host}</TableCell>
                    <TableCell className="align-top text-right text-xs tabular-nums">{formatNumber(r.count, locale)}</TableCell>
                    <TableCell className="align-top text-right text-xs tabular-nums">{formatSize(r.bytes, { locale })}</TableCell>
                    <TableCell className={cn('align-top text-right text-xs tabular-nums', r.failed > 0 && 'font-semibold text-destructive')}>{formatNumber(r.failed, locale)}</TableCell>
                    <TableCell className={cn('align-top text-xs whitespace-normal', WRAP)}>{r.categories.map((c) => categoryLabel(c, t)).join(', ')}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        ) : (
          <ul className="m-0 grid list-none grid-cols-1 gap-1.5 p-0">
            {tp.rows.map((r) => (
              <li key={r.host} data-slot="prs-third-row" data-host={r.host} className="flex min-w-0 flex-col gap-1 rounded-md border px-3 py-2">
                <span className={cn('text-sm font-medium text-foreground', WRAP)}>{r.host}</span>
                <span className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground tabular-nums">
                  <span>{plural(t, 'prs.third.countItem', r.count, formatNumber(r.count, locale))}</span>
                  <span className="font-semibold text-foreground">{formatSize(r.bytes, { locale })}</span>
                  {r.failed > 0 && <span className="font-semibold text-destructive">{plural(t, 'prs.third.brokenItem', r.failed, formatNumber(r.failed, locale))}</span>}
                  <span className={WRAP}>{r.categories.map((c) => categoryLabel(c, t)).join(', ')}</span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
      {tp.source === 'client' && tp.rows.length > 0 && <p className="m-0 text-[11px] text-muted-foreground">{t('prs.third.fromObjects')}</p>}
    </Section>
  )
}

/** Ölçüm notları (sunucu `analysis.notes`) — küçük, soluk liste. */
function MeasurementNotes({ analysis, t, locale }) {
  const notes = noteRows(analysis?.notes, locale)
  if (!notes.length) return null
  return (
    <Section slot="prs-notes" icon={NotebookText} title={t('prs.note.title')} className="bg-muted/20">
      <ul className="m-0 flex list-disc flex-col gap-1 pl-5 text-xs text-muted-foreground">
        {notes.map((n, i) => (
          <li key={`${n.code}#${i}`} data-slot="prs-note" data-code={n.code} className={WRAP}>
            {t(n.key, ...n.args)}
          </li>
        ))}
      </ul>
    </Section>
  )
}
