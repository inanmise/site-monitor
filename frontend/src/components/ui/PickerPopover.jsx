import { useCallback, useRef, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/shadcn/button'
import { Command } from '@/components/shadcn/command'
import { PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'

/**
 * Seçim ailesinin (SearchableSelect, MultiTeamSelect) ORTAK parçaları — shadcn'in klasik
 * Combobox deseni: Popover + Command (cmdk) + Button. Tek başına kullanılmaz; iki seçici aynı
 * açılış/odak/katman sözleşmesini taşısın diye burada.
 *
 * Neden Base UI Combobox değil (shadcn/combobox): ModalShell bir Radix Dialog ve Escape'i Radix'in
 * katman yığını dağıtıyor. Base UI açılır listesi o yığını tanımaz — içindeki Escape'i Radix
 * document-capture aşamasında pencereye verir ve liste yerine PENCERE kapanırdı. Radix Popover
 * aynı yığında durur: Escape önce listeyi kapatır, pencere açık kalır.
 */

/** Açık/kapalı durumu + eşzamanlı okunabilen ref (seçim korumaları olaydan olaya ref'e bakar). */
export function usePickerOpen() {
  const [open, setOpenState] = useState(false)
  const openRef = useRef(false)
  const setOpen = useCallback((next) => {
    openRef.current = next
    setOpenState(next)
  }, [])
  return { open, openRef, setOpen }
}

// Alan görünümü: shadcn Input ile aynı kenarlık; etiket kırpılır (uzun kullanıcı adı sütunu
// şişirmesin — e2e/contact-modal.spec.js ölçüyor). Son satır eski `.ss-trigger` bağlam
// kurallarının karşılığı: tablo süzgeç satırında alçak tetik, toplu işlem çubuğunda asgari genişlik.
const TRIGGER_CLASS = cn(
  'group/picker w-full justify-between gap-2 border-input px-3 font-normal has-[>svg]:px-3',
  'data-[state=open]:border-ring',
  'in-[.inv-fr-cell]:h-7 in-[.inv-fr-cell]:px-2 in-[.inv-fr-cell]:text-xs in-[.bulkbar-field]:min-w-[140px]',
)

/**
 * Tetik: shadcn Button (`role="combobox"`). Açılış FARE BASIŞINDA — eski sözleşme; odak
 * tetiğe hiç geçmeden liste açılır ve arama kutusuna gider. Radix'in `click` toggle'ı bu yüzden
 * bastırılıyor: bastırılmasaydı basış açar, ardından gelen tık hemen kapatırdı.
 *
 * AD: `role="combobox"` adını İÇERİKTEN ALMAZ (ARIA "name from author") — tetikte yazan seçili
 * değer ad değil, değerdir. Eski `.ss-trigger` düz bir düğmeydi ve adını içerikten alıyordu;
 * rol değişince ad sessizce düştü (2026-09-25, R17). Adın üç yolu var: `ariaLabel`, görünür
 * etiketin id'si (`ariaLabelledBy`) ya da `id` + `<label htmlFor>` / Field render-prop'u.
 * Tetiği bir `<label>` sarıyorsa ad oradan gelir. Kapı: rowAccessibleNames.test.js.
 */
export function PickerTrigger({ open, openRef, setOpen, onOpen, disabled, placeholderShown, ariaLabel, ariaLabelledBy, id, children }) {
  const toggle = () => {
    const next = !openRef.current
    if (next) onOpen?.()
    setOpen(next)
  }
  return (
    <PopoverTrigger
      asChild
      onMouseDown={(e) => {
        if (disabled || e.button !== 0) return
        e.preventDefault()
        toggle()
      }}
      onClick={(e) => e.preventDefault()}
      onKeyDown={(e) => {
        if (disabled) return
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          toggle()
        } else if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && !openRef.current) {
          e.preventDefault()
          toggle()
        }
      }}
    >
      <Button
        id={id}
        type="button"
        variant="outline"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        aria-labelledby={ariaLabelledBy}
        disabled={disabled}
        data-placeholder={placeholderShown ? '' : undefined}
        className={TRIGGER_CLASS}
      >
        <span className={cn('min-w-0 flex-1 truncate text-left', placeholderShown && 'text-muted-foreground')}>
          {children}
        </span>
        <ChevronDown
          aria-hidden="true"
          className="text-muted-foreground transition-transform duration-200 group-data-[state=open]/picker:rotate-180 motion-reduce:transition-none"
        />
      </Button>
    </PopoverTrigger>
  )
}

/**
 * Açılır liste: body'ye PORTAL (Radix Popover) — ModalShell ya da `overflow` taşıyan bir ata
 * onu kırpamaz (eskiden kutunun alt kenarında kesiliyordu). Katman `--z-menu` (9600): her
 * derinlikteki modalın (2000+) ve Dialog'un (9500) üstünde, toast'un altında. Aşağıda yer yoksa
 * Radix yukarı çevirir; tetik kaydırılıp görünmez olursa liste de gizlenir (hideWhenDetached).
 *
 * Genişlik eski listeyle aynı: en az tetik kadar, içerik kadar büyür, en çok 360px — dar
 * süzgeç tetiklerinde uzun takım adları sıkışmaz. Yükseklik ekranda kalan alanla sınırlı.
 *
 * Odak: arama kutusu varsa ona, yoksa cmdk köküne — ok tuşları/Enter kökteki keydown ile çalışır;
 * Radix varsayılanı ilk sekmelenebilir öğeyi (dal başlığı, sil düğmesi) seçerdi.
 */
export function PickerContent({ children, commandProps, className }) {
  const commandRef = useRef(null)
  return (
    <PopoverContent
      align="start"
      sideOffset={4}
      collisionPadding={8}
      hideWhenDetached
      className={cn(
        'z-(--z-menu) flex max-h-(--radix-popover-content-available-height) w-max min-w-(--radix-popover-trigger-width) max-w-[min(360px,90vw)] flex-col overflow-hidden p-0',
        className,
      )}
      onOpenAutoFocus={(e) => {
        e.preventDefault()
        const root = commandRef.current
        const target = root?.querySelector('[cmdk-input]') || root
        target?.focus({ preventScroll: true })
      }}
    >
      <Command ref={commandRef} shouldFilter={false} loop className="min-h-0 outline-none" {...commandProps}>
        {children}
      </Command>
    </PopoverContent>
  )
}
