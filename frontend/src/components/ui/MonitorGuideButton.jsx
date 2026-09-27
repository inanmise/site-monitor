import { useState } from 'react'
import { BookOpen } from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import MarkdownEditor from './MarkdownEditor.jsx'
import ModalShell from './ModalShell.jsx'
import { MONITOR_GUIDES } from '../monitorGuides.js'
import { Button } from '@/components/shadcn/button'

/**
 * "Yeni monitör nasıl doldurulur?" — her izleme sayfasının başlığında, Yeni Monitör
 * butonunun yanında küçük bir ikon-buton. Tıklayınca o türe özel, form alanlarını tek
 * tek anlatan how-to dokümanını (markdown) modalda gösterir. İçerik `monitorGuides.js`
 * içinde tür-bazlı TR/EN markdown olarak durur. Yeni izleme türü → orada bir giriş ekle.
 * Pencere `ui/ModalShell` (shadcn Dialog): odak tuzağı, Escape, örtü tıklaması ve i18n'li X oradan.
 *
 * Sayfa başlığı (monitoring/MonitorPageHeader, 2026-09-27): masaüstünde bu düğme; telefonda kılavuz "Diğer"
 * menüsünün bir öğesi → pencere ayrıca `MonitorGuideDialog` olarak dışa verilir (denetimli `open`).
 * `variant`/`size`/`className`/`labelClassName` isteğe bağlı — verilmezse görünüm eskisiyle birebir aynı.
 */
export function hasMonitorGuide(type) {
  return !!MONITOR_GUIDES[type]
}

/** Kılavuz penceresi (denetimli). Tür için kılavuz yoksa hiçbir şey çizmez. */
export function MonitorGuideDialog({ type, open, onClose }) {
  const t = useT()
  const { lang } = useLanguage()
  const guide = MONITOR_GUIDES[type]
  if (!guide) return null
  const md = guide[lang] || guide.tr || guide.en
  return (
    <ModalShell open={open} onClose={onClose} title={t('guideForm.title')} icon={BookOpen}
      size="lg" scrollBody className="sm:max-w-[min(820px,calc(100%-2rem))]">
      <div className="text-sm leading-relaxed">
        <MarkdownEditor value={md} editable={false} />
      </div>
    </ModalShell>
  )
}

export default function MonitorGuideButton({ type, variant = 'secondary', size = 'sm', className, labelClassName }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  if (!hasMonitorGuide(type)) return null
  return (
    <>
      <Button type="button" variant={variant} size={size} data-tour="mon-guide" className={className}
        title={t('guideForm.btn')} aria-label={labelClassName ? t('guideForm.btn') : undefined} onClick={() => setOpen(true)}>
        <BookOpen size={14} aria-hidden="true" className="text-primary" />
        {labelClassName ? <span className={labelClassName}>{t('guideForm.btn')}</span> : <> {t('guideForm.btn')}</>}
      </Button>
      <MonitorGuideDialog type={type} open={open} onClose={() => setOpen(false)} />
    </>
  )
}
