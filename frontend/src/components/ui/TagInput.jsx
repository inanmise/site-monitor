import { useState, useEffect, useMemo, useRef } from 'react'
import { X } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Command, CommandGroup, CommandItem, CommandList } from '@/components/shadcn/command'
import { Input } from '@/components/shadcn/input'
import { Popover, PopoverAnchor, PopoverContent } from '@/components/shadcn/popover'
import { useT } from '../../i18n/index.jsx'

const randomHue = () => Math.floor(Math.random() * 360)
/** Deterministik ton — henüz renk atanmamış (düzenlemede yüklenen) etiketler için stabil fallback. */
const tagHue = (s) => {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360
  return h
}

/**
 * Paylaşımlı etiket chip input — text yazıp Enter (veya virgül) → chip. Değer CSV string
 * ("a, b, c") olarak saklanır; onChange(nextCsv). placeholder field içi ipucudur.
 * IncidentHistoryPage'teki TagInput'tan lift edildi. İç uygulama shadcn: giriş Input, chip'ler
 * Badge, öneri listesi Popover + Command (SearchableSelect ile aynı aile).
 *
 * Giriş kutusu shadcn Input — dış kapsayıcıya güvenmez. Eskiden sınıfsızdı ve yalnız
 * `.form-grid label input` kuralının kapsamında doğru görünüyordu; ilk kez bir `.form-grid`
 * DIŞINDA (Bildirim Grupları modalı) kullanılınca tarayıcı varsayılanı olarak çizildi.
 *
 * Bekleyen metin bir ÖNİZLEME chip'i olarak çizilir (`data-pending`). Bu kozmetik değil,
 * bir tıklama hatasının çözümü: `onBlur` ile işleme alma chip satırını O ANDA büyütüyordu.
 * Kullanıcı değeri yazıp ALTTAKİ bir denetime (örn. "varsayılan yap" kutusu) bastığında sıra
 * şuydu — mousedown → blur → chip eklenir → altındaki her şey bir satır AŞAĞI kayar → mouseup
 * artık başka bir öğenin üstündedir → tarayıcı `click`'i ortak ataya verir ve kutu HİÇ
 * işaretlenmez; kullanıcı ikinci kez basmak zorunda kalırdı. Önizleme yeri baştan ayırdığı için
 * işleme alma anında yükseklik DEĞİŞMEZ ve tıklama hedefinde kalır.
 */
/**
 * `suggestions` (2026-09-22, kullanıcı isteği): takımın kullanımdaki etiketleri — kutuya odaklanınca / yazınca
 * süzülmüş açılır liste; seçilen chip olur. Seçilmiş olanlar listelenmez; eşleşme yoksa liste kapanır ve
 * Enter yine YENİ etiket ekler (eski davranış korunur). Ok tuşları + Enter ile seçim; Escape kapatır.
 * Öğeler string ya da { name, count } olabilir (count sağda soluk sayı).
 *
 * Liste body'ye PORTAL (Radix Popover, `--z-menu`): modalda kırpılmaz; ModalShell içinde Escape
 * önce listeyi kapatır, pencereyi değil. Odak HEP giriş kutusunda kalır (liste açılışta odak
 * almaz) — vurgu `hi` durumunda, cmdk'ye denetimli `value` olarak verilir.
 */
export default function TagInput({ label, value, onChange, disabled, placeholder, suggestions = null, suggestLabel = null }) {
  const t = useT()
  const [text, setText] = useState('')
  const [hues, setHues] = useState({})
  const tags = (value || '').split(',').map(s => s.trim()).filter(Boolean)
  // Görünen ama rengi olmayan etiketlere rastgele ton ata, o oturumda stabil kalsın.
  useEffect(() => {
    setHues(prev => {
      let changed = false
      const next = { ...prev }
      for (const tag of tags) if (next[tag] == null) { next[tag] = randomHue(); changed = true }
      return changed ? next : prev
    })
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps
  const add = () => {
    const v = text.trim()
    if (v && !tags.some(x => x.toLowerCase() === v.toLowerCase())) {
      setHues(prev => ({ ...prev, [v]: randomHue() }))
      onChange([...tags, v].join(', '))
    }
    setText('')
  }
  const remove = (tag) => onChange(tags.filter(x => x !== tag).join(', '))
  const pending = text.trim()
  // ── Öneri listesi ─────────────────────────────────────────────────────────────
  const [open, setOpen] = useState(false)
  const [hi, setHi] = useState(0)
  const anchorRef = useRef(null)
  const pool = useMemo(() => (Array.isArray(suggestions) ? suggestions : [])
    .map(x => (typeof x === 'string' ? { name: x, count: null } : { name: String(x?.name ?? ''), count: x?.count ?? null }))
    .filter(x => x.name), [suggestions])
  const matches = useMemo(() => {
    if (!pool.length) return []
    const q = pending.toLowerCase()
    const taken = new Set(tags.map(x => x.toLowerCase()))
    return pool.filter(x => !taken.has(x.name.toLowerCase()) && (!q || x.name.toLowerCase().includes(q))).slice(0, 50)
  }, [pool, pending, value]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setHi(0) }, [pending, matches.length])
  const pick = (name) => {
    if (!tags.some(x => x.toLowerCase() === name.toLowerCase())) {
      setHues(prev => ({ ...prev, [name]: randomHue() }))
      onChange([...tags, name].join(', '))
    }
    setText('')
  }
  const showList = open && !disabled && matches.length > 0
  // Yinelenen girdi `add` tarafından zaten eklenmiyor; önizlemesi de gösterilmez. Böylece
  // "önizlemede görünen ne ise işleme alınan odur" eşitliği — dolayısıyla yükseklik
  // kararlılığı — her iki dalda da korunur.
  const pendingIsNew = !!pending && !tags.some(x => x.toLowerCase() === pending.toLowerCase())
  // cmdk öğe değeri kırpılmış addır; vurgu indeksi ↔ değer eşlemesi bunun üstünden.
  const activeValue = matches[hi]?.name.trim() ?? ''
  return (
    <label className="full-width">
      {label && <span>{label}</span>}
      {!disabled && (
        <Popover open={showList} onOpenChange={(next) => { if (!next) setOpen(false) }}>
          <PopoverAnchor asChild>
            <span ref={anchorRef} className="relative block">
              <Input type="text" value={text} placeholder={placeholder}
                role={pool.length ? 'combobox' : undefined} aria-expanded={pool.length ? showList : undefined} aria-autocomplete={pool.length ? 'list' : undefined}
                onChange={e => { setText(e.target.value); setOpen(true) }}
                onFocus={() => setOpen(true)}
                onBlur={() => { setOpen(false); add() }}
                onKeyDown={e => {
                  if (showList && e.key === 'ArrowDown') { e.preventDefault(); setHi(h => Math.min(h + 1, matches.length - 1)); return }
                  if (showList && e.key === 'ArrowUp') { e.preventDefault(); setHi(h => Math.max(h - 1, 0)); return }
                  if (e.key === 'Escape') { setOpen(false); return }
                  if (e.key === 'Enter' || e.key === ',') {
                    e.preventDefault()
                    // Liste açıkken Enter vurgulanan öneriyi alır; yazılan metin tam eşleşmiyorsa bile mevcut etiketi tercih et
                    if (showList && matches[hi]) pick(matches[hi].name); else add()
                  }
                }} />
            </span>
          </PopoverAnchor>
          <PopoverContent
            align="start"
            sideOffset={4}
            collisionPadding={8}
            className="z-(--z-menu) w-(--radix-popover-trigger-width) min-w-48 p-0"
            // Odak giriş kutusunda kalır: yazmaya devam edilir, ok tuşları kutunun keydown'ında.
            onOpenAutoFocus={(e) => e.preventDefault()}
            onCloseAutoFocus={(e) => e.preventDefault()}
            // Kutunun kendisine basmak "dışarı" sayılıp listeyi kapatmasın.
            onInteractOutside={(e) => { if (anchorRef.current?.contains(e.target)) e.preventDefault() }}
          >
            <Command shouldFilter={false} value={activeValue}
              onValueChange={(v) => { const i = matches.findIndex(m => m.name.trim() === v); if (i >= 0) setHi(i) }}>
              <CommandList aria-label={suggestLabel || undefined} className="max-h-[220px]">
                <CommandGroup>
                  {matches.map((m) => (
                    <CommandItem key={m.name} value={m.name.trim()}
                      className="justify-between gap-2.5"
                      // mousedown'da blur ENGELLENİR: aksi hâlde önce blur→add yazılan metni chip yapar, sonra tıklama boşa düşer
                      onMouseDown={e => e.preventDefault()}
                      onSelect={() => pick(m.name)}>
                      <span className="min-w-0 truncate">{m.name}</span>
                      {m.count != null && <span className="text-xs text-muted-foreground tabular-nums">{m.count}</span>}
                    </CommandItem>
                  ))}
                </CommandGroup>
              </CommandList>
            </Command>
          </PopoverContent>
        </Popover>
      )}
      {(tags.length > 0 || pendingIsNew) && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {tags.map(tag => {
            const h = hues[tag] ?? tagHue(tag)
            return (
              <Badge key={tag} variant="outline" className="gap-1 px-2.5 font-semibold"
                style={{ background: `hsl(${h},70%,93%)`, color: `hsl(${h},65%,30%)`, borderColor: `hsl(${h},70%,78%)` }}>
                {tag}
                {/* Ad hem çevrilir hem ETİKETİ taşır: sabit "remove" 8 etiketli bir kayıtta
                    8 özdeş düğme demekti (hangisinin kaldırılacağı duyulmuyordu) ve TR
                    arayüzde İngilizce okunuyordu. */}
                {!disabled && (
                  <Button type="button" variant="ghost" size="icon-xs" aria-label={t('tag.removeTag', tag)}
                    className="-mr-1 size-4 rounded-full text-inherit hover:bg-transparent hover:opacity-65 dark:hover:bg-transparent"
                    style={{ color: `hsl(${h},60%,38%)` }} onClick={() => remove(tag)}>
                    <X aria-hidden="true" />
                  </Button>
                )}
              </Badge>
            )
          })}
          {pendingIsNew && (
            <Badge variant="outline" data-pending="" className="gap-1 border-dashed bg-muted px-2.5 font-semibold text-muted-foreground">
              {pending}
              {/* Gerçek chip ile AYNI öğe türü: farklı bir etiket kullanmak (span) düğme
                  yazı tipi kalıtımı yüzünden birkaç piksellik genişlik farkı üretir ve
                  önizlemenin ayırdığı yer tam oturmaz. */}
              <Button type="button" variant="ghost" size="icon-xs" tabIndex={-1} aria-label={t('tag.discardPending')}
                className="-mr-1 size-4 rounded-full text-inherit hover:bg-transparent hover:opacity-65 dark:hover:bg-transparent"
                // mousedown'da blur ENGELLENİR: aksi halde metin önce chip'e dönüşür, bu
                // düğme kaybolur ve tıklama boşa düşerdi.
                onMouseDown={e => e.preventDefault()}
                onClick={() => setText('')}>
                <X aria-hidden="true" />
              </Button>
            </Badge>
          )}
        </div>
      )}
    </label>
  )
}
