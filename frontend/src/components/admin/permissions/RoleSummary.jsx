import { Lock } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { ProgressBar } from '../../ui/Progress.jsx'
import { cn } from '@/lib/utils'
import { ACTIONS } from './permissionModel.js'

/**
 * Rol özet kartları — her rol için "N / M yetki açık" + tür dağılımı (görüntüleme / düzenleme / çalıştırma).
 * Kart shadcn Button (`aria-pressed`): masaüstünde o rolün sütununu vurgular ve görünür kılar, telefonda rol seçicisini
 * o role çevirir. Sayılar kaydedilmemiş değişiklikler DAHİL (ekranda görülen durum). Sol renk şeridi YOK — rol kimliği
 * yalnız küçük nokta. Test kancaları: `data-slot="perm-role-summary|perm-role-card"`, `data-role`.
 */
export default function RoleSummary({ roles, summaries, active, onPick }) {
  const t = useT()
  return (
    <div data-slot="perm-role-summary" className="grid grid-cols-2 gap-2 sm:gap-3 lg:grid-cols-4">
      {roles.map((role) => {
        const s = summaries[role.key]
        const pressed = active === role.key
        return (
          <Button key={role.key} type="button" variant="outline" data-slot="perm-role-card" data-role={role.key}
            aria-pressed={pressed} aria-label={t('perm.summaryAria', role.key, s.on, s.total)}
            onClick={() => onPick(role.key)}
            className={cn('h-auto min-w-0 flex-col items-stretch gap-2 rounded-xl bg-card p-3 text-left font-normal whitespace-normal shadow-xs sm:p-4',
              pressed && 'border-primary ring-2 ring-primary/30')}>
            <span className="flex min-w-0 items-center gap-2">
              <span aria-hidden="true" className={cn('size-2.5 shrink-0 rounded-full', role.dot)} />
              <span className="min-w-0 truncate text-sm font-semibold tracking-wide">{role.key}</span>
              {role.locked && <Lock aria-hidden="true" className="ml-auto size-3.5 shrink-0 text-muted-foreground" />}
            </span>
            <span className="flex items-baseline gap-1.5">
              <span className="text-2xl leading-none font-bold tabular-nums sm:text-3xl">{s.on}</span>
              <span className="text-sm text-muted-foreground tabular-nums">{t('perm.summaryOf', s.total)}</span>
            </span>
            <span className="text-xs text-muted-foreground">{role.locked ? t('perm.summaryLocked') : t('perm.summaryLabel')}</span>
            <ProgressBar value={s.on} max={s.total || 1} size="sm" decorative />
            <span className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted-foreground tabular-nums">
              {ACTIONS.map(({ key, Icon, labelKey }) => (
                <span key={key} className="inline-flex items-center gap-1" title={t(labelKey)}>
                  <Icon aria-hidden="true" className="size-3" />
                  {s.byKind[key].on}/{s.byKind[key].total}
                </span>
              ))}
            </span>
          </Button>
        )
      })}
    </div>
  )
}
