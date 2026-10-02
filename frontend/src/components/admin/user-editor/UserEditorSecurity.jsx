import { ChevronDown, Compass, KeyRound, Lock, LockOpen, LogOut, MonitorSmartphone, ShieldAlert } from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import DeviceHistoryPanel from '../../DeviceHistoryPanel.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { SectionCard } from '../userdetail/parts.jsx'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/** Kart eylem düğmesi: telefonda tam genişlik + 40 px, geniş ekranda içerik genişliğinde. */
const ACTION = 'h-10 w-full justify-center sm:h-9 sm:w-auto sm:self-start'

/**
 * Güvenlik sekmesi (2026-10-02, kullanıcı isteği) — var olan hesabın oturum/erişim eylemleri tek yerde, HEPSİ mevcut
 * uçlarla (yeni uç yok):
 *   • Giriş kilidi — kalıcı ya da süreli kilit + "Kilidi Aç" (`POST /admin/users/{id}/unlock`; menüdekiyle aynı kapı:
 *     yönetici / takım yöneticisi). Kilit yoksa son başarılı girişten beri başarısız deneme sayısı.
 *   • Şifre — geçici şifreyi e-postayla gönder (mevcut `AdminAutoResetModal`, yöneticinin kendi şifresiyle onay);
 *     sonuç (gönderildi / gönderilemedi) satır içinde. "Şifre değişimi bekliyor" + geçici şifre geçerliliği.
 *   • Oturum — açık oturumu uzaktan sonlandır (`POST /admin/system/terminate-session`, gerekçeli; YALNIZ global yönetici,
 *     kendi hesabında yok — sunucu da reddeder).
 *   • Ürün turu sıfırla (`POST /admin/users/{id}/tour-reset`).
 *   • Cihaz geçmişi — salt-okunur, `audit_log.read` izniyle; KAPALI başlar ve kapalıyken HİÇ çizilmez (açılınca iki
 *     sorgu atar — eski düzenleme penceresiyle aynı tembel davranış).
 * Görüntüleme kipinde yalnız durum bilgisi ve cihaz geçmişi görünür.
 */
export default function UserEditorSecurity({ ed }) {
  const t = useT()
  const { user, editable, canManage } = ed
  if (!user) return null
  const lock = ed.lock
  const failed = Number(user.failed_since_login ?? 0)
  const canAct = canManage && editable
  const busy = (k) => ed.busy === k

  return (
    <div data-slot="ued-security" className="@container flex min-w-0 flex-col gap-3">
      <div className="grid grid-cols-1 items-start gap-3 @2xl:grid-cols-2">
        <SectionCard icon={Lock} title={t('ued.secLock')} data-section="lock">
          {lock?.kind === 'perm' && (
            <div className="flex flex-col gap-1">
              <ToneBadge tone="danger" data-lock="perm" className="gap-1 self-start"><Lock aria-hidden="true" className="size-3" />{t('usr.permLocked')}</ToneBadge>
              <span className="text-xs leading-relaxed text-muted-foreground">{t('ud.lockHint.perm')}</span>
            </div>
          )}
          {lock?.kind === 'temp' && (
            <div className="flex flex-col gap-1">
              <ToneBadge tone="warning" data-lock="temp" className="gap-1 self-start"><ShieldAlert aria-hidden="true" className="size-3" />{t('ued.lockedTemp')}</ToneBadge>
              <span className="text-xs leading-relaxed text-muted-foreground">{t('ued.lockTempHint', formatDateSec(lock.until))}</span>
            </div>
          )}
          {!lock && (
            <p data-slot="ued-no-lock" className="m-0 text-sm text-muted-foreground">
              {t('ued.lockNone')}{failed > 0 && <> · <span className="text-warning">{t('ued.failedAttempts', failed)}</span></>}
            </p>
          )}
          {lock && canAct && (
            <Button type="button" variant="outline" className={ACTION} disabled={ed.busy != null} aria-busy={busy('perm') || undefined}
              data-slot="ued-unlock" onClick={() => ed.releaseLock('perm')}>
              {busy('perm') ? <Spinner decorative size={14} /> : <LockOpen aria-hidden="true" />}{t('usr.unlock')}
            </Button>
          )}
        </SectionCard>

        <SectionCard icon={KeyRound} title={t('ued.secPassword')} description={canAct ? t('ued.pwdResetDesc') : undefined} data-section="password">
          {user.must_change_password ? (
            <div className="flex flex-col gap-1">
              <ToneBadge tone="warning" data-flag="must-change" className="gap-1 self-start"><KeyRound aria-hidden="true" className="size-3" />{t('ued.mustChangeBadge')}</ToneBadge>
              <span className="text-xs text-muted-foreground">
                {t('ued.mustChange')}{user.temp_password_expires_at ? ` ${t('ued.tempExpires', formatDateSec(user.temp_password_expires_at))}` : ''}
              </span>
            </div>
          ) : !canAct && <p className="m-0 text-sm text-muted-foreground">{t('ued.pwdNothingPending')}</p>}
          {ed.resetResult === 'sent' && <AlertBanner tone="success" className="mb-0">{t('usr.autoResetSent')}</AlertBanner>}
          {ed.resetResult === 'failed' && <AlertBanner tone="danger" className="mb-0">{t('usr.autoResetFailed')}</AlertBanner>}
          {canAct && (
            <>
              {!user.email && <span className="text-xs text-warning">{t('usr.autoResetNoEmail')}</span>}
              <Button type="button" variant="outline" className={ACTION} disabled={!user.email || ed.busy != null}
                data-slot="ued-auto-reset" onClick={ed.openAutoReset}>
                <KeyRound aria-hidden="true" />{t('usr.autoResetBtn')}
              </Button>
            </>
          )}
        </SectionCard>

        {ed.canTerminate && (
          <SectionCard icon={LogOut} title={t('ued.secSession')} description={t('ued.sessionDesc')} data-section="session">
            <Button type="button" variant="outline" data-slot="ued-terminate" disabled={ed.busy != null}
              className={cn(ACTION, 'border-destructive/40 text-destructive hover:bg-destructive/10 hover:text-destructive')}
              onClick={ed.openTerminate}>
              <LogOut aria-hidden="true" />{t('ued.terminate')}
            </Button>
          </SectionCard>
        )}

        {canAct && (
          <SectionCard icon={Compass} title={t('usr.tourLabel')} description={t('ued.tourDesc')} data-section="tour">
            <Button type="button" variant="outline" className={ACTION} disabled={ed.busy != null} aria-busy={busy('tour') || undefined}
              data-slot="ued-tour-reset" onClick={ed.resetTour}>
              {busy('tour') ? <Spinner decorative size={14} /> : <Compass aria-hidden="true" />}{t('usr.tourReset')}
            </Button>
          </SectionCard>
        )}
      </div>

      {/* Cihaz Geçmişi (K8) — SALT-OKUNUR. Yetkisi olmayana HİÇ çizilmez (açan kişi 403'ü hata sanardı); kapalıyken
          içerik DOM'da yok (Collapsible) — açılınca iki sorgu atar. */}
      {ed.canSeeDevices && (
        <Collapsible open={ed.devicesOpen} onOpenChange={ed.setDevicesOpen}>
          <SectionCard icon={MonitorSmartphone} title={t('dev.adminSectionTitle')} data-section="devices"
            bodyClassName={ed.devicesOpen ? undefined : 'hidden'}
            action={(
              <CollapsibleTrigger asChild>
                <Button type="button" variant="ghost" size="sm" className="h-10 sm:h-8" data-slot="ued-devices-toggle">
                  {ed.devicesOpen ? t('ued.devicesHide') : t('ued.devicesShow')}
                  <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', ed.devicesOpen && 'rotate-180')} />
                </Button>
              </CollapsibleTrigger>
            )}>
            <CollapsibleContent>
              <DeviceHistoryPanel userId={user.id} />
            </CollapsibleContent>
          </SectionCard>
        </Collapsible>
      )}
    </div>
  )
}
