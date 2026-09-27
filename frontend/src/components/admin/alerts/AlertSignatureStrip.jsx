import { History, Clock3, ArrowRightCircle } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Alarm kartı imza şeridi (2026-09-16, kullanıcı isteği): "bu alarm hangi takımın, daha önce kaç kez
 * açıldı, önceki oluşum ne zamandı, en son ne zaman görüldü / ne zamandır sessiz" + aynı imzanın
 * geçmişine tek tıkla geçiş.
 *
 * <p>İmza = alan adı + alarm tipi (sunucudaki `RepeatKey` ile aynı tanım). Sayılar liste ucundan
 * TEK toplu sorguyla gelir (history_*), kart başına istek YOKTUR.
 * Çizim shadcn: çipler Badge (outline, hap), geçmiş bağlantısı ghost Button.
 */
const DAY = 86_400_000
const CHIP = 'rounded-full font-normal text-muted-foreground [&_strong]:font-semibold [&_strong]:text-foreground'

/** ISO (zone'suz = UTC) damgayı ms'e çevirir; bozuksa null. */
function ms(iso) {
  if (!iso) return null
  const d = new Date(String(iso).endsWith('Z') ? iso : iso + 'Z')
  return isNaN(d) ? null : d.getTime()
}

/** Gün farkı (tam gün, aşağı yuvarlı); geçersiz damgada null. */
export function daysSince(iso, now = Date.now()) {
  const t = ms(iso)
  return t == null ? null : Math.max(0, Math.floor((now - t) / DAY))
}

export default function AlertSignatureStrip({ alert: a, teamName, onShowHistory }) {
  const t = useT()
  const count = Number(a?.history_count || 0)
  const prev = a?.history_prev_at || null
  const lastResolved = a?.history_last_resolved_at || null
  const isOpen = !a?.resolved
  // "Ne zamandır sessiz": KAPALI alarmda kendi kapanışından bu yana geçen süre; açık alarmda
  // sessizlik yoktur (alarm sürüyor), onun yerine önceki oluşumla arasındaki boşluk anlamlıdır.
  const quietDays = !isOpen ? daysSince(a?.resolved_at || lastResolved) : null
  const gapDays = prev ? daysSince(prev, ms(a?.created_at) ?? Date.now()) : null
  const hasTeam = a?.team_id != null && !a?.sy_team_name

  if (count <= 1 && !hasTeam && !prev && quietDays == null) return null

  return (
    <div className="mt-1.5 mb-0.5 flex flex-wrap items-center gap-1.5 text-[0.78em]">
      {hasTeam && (
        <Badge variant="outline" className={cn(CHIP, 'bg-primary/5')}>
          {t('alh.sig.team')}: <TeamBadge teamId={a.team_id} teamName={teamName} size={11} />
        </Badge>
      )}
      {count > 1 && (
        <Badge variant="outline" className={CHIP} title={t('alh.sig.countTip')}>
          <History size={11} aria-hidden="true" /> {t('alh.sig.count', count)}
        </Badge>
      )}
      {prev ? (
        <Badge variant="outline" className={CHIP} title={t('alh.sig.prevTip')}>
          <Clock3 size={11} aria-hidden="true" /> {t('alh.sig.prev')}: <strong>{formatDate(prev)}</strong>
          {gapDays != null && <span className="opacity-75"> · {gapDays === 0 ? t('alh.sig.gapSameDay') : t('alh.sig.gap', gapDays)}</span>}
        </Badge>
      ) : count <= 1 ? (
        <Badge variant="outline" className={cn(CHIP, 'border-success/40 text-success')}>{t('alh.sig.first')}</Badge>
      ) : null}
      {quietDays != null && (
        <Badge variant="outline" className={cn(CHIP, 'border-sky-200 bg-sky-50 text-sky-700 dark:border-sky-900 dark:bg-sky-950 dark:text-sky-300')} title={t('alh.sig.quietTip')}>
          {t('alh.sig.quiet', quietDays)}
        </Badge>
      )}
      {count > 1 && (
        <Button type="button" variant="ghost" size="xs" className="h-auto rounded-full px-2 py-0.5 text-[1em] text-primary hover:bg-primary/10 hover:text-primary"
          onClick={() => onShowHistory?.(a)}>
          <ArrowRightCircle size={12} aria-hidden="true" /> {t('alh.sig.showHistory')}
        </Button>
      )}
    </div>
  )
}
