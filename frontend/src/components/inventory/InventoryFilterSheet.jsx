import { SlidersHorizontal } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import Field from '../ui/Field.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'
import { EMPTY_FILTERS, hasActiveFilter } from './inventoryModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Toggle } from '@/components/shadcn/toggle'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'

/**
 * Süzgeç paneli (2026-09-27): TÜM süzgeçler tek yan panelde — telefonda süzgeçlerin tek yeri (araç çubuğundaki fasetler
 * md altında gizli), geniş ekranda "Diğer süzgeçler" (sorumlular, alan adı bitişi, bildirim grubu, UG takımı, vekil,
 * bayraklar, sıralama). Seçim ANINDA uygulanır (state sahibi InventoryManager; adres çubuğu i_* paramları);
 * altlıkta Temizle + Kapat. Çizim shadcn: Sheet (sağdan; telefonda tam genişlik), ui/Field + ui/SearchableSelect, Toggle.
 * Test kancası: panel `data-slot="inv-filter-sheet"`.
 */
export default function InventoryFilterSheet({
  open, onOpenChange, filters, onFilters, teams = [], groupNames = [], notifGroups = [], platformCodes = [], platformNames = {}, tagNames = [],
  sort, onSort, activeCount = 0,
}) {
  const t = useT()
  const set = (patch) => onFilters({ ...filters, ...patch })
  const toggleFlag = (k) => set({ flags: filters.flags.includes(k) ? filters.flags.filter((x) => x !== k) : [...filters.flags, k] })
  const any = { value: '', label: t('inv.filterAny') }
  const teamOpts = [any, ...teams.map((tm) => ({ value: String(tm.id), label: tm.name }))]
  // Field render-prop'u: id etiketi seçiciye bağlar (rowAccessibleNames — seçici adsız kalmaz)
  const sel = (key, options) => function FilterSelect({ id }) {
    return <SearchableSelect id={id} value={filters[key] || ''} onChange={(v) => set({ [key]: v })} options={[any, ...options]} searchThreshold={6} />
  }
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" data-slot="inv-filter-sheet" className="flex w-full flex-col gap-0 p-0 sm:max-w-md">
        <SheetHeader className="border-b pr-12">
          <SheetTitle className="flex items-center gap-2">
            <SlidersHorizontal aria-hidden="true" className="size-4 text-primary" />
            {t('inv.filters')}
            {activeCount > 0 && <Badge variant="secondary" className="tabular-nums">{activeCount}</Badge>}
          </SheetTitle>
          <SheetDescription>{t('inv.filtersDesc')}</SheetDescription>
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto p-4">
          <Field label={t('inv.filterTeam')} className="mb-3">{({ id }) => (
            <SearchableSelect id={id} value={filters.team} onChange={(v) => set({ team: v })} options={teamOpts} searchThreshold={4} />
          )}</Field>
          <Field label={t('inv.filterTier')} className="mb-3">{sel('tier', [
            { value: '1', label: 'T1' }, { value: '2', label: 'T2' }, { value: '3', label: 'T3' }, { value: '4', label: 'T4' }, { value: 'none', label: t('inv.tierNone') },
          ])}</Field>
          <Field label={t('inv.filterCert')} className="mb-3">{sel('cert', [
            { value: 'problem', label: t('inv.certProblem') }, { value: 'error', label: t('inv.certError') }, { value: 'critical', label: t('inv.certCritical') },
            { value: 'warning', label: t('inv.certWarning') }, { value: 'valid', label: t('inv.certValid') }, { value: 'never', label: t('inv.certNever') },
          ])}</Field>
          <Field label={t('inv.facetPlatform')} className="mb-3">{sel('platform', [
            { value: 'none', label: t('inv.filterNone') }, ...platformCodes.map((c) => ({ value: c, label: platformNames[c] || c })),
          ])}</Field>
          <Field label={t('inv.filterGroup')} className="mb-3">{sel('group', [
            { value: 'none', label: t('inv.filterNone') }, ...groupNames.map((g) => ({ value: g, label: g })),
          ])}</Field>
          <Field label={t('inv.facetTag')} className="mb-3">{sel('tag', tagNames.map((x) => ({ value: x, label: x })))}</Field>
          <Field label={t('inv.filterContacts')} className="mb-3">{sel('contacts', [
            { value: 'none', label: t('inv.contactsNone') }, { value: 'partial', label: t('inv.contactsPartial') }, { value: 'full', label: t('inv.contactsFull') },
          ])}</Field>
          <Field label={t('inv.filterDomainExpiry')} className="mb-3">{sel('domainExp', [
            { value: '30', label: t('inv.domainExpIn', 30) }, { value: '60', label: t('inv.domainExpIn', 60) }, { value: '90', label: t('inv.domainExpIn', 90) }, { value: 'unknown', label: t('inv.domainExpUnknown') },
          ])}</Field>
          <Field label={t('inv.filterNotifGroup')} className="mb-3">{sel('notifGroup', [
            { value: 'none', label: t('inv.filterTeamDefault') }, ...notifGroups.map((g) => ({ value: String(g.id), label: g.name })),
          ])}</Field>
          <Field label={t('inv.filterUgTeam')} className="mb-3">{({ id }) => (
            <SearchableSelect id={id} value={filters.ugTeam} onChange={(v) => set({ ugTeam: v })} options={teamOpts} searchThreshold={4} />
          )}</Field>
          {/* Vekil süzgeci (2026-09-22): "vekil üzerinden kontrol edilenler" hızlı erişim için ayrı seçici */}
          <Field label={t('inv.filterProxy')} className="mb-3">{sel('proxy', [
            { value: 'on', label: t('inv.filterProxyOn') }, { value: 'off', label: t('inv.filterProxyOff') },
          ])}</Field>
          <div role="group" aria-label={t('inv.filterFlags')} className="mb-4">
            <span className="mb-1.5 block text-sm font-semibold">{t('inv.filterFlags')}</span>
            <div className="flex flex-wrap gap-1.5">
              {INVENTORY_FLAGS.filter(({ key }) => key !== 'use_proxy').map(({ key, labelKey }) => (
                <Toggle key={key} variant="outline" size="sm" pressed={filters.flags.includes(key)} onPressedChange={() => toggleFlag(key)}
                  className="h-9 rounded-full px-3 text-[.9em] font-normal text-foreground data-[state=on]:border-primary data-[state=on]:bg-primary data-[state=on]:text-primary-foreground">
                  {t(labelKey)}
                </Toggle>
              ))}
            </div>
          </div>
          <Field label={t('inv.sort')} className="mb-1">{({ id }) => (
            <SearchableSelect id={id} value={sort} onChange={onSort} options={[
              { value: 'domain|asc', label: t('inv.sortDomain') }, { value: 'cert|asc', label: t('inv.sortCert') }, { value: 'days|asc', label: t('inv.sortDays') },
              { value: 'team|asc', label: t('inv.sortTeam') }, { value: 'tier|asc', label: t('inv.sortTier') }, { value: 'updated|desc', label: t('inv.sortUpdated') },
            ]} />
          )}</Field>
        </div>
        {/* pe-16: uygulamanın sabit Yardım düğmesi sağ alt köşede — "Kapat" onun altında kalmasın */}
        <SheetFooter className="flex-row gap-2 border-t pe-16 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <Button type="button" variant="outline" className="h-10 flex-1" disabled={!hasActiveFilter(filters)} onClick={() => onFilters({ ...EMPTY_FILTERS })}>
            {t('inv.clearAll')}
          </Button>
          <SheetClose asChild>
            <Button type="button" className="h-10 flex-1">{t('app.close')}</Button>
          </SheetClose>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
