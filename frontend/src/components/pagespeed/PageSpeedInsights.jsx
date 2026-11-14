import { Suspense, lazy, useEffect, useRef, useState } from 'react'
import { Info } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { api } from '../../api/client'
import { formatBytes } from '../../utils/formatBytes.js'
import { ProgressBar, LoadingBlock } from '../ui/Progress.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { failureKind, phasesOf, typeBreakdownOf } from './pageSpeedInsightsModel.js'

const PageSpeedTypeChart = lazy(() => import('./PageSpeedTypeChart.jsx'))

/** Faz renkleri — durum tonu DEĞİL (yeşil / kırmızı / amber kullanılmaz): mavi / mor ve karışımları. */
export const PHASE_COLOR = {
  dns: 'var(--chart-1)',
  connect: 'color-mix(in oklab, var(--chart-1) 55%, var(--chart-5))',
  tls: 'var(--chart-5)',
  server: 'color-mix(in oklab, var(--chart-5) 55%, var(--foreground))',
  download: 'color-mix(in oklab, var(--chart-1) 45%, var(--muted-foreground))',
}

const SECTION = 'rounded-lg border bg-card px-3.5 py-3'
const LABEL = 'text-[.82em] font-semibold tracking-[.03em] text-muted-foreground uppercase'

/**
 * Sayfa Hızı detayının "Kırılım" sekmesi (2026-11-14, madde 8 / 9 / 15): son ölçümün süre kırılımı (yatay yığın — her
 * bölüt fazın süresi oranında), tür kırılımı (bayt + dosya, kendi / üçüncü taraf) ve tür kırılımının zaman içindeki
 * değişimi (tembel grafik). Bilinmeyen değer asla 0 çizilmez: eski satır "kırılım yok" der, seri boşluk bırakır.
 *
 * <p>Test kancaları: `pspd-insights`, `pspd-phases` (+ `data-state` ready|none), `pspd-phase` (`data-phase`),
 * `pspd-types` (+ `data-state`), `pspd-type-row` (`data-type`), `pspd-party`, `pspd-type-series` (+ `data-state`).
 */
export default function PageSpeedInsights({ monitor: m }) {
  const t = useT()
  return (
    <div data-slot="pspd-insights" className="flex min-w-0 flex-col gap-3">
      <PhaseBar m={m} t={t} />
      <TypeBreakdown m={m} t={t} />
      <TypeSeries monitorId={m?.id} t={t} />
    </div>
  )
}

function PhaseBar({ m, t }) {
  const phases = phasesOf(m)
  const total = phases.reduce((s, p) => s + p.ms, 0)
  const text = phases.map((p) => `${t(`pspd.ins.${p.key}`)} ${p.ms} ms`).join(' · ')
  return (
    <section data-slot="pspd-phases" data-state={phases.length ? 'ready' : 'none'} className={SECTION}>
      <div className="mb-2 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <h3 className={cn(LABEL, 'm-0')}>{t('pspd.ins.phases')}</h3>
        <HintPopover content={t('pspd.ins.phasesNote')} aria-label={t('pspd.ins.phasesNote')}
          triggerClassName="size-6 shrink-0 text-muted-foreground hover:text-foreground">
          <Info aria-hidden="true" className="size-3.5" />
        </HintPopover>
      </div>
      {phases.length === 0 ? (
        <p className="m-0 text-sm text-muted-foreground">{t('pspd.ins.phasesNone')}</p>
      ) : (
        <figure className="m-0 min-w-0">
          {/* Yığın: sütun genişliği fazın süresi (fr) — yüzde genişlik değil, veri grafiği. 0 ms faz ince bir çizgi kalır. */}
          <div role="img" aria-label={t('pspd.ins.phasesAria', text)}
            className="grid h-3 w-full gap-px overflow-hidden rounded-full bg-border"
            style={{ gridTemplateColumns: phases.map((p) => `${Math.max(p.ms, total > 0 ? total / 400 : 1)}fr`).join(' ') }}>
            {phases.map((p) => (
              <span key={p.key} data-slot="pspd-phase-seg" data-phase={p.key} className="block h-full min-w-px"
                style={{ background: PHASE_COLOR[p.key] }} />
            ))}
          </div>
          <figcaption>
            <ul className="m-0 mt-2 flex min-w-0 list-none flex-wrap gap-x-4 gap-y-1 p-0 text-xs">
              {phases.map((p) => (
                <li key={p.key} data-slot="pspd-phase" data-phase={p.key} className="inline-flex items-center gap-1.5">
                  <span aria-hidden="true" className="inline-block size-2.5 shrink-0 rounded-sm" style={{ background: PHASE_COLOR[p.key] }} />
                  <span className="text-muted-foreground">{t(`pspd.ins.${p.key}`)}</span>
                  <strong className="tabular-nums">{p.ms} ms</strong>
                </li>
              ))}
            </ul>
          </figcaption>
        </figure>
      )}
    </section>
  )
}

function TypeBreakdown({ m, t }) {
  const b = typeBreakdownOf(m)
  const partial = m?.time_truncated === true || m?.bytes_truncated === true
  return (
    <section data-slot="pspd-types" data-state={b ? 'ready' : 'none'} className={SECTION}>
      <h3 className={cn(LABEL, 'm-0 mb-2')}>{t('pspd.ins.types')}</h3>
      {!b ? (
        <p className="m-0 text-sm text-muted-foreground">{t('pspd.ins.typesNone')}</p>
      ) : (
        <>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {b.rows.map((r) => (
              <li key={r.key} data-slot="pspd-type-row" data-type={r.key} className="min-w-0">
                <div className="flex min-w-0 flex-wrap items-baseline justify-between gap-x-3 text-xs">
                  <span className="font-medium">{t(`pspd.type.${r.key}`)}</span>
                  <span className="text-muted-foreground tabular-nums">
                    <strong className="text-foreground">{formatBytes(r.bytes, partial)}</strong>
                    {r.count != null && <> · {r.count === 1 ? t('pspd.ins.typeCount.one') : t('pspd.ins.typeCount', r.count)}</>}
                  </span>
                </div>
                <ProgressBar size="sm" className="mt-1 h-1.5" decorative value={r.bytes} max={b.total > 0 ? b.total : 1} />
              </li>
            ))}
          </ul>
          {(b.firstParty != null || b.thirdParty != null) && (
            <p data-slot="pspd-party" className="m-0 mt-2.5 text-xs text-muted-foreground">
              <span className="font-medium text-foreground">{t('pspd.ins.party')}: </span>
              {t('pspd.ins.firstParty', formatBytes(b.firstParty, partial))} · {t('pspd.ins.thirdParty', formatBytes(b.thirdParty, partial))}
            </p>
          )}
        </>
      )}
    </section>
  )
}

const RANGES = [1, 7, 30]

/**
 * Tür kırılımı serisi — veri burada okunur (sıra damgalı: geç dönen eski aralık yeniyi ezmez), grafik tembel yüklenir.
 * 404 / 405 = eski sunucu → "desteklemiyor"; okuma hatası → "okunamadı" + yeniden dene; boş seri → açıklamalı boş durum.
 */
function TypeSeries({ monitorId, t }) {
  const [days, setDays] = useState(7)
  const [state, setState] = useState({ status: 'loading', data: null })
  const [nonce, setNonce] = useState(0)
  const seq = useRef(0)
  useEffect(() => {
    if (monitorId == null) return undefined
    const my = ++seq.current
    setState((s) => ({ status: 'loading', data: s.data }))
    Promise.resolve(api.monitoring.getPageSpeedTypeSeries(monitorId, { days }))
      .then((res) => {
        if (my !== seq.current) return
        if (res?.success) setState({ status: 'ready', data: res.data })
        else setState({ status: failureKind(res), data: null })
      })
      .catch(() => { if (my === seq.current) setState({ status: 'error', data: null }) })
    return () => { seq.current++ }
  }, [monitorId, days, nonce])

  return (
    <section data-slot="pspd-type-series" data-state={state.status} className={SECTION}>
      <div className="mb-2 flex min-w-0 flex-wrap items-center justify-between gap-2">
        <h3 className={cn(LABEL, 'm-0')}>{t('pspd.typeChart.title')}</h3>
        <SegmentedControl ariaLabel={t('pspd.range.label')} value={days} onChange={setDays}
          options={RANGES.map((d) => ({ value: d, label: t(`pspd.range.${d}d`) }))}
          className="max-w-full flex-wrap pointer-coarse:[&>[data-slot=toggle-group-item]]:h-10" />
      </div>
      {state.status === 'loading' && !state.data ? (
        <LoadingBlock label={t('modal.loading')} />
      ) : state.status === 'unsupported' ? (
        <p className="m-0 text-sm text-muted-foreground">{t('pspd.typeChart.unsupported')}</p>
      ) : state.status === 'gone' ? (
        <p data-slot="pspd-type-series-gone" className="m-0 text-sm text-muted-foreground">{t('pspd.monitorGone')}</p>
      ) : state.status === 'error' ? (
        <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm text-destructive">
          <span>{t('pspd.typeChart.loadFailed')}</span>
          <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => setNonce((n) => n + 1)}>
            {t('hist.retry')}
          </Button>
        </div>
      ) : (
        <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
          <PageSpeedTypeChart points={state.data?.points} truncated={state.data?.truncated === true} />
        </Suspense>
      )}
    </section>
  )
}
