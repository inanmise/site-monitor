import { useEffect, useState } from 'react'
import { Compass, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useTour } from './TourProvider.jsx'

/**
 * "Bu sayfayı tanımak ister misin?" çipi — sayfa turu olan bir sekmeye İLK gelişte, sağ altta küçük.
 * Kapatınca o sayfa `seen_pages`e yazılır (bir daha çıkmaz); ana turu kapatan kişiye hiç çıkmaz.
 */
export default function TourPageChip({ tab }) {
  const t = useT()
  const { offerPage, start, persist, active } = useTour()
  const [hidden, setHidden] = useState(false)
  useEffect(() => { setHidden(false) }, [tab])
  if (hidden || active || !offerPage(tab)) return null
  return (
    <div className="tour-chip" role="status">
      <Compass size={14} />
      <span>{t('tour.pageOffer')}</span>
      <button type="button" className="btn btn-sm btn-primary" onClick={() => start('page', { pageId: tab })}>{t('tour.pageStart')}</button>
      <button type="button" className="tour-x" aria-label={t('tour.close')} onClick={() => { setHidden(true); persist({ seen_page: tab }) }}><X size={14} /></button>
    </div>
  )
}
