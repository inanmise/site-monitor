import { Building2, CircleHelp, LockKeyhole, Mail, RotateCcw, Smartphone } from 'lucide-react'
import { useT } from '../../../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { channelLabel } from './loginStatsModel.js'

/** Kanal simgeleri (lucide). */
export const CHANNEL_ICON = {
  LDAP: Building2,
  LOCAL: LockKeyhole,
  OTP_PUSH: Smartphone,
  OTP_EMAIL: Mail,
  REMEMBER_ME: RotateCcw,
  UNKNOWN: CircleHelp,
  OTHER: CircleHelp,
}

/**
 * Giriş kanalı rozeti (2026-10-03) — simge + ad; `estimated` → "tahmini" eki (bu sürümden önceki yöntemsiz satır, hesap
 * kaynağına göre sınıflandırıldı). Giriş istatistikleri ile Kullanıcı/Oturum giriş geçmişinde ORTAK.
 * Test kancası: `data-slot="login-channel"` + `data-channel` (+ `data-estimated`).
 */
export default function ChannelBadge({ channel, estimated = false, className }) {
  const t = useT()
  if (!channel) return null
  const Icon = CHANNEL_ICON[channel] || CircleHelp
  return (
    <Badge variant="outline" data-slot="login-channel" data-channel={channel} data-estimated={estimated ? 'true' : undefined}
      className={cn('gap-1 font-normal', className)} title={estimated ? t('lm.stats.estimatedTip') : undefined}>
      <Icon aria-hidden="true" className="size-3" />
      {channelLabel(channel, t)}
      {estimated && <span className="text-muted-foreground">· {t('lm.stats.estimatedTag')}</span>}
    </Badge>
  )
}
