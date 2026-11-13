import { useCallback, useEffect, useRef, useState } from 'react'
import { HardDrive, Pencil } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import Field from '../../ui/Field.jsx'
import { SectionSkeleton } from '../../ui/LazySection.jsx'
import { RefreshButton, SectionError, SectionToolbar, SubTitle } from '../health/HealthParts.jsx'
import { Panel, Tile } from '../httpmetrics/HttpParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { TotalSizeChart, TopTablesChart } from './DbGrowthCharts.jsx'
import DbGrowthFindings from './DbGrowthFindings.jsx'
import DbGrowthTables from './DbGrowthTables.jsx'
import DbGrowthAnalysis from './DbGrowthAnalysis.jsx'
import {
  DEFAULT_RANGE, RANGES, daysTone, formatBytes, formatDay, formatNumber, formatPct, formatSignedBytes, parseCapacity,
  usedTone, viewState,
} from './dbGrowthModel.js'

/**
 * Sistem Sağlığı → Veritabanı büyümesi (2026-11-13, kullanıcı kararı: dört parça birden).
 *
 * <p>Günlük boyut geçmişi + tahmin (son 30 günün Theil–Sen eğimi; ≥ 7 gün yoksa istatistiklerden "tahmin" etiketli soğuk
 * başlangıç), tablo başına saklama önerileri (kısaltma GLOBAL yöneticide tek tıkla, Veri Saklama'nın aynı kayıt ucundan),
 * istek üzerine salt okunur sağlık analizi ve kapasite. Bölüm açılınca yüklenir; kendiliğinden yenilenmez (her açılışta
 * sunucunun 60 sn'lik önbelleği). Bilinmeyen gri kalır — hiçbir değer "yeşil" boyanmaz.
 */
export default function DbGrowthSection({ isAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const [range, setRange] = useState(DEFAULT_RANGE)
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [capOpen, setCapOpen] = useState(false)
  const seq = useRef(0)

  const load = useCallback(async (fresh = false) => {
    const my = ++seq.current
    setLoading(true); setError(null)
    try {
      const res = await api.admin.dbGrowth.get(range, fresh)
      if (my !== seq.current) return
      if (res?.success) setData(res.data)
      else setError(res?.error || t('dbg.loadFailed'))
    } catch (e) {
      if (my === seq.current) setError(e?.message || t('dbg.loadFailed'))
    } finally {
      if (my === seq.current) setLoading(false)
    }
  }, [range, t])

  useEffect(() => { load(false) }, [load])

  const afterApply = useCallback((info) => {
    // Yasal tutma açıkken "ilk gece temizliğinde uygulanır" yanıltır: hold kapanana dek hiçbir satır silinmez.
    toast.success(info?.hold ? t('dbg.apply.doneHold') : t('dbg.apply.done'))
    load(true)
  }, [load, t, toast])

  if (!data && loading) return <SectionSkeleton rows={2} />
  if (!data && error) return <SectionError t={t} onRetry={() => load(true)} description={error} />
  if (!data) return null

  const state = viewState(data)
  const k = data.kpis || {}
  const canOpenRetention = !!data.can_open_retention && isAdmin

  const toolbar = (
    <SectionToolbar
      left={(
        <span data-slot="dbg-updated" className="[overflow-wrap:anywhere]">
          {data.last_day ? t('dbg.lastSnapshot', formatDay(data.last_day)) : t('dbg.noSnapshot')}
          {data.history_days > 0 && ` · ${t('dbg.historyDays', formatNumber(data.history_days))}`}
        </span>
      )}>
      {state !== 'unsupported' && (
        <SegmentedControl value={range} onChange={setRange} ariaLabel={t('dbg.rangeLabel')}
          options={RANGES.map((r) => ({ value: r, label: t('dbg.rangeDays', r) }))} />
      )}
      <RefreshButton onClick={() => load(true)} busy={loading} label={t('dbg.refresh')} />
    </SectionToolbar>
  )

  if (state === 'unsupported') {
    return (
      <div data-slot="dbg-section" data-state="unsupported" className="flex flex-col gap-3">
        {toolbar}
        <StatusBlock tone="neutral" icon={HardDrive} title={t('dbg.notPostgresTitle')} description={t('dbg.notPostgres')} />
      </div>
    )
  }

  const capKnown = data.capacity_gb != null
  // Boyut okunamadıysa doluluk "%0" değil "—" (sunucu used_pct'yi null gönderir)
  const sizeKnown = k.current_bytes != null
  const usedValue = capKnown && sizeKnown ? formatPct(k.used_pct) : '—'
  const usedSub = !capKnown
    ? t('dbg.kpi.noCapacity')
    : sizeKnown
      ? t('dbg.kpi.usedOf', formatBytes(k.current_bytes), formatBytes(Number(data.capacity_gb) * 1024 ** 3))
      : t('dbg.kpi.sizeUnknown')
  const stale = data.forecast_stale === true
  let fullValue = '—'
  let fullSub = t('dbg.kpi.noCapacity')
  if (capKnown) {
    if (!sizeKnown) fullSub = t('dbg.kpi.sizeUnknown')
    else if (stale) fullSub = t('dbg.kpi.staleForecast', formatNumber(data.snapshot_age_days))
    else if (data.forecast_basis === 'none') fullSub = t('dbg.kpi.noForecast')
    else if (k.days_until_full != null) {
      fullValue = t('dbg.daysCount', formatNumber(k.days_until_full))
      fullSub = k.days_until_full_estimate ? t('dbg.kpi.estimate') : t('dbg.kpi.fromHistory')
    } else if (k.fills_within_horizon === false) {
      fullValue = t('dbg.kpi.notWithin')
      fullSub = k.days_until_full_estimate ? t('dbg.kpi.estimate') : t('dbg.kpi.fromHistory')
    }
  }
  const est = (flag) => (flag ? t('dbg.kpi.estimate') : t('dbg.kpi.fromHistory'))

  return (
    <div data-slot="dbg-section" data-state={state} className="flex min-w-0 flex-col gap-3">
      {toolbar}
      {error && (
        <AlertBanner tone="warning" className="mb-0"
          actions={<Button type="button" size="sm" variant="secondary" onClick={() => load(true)}>{t('db.retry')}</Button>}>
          {t('dbg.refreshFailed')}
        </AlertBanner>
      )}

      <div className="@container min-w-0">
        <div data-slot="dbg-kpis" className="grid grid-cols-2 gap-2 @lg:grid-cols-3 @6xl:grid-cols-6">
          <Tile id="current" t={t} wrapText label={t('dbg.kpi.current')} value={formatBytes(k.current_bytes)}
            sub={data.current_source === 'live' ? t('dbg.kpi.live') : data.current_source === 'snapshot' ? t('dbg.kpi.snapshot') : null} />
          <Tile id="daily" t={t} wrapText label={t('dbg.kpi.daily')}
            value={k.daily_growth_bytes != null ? `${formatSignedBytes(k.daily_growth_bytes)}${t('dbg.perDaySuffix')}` : '—'}
            sub={k.daily_growth_bytes != null ? est(k.daily_growth_estimate) : t('dbg.kpi.noForecast')} />
          <Tile id="growth30" t={t} wrapText label={t('dbg.kpi.growth30')} value={formatSignedBytes(k.growth_30d_bytes)}
            sub={k.growth_30d_bytes == null ? t('dbg.kpi.needDays') : null} />
          <Tile id="projection90" t={t} wrapText label={t('dbg.kpi.projection90')} value={formatBytes(k.projection_90d_bytes)}
            sub={k.projection_90d_bytes != null ? est(k.projection_90d_estimate) : t('dbg.kpi.noForecast')} />
          <Tile id="used" t={t} wrapText label={t('dbg.kpi.used')} value={usedValue} sub={usedSub}
            tone={capKnown && sizeKnown ? usedTone(k.used_pct) : 'neutral'} />
          <Tile id="full" t={t} wrapText label={t('dbg.kpi.full')} value={fullValue} sub={fullSub}
            tone={capKnown && !stale && k.days_until_full != null ? daysTone(k.days_until_full) : 'neutral'} />
        </div>
      </div>

      {state === 'collecting' && (
        <div data-slot="dbg-collecting" data-basis={data.forecast_basis}>
          <AlertBanner tone="info" className="mb-0" title={t('dbg.collectingTitle')}>
            {data.forecast_basis === 'cold_start'
              ? t('dbg.collectingCold', formatNumber(data.history_points), formatNumber(data.min_points))
              : t('dbg.collectingNone', formatNumber(data.history_points), formatNumber(data.min_points))}
          </AlertBanner>
        </div>
      )}
      {stale && (
        <div data-slot="dbg-stale" data-age={data.snapshot_age_days ?? undefined}>
          <AlertBanner tone="warning" className="mb-0" title={t('dbg.staleTitle', formatNumber(data.snapshot_age_days))}>
            {t('dbg.staleBody', formatNumber(data.stale_after_days))}
          </AlertBanner>
        </div>
      )}
      {data.hold_active && (
        <div data-slot="dbg-hold"><AlertBanner tone="warning" className="mb-0">{t('dbg.holdActive')}</AlertBanner></div>
      )}

      <Panel data-slot="dbg-capacity" title={t('dbg.cap.title')}
        right={data.can_edit_capacity && (
          <Button type="button" size="sm" variant="outline" data-slot="dbg-capacity-edit" onClick={() => setCapOpen(true)}
            className="pointer-coarse:h-10">
            <Pencil aria-hidden="true" />{t('dbg.cap.edit')}
          </Button>
        )}>
        <p data-slot="dbg-capacity-value" className="m-0 text-sm font-semibold text-foreground tabular-nums">
          {capKnown ? t('dbg.cap.value', formatNumber(data.capacity_gb)) : t('dbg.cap.unknown')}
        </p>
        <p className="m-0 text-xs text-muted-foreground">{t('dbg.cap.hint')}</p>
        {!data.can_edit_capacity && <p className="m-0 text-xs text-muted-foreground">{t('dbg.cap.globalOnly')}</p>}
      </Panel>

      <div className="grid min-w-0 grid-cols-1 gap-3 lg:grid-cols-2">
        <TotalSizeChart view={data} range={range} t={t} />
        <TopTablesChart view={data} t={t} />
      </div>

      <SubTitle>{t('dbg.findings.title')}</SubTitle>
      <DbGrowthFindings findings={data.findings} t={t} canApply={!!data.can_apply} canOpenRetention={canOpenRetention}
        onApplied={afterApply} />
      <div data-slot="dbg-vacuum-note"><AlertBanner tone="info" className="mb-0">{t('dbg.vacuumNote')}</AlertBanner></div>

      <SubTitle>{t('dbg.tbl.title')}</SubTitle>
      <DbGrowthTables tables={data.tables} t={t} />

      <DbGrowthAnalysis t={t} canAnalyze={!!data.can_analyze} canApply={!!data.can_apply} canOpenRetention={canOpenRetention}
        hasLast={!!data.last_analysis} onApplied={afterApply} />

      {capOpen && (
        <CapacityDialog t={t} current={data.capacity_gb} max={data.max_capacity_gb || 1_000_000}
          onClose={() => setCapOpen(false)}
          onSaved={() => { setCapOpen(false); toast.success(t('dbg.cap.saved')); load(true) }} />
      )}
    </div>
  )
}

/** Kapasite (GiB, 1024³ bayt) — GLOBAL yönetici; boş ya da 0 = bilinmiyor (bekçi denetimi NA olur). Sunucu alan hatası alanın altında. */
function CapacityDialog({ t, current, max, onClose, onSaved }) {
  const [value, setValue] = useState(current != null ? String(current) : '')
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)

  async function save() {
    const parsed = parseCapacity(value, max)
    if (parsed.error) { setError(t(`dbg.cap.err.${parsed.error}`, formatNumber(max))); return }
    setSaving(true); setError(null)
    try {
      const res = await api.admin.dbGrowth.saveCapacity(parsed.value)
      if (res?.success) onSaved()
      else setError(res?.error || t('settings.saveError'))
    } catch (e) {
      setError(e?.message || t('settings.saveError'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <ModalShell open onClose={onClose} size="sm" icon={HardDrive} busy={saving} title={t('dbg.cap.dialogTitle')}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={saving}>{t('dbg.apply.cancel')}</Button>
          <Button type="button" data-slot="dbg-capacity-save" onClick={save} disabled={saving} aria-busy={saving || undefined}>
            {saving ? t('dbg.apply.saving') : t('dbg.cap.save')}
          </Button>
        </>
      )}>
      <Field label={t('dbg.cap.field')} hint={t('dbg.cap.fieldHint')} error={error} name="capacity_gb">
        {({ id, describedBy, invalid }) => (
          <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} inputMode="numeric" value={value}
            data-slot="dbg-capacity-input" onChange={(e) => { setValue(e.target.value); setError(null) }}
            onKeyDown={(e) => { if (e.key === 'Enter') save() }} className="max-w-[12rem] tabular-nums pointer-coarse:h-10" />
        )}
      </Field>
    </ModalShell>
  )
}
