import { useEffect, useRef, useState } from 'react'
import { ArrowRight, Calculator, CircleAlert, Info, TriangleAlert } from 'lucide-react'
import { api } from '../../../../api/client'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import AlertBanner from '../../../ui/AlertBanner.jsx'
import ErrorDetails from '../../../ui/ErrorDetails.jsx'
import SegmentedControl from '../../../ui/SegmentedControl.jsx'
import { Spinner } from '../../../ui/Progress.jsx'
import ToneBadge from '../../../admin/ToneBadge.jsx'
import { formatMs, formatNumber, formatSize, gradeTone, plural } from '../resourceScanModel.js'
import { CHIP, Section, TONE_TEXT, TOUCH, WRAP, signed } from '../ResourceScanParts.jsx'
import { useImageTool, useTopic } from './ImageToolContext.jsx'
import { projectionImages, projectionView, serverProblemCode, serverProblemText } from './imageToolModel.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * "Görseller optimize edilse not ne olurdu?" (2026-11-14, kullanıcı kararı 10) — pano kartı. Tek tıkla sunucu AYNI
 * çözümleyiciyi (PageResourceAnalyzer) analizin saklı nesnelerinin bir KOPYASI üzerinde, görsel boyutları değiştirilmiş
 * olarak yeniden koşturur (bir şey yazmaz, dışarı istek atmaz): önce sunucunun kendi ölçümüyle (`use_measured`), kullanıcı
 * bu sekmede görsel işlediyse o GERÇEK sonuçlarla. Gösterilen: not / harf önce → sonra, sayfa boyutu, görsel baytı,
 * tahmini yüklenme, büyük görsel sayısı ve bileşen bileşen değişim.
 *
 * Dürüstlük: sınırlı (kısmi) analizde sonuç ALT SINIRDIR — soluk, "kısmi" uyarılı, kesin gibi sunulmaz; bilinmeyen not "—"
 * (asla yeşil); eski sunucu "bu sürümde yok" der; okunamayan sonuç "hesaplanamadı" + yeniden dene (asla 0 fark).
 */
export default function GradeProjectionCard({ scan, analysis }) {
  const t = useT()
  const locale = useDateLocale()
  const ctx = useImageTool()
  const store = ctx?.store ?? null
  const scanId = scan?.id ?? null
  // Bu sekmede işlenen görseller değişince (sürüm sayacı) liste yeniden okunur
  const version = useTopicOrZero(store)
  const mine = store && scanId != null ? projectionImages(store.processedFor(scanId)) : []
  const [basis, setBasis] = useState('measured')
  const [state, setState] = useState({ status: 'idle', view: null, res: null, basis: null, version: 0 })
  const seq = useRef(0)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  // Başka analize geçilince eski sonuç gösterilmez (geç yanıt da düşer)
  useEffect(() => { seq.current += 1; setState({ status: 'idle', view: null, res: null, basis: null, version: 0 }); setBasis('measured') }, [scanId])

  const imageCount = Number(analysis?.images?.count) || 0
  if (!ctx || scanId == null || scan?.status !== 'DONE' || imageCount === 0) return null
  const itemsGone = ctx.itemsKept === false
  const effBasis = basis === 'mine' && mine.length === 0 ? 'measured' : basis

  async function run() {
    const my = ++seq.current
    setState((s) => ({ ...s, status: 'loading', res: null }))
    let res = null
    try {
      res = await api.monitoring.resourceScanImageProjection(ctx.monitorId, scanId,
        effBasis === 'mine' ? { use_measured: false, images: mine } : { use_measured: true })
    } catch (e) {
      res = { success: false, status: 0, error: e?.message || null }
    }
    if (!alive.current || my !== seq.current) return
    const view = res?.success ? projectionView(res.data) : null
    if (view) setState({ status: 'ok', view, res: null, basis: effBasis, version })
    else setState({ status: res?.success ? 'unreadable' : 'failed', view: null, res, basis: effBasis, version })
  }

  const v = state.view
  const loading = state.status === 'loading'
  const outdated = state.status === 'ok' && (state.basis !== effBasis || (effBasis === 'mine' && state.version !== version))
  const unsupported = state.status === 'failed' && serverProblemCode(state.res) === 'unsupported'
  const fmtSize = (x) => formatSize(x, { locale })
  const fmtNum = (x) => formatNumber(x, locale)

  return (
    <Section slot="prs-it-projection" icon={Calculator} title={t('prs.it.proj.title')} description={t('prs.it.proj.desc')}
      data-status={state.status} data-capped={v?.capped ? 'true' : undefined}
      actions={!itemsGone && !unsupported && (
        <Button type="button" variant={state.status === 'ok' && !outdated ? 'outline' : 'default'} size="sm" className={TOUCH} data-slot="prs-it-proj-run"
          disabled={loading} aria-busy={loading || undefined} onClick={run}>
          {loading ? <Spinner size={14} inline decorative /> : <Calculator aria-hidden="true" />}
          {t(state.status === 'idle' ? 'prs.it.proj.run' : 'prs.it.proj.rerun')}
        </Button>
      )}>
      {itemsGone ? (
        <p data-slot="prs-it-proj-gone" className={cn('m-0 flex items-start gap-2 text-sm text-muted-foreground', WRAP)}>
          <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />{t('prs.it.srv.RESOURCE_SCAN_ITEMS_GONE')}
        </p>
      ) : (
        <>
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <span data-slot="prs-it-proj-basis" data-basis={effBasis}>
              <SegmentedControl value={effBasis} onChange={setBasis} ariaLabel={t('prs.it.proj.basis')} className="flex-wrap"
                options={[
                  { value: 'measured', label: t('prs.it.proj.basisMeasured') },
                  { value: 'mine', label: plural(t, 'prs.it.proj.basisMine', mine.length, fmtNum(mine.length)), disabled: mine.length === 0 },
                ]} />
            </span>
            <span className={cn('text-xs text-muted-foreground', WRAP)}>
              {effBasis === 'mine' ? t('prs.it.proj.basisMineHint') : mine.length === 0 ? t('prs.it.proj.basisMineNone') : t('prs.it.proj.basisMeasuredHint')}
            </span>
          </div>

          {state.status === 'idle' && <p data-slot="prs-it-proj-idle" className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('prs.it.proj.idle')}</p>}
          {unsupported && (
            <p data-slot="prs-it-proj-unsupported" className={cn('m-0 flex items-start gap-2 text-sm text-muted-foreground', WRAP)}>
              <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />{t('prs.it.srv.unsupported')}
            </p>
          )}
          {state.status === 'failed' && !unsupported && (
            <div data-slot="prs-it-proj-failed">
              <AlertBanner tone="danger" className="mb-0" icon={CircleAlert} title={t('prs.it.proj.notComputed')}>
                <span className={WRAP}>{serverProblemText(state.res, t, locale)}</span>
                <ErrorDetails info={state.res} />
              </AlertBanner>
            </div>
          )}
          {state.status === 'unreadable' && (
            <div data-slot="prs-it-proj-failed">
              <AlertBanner tone="danger" className="mb-0" icon={CircleAlert} title={t('prs.it.proj.notComputed')}>{t('prs.it.proj.unreadable')}</AlertBanner>
            </div>
          )}

          {v && (
            <div data-slot="prs-it-proj-result" data-outdated={outdated ? 'true' : undefined} className={cn('flex min-w-0 flex-col gap-3', outdated && 'opacity-60')}>
              {outdated && <p data-slot="prs-it-proj-outdated" className="m-0 text-xs text-muted-foreground">{t('prs.it.proj.outdated')}</p>}
              {v.capped && (
                <div data-slot="prs-it-proj-partial">
                  <AlertBanner tone="warning" className="mb-0" icon={TriangleAlert} title={t('prs.it.proj.partialTitle')}>{t('prs.it.proj.partialBody')}</AlertBanner>
                </div>
              )}
              <div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-2">
                <GradeBox label={t('prs.it.proj.now')} side={v.before} partial={v.capped} t={t} />
                <ArrowRight aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
                <GradeBox label={t('prs.it.proj.then')} side={v.after} partial={v.capped} t={t} slot="prs-it-proj-after" />
                <span data-slot="prs-it-proj-diff" data-known={v.gradeDiff === null ? 'false' : 'true'}
                  className={cn('text-sm font-semibold tabular-nums', v.gradeDiff === null || v.capped ? 'text-muted-foreground' : v.gradeDiff > 0 ? 'text-success' : 'text-muted-foreground', WRAP)}>
                  {v.gradeDiff === null ? t('prs.it.proj.diffUnknown')
                    : v.gradeDiff === 0 ? t('prs.it.proj.diffNone')
                      : t(v.capped ? 'prs.it.proj.diffPartial' : 'prs.it.proj.diff', signed(v.gradeDiff, fmtNum))}
                </span>
              </div>
              <dl data-slot="prs-it-proj-kpis" className="m-0 grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-4">
                <Kpi k="page" label={t('prs.kpi.size')} before={v.before.pageBytes} after={v.after.pageBytes} diff={v.pageBytesDiff} fmt={fmtSize} t={t} />
                <Kpi k="images" label={t('prs.kpi.images')} before={v.before.imageBytes} after={v.after.imageBytes} diff={v.imageBytesDiff} fmt={fmtSize} t={t} />
                <Kpi k="load" label={t('prs.kpi.load')} before={v.before.loadMs} after={v.after.loadMs} diff={v.loadMsDiff} fmt={(x) => formatMs(x, locale)} t={t} />
                <Kpi k="large" label={t('prs.img.large')} before={v.before.largeImages} after={v.after.largeImages}
                  diff={v.before.largeImages !== null && v.after.largeImages !== null ? v.after.largeImages - v.before.largeImages : null} fmt={fmtNum} t={t} />
              </dl>
              <div className="flex min-w-0 flex-col gap-1.5">
                <span className="text-xs font-semibold tracking-[.04em] text-muted-foreground uppercase">{t('prs.it.proj.components')}</span>
                {v.rows.length === 0 ? <p className="m-0 text-xs text-muted-foreground">{t('prs.it.proj.noComponents')}</p> : (
                  <ul data-slot="prs-it-proj-rows" className="m-0 flex list-none flex-col gap-1 p-0">
                    {v.rows.map((r) => (
                      <li key={r.key} data-slot="prs-it-proj-row" data-key={r.key} data-changed={r.changed ? 'true' : 'false'}
                        className={cn('flex min-w-0 flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 rounded-md px-2 py-1 text-sm', r.changed ? 'bg-muted/50' : 'text-muted-foreground')}>
                        <span className={cn('font-medium', WRAP)}>{t(r.labelKey)}</span>
                        <span className="tabular-nums">
                          {r.before ?? '—'} → <span className={cn('font-semibold', r.changed && !v.capped && r.after !== null && r.before !== null && r.after > r.before && 'text-success')}>{r.after ?? '—'}</span>
                          <span className="text-xs text-muted-foreground"> /100</span>
                          {r.gained !== null && r.gained !== 0 && (
                            <span className="ml-2 text-xs text-muted-foreground">{t('prs.it.proj.gained', signed(r.gained, (x) => formatNumber(x, locale, 1)))}</span>
                          )}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <p data-slot="prs-it-proj-applied" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
                {v.applied !== null ? plural(t, 'prs.it.proj.applied', v.applied, fmtNum(v.applied)) : t('prs.it.proj.appliedUnknown')}
                {v.ignored !== null && v.ignored > 0 ? ` ${plural(t, 'prs.it.proj.ignored', v.ignored, fmtNum(v.ignored))}` : ''}
                {' '}{t('prs.it.proj.method')}
              </p>
            </div>
          )}
        </>
      )}
    </Section>
  )
}

/** Depo yoksa (bağlamsız çizim) abonelik de yok — sürüm 0. */
function useTopicOrZero(store) {
  const fallback = useRef({ on: () => () => {}, resultsVersion: () => 0 })
  const s = store || fallback.current
  return useTopic(s, 'results', () => s.resultsVersion())
}

/** Not kutusu: sayı + harf; bilinmeyen "—" (soluk — asla yeşil), kısmi analizde soluk. */
function GradeBox({ label, side, partial, t, slot = 'prs-it-proj-before' }) {
  const tone = side.grade === null || partial ? 'muted' : gradeTone(side.letter)
  return (
    <div data-slot={slot} data-tone={tone} className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/30 px-3 py-2">
      <span className="text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{label}</span>
      <span className="flex items-baseline gap-1.5">
        <span className={cn('text-2xl leading-none font-bold tabular-nums', TONE_TEXT[tone] || TONE_TEXT.muted)}>{side.grade ?? '—'}</span>
        <span className="text-xs text-muted-foreground">/100</span>
        {side.letter && <ToneBadge tone={tone} className={cn(CHIP, 'font-bold')}>{side.letter}</ToneBadge>}
      </span>
      {side.grade === null && <span className="text-[11px] text-muted-foreground">{t('prs.it.proj.gradeUnknown')}</span>}
    </div>
  )
}

function Kpi({ k, label, before, after, diff, fmt, t }) {
  return (
    <div data-slot="prs-it-proj-kpi" data-key={k} className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/30 px-3 py-2">
      <dt className="text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{label}</dt>
      <dd className={cn('m-0 text-sm font-semibold tabular-nums', WRAP)}>{fmt(before)} → {fmt(after)}</dd>
      <dd className="m-0 text-xs text-muted-foreground tabular-nums">
        {diff === null ? t('prs.it.proj.diffUnknown') : diff === 0 ? t('prs.it.proj.same') : signed(diff, fmt)}
      </dd>
    </div>
  )
}
