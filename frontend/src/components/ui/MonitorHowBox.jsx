import { useState } from 'react'
import { HelpCircle, ChevronDown } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'

/**
 * "Bu izleme nasıl ve nereden yapılıyor?" — her izleme türünde standart, açılır-kapanır bilgi kutusu.
 * Sayfa başlığının hemen altına, liste/kartların üstüne konur. Yeni bir izleme türü eklenince
 * VARSAYILAN olarak eklenir: sadece `bullets` (o türün nasıl/nereden çalıştığını anlatan maddeler)
 * geçilir. Başlık ortak `mhow.title` anahtarından gelir; istenirse `title` ile ezilebilir.
 */
export default function MonitorHowBox({ bullets = [], title }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const items = bullets.filter(Boolean)
  if (items.length === 0) return null
  return (
    <div className="mhow" data-tour="mon-how">
      <button type="button" className="mhow-toggle" onClick={() => setOpen(o => !o)} aria-expanded={open}>
        <HelpCircle size={15} /><span>{title || t('mhow.title')}</span>
        <ChevronDown size={15} className={`mhow-chev${open ? ' open' : ''}`} />
      </button>
      {open && (
        <ul className="mhow-body">
          {items.map((b, i) => <li key={i}>{b}</li>)}
        </ul>
      )}
    </div>
  )
}
