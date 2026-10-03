import { useT } from '../../i18n/index.jsx'
import { ProgressRing } from '../ui/Progress.jsx'
import { cn } from '@/lib/utils'

/** Kalan saniye (yukarı yuvarlanır — "1 sn" son saniye boyunca görünür, 0 = doldu). */
export function secondsLeft(expiresAt, now) {
  if (!Number.isFinite(expiresAt) || !Number.isFinite(now)) return 0
  return Math.max(0, Math.ceil((expiresAt - now) / 1000))
}

/** Ekran okuyucu duyurusu yalnız bu eşiklerde değişir (seyrek): 30 sn, 10 sn, doldu. */
export function announceMilestone(left, total) {
  if (left <= 0) return 0
  if (left <= 10) return 10
  if (left <= 30 && total > 30) return 30
  return null
}

/**
 * Kod geri sayımı (2026-10-02, kodla giriş) — dairesel halka + "45 sn". Son 10 saniyede uyarı tonu (halka + metin
 * turuncu), süre dolunca kırmızı "süre doldu". Saat ÇAĞIRANDAN gelir (`now`, tek zamanlayıcı — kod akışı yönetir).
 * Erişilebilirlik: görünür sayaç `role="timer"` (canlı değil — her saniye okunmaz); ayrı, görünmez `aria-live="polite"`
 * bölgesi yalnız 30 sn / 10 sn / doldu anlarında değişir.
 *
 * Test kancası: `data-slot="otp-countdown"` + `data-state="ok|warn|expired"`.
 */
export default function OtpCountdown({ expiresAt, total, now }) {
  const t = useT()
  const left = secondsLeft(expiresAt, now)
  const state = left === 0 ? 'expired' : left <= 10 ? 'warn' : 'ok'
  const pct = total > 0 ? Math.round((left / total) * 100) : 0
  const milestone = announceMilestone(left, total)
  const color = state === 'ok' ? 'var(--primary)' : state === 'warn' ? 'var(--warning)' : 'var(--destructive)'
  return (
    <div data-slot="otp-countdown" data-state={state} className="flex min-w-0 items-center gap-3">
      <span aria-hidden="true" className="inline-flex shrink-0">
        <ProgressRing value={pct} max={100} size={40} stroke={4} showValue={false} color={color} />
      </span>
      <div className="flex min-w-0 flex-col leading-tight">
        <span role="timer" aria-label={t('otp.timerLabel')}
          className={cn('text-lg font-semibold tabular-nums',
            state === 'warn' && 'text-warning', state === 'expired' && 'text-destructive')}>
          {state === 'expired' ? t('otp.expiredShort') : t('otp.remaining', left)}
        </span>
        <span className="text-xs text-muted-foreground">{t('otp.timerLabel')}</span>
      </div>
      <span className="sr-only" aria-live="polite" data-slot="otp-countdown-announce">
        {milestone === 0 ? t('otp.expiredNow') : milestone ? t('otp.timerAnnounce', milestone) : ''}
      </span>
    </div>
  )
}
