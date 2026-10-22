import { useState } from 'react'
import { historyBadge } from '../reportSendStatus.js'
import {
  Activity, AlertOctagon, Bell, CheckCircle2, CircleDashed, HelpCircle, Mail, MinusCircle, Play, Radar, Send, Smartphone, Webhook,
} from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import { useToast } from '../../ui/Toast.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CopyableRef from '../../ui/CopyableRef.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { ItemGroup, Item, ItemTitle } from '@/components/shadcn/item'
import { cn } from '@/lib/utils'
import { relTime } from './healthModel.js'
import { OVERALL_TONE, checkState, checkTone, notifiedKey } from './watchdogModel.js'

const STATE_ICON = {
  problem: AlertOctagon, watch: Activity, ok: CheckCircle2, unknown: HelpCircle, na: MinusCircle, pending: CircleDashed,
}

/**
 * Sistem Sağlığı → Kendi kendini izleme (2026-10-11). Uygulamanın kendi işleyişini dakikada bir ölçen watchdog'un
 * kontrolleri: durum rozeti (sağlıklı / izleniyor / sorun · kritik / ölçülemedi / uygulanmaz / bekliyor), ayrıntı
 * cümlesi (sunucu, dil başlığına göre), ne zamandan beri, son bildirim. Kanal özeti (e-posta alıcısı, push, webhook,
 * hatırlatma), "Şimdi değerlendir" (eylem izni), "Test bildirimi" (global yönetici) ve dış izleyici adresi.
 *
 * Test kancaları: `data-slot="watchdog-section"` (+ `data-status`), `watchdog-check` (+ `data-key`, `data-state`),
 * `watchdog-channels`, `watchdog-public-url`.
 */
/** Test sonucundaki kanal durumları → "e-posta: atlandı · webhook: gönderilemedi · push: —" (arayüz dilinde). */
function channelSummary(data, t) {
  const word = (raw) => (raw ? historyBadge(raw, t).label : '—')
  return [
    `${t('wd.ch.email')}: ${word(data?.email)}`,
    `${t('wd.ch.webhook')}: ${word(data?.webhook)}`,
    `${t('wd.ch.push')}: ${word(data?.push)}`,
  ].join(' · ')
}

export default function WatchdogSection({ t, data, error, canAct, globalAdmin, onChanged }) {
  const toast = useToast()
  const [busy, setBusy] = useState(null)   // 'eval' | 'test'
  if (error && !data) {
    return <AlertBanner tone="danger" role="alert" title={t('wd.loadError')} className="mb-0">{typeof error === 'string' ? error : t('wd.loadErrorBody')}</AlertBanner>
  }
  if (!data) return null
  const checks = Array.isArray(data.checks) ? data.checks : []
  const ch = data.channels || {}
  const publicUrl = `${window.location.origin}/api/public/health/watchdog`

  async function evaluate() {
    setBusy('eval')
    try {
      const res = await api.admin.evaluateWatchdog()
      if (res?.success) {
        const r = res.data?.round || {}
        toast.success(t('wd.evaluated', r.alerts ?? 0, r.recoveries ?? 0))
        onChanged?.(res.data)
      } else toast.error(res?.error || t('wd.actionError'))
    } catch (e) {
      toast.error(e?.message || t('wd.actionError'))
    } finally { setBusy(null) }
  }

  async function sendTest() {
    setBusy('test')
    try {
      const res = await api.admin.testWatchdog()
      if (res?.success) {
        const o = res.data?.outcome
        if (o === 'DELIVERED') toast.success(t('wd.testDelivered'))
        else if (o === 'NO_CHANNEL') toast.error(t('wd.testNoChannel'))
        // UIA-D9 (20.166): hangi kanalın ne olduğunu söyler (eskiden yalnız e-postanın HAM Türkçe durumu basılıyordu — ör.
        // "SKIPPED: alıcı yok" — başarısız olan webhook / push adı geçmiyordu)
        else toast.error(t('wd.testFailedChannels', channelSummary(res.data, t)))
      } else toast.error(res?.error || t('wd.actionError'))
    } catch (e) {
      toast.error(e?.message || t('wd.actionError'))
    } finally { setBusy(null) }
  }

  return (
    <div data-slot="watchdog-section" data-status={data.status} className="@container flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <Radar aria-hidden="true" className="size-4 text-muted-foreground" />
            <ToneBadge tone={OVERALL_TONE[data.status] || 'muted'} className="font-semibold">{t(`wd.status.${data.status}`)}</ToneBadge>
          </div>
          <p className="m-0 text-sm text-muted-foreground">{t('wd.desc', data.interval_seconds ?? 60, data.confirm_checks ?? 2)}</p>
        </div>
        <div className="flex flex-wrap gap-2 sm:justify-end">
          {canAct && data.enabled !== false && (
            <Button type="button" variant="outline" size="sm" onClick={evaluate} disabled={!!busy} aria-busy={busy === 'eval' || undefined}
              className="max-sm:h-10 max-sm:flex-1 pointer-coarse:h-10">
              {busy === 'eval' ? <Spinner size={14} inline decorative /> : <Play aria-hidden="true" />} {t('wd.evaluate')}
            </Button>
          )}
          {globalAdmin && (
            <Button type="button" variant="outline" size="sm" onClick={sendTest} disabled={!!busy} aria-busy={busy === 'test' || undefined}
              className="max-sm:h-10 max-sm:flex-1 pointer-coarse:h-10">
              {busy === 'test' ? <Spinner size={14} inline decorative /> : <Send aria-hidden="true" />} {t('wd.test')}
            </Button>
          )}
        </div>
      </div>

      {data.enabled === false && <AlertBanner tone="warning" title={t('wd.disabledTitle')} className="mb-0">{t('wd.disabledBody')}</AlertBanner>}
      {data.enabled !== false && data.stale && (
        <AlertBanner tone="danger" role="alert" title={t('wd.staleTitle')} className="mb-0">{t('wd.staleBody')}</AlertBanner>
      )}

      <ItemGroup aria-label={t('wd.checks')} className="grid grid-cols-1 gap-2 @3xl:grid-cols-2">
        {checks.map((c) => {
          const state = checkState(c)
          const Icon = STATE_ICON[state] || HelpCircle
          const tone = checkTone(c)
          return (
            <Item key={c.key} role="listitem" variant="outline" data-slot="watchdog-check" data-key={c.key} data-state={state}
              className={cn('min-w-0 flex-col flex-nowrap items-stretch gap-1.5 bg-card p-3', state === 'problem' && 'border-destructive/40')}>
              <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
                <ItemTitle role="heading" aria-level={5} className="flex w-auto min-w-0 items-center gap-1.5 font-semibold [overflow-wrap:anywhere]">
                  <Icon aria-hidden="true" className={cn('size-4 shrink-0', tone === 'danger' ? 'text-destructive' : tone === 'warning'
                    ? 'text-amber-600 dark:text-amber-400' : tone === 'success' ? 'text-success' : 'text-muted-foreground')} />
                  {c.label}
                </ItemTitle>
                <ToneBadge tone={tone}>
                  {state === 'problem' && c.severity === 'CRITICAL' ? t('wd.state.critical') : t(`wd.state.${state}`)}
                </ToneBadge>
              </div>
              <p className="m-0 text-sm [overflow-wrap:anywhere]">{state === 'pending' ? t('wd.pendingText') : c.detail}</p>
              {state === 'problem' && c.problem_text && <p className="m-0 text-xs text-muted-foreground">{c.problem_text}</p>}
              <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
                {c.since && state !== 'pending' && (
                  <span title={formatDateSec(c.since)}>{state === 'problem' ? t('wd.problemSince', relTime(c.since, t)) : t('wd.okSince', relTime(c.since, t))}</span>
                )}
                {c.notified_at && notifiedKey(c.notified_kind) && (
                  <span className="inline-flex items-center gap-1" title={formatDateSec(c.notified_at)}>
                    <Bell aria-hidden="true" className="size-3" /> {t(notifiedKey(c.notified_kind), relTime(c.notified_at, t))}
                  </span>
                )}
              </div>
            </Item>
          )
        })}
      </ItemGroup>

      <div data-slot="watchdog-channels" className="flex flex-wrap items-center gap-1.5 text-xs">
        <span className="mr-1 font-semibold text-muted-foreground">{t('wd.channels')}</span>
        <ToneBadge tone={ch.email_recipients > 0 ? 'info' : 'warning'} className="gap-1"><Mail aria-hidden="true" /> {ch.email_recipients > 0 ? t('wd.chEmail', ch.email_recipients) : t('wd.chEmailNone')}</ToneBadge>
        <ToneBadge tone={ch.push_users > 0 ? 'info' : 'muted'} className="gap-1"><Smartphone aria-hidden="true" /> {ch.push_users > 0 ? t('wd.chPush', ch.push_users) : t('wd.chPushNone')}</ToneBadge>
        <ToneBadge tone={ch.webhook ? 'info' : 'muted'} className="gap-1"><Webhook aria-hidden="true" /> {ch.webhook ? t('wd.chWebhook') : t('wd.chWebhookNone')}</ToneBadge>
        <ToneBadge tone="muted">{ch.reminder_hours > 0 ? t('wd.chReminder', ch.reminder_hours) : t('wd.chReminderOff')}</ToneBadge>
        <ToneBadge tone="muted">{t('wd.chConfirm', ch.confirm_checks ?? 2)}</ToneBadge>
      </div>

      <div data-slot="watchdog-public-url" className="flex min-w-0 flex-col gap-1">
        <span className="text-xs font-semibold text-muted-foreground">{t('wd.publicUrl')}</span>
        <CopyableRef value={publicUrl} buttonClassName="max-sm:size-10 pointer-coarse:size-10" codeClassName="px-2 py-1 text-xs break-all"
          copyLabel={t('wd.copyUrl')} copiedLabel={t('secret.copied')} />
        <span className="text-xs text-muted-foreground">{t('wd.publicUrlHint')}</span>
      </div>
    </div>
  )
}
