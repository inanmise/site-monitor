import { Calendar, Pencil, RotateCw } from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { dateLocale } from '../../i18n/dateLocale.js'
import { PRESETS } from './responseChartModel.js'

/**
 * Grafik araç çubuğu: aralık seçimi + (özel aralıkta) seçili aralık çipi + canlı göstergesi / son güncelleme +
 * yenile. Aralık seçimi telefonda (< 640 px) yerel seçim kutusu (`NativeSelect`, 40 px, 16 px yazı — iOS
 * yakınlaştırmaz), geniş ekranda bitişik `ui/SegmentedControl` (kendi kabında kayar, sayfa taşmaz). İkisi de
 * DOM'da; görünürlük CSS ile (RESPONSIVE.md: görünüm farkı CSS, davranış farkı hook).
 */
export default function ChartToolbar({ t, rangeValue, onPick, custom, showCustom, onEditCustom, live, updatedAt, busy, onRefresh }) {
  const options = [
    ...PRESETS.map((p) => ({ value: p.key, label: t(`chart.range${p.key}`) })),
    { value: 'custom', label: t('chart.custom'), icon: Calendar },
  ]
  const updated = updatedAt
    ? new Date(updatedAt).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' })
    : null
  return (
    <div data-slot="chart-toolbar" className="flex flex-wrap items-center gap-2">
      <div className="min-w-0 flex-1 sm:hidden [&>[data-slot=native-select-wrapper]]:w-full">
        <NativeSelect aria-label={t('chart.rangeLabel')} value={rangeValue} onChange={(e) => onPick(e.target.value)}
          className="h-10 text-base">
          {PRESETS.map((p) => <NativeSelectOption key={p.key} value={p.key}>{t(`chart.range${p.key}`)}</NativeSelectOption>)}
          <NativeSelectOption value="custom">{t('rtc.customRange')}</NativeSelectOption>
        </NativeSelect>
      </div>
      <div data-slot="chart-range-scroll"
        className="hidden max-w-full min-w-0 overflow-x-auto overscroll-x-contain rounded-lg [scrollbar-width:thin] sm:block">
        <SegmentedControl ariaLabel={t('chart.rangeLabel')} options={options} value={rangeValue} onChange={onPick}
          className="w-max flex-nowrap pointer-coarse:[&>[data-slot=toggle-group-item]]:h-10 pointer-coarse:[&>[data-slot=toggle-group-item]]:min-w-10" />
      </div>
      {custom && !showCustom && (
        <Button type="button" variant="outline" size="sm" onClick={onEditCustom} aria-expanded={false}
          className="order-last h-auto min-h-8 basis-full justify-start gap-1.5 py-1 text-xs whitespace-normal text-muted-foreground sm:order-none sm:basis-auto pointer-coarse:min-h-10">
          <Calendar aria-hidden="true" className="size-3.5" />
          <span className="tabular-nums">{formatDateSec(custom.from)} → {formatDateSec(custom.to)}</span>
          <Pencil aria-hidden="true" className="size-3.5" />
          <span className="sr-only">{t('chart.editRange')}</span>
        </Button>
      )}
      <div className="ml-auto flex shrink-0 items-center gap-2">
        {live && (
          <SimpleTooltip content={t('rtc.liveHint')}>
            <Badge variant="outline" data-slot="chart-live" className="gap-1.5 font-medium text-muted-foreground">
              <span aria-hidden="true" className="size-1.5 rounded-full bg-success motion-safe:animate-pulse" />
              {t('rtc.live')}
            </Badge>
          </SimpleTooltip>
        )}
        {updated && (
          <span data-slot="chart-updated" className="text-xs text-muted-foreground tabular-nums">
            <span className="max-sm:sr-only">{t('rtc.updatedLabel')} </span>{updated}
          </span>
        )}
        <SimpleTooltip content={t('rtc.refresh')}>
          <Button type="button" variant="outline" size="icon-sm" onClick={onRefresh} disabled={busy}
            aria-label={t('rtc.refresh')} aria-busy={busy || undefined} className="pointer-coarse:size-10">
            {busy ? <Spinner size={14} decorative /> : <RotateCw aria-hidden="true" className="size-4" />}
          </Button>
        </SimpleTooltip>
      </div>
    </div>
  )
}
