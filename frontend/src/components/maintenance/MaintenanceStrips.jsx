import { CalendarClock, CheckCircle2, Clock, Hourglass, Settings, Square, TimerReset, Wrench, X } from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { clockText, messageFor, timeText, windowEnds, windowText } from '../../utils/systemMaintenance.js'
import { formatDuration } from '../../utils/incidentMeta.js'
import { Alert } from '@/components/shadcn/alert'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Sistem Bakım Modu ŞERİTLERİ (2026-10-02, kullanıcı kararı) — içerik kolonunun üstünde, akış içinde (yapışkan değil),
 * telefonda sarar; sol renk şeridi YOK (ton zemin + kenar + ikon rozetiyle). Üç tür:
 *
 *  • {@link MaintenanceAnnounceStrip} — duyuru penceresinde (bakımdan `announce_hours` önce) tüm oturum açmış kullanıcılara;
 *    kapatılabilir (pencere + sürüm başına; saat değişirse yeniden görünür).
 *  • {@link MaintenanceWarningStrip} — başlangıçtan `warn_minutes` önce global yönetici OLMAYANLARA, geri sayımlı;
 *    kapatılabilir (son 60 sn'de kapatılamayan pencere açılır).
 *  • {@link MaintenanceAdminStrip} — global yöneticiye: bakım sürerken kalıcı "Sistem bakımda · bitiş HH:mm · Uzat ·
 *    Hemen bitir"; başlamak üzereyken "HH:mm'de başlıyor".
 *  • {@link MaintenanceEndedStrip} — bakım BİTTİKTEN sonra (sunucu bildirim süresince `state: 'ended'`) oturum açmış
 *    HERKESE (global yönetici dahil); kapatılabilir, pencere + sürüm başına (2026-10-02 kullanıcı isteği).
 *
 * Saf sunum (zaman hesabı çağıranda — sunucu saatine göre): önizleme (Ayarlar → Sistem Bakımı) aynı bileşenleri örnek
 * veriyle çizer. Test kancaları: `data-slot="maint-announce|maint-warning|maint-admin|maint-ended"`, `maint-countdown`,
 * `maint-dismiss`, `maint-extend`, `maint-end-now`.
 */

const BADGE = {
  info: 'bg-blue-600 dark:bg-blue-500',
  success: 'bg-green-600 dark:bg-green-500',
  warning: 'bg-amber-600 dark:bg-amber-500',
  danger: 'bg-red-600 dark:bg-red-500',
}

function StripShell({ slot, variant, icon: Icon, label, children, actions, className }) {
  return (
    <Alert variant={variant} role="status" data-slot={slot} data-tone={variant}
      className={cn('flex flex-wrap items-center gap-x-3 gap-y-2 rounded-none border-x-0 border-t-0 px-3.5 py-2.5 text-[13px] leading-[1.45] sm:flex-nowrap sm:px-6 sm:text-[13.5px]',
        className)}>
      <span aria-hidden="true"
        className={cn('inline-flex size-8 shrink-0 items-center justify-center rounded-lg text-white', BADGE[variant] || BADGE.info)}>
        <Icon className="size-4" />
      </span>
      <span className="flex min-w-0 flex-1 basis-[12rem] flex-col gap-0.5">
        <span className="text-[10.5px] font-extrabold tracking-[.09em] uppercase opacity-80">{label}</span>
        <span className="min-w-0 font-medium [overflow-wrap:anywhere]">{children}</span>
      </span>
      {actions && <span className="flex w-full shrink-0 flex-wrap items-center justify-end gap-2 sm:w-auto">{actions}</span>}
    </Alert>
  )
}

function DismissButton({ onDismiss, label }) {
  if (!onDismiss) return null
  return (
    <Button type="button" variant="ghost" size="icon" aria-label={label} data-slot="maint-dismiss" onClick={onDismiss}
      className="size-10 shrink-0 text-current opacity-70 hover:bg-current/10 hover:text-current hover:opacity-100 sm:size-9">
      <X aria-hidden="true" />
    </Button>
  )
}

/** Ek satır: yöneticinin mesajı + iletişim (varsa). */
function Extra({ block }) {
  const t = useT()
  const { lang } = useLanguage()
  const msg = messageFor(block, lang)
  const contact = block?.contact
  if (!msg && !contact) return null
  return (
    <span className="mt-0.5 block text-[12.5px] font-normal opacity-90">
      {msg}{msg && contact ? ' · ' : ''}{contact ? t('sysmaint.contact', contact) : ''}
    </span>
  )
}

export function MaintenanceAnnounceStrip({ block, onDismiss }) {
  const t = useT()
  return (
    <StripShell slot="maint-announce" variant="info" icon={CalendarClock} label={t('sysmaint.announce.label')}
      actions={<DismissButton onDismiss={onDismiss} label={t('sysmaint.dismiss')} />}>
      {t('sysmaint.announce.body', windowText(block))}
      <Extra block={block} />
    </StripShell>
  )
}

export function MaintenanceWarningStrip({ block, secondsLeft, onDismiss }) {
  const t = useT()
  const ends = windowEnds(block)
  return (
    <StripShell slot="maint-warning" variant="warning" icon={Hourglass} label={t('sysmaint.warning.label')}
      actions={(
        <>
          <span data-slot="maint-countdown" role="timer" aria-label={t('sysmaint.warning.countdownAria', clockText(secondsLeft))}
            className="inline-flex items-center gap-1.5 rounded-md border border-current/30 px-2.5 py-1 text-base font-bold tabular-nums">
            <Clock aria-hidden="true" className="size-4" />{clockText(secondsLeft)}
          </span>
          <DismissButton onDismiss={onDismiss} label={t('sysmaint.dismiss')} />
        </>
      )}>
      {/* Bitiş saati + toplam süre de yazılır (2026-10-02 kullanıcı isteği); zaman yoksa eski metin */}
      {ends
        ? t('sysmaint.warning.bodyTimed', Math.max(1, Math.ceil((Number(secondsLeft) || 0) / 60)), ends.start, ends.end,
          formatDuration(ends.durationMs, t))
        : t('sysmaint.warning.body', Math.max(1, Math.ceil((Number(secondsLeft) || 0) / 60)), timeText(block?.start_at))}
      <Extra block={block} />
    </StripShell>
  )
}

/**
 * "Bakım tamamlandı" şeridi (2026-10-02, kullanıcı isteği: "planlı bakım sonlandığında kullanıcılara bir uyarı daha
 * gönderilsin") — başarı tonu, başlangıç/bitiş (gün aşımında tarihli; bitiş GERÇEK bitiştir) + yöneticinin mesajı.
 * Pencere ve sayaç YOK; kapatılabilir.
 */
export function MaintenanceEndedStrip({ block, onDismiss }) {
  const t = useT()
  const ends = windowEnds(block)
  return (
    <StripShell slot="maint-ended" variant="success" icon={CheckCircle2} label={t('sysmaint.ended.label')}
      actions={<DismissButton onDismiss={onDismiss} label={t('sysmaint.dismiss')} />}>
      {ends ? t('sysmaint.ended.body', ends.start, ends.end) : t('sysmaint.ended.bodyPlain')}
      <Extra block={block} />
    </StripShell>
  )
}

/**
 * Global yönetici şeridi. `phase`: `active` (bakım sürüyor — Uzat / Hemen bitir) ya da `warning`/`final` (başlamak üzere).
 * `busy`: eylem sürerken düğmeler kilitli.
 */
export function MaintenanceAdminStrip({ block, phase, secondsLeft, onExtend, onEndNow, onOpenSettings, busy = false }) {
  const t = useT()
  const ends = windowEnds(block)
  const active = phase === 'active'
  return (
    <StripShell slot="maint-admin" variant={active ? 'danger' : 'warning'} icon={Wrench}
      label={active ? t('sysmaint.admin.labelActive') : t('sysmaint.admin.labelSoon')}
      actions={(
        <>
          {active && onExtend && (
            <Button type="button" size="sm" variant="outline" data-slot="maint-extend" onClick={onExtend} disabled={busy}
              className="min-h-10 border-current bg-transparent text-current shadow-none hover:bg-current/10 hover:text-current sm:min-h-9">
              <TimerReset aria-hidden="true" />{t('sysmaint.admin.extend')}
            </Button>
          )}
          {active && onEndNow && (
            <Button type="button" size="sm" variant="destructive" data-slot="maint-end-now" onClick={onEndNow} disabled={busy}
              className="min-h-10 sm:min-h-9">
              <Square aria-hidden="true" />{t('sysmaint.admin.endNow')}
            </Button>
          )}
          {onOpenSettings && (
            <Button type="button" size="sm" variant="ghost" data-slot="maint-open-settings" onClick={onOpenSettings}
              className="min-h-10 text-current hover:bg-current/10 hover:text-current sm:min-h-9">
              <Settings aria-hidden="true" />{t('sysmaint.admin.manage')}
            </Button>
          )}
        </>
      )}>
      {active
        ? (ends ? t('sysmaint.admin.bodyActiveTimed', ends.start, ends.end) : t('sysmaint.admin.bodyActive', timeText(block?.end_at)))
        : t('sysmaint.admin.bodySoon', timeText(block?.start_at), clockText(secondsLeft))}
    </StripShell>
  )
}
