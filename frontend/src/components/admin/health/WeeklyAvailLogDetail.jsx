import { useCallback, useEffect, useState } from 'react'
import {
  Mail, CheckCircle2, XCircle, MinusCircle, HelpCircle, FlaskConical, CalendarClock, Copy, ChevronLeft, ChevronRight,
  Monitor, Smartphone, ExternalLink,
} from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { mailPreviewSrcDoc } from '../../../utils/mailPreview.js'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { waKind, waError, isTest, splitAddrs } from './weeklyLogModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

const KIND_META = {
  SENT: { Icon: CheckCircle2, cls: 'border-success/30 bg-success/10 text-success', key: 'health.statusSent' },
  FAILED: { Icon: XCircle, cls: 'border-destructive/30 bg-destructive/10 text-destructive', key: 'health.statusFailed' },
  SKIPPED: { Icon: MinusCircle, cls: 'border-amber-500/30 bg-amber-500/10 text-amber-800 dark:text-amber-300', key: 'waLogs.kind.SKIPPED' },
  UNKNOWN: { Icon: HelpCircle, cls: 'border-border bg-muted text-muted-foreground', key: 'health.statusUnknown' },
}

/**
 * Gönderim durumu rozeti (shadcn Badge): gönderildi · başarısız · alıcısız/atlandı · bilinmiyor; ham durum `title`'da.
 * `short`: tablo / kart satırında kısa etiket ("Alıcısız") — dar sütunda taşmasın.
 */
export function WaStatusBadge({ row, t, className, short = false }) {
  const k = waKind(row?.status)
  const m = KIND_META[k]
  // Etiket sözlükten — anahtarlar burada AÇIKÇA yazılı (i18n kullanılmayan anahtar kapısı dinamik anahtarı göremez)
  const label = { SENT: t('health.statusSent'), FAILED: t('health.statusFailed'),
    SKIPPED: short ? t('waLogs.kind.SKIPPEDShort') : t('waLogs.kind.SKIPPED'), UNKNOWN: t('health.statusUnknown') }[k]
  return (
    <Badge variant="outline" data-slot="wa-status" data-kind={k} title={row?.status || ''}
      className={cn('h-6 gap-1 rounded-md px-2 text-[11px] font-semibold', m.cls, className)}>
      <m.Icon aria-hidden="true" className="size-3" />{label}
    </Badge>
  )
}

/** Gönderim türü rozeti: zamanlanmış (haftalık iş) · test (elle gönderilen deneme). */
export function WaTypeBadge({ row, t }) {
  const test = isTest(row)
  return (
    <Badge variant="outline" data-slot="wa-type" data-type={test ? 'test' : 'scheduled'}
      className={cn('h-5 gap-1 rounded-md px-1.5 text-[10.5px] font-medium',
        test ? 'border-violet-500/30 bg-violet-500/10 text-violet-700 dark:text-violet-300' : 'text-muted-foreground')}>
      {test ? <FlaskConical aria-hidden="true" className="size-3" /> : <CalendarClock aria-hidden="true" className="size-3" />}
      {test ? t('waLogs.test') : t('waLogs.scheduled')}
    </Badge>
  )
}

function Field({ label, children }) {
  return (
    <div className="grid min-w-0 gap-1 sm:grid-cols-[132px_minmax(0,1fr)] sm:items-baseline sm:gap-3">
      <dt className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-sm">{children}</dd>
    </div>
  )
}

function AddressList({ list }) {
  if (!list.length) return <span className="text-muted-foreground">—</span>
  return (
    <span className="flex min-w-0 flex-wrap gap-1.5">
      {list.map((a) => <Badge key={a} variant="outline" className="h-6 max-w-full rounded-md px-2 font-mono text-[11.5px] font-normal"><span className="truncate">{a}</span></Badge>)}
    </span>
  )
}

/**
 * Tek gönderimin ayrıntısı (gönderim logunun ÜSTÜNDE ikinci pencere — ModalShell katmanı; Sheet z-index'i kabın altında
 * kalırdı): konu, durum + tür, hata/atlama nedeni bandı, takım, alıcılar (kopyala), CC, zaman, kayıt no ve e-postanın
 * kendisi (ortak `mailPreviewSrcDoc` — logo + bağlantılar yeni sekmede). Altlıkta önceki / sonraki kayıt (süzülmüş
 * liste sırasıyla) ve konum.
 */
export default function WeeklyAvailLogDetail({ t, id, ids = [], onNavigate, onClose }) {
  const toast = useToast()
  const [state, setState] = useState({ loading: true, error: null, item: null })
  const [view, setView] = useState('desktop')

  const load = useCallback(async () => {
    setState({ loading: true, error: null, item: null })
    try {
      const res = await api.admin.getWeeklyAvailHistoryItem(id)
      if (res?.success && res.data) setState({ loading: false, error: null, item: res.data })
      else setState({ loading: false, error: res?.error || t('waLogs.detail.loadError'), item: null })
    } catch (e) {
      setState({ loading: false, error: e?.message || t('waLogs.detail.loadError'), item: null })
    }
  }, [id, t])
  useEffect(() => { load() }, [load])

  const idx = ids.indexOf(id)
  const prev = idx > 0 ? ids[idx - 1] : null
  const next = idx >= 0 && idx < ids.length - 1 ? ids[idx + 1] : null
  const item = state.item
  const to = splitAddrs(item?.to)
  const cc = splitAddrs(item?.cc)
  const k = waKind(item?.status)

  const copyRecipients = async () => {
    try {
      await navigator.clipboard.writeText([...to, ...cc].join(', '))
      toast.success(t('waLogs.detail.copied'))
    } catch {
      toast.error(t('waLogs.detail.copyFailed'))
    }
  }

  const footer = (
    <div className="flex w-full flex-wrap items-center justify-between gap-2">
      <div className="flex items-center gap-1.5" data-slot="wa-detail-nav">
        <Button type="button" variant="outline" size="sm" disabled={prev == null} onClick={() => onNavigate(prev)}
          aria-label={t('waLogs.detail.prev')} className="pointer-coarse:h-10">
          <ChevronLeft aria-hidden="true" /><span className="hidden sm:inline">{t('waLogs.detail.prev')}</span>
        </Button>
        {idx >= 0 && <span className="px-1 text-xs text-muted-foreground tabular-nums">{t('waLogs.detail.position', idx + 1, ids.length)}</span>}
        <Button type="button" variant="outline" size="sm" disabled={next == null} onClick={() => onNavigate(next)}
          aria-label={t('waLogs.detail.next')} className="pointer-coarse:h-10">
          <span className="hidden sm:inline">{t('waLogs.detail.next')}</span><ChevronRight aria-hidden="true" />
        </Button>
      </div>
      <Button type="button" variant="secondary" onClick={onClose} className="pointer-coarse:h-10">{t('app.close')}</Button>
    </div>
  )

  return (
    <ModalShell open onClose={onClose} title={t('waLogs.detailTitle')} icon={Mail} size="xl" scrollBody closeLabel={t('app.dismiss')} footer={footer}>
      {state.loading ? <LoadingBlock label={t('sys.loading')} fullWidth /> : state.error ? (
        <AlertBanner tone="danger" title={t('waLogs.detail.loadError')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" onClick={load}>{t('waLogs.retry')}</Button>}>
          {state.error}
        </AlertBanner>
      ) : item && (
        <div data-slot="wa-detail" data-kind={k} className="flex min-w-0 flex-col gap-4">
          <div className="flex min-w-0 flex-col gap-2">
            <h3 className="m-0 text-base leading-snug font-semibold break-words">{item.subject || '—'}</h3>
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <WaStatusBadge row={item} t={t} />
              <WaTypeBadge row={item} t={t} />
              <span className="font-mono">{formatDate(item.sent_at)}</span>
              <span>· {t('waLogs.detail.record', item.id)}</span>
            </div>
          </div>

          {k === 'FAILED' && <AlertBanner tone="danger" title={t('waLogs.detail.failed')} className="mb-0"><span className="break-words">{waError(item)}</span></AlertBanner>}
          {k === 'SKIPPED' && (
            <AlertBanner tone="warning" title={t('waLogs.detail.skipped')} className="mb-0">
              {t('waLogs.detail.skippedHint')} <code className="rounded bg-muted px-1 text-[0.85em]">{item.status}</code>
            </AlertBanner>
          )}

          <Card className="gap-0 px-4 py-3.5 shadow-none">
            <dl className="m-0 flex flex-col gap-3">
              <Field label={t('waLogs.colTeam')}><span className="font-semibold break-words">{item.team || '—'}</span></Field>
              <Field label={t('waLogs.colRecipients')}>
                <span className="flex min-w-0 flex-wrap items-center gap-2">
                  <AddressList list={to} />
                  {to.length + cc.length > 0 && (
                    <Button type="button" variant="ghost" size="sm" onClick={copyRecipients} className="h-7 px-2 text-xs pointer-coarse:h-10">
                      <Copy aria-hidden="true" />{t('waLogs.detail.copyTo')}
                    </Button>
                  )}
                </span>
              </Field>
              {cc.length > 0 && <Field label="CC"><AddressList list={cc} /></Field>}
              <Field label={t('waLogs.detail.sentAt')}><span className="font-mono">{formatDate(item.sent_at)}</span></Field>
            </dl>
          </Card>

          <section aria-label={t('waLogs.detail.preview')} className="flex min-w-0 flex-col gap-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h4 className="m-0 text-sm font-semibold">{t('waLogs.detail.preview')}</h4>
              <SegmentedControl value={view} onChange={setView} ariaLabel={t('waLogs.detail.viewLabel')}
                options={[{ value: 'desktop', label: t('waLogs.detail.viewDesktop'), icon: Monitor }, { value: 'phone', label: t('waLogs.detail.viewPhone'), icon: Smartphone }]} />
            </div>
            {item.html ? (
              <>
                <div className="flex justify-center rounded-lg border bg-muted/40 p-1.5 sm:p-3">
                  {/* Ortak yardımcı: cid logo → data: URI, <base target="_blank"> → bağlantılar yeni sekmede (sandbox'tan çıkar) */}
                  <iframe data-slot="wa-preview" data-view={view} title={item.subject || t('waLogs.detail.preview')}
                    className={cn('h-[65vh] min-h-[360px] w-full rounded-md border bg-white', view === 'phone' && 'max-w-[390px]')}
                    srcDoc={mailPreviewSrcDoc(item.html)} sandbox="allow-popups allow-popups-to-escape-sandbox" />
                </div>
                <p className="m-0 flex items-center gap-1.5 text-xs text-muted-foreground"><ExternalLink aria-hidden="true" className="size-3.5" />{t('waLogs.detail.linksHint')}</p>
              </>
            ) : (
              <StatusBlock tone="neutral" icon={Mail} title={t('waLogs.detail.noBody')} className="py-8" />
            )}
          </section>
        </div>
      )}
    </ModalShell>
  )
}
