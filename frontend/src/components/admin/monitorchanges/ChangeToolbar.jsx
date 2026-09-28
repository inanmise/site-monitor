import { Download, RefreshCw, Search, X } from 'lucide-react'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import DateTimeRangePicker from '../../ui/DateTimeRangePicker.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { ActiveFilterChips, FilterFields, FilterSheet } from './ChangeFilters.jsx'
import { RANGE_KEYS } from './changeModel.js'

/**
 * İzleme Değişiklikleri araç çubukları — iki katman, ikisi de saf sunum (durum konsolda):
 *
 * - `PeriodBar` (sayfanın EN ÜSTÜ): dönem — hazır pencereler (kendi kutusunda yatay kayar, telefonda satırı taşırmaz) +
 *   Özel tarih aralığı (Calendar + Popover). Dönem hem özet kartlarını hem listeyi belirler; bu yüzden kartların
 *   üstünde. Sağda CSV ve Yenile.
 * - `FilterBar` (listenin üstü): arama (300 ms gecikmeli sunucu araması; Esc temizler) + süzgeç seçicileri (telefonda
 *   "Süzgeçler (n)" alt Sheet'i) + etkin süzgeç rozetleri + sonuç sayısı.
 *
 * Yerleşim KAP genişliğine göre (`@container/chg`). Dokunma hedefleri telefonda 40 px.
 */
export function PeriodBar({
  t, rangeKey, onRange, pickerRange, onCustom, loading, busy, onRefresh, exporting, onExport, canExport,
}) {
  return (
    <div data-slot="chg-range" className="flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 items-center gap-2">
        <div className="min-w-0 flex-1">
          <div className="max-w-full min-w-0 self-start overflow-x-auto overscroll-x-contain rounded-lg [scrollbar-width:thin]">
            <SegmentedControl value={rangeKey} onChange={onRange}
              ariaLabel={t('chg.rangeFilter')} className="w-max flex-nowrap [&>[data-slot=toggle-group-item]]:max-md:h-10 [&>[data-slot=toggle-group-item]]:max-md:min-w-10"
              options={RANGE_KEYS.map(k => ({
                value: k,
                label: k === 'all' ? t('chg.rangeAll')
                  : k === 'today' ? t('chg.rangeToday')
                    : k === 'custom' ? t('chg.rangeCustom')
                      : t('chg.rangeDaysShort', k),
                title: k === 'all' ? t('chg.rangeAll')
                  : k === 'today' ? t('chg.rangeToday')
                    : k === 'custom' ? t('chg.rangeCustomHint')
                      : t('chg.rangeDays', k),
              }))} />
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <SimpleTooltip content={t('chg.exportHint')}>
            <Button type="button" variant="outline" data-action="chg-export" aria-busy={exporting || undefined}
              aria-label={t('chg.exportCsv')} disabled={!canExport || exporting}
              className="size-10 shrink-0 gap-1.5 @3xl/chg:h-9 @3xl/chg:w-auto @3xl/chg:px-3" onClick={onExport}>
              {exporting ? <Spinner decorative size={16} /> : <Download aria-hidden="true" />}
              <span className="hidden @3xl/chg:inline">CSV</span>
            </Button>
          </SimpleTooltip>
          <SimpleTooltip content={t('app.refresh')}>
            <Button type="button" variant="outline" size="icon" aria-label={t('app.refresh')} aria-busy={loading || undefined}
              className="size-10 shrink-0 @3xl/chg:size-9" onClick={onRefresh}>
              <RefreshCw aria-hidden="true" className={busy ? 'animate-spin motion-reduce:animate-none' : undefined} />
            </Button>
          </SimpleTooltip>
        </div>
      </div>
      {pickerRange && <DateTimeRangePicker from={pickerRange.from} to={pickerRange.to} onApply={onCustom} />}
    </div>
  )
}

export function FilterBar({ t, isMobile, q, onQ, filters, sheetActive, onClearSheet, chips, onClearAll, status }) {
  return (
    <div data-slot="chg-toolbar" className="flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <InputGroup className="h-10 w-full min-w-0 @3xl/chg:h-9 @3xl/chg:w-72 @3xl/chg:flex-none">
          <InputGroupInput type="search" value={q} aria-label={t('chg.searchPlaceholder')} placeholder={t('chg.searchPlaceholder')}
            className="h-full [&::-webkit-search-cancel-button]:hidden"
            onChange={(e) => onQ(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Escape' && q) { e.preventDefault(); e.stopPropagation(); onQ('') } }} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          {q && (
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-xs" className="size-8 sm:size-6" onClick={() => onQ('')}
                aria-label={t('chg.clearSearch')} title={t('chg.clearSearch')}>
                <X aria-hidden="true" />
              </InputGroupButton>
            </InputGroupAddon>
          )}
        </InputGroup>
        {isMobile ? (
          <FilterSheet t={t} filters={filters} activeCount={sheetActive} onClear={onClearSheet} />
        ) : (
          <div data-slot="chg-filters"
            className="grid w-full min-w-0 grid-cols-2 gap-2 @3xl/chg:flex @3xl/chg:w-auto @3xl/chg:flex-wrap @3xl/chg:items-center">
            <FilterFields filters={filters} />
          </div>
        )}
        {/* Geniş kapta sonuç sayısı süzgeç satırının sağında (ayrı bir satır harcamasın); dar kapta rozet satırında */}
        {status && <span className="ml-auto hidden text-xs text-muted-foreground @3xl/chg:inline-flex">{status}</span>}
      </div>
      <ActiveFilterChips t={t} chips={chips} onClearAll={onClearAll} status={status} statusClassName="@3xl/chg:hidden" />
    </div>
  )
}
