import { useState } from 'react'
import { SlidersHorizontal, X } from 'lucide-react'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import Field from '../../ui/Field.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle, SheetTrigger } from '@/components/shadcn/sheet'

/**
 * İzleme Değişiklikleri süzgeçleri — tür / olay / kullanıcı / takım. Aynı süzgeç tanımı iki yerleşimde çizilir:
 * masaüstü/tablet araç çubuğunda yan yana seçiciler (`FilterFields`), telefonda "Süzgeçler (n)" düğmesinin açtığı alt
 * Sheet'te etiketli, alt alta (`FilterSheet`). Etkin süzgeçler iki yerleşimde de X'li rozet olarak görünür
 * (`ActiveFilterChips`). Seçim anında uygulanır (Sheet'in "Sonuçları göster" düğmesi yalnız kapatır).
 *
 * Süzgeç tanımı: `{ key, label, ariaLabel, value, options: [{value,label}], onChange, searchThreshold }` — boş dize
 * "süzgeç yok" demektir (seçeneklerin ilki "Tümü").
 */

const labelOf = (f) => f.options.find(o => String(o.value) === String(f.value))?.label ?? String(f.value)

/** Seçiciler — `stacked`: etiketli, tam genişlik (Sheet); değilse satır içi, sabit genişlikli (araç çubuğu). */
export function FilterFields({ filters, stacked = false }) {
  if (stacked) {
    return filters.map(f => (
      <Field key={f.key} label={f.label}>
        {({ id }) => (
          <SearchableSelect id={id} value={f.value} onChange={f.onChange}
            options={f.options} searchThreshold={f.searchThreshold} />
        )}
      </Field>
    ))
  }
  return filters.map(f => (
    // Dar kapta 2 sütunlu ızgaranın hücresi; geniş kapta (`@container/chg`, MonitorChangesConsole) sabit genişlik.
    <div key={f.key} data-filter={f.key} className="min-w-0 @3xl/chg:w-44">
      <SearchableSelect value={f.value} onChange={f.onChange} options={f.options}
        searchThreshold={f.searchThreshold} ariaLabel={f.ariaLabel} />
    </div>
  ))
}

/** Telefon: "Süzgeçler (n)" düğmesi + alt Sheet (40 px hedefler, güvenli alan payı). */
export function FilterSheet({ t, filters, activeCount, onClear }) {
  const [open, setOpen] = useState(false)
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button type="button" variant="outline" data-filters-trigger="" className="h-10 min-w-0 flex-1">
          <SlidersHorizontal aria-hidden="true" /> {t('chg.filters')}
          {activeCount > 0 && <>
            <Badge variant="secondary" aria-hidden="true" className="rounded-sm px-1.5 tabular-nums">{activeCount}</Badge>
            <span className="sr-only">{t('chg.filtersActive', activeCount)}</span>
          </>}
        </Button>
      </SheetTrigger>
      <SheetContent side="bottom" showCloseButton={false} data-slot="chg-filter-sheet"
        className="max-h-[90dvh] gap-0 rounded-t-xl p-0">
        <SheetHeader className="flex-row items-start gap-3 border-b p-4">
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <SheetTitle>{t('chg.filters')}</SheetTitle>
            <SheetDescription>{t('chg.filtersDesc')}</SheetDescription>
          </div>
          <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-1 size-10 shrink-0 text-muted-foreground"
            aria-label={t('app.close')} onClick={() => setOpen(false)}>
            <X aria-hidden="true" />
          </Button>
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4">
          <FilterFields filters={filters} stacked />
        </div>
        <SheetFooter className="flex-row gap-2 border-t p-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <Button type="button" variant="outline" className="h-10 flex-1" disabled={activeCount === 0} onClick={onClear}>
            {t('chg.presetClear')}
          </Button>
          <Button type="button" className="h-10 flex-1" onClick={() => setOpen(false)}>{t('chg.showResults')}</Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

/**
 * Etkin süzgeç rozetleri (X ile tek tek kaldırılır) + "Süzgeçleri temizle" (arama ve zaman aralığı dâhil HEPSİ).
 * X düğmesi görsel olarak küçük; dokunma alanı görünmez `::after` ile 40 px'e genişler.
 */
export function ActiveFilterChips({ t, filters, showClearAll, onClearAll }) {
  const active = filters.filter(f => f.value)
  if (active.length === 0 && !showClearAll) return null
  return (
    <div data-slot="chg-active-filters" className="flex min-w-0 flex-wrap items-center gap-1.5">
      {active.map(f => {
        const value = labelOf(f)
        return (
          <Badge key={f.key} variant="outline" data-filter-chip={f.key}
            className="h-7 max-w-full gap-1 overflow-visible py-0 pr-0.5 pl-2.5 font-normal">
            <span className="min-w-0 truncate"><span className="text-muted-foreground">{f.label}:</span> <span className="font-medium">{value}</span></span>
            <Button type="button" variant="ghost" size="icon-xs"
              className="relative shrink-0 rounded-full text-muted-foreground after:absolute after:-inset-2 after:content-[''] hover:text-foreground"
              aria-label={t('chg.removeFilter', `${f.label}: ${value}`)} onClick={() => f.onChange('')}>
              <X aria-hidden="true" />
            </Button>
          </Badge>
        )
      })}
      {showClearAll && (
        <Button type="button" variant="ghost" size="sm" className="h-7 text-muted-foreground hover:text-destructive max-md:h-10"
          onClick={onClearAll}>
          <X aria-hidden="true" /> {t('chg.presetClear')}
        </Button>
      )}
    </div>
  )
}
