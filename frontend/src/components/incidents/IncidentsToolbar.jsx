import { useEffect, useRef, useState } from 'react'
import { Search, SlidersHorizontal, X, FilterX } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { InputGroup, InputGroupInput, InputGroupAddon } from '@/components/shadcn/input-group'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/shadcn/sheet'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { cn } from '@/lib/utils'
import { statusOf, activeFilters, LEVEL_OPTIONS, severityMeta } from './incidentsModel.js'

/** Seçicilerin ortak kabı: NativeSelect sarmalayıcısı `w-fit` — telefonda tam genişlik için `*:w-full`. */
const SEL = 'w-full *:w-full sm:w-auto'
/** Dokunmatikte 40 px (shadcn NativeSelect 36 px). */
const SEL_TOUCH = 'pointer-coarse:h-10'
/** Yazarken arama bu kadar duraklamadan sonra uygulanır (her tuşta istek yok); Enter / odak kaybı hemen uygular. */
export const SEARCH_DEBOUNCE_MS = 250

/**
 * Süzgeç araç çubuğu — masaüstünde tek sarılan satır; telefonda arama + "Süzgeçler" düğmesi (alt Sheet).
 * Etkin süzgeçler aşağıda çip olarak listelenir (× ile tek tek, "Temizle" ile hepsi). Kök neden çipleri
 * (canlı sayı, sunucudan) telefonda yatay kayan tek satır, geniş ekranda sarar.
 *
 * 2026-11-13: önem (tek seviye ya da "kritik ve yüksek"), takım (sunucunun takım seçenekleri — ADI ile; adı okunamayan
 * seçili takım "Takım (adı okunamadı)", ham kimlik asla) ve "Yalnız tekrarlayanlar" anahtarı SUNUCUDA uygulanır. Eski
 * sunucu bunları bilmezse sayfa yüklenen sayfaya uygular ve gövde notu söyler (`incov.pageFacetNote`). "Tüm nedenler"
 * çipinin sayısı kök neden süzgecinden BAĞIMSIZ toplamdır (tür sayılarının toplamı).
 *
 * <p>`busy`: yavaş yüklemede (sayfa ~180 ms geciktirir) süzgeç satırının sonunda duran küçük Spinner — sonuçlar yerinde
 * soluk kalır, satır yüksekliği değişmez. Arama yazarken 250 ms duraklamada uygulanır (Enter / odak kaybı hemen).
 */
export default function IncidentsToolbar({ filters, patch, reset, typeCounts, total, teams, phone, labelsFor, busy = null }) {
  const t = useT()
  const [sheetOpen, setSheetOpen] = useState(false)
  // Arama kutusu kontrollü: dış süzgeç (çip ×, Temizle, URL) değişince taslak da onu izler — ref gerekmez
  // (InputGroupInput forwardRef DEĞİL; ref verilse düşer). Uygulama Enter / odak kaybında (her tuşta istek yok).
  const [draft, setDraft] = useState(filters.q)
  // Kutunun UYGULADIĞI son değer: dışarıdan gelen değişim (çip ×, Temizle, URL) taslağı eşitler; kutunun kendi
  // (gecikmeli) uygulaması eşitlemez — yoksa yazarken sondaki boşluk silinir, imleç zıplardı.
  const appliedRef = useRef(filters.q)
  useEffect(() => {
    if (filters.q !== appliedRef.current) { appliedRef.current = filters.q; setDraft(filters.q) }
  }, [filters.q])
  // Yazarken gecikmeli uygulama — tek zamanlayıcı, son değer kazanır; Enter/odak kaybı bekleyeni iptal edip hemen uygular.
  const searchTimer = useRef(null)
  useEffect(() => () => clearTimeout(searchTimer.current), [])
  const commitSearch = (value) => {
    const v = value.trim()
    appliedRef.current = v
    patch({ q: v })   // sayfa aynı değeri yenilemez (patchFilters eşitlikte durum döndürür) — çift istek yok
  }
  const applySearch = () => { clearTimeout(searchTimer.current); commitSearch(draft) }
  const onDraft = (value) => {
    setDraft(value)
    clearTimeout(searchTimer.current)
    searchTimer.current = setTimeout(() => commitSearch(value), SEARCH_DEBOUNCE_MS)
  }
  const active = activeFilters(filters)
  const status = statusOf(filters.stat)
  const pills = Object.entries(typeCounts || {}).sort((a, b) => b[1] - a[1])
  // "Tüm nedenler": tür sayıları kök neden süzgecinden bağımsız sayılır → toplamları süzgeçsiz toplamdır (seçili türün
  // sayısını DEĞİL hepsini gösterir). Tür sayısı yoksa liste toplamı.
  const allCauses = pills.length ? pills.reduce((sum, [, c]) => sum + (Number(c) || 0), 0) : total
  const teamList = teams.some((tm) => tm.id === String(filters.team)) || !filters.team
    ? teams : [...teams, { id: String(filters.team), name: t('incov.chip.teamUnknown') }]
  const typeLabel = (type) => (t(`incov.type.${type}`) !== `incov.type.${type}` ? t(`incov.type.${type}`) : type)

  const statusSelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('incov.colStatus')} value={status} className={SEL_TOUCH}
        onChange={(e) => patch({ stat: e.target.value === 'ongoing' ? 'open' : e.target.value === 'resolved' ? 'resolved' : '' })}>
        <NativeSelectOption value="all">{t('incov.statusAll')}</NativeSelectOption>
        <NativeSelectOption value="ongoing">{t('incov.ongoing')}</NativeSelectOption>
        <NativeSelectOption value="resolved">{t('incov.resolved')}</NativeSelectOption>
      </NativeSelect>
    </div>
  )
  const severitySelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('incov.severity')} value={filters.level} className={SEL_TOUCH} onChange={(e) => patch({ level: e.target.value })}>
        <NativeSelectOption value="">{t('incov.severityAll')}</NativeSelectOption>
        {LEVEL_OPTIONS.map((lvl) => (
          <NativeSelectOption key={lvl} value={lvl}>{lvl.includes(',') ? t('incov.severityCritHigh') : t(severityMeta(lvl).key)}</NativeSelectOption>
        ))}
      </NativeSelect>
    </div>
  )
  const teamSelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('incov.colTeam')} value={filters.team} className={SEL_TOUCH} onChange={(e) => patch({ team: e.target.value })}>
        <NativeSelectOption value="">{t('incov.teamAll')}</NativeSelectOption>
        {teamList.map((tm) => <NativeSelectOption key={tm.id} value={tm.id}>{tm.name}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
  const dates = (
    <div className="flex flex-wrap items-center gap-2">
      <DateTimeField dateOnly clearable className={phone ? '' : 'dtf-inline'} placeholder={t('incov.since')}
        value={filters.since} onChange={(v) => patch({ since: v || '' })} />
      <DateTimeField dateOnly clearable className={phone ? '' : 'dtf-inline'} placeholder={t('incov.until')}
        value={filters.until} onChange={(v) => patch({ until: v || '' })} />
    </div>
  )
  const recurring = (
    <Label className="flex min-h-9 cursor-pointer items-center gap-2 text-sm font-medium pointer-coarse:min-h-10">
      <Switch data-slot="incidents-recurring" checked={filters.recurring === '1'}
        onCheckedChange={(on) => patch({ recurring: on ? '1' : '' })} />
      {t('incov.recurFilter')}
    </Label>
  )
  const search = (
    <InputGroup className="w-full sm:w-64">
      <InputGroupInput type="search" placeholder={t('incov.searchPlaceholder')} aria-label={t('incov.searchPlaceholder')}
        value={draft} onChange={(e) => onDraft(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); applySearch() } }}
        onBlur={() => applySearch()} />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
    </InputGroup>
  )
  const clearAll = () => { clearTimeout(searchTimer.current); setDraft(''); reset() }
  // Yükleme göstergesinin yeri HEP ayrılı (16 px): Spinner belirip kaybolurken satır sarmaz, içerik zıplamaz.
  const busySlot = (
    <span className="inline-flex size-4 shrink-0 items-center justify-center">
      {busy && <span data-slot="incidents-busy" className="inline-flex">{busy}</span>}
    </span>
  )

  return (
    <div data-slot="incidents-toolbar" className="flex min-w-0 flex-col gap-2">
      {phone ? (
        <div className="flex items-center gap-2">
          <div className="min-w-0 flex-1">{search}</div>
          {busySlot}
          <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
            <Button type="button" variant="outline" className="h-10 shrink-0 gap-1.5" onClick={() => setSheetOpen(true)}
              aria-label={t('incov.filters')} aria-expanded={sheetOpen}>
              <SlidersHorizontal aria-hidden="true" />{t('incov.filters')}
              {active.length > 0 && <Badge data-slot="filter-count" className="h-5 min-w-5 justify-center px-1.5 tabular-nums">{active.length}</Badge>}
            </Button>
            {/* z-[1001]: yüzen yardım düğmesinin (.help-fab 900) üstünde — "Uygula" örtülmesin */}
            <SheetContent overlayClassName="z-[1000]" side="bottom" showCloseButton={false} data-slot="incident-filters-sheet"
              className="z-[1001] max-h-[92dvh] gap-0 overflow-y-auto rounded-t-2xl pb-[env(safe-area-inset-bottom)]">
              <SheetHeader className="flex-row items-center justify-between">
                <div>
                  <SheetTitle className="flex items-center gap-2"><SlidersHorizontal aria-hidden="true" className="size-4" />{t('incov.filters')}</SheetTitle>
                  <SheetDescription>{t('incov.filtersHint')}</SheetDescription>
                </div>
                <SheetClose asChild>
                  <Button type="button" variant="ghost" size="icon" aria-label={t('app.close')}><X aria-hidden="true" /></Button>
                </SheetClose>
              </SheetHeader>
              <div className="flex flex-col gap-3 px-4">
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('incov.colStatus')}{statusSelect}</Label>
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('incov.severity')}{severitySelect}</Label>
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('incov.colTeam')}{teamSelect}</Label>
                <div className="flex flex-col gap-1 text-xs font-semibold text-muted-foreground">{t('incov.colStarted')}{dates}</div>
                {recurring}
              </div>
              <SheetFooter className="flex-row justify-between">
                <Button type="button" variant="ghost" onClick={clearAll} disabled={active.length === 0}><FilterX aria-hidden="true" />{t('incov.clearFilters')}</Button>
                <SheetClose asChild><Button type="button">{t('incov.applyFilters')}</Button></SheetClose>
              </SheetFooter>
            </SheetContent>
          </Sheet>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          {/* "Süzgeçleri temizle" burada TEKRARLANMAZ (etkin çip satırında var): süzgeç seçilince satır sarıp kartları
              aşağı itiyordu (1280 ölçümü, titreme bildirimi 2026-09-28). */}
          {statusSelect}{severitySelect}{teamSelect}{search}{dates}{recurring}
          {busySlot}
        </div>
      )}

      {/* Etkin süzgeç çipleri */}
      {active.length > 0 && (
        <div data-slot="active-filters" role="group" aria-label={t('incov.activeFilters')} className="flex flex-wrap items-center gap-1.5">
          {active.map((f) => {
            const label = labelsFor(f)
            return (
              <Badge key={f.key} variant="outline" data-filter={f.key}
                className="h-auto min-h-7 max-w-full gap-1 overflow-visible rounded-md bg-primary/5 py-0.5 pr-1 pl-2.5 font-medium whitespace-normal">
                <span className="min-w-0 [overflow-wrap:anywhere]">{label}</span>
                <Button type="button" variant="ghost" size="icon-xs"
                  className="relative size-5 shrink-0 rounded-full pointer-coarse:size-7 pointer-coarse:after:absolute pointer-coarse:after:-inset-1.5 pointer-coarse:after:content-['']"
                  aria-label={t('incov.removeFilter', label)}
                  onClick={() => patch(f.patch)}>
                  <X aria-hidden="true" className="size-3" />
                </Button>
              </Badge>
            )
          })}
          <Button type="button" variant="link" size="xs" className="h-7 px-1 text-muted-foreground pointer-coarse:h-10" onClick={clearAll}>{t('incov.clearFilters')}</Button>
        </div>
      )}

      {/* Kök neden çipleri (canlı sayı) — telefonda yatay kayar, geniş ekranda sarar */}
      {pills.length > 0 && (
        <div role="group" aria-label={t('incov.colRootCause')}
          className={cn('-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1 [scrollbar-width:thin] sm:mx-0 sm:flex-wrap sm:overflow-visible sm:px-0 sm:pb-0')}>
          {[['', t('incov.allCauses'), allCauses], ...pills.map(([type, count]) => [type, typeLabel(type), count])].map(([type, label, count]) => {
            const on = type ? filters.rootCause === type : !filters.rootCause
            return (
              <Button key={type || 'all'} type="button" variant={on ? 'default' : 'outline'} size="sm" aria-pressed={on}
                className="h-8 shrink-0 gap-1.5 rounded-full px-3 font-medium pointer-coarse:h-10"
                onClick={() => patch({ rootCause: type && filters.rootCause !== type ? type : '' })}>
                {label} <Badge variant={on ? 'secondary' : 'outline'} className="h-5 px-1.5 text-[0.85em] tabular-nums">{count}</Badge>
              </Button>
            )
          })}
        </div>
      )}
    </div>
  )
}
