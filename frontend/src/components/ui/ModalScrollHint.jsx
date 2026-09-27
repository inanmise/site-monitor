import { ChevronDown } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * "Devamı için kaydırın" zıplayan ipucu — `useModalScrollHint` ile çift. shadcn Button.
 * Alt eylem çubuğunun HEMEN ÜSTÜNDE durur: çağıran onu `relative` bir çubuk kabının içine koyar,
 * düğme kabın üst kenarına (`bottom-full`) yaslanır; gövde sonuna gelince kaybolur. Sunum burada,
 * karar hook'ta. Zıplama `animate-bounce` (transform) — ortalama `translate` özelliğinde, çakışmaz;
 * azaltılmış harekette durur.
 */
export default function ModalScrollHint({ show, scrollMore }) {
  const t = useT()
  if (!show) return null
  return (
    <Button type="button" size="sm" data-slot="modal-scroll-hint" onClick={scrollMore}
      className="absolute bottom-full left-1/2 z-10 mb-[26px] -translate-x-1/2 animate-bounce rounded-full px-3.5 text-xs font-semibold shadow-md motion-reduce:animate-none">
      <ChevronDown size={14} aria-hidden="true" />
      <span>{t('mon.scrollForMore')}</span>
    </Button>
  )
}
