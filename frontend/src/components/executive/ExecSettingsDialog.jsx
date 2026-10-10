import { useCallback, useEffect, useMemo, useState } from 'react'
import { Settings2, Send, Mail, History, CalendarClock, Target, CircleAlert } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
import { helpLabel, MasterToggleCard, ToggleRow } from '../admin/SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { buildMonthlyCron, fmtDateTime, invalidEmails, monthLabel, parseMonthlyCron } from './executiveModel.js'

const DAYS = Array.from({ length: 28 }, (_, i) => i + 1)
/** Gönderim geçmişi durumu → rozet varyantı. */
const HIST_VARIANT = { SENT: 'outline', PARTIAL: 'warning', FAILED: 'destructive', NO_RECIPIENT: 'warning',
  SKIPPED_DISABLED: 'secondary', SKIPPED_MAIL_OFF: 'warning', SENDING: 'secondary' }

function toForm(d) {
  const cron = parseMonthlyCron(d?.cron)
  return {
    enabled: !!d?.enabled,
    custom: cron.custom,
    cron: d?.cron || '0 0 9 1 * *',
    day: cron.day,
    time: cron.time,
    recipients: d?.recipients || '',
    includeAdmins: d?.include_global_admins !== false,
    target: d?.availability_target != null ? String(d.availability_target) : '99.9',
    renewalDays: d?.renewal_target_days != null ? String(d.renewal_target_days) : '30',
  }
}

function toBody(f) {
  return {
    enabled: f.enabled,
    cron: f.custom ? f.cron.trim() : buildMonthlyCron(f.day, f.time),
    recipients: f.recipients,
    include_global_admins: f.includeAdmins,
    availability_target: String(f.target).replace(',', '.'),
    renewal_target_days: String(f.renewalDays).trim(),
  }
}

/**
 * Yönetici Özeti ayarları + gönderim (yalnız global yönetici) — `ui/ModalShell`. Zamanlanmış gönderim VARSAYILAN KAPALI;
 * alıcılar açık adres listesi ve/veya global yöneticiler; hedefler; test postası YALNIZ yöneticinin kendi adresine;
 * "Şimdi gönder" onaylı; gönderim geçmişi. Alan hataları alanın altında (`useFormErrors`), sunucu 400 `field` ile.
 */
export default function ExecSettingsDialog({ open, onClose, month }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const fe = useFormErrors(open)
  const [state, setState] = useState({ loading: true, error: null, data: null })
  const [form, setForm] = useState(() => toForm(null))
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [running, setRunning] = useState(false)

  const load = useCallback(async () => {
    setState((s) => ({ ...s, loading: true, error: null }))
    try {
      const res = await api.executiveSummary.getSettings()
      if (res?.success && res.data) {
        setState({ loading: false, error: null, data: res.data })
        setForm(toForm(res.data))
      } else {
        setState({ loading: false, error: res?.error || t('exec.cfg.loadError'), data: null })
      }
    } catch (e) {
      setState({ loading: false, error: e?.message || t('exec.cfg.loadError'), data: null })
    }
  }, [t])
  useEffect(() => { if (open) load() }, [open, load])

  const set = (k) => (v) => { setForm((f) => ({ ...f, [k]: v })); fe.clear(k) }
  const data = state.data
  const dirty = useMemo(() => data != null && JSON.stringify(toBody(form)) !== JSON.stringify(toBody(toForm(data))), [form, data])

  function validate() {
    const bad = invalidEmails(form.recipients)
    const target = Number(String(form.target).replace(',', '.'))
    const days = Number(String(form.renewalDays).trim())
    return fe.check({
      recipients: bad.length > 0 && t('exec.cfg.badEmails', bad.slice(0, 3).join(', ')),
      target: (!Number.isFinite(target) || target < 90 || target > 100) && t('exec.cfg.badTarget'),
      renewalDays: (!Number.isInteger(days) || days < 0 || days > 365) && t('exec.cfg.badDays'),
      cron: form.custom && !form.cron.trim() && t('exec.cfg.badCron'),
    })
  }

  async function save() {
    if (validate()) return
    setSaving(true)
    try {
      const res = await api.executiveSummary.saveSettings(toBody(form))
      if (res?.success) {
        setState({ loading: false, error: null, data: res.data })
        setForm(toForm(res.data))
        toast.success(t('exec.cfg.saved'))
      } else if (res?.field) {
        const map = { recipients: 'recipients', availability_target: 'target', renewal_target_days: 'renewalDays', cron: 'cron' }
        fe.check({ [map[res.field] || res.field]: res.error || t('exec.cfg.saveFailed') })
      } else {
        toast.error(res?.error || t('exec.cfg.saveFailed'))
      }
    } catch (e) {
      toast.error(e?.message || t('exec.cfg.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  async function sendTest() {
    setTesting(true)
    try {
      const res = await api.executiveSummary.sendTest(month)
      if (res?.success) toast.success(res.message || t('exec.cfg.testSent', res?.data?.email || ''))
      else toast.error(res?.error || t('exec.cfg.testFailed'))
    } catch (e) {
      toast.error(e?.message || t('exec.cfg.testFailed'))
    } finally {
      setTesting(false)
    }
  }

  async function runNow() {
    const ok = await showConfirm({
      title: t('exec.cfg.runTitle'),
      message: t('exec.cfg.runMessage', monthLabel(month, lang), data?.recipient_count ?? 0),
      variant: 'warning',
      confirmText: t('exec.cfg.runConfirm'),
      cancelText: t('exec.cfg.cancel'),
    })
    if (!ok) return
    setRunning(true)
    try {
      const res = await api.executiveSummary.runNow(month)
      if (res?.success) toast.success(res.message || t('exec.cfg.runDone'))
      else toast.error(res?.error || t('exec.cfg.runFailed'))
      load()
    } catch (e) {
      toast.error(e?.message || t('exec.cfg.runFailed'))
    } finally {
      setRunning(false)
    }
  }

  const busy = saving || testing || running
  const footer = data ? (
    <div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
      <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
        <Button type="button" variant="outline" onClick={sendTest} disabled={busy} aria-busy={testing || undefined}
          data-slot="ex-cfg-test" className="h-10 lg:h-9">
          {testing ? <Spinner decorative size={14} /> : <Mail aria-hidden="true" />}{t('exec.cfg.sendTest')}
        </Button>
        <Button type="button" variant="outline" onClick={runNow} disabled={busy || dirty} aria-busy={running || undefined}
          data-slot="ex-cfg-run" className="h-10 lg:h-9">
          {running ? <Spinner decorative size={14} /> : <Send aria-hidden="true" />}{t('exec.cfg.runNow')}
        </Button>
      </div>
      <Button type="button" onClick={save} disabled={busy || !dirty} aria-busy={saving || undefined}
        data-slot="ex-cfg-save" className="h-10 lg:h-9">
        {saving && <Spinner decorative size={14} />}{t('exec.cfg.save')}
      </Button>
    </div>
  ) : null

  return (
    <ModalShell open={open} onClose={onClose} title={t('exec.cfg.title')} icon={Settings2} size="lg" scrollBody
      busy={busy} dismissOnBackdrop={false} closeLabel={t('exec.cfg.close')} footer={footer}>
      <div data-slot="ex-settings" className="flex min-w-0 flex-col gap-4">
        {state.loading && !data && <LoadingBlock label={t('exec.cfg.loading')} />}
        {state.error && !data && (
          <StatusBlock tone="danger" icon={CircleAlert} title={t('exec.cfg.loadError')} description={state.error}
            actions={<Button type="button" variant="outline" onClick={load} className="h-10 lg:h-9">{t('exec.retry')}</Button>} />
        )}
        {data && (
          <>
            <p className="m-0 text-sm text-muted-foreground">{t('exec.cfg.intro')}</p>
            <MasterToggleCard checked={form.enabled} onChange={set('enabled')} touch
              label={t('exec.cfg.enabled')} helpKey="help.set.site.monitor.executive-summary.enabled"
              hint={form.enabled ? t('exec.cfg.enabledOn') : t('exec.cfg.enabledOff')} />

            <section aria-labelledby="ex-cfg-schedule" className="flex min-w-0 flex-col gap-1 rounded-lg border p-3 sm:p-4">
              <h3 id="ex-cfg-schedule" className="m-0 mb-2 flex items-center gap-2 text-sm font-semibold">
                <CalendarClock aria-hidden="true" className="size-4 text-primary" />{t('exec.cfg.schedule')}
              </h3>
              {form.custom ? (
                <Field label={helpLabel(t('exec.cfg.cron'), 'help.set.site.monitor.executive-summary.cron')}
                  hint={t('exec.cfg.cronHint')} {...fe.fieldProps('cron')}>
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.cron}
                      onChange={(e) => set('cron')(e.target.value)} className="font-mono" />
                  )}
                </Field>
              ) : (
                <div className="grid grid-cols-1 gap-x-3 sm:grid-cols-2">
                  <Field label={t('exec.cfg.day')} hint={t('exec.cfg.dayHint')}>
                    {({ id, describedBy }) => (
                      <NativeSelect id={id} aria-describedby={describedBy} value={String(form.day)} className="w-full"
                        onChange={(e) => set('day')(Number(e.target.value))}>
                        {DAYS.map((d) => <NativeSelectOption key={d} value={String(d)}>{t('exec.cfg.dayOption', d)}</NativeSelectOption>)}
                      </NativeSelect>
                    )}
                  </Field>
                  <Field label={t('exec.cfg.time')} hint={t('exec.cfg.timeHint')}>
                    {({ id, describedBy }) => (
                      <Input id={id} type="time" aria-describedby={describedBy} value={form.time}
                        onChange={(e) => set('time')(e.target.value)} />
                    )}
                  </Field>
                </div>
              )}
              <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <span>{t('exec.cfg.nextRuns')}</span>
                {(data.next_runs || []).map((r) => (
                  <Badge key={r} variant="outline" className="font-normal tabular-nums">{fmtDateTime(r, lang)}</Badge>
                ))}
                <Button type="button" variant="link" size="sm" className="h-auto min-h-10 px-0 lg:min-h-0"
                  onClick={() => setForm((f) => ({ ...f, custom: !f.custom, cron: f.custom ? f.cron : buildMonthlyCron(f.day, f.time) }))}>
                  {form.custom ? t('exec.cfg.simpleSchedule') : t('exec.cfg.customSchedule')}
                </Button>
              </div>
            </section>

            <section aria-labelledby="ex-cfg-recipients" className="flex min-w-0 flex-col gap-1 rounded-lg border p-3 sm:p-4">
              <h3 id="ex-cfg-recipients" className="m-0 mb-2 flex items-center gap-2 text-sm font-semibold">
                <Mail aria-hidden="true" className="size-4 text-primary" />{t('exec.cfg.recipientsTitle')}
              </h3>
              <Field label={helpLabel(t('exec.cfg.recipients'), 'help.set.site.monitor.executive-summary.recipients')}
                hint={t('exec.cfg.recipientsHint')} {...fe.fieldProps('recipients')}>
                {({ id, describedBy, invalid }) => (
                  <Textarea id={id} rows={3} aria-describedby={describedBy} aria-invalid={invalid} value={form.recipients}
                    placeholder="yonetim@example.com, cto@example.com" onChange={(e) => set('recipients')(e.target.value)} />
                )}
              </Field>
              <ToggleRow checked={form.includeAdmins} onChange={set('includeAdmins')} touch
                label={t('exec.cfg.includeAdmins')} helpKey="help.set.site.monitor.executive-summary.include-global-admins" />
              <p data-slot="ex-cfg-preview" className="m-0 mt-2 text-xs text-muted-foreground">
                {t('exec.cfg.preview', data.recipient_count ?? 0, data.recipient_explicit ?? 0, data.recipient_admins ?? 0, data.bcc_chunk ?? 100)}
              </p>
              {(data.recipient_dropped_inactive ?? 0) > 0 && (
                <AlertBanner tone="warning" className="mt-2">{t('exec.cfg.droppedInactive', data.recipient_dropped_inactive)}</AlertBanner>
              )}
              {dirty && <p className="m-0 text-xs text-muted-foreground">{t('exec.cfg.previewStale')}</p>}
            </section>

            <section aria-labelledby="ex-cfg-targets" className="flex min-w-0 flex-col gap-1 rounded-lg border p-3 sm:p-4">
              <h3 id="ex-cfg-targets" className="m-0 mb-2 flex items-center gap-2 text-sm font-semibold">
                <Target aria-hidden="true" className="size-4 text-primary" />{t('exec.cfg.targets')}
              </h3>
              <div className="grid grid-cols-1 gap-x-3 sm:grid-cols-2">
                <Field label={helpLabel(t('exec.cfg.target'), 'help.set.site.monitor.executive-summary.availability-target')}
                  hint={t('exec.cfg.targetHint')} {...fe.fieldProps('target')}>
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} inputMode="decimal" aria-describedby={describedBy} aria-invalid={invalid} value={form.target}
                      onChange={(e) => set('target')(e.target.value)} />
                  )}
                </Field>
                <Field label={helpLabel(t('exec.cfg.renewalDays'), 'help.set.site.monitor.executive-summary.renewal-target-days')}
                  hint={t('exec.cfg.renewalDaysHint')} {...fe.fieldProps('renewalDays')}>
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} inputMode="numeric" aria-describedby={describedBy} aria-invalid={invalid} value={form.renewalDays}
                      onChange={(e) => set('renewalDays')(e.target.value)} />
                  )}
                </Field>
              </div>
            </section>

            <section aria-labelledby="ex-cfg-history" className="flex min-w-0 flex-col gap-2">
              <h3 id="ex-cfg-history" className="m-0 flex items-center gap-2 text-sm font-semibold">
                <History aria-hidden="true" className="size-4 text-primary" />{t('exec.cfg.history')}
              </h3>
              {(data.history || []).length === 0 ? (
                <p className="m-0 rounded-lg border border-dashed px-3 py-3 text-sm text-muted-foreground">{t('exec.cfg.historyEmpty')}</p>
              ) : (
                <div className="min-w-0 overflow-x-auto rounded-lg border" data-slot="ex-cfg-history">
                  <Table className="text-sm">
                    <TableHeader>
                      <TableRow>
                        <TableHead>{t('exec.cfg.hMonth')}</TableHead>
                        <TableHead>{t('exec.cfg.hStatus')}</TableHead>
                        <TableHead className="text-right">{t('exec.cfg.hRecipients')}</TableHead>
                        <TableHead>{t('exec.cfg.hAt')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {data.history.map((h) => (
                        <TableRow key={h.month} data-slot="ex-cfg-history-row" data-status={h.status}>
                          <TableCell className="whitespace-nowrap">{monthLabel(h.month, lang)}</TableCell>
                          <TableCell>
                            <Badge variant={HIST_VARIANT[h.status] || 'secondary'} title={h.detail || undefined}
                              className={cn(h.status === 'SENT' && 'border-success/40 text-success')}>
                              {t(`exec.hist.${h.status}`) === `exec.hist.${h.status}` ? h.status : t(`exec.hist.${h.status}`)}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-right tabular-nums">{h.recipients ?? '—'}</TableCell>
                          <TableCell className="whitespace-nowrap tabular-nums">{fmtDateTime(h.sent_at, lang)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
              <p className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
                <Send aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('exec.cfg.testHint')}
              </p>
            </section>
          </>
        )}
      </div>
    </ModalShell>
  )
}
