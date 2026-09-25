import { useEffect, useState } from 'react'
import { ShieldAlert, ShieldCheck, LogIn, Clock } from 'lucide-react'
import { formatDate, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import AlertBanner from './ui/AlertBanner.jsx'

/**
 * Kullanıcının kendi giriş güvenliği özeti — backend `login_info` bloğunun üç sunumu.
 *
 * Gösterilen "önceki giriş" BİLEREK içinde bulunulan oturum DEĞİLDİR: kullanıcı kendi oturumunun
 * başlangıcını görse "bu ben miydim?" sorusunu cevaplayamaz. Backend kaydırmayı giriş anında
 * yapar, bu yüzden değer oturum boyunca sabittir (F5 ile değişmez).
 */

/** Bildirim oturumda BİR kez görünür (AnnouncementBanner.jsx HERO_KEY deseni). */
const NOTICE_KEY = 'sm.login.noticeShown'

/** `t()` çağrıları LİTERAL olmalı — i18n-used-keys testi yalnız literal anahtarları tarar. */
function methodLabel(t, method) {
  if (method === 'PASSWORD') return t('lastLogin.methodPassword')
  if (method === 'REMEMBER_ME') return t('lastLogin.methodRemember')
  return null
}
function reasonLabel(t, reason) {
  if (reason === 'BAD_PASSWORD') return t('lastLogin.reasonBadPassword')
  if (reason === 'TEMP_PASSWORD_EXPIRED') return t('lastLogin.reasonTempExpired')
  return null
}

/**
 * "3 dk önce" — ActivityLog.jsx'teki `rel()` mantığının aynısı, mevcut `act.rel.*` anahtarlarıyla
 * (yeni anahtar gerekmez). Backend UTC'yi 'Z'siz döndürdüğü için ekleniyor; aksi halde tarayıcı
 * yerel saat sanar ve 3 saatlik kayma çıkar.
 */
function relativeTime(t, iso) {
  if (!iso) return null
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : iso + 'Z'
  const then = new Date(s).getTime()
  if (isNaN(then)) return null
  const sec = Math.floor(Math.max(0, Date.now() - then) / 1000)
  if (sec < 60) return t('act.rel.now')
  const min = Math.floor(sec / 60); if (min < 60) return t('act.rel.min', min)
  const hr = Math.floor(min / 60);  if (hr < 24)  return t('act.rel.hour', hr)
  return t('act.rel.day', Math.floor(hr / 24))
}

/** Kart içindeki tek bir ölçü: büyük değer + küçük etiket + isteğe bağlı alt satır. */
function Tile({ label, value, sub, tone, Icon }) {
  return (
    <div className={`lli-tile${tone ? ` lli-tile--${tone}` : ''}`}>
      <div className="lli-tile-label">{Icon && <Icon size={12} aria-hidden="true" />}{label}</div>
      <div className="lli-tile-value">{value || '—'}</div>
      {sub && <div className="lli-tile-sub">{sub}</div>}
    </div>
  )
}

/**
 * Giriş sonrası uyarı şeridi — YALNIZ şüpheli durumda (önceki girişten bu yana başarısız deneme
 * varsa). Temiz girişte hiç render edilmez: bilgi zaten "Etkinliklerim" özetinde ve kullanıcı
 * menüsünde duruyor, her girişte şerit göstermek onu göz ardı edilen gürültüye çevirirdi.
 */
export function LastLoginNotice({ info }) {
  const t = useT()
  const [visible, setVisible] = useState(false)
  const failed = Number(info?.failed_before_login ?? 0)
  const stamp = info?.current_login_at || ''

  useEffect(() => {
    if (!info || failed <= 0) return
    // Anahtar DEĞERİ bu girişin damgası: F5 aynı değeri okur → tekrar göstermez; yeni giriş yeni
    // damga üretir → yeniden gösterir.
    let shown = null
    try { shown = sessionStorage.getItem(NOTICE_KEY) } catch { /* yoksay */ }
    if (shown === (stamp || '1')) return
    try { sessionStorage.setItem(NOTICE_KEY, stamp || '1') } catch { /* yoksay */ }
    setVisible(true)
  }, [info, failed, stamp])

  if (!visible) return null

  const lastFailed = info.last_failed_at ? formatDateSec(info.last_failed_at) : null
  const reason = reasonLabel(t, info.last_failed_reason)
  return (
    <AlertBanner
      tone="warning"
      icon={ShieldAlert}
      title={t('lastLogin.noticeTitle')}
      onDismiss={() => setVisible(false)}
      dismissLabel={t('app.close')}
    >
      <div>{t('lastLogin.noticeFailed', failed)}</div>
      {lastFailed &&
        <div>{t('lastLogin.lastFailedAt')}: {lastFailed}{reason ? ` — ${reason}` : ''}</div>}
      <div>{t('lastLogin.noticeNotYou')}</div>
    </AlertBanner>
  )
}

/**
 * "Etkinliklerim" sayfasının üstündeki kalıcı özet.
 *
 * Düz etiket-değer ızgarası yerine ÖLÇÜ KARTI: kullanıcının sorduğu üç soru (en son ne zaman
 * girdim, şu an hangi oturumdayım, adıma kaç başarısız deneme oldu) öne çıkar; IP/yöntem/sebep
 * gibi ikincil ayrıntılar altta ince bir şeritte kalır. Başarısız deneme varsa kart uyarı
 * tonuna geçer — bilgi taramadan fark edilsin.
 */
export function LastLoginSummary({ info }) {
  const t = useT()
  if (!info) return null

  const failed = Number(info.failed_before_login ?? 0)
  const method = methodLabel(t, info.prev_login_method)
  const reason = reasonLabel(t, info.last_failed_reason)
  const suspicious = failed > 0
  const HeadIcon = suspicious ? ShieldAlert : ShieldCheck

  return (
    <section className={`lli-card${suspicious ? ' lli-card--warn' : ''}`}>
      <header className="lli-card-head">
        <HeadIcon size={16} aria-hidden="true" />
        <h3>{t('lastLogin.summaryTitle')}</h3>
      </header>

      {info.first_login
        ? <p className="lli-empty">{t('lastLogin.firstLogin')}</p>
        : (
          <div className="lli-tiles">
            <Tile
              Icon={LogIn}
              label={t('lastLogin.prevAt')}
              value={formatDateSec(info.prev_login_at)}
              sub={[relativeTime(t, info.prev_login_at), info.prev_login_ip, method].filter(Boolean).join(' · ')}
            />
            <Tile
              Icon={Clock}
              label={t('lastLogin.currentAt')}
              value={formatDateSec(info.current_login_at)}
              sub={relativeTime(t, info.current_login_at)}
            />
            <Tile
              Icon={ShieldAlert}
              label={t('lastLogin.failedCount')}
              value={String(failed)}
              tone={suspicious ? 'warn' : null}
              sub={suspicious ? t('lastLogin.sincePrev') : t('lastLogin.noFailed')}
            />
          </div>
        )}

      {info.last_failed_at && (
        <div className="lli-foot">
          <span className="lli-foot-label">{t('lastLogin.lastFailedAt')}</span>
          <span className="lli-foot-value">{formatDateSec(info.last_failed_at)}</span>
          {reason && <span className="lli-chip">{reason}</span>}
          {info.last_failed_ip && <span className="lli-foot-ip">{info.last_failed_ip}</span>}
        </div>
      )}
    </section>
  )
}

/**
 * Sol menü kullanıcı popover'ındaki giriş bilgisi. Veri ÇEKMEZ — App state'inden prop ile gelir.
 *
 * Popover 220 px: etiket ve değer YAN YANA sığmıyordu (taşıyordu). Etiket üstte küçük, değer
 * altta; saniye de atılıyor (`formatDate`) ve uyarı satırı tam cümle yerine kısa bir sayaç.
 */
export function LastLoginPopoverLines({ info }) {
  const t = useT()
  if (!info) return null
  const failed = Number(info.failed_before_login ?? 0)
  return (
    // shadcn DropdownMenu içinde (kullanıcı menüsü): metin satırları, menü öğesi DEĞİL — etiket üstte küçük,
    // değer altta (menü dar; yan yana dizilim taşıyordu).
    <div className="px-2 pb-1.5 pt-0.5">
      <div className="py-0.5">
        <span className="block text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">{t('lastLogin.popoverPrev')}</span>
        <strong className="block text-xs font-semibold tabular-nums">{info.prev_login_at ? formatDate(info.prev_login_at) : t('lastLogin.never')}</strong>
      </div>
      <div className="py-0.5">
        <span className="block text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">{t('lastLogin.popoverFailed')}</span>
        <strong className="block text-xs font-semibold tabular-nums">{info.last_failed_at ? formatDate(info.last_failed_at) : t('lastLogin.never')}</strong>
      </div>
      {failed > 0 && (
        <div className="mt-1 flex items-center gap-1 text-xs font-semibold text-warning">
          <ShieldAlert size={11} aria-hidden="true" />
          {t('lastLogin.failedShort', failed)}
        </div>
      )}
    </div>
  )
}
