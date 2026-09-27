import { useEffect, useState } from 'react'
import { Compass, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useTour } from './TourProvider.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * "Bu sayfayı tanımak ister misin?" çipi — sayfa turu olan bir sekmeye İLK gelişte, sağ altta küçük.
 * Kapatınca o sayfa `seen_pages`e yazılır (bir daha çıkmaz); ana turu kapatan kişiye hiç çıkmaz.
 * Çizim: yüzen hap (shadcn kart yüzeyi jetonları) + shadcn Button; telefonda ekran genişliğinde, yardım
 * düğmesinin (sağ alt "?") üstünde.
 */
export default function TourPageChip({ tab }) {
  const t = useT()
  const { offerPage, start, persist, active } = useTour()
  const [hidden, setHidden] = useState(false)
  useEffect(() => { setHidden(false) }, [tab])
  if (hidden || active || !offerPage(tab)) return null
  return (
    <div role="status" data-slot="tour-page-chip"
      className="fixed inset-x-4 bottom-[calc(70px+env(safe-area-inset-bottom))] z-[890] flex items-center justify-between gap-2 rounded-full border bg-card py-1.5 pr-2 pl-3 text-[.86em] text-card-foreground shadow-lg sm:inset-x-auto sm:right-[72px] sm:bottom-[22px] sm:justify-start print:hidden">
      <Compass size={14} aria-hidden="true" className="shrink-0" />
      <span className="min-w-0 flex-1 sm:flex-none">{t('tour.pageOffer')}</span>
      <Button type="button" size="sm" onClick={() => start('page', { pageId: tab })}>{t('tour.pageStart')}</Button>
      <Button type="button" variant="ghost" size="icon-sm" className="rounded-full text-muted-foreground" aria-label={t('tour.close')}
        onClick={() => { setHidden(true); persist({ seen_page: tab }) }}>
        <X aria-hidden="true" />
      </Button>
    </div>
  )
}
