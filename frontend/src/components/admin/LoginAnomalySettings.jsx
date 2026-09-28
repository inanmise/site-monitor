import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  Archive, Gauge, ListChecks, Lock, Mail, MailCheck, Network, RefreshCw, ShieldAlert, Timer, TrendingUp, Users,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import HelpTip from '../ui/HelpTip.jsx'
import EmailChipsInput from '../noc/EmailChipsInput.jsx'
import ToneBadge from './ToneBadge.jsx'
import {
  SETTINGS_STACK, helpLabel, MasterToggleCard, SettingsHeader, SettingsSaveBar, SettingsSection,
} from './SettingsControls.jsx'
import LaIncidents, { useLaIncidents } from './loginanomaly/LaIncidents.jsx'
import LaTestMail from './loginanomaly/LaTestMail.jsx'
import { NumberField, errorText, richText, warnText } from './loginanomaly/laUi.jsx'
import {
  ABSOLUTE_KEYS, RELATIVE_KEYS, TIMING_KEYS, changedKeys, defaultTestRecipient, effectiveRecipients, fieldWarnings,
  fmtMinutes, incidentStats, retentionShrinks, toForm, toPayload, validateField, validateForm,
} from './loginanomaly/laModel.js'
import { relativeTime } from './audit/auditFormat.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Label } from '@/components/shadcn/label'
import { Separator } from '@/components/shadcn/separator'
import { Switch } from '@/components/shadcn/switch'
import { cn } from '@/lib/utils'

/**
 * Ayarlar → Güvenlik → **Başarısız Login Anomali Uyarısı** (`?sec=loginanomaly`; 2026-09-28 tam yeniden tasarım —
 * shadcn, mobil duyarlı).
 *
 * <p>Akış: başlık (+ Test e-postası) → ana anahtar kartı (canlı durum satırı "Açık · son 30 günde N olay · son olay
 * X önce" + alıcı özeti / alıcı yok uyarısı) → CANLI kural özeti (alanlar değiştikçe düz Türkçe/İngilizce cümle) →
 * kural aileleri (Sabit eşikler · Görece sıçrama · Zamanlama ve bildirim) → alıcı çipleri + kayıt saklama → son
 * olaylar → yapışkan kaydet çubuğu ("N kaydedilmemiş değişiklik" · Vazgeç · Kaydet, satır içi sunucu hatası).
 *
 * <p>Her alan: etiket + yardım (`help.set.site.monitor.failed-login.*`, settings-help-coverage) + birim + ipucu +
 * satır içi doğrulama (sunucu sınırlarıyla aynı). Alanlar-arası UYARILAR kaydı engellemez. Geçersiz alan varken
 * Kaydet kapalı ve kaç alanın düzeltileceği çubukta yazar. Kaydedilmemiş değişiklikle sekmeyi kapatmak/yenilemek
 * tarayıcı uyarısı alır (PermissionMatrix deseni; Ayarlar kabuğunun bölüm değişiminde koruması yok).
 *
 * <p>`retentionReadOnly` (kapsamlı müdür — AD ADMIN, global değil; 2026-09-28 kararı): `failed-login.retention-days`
 * GLOBAL_ONLY. Sayfa kaydedilebilir; saklama alanı kilitli + kısa açıklama, gövdede HEP yüklenen değer gider
 * (sunucu değişmeyen değeri yok sayar, değişeni 403'ler). Saklama KISALIRSA uyarı + kayıtta onay (çözülmüş eski
 * olaylar bir sonraki temizlikte kalıcı silinir). Sol renk şeridi YOK — durum rozetle.
 */
export default function LoginAnomalySettings({ retentionReadOnly = false }) {
  const t = useT()
  const toast = useToast()
  const toastRef = useRef(toast)
  toastRef.current = toast
  const { showConfirm } = useDialog()
  const resolvedId = useId()

  const [form, setForm] = useState(null)
  const [loaded, setLoaded] = useState(null)       // kaydedilmiş anlık görüntü — kirli durum bundan
  const [loadError, setLoadError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const seq = useRef(0)
  const alive = useRef(true)
  const incidents = useLaIncidents(10)

  const load = useCallback(async () => {
    const id = ++seq.current
    setLoading(true)
    try {
      const res = await api.admin.getLoginAnomalySettings()
      if (!alive.current || id !== seq.current) return
      if (res?.success) {
        const f = toForm(res.data)
        setForm(f); setLoaded(f); setLoadError(null)
      } else {
        setLoadError(res?.error || res?.message || t('settings.loadError'))
      }
    } catch (e) {
      if (alive.current && id === seq.current) setLoadError(e?.message || t('settings.loadError'))
    } finally {
      if (alive.current && id === seq.current) setLoading(false)
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    alive.current = true
    load()
    return () => { alive.current = false }
  }, [load])

  const dirtyKeys = useMemo(() => changedKeys(form, loaded), [form, loaded])
  const dirty = dirtyKeys.length > 0
  const errors = useMemo(() => validateForm(form, { retentionReadOnly }), [form, retentionReadOnly])
  const errorCount = Object.keys(errors).length
  const warnings = useMemo(() => fieldWarnings(form), [form])
  const shrink = !retentionReadOnly && retentionShrinks(form, loaded)

  // Kaydedilmemiş değişiklikle sekmeyi kapatma / yenileme → tarayıcı uyarısı
  useEffect(() => {
    if (!dirty) return undefined
    const warn = (e) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const setField = useCallback((key, value) => {
    setForm((f) => ({ ...f, [key]: value }))
    setSaveError(null)
  }, [])

  function discard() {
    setForm(loaded)
    setSaveError(null)
  }

  async function save() {
    if (!form || errorCount > 0) return
    if (shrink) {
      const ok = await showConfirm({
        title: t('loginAnomaly.retentionShrinkTitle'), variant: 'warning', confirmText: t('loginAnomaly.retentionShrinkConfirm'),
        message: t('loginAnomaly.retentionShrinkBody', loaded.retention_days, String(form.retention_days).trim()),
      })
      if (!ok) return
    }
    setSaveError(null)
    setSaving(true)
    try {
      const res = await api.admin.saveLoginAnomalySettings(toPayload(form, loaded, { retentionReadOnly }))
      if (res?.success) {
        const next = toForm(res.data)
        setForm(next); setLoaded(next)
        toastRef.current.success(t('loginAnomaly.saved'))
      } else {
        setSaveError(res?.error || res?.message || t('settings.saveError'))
      }
    } catch (e) {
      setSaveError(e?.message || t('settings.saveError'))
    } finally {
      setSaving(false)
    }
  }

  const header = (
    <SettingsHeader icon={ShieldAlert} title={t('loginAnomaly.title')} description={t('loginAnomaly.desc')}
      actions={form ? <LaTestMail defaultRecipient={defaultTestRecipient(form)} /> : null} />
  )

  if (!form) {
    if (loadError && !loading) {
      return (
        <div className={SETTINGS_STACK} data-testid="login-anomaly-settings">
          {header}
          <AlertBanner tone="danger" role="alert" title={t('settings.loadError')} className="mb-0"
            actions={(
              <Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={load}>
                <RefreshCw aria-hidden="true" />{t('loginAnomaly.retry')}
              </Button>
            )}>
            {String(loadError)}
          </AlertBanner>
        </div>
      )
    }
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  const numProps = (key) => ({
    t, name: key, value: form[key], onChange: setField,
    error: errorText(t, errors[key]), warning: warnText(t, warnings[key]),
    windowMinutes: validateField('window_minutes', form.window_minutes) ? null : String(form.window_minutes).trim(),
  })
  const fieldGrid = 'grid grid-cols-1 gap-x-4 gap-y-4 @md:grid-cols-2'

  return (
    <div className={cn(SETTINGS_STACK, 'pb-2')} data-testid="login-anomaly-settings" data-dirty={dirty ? 'true' : undefined}>
      {header}

      {/* ── Ana anahtar + canlı durum + alıcı özeti ── */}
      <MasterToggleCard touch checked={!!form.enabled} onChange={(v) => setField('enabled', v)}
        label={t('loginAnomaly.enabled')} helpKey="help.set.site.monitor.failed-login.enabled" hint={t('loginAnomaly.enabledHint2')}>
        <StatusLine t={t} enabled={!!form.enabled} incidents={incidents} />
        <Separator className="my-1" />
        <RecipientsSummary t={t} form={form} />
      </MasterToggleCard>

      {/* ── Canlı kural özeti ── */}
      <RuleSummary t={t} form={form} dirty={dirty} />

      {/* ── Sabit eşikler ── */}
      <SettingsSection title={<SectionTitle icon={Gauge} text={t('loginAnomaly.sec.absolute')} />}
        description={t('loginAnomaly.sec.absoluteDesc')} contentClassName="@container">
        <div className={cn(fieldGrid, '@3xl:grid-cols-3')} data-slot="la-absolute">
          {ABSOLUTE_KEYS.map((k) => <NumberField key={k} {...numProps(k)} />)}
        </div>
      </SettingsSection>

      {/* ── Görece sıçrama + Zamanlama ── geniş ekranda yan yana */}
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <SettingsSection title={<SectionTitle icon={TrendingUp} text={t('loginAnomaly.sec.relative')} />}
          description={t('loginAnomaly.sec.relativeDesc')} contentClassName="@container">
          <div className={fieldGrid} data-slot="la-relative">
            {RELATIVE_KEYS.map((k) => <NumberField key={k} {...numProps(k)} />)}
          </div>
        </SettingsSection>

        <SettingsSection title={<SectionTitle icon={Timer} text={t('loginAnomaly.sec.timing')} />}
          description={t('loginAnomaly.sec.timingDesc')} contentClassName="@container flex flex-col gap-4">
          <div className={fieldGrid} data-slot="la-timing">
            {TIMING_KEYS.map((k) => <NumberField key={k} {...numProps(k)} />)}
          </div>
          <Label htmlFor={resolvedId} data-slot="la-switch-row"
            className="flex min-h-12 w-full cursor-pointer items-center gap-3 rounded-lg border px-3 py-2.5 font-normal">
            <MailCheck aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            <span className="flex min-w-0 flex-1 flex-col gap-1">
              <span className="inline-flex items-center text-sm leading-snug font-medium">
                <span id={`${resolvedId}-t`}>{t('loginAnomaly.resolvedEmail')}</span>
                <HelpTip helpKey="help.set.site.monitor.failed-login.resolved-email-enabled" label={t('loginAnomaly.resolvedEmail')} />
              </span>
              <span id={`${resolvedId}-h`} className="text-xs leading-normal text-muted-foreground">{t('loginAnomaly.resolvedEmailHint')}</span>
            </span>
            <Switch id={resolvedId} checked={!!form.resolved_email_enabled} aria-labelledby={`${resolvedId}-t`}
              aria-describedby={`${resolvedId}-h`} onCheckedChange={(v) => setField('resolved_email_enabled', v)} />
          </Label>
        </SettingsSection>
      </div>

      {/* ── Alıcılar + Kayıt saklama ── */}
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <SettingsSection title={<SectionTitle icon={Users} text={helpLabel(t('loginAnomaly.recipientsTitle'), 'help.set.site.monitor.failed-login.alert-recipients')} />}
          description={t('loginAnomaly.sec.recipientsDesc')}>
          <RecipientsField t={t} form={form} error={errorText(t, errors.recipients)} onChange={(v) => setField('recipients', v)} />
        </SettingsSection>

        <SettingsSection
          title={(
            <span className="inline-flex flex-wrap items-center gap-2">
              <SectionTitle icon={Archive} text={t('loginAnomaly.sec.retention')} />
              {retentionReadOnly && (
                <ToneBadge tone="muted" data-slot="la-retention-ro"><Lock aria-hidden="true" />{t('loginAnomaly.retentionRoBadge')}</ToneBadge>
              )}
            </span>
          )}
          description={t('loginAnomaly.sec.retentionDesc')} contentClassName="flex flex-col gap-3">
          <NumberField {...numProps('retention_days')} readOnly={retentionReadOnly} />
          {retentionReadOnly && (
            <AlertBanner tone="info" icon={Lock} title={t('loginAnomaly.retentionRoTitle')} className="mb-0">
              <span data-testid="la-retention-ro-body">{t('loginAnomaly.retentionRoBody')}</span>
            </AlertBanner>
          )}
          {shrink && (
            <AlertBanner tone="warning" title={t('loginAnomaly.retentionShrinkTitle')} className="mb-0">
              <span data-testid="la-retention-shrink">
                {t('loginAnomaly.retentionShrinkBody', loaded.retention_days, String(form.retention_days).trim())}
              </span>
            </AlertBanner>
          )}
        </SettingsSection>
      </div>

      {/* ── Son olaylar ── */}
      <LaIncidents state={incidents} windowMinutes={Number(loaded?.window_minutes) || 10} />

      {/* ── Yapışkan kaydet çubuğu ── */}
      <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={discard} saveLabel={t('loginAnomaly.save')}
        dirtyLabel={dirtyKeys.length === 1 ? t('loginAnomaly.dirtyOne') : t('loginAnomaly.dirtyMany', dirtyKeys.length)}
        disabled={errorCount > 0}
        result={saveError ? (
          <div className="min-w-0 basis-full" data-slot="la-save-error">
            <AlertBanner tone="danger" role="alert" title={t('loginAnomaly.saveFailedTitle')} className="mb-0">{String(saveError)}</AlertBanner>
          </div>
        ) : null}>
        {errorCount > 0 && (
          <span data-slot="la-invalid-count" className="basis-full text-xs font-medium text-destructive sm:basis-auto">
            {errorCount === 1 ? t('loginAnomaly.invalidOne') : t('loginAnomaly.invalidMany', errorCount)}
          </span>
        )}
      </SettingsSaveBar>
    </div>
  )
}

/** Kart başlığı — ikon + metin (başlık düzeyi SettingsSection'da). */
function SectionTitle({ icon: Icon, text }) {
  return (
    <span className="inline-flex items-center gap-2">
      <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
      {text}
    </span>
  )
}

/** "Açık · son 30 günde 2 olay · son olay 3 gün önce" (+ açık olay rozeti) — olay listesiyle aynı veri. */
function StatusLine({ t, enabled, incidents }) {
  const stats = incidents.items ? incidentStats(incidents.items, incidents.total) : null
  let count = null
  if (stats) {
    if (stats.saturated) count = t('loginAnomaly.st.last30Many', `${stats.last30}+`)
    else if (stats.last30 === 0) count = t('loginAnomaly.st.last30None')
    else if (stats.last30 === 1) count = t('loginAnomaly.st.last30One')
    else count = t('loginAnomaly.st.last30Many', stats.last30)
  }
  const lastRel = stats?.last ? relativeTime(stats.last.opened_at, t) : null
  return (
    <div data-slot="la-status" className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
      <ToneBadge tone={enabled ? 'success' : 'muted'} data-slot="la-state" data-state={enabled ? 'on' : 'off'} className="font-semibold">
        {enabled ? t('loginAnomaly.st.on') : t('loginAnomaly.st.off')}
      </ToneBadge>
      {count && <span data-slot="la-status-count">{count}</span>}
      {lastRel && <span><span aria-hidden="true">· </span>{t('loginAnomaly.st.lastAt', lastRel)}</span>}
      {stats?.open && (
        <ToneBadge tone="danger" data-slot="la-open-now" className="font-semibold">{t('loginAnomaly.st.openNow')}</ToneBadge>
      )}
    </div>
  )
}

/** Uyarıların gerçekten gideceği yer — alıcılar / sistem yöneticisi yedeği / kimse (uyarı). */
function RecipientsSummary({ t, form }) {
  const eff = effectiveRecipients(form)
  if (eff.list.length === 0) {
    return (
      <AlertBanner tone="warning" title={t('loginAnomaly.rcp.noneTitle')} className="mb-0">
        <span data-slot="la-no-recipients">{t('loginAnomaly.rcp.noneBody')}</span>
      </AlertBanner>
    )
  }
  const shown = eff.list.slice(0, 2)
  const more = eff.list.length - shown.length
  const lead = eff.fallback ? t('loginAnomaly.rcp.fallback')
    : eff.list.length === 1 ? t('loginAnomaly.rcp.toOne') : t('loginAnomaly.rcp.toMany', eff.list.length)
  return (
    <p data-slot="la-recipients-summary" className="flex min-w-0 items-start gap-2 text-sm">
      <Mail aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      <span className="min-w-0 [overflow-wrap:anywhere]">
        <span className="text-muted-foreground">{lead}: </span>
        <span className="font-medium">{shown.join(', ')}</span>
        {more > 0 && <span className="text-muted-foreground"> {t('loginAnomaly.rcp.more', more)}</span>}
      </span>
    </p>
  )
}

/** Alıcı çip girişi (noc/EmailChipsInput: doğrulama, yinelenen, yapıştırma, ×) + yedek adres ipucu. */
function RecipientsField({ t, form, error, onChange }) {
  const admin = form.system_admin_email
  const hint = admin ? t('loginAnomaly.recipientsFallback', admin) : t('loginAnomaly.recipientsNoFallback')
  const empty = form.recipients.emails.length === 0
  return (
    <Field hint={hint} hintTone={empty && !admin ? 'warn' : undefined} error={error} className="mb-0">
      {({ id, describedBy, invalid }) => (
        <>
          <Label htmlFor={id} className="sr-only">{t('loginAnomaly.recipientsTitle')}</Label>
          <EmailChipsInput id={id} describedBy={describedBy} ariaInvalid={invalid}
            emails={form.recipients.emails} invalid={form.recipients.invalid} onChange={onChange}
            placeholder={t('loginAnomaly.recipientsChipPh')} />
        </>
      )}
    </Field>
  )
}

/** Canlı kural özeti — alan değerlerinden düz dil cümleleri; sayılar kalın. Kirliyken "kaydedilmemiş önizleme". */
function RuleSummary({ t, form, dirty }) {
  const v = (k) => (validateField(k, form[k]) ? '—' : String(form[k]).trim())
  const cooldown = validateField('cooldown_minutes', form.cooldown_minutes) ? '—' : fmtMinutes(Number(form.cooldown_minutes), t)
  const rows = [
    { key: 'main', icon: ShieldAlert, node: richText(t('loginAnomaly.sum.main'), [v('window_minutes'), v('threshold_per_account'), v('threshold_per_ip'), v('threshold_total')]) },
    { key: 'pattern', icon: Network, node: richText(t('loginAnomaly.sum.pattern'), [v('threshold_distinct_users_per_ip'), v('threshold_distinct_ips_per_account')]) },
    { key: 'relative', icon: TrendingUp, node: richText(t('loginAnomaly.sum.relative'), [v('baseline_hours'), v('relative_multiplier'), v('relative_floor')]) },
    { key: 'cooldown', icon: Timer, node: <>{richText(t('loginAnomaly.sum.cooldown'), [cooldown])} {form.resolved_email_enabled ? t('loginAnomaly.sum.resolvedOn') : t('loginAnomaly.sum.resolvedOff')}</> },
  ]
  return (
    <Card data-slot="la-rule-summary" data-enabled={form.enabled ? 'true' : 'false'} className="gap-3 py-4">
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={4} className="flex flex-wrap items-center gap-2">
          <ListChecks aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          {t('loginAnomaly.sum.title')}
          {dirty && <Badge variant="warning" data-slot="la-preview">{t('loginAnomaly.sum.preview')}</Badge>}
        </CardTitle>
        <CardDescription>{t('loginAnomaly.sum.desc')}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 px-4 sm:px-6">
        {!form.enabled && <AlertBanner tone="info" className="mb-0">{t('loginAnomaly.sum.off')}</AlertBanner>}
        <ul className={cn('flex list-none flex-col gap-3', !form.enabled && 'opacity-70')}>
          {rows.map(({ key, icon: Icon, node }) => (
            <li key={key} data-rule-line={key} className="flex min-w-0 items-start gap-3">
              <span aria-hidden="true" className="inline-flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                <Icon className="size-4" />
              </span>
              <p className="min-w-0 pt-1 text-sm leading-relaxed text-muted-foreground [overflow-wrap:anywhere]">{node}</p>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}
