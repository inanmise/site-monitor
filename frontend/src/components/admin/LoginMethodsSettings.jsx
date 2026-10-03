import { useCallback, useEffect, useState } from 'react'
import { KeyRound, LockKeyhole, Mail, ShieldCheck, Smartphone, UserCog } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { FIELD_GRID_3, SETTINGS_STACK, SettingsHeader, SettingsSaveBar, SettingsSection, ToggleRow, helpLabel } from './SettingsControls.jsx'
import LoginMethodsPreview from './loginmethods/LoginMethodsPreview.jsx'
import LoginMethodsActivity from './loginmethods/LoginMethodsActivity.jsx'

/** Sayısal alanlar → sunucu sınır anahtarı (limits) + yardım anahtarı. Doğrulama sunucuyla AYNI aralıklardan. */
export const NUMERIC_FIELDS = [
  { key: 'push_ttl_seconds', limit: 'ttl' },
  { key: 'email_ttl_seconds', limit: 'ttl' },
  { key: 'max_attempts', limit: 'max_attempts' },
  { key: 'resend_cooldown_seconds', limit: 'resend_cooldown' },
  { key: 'max_requests_per_user', limit: 'max_requests_per_user' },
  { key: 'max_requests_per_ip', limit: 'max_requests_per_ip' },
  { key: 'max_failed_verifications', limit: 'max_failed_verifications' },
]

/** Formdaki sayısal alan geçerli mi → hata haritası (boş = geçerli). Saf; testte doğrudan sınanır. */
export function validateLoginMethods(form, limits, rangeMsg) {
  const errs = {}
  for (const { key, limit } of NUMERIC_FIELDS) {
    const [min, max] = limits?.[limit] || [1, 1000]
    const raw = String(form?.[key] ?? '').trim()
    const n = Number(raw)
    if (raw === '' || !/^[0-9]+$/.test(raw) || n < min || n > max) errs[key] = rangeMsg(min, max)
  }
  return errs
}

/** Gönderilecek gövde — sayılar sayı, anahtarlar boolean. */
function payloadOf(form) {
  const out = {}
  for (const k of ['ldap_enabled', 'push_enabled', 'email_enabled', 'allow_global_admins']) out[k] = !!form[k]
  for (const { key } of NUMERIC_FIELDS) out[key] = Number(String(form[key]).trim())
  return out
}

/** Durum rozeti (Açık / Kapalı / Kullanılamıyor) — literal anahtarlar. */
function StateBadge({ state }) {
  const t = useT()
  if (state === 'on') return <Badge variant="secondary" data-state="on">{t('lm.status.on')}</Badge>
  if (state === 'unavailable') return <Badge variant="warning" data-state="unavailable">{t('lm.status.unavailable')}</Badge>
  if (state === 'always') return <Badge variant="secondary" data-state="always">{t('lm.alwaysOn')}</Badge>
  return <Badge variant="outline" data-state="off">{t('lm.status.off')}</Badge>
}

/** Yöntem kartı — simge + başlık + durum rozeti + açıklama + içerik. Sol renk şeridi YOK (durum rozetle). */
function MethodCard({ method, icon: Icon, title, description, state, children }) {
  return (
    <Card data-slot="lm-method" data-method={method} data-status={state} className="min-w-0 gap-3 py-4">
      <CardHeader className="px-4 sm:px-5">
        <div className="flex min-w-0 items-start gap-3">
          <span aria-hidden="true" className="inline-flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Icon className="size-[18px]" />
          </span>
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <CardTitle role="heading" aria-level={4} className="text-base">{title}</CardTitle>
              <StateBadge state={state} />
            </div>
            <CardDescription>{description}</CardDescription>
          </div>
        </div>
      </CardHeader>
      {children && <CardContent className="flex min-w-0 flex-col gap-3 px-4 sm:px-5">{children}</CardContent>}
    </Card>
  )
}

/**
 * Ayarlar → Güvenlik → "Giriş Yöntemleri" (2026-10-02, kullanıcı isteği) — YALNIZ global yönetici (kabuk kapsamlı müdüre
 * "yalnız global yönetici" notu çizer; sunucu her uçta 403; anahtarlar GLOBAL_ONLY).
 *
 * Düzen (mobil-önce): başlık + durum çipleri → uyarılar (LDAP kapalı ve kod yöntemi yok / gizli anahtar) → YÖNTEM
 * KARTLARI (Şifre — her zaman açık; LDAP — anahtar + kapatırken onay; Push ile kod — anahtar + süre, ağ geçidi yoksa
 * kullanılamaz + push ayarlarına bağlantı; E-posta ile kod — anahtar + süre + SMTP durumu) → ORTAK GÜVENLİK KURALLARI
 * (deneme, bekleme, 15 dk sınırları, global yönetici izni) → giriş ekranı ÖNİZLEMESİ (TR/EN, kaydedilmemiş değerlerle) →
 * SON ETKİNLİK (denetimden son 20 olay + Denetim Logu bağlantısı) → yapışkan kaydet çubuğu. Doğrulama alanın altında
 * (useFormErrors); sunucu reddi de alan adıyla döner.
 *
 * Test kancası: `data-testid="loginmethods-settings"`.
 */
export default function LoginMethodsSettings({ onOpenSection }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const fe = useFormErrors()
  const [data, setData] = useState(null)
  const [form, setForm] = useState(null)
  const [saved, setSaved] = useState(null)
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)

  const apply = useCallback((d) => {
    setData(d)
    setForm({ ...(d?.settings || {}) })
    setSaved({ ...(d?.settings || {}) })
  }, [])

  const load = useCallback(async () => {
    const r = await api.loginMethodsAdmin.get()
    if (r?.success && r.data) {
      apply(r.data)
      setError(null)
    } else {
      setError(r?.error || t('lm.err.load'))
    }
  }, [apply, t])
  useEffect(() => { load() }, [load])

  const dirty = !!form && !!saved && JSON.stringify(payloadOfSafe(form)) !== JSON.stringify(payloadOfSafe(saved))
  const limits = data?.limits || {}
  const status = data?.status || {}
  const gateway = !!status.push_gateway_configured
  const smtp = status.smtp || {}

  function set(key, value) {
    setForm((f) => ({ ...f, [key]: value }))
    fe.clear(key)
  }

  async function save() {
    if (!form || saving) return
    const errs = validateLoginMethods(form, limits, (min, max) => t('lm.range', min, max))
    if (fe.check(errs)) return
    if (saved?.ldap_enabled && !form.ldap_enabled) {
      const noCode = !(form.push_enabled && gateway) && !form.email_enabled
      const ok = await showConfirm({
        title: t('lm.ldap.confirmTitle'),
        message: noCode ? `${t('lm.ldap.confirmBody')}\n\n${t('lm.ldap.confirmNoCode')}` : t('lm.ldap.confirmBody'),
        confirmText: t('lm.ldap.confirmOk'),
        cancelText: t('app.cancel'),
        variant: noCode ? 'danger' : 'warning',
      })
      if (!ok) return
    }
    setSaving(true)
    try {
      const r = await api.loginMethodsAdmin.save(payloadOf(form))
      if (r?.success && r.data) {
        apply(r.data)
        toast.success(r.message || t('lm.saved'))
      } else if (r?.field) {
        fe.check({ [r.field]: r.error || t('lm.err.save') })
      } else {
        toast.error(r?.error || t('lm.err.save'))
      }
    } finally {
      setSaving(false)
    }
  }

  const numField = (key, label, helpKey, extra = {}) => (
    <Field label={helpLabel(label, helpKey)} {...fe.fieldProps(key)} hint={extra.hint}>
      {({ id, describedBy, invalid }) => (
        <Input id={id} type="number" inputMode="numeric" className="h-10 w-full sm:max-w-40" value={form?.[key] ?? ''}
          min={extra.min} max={extra.max} aria-describedby={describedBy} aria-invalid={invalid}
          disabled={extra.disabled}
          onChange={(e) => set(key, e.target.value)} />
      )}
    </Field>
  )

  const ttlRange = limits.ttl || [30, 300]
  const pushState = !gateway ? 'unavailable' : form?.push_enabled ? 'on' : 'off'
  const noCodeWithLdapOff = form && !form.ldap_enabled && !(form.push_enabled && gateway) && !form.email_enabled

  return (
    <div className={SETTINGS_STACK} data-testid="loginmethods-settings">
      <SettingsHeader icon={KeyRound} title={t('lm.title')} description={t('lm.desc')} hint={t('lm.hint')}
        meta={form ? (
          <>
            <span data-slot="lm-chip" data-method="ldap">{t('lm.ldap.title')}: {form.ldap_enabled ? t('lm.status.on') : t('lm.status.off')}</span>
            <span aria-hidden="true">·</span>
            <span data-slot="lm-chip" data-method="push">{t('lm.push.title')}: {pushState === 'on' ? t('lm.status.on') : pushState === 'unavailable' ? t('lm.status.unavailable') : t('lm.status.off')}</span>
            <span aria-hidden="true">·</span>
            <span data-slot="lm-chip" data-method="email">{t('lm.email.title')}: {form.email_enabled ? t('lm.status.on') : t('lm.status.off')}</span>
          </>
        ) : null} />

      {!data && !error && <LoadingBlock label={t('app.loading')} />}
      {error && !data && (
        <StatusBlock tone="danger" title={t('lm.err.load')} description={error}
          actions={<Button type="button" variant="outline" onClick={load} className="min-h-10">{t('lm.retry')}</Button>} />
      )}

      {data && form && (
        <>
          {noCodeWithLdapOff && (
            <div data-slot="lm-no-code-warning">
              <AlertBanner tone="danger" title={t('lm.ldap.noCodeTitle')} role="alert" className="mb-0">
                {t('lm.ldap.noCodeBody')}
              </AlertBanner>
            </div>
          )}
          {status.secret_key_ephemeral && (
            <div data-slot="lm-secret-warning">
              <AlertBanner tone="warning" className="mb-0">{t('lm.secretEphemeral')}</AlertBanner>
            </div>
          )}

          <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2">
            <MethodCard method="password" icon={LockKeyhole} title={t('lm.password.title')} description={t('lm.password.desc')}
              state="always" />

            <MethodCard method="ldap" icon={UserCog} title={t('lm.ldap.title')} description={t('lm.ldap.desc')}
              state={form.ldap_enabled ? 'on' : 'off'}>
              <ToggleRow checked={!!form.ldap_enabled} onChange={(v) => set('ldap_enabled', v)} label={t('lm.ldap.toggle')}
                helpKey="help.set.site.monitor.login.ldap-enabled" touch />
              {!status.ldap_integration_enabled && <p className="text-xs text-muted-foreground">{t('lm.ldap.integrationOff')}</p>}
            </MethodCard>

            <MethodCard method="push" icon={Smartphone} title={t('lm.push.title')} description={t('lm.push.desc')} state={pushState}>
              <ToggleRow checked={!!form.push_enabled} onChange={(v) => set('push_enabled', v)} label={t('lm.push.toggle')}
                helpKey="help.set.site.monitor.login.otp.push.enabled" touch disabled={!gateway && !form.push_enabled} />
              {!gateway && (
                <div data-slot="lm-push-no-gateway">
                <AlertBanner tone="warning" className="mb-0"
                  actions={onOpenSection ? (
                    <Button type="button" variant="outline" size="sm" className="min-h-10 sm:min-h-8" onClick={() => onOpenSection('userpush')}>
                      {t('lm.push.openSettings')}
                    </Button>
                  ) : undefined}>
                  {t('lm.push.noGateway')}
                </AlertBanner>
                </div>
              )}
              {numField('push_ttl_seconds', t('lm.ttl'), 'help.set.site.monitor.login.otp.push.ttl-seconds',
                { min: ttlRange[0], max: ttlRange[1], hint: t('lm.rangeHint', ttlRange[0], ttlRange[1]) })}
            </MethodCard>

            <MethodCard method="email" icon={Mail} title={t('lm.email.title')} description={t('lm.email.desc')}
              state={form.email_enabled ? 'on' : 'off'}>
              <ToggleRow checked={!!form.email_enabled} onChange={(v) => set('email_enabled', v)} label={t('lm.email.toggle')}
                helpKey="help.set.site.monitor.login.otp.email.enabled" touch />
              <div className="flex min-w-0 flex-wrap items-center gap-2 text-xs text-muted-foreground" data-slot="lm-smtp">
                <span className="[overflow-wrap:anywhere]">{smtp.configured ? t('lm.email.smtp', smtp.host) : t('lm.email.smtpMissing')}</span>
                {onOpenSection && (
                  <Button type="button" variant="link" size="sm" className="min-h-10 px-0 sm:min-h-8" onClick={() => onOpenSection('smtp')}>
                    {t('lm.email.openSmtp')}
                  </Button>
                )}
              </div>
              {smtp.configured && !smtp.alarm_mail_enabled && (
                <p className="text-xs text-muted-foreground">{t('lm.email.alarmMuted')}</p>
              )}
              {numField('email_ttl_seconds', t('lm.ttl'), 'help.set.site.monitor.login.otp.email.ttl-seconds',
                { min: ttlRange[0], max: ttlRange[1], hint: t('lm.rangeHint', ttlRange[0], ttlRange[1]) })}
            </MethodCard>
          </div>

          <SettingsSection title={<span className="inline-flex items-center gap-2"><ShieldCheck aria-hidden="true" className="size-4" />{t('lm.rules.title')}</span>}
            description={t('lm.rules.desc', limits.window_minutes || 15)} contentClassName="flex min-w-0 flex-col gap-2">
            <div className={cn(FIELD_GRID_3, 'min-w-0')}>
              {numField('max_attempts', t('lm.maxAttempts'), 'help.set.site.monitor.login.otp.max-attempts',
                { hint: t('lm.rangeHint', ...(limits.max_attempts || [1, 10])) })}
              {numField('resend_cooldown_seconds', t('lm.cooldown'), 'help.set.site.monitor.login.otp.resend-cooldown-seconds',
                { hint: t('lm.rangeHint', ...(limits.resend_cooldown || [10, 300])) })}
              {numField('max_requests_per_user', t('lm.perUser'), 'help.set.site.monitor.login.otp.max-requests-per-user',
                { hint: t('lm.rangeHint', ...(limits.max_requests_per_user || [1, 20])) })}
              {numField('max_requests_per_ip', t('lm.perIp'), 'help.set.site.monitor.login.otp.max-requests-per-ip',
                { hint: t('lm.rangeHint', ...(limits.max_requests_per_ip || [1, 500])) })}
              {numField('max_failed_verifications', t('lm.maxFailed'), 'help.set.site.monitor.login.otp.max-failed-verifications',
                { hint: t('lm.rangeHint', ...(limits.max_failed_verifications || [1, 20])) })}
            </div>
            <ToggleRow checked={!!form.allow_global_admins} onChange={(v) => set('allow_global_admins', v)}
              label={t('lm.allowAdmins')} helpKey="help.set.site.monitor.login.otp.allow-global-admins" touch />
            <p className="text-xs text-muted-foreground">{t('lm.allowAdminsHint')}</p>
          </SettingsSection>

          <LoginMethodsPreview form={form} status={status} />
          <LoginMethodsActivity rows={data.activity} types={data.activity_types} />

          <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => { setForm({ ...saved }); fe.reset() }} />
        </>
      )}
    </div>
  )
}

/** Kirlilik karşılaştırması için tutarlı biçim (sayı alanları metin olarak gelebilir). */
function payloadOfSafe(form) {
  const out = {}
  for (const k of ['ldap_enabled', 'push_enabled', 'email_enabled', 'allow_global_admins']) out[k] = !!form?.[k]
  for (const { key } of NUMERIC_FIELDS) out[key] = String(form?.[key] ?? '').trim()
  return out
}
