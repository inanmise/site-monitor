import { useMemo, useState } from 'react'
import { Check, PhoneOff, UserPlus, Crown } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Popover } from '@/components/shadcn/popover'
import { Badge } from '@/components/shadcn/badge'
import { CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/shadcn/command'
import { cn } from '@/lib/utils'
import { PickerContent, PickerTrigger, usePickerOpen } from '../../ui/PickerPopover.jsx'
import { groupContacts } from './nocCallModel.js'

/** cmdk öğe değeri boş olamaz → önekli (SearchableSelect ile aynı). */
const OTHER = 'noc:other'
const itemValue = (id) => `noc:${id}`

/** Arama kutusu bu kadar ve daha fazla kişide görünür (kısa listede fazladan dokunuş olmasın). */
const SEARCH_AT = 6

/**
 * 7/24 arama kaydı KİŞİ SEÇİCİSİ — shadcn Combobox deseni (Popover + Command + Button, `ui/PickerPopover`): gruplar
 * "Arama listesi" (sıralı, numaralı), "Takım Müdürü", "Diğer üyeler"; AD'de telefonu olmayan kişide uyarı simgesi
 * (metinle — renk tek başına anlam taşımaz); en altta "Başka biri…" serbest ad girişine geçer.
 *
 * Değer: `{ kind: 'user', userId, name }` | `{ kind: 'other', name }` | null. Seçim fare BASIŞINDA (proje sözleşmesi —
 * odak arama kutusundan kaymaz); klavye ok + Enter. Liste body'ye portal'lanır ve `--z-menu` katmanında (Sheet ve
 * pencerelerin üstünde). Telefon numarası HİÇ gösterilmez — yalnız `has_phone`.
 */
export default function NocPersonPicker({ id, contacts, loading = false, value, onChange, disabled, invalid, describedBy }) {
  const t = useT()
  const { open, openRef, setOpen } = usePickerOpen()
  const [query, setQuery] = useState('')
  const groups = useMemo(() => groupContacts(contacts), [contacts])
  const all = useMemo(() => [...groups.callList, ...groups.manager, ...groups.members], [groups])
  const showSearch = all.length >= SEARCH_AT
  const q = query.trim().toLocaleLowerCase()
  const match = (c) => !q || `${c.display_name ?? ''} ${c.title ?? ''}`.toLocaleLowerCase().includes(q)

  const close = () => { setOpen(false); setQuery('') }
  const pick = (next) => {
    if (!openRef.current) return   // basışın ardından gelen click ikinci kez seçmesin
    onChange(next)
    close()
  }
  const pickOnPress = (fn) => (e) => {
    if (e.button !== 0) return
    e.preventDefault()
    fn()
  }

  const selectedUser = value?.kind === 'user' ? all.find((c) => String(c.user_id) === String(value.userId)) : null
  const label = value?.kind === 'other'
    ? t('nocCall.person.other')
    : value?.kind === 'user' ? (selectedUser?.display_name || value.name || t('nocCall.person.choose'))
      : (loading ? t('nocCall.person.loading') : t('nocCall.person.choose'))

  const renderPerson = (c, order) => {
    const selected = value?.kind === 'user' && String(value.userId) === String(c.user_id)
    return (
      <CommandItem key={String(c.user_id)} value={itemValue(c.user_id)} data-checked={selected ? 'true' : undefined}
        data-source={c.source} className="min-h-10 min-w-0 gap-2"
        onMouseDown={pickOnPress(() => pick({ kind: 'user', userId: c.user_id, name: c.display_name }))}
        onSelect={() => pick({ kind: 'user', userId: c.user_id, name: c.display_name })}>
        {order != null && (
          <Badge variant="secondary" className="size-5 shrink-0 justify-center rounded-full p-0 text-[11px] tabular-nums">{order}</Badge>
        )}
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="flex min-w-0 items-center gap-1.5">
            <span className="truncate font-medium">{c.display_name}</span>
            {c.is_manager && c.source !== 'MANAGER' && (
              <Crown aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
            )}
          </span>
          {c.title && <span className="truncate text-xs text-muted-foreground">{c.title}</span>}
        </span>
        {c.has_phone === false && (
          <span data-slot="no-phone" className="inline-flex shrink-0 items-center gap-1 text-[11px] font-medium text-amber-700 dark:text-amber-300">
            <PhoneOff aria-hidden="true" className="size-3.5" />{t('nocCall.person.noPhone')}
          </span>
        )}
        <Check aria-hidden="true" className={cn('ml-1 size-4 shrink-0 text-primary', selected ? 'opacity-100' : 'opacity-0')} />
      </CommandItem>
    )
  }

  const callList = groups.callList.filter(match)
  const manager = groups.manager.filter(match)
  const members = groups.members.filter(match)
  const nothing = callList.length + manager.length + members.length === 0

  return (
    <div data-slot="noc-person-picker" className="min-w-0">
      <Popover open={open} onOpenChange={(next) => { if (!next) close() }}>
        <PickerTrigger open={open} openRef={openRef} setOpen={setOpen} onOpen={() => setQuery('')} disabled={disabled}
          placeholderShown={!value} id={id} ariaDescribedBy={describedBy} ariaInvalid={invalid}>
          <span className="flex min-w-0 items-center gap-2">
            {value?.kind === 'other' && <UserPlus aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
            <span className="truncate">{label}</span>
            {selectedUser?.has_phone === false && <PhoneOff aria-hidden="true" className="size-3.5 shrink-0 text-amber-700 dark:text-amber-300" />}
          </span>
        </PickerTrigger>
        <PickerContent commandProps={{ label: t('nocCall.person.label'), defaultValue: value?.kind === 'user' ? itemValue(value.userId) : undefined }}
          className="max-w-[min(420px,calc(100vw-2rem))]">
          {showSearch && <CommandInput placeholder={t('nocCall.person.search')} value={query} onValueChange={setQuery} />}
          {/* Liste boyu: 300 px yerine ekranda kalan alan (en çok 420 px) — tipik bir takım listesi kaydırmadan sığar */}
          <CommandList className="max-h-[min(420px,calc(var(--radix-popover-content-available-height)-3rem))] min-h-0 flex-1">
            {callList.length > 0 && (
              <CommandGroup heading={t('nocCall.person.groupCallList')}>
                {callList.map((c) => renderPerson(c, groups.callList.indexOf(c) + 1))}
              </CommandGroup>
            )}
            {manager.length > 0 && (
              <CommandGroup heading={t('nocCall.person.groupManager')}>{manager.map((c) => renderPerson(c, null))}</CommandGroup>
            )}
            {members.length > 0 && (
              <CommandGroup heading={t('nocCall.person.groupMembers')}>{members.map((c) => renderPerson(c, null))}</CommandGroup>
            )}
            {/* Düz <p>, CommandEmpty DEĞİL: süzgeç bizde (shouldFilter=false) ve cmdk yapışık "Başka biri…" öğesini de
                sayar → CommandEmpty hiç çizilmezdi (boş liste sessiz kalıyordu). */}
            {nothing && (
              <p role="status" data-slot="noc-person-empty" className="px-3 py-3 text-center text-sm text-muted-foreground">
                {all.length === 0 ? (loading ? t('nocCall.person.loading') : t('nocCall.person.none')) : t('ss.noResult')}
              </p>
            )}
            {/* "Başka biri…" listenin ALTINA YAPIŞIK: uzun arama listesinde kaydırmadan görünür (kaçış yolu) */}
            <CommandGroup className="sticky bottom-0 z-10 border-t bg-popover">
              <CommandItem value={OTHER} data-checked={value?.kind === 'other' ? 'true' : undefined} className="min-h-10 gap-2"
                onMouseDown={pickOnPress(() => pick({ kind: 'other', name: value?.kind === 'other' ? value.name : '' }))}
                onSelect={() => pick({ kind: 'other', name: value?.kind === 'other' ? value.name : '' })}>
                <UserPlus aria-hidden="true" className="size-4 text-muted-foreground" />
                <span className="flex-1">{t('nocCall.person.other')}</span>
                <Check aria-hidden="true" className={cn('size-4 text-primary', value?.kind === 'other' ? 'opacity-100' : 'opacity-0')} />
              </CommandItem>
            </CommandGroup>
          </CommandList>
        </PickerContent>
      </Popover>
    </div>
  )
}
