import { useEffect, useState } from 'react'
import { Timer } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'

/** Sunucu penceresi 60 sn (dakikada 10 tanılama); Retry-After başlığı gelmiyor → tam pencere kadar bekletilir. */
export const RATE_LIMIT_WINDOW_S = 60

/**
 * 429 (hız sınırı) şeridi — dostça açıklama + geri sayım + süre dolunca "yeniden dene" düğmesi.
 * Her iki tanılama yüzeyi (Bağlantı Tanılama penceresi, Alan Adı Tanılama) aynı şeridi çizer.
 *
 * @param {Function} onRetry   süre dolunca gösterilen düğmenin eylemi
 * @param {number}   [seconds] bekleme süresi (varsayılan pencere)
 */
export default function RateLimitBanner({ onRetry, seconds = RATE_LIMIT_WINDOW_S, className }) {
  const t = useT()
  const [left, setLeft] = useState(seconds)

  useEffect(() => {
    setLeft(seconds)
    if (!(seconds > 0)) return undefined
    const id = setInterval(() => setLeft((s) => (s <= 1 ? 0 : s - 1)), 1000)
    return () => clearInterval(id)
  }, [seconds])

  const ready = left <= 0
  return (
    <AlertBanner tone="warning" icon={Timer} role="alert" data-slot="rate-limit" title={t('diag.rateLimited')} className={className}
      actions={ready && onRetry ? <Button type="button" size="sm" onClick={onRetry}>{t('inv.diagRerun')}</Button> : null}>
      <span aria-live="polite">{ready ? t('diag.rateLimitedReady') : t('diag.rateLimitedBody', left)}</span>
    </AlertBanner>
  )
}
