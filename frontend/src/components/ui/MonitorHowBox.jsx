import { useState } from 'react'
import { HelpCircle, ChevronDown } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/**
 * "Bu izleme nasıl ve nereden yapılıyor?" — her izleme türünde standart, açılır-kapanır bilgi kutusu.
 * Sayfa başlığının hemen altına, liste/kartların üstüne konur. Yeni bir izleme türü eklenince
 * VARSAYILAN olarak eklenir: sadece `bullets` (o türün nasıl/nereden çalıştığını anlatan maddeler)
 * geçilir. Başlık ortak `mhow.title` anahtarından gelir; istenirse `title` ile ezilebilir.
 * Çizim shadcn Collapsible (tetik Button; aria-expanded Radix'ten). Kapalıyken içerik DOM'da yok.
 */
export default function MonitorHowBox({ bullets = [], title }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const items = bullets.filter(Boolean)
  if (items.length === 0) return null
  return (
    <Collapsible open={open} onOpenChange={setOpen} data-tour="mon-how"
      className="mb-3.5 overflow-hidden rounded-lg border bg-violet-500/5">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost"
          className="h-auto w-full justify-start gap-2 rounded-none px-3.5 py-2 text-[13px] font-semibold hover:bg-violet-500/5 dark:hover:bg-violet-500/10">
          <HelpCircle size={15} aria-hidden="true" className="text-violet-600 dark:text-violet-400" />
          <span>{title || t('mhow.title')}</span>
          <ChevronDown size={15} aria-hidden="true"
            className={cn('ml-auto text-muted-foreground transition-transform duration-200 motion-reduce:transition-none', open && 'rotate-180')} />
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <ul className="m-0 list-disc py-0.5 pr-4 pb-3 pl-[34px] text-[13px] leading-relaxed text-muted-foreground">
          {items.map((b, i) => <li key={i} className="my-[3px]">{b}</li>)}
        </ul>
      </CollapsibleContent>
    </Collapsible>
  )
}
