import { CheckCircle2, AlertTriangle, AlertOctagon, HelpCircle } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { cn } from '@/lib/utils'

const ICON = { success: CheckCircle2, warning: AlertTriangle, danger: AlertOctagon }

/**
 * Hüküm şeridi — "Erişilebilir …" / "TLS'te takıldı …": en olası neden + sonraki adım + sertifika notları.
 * Terimlerin (DNS/TCP/CONNECT/TLS) kısa sözlüğü dokunmatikte de açılan `ui/HintPopover` ile.
 * Test kancası: `data-slot="diag-verdict"` + `data-tone` (AlertBanner'ın kendi kancası).
 */
export default function DiagVerdict({ verdict, className }) {
  const t = useT()
  if (!verdict) return null
  const Icon = ICON[verdict.tone] || AlertTriangle
  return (
    <div data-slot="diag-verdict" data-verdict-tone={verdict.tone} className={cn('min-w-0', className)}>
      <AlertBanner tone={verdict.tone} icon={Icon} role="status" title={verdict.title} className="mb-0"
        actions={(
          <HintPopover content={t('diag.verdict.help')} side="bottom" align="end" triggerClassName="text-muted-foreground">
            <span className="inline-flex items-center gap-1 text-xs underline decoration-dotted underline-offset-2">
              <HelpCircle aria-hidden="true" className="size-3.5" />{t('diag.verdict.what')}
            </span>
          </HintPopover>
        )}>
        {verdict.cause && <div>{verdict.cause}</div>}
        {verdict.next && <div className="mt-1 font-medium">{verdict.next}</div>}
        {verdict.notes?.length > 0 && (
          <ul className="mt-1.5 flex flex-col gap-0.5">
            {verdict.notes.map((n, i) => (
              <li key={i} data-note-tone={n.tone} className={cn('text-sm font-medium', n.tone === 'danger' ? 'text-destructive' : 'text-amber-700 dark:text-amber-300')}>{n.text}</li>
            ))}
          </ul>
        )}
      </AlertBanner>
    </div>
  )
}
