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
 * - `content` boşsa çocuk olduğu gibi döner (koşullu ipucu için çağıranda dallanma gerekmez).
 * - `triggerClassName`: tetik düğmesine (ör. kartta örtünün üstüne çıkmak için `CARD_LAYER`).
 * - Modal içinde de görünsün diye katman `--z-menu` (SHADCN.md §2.5).
 */
export default function HintPopover({ content, side = 'top', align = 'start', triggerClassName, className, children, ...rest }) {
  const [open, setOpen] = useState(false)
  const id = useId()
  if (content == null || content === '' || content === false) return children
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" data-slot="hint-trigger" aria-describedby={open ? id : undefined} {...rest}
          className={cn('h-auto min-h-0 cursor-help gap-0 rounded-full p-0 font-[inherit] hover:bg-transparent dark:hover:bg-transparent pointer-coarse:min-h-8', triggerClassName)}>
          {children}
        </Button>
      </PopoverTrigger>
      <PopoverContent side={side} align={align} sideOffset={6} collisionPadding={8}
        id={id} role="tooltip" data-slot="hint-popover" onOpenAutoFocus={(e) => e.preventDefault()}
        className={cn('z-(--z-menu) w-auto max-w-[min(20rem,calc(100vw-1rem))] px-3 py-2 text-xs leading-relaxed whitespace-pre-line', className)}>
        {content}
      </PopoverContent>
    </Popover>
  )
}
