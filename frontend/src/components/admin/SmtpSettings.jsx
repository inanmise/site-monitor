import { useState, useEffect } from 'react'
import { Send, PlugZap, Mail } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SecretKeyWarning from './SecretKeyWarning.jsx'
import {
  FIELD_GRID as GRID, FIELD_GRID_3, SETTINGS_STACK, helpLabel, MasterToggleCard, SettingsHeader, SettingsSaveBar,
  SettingsSection, TestResult, ToggleRow,
} from './SettingsControls.jsx'
import { Spinner, LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import ToneBadge from './ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupInput, InputGroupText } from '@/components/shadcn/input-group'

/**
 * SMTP / giden e-posta ayarları. Tam sayfa yeniden tasarım (2026-09-27): SettingsHeader + ana anahtar kartı +
 * konu kartları (bağlantı / gelişmiş / test gönderimi) + alt kayıt çubuğu. Kirli durum kaydedilmiş anlık
 * görüntüyle karşılaştırılarak izlenir (parola kutusuna yazılan her şey kirli sayılır — sunucu parolayı
 * hiç göndermez, ekrandaki tek kaynak kutunun kendisidir).
 */
export default function SmtpSettings() {
  const t = useT()
  const toast = useToast()

  const [form, setForm] = useState(null)
  const [loaded, setLoaded] = useState(null)      // kaydedilmiş hâlin anlık görüntüsü (kirli durum bundan türer)
  const [loadError, setLoadError] = useState(null)
  const [pw, setPw] = useState('')
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [recipient, setRecipient] = useState('')
  const [sending, setSending] = useState(false)
  const [sendResult, setSendResult] = useState(null)
  const [secretKeySet, setSecretKeySet] = useState(true)

  useEffect(() => { load() }, [])

  async function load() {
    // AG HATASI DA GORUNUR OLMALI: api/client.js request() ag hatasinda {success:false}
    // DONDURMEZ, throw eder. try/catch olmadan promise reject oluyor ve ekran sonsuza
    // kadar yukleniyor durumunda kaliyordu (yalnizca konsolda unhandled rejection).
    try {
      const res = await api.admin.getSmtpSettings()
      if (res?.success) {
        setForm({ ...res.data }); setLoaded({ ...res.data })
        setSecretKeySet(res.secret_key_set !== false); setLoadError(null)
      } else {
        const msg = res?.error || t('settings.loadError')
        toast.error(msg); setLoadError(msg)
      }
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
  }

  function set(key, value) {
    setForm((f) => ({ ...f, [key]: value }))
  }
  function num(key, e) {
    set(key, e.target.value === '' ? null : Number(e.target.value))
  }

  async function save() {
    setSaving(true)
    try {
      const dto = { ...form }
      if (pw.trim()) dto.password = pw
      const res = await api.admin.saveSmtpSettings(dto)
      if (res?.success) {
        toast.success(res.message || t('settings.saved'))
        setPw('')
        setForm({ ...res.data }); setLoaded({ ...res.data })
      } else {
        toast.error(res?.error || t('settings.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  /** Vazgeç: kaydedilmiş hâle dön (parola kutusu da boşalır). */
  function discard() {
    if (loaded) setForm({ ...loaded })
    setPw('')
  }

  async function test() {
    setTesting(true)
    try {
      setTestResult(null)
      const res = await api.admin.testSmtp()
      setTestResult(res)
      if (res?.success) toast.success(res.message || t('smtp.testOk'))
      else toast.error(res?.error || t('smtp.testFail'))
    } finally {
      setTesting(false)
    }
  }

  async function sendTest() {
    if (!recipient.trim()) return
    setSending(true)
    try {
      setSendResult(null)
      const res = await api.admin.sendSmtpTest(recipient.trim())
      setSendResult(res)
      if (res?.success) toast.success(res.message || t('smtp.sendOk'))
      else toast.error(res?.error || t('smtp.sendFail'))
    } finally {
      setSending(false)
    }
  }

  if (!form) {
    // Yukleme BASARISIZ olduysa spinner sonsuza kadar donerdi: load() try/catch tasimadigi
    // icin ag hatasinda promise reject oluyor, hicbir durum guncellenmiyordu. Artik ayni
    // yerde hatanin KENDISI gosteriliyor (SystemHealth.jsx:163 loadErrors deseninin esdegeri).
    if (loadError) {
      return <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
    }
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  const hasHost = !!(form.host && form.host.trim())
  const dirty = pw !== '' || (loaded != null && JSON.stringify(form) !== JSON.stringify(loaded))

  // Sayı alanı: boş → null (sunucu varsayılanı), aksi halde Number. `unit` verilirse birim eki (InputGroup).
  const numField = (key, labelKey, helpKey, placeholder, unit) => (
    <Field label={helpLabel(t(labelKey), helpKey)}>
      {({ id, describedBy }) => unit ? (
        <InputGroup>
          <InputGroupInput id={id} aria-describedby={describedBy} type="number" value={form[key] ?? ''}
            placeholder={placeholder} onChange={(e) => num(key, e)} />
          <InputGroupAddon align="inline-end"><InputGroupText>{unit}</InputGroupText></InputGroupAddon>
        </InputGroup>
      ) : (
        <Input id={id} aria-describedby={describedBy} type="number" value={form[key] ?? ''}
          placeholder={placeholder} onChange={(e) => num(key, e)} />
      )}
    </Field>
  )
  const textField = (key, labelKey, helpKey, placeholder, extra = {}) => (
    <Field label={helpLabel(t(labelKey), helpKey)} hint={extra.hint}>
      {({ id, describedBy }) => (
        <Input id={id} aria-describedby={describedBy} type="text" value={form[key] || ''}
          placeholder={placeholder} autoComplete={extra.autoComplete}
          onChange={(e) => set(key, e.target.value)} />
      )}
    </Field>
  )

  return (
    <div className={SETTINGS_STACK} data-testid="smtp-settings">
      <SettingsHeader icon={Mail} title={t('smtp.title')} description={t('smtp.desc')}
        hint={form.updated_at ? t('smtp.lastUpdated', form.updated_at, form.updated_by || '—') : t('smtp.liveHint')}
        meta={<>
          <ToneBadge tone={form.enabled ? 'success' : 'muted'} className="font-semibold">{form.enabled ? t('general.on') : t('general.off')}</ToneBadge>
          {hasHost && <Badge variant="outline" className="max-w-full font-mono font-normal break-all whitespace-normal">{form.host}{form.port ? `:${form.port}` : ''}</Badge>}
        </>}>
        {!secretKeySet && <SecretKeyWarning />}
      </SettingsHeader>

      {/* Master enable */}
      <MasterToggleCard checked={!!form.enabled} onChange={(v) => set('enabled', v)}
        label={t('smtp.enabled')} helpKey="help.smtp.enabled" hint={t('smtp.enabledHint')} />

      {/* Connection / identity — kısa alan çiftleri (sunucu/port, kullanıcı/parola, gönderen adres/ad) */}
      <SettingsSection title={t('smtp.connection')}>
          <div className={GRID}>
            {textField('host', 'smtp.host', 'help.smtp.host', 'smtp.example.com')}
            {numField('port', 'smtp.port', 'help.smtp.port', '587')}
            {textField('username', 'smtp.username', 'help.smtp.username', undefined, { autoComplete: 'off' })}
            <Field label={helpLabel(t('smtp.password'), 'help.smtp.password')} hint={t('smtp.pwHint')}>
              {({ id, describedBy }) => (
                <Input id={id} aria-describedby={describedBy} type="password" value={pw} autoComplete="new-password"
                  placeholder={form.password_set ? t('smtp.pwSet') : t('smtp.pwEmpty')}
                  onChange={(e) => setPw(e.target.value)} />
              )}
            </Field>
            {textField('from_address', 'smtp.fromAddress', 'help.smtp.fromAddress', 'alerts@corp.com')}
            {textField('from_name', 'smtp.fromName', 'help.smtp.fromName', 'SiteMonitor')}
          </div>
          <div className="my-3 flex flex-wrap gap-x-6 gap-y-2.5">
            <ToggleRow checked={!!form.auth_enabled} onChange={(v) => set('auth_enabled', v)}
              label={t('smtp.auth')} helpKey="help.smtp.auth" />
            <ToggleRow checked={!!form.start_tls_enable} onChange={(v) => set('start_tls_enable', v)}
              label={t('smtp.startTls')} helpKey="help.smtp.startTls" />
            <ToggleRow checked={!!form.start_tls_required} onChange={(v) => set('start_tls_required', v)}
              label={t('smtp.startTlsRequired')} helpKey="help.smtp.startTlsRequired" />
          </div>
          {textField('ssl_trust', 'smtp.sslTrust', 'help.smtp.sslTrust', 'smtp.example.com  (veya *)', { hint: t('smtp.sslTrustHint') })}
      </SettingsSection>

      {/* Advanced — zaman aşımları ve gecikmeler (ms), geniş ekranda üç sütun */}
      <SettingsSection title={t('smtp.advanced')} description={t('smtp.advancedDesc')}>
          <div className={FIELD_GRID_3}>
            {numField('connection_timeout_ms', 'smtp.connTimeout', 'help.smtp.connTimeout', undefined, 'ms')}
            {numField('read_timeout_ms', 'smtp.readTimeout', 'help.smtp.readTimeout', undefined, 'ms')}
            {numField('write_timeout_ms', 'smtp.writeTimeout', 'help.smtp.writeTimeout', undefined, 'ms')}
            {numField('retry_delay_ms', 'smtp.retryDelay', 'help.smtp.retryDelay', undefined, 'ms')}
            {numField('inter_contact_delay_ms', 'smtp.interContact', 'help.smtp.interContact', undefined, 'ms')}
            {numField('inter_domain_delay_ms', 'smtp.interDomain', 'help.smtp.interDomain', undefined, 'ms')}
          </div>
      </SettingsSection>

      {/* Send test email */}
      <SettingsSection title={t('smtp.sendTitle')} description={t('smtp.sendDesc')} contentClassName="flex flex-col gap-3">
          <div className="flex flex-col gap-2 sm:max-w-xl sm:flex-row sm:items-stretch">
            <Input type="email" value={recipient} placeholder="recipient@example.com" className="min-w-0 flex-1"
              aria-label={t('smtp.recipient')}
              onChange={(e) => setRecipient(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') sendTest() }} />
            <Button className="shrink-0" onClick={sendTest} disabled={sending || !hasHost || !recipient.trim()} aria-busy={sending || undefined}>
              {sending ? <Spinner size={15} inline decorative /> : <Send size={15} />} {t('smtp.sendBtn')}
            </Button>
          </div>
          {!hasHost && <p className="text-xs text-muted-foreground">{t('smtp.saveFirst')}</p>}
          {sendResult && (
            <AlertBanner tone={sendResult.success ? 'success' : 'danger'} className="mb-0">
              {sendResult.success ? (sendResult.message || t('smtp.sendOk')) : (sendResult.error || t('smtp.sendFail'))}
            </AlertBanner>
          )}
      </SettingsSection>

      {/* Save / test connection — kirliyken alta yapışır */}
      <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={discard}
        result={testResult && (
          <TestResult ok={!!testResult.success}>
            {testResult.success ? (testResult.message || t('smtp.testOk')) : (testResult.error || t('smtp.testFail'))}
          </TestResult>
        )}>
        <Button type="button" variant="outline" onClick={test} disabled={testing || !hasHost} aria-busy={testing || undefined}>
          {testing ? <Spinner size={15} inline decorative /> : <PlugZap size={15} />} {t('smtp.testConnection')}
        </Button>
      </SettingsSaveBar>
    </div>
  )
}
