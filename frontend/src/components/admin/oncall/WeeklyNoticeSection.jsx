import { useId } from 'react'
import { BellRing, Info } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import Field from '../../ui/Field.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { ToggleRow } from '../SettingsControls.jsx'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import {
  NOTICE_DAYS, istanbulParts, noticeChanges, noticeLastView, noticeNextState, noticeStatusTone, noticeUsable, weekKeyText,
} from './oncallModel.js'

const SECTION = 'flex min-w-0 flex-col gap-3 rounded-lg border bg-card p-3 sm:p-4'
/** Sunucunun e-posta sınıfı → etiket anahtarı (ham SMTP metni gelmez). */
const MAIL_KEY = {
  delivered: 'oncall.wn.mailOutcome.sent', queued: 'oncall.wn.mailOutcome.queued', skipped: 'oncall.wn.mailOutcome.skip',
  failed: 'oncall.wn.mailOutcome.notSent', off: 'oncall.wn.mailOutcome.off',
}
/** Push sınıfı → etiket anahtarı (statik — dinamik anahtar yok). */
const PUSH_KEY = {
  people: 'oncall.wn.pushOutcome.people', off: 'oncall.wn.pushOutcome.off', notSent: 'oncall.wn.pushOutcome.notSent',
  none: 'oncall.wn.pushOutcome.none',
}
const H = 'm-0 text-sm font-semibold'

/** Kanal satırı — shadcn Checkbox + bağlı Label; dokunmatikte satır en az 40 px (kutunun kendi ::after alanı da 40 px). */
function ChannelRow({ checked, disabled, onChange, label, hint, slot }) {
  const id = useId()
  return (
    <div data-slot={slot} className="flex min-w-0 items-start gap-2 pointer-coarse:min-h-10 pointer-coarse:items-center">
      <Checkbox id={id} checked={checked} disabled={disabled} onCheckedChange={(v) => onChange(v === true)} className="mt-0.5 pointer-coarse:mt-0" />
      <Label htmlFor={id} className={cn('flex min-w-0 flex-col items-start gap-0.5 font-normal', disabled ? 'cursor-not-allowed opacity-70' : 'cursor-pointer')}>
        <span className="font-medium">{label}</span>
        {hint && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{hint}</span>}
      </Label>
    </div>
  )
}

/**
 * Haftalık nöbet bildirimi bölümü (2026-11-15, kullanıcı isteği: "takımın nöbetçi bilgisini her hafta pazartesi 08:30'da
 * push ve mail ile ekibe bildirelim; takım kendisi açsın, ayarlarını kendisi yapsın"). Açma anahtarı, kanallar (e-posta =
 * takımın alarm adresi, push = takım üyeleri), gün + saat (İstanbul), sıradaki gönderim ve son sonuç. Çizelge kapalıyken /
 * nöbetçi yokken açılamaz (kapatmak her zaman mümkün). Yönetemeyen kişi salt okunur görür. Eski sunucu alanı göndermezse
 * bölüm çizilmez (`form.notice.supported`).
 *
 * Test kancaları: `oncall-weekly-notice`, `oncall-wn-next`, `oncall-wn-last` (+ `data-status`), `oncall-wn-unusable`,
 * `oncall-wn-mail`, `oncall-wn-push`, `oncall-wn-day`, `oncall-wn-time`.
 */
export default function WeeklyNoticeSection({ form, base, view, canEdit, errors, onChange }) {
  const t = useT()
  const n = form?.notice
  if (!n?.supported) return null
  const usable = noticeUsable(form)
  const switchLocked = !canEdit || (!usable && !n.enabled)
  const controlsLocked = !canEdit || !n.enabled
  const dirty = Object.keys(noticeChanges(form, base)).length > 0
  const set = (patch) => onChange({ ...n, ...patch })
  const err = (k) => (errors?.[k] ? t(errors[k]) : errors?._serverField === k ? errors._server : undefined)
  const channelErr = err('weekly_notice.mail') || err('weekly_notice.push') || err('weekly_notice.enabled')

  const savedEnabled = !!view?.weekly_notice?.enabled
  const next = view?.weekly_notice_next
  const nextState = noticeNextState(next)
  const last = view?.weekly_notice_last
  const lv = noticeLastView(last)
  const lastStatus = lv?.status || null
  const nextParts = istanbulParts(next)
  const atParts = istanbulParts(last?.at)

  return (
    <div className={SECTION} data-slot="oncall-weekly-notice">
      <div className="flex min-w-0 items-start gap-2">
        <BellRing aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-primary" />
        <div className="flex min-w-0 flex-col gap-1">
          <h4 className={H}>{t('oncall.wn.title')}</h4>
          <p className="m-0 text-xs text-muted-foreground">{t('oncall.wn.intro')}</p>
        </div>
      </div>

      <ToggleRow major touch checked={n.enabled} disabled={switchLocked} label={t('oncall.wn.enabled')}
        onChange={(v) => set({ enabled: v })} />
      {!usable && (
        <p data-slot="oncall-wn-unusable" className="m-0 flex min-w-0 items-start gap-1.5 text-xs text-muted-foreground">
          <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{n.enabled ? t('oncall.wn.unusableOn') : t('oncall.wn.unusable')}</span>
        </p>
      )}

      <fieldset className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0" data-field="weekly_notice.mail">
        <legend className="mb-1 p-0 text-xs font-semibold text-muted-foreground">{t('oncall.wn.channels')}</legend>
        <ChannelRow slot="oncall-wn-mail" checked={n.mail} disabled={controlsLocked} onChange={(v) => set({ mail: v })}
          label={t('oncall.wn.mail')} hint={t('oncall.wn.mailHint')} />
        <ChannelRow slot="oncall-wn-push" checked={n.push} disabled={controlsLocked} onChange={(v) => set({ push: v })}
          label={t('oncall.wn.push')} hint={t('oncall.wn.pushHint')} />
        {channelErr && <p className="m-0 text-sm font-medium text-destructive" role="alert">{channelErr}</p>}
      </fieldset>
      {errors?._noticeOld && (
        <p data-slot="oncall-wn-oldserver" role="alert" className="m-0 text-sm font-medium text-destructive [overflow-wrap:anywhere]">
          {t('oncall.wn.oldServer')}
        </p>
      )}

      <div className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2">
        <Field label={t('oncall.wn.day')} error={err('weekly_notice.day')} name="weekly_notice.day" className="mb-0">
          {({ id, describedBy, invalid }) => (
            <NativeSelect id={id} data-slot="oncall-wn-day" aria-describedby={describedBy} aria-invalid={invalid}
              value={String(n.day)} disabled={controlsLocked} className="pointer-coarse:h-10 max-sm:h-10"
              onChange={(e) => set({ day: Number(e.target.value) })}>
              {NOTICE_DAYS.map((d) => <NativeSelectOption key={d} value={String(d)}>{t(`oncall.wn.dayName.${d}`)}</NativeSelectOption>)}
            </NativeSelect>
          )}
        </Field>
        <Field label={t('oncall.wn.time')} hint={t('oncall.wn.timeHint')} error={err('weekly_notice.time')} name="weekly_notice.time" className="mb-0">
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="time" step={60} data-slot="oncall-wn-time" aria-describedby={describedBy} aria-invalid={invalid}
              value={n.time} disabled={controlsLocked} className="w-full sm:w-40 pointer-coarse:h-10"
              onChange={(e) => set({ time: e.target.value })} />
          )}
        </Field>
      </div>

      <div className="flex min-w-0 flex-col gap-1.5 rounded-md bg-muted/40 px-2.5 py-2 text-xs">
        <div data-slot="oncall-wn-next" className="[overflow-wrap:anywhere]">
          <span className="font-semibold">{t('oncall.wn.nextLabel')}</span>{' '}
          {dirty ? t('oncall.wn.nextAfterSave')
            : !savedEnabled ? t('oncall.wn.nextOff')
              : view?.weekly_notice_unreadable ? t('oncall.wn.unreadable')
                : nextState === 'soon' ? t('oncall.wn.nextSoon')
                  : nextState === 'scheduled' && nextParts
                    ? t('oncall.wn.nextAt', t(`oncall.wn.dayName.${nextParts.dow}`), nextParts.date, nextParts.time)
                    : t('oncall.wn.nextUnknown')}
        </div>
        <div data-slot="oncall-wn-last" data-status={lastStatus || undefined} className="flex min-w-0 flex-wrap items-center gap-1.5">
          <span className="font-semibold">{t('oncall.wn.lastLabel')}</span>
          {view?.weekly_notice_unreadable ? <span>{t('oncall.wn.unreadable')}</span>
            : !last ? <span>{t('oncall.wn.lastNone')}</span>
              : (
                <>
                  <ToneBadge tone={noticeStatusTone(lastStatus)} className="rounded-md whitespace-normal">
                    {lastStatus ? t(`oncall.wn.status.${lastStatus}`) : t('oncall.wn.status.other')}
                  </ToneBadge>
                  <span className="tabular-nums">{t('oncall.wn.lastWeek', weekKeyText(last.week) || '—')}</span>
                  {atParts && <span className="text-muted-foreground tabular-nums">· {atParts.date} {atParts.time}</span>}
                  {lv.reason && <span className="w-full [overflow-wrap:anywhere] text-muted-foreground">{t(`oncall.wn.reason.${lv.reason}`)}</span>}
                  {lv.retry === 'pending' && (
                    <span data-slot="oncall-wn-retry" data-retry="pending" className="w-full [overflow-wrap:anywhere] text-muted-foreground">
                      {t('oncall.wn.retryPending', String(lv.attempts ?? '—'), String(lv.maxAttempts ?? '—'))}
                    </span>
                  )}
                  {lv.retry === 'done' && (
                    <span data-slot="oncall-wn-retry" data-retry="done" className="w-full [overflow-wrap:anywhere] text-muted-foreground">
                      {lastStatus === 'PARTIAL' ? t('oncall.wn.retryDonePartial') : t('oncall.wn.retryDone')}
                    </span>
                  )}
                  {(last.mail || lv.push) && (
                    <span data-slot="oncall-wn-channels" className="w-full [overflow-wrap:anywhere] text-muted-foreground">
                      {t('oncall.wn.lastChannels', MAIL_KEY[last.mail] ? t(MAIL_KEY[last.mail]) : '—',
                        lv.push && PUSH_KEY[lv.push.kind] ? t(PUSH_KEY[lv.push.kind], lv.push.arg) : '—')}
                    </span>
                  )}
                </>
              )}
        </div>
      </div>
    </div>
  )
}
