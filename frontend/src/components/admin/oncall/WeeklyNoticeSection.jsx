import { useId } from 'react'
import { BellRing, Info } from 'lucide-react'
import { formatDate } from '../../../api/client'
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
  NOTICE_DAYS, NOTICE_REASONS, NOTICE_STATUSES, noticeChanges, noticeNextState, noticeStatusTone, noticeUsable,
} from './oncallModel.js'

const SECTION = 'flex min-w-0 flex-col gap-3 rounded-lg border bg-card p-3 sm:p-4'
/** Sunucunun e-posta sınıfı → etiket anahtarı (ham SMTP metni gelmez). */
const MAIL_KEY = { delivered: 'oncall.wn.mailOutcome.sent', skipped: 'oncall.wn.mailOutcome.skip', failed: 'oncall.wn.mailOutcome.notSent', off: 'oncall.wn.mailOutcome.off' }
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
  const lastStatus = NOTICE_STATUSES.includes(last?.status) ? last.status : null
  const lastReason = NOTICE_REASONS.includes(last?.reason) ? last.reason : null

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
                  : nextState === 'scheduled' ? formatDate(next) : t('oncall.wn.nextUnknown')}
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
                  <span className="tabular-nums">{t('oncall.wn.lastWeek', last.week || '—')}</span>
                  {last.at && <span className="text-muted-foreground tabular-nums">· {formatDate(last.at)}</span>}
                  {lastReason && <span className="w-full [overflow-wrap:anywhere] text-muted-foreground">{t(`oncall.wn.reason.${lastReason}`)}</span>}
                  {(last.mail || Number.isFinite(last.push_count)) && (
                    <span className="w-full [overflow-wrap:anywhere] text-muted-foreground">
                      {t('oncall.wn.lastChannels', MAIL_KEY[last.mail] ? t(MAIL_KEY[last.mail]) : '—',
                        Number.isFinite(last.push_count) ? String(last.push_count) : '—')}
                    </span>
                  )}
                </>
              )}
        </div>
      </div>
    </div>
  )
}
