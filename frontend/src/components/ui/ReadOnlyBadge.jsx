import { Lock } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import TeamBadge from './TeamBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/**
 * "Başka takımın kaydı — salt okunur" rozeti (org geneli görünürlük, 2026-09-26).
 *
 * Yabancı satır/kart/pencere başlığında durur; yanında kaydın SAHİBİ takımın rozeti (TeamBadge → üye
 * penceresi). Metin görünür (yalnız-hover bilgi yok), `title` ile uzun açıklama. Sol renk şeridi YOK.
 * Test kancası `data-slot="read-only-badge"`.
 */
export default function ReadOnlyBadge({ teamId, teamName, className = '', compact = false }) {
  const t = useT()
  return (
    <span data-slot="read-only-badge" className={cn('inline-flex min-w-0 flex-wrap items-center gap-1.5', className)}>
      <Badge variant="secondary" title={t('scope.readOnlyHint')}
        className="gap-1 bg-muted font-semibold whitespace-normal text-muted-foreground">
        <Lock aria-hidden="true" />{compact ? t('scope.readOnly').split(' — ').pop() : t('scope.readOnly')}
      </Badge>
      {teamName ? <TeamBadge teamId={teamId} teamName={teamName} /> : null}
    </span>
  )
}
