import { useEffect, useId, useMemo, useState } from 'react'
import { BellOff, BellRing, SlidersHorizontal, Send, Timer, RefreshCw, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import {
  PUSH_FAMILIES, PUSH_LEVELS, SNOOZE_PRESETS, prefsToForm, formToBody, formEqual, isDefaultForm, familyLabel, snoozeParts,
} from '../../utils/pushPrefs.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Label } from '@/components/shadcn/label'
import { Separator } from '@/components/shadcn/separator'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Switch } from '@/components/shadcn/switch'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

const CARD = 'min-w-0 gap-3 py-4 shadow-none'
const TOUCH = 'pointer-coarse:h-10 max-sm:h-10'

/**
 * "Bildirim tercihlerim" (2026-10-04, onaylı öneriler 4/5) — Etkinliklerim yan kartı, "push istemiyorum" anahtarının
 * hemen altında. Kişi YALNIZ kendi kaydını yazar (`/api/me/push-*`, kimlik oturumdan).
 *
 * <ul>
 *   <li><b>Sustur</b>: 1 saat / 4 saat / yarın 08:00 (İstanbul) / kapat — anında kaydedilir; "kritikler yine gelsin"
 *       (vars. açık). Etkin susturma "HH:mm saatine kadar susturuldu" olarak görünür.</li>
 *   <li><b>En düşük seviye</b>, <b>izleme türleri</b> (izin listesi; en az biri) ve <b>push dili</b> — Kaydet ile
 *       (değişiklik yokken kapalı). Doğrulama hatası alanın altında (useFormErrors; sunucu 400 + {@code field}).</li>
 *   <li><b>Kendime test push'u</b> — tercihlerden bağımsız, yalnız kişiye; sonuç kartta (gönderildi / ayarlanmamış /
 *       HTTP kodu / 10 dk'da 3 sınırı).</li>
 * </ul>
 * Çözüm push'u ve kodla giriş push'u tercihlerden etkilenmez (açıklama metni söyler). Sol renk şeridi yok; mobilde tek
 * sütun, dokunma hedefleri 40 px. Test kancası: {@code data-slot="push-prefs"}.
 */
export default function PushPreferencesCard({ optOut = false }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const critId = useId()
  const critHintId = useId()

  const [prefs, setPrefs] = useState(null)
  const [loadError, setLoadError] = useState(false)
  const [reload, setReload] = useState(0)
  const families = prefs?.available_families?.length ? prefs.available_families : PUSH_FAMILIES
  // Kayıtlı form YALNIZ seviye / aile / dil değişince yenilenir: susturma yanıtı kişinin kaydedilmemiş düzenlemesini silmesin.
  const savedKey = prefs ? JSON.stringify([prefs.min_level ?? null, prefs.families ?? null, prefs.lang ?? null, families]) : null
  const saved = useMemo(() => (prefs ? prefsToForm(prefs, families) : null), [savedKey]) // eslint-disable-line react-hooks/exhaustive-deps
  const [form, setForm] = useState(null)
  const [saving, setSaving] = useState(false)
  const [snoozeBusy, setSnoozeBusy] = useState(false)
  const [testBusy, setTestBusy] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const fe = useFormErrors(saved)

  useEffect(() => {
    let alive = true
    setLoadError(false)
    // then içinde çağrı: uç yoksa / senkron atarsa da reddedilen söz olur → hata kartı (efekt çökmez)
    Promise.resolve().then(() => api.me.getPushPreferences()).then((r) => {
      if (!alive) return
      if (r?.success) setPrefs(r)
      else setLoadError(true)
    }).catch(() => { if (alive) setLoadError(true) })
    return () => { alive = false }
  }, [reload])
  useEffect(() => { if (saved) setForm(saved) }, [saved])

  const dirty = !!(form && saved && !formEqual(form, saved))

  async function save() {
    if (fe.check({ families: form.families.length === 0 && t('mypush.families.required') })) return
    setSaving(true)
    try {
      const res = await api.me.savePushPreferences(formToBody(form, families))
      if (res?.success) {
        setPrefs(res)
        toast.success(t('mypush.saved'))
      } else if (res?.field) {
        fe.check({ [res.field]: res.error || t('mypush.saveError') })
      } else {
        toast.error(res?.error || t('mypush.saveError'))
      }
    } catch (e) {
      toast.error(e?.message || t('mypush.saveError'))
    } finally {
      setSaving(false)
    }
  }

  async function snooze(body) {
    setSnoozeBusy(true)
    try {
      const res = await api.me.pushSnooze(body)
      if (res?.success) {
        setPrefs((p) => ({ ...p, ...res }))
        if (body.preset === 'off') toast.success(t('mypush.snooze.cleared'))
        else if (body.preset) {
          const parts = snoozeParts(res.snooze_until, locale)
          toast.success(t('mypush.snooze.done', parts ? (parts.date ? `${parts.date} ${parts.time}` : parts.time) : '—'))
        }
      } else {
        toast.error(res?.error || t('mypush.snooze.error'))
      }
    } catch (e) {
      toast.error(e?.message || t('mypush.snooze.error'))
    } finally {
      setSnoozeBusy(false)
    }
  }

  async function sendTest() {
    setTestBusy(true)
    setTestResult(null)
    try {
      const res = await api.me.pushSelfTest()
      if (res?.status === 429 || res?.code === 'RATE_LIMITED') setTestResult({ tone: 'warning', text: t('mypush.test.rate') })
      else if (res?.success && res.ok) setTestResult({ tone: 'success', text: t('mypush.test.ok'), channelOff: res.channel_enabled === false })
      else if (res?.success && res.outcome === 'NOT_CONFIGURED') setTestResult({ tone: 'warning', text: t('mypush.test.notConfigured') })
      else if (res?.success && res.outcome === 'HTTP') setTestResult({ tone: 'danger', text: t('mypush.test.http', res.http_status) })
      else setTestResult({ tone: 'danger', text: t('mypush.test.error', res?.error || '—') })
    } catch (e) {
      setTestResult({ tone: 'danger', text: t('mypush.test.error', e?.message || '—') })
    } finally {
      setTestBusy(false)
    }
  }

  if (!prefs || !form) {
    return (
      <Card data-slot="push-prefs" className={CARD} aria-busy={!loadError || undefined}>
        <CardHeader className="px-4">
          <CardTitle className="flex items-center gap-2 text-sm">
            <SlidersHorizontal aria-hidden="true" className="size-4 text-primary" />{t('mypush.pref.title')}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2 px-4">
          {loadError ? (
            <AlertBanner tone="danger" role="alert" className="mb-0" title={t('mypush.pref.loadError')}
              actions={<Button type="button" variant="outline" size="sm" className={TOUCH} onClick={() => setReload((k) => k + 1)}>
                <RefreshCw aria-hidden="true" /> {t('myact.retry')}</Button>} />
          ) : (
            <>
              <span className="sr-only">{t('app.loading')}</span>
              <Skeleton className="h-9 w-full" />
              <Skeleton className="h-9 w-2/3" />
              <Skeleton className="h-16 w-full" />
            </>
          )}
        </CardContent>
      </Card>
    )
  }

  const snoozed = !!prefs.snooze_active
  const parts = snoozed ? snoozeParts(prefs.snooze_until, locale) : null
  const snoozeText = parts ? (parts.date ? t('mypush.snooze.untilDate', parts.date, parts.time) : t('mypush.snooze.until', parts.time)) : null
  const busy = saving || snoozeBusy

  return (
    <Card data-slot="push-prefs" data-snoozed={snoozed || undefined} className={CARD}>
      <CardHeader className="gap-1.5 px-4">
        <CardTitle className="flex items-center gap-2 text-sm">
          <SlidersHorizontal aria-hidden="true" className="size-4 text-primary" />{t('mypush.pref.title')}
        </CardTitle>
        <div className="flex flex-wrap items-center gap-1.5" data-slot="push-prefs-summary">
          {snoozed
            ? <Badge variant="warning" className="gap-1"><BellOff aria-hidden="true" />{snoozeText}</Badge>
            : <Badge variant="outline" className="font-normal">{isDefaultForm(saved, families) ? t('mypush.pref.summaryAll') : t('mypush.pref.summaryCustom')}</Badge>}
          {saved.lang === 'en' && <Badge variant="secondary" className="font-normal">{t('mypush.lang.en')}</Badge>}
        </div>
      </CardHeader>

      <CardContent className="flex min-w-0 flex-col gap-3.5 px-4">
        <p className="m-0 text-xs leading-snug text-muted-foreground">{t('mypush.pref.desc')}</p>
        {optOut && <div data-slot="push-prefs-optout"><AlertBanner tone="warning" className="mb-0">{t('mypush.pref.optOutNote')}</AlertBanner></div>}

        {/* ── Sustur — anında kaydedilir ── */}
        <section data-slot="push-snooze" aria-label={t('mypush.snooze.title')} className="flex min-w-0 flex-col gap-2">
          <div className="flex min-w-0 items-start gap-2">
            {snoozed
              ? <BellOff aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-amber-600 dark:text-amber-400" />
              : <BellRing aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />}
            <span className="min-w-0 text-sm font-semibold leading-snug" data-slot="push-snooze-state">
              {snoozed ? snoozeText : t('mypush.snooze.off')}
            </span>
          </div>
          <div role="group" aria-label={t('mypush.snooze.aria')} className="flex flex-wrap gap-2">
            {SNOOZE_PRESETS.map((p) => (
              <Button key={p} type="button" variant="outline" size="sm" data-preset={p} disabled={busy}
                className={cn('flex-1 basis-[30%]', TOUCH)} onClick={() => snooze({ preset: p, critical: !!prefs.snooze_critical })}>
                <Timer aria-hidden="true" /> {t(`mypush.snooze.${p}`)}
              </Button>
            ))}
            {snoozed && (
              <Button type="button" variant="secondary" size="sm" data-preset="off" disabled={busy}
                className={cn('w-full', TOUCH)} onClick={() => snooze({ preset: 'off' })}>
                <X aria-hidden="true" /> {t('mypush.snooze.end')}
              </Button>
            )}
          </div>
          <div className="flex min-w-0 items-start gap-3">
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              <Label htmlFor={critId} className="cursor-pointer leading-snug">{t('mypush.snooze.critical')}</Label>
              <span id={critHintId} className="text-xs leading-snug text-muted-foreground">{t('mypush.snooze.criticalHint')}</span>
            </div>
            <Switch id={critId} checked={!!prefs.snooze_critical} disabled={busy} aria-describedby={critHintId}
              className="mt-0.5 shrink-0" onCheckedChange={(v) => snooze({ critical: !!v })} />
          </div>
        </section>

        <Separator />

        {/* ── Seviye / türler / dil — Kaydet ile ── */}
        <Field label={t('mypush.level.label')} hint={t('mypush.level.hint')} {...fe.fieldProps('min_level')} className="mb-0">
          {({ id, describedBy }) => (
            <div id={id} aria-describedby={describedBy} className="min-w-0">
              <SegmentedControl value={form.level} ariaLabel={t('mypush.level.label')} className="flex-wrap" itemClassName="max-sm:min-h-10"
                onChange={(v) => { setForm((f) => ({ ...f, level: v })); fe.clear('min_level') }}
                options={PUSH_LEVELS.map((l) => ({ value: l, label: t(`mypush.level.${l}`) }))} />
            </div>
          )}
        </Field>

        <Field label={t('mypush.families.label')} hint={t('mypush.families.hint')} {...fe.fieldProps('families')} className="mb-0">
          {({ id, describedBy, invalid }) => (
            <div className="flex min-w-0 flex-col gap-1.5">
              <ToggleGroup id={id} type="multiple" variant="outline" spacing={1} disabled={saving}
                aria-label={t('mypush.families.label')} aria-describedby={describedBy} aria-invalid={invalid}
                value={form.families} className="flex flex-wrap justify-start"
                onValueChange={(v) => { setForm((f) => ({ ...f, families: families.filter((x) => v.includes(x)) })); fe.clear('families') }}>
                {families.map((f) => (
                  <ToggleGroupItem key={f} value={f} data-family={f} className="min-w-11 px-2.5 text-xs pointer-coarse:h-10 max-sm:h-10">
                    {familyLabel(f, t)}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
              {form.families.length < families.length && (
                <Button type="button" variant="link" size="sm" className="h-auto self-start p-0 text-xs max-sm:min-h-10"
                  onClick={() => { setForm((f) => ({ ...f, families: [...families] })); fe.clear('families') }}>
                  {t('mypush.families.all')}
                </Button>
              )}
            </div>
          )}
        </Field>

        <Field label={t('mypush.lang.label')} hint={t('mypush.lang.hint')} {...fe.fieldProps('lang')} className="mb-0">
          {({ id, describedBy }) => (
            <div id={id} aria-describedby={describedBy} className="min-w-0">
              <SegmentedControl value={form.lang} ariaLabel={t('mypush.lang.label')} itemClassName="max-sm:min-h-10"
                onChange={(v) => { setForm((f) => ({ ...f, lang: v })); fe.clear('lang') }}
                options={[{ value: 'tr', label: t('mypush.lang.tr') }, { value: 'en', label: t('mypush.lang.en') }]} />
            </div>
          )}
        </Field>
      </CardContent>

      <CardFooter className="flex flex-wrap justify-end gap-2 px-4">
        {dirty && (
          <Button type="button" variant="ghost" size="sm" className={TOUCH} disabled={saving}
            onClick={() => { setForm(saved); fe.reset() }}>
            {t('mypush.revert')}
          </Button>
        )}
        <Button type="button" size="sm" className={TOUCH} disabled={!dirty || saving} aria-busy={saving || undefined} onClick={save}>
          {saving ? t('mypush.saving') : t('mypush.save')}
        </Button>
      </CardFooter>

      {/* ── Kendime test ── */}
      <CardContent className="flex min-w-0 flex-col gap-2 border-t px-4 pt-3" data-slot="push-self-test">
        <Button type="button" variant="outline" size="sm" className={cn('self-start', TOUCH)} disabled={testBusy}
          aria-busy={testBusy || undefined} onClick={sendTest}>
          <Send aria-hidden="true" /> {testBusy ? t('mypush.test.sending') : t('mypush.test.button')}
        </Button>
        <span className="text-xs leading-snug text-muted-foreground">{t('mypush.test.hint')}</span>
        {testResult && (
          <div data-slot="push-self-test-result" data-tone={testResult.tone}>
            <AlertBanner tone={testResult.tone} className="mb-0" role="status">
              {testResult.text}
              {testResult.channelOff && <span className="mt-1 block text-xs">{t('mypush.test.channelOff')}</span>}
            </AlertBanner>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
