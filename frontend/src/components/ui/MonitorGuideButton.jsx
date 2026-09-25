import { useState } from 'react'
import { createPortal } from 'react-dom'
import { BookOpen, X } from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import MarkdownEditor from './MarkdownEditor.jsx'
import { MONITOR_GUIDES } from '../monitorGuides.js'
import { Button } from '@/components/shadcn/button'

/**
 * "Yeni monitör nasıl doldurulur?" — her izleme sayfasının başlığında, Yeni Monitör
 * butonunun yanında küçük bir ikon-buton. Tıklayınca o türe özel, form alanlarını tek
 * tek anlatan how-to dokümanını (markdown) modalda gösterir. İçerik `monitorGuides.js`
 * içinde tür-bazlı TR/EN markdown olarak durur. Yeni izleme türü → orada bir giriş ekle.
 */
export default function MonitorGuideButton({ type }) {
  const t = useT()
  const { lang } = useLanguage()
  const [open, setOpen] = useState(false)
  const guide = MONITOR_GUIDES[type]
  if (!guide) return null
  const md = guide[lang] || guide.tr || guide.en
  return (
    <>
      <Button type="button" variant="secondary" size="sm" className="mguide-btn" data-tour="mon-guide"
        title={t('guideForm.btn')} onClick={() => setOpen(true)}>
        <BookOpen size={14} /> {t('guideForm.btn')}
      </Button>
      {open && createPortal(
        <div className="modal-overlay" onClick={() => setOpen(false)}>
          <div className="modal-box modal-wide mguide-modal" onClick={e => e.stopPropagation()}>
            <div className="modal-head">
              <h3><BookOpen size={17} style={{ verticalAlign: '-3px', marginRight: 6, color: '#7c3aed' }} />{t('guideForm.title')}</h3>
              {/* İkon-yalnız düğme: adı i18n'den (dokuz sayfada adsız "düğme" duyuluyordu — 2026-09-25, R16). */}
              <button type="button" className="icon-btn" onClick={() => setOpen(false)}
                aria-label={t('app.close')} title={t('app.close')}><X size={18} aria-hidden="true" /></button>
            </div>
            <div className="mguide-body">
              <MarkdownEditor value={md} editable={false} />
            </div>
          </div>
        </div>, document.body)}
    </>
  )
}
