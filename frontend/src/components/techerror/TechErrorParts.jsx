import { Badge } from '@/components/shadcn/badge'
import CopyButton from '../ui/CopyButton.jsx'
import { useT } from '../../i18n/index.jsx'
import { cn } from '@/lib/utils'
import { lastAttemptMeta, monthName, statusMeta } from './techErrorModel.js'

const TONE_CLASS = {
  success: 'bg-green-600/10 text-green-800 dark:bg-green-500/15 dark:text-green-300',
  warning: 'bg-amber-500/15 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  info: 'bg-primary/10 text-primary',
  danger: 'bg-destructive/10 text-destructive',
  muted: 'bg-muted text-muted-foreground',
}

/** "Eylül 2026" — arayüz diline göre (rapor dili değil). */
export function periodLabel(period, t) {
  if (!period?.year || !period?.month) return ''
  return `${t(`te.month.${period.month}`)} ${period.year}`
}

/** Sunucu ay adı ile aynı (PDF dili) — şablon diline göre. */
export function periodLabelIn(period, lang) {
  if (!period?.year || !period?.month) return ''
  return `${monthName(period.month, lang)} ${period.year}`
}

/**
 * Rapor durumu rozeti — durum metinle de yazılır (renk tek başına taşımaz). Durum yoksa "Başlanmadı" (yeşil DEĞİL).
 * Son deneme başarısızsa / kuyruktaysa ikinci rozet.
 */
export function StatusBadge({ status, sendState = null, prefix = null, className }) {
  const t = useT()
  const meta = status ? statusMeta(status) : { tone: 'muted', key: 'te.status.notStarted' }
  const attempt = lastAttemptMeta(sendState)
  return (
    <span className={cn('inline-flex flex-wrap items-center gap-1.5', className)} data-slot="te-status" data-status={status || 'none'}>
      <Badge className={cn('rounded-md overflow-visible whitespace-normal [overflow-wrap:anywhere]', TONE_CLASS[meta.tone])}>
        {prefix ? `${prefix} · ${t(meta.key)}` : t(meta.key)}
      </Badge>
      {attempt && (
        <Badge className={cn('rounded-md overflow-visible whitespace-normal', TONE_CLASS[attempt.tone])} data-slot="te-attempt">
          {t(attempt.key)}
        </Badge>
      )}
    </span>
  )
}

/**
 * Yazının HEMEN bitimine küçük kopyalama düğmesi (2026-10-16, kullanıcı isteği: rapor hazırlanırken bölüm başlıkları,
 * alan ve görsel adları başka araçlara kolayca kopyalansın). Ekranda görünen metni (ay / yıl yer tutucuları çözülmüş)
 * kopyalar. Başlığın / etiketin İÇİNE değil YANINA konur — başlığın, etiketin ve şeklin erişilebilir adı değişmez.
 * Satır içi akar (uzun ad sararsa son kelimenin yanında durur); dokunmatikte 40 px. Boş metinde çizilmez. Metin + düğme
 * kabı `data-slot="te-copy-row"` taşır (e2e kırpılma denetimi bu kabı ölçer — satır içi metnin kendi genişliği 0 okunur).
 * İkon tam `muted-foreground` (opaklık yok): açık temada 3:1 arayüz kontrastı (inceleme 2026-10-16: %70 opaklık 2,7:1).
 * Dokunmatikte negatif dikey pay YOK: 40 px'lik düğme alttaki alanın üst kenarını örtmesin.
 */
export function CopyTextButton({ text, className }) {
  const t = useT()
  const value = String(text ?? '').trim()
  if (!value) return null
  return (
    <span data-slot="te-copy" className={cn('ml-1 inline-flex align-middle print:hidden', className)}>
      <CopyButton value={value} variant="ghost" buttonSize="icon-xs" size={12}
        label={t('te.copy.label', value)} copiedLabel={t('te.copy.done')}
        className="-my-1 text-muted-foreground hover:text-foreground pointer-coarse:my-0 pointer-coarse:size-10" />
    </span>
  )
}

/** Yerel saatle kısa tarih-saat (dd.MM.yyyy HH:mm). */
export function fmtDateTime(iso, lang) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul',
  }).format(d)
}
