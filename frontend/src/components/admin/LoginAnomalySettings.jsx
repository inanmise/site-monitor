import { useState, useEffect } from 'react'
import { ShieldAlert, Send } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
import Field from '../ui/Field.jsx'
import ToneBadge from './ToneBadge.jsx'
import {
  FIELD_GRID_3, SETTINGS_STACK, helpLabel, MasterToggleCard, SettingsHeader, SettingsSaveBar, SettingsSection, ToggleRow,
} from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Card, CardContent } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'

/**
 * "Login Anomali" — başarısız-login anomali tespiti + sistem-admin e-posta uyarısı yapılandırması.
 * Katmanlı kural eşikleri, alıcılar, cooldown/resolved + "test maili gönder" + son tetiklenen incident'lar.
 * Kalıcılık site.monitor.failed-login.* key'lerine (LoginAnomalyController → AppSettingsService) gider; CANLI.
 */
const NUM_FIELDS = [
  { key: 'threshold_total', min: 1 },
  { key: 'threshold_per_account', min: 1 },
  { key: 'threshold_per_ip', min: 1 },
  { key: 'threshold_distinct_users_per_ip', min: 1 },
  { key: 'threshold_distinct_ips_per_account', min: 1 },
  { key: 'relative_multiplier', min: 1, step: 0.5 },
  { key: 'baseline_hours', min: 1 },
  { key: 'relative_floor', min: 0 },
  { key: 'window_minutes', min: 1 },
  { key: 'cooldown_minutes', min: 1 },
  { key: 'catchup_cap_minutes', min: 1 },
  { key: 'retention_days', min: 7 },
]

export default function LoginAnomalySettings() {
  const t = useT()
  const toast = useToast()

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [form, setForm] = useState(null)
  const [testEmail, setTestEmail] = useState('')
  const [testing, setTesting] = useState(false)
  const [incidents, setIncidents] = useState([])

  useEffect(() => { load(); loadIncidents() }, [])

  async function load() {
    setLoading(true)
    try {
      const res = await api.admin.getLoginAnomalySettings()
      if (res?.success) setForm(res.data)
      else toast.error(res?.error || t('settings.loadError'))
    } finally {
      setLoading(false)
    }
  }

  async function loadIncidents() {
    const res = await api.admin.getLoginAnomalyIncidents(0, 10)
    if (res?.success) setIncidents(res.data || [])
  }

  function set(key, val) { setForm((f) => ({ ...f, [key]: val })) }

  async function save() {
    setSaving(true)
    try {
      const res = await api.admin.saveLoginAnomalySettings(form)
      if (res?.success) { setForm(res.data); toast.success(t('loginAnomaly.saved')) }
      else toast.error(res?.error || res?.message || t('settings.saveError'))
    } finally {
      setSaving(false)
    }
  }

  async function sendTest() {
    if (!testEmail.trim()) { toast.error(t('loginAnomaly.testNeedEmail')); return }
    setTesting(true)
    try {
      const res = await api.admin.testLoginAnomalyEmail(testEmail.trim())
      if (res?.success && res.data?.sent) toast.success(t('loginAnomaly.testSent'))
      else toast.error((res?.data?.status) || res?.error || t('loginAnomaly.testFailed'))
    } finally {
      setTesting(false)
    }
  }

  if (loading || !form) {
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  return (
    <div className={SETTINGS_STACK} data-testid="login-anomaly-settings">
      <SettingsHeader icon={ShieldAlert} title={t('loginAnomaly.title')} description={t('loginAnomaly.desc')} />

      {/* Master toggle */}
      <MasterToggleCard checked={!!form.enabled} onChange={(v) => set('enabled', v)}
        label={t('loginAnomaly.enabled')} helpKey="help.set.site.monitor.failed-login.enabled" hint={t('loginAnomaly.enabledHint')} />

      {/* Recipients */}
      <SettingsSection title={helpLabel(t('loginAnomaly.recipientsTitle'), 'help.set.site.monitor.failed-login.alert-recipients')}>
        <Field hint={t('loginAnomaly.recipientsHint', form.system_admin_email || '—')} className="mb-0 xl:max-w-3xl">
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} aria-label={t('loginAnomaly.recipientsTitle')}
              placeholder={t('loginAnomaly.recipientsPh')}
              value={form.alert_recipients || ''} onChange={(e) => set('alert_recipients', e.target.value)} />
          )}
        </Field>
      </SettingsSection>

      {/* Thresholds — geniş ekranda üç sütun (12 kısa sayısal alan) */}
      <SettingsSection title={t('loginAnomaly.thresholdsTitle')} description={t('loginAnomaly.thresholdsDesc')}>
        <div className={FIELD_GRID_3}>
          {NUM_FIELDS.map((f) => (
            // form alanı adı snake_case, katalog anahtarı kebab-case
            <Field key={f.key} className="mb-3"
              label={helpLabel(t('loginAnomaly.f.' + f.key), 'help.set.site.monitor.failed-login.' + f.key.replace(/_/g, '-'))}>
              {({ id }) => (
                <Input id={id} type="number" min={f.min} step={f.step || 1} value={form[f.key] ?? ''}
                  onChange={(e) => set(f.key, e.target.value === '' ? '' : Number(e.target.value))} />
              )}
            </Field>
          ))}
        </div>
      </SettingsSection>

      {/* Resolved email toggle */}
      <Card className="gap-2 py-4">
        <CardContent className="flex flex-col gap-2 px-4 sm:px-6">
          <ToggleRow checked={!!form.resolved_email_enabled} onChange={(v) => set('resolved_email_enabled', v)}
            label={t('loginAnomaly.resolvedEmail')} helpKey="help.set.site.monitor.failed-login.resolved-email-enabled" />
          <p className="text-xs text-muted-foreground">{t('loginAnomaly.resolvedEmailHint')}</p>
        </CardContent>
      </Card>

      {/* Test email + recent incidents — geniş ekranda yan yana */}
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <SettingsSection title={t('loginAnomaly.testTitle')} description={t('loginAnomaly.testDesc')}>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <Input type="email" aria-label={t('loginAnomaly.testTitle')} placeholder={t('loginAnomaly.testPh')} className="min-w-0 flex-1"
              value={testEmail} onChange={(e) => setTestEmail(e.target.value)} />
            <Button variant="outline" onClick={sendTest} disabled={testing} aria-busy={testing || undefined} className="shrink-0">
              {testing ? <Spinner size={15} inline decorative /> : <Send size={15} />} {t('loginAnomaly.testSend')}
            </Button>
          </div>
        </SettingsSection>

        <SettingsSection title={t('loginAnomaly.recentTitle')}>
          {incidents.length === 0 ? (
            <p className="text-xs text-muted-foreground">{t('loginAnomaly.recentEmpty')}</p>
          ) : (
            <div className="overflow-hidden rounded-lg border">
              <Table data-testid="la-incidents">
                <TableHeader className="bg-muted/50">
                  <TableRow>
                    <TableHead>{t('loginAnomaly.colOpened')}</TableHead>
                    <TableHead>{t('loginAnomaly.colRules')}</TableHead>
                    <TableHead className="text-right">{t('loginAnomaly.colPeak')}</TableHead>
                    <TableHead>{t('loginAnomaly.colStatus')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {incidents.map((it) => (
                    <TableRow key={it.id}>
                      <TableCell className="tabular-nums">{formatDate(it.opened_at)}</TableCell>
                      <TableCell className="min-w-[180px] font-mono text-xs break-all whitespace-normal">{it.rules_signature || '—'}</TableCell>
                      <TableCell className="text-right tabular-nums">{it.peak_total}</TableCell>
                      <TableCell>
                        <ToneBadge tone={it.resolved ? 'success' : 'danger'} className="font-semibold">
                          {it.resolved ? t('loginAnomaly.stResolved') : t('loginAnomaly.stActive')}
                        </ToneBadge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </SettingsSection>
      </div>

      {/* Save */}
      <SettingsSaveBar saving={saving} onSave={save} saveLabel={t('loginAnomaly.save')} />
    </div>
  )
}
