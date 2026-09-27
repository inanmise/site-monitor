import { Compass, Sparkles } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Karşılama kartı (ilk giriş) / Yenilikler kartı (sürüm artınca). Üç çıkış: başlat · şimdi değil
 * (en çok 3 kez yeniden sorulur) · bir daha gösterme (kalıcı, sunucuda). Esc = "şimdi değil".
 * Çizim ui/ModalShell (shadcn Dialog) + Button; "bir daha gösterme" bağlantı görünümlü düğme.
 */
export default function WelcomeCard({ kind = 'welcome', isMobile = false, onStart, onLater, onNever }) {
  const t = useT()
  const whatsNew = kind === 'whatsnew'
  return (
    <ModalShell open onClose={onLater} size="sm" icon={whatsNew ? Sparkles : Compass}
      title={t(whatsNew ? 'tour.newTitle' : 'tour.welcomeTitle')}
      footer={(
        <div className="flex w-full flex-wrap items-center gap-2">
          <Button type="button" variant="link" size="sm" className="h-auto px-1 font-normal text-muted-foreground" onClick={onNever}>
            {t('tour.never')}
          </Button>
          <span className="flex-1" />
          <Button type="button" variant="secondary" onClick={onLater}>{t('tour.later')}</Button>
          <Button type="button" onClick={onStart} autoFocus>{t(whatsNew ? 'tour.newStart' : 'tour.start')}</Button>
        </div>
      )}>
      <p className="mb-2.5 leading-normal">{t(whatsNew ? 'tour.newBody' : (isMobile ? 'tour.welcomeBodyMobile' : 'tour.welcomeBody'))}</p>
      <ul className="mb-2.5 list-disc pl-[18px] text-[.92em] leading-[1.6]">
        <li>{t('tour.welcomeItem1')}</li>
        <li>{t('tour.welcomeItem2')}</li>
        <li>{t('tour.welcomeItem3')}</li>
      </ul>
      <p className="text-[.82em] text-muted-foreground">{t('tour.welcomeHint')}</p>
    </ModalShell>
  )
}
