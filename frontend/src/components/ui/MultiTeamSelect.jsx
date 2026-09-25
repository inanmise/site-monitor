import { useRef, useState } from 'react'
import { Checkbox } from '@/components/shadcn/checkbox'
import { CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/shadcn/command'
import { Popover } from '@/components/shadcn/popover'
import { useT } from '../../i18n/index.jsx'
import { PickerContent, PickerTrigger, usePickerOpen } from './PickerPopover.jsx'

/**
 * Çoklu seçim açılır listesi (takımlar için). `value` = id dizisi, `onChange(ids)`.
 * SearchableSelect ile aynı shadcn Combobox deseni (Popover + Command, PickerPopover.jsx); farkları:
 * shadcn Checkbox'lı çoklu seçim, seçimde KAPANMAZ, tetikleyicide seçili etiketler virgülle gösterilir.
 */
export default function MultiTeamSelect({
  value = [], onChange, options = [], placeholder, disabled = false, searchThreshold = 4,
  // Ad yolları SearchableSelect ile aynı (role="combobox" içerikten ad almaz — PickerTrigger).
  ariaLabel, ariaLabelledBy, id,
}) {
  const t = useT()
  const { open, openRef, setOpen } = usePickerOpen()
  const [query, setQuery] = useState('')
  // Liste seçimde KAPANMADIĞI için SearchableSelect'in "kapandıysa yut" koruması burada işlemez:
  // basışta çevrilen seçeneği, aynı basışın ardından cmdk'nin click → onSelect'i geri çevirirdi
  // (net değişiklik sıfır). Basılan seçenek hatırlanır, onun click'i yutulur; klavye (Enter)
  // öncesinde keydown hatırlananı temizler, böylece klavye seçimi her zaman işler.
  const pressedRef = useRef(null)

  const selectedIds = Array.isArray(value) ? value : []
  const isSel = (v) => selectedIds.some(id => String(id) === String(v))

  const showSearch = options.length >= searchThreshold
  const filtered = showSearch
    ? options.filter(o => String(o.label ?? '').toLowerCase().includes(query.toLowerCase()))
    : options

  function toggle(v) {
    const next = isSel(v)
      ? selectedIds.filter(id => String(id) !== String(v))
      : [...selectedIds, v]
    onChange(next)
  }

  const selectedLabels = options.filter(o => isSel(o.value)).map(o => o.label)
  const isEmpty = selectedLabels.length === 0
  const triggerLabel = isEmpty ? (placeholder || t('ss.choose')) : selectedLabels.join(', ')

  return (
    <div className="ss-wrap" data-slot="multi-team-select">
      <Popover open={open} onOpenChange={(next) => { if (!next) { setOpen(false); setQuery('') } }}>
        <PickerTrigger
          open={open}
          openRef={openRef}
          setOpen={setOpen}
          onOpen={() => setQuery('')}
          disabled={disabled}
          placeholderShown={isEmpty}
          ariaLabel={ariaLabel}
          ariaLabelledBy={ariaLabelledBy}
          id={id}
        >
          {triggerLabel}
        </PickerTrigger>
        <PickerContent commandProps={{ label: t('ss.search'), onKeyDown: () => { pressedRef.current = null } }}>
          {showSearch && (
            <CommandInput placeholder={t('ss.search')} value={query} onValueChange={setQuery} />
          )}
          <CommandList className="min-h-0 flex-1" aria-multiselectable="true">
            {filtered.length > 0 && (
              <CommandGroup>
                {filtered.map(opt => {
                  const checked = isSel(opt.value)
                  return (
                    <CommandItem
                      key={String(opt.value)}
                      value={`o:${String(opt.value)}`}
                      // Seçili durum DUYURULUR: kutu aria-hidden (satırın kendisi seçenek) ve cmdk'nin
                      // aria-selected'ı klavye VURGUSU demek, seçim değil. Çoklu listbox'ta seçim
                      // aria-checked ile söylenir — kardeş FacetedFilter ile aynı (2026-09-25, R17).
                      aria-checked={checked}
                      data-checked={checked ? 'true' : undefined}
                      // Basışta çevir (odak arama kutusunda kalır); klavyede Enter → cmdk onSelect.
                      onMouseDown={(e) => {
                        if (e.button !== 0) return
                        e.preventDefault()
                        pressedRef.current = String(opt.value)
                        toggle(opt.value)
                      }}
                      onSelect={() => {
                        if (pressedRef.current === String(opt.value)) { pressedRef.current = null; return }
                        toggle(opt.value)
                      }}
                    >
                      {/* Görsel işaret: satırın kendisi seçenek; kutu ayrıca odak/tık almaz. */}
                      <Checkbox checked={checked} tabIndex={-1} aria-hidden="true" className="pointer-events-none" />
                      <span className="min-w-0 flex-1 truncate">{opt.label}</span>
                    </CommandItem>
                  )
                })}
              </CommandGroup>
            )}
            {filtered.length === 0 && (
              <CommandEmpty className="py-3 text-center text-sm text-muted-foreground">{t('ss.noResult')}</CommandEmpty>
            )}
          </CommandList>
        </PickerContent>
      </Popover>
    </div>
  )
}
