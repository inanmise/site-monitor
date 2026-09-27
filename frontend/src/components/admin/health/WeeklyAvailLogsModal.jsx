import { useEffect, useState } from 'react'
import { Mail } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { mailPreviewSrcDoc } from '../../../utils/mailPreview.js'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import ModalShell from '../../ui/ModalShell.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { StatusBadge } from '../SmtpLogView.jsx'
import { MetaRow, SectionLabel } from '../LogViewParts.jsx'
import { TH, TD, DataTable } from '../HealthUi.jsx'
import { LG, MD } from './HealthParts.jsx'

// Haftalık erişilebilirlik gönderim durumunu StatusBadge'in beklediği "kind"e indirger.
function waKind(status) {
  if (!status) return 'UNKNOWN'
  if (status === 'SENT') return 'SENT'
  if (status.startsWith('FAILED')) return 'FAILED'
  return 'SKIPPED' // NO_RECIPIENT vb.
}
const cell = (row, t) => <StatusBadge kind={waKind(row.status)} error={row.status?.startsWith('FAILED') ? row.status : null} t={t} />

/** Haftalık erişilebilirlik gönderim logları (SMTP günlüğüyle aynı yapı) + satır ayrıntısı (mail gövdesi). */
export default function WeeklyAvailLogsModal({ t, onClose }) {
  const [logs, setLogs] = useState(null)
  const [loading, setLoading] = useState(true)
  const [item, setItem] = useState(null)

  useEffect(() => {
    let alive = true
    api.admin.getWeeklyAvailHistory(100, true)
      .then((res) => { if (alive) setLogs(res?.success ? res.data : []) })
      .catch(() => { if (alive) setLogs([]) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])

  async function openItem(row) {
    const res = await api.admin.getWeeklyAvailHistoryItem(row.id)
    if (res?.success) setItem(res.data)
  }

  return (
    <ModalShell open onClose={onClose} title={t('waLogs.title')} icon={Mail} size="xl" scrollBody closeLabel={t('app.dismiss')}>
      {loading ? (
        <LoadingBlock label={t('sys.loading')} />
      ) : (logs?.length ?? 0) === 0 ? (
        <StatusBlock tone="neutral" icon={Mail} title={t('waLogs.empty')} />
      ) : (
        <DataTable testId="wa-logs">
          <TableHeader><TableRow>
            <TableHead className={TH}>{t('health.smtpLogDate')}</TableHead>
            <TableHead className={TH}>{t('waLogs.colTeam')}</TableHead>
            <TableHead className={cn(TH, MD)}>{t('waLogs.colRecipients')}</TableHead>
            <TableHead className={cn(TH, LG)}>{t('waLogs.colType')}</TableHead>
            <TableHead className={TH}>{t('health.smtpLogStatus')}</TableHead>
          </TableRow></TableHeader>
          <TableBody>
            {logs.map((row) => (
              <TableRow key={row.id} tabIndex={0} className="cursor-pointer outline-none focus-visible:bg-muted/60"
                aria-label={t('a11y.openRow', formatDate(row.sent_at))}
                onClick={() => openItem(row)}
                onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); openItem(row) } }}>
                <TableCell className={cn(TD, 'font-mono whitespace-nowrap')}>{formatDate(row.sent_at)}</TableCell>
                <TableCell className={TD}>{row.team || '—'}</TableCell>
                <TableCell className={cn(TD, MD)}>
                  <div className="max-w-[260px] truncate text-xs text-muted-foreground" title={row.to || ''}>{row.to || '—'}</div>
                  {row.cc && <div className="max-w-[260px] truncate text-xs text-muted-foreground" title={row.cc}>CC: {row.cc}</div>}
                </TableCell>
                <TableCell className={cn(TD, 'text-xs', LG)}>{row.trigger === 'WEEKLY_AVAILABILITY_TEST' ? t('waLogs.test') : t('waLogs.scheduled')}</TableCell>
                <TableCell className={TD}>{cell(row, t)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </DataTable>
      )}

      {/* Mail gövdesi (satır detayı) — günlük penceresinin İÇİNDE: üstte açılır. */}
      {item && (
        <ModalShell open onClose={() => setItem(null)} title={t('waLogs.detailTitle')} icon={Mail} size="xl" scrollBody closeLabel={t('app.dismiss')}>
          <div className="flex flex-col gap-2 rounded-lg border bg-muted/40 px-3.5 py-3">
            <MetaRow label={t('health.emailDetailTo')}>
              <span className="min-w-0 break-words"><strong>{item.team}</strong>{item.to && <span className="text-muted-foreground"> &lt;{item.to}&gt;</span>}</span>
            </MetaRow>
            {item.cc && <MetaRow label="CC"><span className="min-w-0 break-words text-muted-foreground">{item.cc}</span></MetaRow>}
            <MetaRow label={t('health.smtpLogSubject')}><span className="min-w-0 font-semibold break-words">{item.subject}</span></MetaRow>
            <MetaRow label={t('health.smtpLogDate')}><span className="font-mono">{formatDate(item.sent_at)}</span></MetaRow>
            <MetaRow label={t('waLogs.colType')}>
              <span className="text-xs">{item.trigger === 'WEEKLY_AVAILABILITY_TEST' ? t('waLogs.test') : t('waLogs.scheduled')}</span>
              {cell(item, t)}
            </MetaRow>
          </div>
          <SectionLabel>{t('health.emailDetailBody')}</SectionLabel>
          {/* Haftalık erişilebilirlik raporu daima "ok" logo varyantıyla gönderilir (varsayılan). */}
          <iframe className="h-[60vh] min-h-[320px] w-full rounded-md border bg-white" srcDoc={mailPreviewSrcDoc(item.html)} sandbox="" title={item.subject} />
        </ModalShell>
      )}
    </ModalShell>
  )
}
