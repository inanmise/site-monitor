import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import { readPageSize, writePageSize } from '../../hooks/usePagination.js'
import UserBadge from '../ui/UserBadge.jsx'
import { systemResolverKey } from '../../utils/resolvedBy.js'
import { mailPreviewSrcDoc, mailLogoVariant, MAIL_PREVIEW_SANDBOX } from '../../utils/mailPreview.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import { ALERT_TYPES, alertTypeMeta, alertTypeLabel } from '../../utils/alertTypeMeta.js'
import { formatDuration, durationMs } from '../../utils/incidentMeta.js'
import MonitorStatsSection from '../MonitorStatsSection.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import {
  Check, ShieldAlert, TrendingUp, RefreshCcw, Bell, CheckCircle, AlertCircle, ChevronUp,
  ChevronDown, ChevronRight, Mail, MailX, Clock, Users, Calendar
} from 'lucide-react'

/** Seviye → CSS sınıfı eki. Renkler App.css'te (iki tema); burada yalnız eşleme. */
const levelClass = (lvl) => ({ WARNING: 'warning', HIGH: 'high', CRITICAL: 'critical' })[lvl] ?? 'unknown'
// Süre biçimlendirme BİLİNÇLİ olarak ortak yardımcıdan geliyor (utils/incidentMeta.js).
//
// 2026-08-20'ye kadar burada yerel bir kopya vardı ve birimleri TERSTİ: dakikayı `d`, saati `s`
// ile yazıyordu. 10 dakikalık bir alarm "9d" (gün sanıldı), 3sa5dk ise "3s 5d" (saniye sanıldı)
// görünüyordu — yani ekrandaki en kritik sayı, kesintinin süresi, sistematik olarak yanlış
// okunuyordu. Aynı işin üç ayrı kopyası olması (App.jsx, incidentMeta.js, burası) bu sapmanın
// fark edilmeden yaşamasının sebebiydi; diğer ikisi zaten dk/sa/g kullanıyor.
// incidentMeta.formatDuration birimleri i18n'den alır (incov.unit.*), yani TR/EN tutarlıdır.

// Snapshot expiry date at alarm-creation time: created_at + days_remaining × 1 day.
// Reflects the cert's not_after as it was when the alert fired, not the current value.
function alertExpiryDate(a) {
  if (!a?.created_at || a.days_remaining == null) return null
  const created = new Date(a.created_at)
  if (isNaN(created)) return null
  return new Date(created.getTime() + a.days_remaining * 86_400_000)
}

function AuditRow({ label, by, at, variant, note }) {
  // Palet ARTIK burada değil: satır içi sabit hex CSS'i baypas ettiği için koyu temada
  // açık zeminler okunmuyordu. Renkler .alh-audit--* sınıflarında, iki tema için de tanımlı.
  const kind = ['ack', 'resolve', 'system'].includes(variant) ? variant : 'system'
  return (
    <div className={`alh-audit alh-audit--${kind}`}>
      <span className="alh-audit-icon"><Check size={14} /></span>
      <div className="alh-audit-text">
        <span className="alh-audit-label">{label}</span>
        <span className="alh-audit-meta">
          <UserBadge username={by} inline size="sm" />
          {at && <> &nbsp;·&nbsp; {formatDate(at)}</>}
        </span>
        {/* Gerekçe — zorunluluk ÖNCESİ onaylanmış alarmlarda yok; boş blok çizmemek için
            koşullu. Notsuz eski kayıtlar sayıca çok ve hepsinde boş alıntı görünürdü. */}
        {note && <span className="alh-audit-note">{note}</span>}
      </div>
    </div>
  )
}

function EmailStatusBadge({ status }) {
  const t = useT()
  if (!status) return null
  if (status === 'SENT')
    return <span className="nl-status nl-status-ok">{t('alh.status.sent')}</span>
  if (status === 'SKIPPED_DISABLED')
    return <span className="nl-status nl-status-warn">{t('alh.status.skip')}</span>
  if (status.startsWith('FAILED'))
    return <span className="nl-status nl-status-err" title={status}>{t('alh.status.failed')}</span>
  return <span className="nl-status nl-status-muted">{status}</span>
}

/**
 * Ayni mesaji ayni tetikte alan alicilari TEK satirda toplar.
 *
 * <p>Bir alarm bes kisiye gittiginde bes ayni satir aliniyordu; ekranin tamami tek bir gonderimin
 * tekrarina gidiyor, mesajin kendisi ise hicbir yerde okunamiyordu. Anahtar (tetik + durum +
 * mesaj): metin farkliysa gruplanmazlar, cunku o zaman gercekten farkli gonderimlerdir.
 */
export function groupPushRows(rows) {
  const by = new Map()
  for (const p of rows ?? []) {
    const key = `${p.trigger}|${p.status}|${p.message ?? ''}`
    if (!by.has(key)) by.set(key, [])
    by.get(key).push(p)
  }
  // EN YENİDEN eskiye. Eskiden ekleme sırası korunuyordu ve liste sunucudan artan geldiği için
  // en yeni teslimat EN ALTTA kalıyordu: operatör "az önce ne gitti" sorusunu listenin sonuna
  // inerek cevaplıyordu. E-posta bölümü zaten azalan sıradaydı; iki bölüm ters yöndeydi.
  const stamp = (g) => g.reduce((mx, r) => {
    const v = r.sent_at || r.created_at || ''
    return v > mx ? v : mx
  }, '')
  return [...by.values()].sort((a, b) => stamp(b).localeCompare(stamp(a)))
}

/**
 * Saklanan damga -> yerel okunur tarih. Push teslimat damgalari da artik UTC yaziliyor
 * (UserPushService.ISO), dolayisiyla mail kartiyla AYNI kural gecerli: zone tasimayan damgaya
 * 'Z' eklenir. Ham ISO basmak ayni modalda iki farkli zaman dili uretiyordu.
 */
function fmtStamp(iso, locale) {
  if (!iso) return '—'
  try {
    const s = iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z'
    return new Date(s).toLocaleString(locale, {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch { return iso }
}

/** Push tetigi -> mevcut nl-card renk varyanti (yeni CSS sinifi uydurulmaz). */
const PUSH_TRIGGER_CLS = {
  OPEN: 'initial', ESCALATION: 'escalation', RE_ALERT: 'daily',
  RESEND: 'manual', RESOLVE: 'resolution',
}

/**
 * Tek webhook gonderimi (ya da ayni mesaji alan alici grubu) — TIKLANINCA acilir ve
 * kullaniciya GERCEKTEN giden metni gosterir. Duzen ve siniflar NotifLogCard ile ayni;
 * mail ve webhook ayni modalda iki farkli sekilde davranmasin.
 */
/**
 * Teslimat durum kodunu okunur karşılığına çevirir; BİLİNMEYEN kodu ham hâliyle bırakır.
 *
 * <p>Neden eşleme tablosu: {@code t(key, arg)} ikinci argümanı yedek DEĞİL, {0} yerine geçen
 * değerdir; anahtar yoksa {@code useT} ham anahtarı basar. Doğrudan {@code t('...' + status)}
 * yazmak, arka uca yeni bir durum eklendiği gün ekrana {@code alh.push.status.YENI_KOD}
 * yazdırırdı. Burada bilinen kümede karşılığı, dışında ham kod gösterilir.
 *
 * <p>Ham kod {@code title} olarak korunur: destek ve günlükler o kodla arıyor.
 */
const PUSH_STATUS_KEYS = new Set([
  'SENT', 'FAILED', 'PENDING', 'CIRCUIT_OPEN',
  'SKIPPED_DISABLED', 'SKIPPED_MONITOR_OFF', 'SKIPPED_NO_CONTACT', 'SKIPPED_NO_ID',
  'SKIPPED_NO_PRIOR', 'SKIPPED_NO_RECIPIENT', 'SKIPPED_NO_RECIPIENTS', 'SKIPPED_QUIET_HOURS',
  'SKIPPED_REALERT_OFF', 'SKIPPED_TEAM_OFF', 'SKIPPED_TYPE_OFF', 'SKIPPED_USER_OPT_OUT',
])

export function statusLabel(t, status) {
  return PUSH_STATUS_KEYS.has(status) ? t('alh.push.status.' + status) : (status || '—')
}

function PushDeliveryGroup({ rows }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)
  const head = rows[0]
  const many = rows.length > 1
  const uniqueRecipients = new Set(rows.map(r => r.username)).size
  const cls = PUSH_TRIGGER_CLS[head.trigger] ?? 'other'
  const statusCls = head.status === 'SENT' ? 'ok'
    : (head.status === 'FAILED' || head.status === 'CIRCUIT_OPEN') ? 'danger' : 'muted'

  // Her alıcı KENDİ kutusunda: eskiden yan yana ayırıcısız basılıyordu ve iki kusur üretiyordu —
  // (1) kişiler birbirine yapışıp ayırt edilemiyordu, (2) "katman kararı" satırları tekrarlanınca
  // "scope decisionscope decision…" gibi bozuk bir dize gibi görünüyordu (metin doğruydu, ayırıcı
  // yoktu). Sicil de yanında: aynı ada sahip iki kişiyi ancak sicil ayırır ve operatör push
  // ayarlarındaki kaydı sicille arar.
  const who = (p) => (p.username === '-'
    ? <span key={p.id} className="nl-who nl-who--system"><em>{t('userpush.systemRow')}</em></span>
    : (
      <span key={p.id} className="nl-who">
        <UserBadge username={p.username} displayName={p.display_name} size="sm" inline nameOnly />
        <span className="nl-who-id">{p.username}</span>
      </span>
    ))

  return (
    <div className={`nl-card nl-card--${cls}${open ? ' is-open' : ''}`}>
      <div className="nl-card-header" role="button" tabIndex={0} aria-expanded={open}
        onClick={() => setOpen(o => !o)}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setOpen(o => !o) } }}>
        {/* Ham durum kodu tek başına "bu bozuk mu?" sorusunu üretiyordu: SKIPPED_NO_RECIPIENTS
            gören operatör bunu arıza sanıyor, oysa çoğu kez şiddet kuralının doğru çalışmasıdır
            (grup asgari seviyesi YÜKSEK+ iken UYARI alarmı aday bulamaz). Kod korunuyor —
            günlüklerde ve destekte aranan şey o — ama yanına okunur karşılığı yazılıyor. */}
        <span className={`userpush-badge userpush-badge--${statusCls}`} title={head.status}>
          {statusLabel(t, head.status)}
        </span>
        <div className="nl-recipient">
          {/* BENZERSIZ alici sayilir: ayni alarma iki kez "Tekrar Bildir" basildiginda
              iki satir ayni gruba duser ve rows.length "2 alici" derdi — oysa tek kisiye
              iki kez gidilmistir. */}
          {many ? <strong>{t('alh.push.recipients', uniqueRecipients)}</strong> : who(head)}
        </div>
        <div className="nl-right">
          <span className="userpush-modal-trigger">{t('userpush.trigger.' + head.trigger)}</span>
          <span className="nl-time">{fmtStamp(head.sent_at || head.created_at, locale)}</span>
          <span className="nl-chevron">{open ? <ChevronUp size={12} /> : <ChevronDown size={12} />}</span>
        </div>
      </div>

      {open && (
        <div className="nl-card-body">
          <div className="nl-detail-row nl-detail-row--body">
            <span className="nl-detail-label">{t('alh.push.message')}</span>
            <span className="nl-detail-val nl-message">{head.message || '\u2014'}</span>
          </div>
          {many && (
            <div className="nl-detail-row nl-detail-row--body">
              <span className="nl-detail-label">{t('alh.push.who')}</span>
              <span className="nl-detail-val nl-who-list">{rows.map(who)}</span>
            </div>
          )}
          {head.http_status != null && (
            <div className="nl-detail-row">
              <span className="nl-detail-label">HTTP</span>
              <span className="nl-detail-val">{head.http_status}</span>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function NotifLogCard({ log: l, alertLevel }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)

  // Tetikleyici görsel kimliği: yalnız ikon + etiket burada; RENKLER .nl-trigger--* ve
  // .nl-card--* sınıflarında (koyu tema karşılıklarıyla). Eskiden satır içi sabit hex'ti.
  const triggerMeta = {
    INITIAL:       { Icon: ShieldAlert, textKey: 'alh.trigger.initial',    cls: 'initial' },
    ESCALATION:    { Icon: TrendingUp,  textKey: 'alh.trigger.escalation', cls: 'escalation' },
    DAILY_REALERT: { Icon: RefreshCcw,  textKey: 'alh.trigger.daily',      cls: 'daily' },
    MANUAL:        { Icon: Bell,        textKey: 'alh.trigger.manual',     cls: 'manual' },
    RESOLUTION:    { Icon: CheckCircle, textKey: 'alh.trigger.resolution', cls: 'resolution' },
  }

  const trigBase = triggerMeta[l.trigger]
  const trig = trigBase
    ? { ...trigBase, text: t(trigBase.textKey) }
    : { Icon: Mail, text: l.trigger, cls: 'other' }

  function fmtDateTime(iso) {
    if (!iso) return '—'
    try {
      const s = iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z'
      return new Date(s).toLocaleString(locale, {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
      })
    } catch { return iso }
  }

  const sentDate = fmtDateTime(l.sent_at)

  return (
    <div className={`nl-card nl-card--${trig.cls}${open ? ' is-open' : ''}`}>
      <div className="nl-card-header" onClick={() => setOpen(o => !o)}>
        <span className={`nl-trigger-badge nl-trigger--${trig.cls}`}>
          <trig.Icon size={11} /> {trig.text}
        </span>
        <div className="nl-recipient">
          <strong>{l.recipient_name}</strong>
          {l.recipient_role && l.recipient_role !== 'COMBINED' && (
            <span className="role-badge" style={{ marginLeft: 6, fontSize: '.75em' }}>{l.recipient_role}</span>
          )}
          <span className="nl-email">{l.recipient_email}</span>
        </div>
        <div className="nl-right">
          <EmailStatusBadge status={l.email_status} />
          <span className="nl-time">{sentDate}</span>
          <span className="nl-chevron">{open ? <ChevronUp size={12} /> : <ChevronDown size={12} />}</span>
        </div>
      </div>

      {open && (
        <div className="nl-card-body">
          {l.email_from && (
            <div className="nl-detail-row">
              <span className="nl-detail-label">{t('alh.notif.from')}</span>
              <span className="nl-detail-val">{l.email_from}</span>
            </div>
          )}
          {l.recipient_email && (
            <div className="nl-detail-row">
              <span className="nl-detail-label">{t('alh.notif.to')}</span>
              <span className="nl-detail-val">{l.recipient_email}</span>
            </div>
          )}
          {l.cc && (
            <div className="nl-detail-row">
              <span className="nl-detail-label">{t('alh.notif.cc')}</span>
              <span className="nl-detail-val">{l.cc}</span>
            </div>
          )}
          <div className="nl-detail-row">
            <span className="nl-detail-label">{t('alh.notif.subject')}</span>
            <span className="nl-detail-val nl-subject">{l.subject || '—'}</span>
          </div>
          <div className="nl-detail-row nl-detail-row--body">
            <span className="nl-detail-label">{t('alh.notif.content')}</span>
            {l.message && l.message.trimStart().startsWith('<') ? (
              <iframe
                className="nl-message-iframe"
                srcDoc={mailPreviewSrcDoc(l.message, {
                  // Gönderimde hangi logo varyantı iliştirildiyse önizlemede de o gösterilir
                  // (çözülme mailleri daima "ok"). Bkz. mailLogoVariant.
                  logoVariant: mailLogoVariant({ trigger: l.trigger, level: alertLevel }),
                })}
                sandbox={MAIL_PREVIEW_SANDBOX}
                title={l.subject}
              />
            ) : (
              <span className="nl-detail-val nl-message">{l.message || '—'}</span>
            )}
          </div>
          <div className="nl-detail-row">
            <span className="nl-detail-label">{t('alh.notif.emailStatus')}</span>
            <span className="nl-detail-val">
              <EmailStatusBadge status={l.email_status} />
              {l.email_status?.startsWith('FAILED') && (
                <span className="nl-error-detail">{l.email_status.replace('FAILED: ', '')}</span>
              )}
            </span>
          </div>
          {l.webhook_status && l.webhook_status !== 'SKIPPED' && (
            <div className="nl-detail-row">
              <span className="nl-detail-label">{t('alh.notif.webhook')}</span>
              <span className="nl-detail-val">{l.webhook_status}</span>
            </div>
          )}
          <div className="nl-detail-row">
            <span className="nl-detail-label">{t('alh.notif.sentAt')}</span>
            <span className="nl-detail-val">{sentDate}</span>
          </div>
        </div>
      )}
    </div>
  )
}

function NotifyResultModal({ alertId, alertInfo, currentResult, onClose }) {
  const t = useT()
  // Akordiyon: iki bölüm de KAPALI açılır, tıklanan açılır ve diğeri kapanır.
  // Gerekçe: modal açılır açılmaz onlarca satır dökülüyordu; operatör önce hangi kanala
  // bakacağını seçemiyor, aradığı kaydı bulmak için kaydırmak zorunda kalıyordu.
  const [openSection, setOpenSection] = useState(null)   // null | 'email' | 'push'
  const toggleSection = (k) => setOpenSection(cur => (cur === k ? null : k))
  const [history, setHistory]       = useState([])
  const [loadingHistory, setLoading] = useState(true)
  const [pushRows, setPushRows] = useState([])

  useEffect(() => {
    api.admin.getAlertNotifications(alertId).then(res => {
      setLoading(false)
      if (res?.success) setHistory(res.data)
    })
    // Kanal-ayrımlı webhook (push) teslimatları — uç patlarsa bölüm boş kalır, modal çalışır.
    api.admin.getAlertPushDeliveries(alertId)
      .then(res => { if (res?.success) setPushRows(Array.isArray(res.data) ? res.data : []) })
      .catch(() => {})
  }, [alertId])

  const { notifications = [], contacts_attempted } = currentResult?.data ?? {}

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box nl-modal" onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
          <h3 style={{ margin: 0 }}>{t('alh.notifModal.title')}</h3>
          {alertInfo && (
            <span className={`alh-modal-state alh-modal-state--${alertInfo.resolved ? 'closed' : 'open'}`}>
              {alertInfo.resolved ? t('alh.notifModal.closed') : t('alh.notifModal.open')} · {alertInfo.domain}
            </span>
          )}
        </div>

        {notifications.length > 0 && (
          <div className="nl-section">
            <div className="nl-section-title">
              {t('alh.notifModal.lastSent')}
              <span className="nl-count">{t('alh.notifModal.recipients', contacts_attempted)}</span>
            </div>
            {notifications.some(n => n.email_status === 'SKIPPED_DISABLED') && (
              <div className="nl-banner-warn">{t('alh.notifModal.emailOff')}</div>
            )}
            {notifications.map((n, i) => (
              <div key={n.email ?? `nq-${i}`} className="nl-quick-row">
                <strong>{n.name}</strong>
                <span className="role-badge">{n.role}</span>
                <span className="nl-email">{n.email}</span>
                <EmailStatusBadge status={n.email_status} />
              </div>
            ))}
          </div>
        )}

        <div className="nl-section" style={{ marginTop: notifications.length > 0 ? 20 : 0 }}>
          <div className="nl-section-title nl-section-title--toggle" role="button" tabIndex={0}
            aria-expanded={openSection === 'email'}
            onClick={() => toggleSection('email')}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSection('email') } }}>
            <span className="nl-section-caret">
              {openSection === 'email' ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
            </span>
            {t('alh.notifModal.allHistory')}
            {!loadingHistory && <span className="nl-count">{t('alh.notifModal.records', history.length)}</span>}
          </div>

          {openSection === 'email' && (<>
            {loadingHistory && <div className="nl-empty">{t('alh.loading')}</div>}

            {!loadingHistory && history.length === 0 && (
              <div className="nl-empty">{t('alh.notifModal.noNotifs')}</div>
            )}

            {!loadingHistory && history.map((l, i) => (
              <NotifLogCard key={l.id ?? i} log={l} alertLevel={alertInfo?.alert_level} />
            ))}
          </>)}
        </div>

        {/* Webhook (push) teslimatları — kanal AYRIMLI: kime, ne zaman, mesaj, sonuç. Çözüm
            teslimatları da burada (tetik etiketi ayırır). Kayıt yoksa kısa notla yine çizilir:
            "hiç gitmedi" bilgisi de bilgidir. */}
        <div className="nl-section" style={{ marginTop: 20 }}>
          <div className="nl-section-title nl-section-title--toggle" role="button" tabIndex={0}
            aria-expanded={openSection === 'push'}
            onClick={() => toggleSection('push')}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSection('push') } }}>
            <span className="nl-section-caret">
              {openSection === 'push' ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
            </span>
            {t('alh.webhookSection')}
            <span className="nl-count">{t('alh.notifModal.records', pushRows.length)}</span>
          </div>
          {openSection === 'push' && (<>
            {pushRows.length === 0 && <div className="nl-empty">{t('alh.webhookNone')}</div>}
            {groupPushRows(pushRows).map((g, i) => (
              <PushDeliveryGroup key={g[0].id ?? i} rows={g} />
            ))}
          </>)}
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>{t('alh.notifModal.close')}</button>
        </div>
      </div>
    </div>
  )
}

/**
 * "Tekrar Bildir" onay pop-up'i: gonderim ONCESI alici listesi, KANAL KANAL.
 *
 * <p>Kullanici e-posta ve webhook alicilarini AYRI AYRI cikarabilir — "bu kisiye mail gitmesin
 * ama push gitsin" mesru bir istek. Gonderilemeyecek webhook alicilari da GORUNUR (sebebiyle,
 * pasif satir olarak): sessiz bir "gitmedi" yerine operatorun neden gitmedigini gordugu bir liste.
 */
function ReNotifyConfirmModal({ domain, recipients, webhook, sending, onSend, onClose }) {
  const t = useT()
  const [uncheckedEmails, setUncheckedEmails] = useState(() => new Set())
  const [uncheckedUsers, setUncheckedUsers] = useState(() => new Set())

  const pushRows = webhook?.recipients || []
  const sendableUsers = pushRows.filter(r => r.status === 'PENDING')
  const blockReason = webhook?.channel_enabled === false
    ? 'CHANNEL_DISABLED' : (webhook?.block_reason || null)

  const toggleIn = (setter) => (key) => setter(s => {
    const n = new Set(s); if (n.has(key)) n.delete(key); else n.add(key); return n
  })
  const toggleEmail = toggleIn(setUncheckedEmails)
  const toggleUser = toggleIn(setUncheckedUsers)

  const selectedEmails = recipients.length - uncheckedEmails.size
  const selectedUsers = sendableUsers.length - uncheckedUsers.size
  const selectedCount = selectedEmails + selectedUsers

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box nl-modal" onClick={e => e.stopPropagation()}>
        <h3 style={{ marginTop: 0 }}>{t('alh.renotifyModal.title')}</h3>
        <p style={{ fontSize: '.88em', color: 'var(--text-light)' }}>{t('alh.renotifyModal.desc', domain)}</p>

        <div className="nl-section">
          <strong>{t('alh.renotifyModal.emailSection')}</strong>
          {recipients.length === 0 ? (
            <div className="nl-empty">{t('alh.renotifyModal.noRecipients')}</div>
          ) : recipients.map(r => (
            <label key={r.email} className="checkbox-label nl-quick-row" style={{ width: '100%' }}>
              <input type="checkbox" checked={!uncheckedEmails.has(r.email)} onChange={() => toggleEmail(r.email)} />
              <strong>{r.name || r.email}</strong>
              {/* K9: takim satirinda kaynak etiketi ("Grup: X" / "Takim maili") -- backend role
                  alaninda gonderir. Gelmezse eski sabit "Takim" etiketine duser. */}
              <span className="role-badge">{r.kind === 'TEAM' ? (r.role || t('alh.renotifyModal.kindTeam')) : (r.role || '')}</span>
              <span className="nl-email">{r.email}</span>
            </label>
          ))}
        </div>

        <div className="nl-section">
          <strong>{t('alh.renotifyModal.webhookSection')}</strong>
          {blockReason ? (
            <div className="nl-empty">{t('alh.renotifyModal.webhookOff', blockReason)}</div>
          ) : pushRows.length === 0 ? (
            <div className="nl-empty">{t('alh.renotifyModal.noRecipients')}</div>
          ) : pushRows.map(r => {
            const willSend = r.status === 'PENDING'
            return (
              <label key={r.username} className="checkbox-label nl-quick-row" style={{ width: '100%' }}
                title={willSend ? undefined : r.status}>
                <input type="checkbox" disabled={!willSend}
                  checked={willSend && !uncheckedUsers.has(r.username)}
                  onChange={() => toggleUser(r.username)} />
                <strong>{r.display_name || r.username}</strong>
                <span className="role-badge">
                  {willSend ? t('alh.renotifyModal.willSend') : t('alh.renotifyModal.wontSend')}
                </span>
                <span className="nl-email">{willSend ? r.username : r.status}</span>
              </label>
            )
          })}
        </div>

        <div style={{ fontSize: '.82em', color: 'var(--text-light)', marginTop: 10 }}>
          {t('alh.renotifyModal.selectedTotal', selectedCount, selectedEmails, selectedUsers)}
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose} disabled={sending}>
            {t('alh.renotifyModal.cancel')}
          </button>
          <button
            className="btn btn-warning"
            disabled={sending || selectedCount === 0}
            onClick={() => onSend([...uncheckedEmails], [...uncheckedUsers])}
          >
            {t('alh.renotifyModal.send')}
          </button>
        </div>
      </div>
    </div>
  )
}



/**
 * "Ne kadardır AÇIK" rozeti — açık bir alarmın en kritik sayısı ve buraya kadar HİÇ
 * gösterilmiyordu ({@code formatDuration} yalnız kapalı alarmlarda kullanılıyordu).
 *
 * <p>Eşiği aşan alarmlar vurgulanır: uzun süredir açık kalan bir alarm ya çözülmemiş ya
 * unutulmuştur; ikisi de görünmesi gereken durumlar.
 */
function OpenDurationBadge({ createdAt, staleHours }) {
  const t = useT()
  if (!createdAt) return null
  // UTC olarak ayrıştır. Backend zaman damgalarını saat dilimi EKİ OLMADAN yazıyor
  // ("2026-08-16T09:00:00") ve JS böyle bir dizeyi YEREL saat sanır. Kapalı karttaki
  // "açık kalma süresi" iki naive damganın FARKI olduğu için kayma sönümleniyordu; burada
  // ise şimdiki zamanla (mutlak) karşılaştırıyoruz — sönümlenmez. Europe/Istanbul'da 3 saatlik
  // sapma üretiyordu (testte yakalandı: 3 saatlik alarm "6s" görünüyordu).
  const utc = createdAt.endsWith('Z') || createdAt.includes('+') ? createdAt : createdAt + 'Z'
  const startedMs = new Date(utc).getTime()
  const ms = Date.now() - startedMs
  if (!Number.isFinite(ms) || ms < 0) return null
  const stale = ms >= staleHours * 3_600_000
  return (
    <span className={`alh-open-for${stale ? ' is-stale' : ''}`}
      title={stale ? t('alh.openForStaleTip', staleHours) : t('alh.openForTip')}>
      <Clock size={11} /> {t('alh.openFor', formatDuration(ms, t))}
    </span>
  )
}

/**
 * "Bu ay N. kez" rozeti — aynı domain + tip için son 30 gündeki alarm sayısı.
 *
 * <p>Tekrar eden sorunu tekil olandan ayırır. 1 ise rozet ÇIZILMEZ: her karta "1. kez" yazmak
 * gürültüdür ve asıl sinyali (tekrar edenler) boğar.
 */
function RepeatBadge({ count }) {
  const t = useT()
  if (!count || count < 2) return null
  return (
    <span className="alh-repeat" title={t('alh.repeatTip', count)}>
      <RefreshCcw size={11} /> {t('alh.repeat', count)}
    </span>
  )
}

/**
 * AÇIK bırakılan grupların oturum anahtarı.
 *
 * <p>Semantik BİLEREK "açılmışlar" — "katlanmışlar" değil. Gruplar varsayılan olarak KAPALI
 * geliyor: hepsi açıkken sayfa uzuyor ve "hangi konudan kaç alarm var" özeti kayboluyordu
 * (kullanıcı geri bildirimi). Kapalıyken ekranda yalnız konu başlıkları ve sayıları kalıyor;
 * ilgilenilen konu tek tıkla açılıyor.
 *
 * <p>Anahtar da değişti: eskiden burada KATLANMIŞ tipler saklanıyordu. Aynı anahtar
 * kullanılsaydı eski oturumdaki liste "açılmışlar" diye okunur ve tam ters davranış çıkardı.
 */
const GROUP_EXPAND_KEY = 'alh-expanded-groups'

/**
 * Alarmları KONUSUNA (alert_type) göre katlanabilir gruplara ayırır.
 *
 * <p>"Hangi konudan hangi alarmlar var" sorusunun ekrandaki karşılığı: düz bir listede 20 satır
 * arasında 5 DNS + 3 sertifika + 12 erişim alarmı olduğunu görmek için tek tek okumak gerekiyordu.
 *
 * <p><b>Gruplama GÖRÜNEN SAYFA içindedir</b> — sunucu sayfalaması korunur. Bu yüzden başlıktaki
 * sayı "bu sayfada N" demektir, tipin TOPLAMI değil; toplam yukarıdaki tip rozetinde duruyor.
 * İki sayı farklı anlamda olduğu için çubuğun altında bir satırla açıkça söyleniyor (aksi halde
 * kullanıcı çelişki sanar).
 *
 * <p>Tek tip varsa gruplama YAPILMAZ: tek başlık altında tek grup, bilgi taşımayan bir çerçeveden
 * ibaret olurdu. Bu kural gömülü modda da (tek domainin 2-3 alarmı) doğru davranışı veriyor.
 */
function AlertTypeGroups({ alerts, listClassName, expanded, onToggle, renderCard }) {
  const t = useT()
  const order = []
  const byType = new Map()
  for (const a of alerts) {
    const key = a.alert_type ?? '?'
    if (!byType.has(key)) { byType.set(key, []); order.push(key) }
    byType.get(key).push(a)
  }

  if (order.length < 2) {
    return <div className={listClassName}>{alerts.map(renderCard)}</div>
  }

  return order.map(type => {
    const items = byType.get(type)
    const isOpenGroup = expanded.has(type)
    const { icon: Icon, color } = alertTypeMeta(type)
    return (
      <div className="alh-group" key={type}>
        <button type="button" className="alh-group-head" onClick={() => onToggle(type)}
          aria-expanded={isOpenGroup}>
          <span className="alh-group-chevron">
            {isOpenGroup ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
          </span>
          <Icon size={14} style={{ color, flexShrink: 0 }} />
          <span className="alh-group-title" style={{ color }}>{alertTypeLabel(t, type)}</span>
          <span className="alh-group-count">{items.length}</span>
        </button>
        {isOpenGroup && <div className={listClassName}>{items.map(renderCard)}</div>}
      </div>
    )
  })
}

/**
 * @param types İzleme modallarında GÖMÜLÜ kullanım için: yalnız bu alarm tipleri listelenir.
 *              Verilmezse (bağımsız Alarm Geçmişi ekranı) hiçbir tip süzgeci uygulanmaz.
 *              Süzme SUNUCUDA yapılır — istemcide süzmek yalnız açık sayfayı süzer, sayfalama
 *              ve tip/seviye sayaçları yanlış kalırdı (arama/seviye filtreleriyle aynı gerekçe).
 */
export default function AlertHistory({ domain = null, urlSync = false, types = null }) {
  // Dizi kimliği her render'da değişir; load() bağımlılığına DİZİ koymak sonsuz döngü demek.
  // Tek bir dizeye indirgeniyor.
  const typesParam = Array.isArray(types) && types.length > 0 ? types.join(',') : null
  const t = useT()
  const { showConfirm, showNoteConfirm } = useDialog()
  const toast = useToast()
  const [alerts,       setAlerts]       = useState([])
  const [tab,          setTab]          = useState(() => (urlSync && readUrlParam('tab', null) === 'closed' ? 'closed' : 'open'))
  const [page,         setPage]         = useState(() => (urlSync ? readUrlInt('page', 1) - 1 : 0))
  const [pageSize,     setPageSize]     = useState(() => (urlSync && readUrlInt('ps', null)) || readPageSize('alert-history'))
  const [total,        setTotal]        = useState(0)
  const [closedFrom,   setClosedFrom]   = useState(() => (urlSync ? readUrlParam('from', null) : null))
  const [closedTo,     setClosedTo]     = useState(() => (urlSync ? readUrlParam('to', null) : null))
  const [loading,      setLoading]      = useState(false)
  const [notifyModal,  setNotifyModal]  = useState(null)
  const [notifying,    setNotifying]    = useState(null)
  const [renotifyModal,   setRenotifyModal]   = useState(null)   // Tekrar Bildir onay pop-up'ı
  const [renotifySending, setRenotifySending] = useState(false)
  const [typeFilter,   setTypeFilter]   = useState(() => (urlSync ? readUrlParam('type', '') : ''))   // '' = tüm tipler
  const [typeCounts,   setTypeCounts]   = useState({})
  const [selected,     setSelected]     = useState(() => new Set())   // toplu seçim (yalnız açık sekme)
  const [bulkBusy,     setBulkBusy]     = useState(false)
  // ── Arama ve filtreler (yalnız bağımsız sayfada; gömülü modda domain zaten sabit) ──
  // Hepsi SUNUCUYA gider: istemci tarafında süzmek yalnız açık sayfayı süzer ve sayfalamayla
  // "3 sonuç" derken aslında 90 sonuç olur.
  const [search,       setSearch]       = useState(() => (urlSync ? readUrlParam('q', '') : ''))
  const [searchTerm,   setSearchTerm]   = useState(search)   // debounce'lanmış hâli (isteğe giden)
  const [levelFilter,  setLevelFilter]  = useState(() => (urlSync ? readUrlParam('level', '') : ''))
  const [teamFilter,   setTeamFilter]   = useState(() => (urlSync ? readUrlParam('team', '') : ''))
  const [ackFilter,    setAckFilter]    = useState(() => (urlSync ? readUrlParam('ack', '') : ''))
  const [levelCounts,  setLevelCounts]  = useState({})
  const [unackedTotal, setUnackedTotal] = useState(0)
  const [staleTotal,   setStaleTotal]   = useState(0)
  const [staleHours,   setStaleHours]   = useState(24)
  const [teams,        setTeams]        = useState([])
  // Şerit KAPALI başlar — sekiz izleme sayfasının hepsinde böyle; burada `true` bırakmak
  // tutarsızlıktı. Ayrıca sayfa açılışında ekranı doldurmuyor: önce alarmlar görünüyor,
  // sayaçlara ihtiyaç duyan tek tıkla açıyor.
  //
  // Kapanınca seviye filtresi TEMİZLENMEZ (izleme sayfalarından farkı): burada aynı filtre
  // araç çubuğundaki açılırda da duruyor, yani gizli bir filtre kalmıyor. İzleme sayfalarında
  // tek erişim noktası kartlar olduğu için orada temizlemek doğru.
  const [statsVisible, setStatsVisible] = useState(false)
  // Katlanan grup tipleri — oturum boyunca korunur (sayfa değişince kapattığın grup açılmasın).
  const [expanded, setExpanded] = useState(() => {
    try { return new Set(JSON.parse(sessionStorage.getItem(GROUP_EXPAND_KEY) || '[]')) }
    catch { return new Set() }
  })
  const toggleGroup = useCallback((type) => {
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(type)) next.delete(type); else next.add(type)
      try { sessionStorage.setItem(GROUP_EXPAND_KEY, JSON.stringify([...next])) } catch { /* depolama kapalı */ }
      return next
    })
  }, [])

  // Yazarken her tuşta istek atma — 300 ms sessizlikten sonra tek istek.
  useEffect(() => {
    const id = setTimeout(() => setSearchTerm(search), 300)
    return () => clearTimeout(id)
  }, [search])

  // Takım listesi yalnız bağımsız sayfada ve bir kez.
  useEffect(() => {
    if (!urlSync) return
    api.admin.getTeams().then(res => { if (res?.success) setTeams(res.data ?? []) }).catch(() => {})
  }, [urlSync])

  const levelLabel = {
    WARNING: t('alh.level.warning'), HIGH: t('alh.level.high'), CRITICAL: t('alh.level.critical'),
  }
  // Tip etiketi/ikonu/rengi ARTIK YEREL DEĞİL: utils/alertTypeMeta.js tek kaynak.
  // Buradaki yerel harita yalnız 11 tip tanıyordu (backend'de 28) — keyword/ping/HTTP/sayfa/
  // sentetik/alan-adı alarmları ham enum adıyla görünüyor ve FİLTRELENEMİYORDU.
  function TypeChip({ type, size = 13 }) {
    const { icon: Icon, color } = alertTypeMeta(type)
    return (
      <span className="alert-type alh-type-chip" style={{ color }}>
        <Icon size={size} />
        {alertTypeLabel(t, type)}
      </span>
    )
  }

  // ── İstatistik kartları ────────────────────────────────────────────────────
  // Sayılar SUNUCUDAN gelir (level_counts / unacked_total). Sayfa içinden hesaplanamaz:
  // 81 alarmın 20'si ekranda dururken "Kritik: 12" yazmak yanıltıcı olurdu.
  const levelTotal = Object.values(levelCounts).reduce((a, b) => a + b, 0)
  const statItems = [
    { key: '',         Icon: Bell,        label: t('alh.statTotal'),    value: levelTotal,                  cls: 'total'    },
    { key: 'CRITICAL', Icon: AlertCircle, label: t('alh.statCritical'), value: levelCounts.CRITICAL ?? 0,   cls: 'critical' },
    { key: 'HIGH',     Icon: TrendingUp,  label: t('alh.statHigh'),     value: levelCounts.HIGH ?? 0,       cls: 'high'     },
    { key: 'WARNING',  Icon: ShieldAlert, label: t('alh.statWarning'),  value: levelCounts.WARNING ?? 0,    cls: 'warning'  },
    { key: 'unacked',  Icon: Bell,        label: t('alh.statUnacked'),  value: unackedTotal,                cls: 'warning',
      hint: t('mondash.unackedHint') },
    // YALNIZ açık sekmede: kapalı sekmede bu sayı "24 saatten eski" demek olurdu,
    // "24 saattir AÇIK" değil — iki farklı şey ve ikincisi kullanıcının sorduğu.
    ...(tab === 'open' ? [{ key: 'stale', Icon: Clock, label: t('alh.statStale', staleHours),
                    value: staleTotal, cls: 'high', hint: t('alh.statStaleHint', staleHours) }] : []),
  ]

  // "Sahiplenilmemiş" kartı seviye DEĞİL, sahiplenme boyutunu filtreler — tek kart şeridinde
  // iki farklı boyut olduğu için tıklama burada ayrıştırılır.
  function onLevelCardClick(key) {
    // "Uzun süredir açık" kartı SAYAÇ — tıklanınca filtre uygulamaz. Sunucuda karşılığı olan bir
    // parametre yok; sahte bir istemci-tarafı süzme eklemek sayfalamayla yanıltıcı olurdu
    // (ekrandaki 20 satırdan 3'ünü gösterip "3 tane" demek). Bilinçli olarak gösterge bırakıldı.
    if (key === 'stale') return
    if (key === 'unacked') { setAckFilter(a => (a === 'unack' ? '' : 'unack')); setLevelFilter(''); return }
    setAckFilter('')
    setLevelFilter(l => (l === key ? '' : key))
  }

  const levelOptions = [
    { value: '',         label: t('alh.allLevels') },
    { value: 'CRITICAL', label: levelLabel.CRITICAL },
    { value: 'HIGH',     label: levelLabel.HIGH },
    { value: 'WARNING',  label: levelLabel.WARNING },
  ]
  const ackOptions = [
    { value: '',      label: t('alh.allAckStates') },
    { value: 'unack', label: t('alh.unackedOnly') },
    { value: 'ack',   label: t('alh.ackOnly') },
  ]
  const teamOptions = [
    { value: '', label: t('alh.allTeams') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name })),
  ]

  // CSV, listeyle AYNI parametreleri kullanır (sayfalama hariç — dosya tüm sonucu içerir).
  const csvParams = {
    resolved: tab === 'closed' ? 'true' : 'false',
    ...(domain ? { domain } : {}),
    ...(typesParam ? { alertTypes: typesParam } : {}),
    ...(typeFilter ? { alertType: typeFilter } : {}),
    ...(searchTerm.trim() ? { q: searchTerm.trim() } : {}),
    ...(levelFilter ? { level: levelFilter } : {}),
    ...(teamFilter ? { teamId: teamFilter } : {}),
    ...(ackFilter ? { acknowledged: ackFilter === 'ack' ? 'true' : 'false' } : {}),
    ...(tab === 'closed' && closedFrom ? { resolvedSince: closedFrom } : {}),
    ...(tab === 'closed' && closedTo ? { resolvedUntil: closedTo } : {}),
  }

  const hasActiveFilters = !!(search || levelFilter || teamFilter || ackFilter || typeFilter
                              || closedFrom || closedTo)
  function clearFilters() {
    setSearch(''); setLevelFilter(''); setTeamFilter(''); setAckFilter('')
    setTypeFilter(''); setClosedFrom(null); setClosedTo(null)
  }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const params = {
        resolved: tab === 'closed' ? 'true' : 'false',
        page,
        size: pageSize,
      }
      if (tab === 'closed') {
        if (closedFrom) params.resolvedSince = closedFrom
        if (closedTo)   params.resolvedUntil = closedTo
      }
      if (domain) params.domain = domain
      if (typesParam) params.alertTypes = typesParam
      if (typeFilter) params.alertType = typeFilter
      if (searchTerm.trim()) params.q = searchTerm.trim()
      if (levelFilter) params.level = levelFilter
      if (teamFilter)  params.teamId = teamFilter
      if (ackFilter)   params.acknowledged = ackFilter === 'ack' ? 'true' : 'false'
      const res = await api.admin.getAlerts(params)
      if (res?.success) {
        setAlerts(res.data ?? [])
        setTotal(res.total ?? 0)
        setTypeCounts(res.type_counts ?? {})
        setLevelCounts(res.level_counts ?? {})
        setUnackedTotal(res.unacked_total ?? 0)
        setStaleTotal(res.stale_total ?? 0)
        setStaleHours(res.stale_hours ?? 24)
      } else if (res != null) {
        toast.error(res?.error || t('alh.loadError'))
      }
    } finally {
      setLoading(false)
    }
  }, [tab, page, pageSize, closedFrom, closedTo, domain, typesParam, typeFilter,
      searchTerm, levelFilter, teamFilter, ackFilter, t, toast])

  useEffect(() => { load() }, [load])
  // Filtre değişince 1. sayfaya dön — aksi halde 5. sayfada daralan sonuçta BOŞ ekran kalır.
  useEffect(() => { setPage(0) }, [tab, pageSize, closedFrom, closedTo, typeFilter,
                                   searchTerm, levelFilter, teamFilter, ackFilter])
  // Liste bağlamı değişince seçim sıfırlansın (sekme/sayfa/filtre) — bayat id'ler seçili kalmasın
  useEffect(() => { setSelected(new Set()) }, [tab, page, pageSize, closedFrom, closedTo, typeFilter, domain])

  function applyQuickRange(days) {
    const now = new Date()
    const from = new Date(now.getTime() - days * 86400000)
    setClosedFrom(from.toISOString().slice(0, 19))
    setClosedTo(now.toISOString().slice(0, 19))
  }

  /** Zorunlu gerekçe modalinin ortak seçenekleri — dört çağrı noktasında (tekli/toplu × onay/çöz)
   *  aynı kural ve aynı ipucu metni kullanılsın diye tek yerde. */
  function noteOpts(extra) {
    return {
      noteHint: t('alh.note.hint'),
      noteOkText: t('alh.note.ok'),
      noteLabel: t('alh.note.label'),
      placeholder: t('alh.note.placeholder'),
      chips: [t('alh.note.chip1'), t('alh.note.chip2'), t('alh.note.chip3'),
              t('alh.note.chip4'), t('alh.note.chip5')],
      cancelText: t('alh.ackDialog.cancel'),
      ...extra,
    }
  }

  async function ack(id) {
    const alert = alerts.find(a => a.id === id)
    const res = await showNoteConfirm(noteOpts({
      title: t('alh.ackDialog.title'),
      message: t('alh.ackDialog.msg', alert?.domain ?? ''),
      variant: 'warning',
      confirmText: t('alh.ackDialog.confirm'),
    }))
    if (!res?.confirmed) return
    const r = await api.admin.acknowledgeAlert(id, res.note)
    if (r?.success === false) { toast.error(r?.error || t('alh.note.error')); return }
    load()
  }

  async function resolve(id) {
    const alert = alerts.find(a => a.id === id)
    const ok = await showNoteConfirm(noteOpts({
      title: t('alh.resolveDialog.title'),
      message: t('alh.resolveDialog.msg', alert?.domain ?? ''),
      variant: 'success',
      confirmText: t('alh.resolveDialog.confirm'),
      cancelText: t('alh.resolveDialog.cancel'),
      placeholder: t('alh.note.placeholderResolve'),
    }))
    if (!ok?.confirmed) return
    try {
      const res = await api.admin.resolveAlert(id, ok.note)
      if (res?.success === false) {
        toast.error(res?.error || t('alh.resolveError'))
      } else {
        toast.success(t('alh.resolveSuccess'))
      }
    } catch {
      toast.error(t('alh.resolveError'))
    } finally {
      load()
    }
  }

  // Tekrar Bildir: önce alıcı önizlemesi → onay pop-up'ı; gönderim sendReNotify ile yapılır.
  async function reNotify(id) {
    setNotifying(id)
    const res = await api.admin.previewReNotify(id)
    setNotifying(null)
    if (res?.success) {
      const alert = alerts.find(a => a.id === id)
      setRenotifyModal({ alertId: id, domain: alert?.domain || '',
        recipients: res.data?.recipients || [], webhook: res.data?.webhook || null })
    } else {
      toast.error(res?.error || t('alh.renotifyModal.previewError'))
    }
  }

  async function sendReNotify(excludeEmails, excludeUsernames = []) {
    if (!renotifyModal) return
    setRenotifySending(true)
    try {
      const body = {}
      if (excludeEmails.length) body.excludeEmails = excludeEmails
      if (excludeUsernames.length) body.excludeUsernames = excludeUsernames
      const res = await api.admin.reNotifyAlert(renotifyModal.alertId,
        Object.keys(body).length ? body : undefined)
      if (res?.success) {
        const count = res.data?.recipients_queued ?? res.data?.contacts_queued ?? 0
        toast.success(t('alh.notifyQueued', count))
        setRenotifyModal(null)
        load()
      } else {
        toast.error(res?.error || 'Error')
      }
    } finally {
      setRenotifySending(false)
    }
  }

  // ── Toplu seçim + toplu işlem (yalnız açık sekme) ──
  const toggleSelect = (id) => setSelected(s => {
    const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n
  })
  const allSelected = alerts.length > 0 && alerts.every(a => selected.has(a.id))
  const toggleSelectAll = () => setSelected(() => allSelected ? new Set() : new Set(alerts.map(a => a.id)))

  async function bulkAction(action) {
    const ids = [...selected]
    if (ids.length === 0) return
    const meta = {
      acknowledge: { title: t('alh.bulk.ackTitle'),      msg: t('alh.bulk.ackMsg', ids.length),      variant: 'warning', confirm: t('alh.ack') },
      resolve:     { title: t('alh.bulk.resolveTitle'),  msg: t('alh.bulk.resolveMsg', ids.length),  variant: 'success', confirm: t('alh.resolve') },
      're-notify': { title: t('alh.bulk.renotifyTitle'), msg: t('alh.bulk.renotifyMsg', ids.length), variant: 'warning', confirm: t('alh.renotify') },
    }[action]
    // Onayla/çöz TOPLU yolda da gerekçe ister — yalnız teklide istenseydi zorunluluk delinirdi
    // (tek alarmı seçip "toplu onayla" demek notsuz bir kaçış olurdu). Tekrar bildirimde not
    // aranmaz: orada bir alarm kapatılmıyor, yalnız bildirim yeniden gönderiliyor.
    const needsNote = action === 'acknowledge' || action === 'resolve'
    let note = null
    if (needsNote) {
      const res0 = await showNoteConfirm(noteOpts({
        title: meta.title, message: meta.msg, variant: meta.variant, confirmText: meta.confirm,
        placeholder: action === 'resolve' ? t('alh.note.placeholderResolve') : t('alh.note.placeholder'),
        noteHint: t('alh.note.hintBulk', ids.length),
      }))
      if (!res0?.confirmed) return
      note = res0.note
    } else {
      const confirmed = await showConfirm({
        title: meta.title, message: meta.msg, variant: meta.variant,
        confirmText: meta.confirm, cancelText: t('alh.resolveDialog.cancel'),
      })
      if (!confirmed) return
    }
    setBulkBusy(true)
    let res
    try { res = await api.admin.bulkAlertAction(action, ids, note) }
    finally { setBulkBusy(false) }
    if (res?.success) {
      const { processed = 0, skipped = 0, failed = 0 } = res.data ?? {}
      let msg = t('alh.bulk.done', processed)
      if (skipped) msg += ' · ' + t('alh.bulk.skipped', skipped)
      if (failed)  msg += ' · ' + t('alh.bulk.failed', failed)
      if (failed) toast.error(msg); else toast.success(msg)
      setSelected(new Set())
      load()
    } else {
      toast.error(res?.error || t('alh.resolveError'))
    }
  }

  function openNotifyHistory(id) {
    const alert = alerts.find(a => a.id === id)
    setNotifyModal({ alertId: id, alertInfo: alert, result: null })
  }

  function parseContacts(json) {
    if (!json) return []
    try {
      const v = JSON.parse(json)
      return Array.isArray(v) ? v : []
    } catch { return [] }
  }

  const isOpen   = tab === 'open'
  const isClosed = tab === 'closed'
  const totalPages = Math.max(1, Math.ceil(total / pageSize))

  // Paylaşılabilir URL (yalnız Alarm Geçmişi SEKMESİ — gömülü modallarda kapalı: enabled guard).
  // Paylaşılabilir bağlantı: görünümü ÜRETEN her şey adres çubuğunda yaşar; varsayılan değer
  // param üretmez (temiz URL). Bağlantıyı alan kişi AYNI listeyi açar — eskiden yalnız sayfa
  // numarası taşınıyordu, filtreler kayboluyordu.
  useUrlQuerySync({
    tab: tab !== 'open' ? tab : null,
    type: typeFilter || null,
    q: searchTerm.trim() || null,
    level: levelFilter || null,
    team: teamFilter || null,
    ack: ackFilter || null,
    from: closedFrom || null,
    to: closedTo || null,
    page: page > 0 ? page + 1 : null,
    ps: (pageSize !== 50 || page > 0) ? pageSize : null,
  }, { enabled: urlSync })

  return (
    <div className="admin-section">
      <div className="alh-header">
        <div className="alh-tabs">
          <button
            type="button"
            className={`alh-tab alh-tab-open${isOpen ? ' is-active' : ''}`}
            onClick={() => setTab('open')}
          >
            <AlertCircle size={13} />
            {t('alh.tabOpen')}
          </button>
          <button
            type="button"
            className={`alh-tab alh-tab-closed${isClosed ? ' is-active' : ''}`}
            onClick={() => setTab('closed')}
          >
            <CheckCircle size={13} />
            {t('alh.tabClosed')}
          </button>
        </div>
        <button className="btn btn-secondary btn-sm-p" onClick={load} disabled={loading}>
          <RefreshCcw size={13} /> {t('alh.refresh')}
        </button>
      </div>

      {/* ── İstatistik şeridi + filtre çubuğu — YALNIZ bağımsız sayfada ──
             Gömülü modda (monitör/sertifika modalının "Alarm Geçmişi" sekmesi) domain zaten
             sabit; orada takım/arama filtresi anlamsız olur ve modalı gereksiz uzatır. ── */}
      {urlSync && (
        <>
          <MonitorStatsSection
            loading={loading} total={statItems[0].value}
            statsVisible={statsVisible} onToggle={() => setStatsVisible(v => !v)}
            items={statItems} activeFilter={levelFilter || null}
            onStatClick={onLevelCardClick}
            onClearFilter={() => setLevelFilter('')}
            shownCount={alerts.length} />

          <div className="upt-toolbar alh-toolbar">
            <SearchableSelect value={levelFilter} onChange={setLevelFilter} options={levelOptions} />
            {teamOptions.length > 1 && (
              <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} searchThreshold={2} />
            )}
            <SearchableSelect value={ackFilter} onChange={setAckFilter} options={ackOptions} />
            <input className="upt-search" type="text" placeholder={t('alh.searchPlaceholder')}
              value={search} onChange={e => setSearch(e.target.value)} />
            {hasActiveFilters && (
              <button type="button" className="btn btn-secondary btn-sm-p" onClick={clearFilters}>
                {t('alh.clearFilters')}
              </button>
            )}
            {/* CSV: EKRANDAKİ filtrelerin aynısıyla. Ayrı bir filtre yüzeyi olsaydı
                "ekranda 12 satır vardı, dosyada 800 çıktı" sürprizi kaçınılmazdı. */}
            <a className="hist-csv-btn" href={api.admin.getAlertsCsvUrl(csvParams)}
              title={t('alh.csvTip')}>{t('alh.csv')}</a>
          </div>
        </>
      )}

      {/* ── Tip filtre pill'leri — canlı sayılarla ── */}
      <div className="inv-stats-pills" style={{ marginBottom: 14 }}>
        <button
          type="button"
          className={`inv-stat-pill${typeFilter === '' ? ' is-selected' : ''}`}
          style={typeFilter === '' ? { borderColor: 'var(--text-muted)', background: 'rgba(100,116,139,.12)' } : undefined}
          onClick={() => setTypeFilter('')}
        >
          {t('alh.typeAll')}: <strong>{Object.values(typeCounts).reduce((s, n) => s + n, 0)}</strong>
        </button>
        {ALERT_TYPES
          .filter(type => (typeCounts[type] ?? 0) > 0 || typeFilter === type)
          .map(type => {
            const meta = alertTypeMeta(type)
            const Icon = meta.icon
            const selected = typeFilter === type
            return (
              <button
                key={type}
                type="button"
                className={`inv-stat-pill${selected ? ' is-selected' : ''}`}
                style={selected ? { borderColor: meta.color, background: meta.color + '1a' } : undefined}
                onClick={() => setTypeFilter(selected ? '' : type)}
              >
                <Icon size={12} style={{ color: meta.color, flexShrink: 0 }} />
                {alertTypeLabel(t, type)}: <strong>{typeCounts[type] ?? 0}</strong>
              </button>
            )
          })}
      </div>

      {/* Grup sayıları SAYFA İÇİdir, tip rozetleri TOPLAMI gösterir — iki farklı sayı.
             Ayrılmazsa kullanıcı çelişki sanar. Yalnız gruplama gerçekten yapılıyorsa çıkar. */}
      {new Set(alerts.map(a => a.alert_type)).size > 1 && (
        <div className="alh-group-note">{t('alh.groupNote')}</div>
      )}

      {isClosed && (
        <div className="alh-filter-bar">
          <div className="alh-quick-pills">
            {[
              { key: '24h', days: 1 },
              { key: '7d',  days: 7 },
              { key: '30d', days: 30 },
              { key: '90d', days: 90 },
            ].map(({ key, days }) => (
              <button
                key={key}
                type="button"
                className="alh-quick-pill"
                onClick={() => applyQuickRange(days)}
              >
                {t(`alh.quick.${key}`)}
              </button>
            ))}
            {(closedFrom || closedTo) && (
              <button
                type="button"
                className="alh-quick-pill alh-quick-clear"
                onClick={() => { setClosedFrom(null); setClosedTo(null) }}
              >
                {t('alh.quick.clear')}
              </button>
            )}
          </div>
          {(closedFrom || closedTo) && (
            <span className="alh-filter-summary">
              {closedFrom && <>{formatDate(closedFrom)}</>}
              {closedFrom && closedTo && ' → '}
              {closedTo && <>{formatDate(closedTo)}</>}
            </span>
          )}
        </div>
      )}

      {loading && <LoadingBlock label={t('alh.loading')} fullWidth />}

      {!loading && alerts.length === 0 && isOpen && (
        <div className="empty-state">{t('alh.noOpen')}</div>
      )}

      {!loading && alerts.length === 0 && isClosed && (
        <div className="empty-state">{t('alh.noClosed')}</div>
      )}

      {isOpen && alerts.length > 0 && (
        <div className="alh-bulk-bar">
          <label className="alh-bulk-all">
            <input
              type="checkbox"
              checked={allSelected}
              ref={el => { if (el) el.indeterminate = selected.size > 0 && !allSelected }}
              onChange={toggleSelectAll}
            />
            <span>{selected.size > 0 ? t('alh.bulk.selected', selected.size) : t('alh.bulk.selectAll')}</span>
          </label>
          {selected.size > 0 && (
            <div className="alh-bulk-actions">
              <button className="btn btn-secondary btn-sm-p" disabled={bulkBusy} onClick={() => bulkAction('acknowledge')}>{t('alh.ack')}</button>
              <button className="btn btn-warning btn-sm-p"   disabled={bulkBusy} onClick={() => bulkAction('re-notify')}>{t('alh.renotify')}</button>
              <button className="btn btn-primary btn-sm-p"   disabled={bulkBusy} onClick={() => bulkAction('resolve')}>{t('alh.resolve')}</button>
              <button className="btn btn-secondary btn-sm-p" disabled={bulkBusy} onClick={() => setSelected(new Set())}>{t('alh.bulk.clear')}</button>
            </div>
          )}
        </div>
      )}

      {isOpen && alerts.length > 0 && (
        <AlertTypeGroups alerts={alerts} listClassName="alert-list"
          expanded={expanded} onToggle={toggleGroup} renderCard={(a) => {
            const notifiedList = parseContacts(a.notified_contacts)
            return (
              <div key={a.id} className={`alert-card alert-${a.alert_level?.toLowerCase()}`}>

                <div className="alert-card-header">
                  <input
                    type="checkbox"
                    className="alh-card-check"
                    checked={selected.has(a.id)}
                    onChange={() => toggleSelect(a.id)}
                    aria-label={t('alh.bulk.selectOne')}
                  />
                  <span className={`alert-level-badge alh-lvl-bg--${levelClass(a.alert_level)}`}>
                    {levelLabel[a.alert_level] || a.alert_level}
                  </span>
                  <TypeChip type={a.alert_type} />
                  <strong className="alert-domain">{a.domain}</strong>
                  <OpenDurationBadge createdAt={a.created_at} staleHours={staleHours} />
                  <RepeatBadge count={a.repeat_count} />
                  {(a.email_failed_count ?? 0) > 0 && (
                    <span className="alert-send-failed" title={t('alh.sendFailedTip')}>
                      <MailX size={12} /> {t('alh.sendFailed')}
                    </span>
                  )}
                  {a.days_remaining != null && (
                    <span className="alert-days">{t('alh.days', a.days_remaining)}</span>
                  )}
                </div>

                <p className="alert-message">{a.message}</p>

                <div className="alert-meta">
                  <span>{t('alh.created')} {formatDate(a.created_at)}</span>
                  {a.last_re_alert_at && (
                    <span>{t('alh.lastNotif')} {formatDate(a.last_re_alert_at)}</span>
                  )}
                </div>

                {notifiedList.length > 0 && (
                  <div className="alert-notified">
                    <span className="notified-label">{t('alh.notified')}</span>
                    {notifiedList.map((c, i) => (
                      <span key={c.email ?? `nc-${i}`} className="notified-chip" title={c.email}>
                        <UserBadge displayName={c.name} email={c.email} inline size="sm" /> <em>({c.role})</em>
                      </span>
                    ))}
                  </div>
                )}

                {a.acknowledged && (
                  <div style={{ margin: '10px 0' }}>
                    <AuditRow
                      label={t('alh.acknowledged')}
                      by={a.acknowledged_by}
                      at={a.acknowledged_at}
                      variant="ack"
                      note={a.acknowledged_note}
                    />
                  </div>
                )}

                <div className="alert-actions">
                  {!a.acknowledged && (
                    <button className="btn btn-secondary btn-sm-p" onClick={() => ack(a.id)}>
                      {t('alh.ack')}
                    </button>
                  )}
                  <button
                    className="btn btn-warning btn-sm-p"
                    onClick={() => reNotify(a.id)}
                    disabled={notifying === a.id}
                  >
                    {notifying === a.id ? t('alh.sending') : t('alh.renotify')}
                  </button>
                  <button
                    className="btn btn-secondary btn-sm-p"
                    onClick={() => openNotifyHistory(a.id)}
                    title={t('alh.notifHistory')}
                  >
                    {t('alh.history')}
                  </button>
                  <button className="btn btn-primary btn-sm-p" onClick={() => resolve(a.id)}>
                    {t('alh.resolve')}
                  </button>
                </div>

              </div>
            )
          }} />
      )}

      {isClosed && alerts.length > 0 && (
        <div className="alert-history-wrap">
          <AlertTypeGroups alerts={alerts} listClassName="alert-history-cards"
            expanded={expanded} onToggle={toggleGroup} renderCard={(a) => (
              <div key={a.id} className="alert-history-card">
                <div className={`ahc-stripe alh-lvl-bg--${levelClass(a.alert_level)}`} />

                <div className="ahc-body">
                  <div className="ahc-top">
                    <strong className="ahc-domain">{a.domain}</strong>
                    <TypeChip type={a.alert_type} size={12} />
                    <RepeatBadge count={a.repeat_count} />
                    <span className={`ahc-level alh-lvl--${levelClass(a.alert_level)}`}>
                      {levelLabel[a.alert_level] || a.alert_level}
                    </span>
                    {a.days_remaining != null && (
                      <span className="ahc-days">{t('alh.days', a.days_remaining)}</span>
                    )}
                  </div>

                  {(() => {
                    const expDate = a.alert_type === 'EXPIRY' ? alertExpiryDate(a) : null
                    const hasMeta = a.sy_team_name || a.ug_team_name || a.cert_tier != null || expDate
                    if (!hasMeta) return null
                    return (
                      <div className="ahc-meta">
                        {expDate && (
                          <span className="ahc-chip ahc-chip-expiry">
                            <Calendar size={11}/> {t('alh.expiryWas')}: <strong>{formatDate(expDate.toISOString())}</strong>
                          </span>
                        )}
                        {a.sy_team_name && (
                          <span className="ahc-chip ahc-chip-team">
                            <Users size={11}/> {t('alh.syTeam')}: <strong>{a.sy_team_name}</strong>
                          </span>
                        )}
                        {a.ug_team_name && (
                          <span className="ahc-chip ahc-chip-team">
                            <Users size={11}/> {t('alh.ugTeam')}: <strong>{a.ug_team_name}</strong>
                          </span>
                        )}
                        {a.cert_tier != null && (
                          <span className={`ahc-chip ahc-chip-tier tier-badge-${a.cert_tier}`}>
                            T{a.cert_tier}
                          </span>
                        )}
                      </div>
                    )
                  })()}

                  <div className="ahc-timeline">
                    <div className="ahc-tl-item">
                      <span className="ahc-tl-icon"><ShieldAlert size={13} /></span>
                      <div>
                        <div className="ahc-tl-label">{t('alh.tlCreated')}</div>
                        <div className="ahc-tl-val">{formatDate(a.created_at)}</div>
                      </div>
                    </div>

                    {a.acknowledged ? (
                      <div className="ahc-tl-item ahc-tl-ack">
                        <span className="ahc-tl-icon"><Check size={13} /></span>
                        <div>
                          <div className="ahc-tl-label">{t('alh.tlAck')}</div>
                          <div className="ahc-tl-val">
                            <UserBadge username={a.acknowledged_by} inline size="sm" />
                            {a.acknowledged_at && <> &nbsp;·&nbsp; {formatDate(a.acknowledged_at)}</>}
                          </div>
                          {a.acknowledged_note && (
                            <div className="ahc-tl-note">{a.acknowledged_note}</div>
                          )}
                        </div>
                      </div>
                    ) : (
                      <div className="ahc-tl-item ahc-tl-noack">
                        <span className="ahc-tl-icon alh-tl-empty">—</span>
                        <div>
                          <div className="ahc-tl-label">{t('alh.tlAckLabel')}</div>
                          <div className="ahc-tl-val alh-tl-empty">{t('alh.tlNotAcked')}</div>
                        </div>
                      </div>
                    )}

                    <div className="ahc-tl-item ahc-tl-resolve">
                      <span className="ahc-tl-icon"><CheckCircle size={13} /></span>
                      <div>
                        <div className="ahc-tl-label">{t('alh.tlResolved')}</div>
                        <div className="ahc-tl-val">
                          {/* Deger bir SICIL ya da SISTEM JETONU olabilir. Eskiden yalniz 'system'
                              ozel-durumlaniyordu; 'inventory_delete' gibi jetonlar KISI ROZETI olarak
                              ciziliyor ve kullanici alarmin neden kapandigini anlayamiyordu. */}
                          {systemResolverKey(a.resolved_by)
                            ? <strong>{t(systemResolverKey(a.resolved_by))}</strong>
                            : <UserBadge username={a.resolved_by} inline size="sm" />}
                          {a.resolved_at && <> &nbsp;·&nbsp; {formatDate(a.resolved_at)}</>}
                        </div>
                        {a.resolved_note && (
                          <div className="ahc-tl-note">{a.resolved_note}</div>
                        )}
                      </div>
                    </div>
                  </div>

                  <div className="ahc-stats">
                    <span className="ahc-stat">
                      <Mail size={12}/> {t('alh.mailsSent', a.email_sent_count ?? 0)}
                    </span>
                    <span className={`ahc-stat${(a.email_failed_count ?? 0) > 0 ? ' ahc-stat-failed' : ''}`}>
                      <MailX size={12}/> {t('alh.mailsFailed', a.email_failed_count ?? 0)}
                    </span>
                    {a.resolved_at && a.created_at && (
                      <span className="ahc-stat ahc-stat-duration">
                        <Clock size={12}/> {t('alh.openDuration')}: <strong>{formatDuration(durationMs(a.created_at, a.resolved_at), t)}</strong>
                      </span>
                    )}
                  </div>

                  <div className="ahc-footer">
                    <button
                      className="btn btn-secondary btn-sm-p"
                      onClick={() => openNotifyHistory(a.id)}
                    >
                      {t('alh.notifHistory')}
                    </button>
                  </div>
                </div>
              </div>
            )} />
        </div>
      )}

      {!loading && (
        <PaginationBar
          page={page + 1} totalPages={totalPages} totalItems={total}
          rangeStart={total === 0 ? 0 : page * pageSize + 1}
          rangeEnd={Math.min((page + 1) * pageSize, total)}
          pageSize={pageSize}
          onPageChange={p => setPage(p - 1)}
          onPageSizeChange={n => { setPageSize(n); writePageSize('alert-history', n) }}
        />
      )}

      {notifyModal && (
        <NotifyResultModal
          alertId={notifyModal.alertId}
          alertInfo={notifyModal.alertInfo}
          currentResult={notifyModal.result}
          onClose={() => setNotifyModal(null)}
        />
      )}
      {renotifyModal && (
        <ReNotifyConfirmModal
          domain={renotifyModal.domain}
          recipients={renotifyModal.recipients}
          webhook={renotifyModal.webhook}
          sending={renotifySending}
          onSend={sendReNotify}
          onClose={() => { if (!renotifySending) setRenotifyModal(null) }}
        />
      )}
    </div>
  )
}
