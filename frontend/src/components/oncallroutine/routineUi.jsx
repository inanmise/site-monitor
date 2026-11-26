/**
 * Nöbet Rutinleri — küçük ortak görünüm parçaları (durum rozeti, ton sınıfları, sunucu metni, canlı sunucu saati).
 * Ekranın her yerinde aynı ton dili: bilinmeyen / kayıt yok hiçbir zaman yeşil değildir.
 */
import { useEffect, useState } from 'react'
import { CheckCheck, CircleCheck, CircleDashed, CircleDot, CircleX, Clock3, PauseCircle, CalendarOff, CircleSlash } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import { dayStatusTone, errorKey, codeOf, serverNowMs } from './routineModel.js'

/** Ton → rozet sınıfı (tema jetonları; uyarı tonu projenin amber deseni). */
export const TONE_BADGE = Object.freeze({
  success: 'border-transparent bg-success/15 text-success',
  partial: 'border-success/30 bg-success/10 text-success',
  warning: 'border-transparent bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  danger: 'border-transparent bg-destructive/12 text-destructive',
  open: 'border-primary/30 bg-primary/10 text-primary',
  muted: 'border-border bg-muted text-muted-foreground',
})

const STATUS_ICON = {
  COMPLETED: CircleCheck,
  COMPLETED_PARTIAL: CheckCheck,
  INCOMPLETE: CircleDashed,
  MISSED: CircleX,
  OPEN: CircleDot,
  NONE: CircleSlash,
  NOT_WORKDAY: CalendarOff,
  FUTURE: Clock3,
  PAUSED: PauseCircle,
}

/** Gün durumu simgesi (bilinmeyen kod → boş daire). */
export function statusIcon(status) {
  return STATUS_ICON[status] || CircleSlash
}

/** Gün durumu rozeti — metin `or.status.<KOD>`, ton `dayStatusTone`. Ad sığmazsa sarar (kesilmez). */
export function DayStatusBadge({ status, className, slot = 'or-day-status' }) {
  const t = useT()
  const code = status || 'NONE'
  const Icon = statusIcon(code)
  const tone = dayStatusTone(code)
  return (
    <Badge variant="outline" data-slot={slot} data-status={code} data-tone={tone}
      className={cn('h-auto gap-1 rounded-md whitespace-normal overflow-visible', TONE_BADGE[tone], className)}>
      <Icon aria-hidden="true" />
      <span>{t(`or.status.${code}`)}</span>
    </Badge>
  )
}

/**
 * Sunucu yanıtının kullanıcıya gösterilecek metni: sunucunun kendi (Msg.t) metni varsa o; yoksa kodun çevirisi; yoksa
 * çağıranın yedek anahtarı.
 */
export function serverText(res, t, fallbackKey) {
  const msg = typeof res?.error === 'string' && res.error.trim() ? res.error.trim() : null
  const code = codeOf(res)
  if (msg && msg !== code) return msg
  const k = errorKey(code)
  return k ? t(k) : t(fallbackKey)
}

/**
 * Canlı sunucu saati (ms) — `tickMs` aralıkla yeniden çizer (geciken rozetleri yoklama beklemeden belirsin). Saat farkı
 * `clock` (routineModel.serverClock) ile düzeltilir. Görünmeyen sekmede tarayıcı zamanlayıcıyı zaten yavaşlatır.
 */
export function useServerNow(clock, tickMs = 30_000) {
  const [now, setNow] = useState(() => serverNowMs(clock))
  useEffect(() => {
    setNow(serverNowMs(clock))
    const id = setInterval(() => setNow(serverNowMs(clock)), tickMs)
    return () => clearInterval(id)
  }, [clock, tickMs])
  return now
}

/** Kişi adı — tam görünür, sarar. "Siz" çipi isteğe bağlı. */
export function PersonName({ name, isMe = false, className }) {
  const t = useT()
  return (
    <span className={cn('inline-flex min-w-0 flex-wrap items-center gap-1.5', className)}>
      {(name || !isMe) && <span className="min-w-0 [overflow-wrap:anywhere]">{name || t('or.resp.unknownPerson')}</span>}
      {isMe && (
        <Badge variant="secondary" data-slot="or-me-chip" className="h-5 rounded-md px-1.5 text-[11px]">{t('or.me')}</Badge>
      )}
    </span>
  )
}
