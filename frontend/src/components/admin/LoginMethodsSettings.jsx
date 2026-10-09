import { useCallback, useEffect, useState } from 'react'
import { BarChart3, KeyRound, LockKeyhole, Mail, Settings2, ShieldCheck, Smartphone, UserCog } from 'lucide-react'
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
import LoginMethodsCoverage from './loginmethods/LoginMethodsCoverage.jsx'
import PushTemplateEditor from './loginmethods/PushTemplateEditor.jsx'
import { DEFAULT_MESSAGE_MAX, PUSH_TEXT_FIELDS, validatePushTexts } from './loginmethods/pushTemplateModel.js'
import LoginStatsTab from './loginmethods/stats/LoginStatsTab.jsx'
import { enabledChannels } from './loginmethods/stats/loginStatsModel.js'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'

/** Sayısal alanlar → sunucu sınır anahtarı (limits) + yardım anahtarı. Doğrulama sunucuyla AYNI aralıklardan. */
export const NUMERIC_FIELDS = [
  { key: 'push_ttl_seconds', limit: 'ttl' },
  { key: 'email_ttl_seconds', limit: 'ttl' },
  { key: 'max_attempts', limit: 'max_attempts' },
  { key: 'resend_cooldown_seconds', limit: 'resend_cooldown' },
  { key: 'max_requests_per_user', limit: 'max_requests_per_user' },
  { key: 'max_requests_per_ip', limit: 'max_requests_per_ip' },
  { key: 'max_failed_verifications', limit: 'max_failed_verifications' },
  { key: 'max_contact_mismatches', limit: 'max_contact_mismatches' },   // 2026-10-03
]

/** Açık/kapalı alanlar (gönderimde boolean). 2026-10-03: kişi bilgisi doğrulaması anahtarları eklendi. */
export const BOOL_FIELDS = ['ldap_enabled', 'push_enabled', 'email_enabled', 'allow_global_admins',
  'push_require_phone', 'email_require_email']

/** Formdaki sayısal alan geçerli mi → hata haritası (boş = geçerli). Saf; testte doğrudan sınanır. */
export function validateLoginMethods(form, limits, rangeMsg) {
  const errs = {}
  for (const { key, limit } of NUMERIC_FIELDS) {
    if (form?.[key] === undefined) continue   // sunucu bu alanı hiç göndermedi (eski sürüm) — doğrulanmaz, gönderilmez
    const [min, max] = limits?.[limit] || [1, 1000]
    const raw = String(form?.[key] ?? '').trim()
    const n = Number(raw)
    if (raw === '' || !/^[0-9]+$/.test(raw) || n < min || n > max) errs[key] = rangeMsg(min, max)
  }
  return errs
}

/** Push metni alanları (2026-10-03) — metin; sunucu göndermediyse (eski sürüm) gönderilmez. */
export const TEXT_FIELDS = PUSH_TEXT_FIELDS

/**
 * Gönderilecek gövde — sayılar sayı, anahtarlar boolean, metinler kırpılmış dize. Push metinleri YALNIZ değiştiyse gider:
 * push tavanı sonradan düşürüldüyse kayıtlı (artık uzun) metin ilgisiz bir kaydı (ör. LDAP'ı kapatmak) engellemesin —
 * o durum düzenleyicide ayrıca uyarılır.
 */
function payloadOf(form, saved) {
  const out = {}
  for (const k of BOOL_FIELDS) out[k] = !!form[k]
  for (const { key } of NUMERIC_FIELDS) if (form[key] !== undefined) out[key] = Number(String(form[key]).trim())
  for (const [k, v] of Object.entries(changedTexts(form, saved))) out[k] = String(v ?? '').trim()
  return out
}

/** Kayıtlıdan farklı push metni alanları (doğrulama ve gönderim yalnız bunlar için). */
export function changedTexts(form, saved) {
  const out = {}
  for (const k of TEXT_FIELDS) {
    if (form?.[k] === undefined) continue
    if (String(form[k] ?? '').trim() !== String(saved?.[k] ?? '').trim()) out[k] = form[k]
  }
  return out
}

/** Hata haritasında yalnız diğer dilin push metni hatası varsa o dil (düzenleyici sekmesi o dile geçer). */
function pushLangForErrors(errs, current) {
  const has = (l) => !!(errs[`push_title_${l}`] || errs[`push_message_${l}`])
  if (has(current)) return current
  return ['tr', 'en'].find(has) || current
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
 * 2026-10-03: başlığın altında shadcn Tabs "Ayarlar | İstatistikler" (URL `lm_tab=stats`, varsayılan yazılmaz; kaydet
 * çubuğu yalnız ayarlarda; istatistikler → `loginmethods/stats/LoginStatsTab`). Ayarlarda yöntem kartlarının ardından
 * PUSH MESAJI düzenleyicisi (`loginmethods/PushTemplateEditor` — TR/EN başlık + metin, yer tutucular, telefon önizlemesi,
 * kendime test gönder); push metni yalnız DEĞİŞTİYSE gönderilir ve doğrulanır, diğer dildeki hata sekmeyi o dile çevirir.
 *
 * Düzen (mobil-önce): başlık + durum çipleri → uyarılar (LDAP kapalı ve kod yöntemi yok / gizli anahtar) → YÖNTEM
 * KARTLARI (Şifre — her zaman açık; LDAP — anahtar + kapatırken onay; Push ile kod — anahtar + süre, ağ geçidi yoksa
 * kullanılamaz + push ayarlarına bağlantı; E-posta ile kod — anahtar + süre + SMTP durumu; 2026-10-03: her iki kartta
 * "telefon / e-posta da sorulsun" anahtarı + kişi bilgisi KAPSAMI ipucu — aktif kullanıcıların kaçında kayıtlı telefon /
 * e-posta var, %80 altı uyarı tonu) → ORTAK GÜVENLİK KURALLARI (deneme, bekleme, 15 dk sınırları — eşleşmeyen telefon /
 * e-posta sınırı dahil — global yönetici izni) → giriş ekranı ÖNİZLEMESİ (TR/EN, kaydedilmemiş değerlerle) →
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
  // Push metni düzenleyicisinin dil sekmesi — kabukta: doğrulama hatası diğer dildeyse sekme o dile çevrilir (2026-10-03)
  const [pushLang, setPushLang] = useState('tr')
  // Sayfa görünümü (2026-10-03): ayarlar | istatistikler — derin bağlantı ?lm_tab=stats (Kullanıcı/Oturum'dan gelir)
  const [view, setView] = useState(() => (readUrlParam('lm_tab') === 'stats' ? 'stats' : 'settings'))
  useUrlQuerySync({ lm_tab: view === 'stats' ? 'stats' : null })

  const apply = useCallback((d) => {
    setData(d)
    setForm({ ...(d?.settings || {}) })
    setSaved({ ...(d?.settings || {}) })
  }, [])

  // Ağ hatası (request() throw eder) da hata bloğuna düşer — eskiden yakalanmıyor, sayfa sonsuza dek "yükleniyor" kalıyordu.
  const load = useCallback(async () => {
    try {
      const r = await api.loginMethodsAdmin.get()
      if (r?.success && r.data) {
        apply(r.data)
        setError(null)
      } else {
        setError(r?.error || t('lm.err.load'))
      }
    } catch (e) {
      setError(e?.message || t('lm.err.load'))
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

  /** Alan hatalarını uygular; push metni hatası başka dildeyse düzenleyici sekmesi önce o dile geçer (alan görünür olsun). */
  function showErrors(errs) {
    setPushLang((cur) => pushLangForErrors(errs, cur))
    return fe.check(errs)
  }

  async function save() {
    if (!form || saving) return
    const errs = {
      ...validateLoginMethods(form, limits, (min, max) => t('lm.range', min, max)),
      ...validatePushTexts(changedTexts(form, saved), Number(data?.push_template?.message_max) || DEFAULT_MESSAGE_MAX, t),
    }
    if (showErrors(errs)) return
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
      const r = await api.loginMethodsAdmin.save(payloadOf(form, saved))
      if (r?.success && r.data) {
        apply(r.data)
        toast.success(r.message || t('lm.saved'))
      } else if (r?.field) {
        showErrors({ [r.field]: r.error || t('lm.err.save') })
      } else {
        toast.error(r?.error || t('lm.err.save'))
      }
    } catch (e) {
      toast.error(e?.message || t('lm.err.save'))
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

      {/* 2026-10-03: "Ayarlar | İstatistikler" — URL lm_tab=stats (varsayılan yazılmaz); kaydet çubuğu yalnız ayarlarda.
          Radix pasif sekmeyi söker: istatistik isteği yalnız sekme açıkken atılır, form durumu kabukta yaşar. */}
      <Tabs value={view} onValueChange={setView} className="min-w-0 gap-5">
        <TabsList aria-label={t('lm.tabs')} className="h-auto! w-full flex-row! sm:w-fit" data-slot="lm-tabs">
          <TabsTrigger value="settings" data-tab="settings" className="min-h-10 w-auto! flex-1 justify-center! gap-1.5 px-3 sm:min-h-8 sm:flex-none">
            <Settings2 aria-hidden="true" />{t('lm.tab.settings')}
          </TabsTrigger>
          <TabsTrigger value="stats" data-tab="stats" className="min-h-10 w-auto! flex-1 justify-center! gap-1.5 px-3 sm:min-h-8 sm:flex-none">
            <BarChart3 aria-hidden="true" />{t('lm.tab.stats')}
          </TabsTrigger>
        </TabsList>
        <TabsContent value="stats" className="min-w-0">
          <LoginStatsTab methods={enabledChannels(saved, status)} />
        </TabsContent>
        <TabsContent value="settings" className={cn(SETTINGS_STACK, 'mt-0')}>
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
                  {/* 2026-10-03: kişi bilgisi doğrulaması — telefon da sorulsun; kapsam yalnız anahtar açıkken anlamlı */}
                  <div data-slot="lm-require" data-kind="phone" className="flex min-w-0 flex-col gap-1.5 border-t pt-3">
                    <ToggleRow checked={!!form.push_require_phone} onChange={(v) => set('push_require_phone', v)}
                      label={t('lm.push.requirePhone')} helpKey="help.set.site.monitor.login.otp.push.require-phone" touch />
                    <p className="m-0 text-xs text-muted-foreground">{t('lm.push.requirePhoneHint')}</p>
                    {form.push_require_phone && <LoginMethodsCoverage coverage={data.coverage} kind="phone" />}
                  </div>
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
                  {/* 2026-10-03: kişi bilgisi doğrulaması — e-posta da sorulsun; e-posta kodu her durumda kayıtlı adres ister */}
                  <div data-slot="lm-require" data-kind="email" className="flex min-w-0 flex-col gap-1.5 border-t pt-3">
                    <ToggleRow checked={!!form.email_require_email} onChange={(v) => set('email_require_email', v)}
                      label={t('lm.email.requireEmail')} helpKey="help.set.site.monitor.login.otp.email.require-email" touch />
                    <p className="m-0 text-xs text-muted-foreground">{t('lm.email.requireEmailHint')}</p>
                    <LoginMethodsCoverage coverage={data.coverage} kind="email" />
                  </div>
                  {numField('email_ttl_seconds', t('lm.ttl'), 'help.set.site.monitor.login.otp.email.ttl-seconds',
                    { min: ttlRange[0], max: ttlRange[1], hint: t('lm.rangeHint', ttlRange[0], ttlRange[1]) })}
                </MethodCard>
              </div>

              {/* 2026-10-03: kodla giriş push metni (TR / EN) — ağ geçidi yokken de hazırlanabilir; test gönderimi pasif */}
              {form.push_title_tr !== undefined && (
                <PushTemplateEditor form={form} onField={set} fe={fe} template={data.push_template} lang={pushLang}
                  onLangChange={setPushLang} gateway={gateway} ttl={form.push_ttl_seconds} />
              )}

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
                  {numField('max_contact_mismatches', t('lm.maxContactMismatches'), 'help.set.site.monitor.login.otp.max-contact-mismatches',
                    { hint: t('lm.rangeHint', ...(limits.max_contact_mismatches || [1, 20])) })}
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
        </TabsContent>
      </Tabs>
    </div>
  )
}

/** Kirlilik karşılaştırması için tutarlı biçim (sayı alanları metin olarak gelebilir). */
function payloadOfSafe(form) {
  const out = {}
  for (const k of BOOL_FIELDS) out[k] = !!form?.[k]
  for (const { key } of NUMERIC_FIELDS) out[key] = String(form?.[key] ?? '').trim()
  for (const k of TEXT_FIELDS) out[k] = String(form?.[k] ?? '').trim()
  return out
}
