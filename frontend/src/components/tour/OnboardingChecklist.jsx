import { Check, Circle, Compass, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { checklistProgress } from './tourEngine.js'
import { CHECKLIST_ITEMS } from './tourSteps.js'
import { useTour } from './TourProvider.jsx'

/**
 * Başlangıç listesi (yeni kullanıcı) — Genel Bakış'ın üstünde küçük panel: tur, ilk kart, Tüm
 * Sertifikalar, izleme, haftalık rapor, yardım. Maddeler sunucudaki `checklist` ile işaretlenir
 * (sekme ziyaretleri TourProvider'da), hepsi bitince ya da kullanıcı kapatınca kalıcı olarak gizlenir.
 * Ana turu kapatan (dismissed) kullanıcıya gösterilmez.
 */
export default function OnboardingChecklist() {
  const t = useT()
  const { tourState, start, persist, active } = useTour()
  if (active || !tourState || tourState.status === 'dismissed' || tourState.checklist_hidden) return null
  const p = checklistProgress(CHECKLIST_ITEMS, tourState.checklist)
  if (p.complete) return null
  return (
    <section className="tour-check" aria-label={t('tour.checkTitle')}>
      <div className="tour-check-head">
        <Compass size={16} />
        <strong>{t('tour.checkTitle')}</strong>
        <span className="tour-check-pct">{p.done}/{p.total}</span>
        <span className="tour-spacer" />
        <button type="button" className="tour-x" aria-label={t('tour.checkHide')} title={t('tour.checkHide')} onClick={() => persist({ checklist_hidden: true })}><X size={14} /></button>
      </div>
      <ProgressBar value={p.done} max={p.total} size="sm" decorative className="tour-bar" />
      <ul className="tour-check-list">
        {CHECKLIST_ITEMS.map((it) => {
          const done = tourState.checklist?.[it.key] === true
          const go = () => { if (it.key === 'tour') start('main'); else if (it.tab) navigateTo(it.tab) }
          return (
            <li key={it.key} className={done ? 'is-done' : ''}>
              {done ? <Check size={14} className="tour-check-ok" /> : <Circle size={14} className="tour-check-todo" />}
              {done ? <span>{t(`tour.check.${it.key}`)}</span> : <button type="button" className="tour-link" onClick={go}>{t(`tour.check.${it.key}`)}</button>}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
