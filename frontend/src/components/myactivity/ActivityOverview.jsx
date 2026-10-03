import { useEffect, useId, useMemo, useState } from 'react'
import {
  ShieldCheck, ShieldAlert, BellOff, BellRing, MonitorSmartphone, Monitor, Smartphone, ListChecks, KeyRound, ShieldX, Moon,
} from 'lucide-react'
import { useToast } from '../ui/Toast.jsx'
import QuietHoursFields from '../ui/QuietHoursFields.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import { quietFromMe, quietEqual, quietErrors, quietIsSet, quietSummary, quietUserPayload } from '../../utils/quietHours.js'
import { formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { eventLabel } from '../admin/audit/auditFormat.js'
import { EventIcon } from './ActivityTimeline.jsx'
import { deviceText, locationText, relTime, isUnusualSignIn, deviceStats, typeBreakdown } from './activityModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Label } from '@/components/shadcn/label'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Switch } from '@/components/shadcn/switch'
import { cn } from '@/lib/utils'

/**
 * Etkinliklerim — özet yüzeyleri: giriş özeti (başlık altında), güvenlik uyarısı, yan kartlar (cihazlar, "neler
 * yaptınız", bildirim tercihi). Hepsi shadcn (Card / Badge / Button / Switch / AlertBanner); sol renk şeridi YOK.
 */

/** "ne zaman · nereden": "2 gün önce · 192.0.2.77 · Amsterdam, Netherlands" */
function whereText(row, t) {
  return [row?.ip_address, locationText(row, t)].filter(Boolean).join(' · ')
}

function methodLabel(t, m) {
  if (m === 'PASSWORD') return t('lastLogin.methodPassword')
  if (m === 'REMEMBER_ME') return t('lastLogin.methodRemember')
  if (m === 'OTP_PUSH') return t('lastLogin.methodOtpPush')     // kodla giriş (2026-10-02)
  if (m === 'OTP_EMAIL') return t('lastLogin.methodOtpEmail')
  return null
}
function reasonLabel(t, r) {
  if (r === 'BAD_PASSWORD') return t('lastLogin.reasonBadPassword')
  if (r === 'TEMP_PASSWORD_EXPIRED') return t('lastLogin.reasonTempExpired')
  if (r === 'ACCOUNT_INACTIVE') return t('lastLogin.reasonAccountInactive')   // 2026-10-02
  return null
}

/**
 * Giriş özeti — backend `login_info` (App state'inden prop; ek çekim YOK). Gösterilen "önceki giriş" bilerek
 * içinde bulunulan oturum DEĞİL (LastLoginInfo.jsx ile aynı sözleşme ve aynı `lastLogin.*` metinleri).
 */
export function SignInSummary({ info }) {
  const t = useT()
  if (!info) return null
  const failed = Number(info.failed_before_login ?? 0)
  const Icon = failed > 0 ? ShieldAlert : ShieldCheck
  const item = 'inline-flex min-w-0 flex-wrap items-baseline gap-x-1.5'
  const label = 'text-muted-foreground'
  return (
    <section data-slot="sign-in-summary" aria-label={t('lastLogin.summaryTitle')}
      className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-muted/30 px-3 py-2.5 text-sm">
      <span className="inline-flex items-center gap-1.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        <Icon aria-hidden="true" className={cn('size-3.5', failed > 0 ? 'text-amber-600 dark:text-amber-400' : 'text-success')} />
        {t('lastLogin.summaryTitle')}
      </span>
      {info.first_login ? (
        <span>{t('lastLogin.firstLogin')}</span>
      ) : (
        <div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1">
          <span className={item}>
            <span className={label}>{t('lastLogin.prevAt')}:</span>
            <strong className="font-semibold tabular-nums">{info.prev_login_at ? formatDateSec(info.prev_login_at) : t('lastLogin.never')}</strong>
            {[relTime(info.prev_login_at, t), info.prev_login_ip, methodLabel(t, info.prev_login_method)].filter(Boolean).map((s, i) => (
              <span key={i} className={cn('text-muted-foreground', /^[\d.:a-f]+$/i.test(s) && 'font-mono')}>· {s}</span>
            ))}
          </span>
          {info.current_login_at && (
            <span className={item}>
              <span className={label}>{t('lastLogin.currentAt')}:</span>
              <span className="tabular-nums">{formatDateSec(info.current_login_at)}</span>
            </span>
          )}
          {failed > 0 && <Badge variant="warning" data-slot="failed-since-prev">{t('myact.failedSincePrev', failed)}</Badge>}
          {info.last_failed_at && (
            <span className={item}>
              <span className={label}>{t('lastLogin.lastFailedAt')}:</span>
              <span className="tabular-nums">{formatDateSec(info.last_failed_at)}</span>
              {[reasonLabel(t, info.last_failed_reason), info.last_failed_ip].filter(Boolean).map((s, i) => (
                <span key={i} className={cn('text-muted-foreground', /^[\d.:a-f]+$/i.test(s) && 'font-mono')}>· {s}</span>
              ))}
            </span>
          )}
        </div>
      )}
    </section>
  )
}

/**
 * Güvenlik uyarısı — seçili aralıkta başarısız/engellenen giriş ya da YENİ AĞDAN (UNUSUAL_IP / GEO_VELOCITY,
 * sunucu tüm geçmişe bakarak işaretler) başarılı giriş varsa. Yeni ağdan başarılı giriş en ciddi aday (biri
 * içeri girmiş olabilir) → tehlike tonu; yalnız denemeler → uyarı tonu. "Bu ben değildim" rehberi + eylemler.
 */
export function SecurityCallout({ summary, onShowFailed, onChangePassword, onReport }) {
  const t = useT()
  if (!summary) return null
  const failedN = summary.failed.total
  const blockedN = summary.blocked.total
  const unusual = summary.signIns.rows.filter(isUnusualSignIn)
  if (!failedN && !blockedN && !unusual.length) return null
  const latestFailed = summary.failed.rows[0]
  const reportRow = unusual[0] || latestFailed
  const btn = 'h-8 bg-background/60 pointer-coarse:h-10'
  return (
    <AlertBanner tone={unusual.length ? 'danger' : 'warning'} icon={ShieldAlert} title={t('myact.alert.title')} className="mb-0">
      <ul data-slot="security-facts" className="my-1 flex list-disc flex-col gap-0.5 pl-4">
        {failedN > 0 && (
          <li>
            {failedN === 1
              ? t('myact.alert.failedOne', relTime(latestFailed?.event_time, t) || '—', whereText(latestFailed, t) || '—')
              : t('myact.alert.failedMany', failedN, relTime(latestFailed?.event_time, t) || '—', whereText(latestFailed, t) || '—')}
          </li>
        )}
        {blockedN > 0 && <li>{blockedN === 1 ? t('myact.alert.blockedOne') : t('myact.alert.blockedMany', blockedN)}</li>}
        {unusual.slice(0, 2).map((r) => (
          <li key={r.id ?? r.event_time}>{t('myact.alert.newNetwork', whereText(r, t) || '—', relTime(r.event_time, t) || '—')}</li>
        ))}
      </ul>
      <p className="m-0">{t('myact.alert.notYou')}</p>
      <div className="mt-2 flex flex-wrap gap-2">
        {failedN > 0 && onShowFailed && (
          <Button type="button" variant="outline" size="sm" className={btn} onClick={onShowFailed}>
            <ShieldX aria-hidden="true" /> {t('myact.alert.showFailed')}
          </Button>
        )}
        {onChangePassword && (
          <Button type="button" variant="outline" size="sm" className={btn} onClick={onChangePassword}>
            <KeyRound aria-hidden="true" /> {t('dev.changePasswordAction')}
          </Button>
        )}
        {reportRow && onReport && (
          <Button type="button" variant="outline" size="sm" className={btn} onClick={() => onReport(reportRow)}
            aria-label={t('a11y.rowAction', formatDateSec(reportRow.event_time), t('dev.reportAction'))}>
            <ShieldAlert aria-hidden="true" /> {t('dev.reportAction')}
          </Button>
        )}
      </div>
    </AlertBanner>
  )
}

const CARD = 'min-w-0 gap-3 py-4 shadow-none'
const CARD_HEAD = 'px-4'
const CARD_TITLE = 'flex items-center gap-2 text-sm'

/** Kart gövdesi yüklenirken — gerçek satır boyutunda iskelet. */
function RowsSkeleton({ n = 3 }) {
  const t = useT()
  return (
    <div className="flex flex-col gap-3" role="status">
      <span className="sr-only">{t('app.loading')}</span>
      {Array.from({ length: n }, (_, i) => (
        <div key={i} className="flex items-center gap-3"><Skeleton className="size-8 rounded-full" /><Skeleton className="h-4 flex-1" /></div>
      ))}
    </div>
  )
}

/** Giriş yapılan cihazlar (giriş örnekleminden) + "Cihazları yönet" (Cihaz Geçmişi paneli, Sheet). */
export function DevicesCard({ summary, loading, onOpenDevices }) {
  const t = useT()
  const rows = summary?.signIns.rows ?? []
  const { devices } = deviceStats(rows)
  const partial = summary && summary.signIns.rows.length < summary.signIns.total
  const shown = devices.slice(0, 4)
  return (
    <Card data-slot="devices-card" className={CARD}>
      <CardHeader className={CARD_HEAD}>
        <CardTitle className={CARD_TITLE}><MonitorSmartphone aria-hidden="true" className="size-4 text-muted-foreground" /> {t('myact.devicesCard')}</CardTitle>
      </CardHeader>
      <CardContent className="px-4">
        {loading && !summary ? <RowsSkeleton /> : shown.length === 0 ? (
          <p className="m-0 text-sm text-muted-foreground">{t('myact.noDevices')}</p>
        ) : (
          <ul className="m-0 flex list-none flex-col divide-y p-0">
            {shown.map((d) => {
              const Icon = d.mobile ? Smartphone : Monitor
              return (
                <li key={d.key} data-slot="device-row" className="flex min-w-0 items-start gap-3 py-2 first:pt-0 last:pb-0">
                  <span aria-hidden="true" className="mt-0.5 inline-flex size-8 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
                    <Icon className="size-4" />
                  </span>
                  <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                    <span className="font-medium [overflow-wrap:anywhere]">{deviceText(d.label, t) || t('dev.genericSession')}</span>
                    <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
                      {[d.count === 1 ? t('myact.devSignInsOne') : t('myact.devSignIns', d.count),
                        t('myact.devLast', relTime(d.last.event_time, t) || '—'),
                        locationText(d.last, t)].filter(Boolean).join(' · ')}
                    </span>
                  </span>
                </li>
              )
            })}
          </ul>
        )}
        {devices.length > shown.length && (
          <p className="m-0 mt-2 text-xs text-muted-foreground">{t('myact.moreDevices', devices.length - shown.length)}</p>
        )}
        {partial && <p className="m-0 mt-2 text-xs text-muted-foreground">{t('myact.sampleNoteSignIns', summary.signIns.rows.length, summary.signIns.total)}</p>}
      </CardContent>
      <CardFooter className="px-4">
        <Button type="button" variant="outline" size="sm" className="w-full pointer-coarse:h-10" onClick={onOpenDevices}>
          {t('myact.manageDevices')}
        </Button>
      </CardFooter>
    </Card>
  )
}

/** "Neler yaptınız" — olay türü dağılımı (örneklemden; kısmi ise not). Satır = tür süzgeci (aria-pressed). */
export function TypeBreakdownCard({ summary, loading, activeType, onPick }) {
  const t = useT()
  const rows = summary?.all.rows ?? []
  const types = typeBreakdown(rows).slice(0, 8)
  if (!loading && summary && types.length === 0) return null
  return (
    <Card data-slot="type-breakdown" className={CARD}>
      <CardHeader className={CARD_HEAD}>
        <CardTitle className={CARD_TITLE}><ListChecks aria-hidden="true" className="size-4 text-muted-foreground" /> {t('myact.byType')}</CardTitle>
      </CardHeader>
      <CardContent className="px-2">
        {loading && !summary ? <div className="px-2"><RowsSkeleton n={4} /></div> : (
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
            {types.map(([type, n]) => {
              const on = activeType === type
              const label = eventLabel(type, t)
              return (
                <li key={type}>
                  <Button type="button" variant="ghost" size="sm" aria-pressed={on} data-type={type}
                    title={t('mondash.filterTip', label)}
                    className={cn('h-auto min-h-9 w-full justify-between gap-2 px-2 py-1.5 font-normal whitespace-normal pointer-coarse:min-h-10',
                      on && 'bg-accent text-accent-foreground')}
                    onClick={() => onPick(on ? '' : type)}>
                    <span className="flex min-w-0 items-center gap-2 text-left">
                      <EventIcon row={{ event_type: type }} className="size-6 [&_svg]:size-3.5" />
                      <span className="min-w-0 [overflow-wrap:anywhere]">{label}</span>
                    </span>
                    <span className="shrink-0 text-muted-foreground tabular-nums">{n}</span>
                  </Button>
                </li>
              )
            })}
          </ul>
        )}
        {summary && !summary.complete && (
          <p className="m-0 mt-2 px-2 text-xs text-muted-foreground">{t('myact.sampleNote', summary.all.rows.length, summary.all.total)}</p>
        )}
      </CardContent>
    </Card>
  )
}

/**
 * E1: kişi webhook push tercihi — kullanıcı YALNIZ kendi bayrağını yazar (sunucu onayı gelmeden anahtar
 * değişmez: App.jsx). Günlükte SKIPPED_USER_OPT_OUT olarak görünür.
 */
export function NotificationCard({ pushOptOut, onChange }) {
  const t = useT()
  const switchId = useId()
  const hintId = useId()
  return (
    <Card data-slot="push-optout" data-off={pushOptOut || undefined} className={CARD}>
      <CardHeader className={CARD_HEAD}>
        <CardTitle className={CARD_TITLE}>
          {pushOptOut ? <BellOff aria-hidden="true" className="size-4 text-muted-foreground" /> : <BellRing aria-hidden="true" className="size-4 text-primary" />}
          {t('myact.notifCard')}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex items-start gap-3 px-4">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          {/* Etiket anahtara bağlı: metne dokunmak da anahtarı çevirir (dokunmatikte küçük hedefin telafisi) */}
          <Label htmlFor={switchId} className="cursor-pointer leading-snug">{t('userpush.optOut')}</Label>
          <span id={hintId} className="text-xs leading-snug text-muted-foreground">{t('userpush.optOutHint')}</span>
        </div>
        <Switch id={switchId} checked={!!pushOptOut} aria-describedby={hintId} className="mt-0.5 shrink-0"
          onCheckedChange={(v) => onChange(v)} />
      </CardContent>
    </Card>
  )
}

/**
 * Kişisel push sessiz saati (2026-10-01, onaylı öneri 15) — kullanıcı YALNIZ kendi penceresini yazar (POST
 * /me/push-quiet-hours). Global push sessiz saatinin kişi eşi: pencerede seçilen seviyenin altındaki push bu kişiye gitmez
 * (teslimat günlüğünde SKIPPED_USER_QUIET_HOURS); KRİTİK ve "düzeldi" push'u etkilenmez. Boş = bugünkü davranış.
 * Kayıt sunucu onayıyla görünür (iyimser güncelleme yok); doğrulama hatası alanın altında.
 */
export function PushQuietHoursCard({ value, onSave }) {
  const t = useT()
  const toast = useToast()
  const saved = useMemo(() => quietFromMe(value), [value])
  const [draft, setDraft] = useState(saved)
  const [busy, setBusy] = useState(false)
  const fe = useFormErrors(saved)
  useEffect(() => { setDraft(saved) }, [saved])
  const dirty = !quietEqual(draft, saved)
  const keys = { start: 'start', end: 'end', days: 'days' }

  async function save() {
    if (fe.check(quietErrors(draft, t, keys))) return
    setBusy(true)
    try {
      const res = await onSave?.(quietUserPayload(draft))
      if (res?.success) toast.success(t('quiet.saved'))
      else toast.error(res?.error || t('quiet.saveError'))
    } catch (e) {
      toast.error(e?.message || t('quiet.saveError'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card data-slot="push-quiet" data-set={quietIsSet(saved) || undefined} className={CARD}>
      <CardHeader className={CARD_HEAD}>
        <CardTitle className={CARD_TITLE}>
          <Moon aria-hidden="true" className={cn('size-4', quietIsSet(saved) ? 'text-primary' : 'text-muted-foreground')} />
          {t('quiet.pushCard')}
        </CardTitle>
        <p className="m-0 text-xs leading-snug text-muted-foreground" data-slot="push-quiet-summary">
          {t('quiet.current', quietSummary(saved, t))}
        </p>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-1 px-4">
        <span className="mb-2 text-xs leading-snug text-muted-foreground">{t('quiet.pushDesc')}</span>
        <QuietHoursFields value={draft} keys={keys} fieldProps={fe.fieldProps} levelHintKey="quiet.levelHintPush" disabled={busy}
          onChange={(next, key) => { setDraft(next); if (key === 'clear') fe.reset(); else if (key) fe.clear(key) }} />
      </CardContent>
      <CardFooter className="flex flex-wrap justify-end gap-2 px-4">
        {dirty && (
          <Button type="button" variant="ghost" size="sm" className="pointer-coarse:h-10" disabled={busy}
            onClick={() => { setDraft(saved); fe.reset() }}>
            {t('quiet.revert')}
          </Button>
        )}
        <Button type="button" size="sm" className="pointer-coarse:h-10" disabled={!dirty || busy} aria-busy={busy || undefined}
          onClick={save}>
          {busy ? t('quiet.saving') : t('quiet.save')}
        </Button>
      </CardFooter>
    </Card>
  )
}
