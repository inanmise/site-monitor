import { Fragment, useState, useMemo } from 'react'
import { Check, ChevronRight, Plus, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList, CommandSeparator } from '@/components/shadcn/command'
import { Popover } from '@/components/shadcn/popover'
import { useT } from '../../i18n/index.jsx'
import { PickerContent, PickerTrigger, usePickerOpen } from './PickerPopover.jsx'

/**
 * Aranabilir seçici. Seçeneklere opsiyonel `group` verilirse liste grup başlıklarıyla bölünür
 * (ör. "Kayıtlı script'ler" / "Şablonlar") — arama yine ETİKET üzerinde çalışır.
 *
 * <p>İç uygulama shadcn Combobox deseni (Popover + Command + Button; gerekçe PickerPopover.jsx);
 * dış API (prop'lar, `onChange(value)`, seçenek biçimi) DEĞİŞMEDİ. cmdk'nin kendi bulanık
 * süzgeci/sıralaması KAPALI (`shouldFilter={false}`): süzgeç eskisi gibi etikette "içerir" ve
 * sıra çağıranın verdiği sıra. Klavye: ok tuşları + Enter seçer, Escape yalnız listeyi kapatır.
 *
 * <p>`collapsibleGroups` ile grup başlıkları KATLANABİLİR dala dönüşür (başlık + adet, tıklayınca
 * açılır). OPT-IN olması bilinçli: bu bileşen 20'den fazla yerde kullanılıyor ve çoğunda grup
 * sayısı 2-3; onları katlamak gereksiz bir tık ekler. Uzun kataloglar için vardır — sentetik
 * izlemedeki script seçicisinde 100 yerleşik şablon (10 kategori × 10) tek düz liste hâlinde
 * dökülüyordu ve bir script'in hangi kategoriden geldiği HİÇ görünmüyordu.
 *
 * <p>Kurallar: seçili değerin dalı AÇIK başlar (kullanıcı mevcut seçimini görebilsin); arama
 * yazılınca TÜM dallar açılır (aksi halde eşleşen sonuç kapalı dalda saklı kalırdı); `group`
 * taşımayan seçenekler (ör. boş "Seçiniz" satırı) her zaman görünür.
 *
 * <p>Bir seçenek `groupOpen: true` taşırsa o dal AÇIK başlar. Karar ÇAĞIRANA bırakıldı, çünkü
 * "hangi dal küçük/önemli" bilgisi veriye özgü: script seçicisinde kullanıcının KENDİ script'leri
 * birkaç tanedir ve en sık seçilendir — onları katlamak en yaygın işe fazladan tık ekler; asıl
 * katlanması gereken 100 satırlık yerleşik katalogtur. Bileşene sihirli bir "küçükse aç" eşiği
 * koymak bu bilgiyi tahmine çevirirdi.
 *
 * <p>Seçim FARE BASIŞINDA olur (eski sözleşme — odak arama kutusundan kaymaz). cmdk ayrıca
 * `click`'te de seçer; kapanış animasyonu sürerken gelen o ikinci olay `openRef` korumasıyla
 * yutulur — yoksa `onChange` iki kez, `onCreate` iki kez çağrılırdı.
 */

/** cmdk öğe değeri: boş dize cmdk'de "seçim yok" demek — bu yüzden önekli ve dizeye çevrilmiş. */
const itemValue = (v) => `o:${String(v)}`

export default function SearchableSelect({
  value, onChange, options, placeholder, disabled = false, searchThreshold = 4,
  creatable = false, onCreate, onDelete, ariaLabel, collapsibleGroups = false
}) {
  const t = useT()
  const { open, openRef, setOpen } = usePickerOpen()
  const [query, setQuery] = useState('')

  const selected = options.find(o => String(o.value) === String(value))
  // creatable: arama kutusu her zaman açık (yeni değer yazabilmek için)
  const showSearch = creatable || options.length >= searchThreshold
  // String(): sayısal/boş label (örn. yıl) düşük eşikte filtre yoluna girince patlamasın
  const filtered = showSearch
    ? options.filter(o => String(o.label ?? '').toLowerCase().includes(query.toLowerCase()))
    : options

  function close() {
    setOpen(false)
    setQuery('')
  }

  function select(val) {
    // Liste zaten kapandıysa bu, mousedown seçiminin ardından gelen click'tir — ikinci kez seçme.
    if (!openRef.current) return
    onChange(val)
    close()
  }

  async function handleCreate(val) {
    const v = val.trim()
    if (!v || !openRef.current) return
    close()   // önce kapat: aynı basışın click'i ikinci bir oluşturma tetiklemesin
    if (onCreate) { try { await onCreate(v) } catch { /* parent toast eder */ } }
    onChange(v)
  }

  // Seçili değerin dalı açık başlasın; kullanıcı listeyi açtığında mevcut seçimi görmeli.
  const selectedGroup = selected?.group
  const [collapsedOverride, setCollapsedOverride] = useState({})
  const searching = collapsibleGroups && query.trim() !== ''
  /** Çağıranın "açık başlasın" dediği gruplar (seçeneklerdeki `groupOpen`). */
  const defaultOpenGroups = useMemo(() => {
    const set = new Set()
    for (const o of options) if (o.groupOpen && o.group) set.add(o.group)
    return set
  }, [options])
  const isExpanded = (g) => {
    if (!collapsibleGroups) return true
    if (searching) return true                       // arama: hiçbir sonuç kapalı dalda saklanmasın
    const o = collapsedOverride[g]
    return o !== undefined ? o : (g === selectedGroup || defaultOpenGroups.has(g))
  }
  const toggleGroup = (g) => setCollapsedOverride(p => ({ ...p, [g]: !isExpanded(g) }))

  /** Dal başlığındaki adet — filtrelenmiş listeden sayılır, aramada gerçek sonucu gösterir. */
  const groupCounts = useMemo(() => {
    const m = {}
    for (const o of filtered) if (o.group) m[o.group] = (m[o.group] || 0) + 1
    return m
  }, [filtered])

  /**
   * Ardışık aynı gruplu seçenekler tek blok: eskiden başlık "grup DEĞİŞTİĞİNDE" basılıyordu,
   * blok sınırı aynı kural. Aramada boşalan grubun bloğu (ve başlığı) kendiliğinden düşer.
   */
  const blocks = useMemo(() => {
    const out = []
    for (const o of filtered) {
      const g = o.group || undefined
      const last = out[out.length - 1]
      if (last && last.group === g) last.items.push(o)
      else out.push({ group: g, items: [o] })
    }
    return out
  }, [filtered])

  const isEmpty = value === '' || value === null || value === undefined
  // creatable serbest değer: options'ta yoksa bile değeri etiket olarak göster
  const triggerLabel = selected ? selected.label : (isEmpty ? (placeholder || t('ss.choose')) : String(value))
  const trimmedQuery = query.trim()
  const queryExists = options.some(o => String(o.label ?? '').toLowerCase() === trimmedQuery.toLowerCase())
  const showCreate = creatable && !!trimmedQuery && !queryExists

  // Mouse ile seçim basışta: odak arama kutusunda kalır, liste eski gibi anında kapanır.
  const pickOnPress = (fn) => (e) => {
    if (e.button !== 0) return
    e.preventDefault()
    fn()
  }

  function renderOption(opt) {
    const deletable = onDelete && opt.value !== '' && opt.value != null
    const isSelected = String(opt.value) === String(value)
    return (
      <CommandItem
        key={String(opt.value)}
        value={itemValue(opt.value)}
        title={opt.title || undefined}   /* isteğe bağlı açıklama tooltip'i (2026-09-22: platform seçicisi) */
        data-checked={isSelected ? 'true' : undefined}
        className={cn(
          'min-w-0',
          isSelected && 'font-medium',
          // Boş değerli satır ("Seçiniz", "Tümü"): gerçek bir seçim değil, soluk ve eğik.
          opt.value === '' && 'text-muted-foreground italic',
        )}
        onMouseDown={pickOnPress(() => select(opt.value))}
        onSelect={() => select(opt.value)}
      >
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="truncate">{opt.label}</span>
          {opt.hint && <span className="truncate text-xs font-normal text-muted-foreground not-italic">{opt.hint}</span>}
        </span>
        {deletable && (
          /* Klavye/dokunmatik kullanıcısı da silebilsin (Enter/Space) ve ad hangi seçeneğin
             silineceğini söylesin. Basış seçimi TETİKLEMEZ (stopPropagation); click de cmdk'nin
             öğe seçimine ulaşmaz — yoksa silinen seçenek aynı anda seçilirdi. */
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            className="size-5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
            title={t('ss.delete')}
            aria-label={t('ss.deleteOption', opt.label)}
            onKeyDown={(e) => {
              if (e.key !== 'Enter' && e.key !== ' ') return
              e.preventDefault(); e.stopPropagation(); onDelete(opt.value)
            }}
            onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); onDelete(opt.value) }}
            onClick={(e) => e.stopPropagation()}
          >
            <X aria-hidden="true" />
          </Button>
        )}
        <Check aria-hidden="true" className={cn('ml-auto text-primary', isSelected ? 'opacity-100' : 'opacity-0')} />
      </CommandItem>
    )
  }

  function renderGroupHeader(g) {
    const expanded = isExpanded(g)
    return (
      <Button
        type="button"
        variant="ghost"
        size="sm"
        aria-expanded={expanded}
        className="h-auto w-full justify-start gap-1.5 px-2 py-1.5 text-xs font-medium text-muted-foreground has-[>svg]:px-2"
        onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); toggleGroup(g) }}
        // Klavye (Enter/Space → detail=0 olan click): fare basışı zaten onMouseDown'da işlendi.
        onClick={(e) => { if (e.detail === 0) toggleGroup(g) }}
      >
        <ChevronRight
          aria-hidden="true"
          className={cn('size-3 transition-transform motion-reduce:transition-none', expanded && 'rotate-90')}
        />
        <span className="min-w-0 flex-1 truncate text-left">{g}</span>
        <Badge variant="secondary" className="tabular-nums">{groupCounts[g]}</Badge>
      </Button>
    )
  }

  function renderBlock(block, i) {
    const sep = i > 0 && block.group ? <CommandSeparator /> : null
    // Anahtar grup adını taşır: aramada bloklar kayınca React bir dalın durumunu komşusuna devretmesin.
    const key = `${i}:${block.group ?? ''}`
    if (!block.group) {
      return <CommandGroup key={key}>{block.items.map(renderOption)}</CommandGroup>
    }
    if (!collapsibleGroups) {
      return (
        <Fragment key={key}>
          {sep}
          <CommandGroup heading={block.group}>{block.items.map(renderOption)}</CommandGroup>
        </Fragment>
      )
    }
    // Katlanabilir dal: başlık her zaman çizilir (yoksa dal kaybolurdu), kapalı dalın seçenekleri çizilmez.
    return (
      <Fragment key={key}>
        {sep}
        <CommandGroup>
          {renderGroupHeader(block.group)}
          {isExpanded(block.group) && block.items.map(renderOption)}
        </CommandGroup>
      </Fragment>
    )
  }

  return (
    // `ss-wrap`: YALNIZ yerleşim kancası — ekranlardaki bağlam kuralları (araç çubuğunda genişlik,
    // süzgeç satırında asgari genişlik) bu sarmalayıcıyı boyutlandırıyor; görünüm shadcn'de.
    <div className="ss-wrap" data-slot="searchable-select">
      <Popover open={open} onOpenChange={(next) => { if (!next) close() }}>
        <PickerTrigger
          open={open}
          openRef={openRef}
          setOpen={setOpen}
          onOpen={() => setQuery('')}
          disabled={disabled}
          placeholderShown={isEmpty}
          ariaLabel={ariaLabel}
        >
          {triggerLabel}
        </PickerTrigger>
        <PickerContent commandProps={{ label: t('ss.search'), defaultValue: selected ? itemValue(selected.value) : undefined }}>
          {showSearch && (
            <CommandInput placeholder={t('ss.search')} value={query} onValueChange={setQuery} />
          )}
          <CommandList className="min-h-0 flex-1">
            {blocks.map(renderBlock)}
            {filtered.length === 0 && !showCreate && (
              <CommandEmpty className="py-3 text-center text-sm text-muted-foreground">{t('ss.noResult')}</CommandEmpty>
            )}
            {showCreate && (
              <CommandGroup className="border-t">
                <CommandItem
                  value="create:"
                  className="font-medium text-primary"
                  onMouseDown={pickOnPress(() => handleCreate(trimmedQuery))}
                  onSelect={() => handleCreate(trimmedQuery)}
                >
                  <Plus aria-hidden="true" className="text-primary" />
                  <span className="min-w-0 truncate">{t('ss.add')} “{trimmedQuery}”</span>
                </CommandItem>
              </CommandGroup>
            )}
          </CommandList>
        </PickerContent>
      </Popover>
    </div>
  )
}
