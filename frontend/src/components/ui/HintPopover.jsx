import { useId, useState } from 'react'
import { Button } from '@/components/shadcn/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'

/**
 * DOKUN-GÖR açıklama — rozet/etiket gibi küçük bir öğenin açıklamasını fare, klavye VE dokunmatikte gösterir.
 *
 * <p>Neden: `ui/SimpleTooltip` (shadcn Tooltip) yalnız hover/odakta açılır; telefonda hiç açılmaz. Kartlardaki
 * "Hiç başarılı olmadı", "Sistem kapattı (sebep)", k6 sürümü gibi TEMEL bilgi taşıyan açıklamalar dokunmatik
 * kullanıcıya hiç ulaşmıyordu (kullanıcı kuralı 2026-09-26: yalnız-hover bilgi YOK). Bu sarmalayıcı öğeyi gerçek bir
 * shadcn düğmesine (klavyeyle odaklanır, Enter/Space açar) çevirir ve açıklamayı shadcn Popover'da gösterir;
 * Escape / dışarı dokunuş kapatır. Metin okunacak bir açıklama olduğu için açılışta odak tetikte kalır; içerik
 * `role="tooltip"` ve açıkken tetiğe `aria-describedby` ile bağlı (ui/HelpTip ile aynı sözleşme).
 *
 * - `content` boşsa çocuk olduğu gibi döner (koşullu ipucu için çağıranda dallanma gerekmez). İşlev de olabilir:
 *   `content({ close })` — içerikteki bir eylem balonu kapatabilsin (ör. düzenleme formu açılırken balon formun
 *   üstünde asılı kalmasın).
 * - `triggerClassName`: tetik düğmesine (ör. kartta örtünün üstüne çıkmak için `CARD_LAYER`).
 * - Modal içinde de görünsün diye katman `--z-menu` (SHADCN.md §2.5).
 * - `interactive` (2026-09-28, 7/24 kart göstergesi): içerik bir BAĞLANTI / düğme taşıyorsa. Bu kipte içerik
 *   `role="tooltip"` DEĞİLDİR (ipucu etkileşimli öğe içeremez) — Radix'in `dialog` rolü kalır, adı `contentLabel`;
 *   açılışta odak içeriğe geçer (klavye kullanıcısı Tab ile eyleme ulaşır, Escape tetiğe döndürür). Varsayılan
 *   (açıklama) kip DEĞİŞMEDİ.
 */
export default function HintPopover({ content, side = 'top', align = 'start', interactive = false, contentLabel, triggerClassName, className, children, ...rest }) {
  const [open, setOpen] = useState(false)
  const id = useId()
  if (content == null || content === '' || content === false) return children
  const body = typeof content === 'function' ? content({ close: () => setOpen(false) }) : content
  // `role` anahtarı etkileşimli kipte HİÇ geçilmez: Radix içeriğe `role="dialog"` yazıp prop'ları ÜSTÜNE yayar —
  // `role={undefined}` o varsayılanı silerdi.
  const contentRole = interactive ? { 'aria-label': contentLabel } : { role: 'tooltip', onOpenAutoFocus: (e) => e.preventDefault() }
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" data-slot="hint-trigger" aria-describedby={open && !interactive ? id : undefined} {...rest}
          className={cn('h-auto min-h-0 gap-0 rounded-full p-0 font-[inherit] hover:bg-transparent dark:hover:bg-transparent pointer-coarse:min-h-8',
            interactive ? 'cursor-pointer' : 'cursor-help', triggerClassName)}>
          {children}
        </Button>
      </PopoverTrigger>
      <PopoverContent side={side} align={align} sideOffset={6} collisionPadding={8}
        id={id} {...contentRole}
        data-slot="hint-popover" data-interactive={interactive ? 'true' : undefined}
        className={cn('z-(--z-menu) w-auto max-w-[min(20rem,calc(100vw-1rem))] px-3 py-2 text-xs leading-relaxed whitespace-pre-line', className)}>
        {body}
      </PopoverContent>
    </Popover>
  )
}
