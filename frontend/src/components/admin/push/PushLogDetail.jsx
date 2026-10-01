import { Webhook, ExternalLink, RotateCcw, Copy, ChevronLeft, ChevronRight, CircleDot, CheckCircle2, XCircle, Clock } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { formatDuration } from '../../../utils/incidentMeta.js'
import TeamBadge from '../../ui/TeamBadge.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { TriggerBadge, LevelBadge, TypeBadge, ErrorClassBadge, ChainList, MUTED_SM } from '../LogViewParts.jsx'
import { PushStatusBadge } from './PushLogRows.jsx'
import { triggerLabel, retryable, gapMs } from './pushLogModel.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'

const PRE = 'm-0 max-h-72 overflow-auto rounded-lg border bg-muted/50 px-3 py-2.5 font-mono text-xs break-words whitespace-pre-wrap'

function Field({ label, children }) {
  return (
    <div className="grid min-w-0 gap-1 sm:grid-cols-[120px_minmax(0,1fr)] sm:items-baseline sm:gap-3">
      <dt className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 flex min-w-0 flex-wrap items-center gap-1.5 text-sm">{children}</dd>
    </div>
  )
}

function CopyBlock({ title, text, t, toast }) {
  const copy = async () => {
    try { await navigator.clipboard.writeText(text); toast.success(t('pl.detail.copied')) } catch { toast.error(t('pl.detail.copyFailed')) }
  }
  return (
    <section aria-label={title} className="flex min-w-0 flex-col gap-1.5">
      <div className="flex items-center justify-between gap-2">
        <h4 className="m-0 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h4>
        <Button type="button" variant="ghost" size="sm" onClick={copy} aria-label={t('pl.detail.copyWhat', title)} className="-mr-2 h-7 px-2 text-xs pointer-coarse:h-10">
          <Copy aria-hidden="true" />{t('pl.detail.copy')}
        </Button>
      </div>
      <pre className={PRE}>{text}</pre>
    </section>
  )
}

/** Teslimat akışı: oluşturuldu → (bekleme) → gönderildi / sonuçlandı ya da kuyrukta. */
function DeliveryFlow({ d, t }) {
  const wait = gapMs(d.created_at, d.sent_at)
  const done = d.kind === 'SENT'
  const failed = d.kind === 'FAILED' || d.kind === 'BLOCKED'
  const end = d.sent_at
    ? { Icon: done ? CheckCircle2 : failed ? XCircle : CircleDot, cls: done ? 'text-success' : failed ? 'text-destructive' : 'text-muted-foreground',
        label: done ? t('pl.detail.sent') : t('pl.detail.finished'), at: d.sent_at }
    : { Icon: Clock, cls: 'text-amber-600 dark:text-amber-400', label: d.kind === 'PENDING' ? t('pl.detail.waiting') : t('pl.detail.noSend'), at: null }
  return (
    <ol data-slot="pl-flow" className="m-0 flex list-none flex-col gap-0 p-0">
      <li className="flex items-start gap-2.5">
        <span className="flex flex-col items-center"><CircleDot aria-hidden="true" className="size-4 text-primary" /><span aria-hidden="true" className="my-0.5 h-5 w-px bg-border" /></span>
        <span className="min-w-0 text-sm"><span className="font-medium">{t('pl.detail.created')}</span> <span className="font-mono text-xs text-muted-foreground">{formatDate(d.created_at)}</span></span>
      </li>
      <li className="flex items-start gap-2.5">
        <end.Icon aria-hidden="true" className={cn('size-4 shrink-0', end.cls)} />
        <span className="min-w-0 text-sm">
          <span className="font-medium">{end.label}</span>
          {end.at && <> <span className="font-mono text-xs text-muted-foreground">{formatDate(end.at)}</span></>}
          {wait != null && <span className="block text-xs text-muted-foreground">{t('pl.detail.wait', formatDuration(wait, t))}</span>}
          {d.attempts > 1 && <span className="block text-xs text-muted-foreground">{t('pl.attempts', d.attempts)}</span>}
        </span>
      </li>
    </ol>
  )
}

/**
 * Push gönderim ayrıntısı (2026-10-01 yeniden tasarım): sağdan açılan shadcn Sheet — liste bağlamı arkada görünür
 * kalır; telefonda tam genişlik. Durum + hata nedeni bandı, teslimat akışı (oluşturuldu → gönderildi, bekleme süresi,
 * deneme), alıcı / takım, izleme / seviye / tetikleyici, kimlikler, aynı toplu isteğin alıcıları, mesaj ve ham yanıt
 * (kopyala). Altlık: Alarmı aç · Yeniden kuyruğa al (yönetici) · önceki / sonraki kayıt (geçerli sayfa sırası).
 */
export default function PushLogDetail({ open, detail, t, onClose, onPick, ids = [], canRequeue, busy, onRequeue, onOpenAlert }) {
  const toast = useToast()
  const id = detail?.id
  const idx = id != null ? ids.indexOf(id) : -1
  const prev = idx > 0 ? ids[idx - 1] : null
  const next = idx >= 0 && idx < ids.length - 1 ? ids[idx + 1] : null
  const d = detail
  const errText = d ? [d.status, d.http_status ? `HTTP ${d.http_status}` : null, d.error].filter(Boolean).join(' · ') : ''
  return (
    <Sheet open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <SheetContent side="right" data-slot="pl-detail" className="w-full gap-0 p-0 sm:max-w-xl lg:max-w-2xl">
        <SheetHeader className="gap-1 border-b pr-12">
          <SheetTitle className="flex items-center gap-2"><Webhook aria-hidden="true" className="size-4 text-primary" />{t('pl.detail')}</SheetTitle>
          <SheetDescription className="truncate">
            {d ? `${d.monitor_name || d.title || '—'} · ${formatDate(d.created_at || d.at)}` : t('sys.loading')}
          </SheetDescription>
          {ids.length > 1 && idx >= 0 && (
            <div className="flex items-center gap-1.5 pt-1" data-slot="pl-detail-nav">
              <Button type="button" variant="outline" size="sm" disabled={prev == null} onClick={() => onPick(prev)} aria-label={t('pl.detail.prev')} className="pointer-coarse:h-10">
                <ChevronLeft aria-hidden="true" /><span className="hidden sm:inline">{t('pl.detail.prev')}</span>
              </Button>
              <span className="px-1 text-xs text-muted-foreground tabular-nums">{t('pl.detail.position', idx + 1, ids.length)}</span>
              <Button type="button" variant="outline" size="sm" disabled={next == null} onClick={() => onPick(next)} aria-label={t('pl.detail.next')} className="pointer-coarse:h-10">
                <span className="hidden sm:inline">{t('pl.detail.next')}</span><ChevronRight aria-hidden="true" />
              </Button>
            </div>
          )}
        </SheetHeader>

        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4">
          {!d ? <LoadingBlock label={t('sys.loading')} fullWidth /> : (
            <>
              <div className="flex flex-wrap items-center gap-2">
                <PushStatusBadge row={d} t={t} compact />
                {d.http_status && <span className={MUTED_SM}>HTTP {d.http_status}</span>}
                {d.error_class && <ErrorClassBadge>{t(`pl.cls.${d.error_class}`)}</ErrorClassBadge>}
              </div>
              {d.kind !== 'SENT' && errText && (
                <AlertBanner tone={d.kind === 'FAILED' || d.kind === 'BLOCKED' ? 'danger' : 'warning'} className="mb-0"
                  title={d.kind === 'BLOCKED' ? t('pl.detail.blockedTitle') : d.kind === 'FAILED' ? t('pl.detail.failedTitle') : t('pl.detail.notSentTitle')}>
                  <code className="text-[0.85em] [overflow-wrap:anywhere]">{errText}</code>
                </AlertBanner>
              )}

              <Card className="gap-3 px-4 py-3.5 shadow-none">
                <DeliveryFlow d={d} t={t} />
              </Card>

              <Card className="gap-0 px-4 py-3.5 shadow-none">
                <dl className="m-0 flex flex-col gap-3">
                  <Field label={t('pl.colUser')}>
                    <UserBadge username={d.username} displayName={d.display_name} inline size="sm" />
                    {d.team_name && <TeamBadge teamId={d.team_id} teamName={d.team_name} />}
                  </Field>
                  <Field label={t('pl.colMonitor')}>
                    <TypeBadge>{d.monitor_type || '—'}</TypeBadge><span className="min-w-0 break-words">{d.monitor_name || '—'}</span>
                    {d.alert_level && <LevelBadge level={d.alert_level} />}
                  </Field>
                  <Field label={t('pl.colTitle')}><span className="font-semibold break-words">{d.title || '—'}</span></Field>
                  <Field label={t('health.emailDetailTrigger')}><TriggerBadge trigger={d.trigger}>{triggerLabel(d.trigger, t)}</TriggerBadge></Field>
                  {(d.notification_id || d.batch_id) && (
                    <Field label={t('pl.colNotificationId')}>
                      <span className="font-mono text-xs break-all">{d.notification_id || '—'}</span>
                      {d.batch_id && <span className="font-mono text-xs break-all text-muted-foreground">· batch {d.batch_id}</span>}
                    </Field>
                  )}
                </dl>
              </Card>

              {(d.batch || []).length > 1 && (
                <section aria-label={t('pl.batchPeers', d.batch.length)} className="flex min-w-0 flex-col gap-1.5">
                  <h4 className="m-0 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{t('pl.batchPeers', d.batch.length)}</h4>
                  <ChainList items={d.batch} currentId={d.id} onPick={onPick} render={(c) => (
                    <>
                      <UserBadge username={c.username} displayName={c.display_name} inline size="sm" />
                      <PushStatusBadge row={c} t={t} compact />
                      {c.http_status && <span className={MUTED_SM}>HTTP {c.http_status}</span>}
                    </>
                  )} />
                </section>
              )}

              <CopyBlock title={t('pl.message')} text={d.message || t('health.emailDetailNoBody')} t={t} toast={toast} />
              {d.raw_response && <CopyBlock title={t('pl.rawResponse')} text={d.raw_response} t={t} toast={toast} />}
            </>
          )}
        </div>

        <SheetFooter className="flex-row flex-wrap justify-end gap-2 border-t">
          {d?.alert_event_id && (
            <Button type="button" variant="outline" onClick={() => onOpenAlert(d.alert_event_id)} className="pointer-coarse:h-10">
              <ExternalLink aria-hidden="true" />{t('sml.openAlert')}
            </Button>
          )}
          {canRequeue && d && retryable(d) && (
            <Button type="button" disabled={busy} onClick={() => onRequeue(d)} className="pointer-coarse:h-10">
              <RotateCcw aria-hidden="true" />{t('pl.requeue')}
            </Button>
          )}
          <Button type="button" variant="secondary" onClick={onClose} className="pointer-coarse:h-10">{t('app.close')}</Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
