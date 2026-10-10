import { useId } from 'react'
import { History } from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { fmtDateTime, monthLabel } from './executiveModel.js'

/** Gönderim geçmişi durumu → rozet varyantı. */
export const HIST_VARIANT = {
  SENT: 'outline', PARTIAL: 'warning', FAILED: 'destructive', NO_RECIPIENT: 'warning',
  SKIPPED_DISABLED: 'secondary', SKIPPED_MAIL_OFF: 'warning', SENDING: 'secondary',
}

/** Bilinen durumların i18n anahtarı (literal: used-keys kapısı); bilinmeyen kod ham gösterilir. */
const HIST_KEY = {
  SENT: 'exec.hist.SENT', PARTIAL: 'exec.hist.PARTIAL', FAILED: 'exec.hist.FAILED', NO_RECIPIENT: 'exec.hist.NO_RECIPIENT',
  SKIPPED_DISABLED: 'exec.hist.SKIPPED_DISABLED', SKIPPED_MAIL_OFF: 'exec.hist.SKIPPED_MAIL_OFF', SENDING: 'exec.hist.SENDING',
}

/** Gönderim durumu rozeti (ayrıntı `title`'da). */
export function HistoryBadge({ status, detail, className }) {
  const t = useT()
  if (!status) return null
  return (
    <Badge variant={HIST_VARIANT[status] || 'secondary'} title={detail || undefined} data-slot="ex-hist-status" data-status={status}
      className={cn(status === 'SENT' && 'border-success/40 text-success', className)}>
      {HIST_KEY[status] ? t(HIST_KEY[status]) : status}
    </Badge>
  )
}

/**
 * Gönderim geçmişi — ≥ 640 px tablo, telefonda kart listesi. Satırlar en yeni ay önce (sunucu sırası).
 */
export default function ExecHistory({ rows, slot = 'ex-cfg-history' }) {
  const t = useT()
  const { lang } = useLanguage()
  const id = useId()
  const list = rows || []
  return (
    <section aria-labelledby={id} className="flex min-w-0 flex-col gap-2">
      <h3 id={id} className="m-0 flex items-center gap-2 text-sm font-semibold">
        <History aria-hidden="true" className="size-4 text-primary" />{t('exec.cfg.history')}
      </h3>
      {list.length === 0 ? (
        <p className="m-0 rounded-lg border border-dashed px-3 py-3 text-sm text-muted-foreground">{t('exec.cfg.historyEmpty')}</p>
      ) : (
        <>
          <div className="hidden min-w-0 overflow-x-auto rounded-lg border sm:block" data-slot={slot}>
            <Table className="text-sm">
              <TableHeader className="bg-muted/40">
                <TableRow>
                  <TableHead className="text-xs">{t('exec.cfg.hMonth')}</TableHead>
                  <TableHead className="text-xs">{t('exec.cfg.hStatus')}</TableHead>
                  <TableHead className="text-right text-xs">{t('exec.cfg.hRecipients')}</TableHead>
                  <TableHead className="text-xs">{t('exec.cfg.hAt')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.map((h) => (
                  <TableRow key={h.month} data-slot="ex-cfg-history-row" data-status={h.status}>
                    <TableCell className="whitespace-nowrap">{monthLabel(h.month, lang)}</TableCell>
                    <TableCell><HistoryBadge status={h.status} detail={h.detail} /></TableCell>
                    <TableCell className="text-right tabular-nums">{h.recipients ?? '—'}</TableCell>
                    <TableCell className="whitespace-nowrap tabular-nums">{fmtDateTime(h.sent_at, lang)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <ul className="m-0 flex list-none flex-col gap-2 p-0 sm:hidden" data-slot={`${slot}-cards`}>
            {list.map((h) => (
              <li key={h.month} className="flex min-w-0 flex-col gap-1 rounded-lg border bg-card px-3 py-2.5">
                <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
                  <span className="text-sm font-semibold">{monthLabel(h.month, lang)}</span>
                  <HistoryBadge status={h.status} detail={h.detail} />
                </div>
                <span className="text-xs text-muted-foreground tabular-nums">
                  {t('exec.hist.line', h.recipients ?? '—', fmtDateTime(h.sent_at, lang))}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
