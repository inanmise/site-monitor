import { CalendarClock, CheckCircle2, Phone, Wrench } from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { messageFor, windowText } from '../../utils/systemMaintenance.js'
import { Alert, AlertDescription, AlertTitle } from '@/components/shadcn/alert'

/**
 * Giriş sayfası BAKIM KARTI (2026-10-02, kullanıcı kararı) — public uçtan (`/api/public/system-maintenance`, oturumsuz)
 * ya da `?session=maintenance` ile. Dört görünüm:
 *  • bakım sürüyor (`active`) → "Sistem bakımda" + pencere; yalnız sistem yöneticileri giriş yapabilir;
 *  • yaklaşan bakım (`announced` / `warning`) → "Planlı bakım: 22:00–23:00";
 *  • `sessionEnded` → oturum bakım nedeniyle kapatıldı (pencere bilgisi varsa onunla);
 *  • bakım TAMAMLANDI (sunucu bildirim süresince `ended`; `?session=maintenance` ile gelinse de) → yeşil "Planlı bakım
 *    tamamlandı; giriş yapabilirsiniz." + gerçekleşen pencere (2026-10-02 kullanıcı isteği). `data-state="completed"`
 *    (oturum kesimi kipinin `ended` değeriyle karışmasın).
 * Mesaj: yöneticinin TR/EN açıklaması (arayüz dilinde; boşsa yalnız standart cümle) + iletişim bilgisi.
 *
 * Test kancaları: `data-slot="login-maintenance"` (`data-state`), `login-maintenance-window`.
 */
export default function MaintenanceLoginCard({ status, sessionEnded = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const state = status?.state && status.state !== 'none' ? status.state : null
  if (!state && !sessionEnded) return null
  if (state === 'ended') {
    const done = windowText(status)
    return (
      <Alert variant="success" role="status" aria-live="polite" data-slot="login-maintenance" data-state="completed">
        <CheckCircle2 aria-hidden="true" />
        <AlertTitle className="line-clamp-none font-bold">{t('sysmaint.login.titleCompleted')}</AlertTitle>
        {done && (
          <AlertDescription className="[overflow-wrap:anywhere]">
            <span data-slot="login-maintenance-window" className="block">{t('sysmaint.login.windowCompleted', done)}</span>
          </AlertDescription>
        )}
      </Alert>
    )
  }
  // Bakım bittiyse (public durum `none`) ama adres hâlâ ?session=maintenance: "yeniden giriş yapabilirsiniz"
  const over = sessionEnded && status?.state === 'none'
  const active = state === 'active' || (sessionEnded && !state && !over)
  const win = windowText(status)
  const msg = messageFor(status, lang)
  const title = sessionEnded ? t('sysmaint.login.titleEnded')
    : active ? t('sysmaint.login.titleActive') : t('sysmaint.login.titleUpcoming')
  return (
    <Alert variant={active ? 'warning' : 'info'} role="status" aria-live="polite" data-slot="login-maintenance"
      data-state={sessionEnded ? 'ended' : state}>
      {active ? <Wrench aria-hidden="true" /> : <CalendarClock aria-hidden="true" />}
      <AlertTitle className="line-clamp-none font-bold">{title}</AlertTitle>
      <AlertDescription className="[overflow-wrap:anywhere]">
        {win && (
          <span data-slot="login-maintenance-window" className="block">
            {active || sessionEnded ? t('sysmaint.login.windowActive', win) : t('sysmaint.login.windowUpcoming', win)}
          </span>
        )}
        <span className="block">
          {over ? t('sysmaint.login.overHint') : active || sessionEnded ? t('sysmaint.login.onlyAdmins') : t('sysmaint.login.upcomingHint')}
        </span>
        {msg && <span className="mt-1 block">{msg}</span>}
        {status?.contact && (
          <span className="mt-1 flex items-center gap-1.5">
            <Phone aria-hidden="true" className="size-3.5 shrink-0" />{t('sysmaint.contact', status.contact)}
          </span>
        )}
      </AlertDescription>
    </Alert>
  )
}
