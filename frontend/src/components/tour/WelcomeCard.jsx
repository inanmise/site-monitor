import { Compass, Sparkles } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Karşılama kartı (ilk giriş) / Yenilikler kartı (sürüm artınca). Üç çıkış: başlat · şimdi değil
 * (en çok 3 kez yeniden sorulur) · bir daha gösterme (kalıcı, sunucuda). Esc = "şimdi değil".
 */
export default function WelcomeCard({ kind = 'welcome', isMobile = false, onStart, onLater, onNever }) {
  const t = useT()
  const whatsNew = kind === 'whatsnew'
  return (
    <ModalShell open onClose={onLater} size="sm" icon={whatsNew ? Sparkles : Compass}
      title={t(whatsNew ? 'tour.newTitle' : 'tour.welcomeTitle')}
      footer={(
        <div className="tour-welcome-actions">
          <button type="button" className="tour-link tour-link--muted" onClick={onNever}>{t('tour.never')}</button>
          <span className="tour-spacer" />
          <Button type="button" variant="secondary" onClick={onLater}>{t('tour.later')}</Button>
          <Button type="button" onClick={onStart} autoFocus>{t(whatsNew ? 'tour.newStart' : 'tour.start')}</Button>
        </div>
      )}>
      <p className="tour-welcome-body">{t(whatsNew ? 'tour.newBody' : (isMobile ? 'tour.welcomeBodyMobile' : 'tour.welcomeBody'))}</p>
      <ul className="tour-welcome-list">
        <li>{t('tour.welcomeItem1')}</li>
        <li>{t('tour.welcomeItem2')}</li>
        <li>{t('tour.welcomeItem3')}</li>
      </ul>
      <p className="tour-welcome-hint">{t('tour.welcomeHint')}</p>
    </ModalShell>
  )
}
