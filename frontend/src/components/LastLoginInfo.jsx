import { useEffect, useState } from 'react'
import { ShieldAlert } from 'lucide-react'
import { formatDateSec } from '../api/client'
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

/** Boş değerlerde tire — SystemHealth `field()` deseniyle aynı görsel dil. */
function field(label, value, mono = false) {
  return (
    <div className="show-field" key={label}>
      <span className="show-field-label">{label}</span>
      <span className={`show-field-value${mono ? ' show-field-mono' : ''}`}>{value || '—'}</span>
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

/** "Etkinliklerim" sayfasının üstündeki kalıcı özet. */
export function LastLoginSummary({ info }) {
  const t = useT()
  if (!info) return null

  const failed = Number(info.failed_before_login ?? 0)
  const method = methodLabel(t, info.prev_login_method)
  const reason = reasonLabel(t, info.last_failed_reason)

  return (
    <div className="lastlogin-summary">
      <div className="show-section-header">{t('lastLogin.summaryTitle')}</div>
      {info.first_login && <div className="field-hint">{t('lastLogin.firstLogin')}</div>}
      <div className="show-grid-2">
        {field(t('lastLogin.prevAt'), info.prev_login_at ? formatDateSec(info.prev_login_at) : t('lastLogin.never'), true)}
        {field(t('lastLogin.prevIp'), info.prev_login_ip, true)}
        {field(t('lastLogin.currentAt'), info.current_login_at ? formatDateSec(info.current_login_at) : null, true)}
        {field(t('lastLogin.method'), method)}
        {field(t('lastLogin.failedCount'), String(failed))}
        {field(t('lastLogin.lastFailedAt'), info.last_failed_at ? formatDateSec(info.last_failed_at) : t('lastLogin.never'), true)}
        {field(t('lastLogin.lastFailedIp'), info.last_failed_ip, true)}
        {field(t('lastLogin.lastFailedReason'), reason)}
      </div>
    </div>
  )
}

/** Sol menü kullanıcı popover'ındaki iki satır. Veri ÇEKMEZ — App state'inden prop ile gelir. */
export function LastLoginPopoverLines({ info }) {
  const t = useT()
  if (!info) return null
  const failed = Number(info.failed_before_login ?? 0)
  return (
    <div className="sb-user-popover-meta">
      <div>
        <span>{t('lastLogin.popoverPrev')}</span>
        <strong>{info.prev_login_at ? formatDateSec(info.prev_login_at) : t('lastLogin.never')}</strong>
      </div>
      <div>
        <span>{t('lastLogin.popoverFailed')}</span>
        <strong>{info.last_failed_at ? formatDateSec(info.last_failed_at) : t('lastLogin.never')}</strong>
      </div>
      {failed > 0 && <div className="sb-user-popover-meta-warn">{t('lastLogin.noticeFailed', failed)}</div>}
    </div>
  )
}
