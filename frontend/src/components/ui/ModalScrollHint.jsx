import { ChevronDown } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'

/**
 * "Devamı için kaydırın" zıplayan ipucu — `useModalScrollHint` ile çift.
 * Modalın alt barının hemen üstünde, mutlak konumlu (`.modal-scroll-hint`); gövde sonuna
 * gelince kaybolur. Sunum burada, karar hook'ta.
 */
export default function ModalScrollHint({ show, scrollMore }) {
  const t = useT()
  if (!show) return null
  return (
    <button type="button" className="modal-scroll-hint" onClick={scrollMore}>
      <ChevronDown size={14} />
      <span>{t('mon.scrollForMore')}</span>
    </button>
  )
}
