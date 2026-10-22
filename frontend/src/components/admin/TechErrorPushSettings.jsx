import { useEffect, useRef, useState } from 'react'
import { BellRing, CircleAlert, RefreshCw, Send, Stamp } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { SettingsSaveBar, SettingsSection, ToggleRow } from './SettingsControls.jsx'
import { isUnsupportedResponse } from './techErrorReminderModel.js'
import { PUSH_GROUPS, applyPush, changedPushFields, isPushDirty, normalizePush, toPushForm } from './techErrorPushModel.js'
import { Button } from '@/components/shadcn/button'
import { Separator } from '@/components/shadcn/separator'
import { cn } from '@/lib/utils'

const TOUCH = 'pointer-coarse:min-h-10'

/** Alan → etiket / açıklama / yardım anahtarı. Anahtarlar AÇIKÇA yazılı (kullanılmayan anahtar kapısı dinamik anahtarı görmez). */
const ROWS = {
  period_reminder: { label: 'te.push.period', hint: 'te.push.periodHint', help: 'help.set.site.monitor.tech-error-report.reminder-push' },
  approval_request: { label: 'te.push.approvalRequest', hint: 'te.push.approvalRequestHint', help: 'help.set.site.monitor.tech-error-report.push.approval-request' },
  approval_reminder: { label: 'te.push.approvalReminder', hint: 'te.push.approvalReminderHint', help: 'help.set.site.monitor.tech-error-report.push.approval-reminder' },
  decision: { label: 'te.push.decision', hint: 'te.push.decisionHint', help: 'help.set.site.monitor.tech-error-report.push.decision' },
  delivered: { label: 'te.push.delivered', hint: 'te.push.deliveredHint', help: 'help.set.site.monitor.tech-error-report.push.delivered' },
  failed: { label: 'te.push.notSent', hint: 'te.push.notSentHint', help: 'help.set.site.monitor.tech-error-report.push.failed' },
}

const GROUP_META = {
  period: { title: 'te.push.groupPeriod', Icon: BellRing },
  approval: { title: 'te.push.groupApproval', Icon: Stamp },
  send: { title: 'te.push.groupSend', Icon: Send },
}

/**
 * Ayarlar → Teknik hata raporu → "Push bildirimleri" (2026-10-18, kullanıcı isteği: "teknik hata raporu bildirimlerini
 * haftalık rapordaki gibi push ile de yapalım, ayarlardan kontrol edilebilsin"). Olay başına bir anahtar — onay isteği /
 * onay hatırlatması (PO'lara), karar (isteyene), gönderildi (takıma), gönderilemedi (gönderene) ve dönem hatırlatmasının
 * takım push'u (eski hatırlatma kartından buraya taşındı, aynı ayar anahtarı). Hepsi varsayılan AÇIK, GLOBAL_ONLY: kapsamlı
 * müdür salt okunur görür (kayıt çubuğu yok). Değerler sayfanın okuma dalgasıyla gelir (`state` = `pushState`); kayıt AYRI
 * uca (`PUT …/settings/push`) YALNIZ değişen anahtarlarla gider. Eski pod 404 / 405 → "henüz desteklenmiyor", salt okunur,
 * taslak korunur. Genel push kanalı kapalıysa (`push_enabled: false`) kart bunu söyler ve Push ayarlarına götürür
 * (sayfa `onOpenPushSettings` verirse önce onun kaydedilmemiş değişiklik onayı — 20.166 bugfix, UIA-E5).
 * Sayfayla eşgüdüm hatırlatma kartıyla aynı (`onSaving` / `onSaved` / `onUnsupported` / `onDirtyChange`). Kancalar `tes-push-*`.
 */
export default function TechErrorPushSettings({
  state, readOnly = false, onDirtyChange, onSaving, onSaved, onUnsupported, onRetry, retryDisabled = false, onOpenPushSettings,
}) {
  const t = useT()
  const toast = useToast()
  const data = state?.data || null
  const [saved, setSaved] = useState(() => data)
  const [form, setForm] = useState(() => (data ? toPushForm(data) : null))
  const [saving, setSaving] = useState(false)
  const alive = useRef(true)
  const savedRef = useRef(saved)
  savedRef.current = saved

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  // Üst bileşen yeniden okuduğunda (Yenile): kayıtlı taban güncellenir, kullanıcının kaydedilmemiş taslağı korunur
  useEffect(() => {
    if (!data) return
    const prev = savedRef.current
    setSaved(data)
    setForm((cur) => (cur && prev && isPushDirty(prev, cur) ? cur : toPushForm(data)))
  }, [data])

  const unsupported = state?.status === 'unsupported'
  const locked = readOnly || saved?.read_only === true || unsupported
  const dirty = !!saved && !!form && isPushDirty(saved, form)
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])

  if (!state) return null

  async function save() {
    if (saving || locked || !saved || !form) return
    const body = changedPushFields(saved, form)
    if (Object.keys(body).length === 0) return
    setSaving(true)
    onSaving?.(true)
    try {
      const res = await api.admin.saveTechErrorPushSettings(body)
      if (!alive.current) return
      if (isUnsupportedResponse(res)) {
        onUnsupported?.()            // eski pod: hiçbir şey yazılmadı, taslak yerinde
        return
      }
      if (!res || res.success === false) {
        toast.error(res?.error || t('te.push.saveError'), { details: res?.errorInfo })
        return
      }
      const next = normalizePush(res.data) || applyPush(saved, body)
      setSaved(next)
      // Kayıt sırasında değiştirilen anahtar silinmez: gönderilenler kaydedildi, diğerleri taslakta kalır
      setForm((cur) => {
        const merged = { ...cur }
        for (const k of Object.keys(body)) merged[k] = next[k]
        return merged
      })
      onSaved?.(next)
      toast.success(t('te.push.saved'))
    } catch (e) {
      toast.error(e?.message || t('te.push.saveError'))
    } finally {
      if (alive.current) setSaving(false)
      onSaving?.(false)
    }
  }

  function discard() {
    if (saved) setForm(toPushForm(saved))
  }

  const retryButton = onRetry && (
    <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={saving || retryDisabled}
      className={cn('h-9 sm:h-8', TOUCH)} data-slot="tes-push-retry">
      <RefreshCw aria-hidden="true" /> {t('te.retry')}
    </Button>
  )

  const notes = (
    <>
      {unsupported && (
        <div data-slot="tes-push-unsupported">
          <AlertBanner tone="info" role="status" className="mb-0" title={t('te.push.unsupportedTitle')}>{t('te.push.unsupported')}</AlertBanner>
        </div>
      )}
      {state.status === 'failed' && data && (
        <div data-slot="tes-push-stale">
          <AlertBanner tone="warning" className="mb-0" title={t('te.push.staleTitle')} actions={retryButton}>
            {state.error ? String(state.error) : t('te.push.stale')}
          </AlertBanner>
        </div>
      )}
      {saved?.push_enabled === false && (
        <div data-slot="tes-push-channel-off">
          <AlertBanner tone="warning" className="mb-0" title={t('te.push.channelOffTitle')}
            actions={(
              <Button type="button" variant="outline" size="sm" className={cn('h-9 sm:h-8', TOUCH)} data-slot="tes-push-open-channel"
                onClick={() => (onOpenPushSettings ? onOpenPushSettings() : navigateTo('settings', { sec: 'userpush' }))}>
                {t('te.push.openChannel')}
              </Button>
            )}>
            {t('te.push.channelOff')}
          </AlertBanner>
        </div>
      )}
    </>
  )

  if (!saved || !form) {
    return (
      <div data-slot="tes-push" data-state={state.status} className="min-w-0">
        <SettingsSection title={t('te.push.title')} description={t('te.push.desc')} contentClassName="flex flex-col gap-4">
          {notes}
          {state.status === 'failed' && (
            <div data-slot="tes-push-unreadable">
              <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('te.push.unreadable')}
                description={state.error ? String(state.error) : t('te.push.unreadableBody')} className="py-6" actions={retryButton} />
            </div>
          )}
        </SettingsSection>
      </div>
    )
  }

  return (
    <div data-slot="tes-push" data-state={state.status} className="min-w-0">
      <SettingsSection title={t('te.push.title')} description={t('te.push.desc')} contentClassName="flex flex-col gap-5">
        {notes}
        {PUSH_GROUPS.map((g, gi) => {
          const meta = GROUP_META[g.id]
          const headId = `tes-push-${g.id}-h`
          return (
            <div key={g.id} className="flex min-w-0 flex-col gap-5">
              {gi > 0 && <Separator />}
              <section data-slot={`tes-push-group-${g.id}`} aria-labelledby={headId} className="flex min-w-0 flex-col gap-3">
                <h5 id={headId} className="m-0 flex items-center gap-2 text-sm font-semibold">
                  <meta.Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /> {t(meta.title)}
                </h5>
                {g.fields.map((k) => {
                  const row = ROWS[k]
                  return (
                    <div key={k} className="flex min-w-0 flex-col gap-1" data-slot={`tes-push-${k.replace(/_/g, '-')}`}
                      data-on={form[k] ? 'true' : 'false'}>
                      <ToggleRow checked={!!form[k]} onChange={(v) => setForm((f) => ({ ...f, [k]: !!v }))} disabled={locked || saving}
                        label={t(row.label)} helpKey={row.help} touch className="min-w-0 max-w-full" />
                      <p className="m-0 pl-10 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t(row.hint)}</p>
                    </div>
                  )
                })}
              </section>
            </div>
          )
        })}

        {!locked && (
          <div data-slot="tes-push-save">
            <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={dirty ? discard : undefined} />
          </div>
        )}
      </SettingsSection>
    </div>
  )
}
