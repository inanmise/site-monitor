import { Check, Circle, Compass, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { checklistProgress } from './tourEngine.js'
import { CHECKLIST_ITEMS } from './tourSteps.js'
import { useTour } from './TourProvider.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * Başlangıç listesi (yeni kullanıcı) — Genel Bakış'ın üstünde küçük panel: tur, ilk kart, Tüm
 * Sertifikalar, izleme, haftalık rapor, yardım. Maddeler sunucudaki `checklist` ile işaretlenir
 * (sekme ziyaretleri TourProvider'da), hepsi bitince ya da kullanıcı kapatınca kalıcı olarak gizlenir.
 * Ana turu kapatan (dismissed) kullanıcıya gösterilmez. Çizim shadcn Card (marka tonlu) + Button.
 */
export default function OnboardingChecklist() {
  const t = useT()
  const { tourState, start, persist, active } = useTour()
  if (active || !tourState || tourState.status === 'dismissed' || tourState.checklist_hidden) return null
  const p = checklistProgress(CHECKLIST_ITEMS, tourState.checklist)
  if (p.complete) return null
  return (
    <Card role="region" aria-label={t('tour.checkTitle')} data-slot="onboarding-checklist"
      className="mb-3.5 gap-1.5 border-primary/35 bg-primary/5 px-3.5 py-2.5 shadow-none">
      <div className="flex items-center gap-2">
        <Compass size={16} aria-hidden="true" className="shrink-0" />
        <strong className="min-w-0">{t('tour.checkTitle')}</strong>
        <span className="text-[.82em] font-semibold text-muted-foreground">{p.done}/{p.total}</span>
        <span className="flex-1" />
        <SimpleTooltip content={t('tour.checkHide')}>
          <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground"
            aria-label={t('tour.checkHide')} onClick={() => persist({ checklist_hidden: true })}>
            <X aria-hidden="true" />
          </Button>
        </SimpleTooltip>
      </div>
      <ProgressBar value={p.done} max={p.total} size="sm" decorative />
      <ul className="mt-1.5 grid grid-cols-[repeat(auto-fit,minmax(min(240px,100%),1fr))] gap-x-4 gap-y-1">
        {CHECKLIST_ITEMS.map((it) => {
          const done = tourState.checklist?.[it.key] === true
          const go = () => { if (it.key === 'tour') start('main'); else if (it.tab) navigateTo(it.tab) }
          return (
            <li key={it.key} data-done={done || undefined}
              className={cn('flex min-h-8 items-center gap-1.5 text-[.9em]', done && 'text-muted-foreground line-through')}>
              {done
                ? <Check size={14} aria-hidden="true" className="shrink-0 text-success" />
                : <Circle size={14} aria-hidden="true" className="shrink-0 text-muted-foreground" />}
              {done ? <span>{t(`tour.check.${it.key}`)}</span> : (
                <Button type="button" variant="link" size="sm" className="h-auto px-1 py-0.5 text-[1em] font-semibold whitespace-normal"
                  onClick={go}>{t(`tour.check.${it.key}`)}</Button>
              )}
            </li>
          )
        })}
      </ul>
    </Card>
  )
}
