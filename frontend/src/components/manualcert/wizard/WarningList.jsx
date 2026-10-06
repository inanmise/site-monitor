import { AlertOctagon, AlertTriangle, Info } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { cn } from '@/lib/utils'
import { severityTone, sortWarnings, warningText } from '../manualCertModel.js'

const ICON = { info: Info, warning: AlertTriangle, danger: AlertOctagon }
const TONE = {
  info: 'border-primary/20 bg-primary/5 text-foreground [&>svg]:text-primary dark:bg-primary/10',
  warning: 'border-amber-500/35 bg-amber-500/10 text-amber-900 [&>svg]:text-amber-600 dark:bg-amber-500/15 dark:text-amber-200 dark:[&>svg]:text-amber-400',
  danger: 'border-destructive/35 bg-destructive/10 text-destructive [&>svg]:text-destructive dark:bg-destructive/20',
}

/**
 * Sunucu uyarı listesi (`[{code, severity, params}]`) — ağır olan önce, her satır ikon + sözlük metni (`mcert.warn.<CODE>`,
 * adlı parametreler doldurulur). Tek tek AlertBanner yerine sıkı liste: bir dosyada beş uyarı beş kutu olmasın.
 * Test kancası: liste `data-slot="mcert-warnings"`, satır `data-slot="mcert-warning"` + `data-code` + `data-severity`.
 */
export default function WarningList({ warnings, className, label }) {
  const t = useT()
  const list = sortWarnings(warnings)
  if (!list.length) return null
  return (
    <ul data-slot="mcert-warnings" aria-label={label} className={cn('m-0 flex list-none flex-col gap-1.5 p-0', className)}>
      {list.map((w, i) => {
        const tone = severityTone(w?.severity)
        const Icon = ICON[tone]
        return (
          <li key={`${w?.code}-${i}`} data-slot="mcert-warning" data-code={w?.code} data-severity={w?.severity || 'info'}
            className={cn('flex min-w-0 items-start gap-2 rounded-md border px-2.5 py-1.5 text-[13px] leading-snug', TONE[tone])}>
            <Icon aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
            <span className="min-w-0 [overflow-wrap:anywhere]">{warningText(t, w)}</span>
          </li>
        )
      })}
    </ul>
  )
}
