import { Forward } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { forwardTeamName } from './forwardModel.js'

/**
 * İletim alıcısı rozeti (2026-11-05) — elle yeniden gönderim önizlemesinin e-posta / push satırları, alarmın bildirim
 * geçmişi ve SMTP günlüğü: satır, sahip takımın alarmını başka takıma ileten bir ALARM İLETİMİ alıcısıdır.
 * Metin "İletim · <alıcı takım>" (takım adı yoksa — boş ya da yalnız "-" — yalnız "İletim"); ad KESİLMEZ, rozet sarar (düz köşe,
 * taşmasız).
 * Ton bilgi amaçlı (camgöbeği) — alarm rengi değil. Test kancası: `data-slot="forward-badge"`.
 */
export const FORWARD_TONE = 'border-teal-300 bg-teal-500/10 text-teal-800 dark:border-teal-800 dark:text-teal-200'

export default function ForwardBadge({ teamName, className, ...rest }) {
  const t = useT()
  const name = forwardTeamName(teamName)
  return (
    <Badge variant="outline" data-slot="forward-badge" {...rest}
      className={cn('h-auto max-w-full gap-1 overflow-visible rounded-md font-semibold whitespace-normal [overflow-wrap:anywhere]', FORWARD_TONE, className)}>
      <Forward aria-hidden="true" className="size-3 shrink-0" />
      <span className="min-w-0">{name ? t('fwdx.badgeTeam', name) : t('fwdx.role')}</span>
    </Badge>
  )
}
