/**
 * Denetim kaydı biçimlendirme kuralları — SAF fonksiyonlar (React yok, DOM yok).
 *
 * <p>Saf olması bilinçli: etiket sözlüğü, sınıf eşlemesi ve ayrıntı ayrıştırması render
 * edilmeden test edilebilsin. Ekran bileşenleri bunları yalnız çağırır.
 */
import { agoText } from '../../../utils/relativeTime.js'

/**
 * Olay türü → renk sınıfı.
 *
 * <p>Eski eşleme yalnız SONEKE bakıyordu (create/update/delete/login/logout) ve kategori
 * boyutu yoktu: {@code ACCESS_DENIED} nötr `ev-other`'a düşüyordu, yani ENGELLENEN bir erişim
 * ekranda sıradan bir olay gibi görünüyordu. Kural listesi SIRALIDIR — özel olan önce.
 */
export function eventClass(et) {
  if (!et) return 'ev-other'
  const t = String(et).toUpperCase()

  if (t === 'ACCESS_DENIED' || t === 'AUTH_REQUIRED' || t.endsWith('_DENIED')) return 'ev-denied'
  if (t === 'LOGIN') return 'ev-login'
  if (t === 'LOGIN_FAILED' || t === 'ACCOUNT_LOCKED') return 'ev-failed'
  // Kodla giriş hataları (2026-10-02): teslim edilemedi / yanlış kod / süre doldu / kilit — başarısız giriş tonu
  if (t.startsWith('LOGIN_OTP_') && t !== 'LOGIN_OTP_REQUESTED') return 'ev-failed'
  if (t === 'LOGOUT') return 'ev-logout'
  if (t === 'MONITOR_TEST' || t.endsWith('_TEST') || t.endsWith('_TEST_EMAIL')) return 'ev-test'
  if (t.endsWith('_EXPORT')) return 'ev-export'
  if (t.startsWith('SYSTEM_') || t.startsWith('SCHEDULER_') || t.startsWith('RETENTION_')
      || t === 'SCHEMA_PATCH' || t === 'AUDIT_REPLAY') return 'ev-system'
  if (t.endsWith('_DELETE') || t.endsWith('_PURGE')) return 'ev-delete'
  if (t.endsWith('_CREATE') || t.endsWith('_ADD')) return 'ev-create'
  if (t.endsWith('_EDIT') || t.endsWith('_UPDATE') || t.endsWith('_SAVE')) return 'ev-edit'
  return 'ev-other'
}

/**
 * Olay türünün sonundaki EYLEM eki → i18n anahtarı.
 *
 * <p>Sıra önemli: `_TEST_EMAIL` `_EMAIL`'den önce, `_BULK_DELETE` `_DELETE`'ten önce eşleşmeli.
 */
const VERB_SUFFIXES = [
  ['_TEST_EMAIL', 'audit.verbSuffix.testEmail'],
  ['_ACKNOWLEDGE', 'audit.verbSuffix.acknowledge'],
  ['_RENOTIFY', 'audit.verbSuffix.renotify'],
  ['_TRANSFER', 'audit.verbSuffix.transfer'],
  ['_APPROVE', 'audit.verbSuffix.approve'],
  ['_REJECT', 'audit.verbSuffix.reject'],
  ['_SUBMIT', 'audit.verbSuffix.submit'],
  ['_REOPEN', 'audit.verbSuffix.reopen'],
  ['_RESEND', 'audit.verbSuffix.resend'],
  ['_REQUEUE', 'audit.verbSuffix.requeue'],
  ['_RESTORE', 'audit.verbSuffix.restore'],
  ['_RESOLVE', 'audit.verbSuffix.resolve'],
  ['_UNLOCK', 'audit.verbSuffix.unlock'],
  ['_EXPORT', 'audit.verbSuffix.export'],
  ['_TRIGGER', 'audit.verbSuffix.trigger'],
  ['_RELEASE', 'audit.verbSuffix.release'],
  ['_DECRYPT', 'audit.verbSuffix.decrypt'],
  ['_PURGE', 'audit.verbSuffix.purge'],
  ['_DELETE', 'audit.verbSuffix.delete'],
  ['_CREATE', 'audit.verbSuffix.create'],
  ['_UPDATE', 'audit.verbSuffix.update'],
  ['_CHANGE', 'audit.verbSuffix.update'],
  ['_EDIT', 'audit.verbSuffix.update'],
  ['_SAVE', 'audit.verbSuffix.save'],
  ['_RESET', 'audit.verbSuffix.reset'],
  ['_PAUSE', 'audit.verbSuffix.pause'],
  ['_RESUME', 'audit.verbSuffix.resume'],
  ['_REVOKE', 'audit.verbSuffix.revoke'],
  ['_DENIED', 'audit.verbSuffix.denied'],
  ['_REPORT', 'audit.verbSuffix.report'],
  ['_ADD', 'audit.verbSuffix.add'],
  ['_RUN', 'audit.verbSuffix.run'],
  ['_TEST', 'audit.verbSuffix.test'],
  // Fiil taşımayan ama "kaydedildi" anlamına gelen türler: USER_PUSH_SETTINGS, USER_PUSH_SCOPES,
  // CERT_INVENTORY_REPORT_SETTINGS… Bunlar olmadan kural devre dışı kalıp ham türe düşülüyordu.
  ['_SETTINGS', 'audit.verbSuffix.save'],
  ['_SCOPES', 'audit.verbSuffix.save'],
  ['_TOGGLE', 'audit.verbSuffix.toggle'],
  ['_PROMOTE', 'audit.verbSuffix.promote'],
  ['_DEMOTE', 'audit.verbSuffix.demote'],
  ['_DEFAULT', 'audit.verbSuffix.setDefault'],
  ['_REASSIGN', 'audit.verbSuffix.reassign'],
]

/** Olay türünün başındaki NESNE → i18n anahtarı (en uzun önek önce eşleşir). */
const NOUN_PREFIXES = [
  ['CERT_INVENTORY_REPORT', 'audit.noun.certInventoryReport'],
  ['NOTIFICATION_GROUP', 'audit.noun.notificationGroup'],
  ['WEEKLY_AVAILABILITY', 'audit.noun.weeklyAvailability'],
  ['REMEMBER_TOKEN', 'audit.noun.rememberToken'],
  ['LOGIN_ANOMALY', 'audit.noun.loginAnomaly'],
  ['WEEKLY_REPORT', 'audit.noun.weeklyReport'],
  ['SCRIPTED_DRAFT', 'audit.noun.scriptedDraft'],
  ['LOGIN_ISSUE', 'audit.noun.loginIssue'],
  ['MAINTENANCE', 'audit.noun.maintenance'],
  ['DIAGNOSTICS', 'audit.noun.diagnostics'],
  ['PERMISSION', 'audit.noun.permission'],
  ['CLIENT_ERROR', 'audit.noun.clientError'],
  ['ALERT_BULK', 'audit.noun.alertBulk'],
  ['DOMAIN_BULK', 'audit.noun.domainBulk'],
  ['GUIDE_LINK', 'audit.noun.guideLink'],
  ['USER_PUSH', 'audit.noun.userPush'],
  ['THRESHOLD', 'audit.noun.threshold'],
  ['CERT_NOTE', 'audit.noun.certNote'],
  ['CERT_HEALTH', 'audit.noun.certHealth'],
  ['MONITOR_NOTE', 'audit.noun.monitorNote'],
  ['MONITOR_GUIDE', 'audit.noun.monitorGuide'],
  ['MONITOR_GROUP', 'audit.noun.monitorGroup'],
  ['CHANGE_LOG', 'audit.noun.changeLog'],
  ['INCIDENT', 'audit.noun.incident'],
  ['RETENTION', 'audit.noun.retention'],
  ['SCHEDULER', 'audit.noun.scheduler'],
  ['TEMPLATE', 'audit.noun.template'],
  ['BRANDING', 'audit.noun.branding'],
  ['SESSION', 'audit.noun.session'],
  ['CONTACT', 'audit.noun.contact'],
  ['MONITOR', 'audit.noun.monitor'],
  ['GENERAL', 'audit.noun.general'],
  ['DOMAIN', 'audit.noun.domain'],
  ['SYSTEM', 'audit.noun.system'],
  ['SECRET', 'audit.noun.secret'],
  ['STORM', 'audit.noun.storm'],
  ['AUDIT', 'audit.noun.audit'],
  ['ALERT', 'audit.noun.alert'],
  ['ISSUE', 'audit.noun.issue'],
  ['LOGIN', 'audit.noun.login'],
  ['TEAM', 'audit.noun.team'],
  ['USER', 'audit.noun.user'],
  ['LDAP', 'audit.noun.ldap'],
  ['SMTP', 'audit.noun.smtp'],
  ['CERT', 'audit.noun.cert'],
  ['SQL', 'audit.noun.sql'],
  ['CA', 'audit.noun.ca'],
]

/** Kurala uymayan / özel anlamlı türler — doğrudan sözlükten. */
const SPECIAL = {
  LOGIN: 'audit.ev.LOGIN',
  LOGIN_FAILED: 'audit.ev.LOGIN_FAILED',
  LOGOUT: 'audit.ev.LOGOUT',
  ACCOUNT_LOCKED: 'audit.ev.ACCOUNT_LOCKED',
  ACCESS_DENIED: 'audit.ev.ACCESS_DENIED',
  AUTH_REQUIRED: 'audit.ev.AUTH_REQUIRED',
  SELF_PASSWORD_CHANGE: 'audit.ev.SELF_PASSWORD_CHANGE',
  SQL_EXECUTE: 'audit.ev.SQL_EXECUTE',
  SCHEMA_PATCH: 'audit.ev.SCHEMA_PATCH',
  MONITOR_TEST: 'audit.ev.MONITOR_TEST',
  MONITOR_DIAGNOSE: 'audit.ev.MONITOR_DIAGNOSE',
  MAINTENANCE_QUICK: 'audit.ev.MAINTENANCE_QUICK',
  LDAP_QUERY_USER: 'audit.ev.LDAP_QUERY_USER',
  DOMAIN_SOFT_DELETE: 'audit.ev.DOMAIN_SOFT_DELETE',   // eski (çöp kutusu) — 2026-10-07'den beri yazılmaz
  DOMAIN_DELETE_CHECK: 'audit.ev.DOMAIN_DELETE_CHECK',
  SYSTEM_DELETED_RECORDS_PURGE: 'audit.ev.SYSTEM_DELETED_RECORDS_PURGE',   // eski çöp kutusunun tek seferlik kalıcı temizliği
  USER_PASSWORD_AUTO_RESET: 'audit.ev.USER_PASSWORD_AUTO_RESET',
  USER_PUSH_OPT_OUT: 'audit.ev.USER_PUSH_OPT_OUT',
  USER_PUSH_QUIET_HOURS: 'audit.ev.USER_PUSH_QUIET_HOURS',
  // Kişisel push tercihleri / susturma / kendine test; yöneticinin susturmayı kaldırması (2026-10-04)
  PUSH_PREFS_UPDATE: 'audit.ev.PUSH_PREFS_UPDATE',
  PUSH_SNOOZE: 'audit.ev.PUSH_SNOOZE',
  PUSH_SELF_TEST: 'audit.ev.PUSH_SELF_TEST',
  PUSH_SNOOZE_CLEAR: 'audit.ev.PUSH_SNOOZE_CLEAR',
  USER_PREFERENCES_UPDATE: 'audit.ev.USER_PREFERENCES_UPDATE',   // favoriler / açılış sekmesi / kayıtlı görünümler (2026-10-02)
  LOGIN_DISPUTED: 'audit.ev.LOGIN_DISPUTED',
  ISSUE_REPORT: 'audit.ev.ISSUE_REPORT',
  CA_PINNED: 'audit.ev.CA_PINNED',
  CA_ROTATED: 'audit.ev.CA_ROTATED',
  AUDIT_REPLAY: 'audit.ev.AUDIT_REPLAY',
  // Sistem geneli toplu pasife alma + geri alma; pasif hesabın haftalık rapor onay bağlantısı reddi (2026-10-02)
  USER_BULK_DEACTIVATE: 'audit.ev.USER_BULK_DEACTIVATE',
  USER_BULK_DEACTIVATE_UNDO: 'audit.ev.USER_BULK_DEACTIVATE_UNDO',
  WEEKLY_REPORT_LINK_DENIED: 'audit.ev.WEEKLY_REPORT_LINK_DENIED',
  // Pasif hesabın açık oturumunun kesilmesi; HTTP uçtan uca tanılama (2026-10-02) — kural adı/eylem eşlemesine uymaz
  SESSION_ENDED_INACTIVE: 'audit.ev.SESSION_ENDED_INACTIVE',
  HTTP_DIAGNOSTICS_RUN: 'audit.ev.HTTP_DIAGNOSTICS_RUN',
  KEYWORD_DIAGNOSTICS_RUN: 'audit.ev.KEYWORD_DIAGNOSTICS_RUN',   // keyword uçtan uca tanılama (2026-10-04)
  // Ping / Port / DNS uçtan uca tanılama (2026-10-05)
  PING_DIAGNOSTICS_RUN: 'audit.ev.PING_DIAGNOSTICS_RUN',
  PORT_DIAGNOSTICS_RUN: 'audit.ev.PORT_DIAGNOSTICS_RUN',
  DNS_DIAGNOSTICS_RUN: 'audit.ev.DNS_DIAGNOSTICS_RUN',
  // Sayfa Bütünlüğü / Sayfa Hızı uçtan uca tanılama (2026-10-05)
  PAGE_DIAGNOSTICS_RUN: 'audit.ev.PAGE_DIAGNOSTICS_RUN',
  PAGESPEED_DIAGNOSTICS_RUN: 'audit.ev.PAGESPEED_DIAGNOSTICS_RUN',
  // Sistem Bakım Modu (2026-10-02): planla / hemen bakıma al / düzenle / başladı / uzat / hemen bitir / bitti / iptal /
  // duyuru e-postası + bakım başlayınca kesilen oturum — kural adı/eylem eşlemesine uymaz
  SYSTEM_MAINTENANCE_SCHEDULED: 'audit.ev.SYSTEM_MAINTENANCE_SCHEDULED',
  SYSTEM_MAINTENANCE_START_NOW: 'audit.ev.SYSTEM_MAINTENANCE_START_NOW',
  SYSTEM_MAINTENANCE_UPDATED: 'audit.ev.SYSTEM_MAINTENANCE_UPDATED',
  SYSTEM_MAINTENANCE_STARTED: 'audit.ev.SYSTEM_MAINTENANCE_STARTED',
  SYSTEM_MAINTENANCE_EXTENDED: 'audit.ev.SYSTEM_MAINTENANCE_EXTENDED',
  SYSTEM_MAINTENANCE_END_NOW: 'audit.ev.SYSTEM_MAINTENANCE_END_NOW',
  SYSTEM_MAINTENANCE_ENDED: 'audit.ev.SYSTEM_MAINTENANCE_ENDED',
  SYSTEM_MAINTENANCE_CANCELLED: 'audit.ev.SYSTEM_MAINTENANCE_CANCELLED',
  SYSTEM_MAINTENANCE_MAIL: 'audit.ev.SYSTEM_MAINTENANCE_MAIL',
  SESSION_ENDED_MAINTENANCE: 'audit.ev.SESSION_ENDED_MAINTENANCE',
  // Kodla giriş (push / e-posta tek kullanımlık kod) + Giriş Yöntemleri ayarı (2026-10-02) — kural adı/eylem eşlemesine uymaz
  LOGIN_OTP_REQUESTED: 'audit.ev.LOGIN_OTP_REQUESTED',
  LOGIN_OTP_DELIVERY_FAILED: 'audit.ev.LOGIN_OTP_DELIVERY_FAILED',
  LOGIN_OTP_VERIFY_FAILED: 'audit.ev.LOGIN_OTP_VERIFY_FAILED',
  LOGIN_OTP_EXPIRED: 'audit.ev.LOGIN_OTP_EXPIRED',
  LOGIN_OTP_LOCKED: 'audit.ev.LOGIN_OTP_LOCKED',
  LOGIN_METHODS_SETTINGS_SAVE: 'audit.ev.LOGIN_METHODS_SETTINGS_SAVE',
  // Giriş Yöntemleri → push metni "kendime test gönder" (2026-10-03)
  LOGIN_METHODS_PUSH_TEST: 'audit.ev.LOGIN_METHODS_PUSH_TEST',
  // 7/24 izleme ekibi takımları değişti (2026-10-04) — kimin TÜM izlemeleri göreceği; kural adı/eylem eşlemesine uymaz
  NOC_TEAMS_UPDATE: 'audit.ev.NOC_TEAMS_UPDATE',
  // Ayarlar → Görünüm → Temalar: açık temalar + varsayılan (2026-10-05) — "THEME" kural sözlüğünde yok
  THEME_SETTINGS_SAVE: 'audit.ev.THEME_SETTINGS_SAVE',
  // Manuel (dosyadan yüklenen) sertifika: ilk yükleme / yeni sürüm (2026-10-06) — "_UPLOAD"/"_RENEW" eylem sözlüğünde yok
  CERT_MANUAL_UPLOAD: 'audit.ev.CERT_MANUAL_UPLOAD',
  CERT_MANUAL_RENEW: 'audit.ev.CERT_MANUAL_RENEW',
  // Eski sürümün kalıcı silinmesi (2026-10-07) — "_VERSION_DELETE" nesne sözlüğünde yok
  CERT_MANUAL_VERSION_DELETE: 'audit.ev.CERT_MANUAL_VERSION_DELETE',
}

/**
 * Olay türü → okunur etiket.
 *
 * <p><b>Üç katman.</b> (1) özel sözlük, (2) NESNE + EYLEM kuralı, (3) Title-Case yedeği.
 * Yalnız sözlük yeterli olsaydı 162 tür × 2 dil = 324 girdi tutmak ve her yeni türde
 * güncellemeyi hatırlamak gerekirdi; yalnız kural yeterli olsaydı `SQL_EXECUTE`,
 * `MAINTENANCE_QUICK`, `LDAP_QUERY_USER` gibi ~20 türde saçmalardı.
 *
 * <p><b>Ham tür KAYBOLMAZ.</b> Denetçiler ham kodla filtreler ve kopyalar; çağıran onu
 * rozetin `title`'ında ve detay panelinde mono olarak göstermeye devam eder.
 */
export function eventLabel(et, t) {
  if (!et) return '—'
  const type = String(et).toUpperCase()

  const special = SPECIAL[type]
  if (special) {
    const s = t(special)
    if (s !== special) return s
  }

  const suffix = VERB_SUFFIXES.find(([sfx]) => type.endsWith(sfx))
  const noun = NOUN_PREFIXES.find(([pfx]) => type.startsWith(pfx))
  if (suffix && noun) {
    const nounText = t(noun[1])
    const verbText = t(suffix[1])
    // Çeviri eksikse (anahtar geri dönerse) kurala güvenmek yerine ham türe düşülür:
    // yarım çevrilmiş "audit.noun.user silindi" ham koddan daha kötü okunur.
    if (nounText !== noun[1] && verbText !== suffix[1]) return `${nounText} ${verbText}`
  }
  return titleCase(type)
}

/** `USER_ROLE_UNLOCK` → `User Role Unlock` (son çare; ham SNAKE_CASE'den okunur). */
function titleCase(type) {
  return type.toLowerCase().split('_')
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}

/** Sonuç kodu → i18n anahtarı (düz string; `i18n-used-keys` kapısı görebilsin diye çağıran t()'ler). */
export const OUTCOME_KEYS = {
  SUCCESS: 'audit.outcome.success',
  FAILURE: 'audit.outcome.failure',
  BLOCKED: 'audit.outcome.blocked',
}

function tryJson(s) {
  if (!s) return null
  try {
    const v = JSON.parse(s)
    return v && typeof v === 'object' ? v : null
  } catch { return null }
}

/**
 * Bir denetim satırının anlatılabilir parçalarını ayırır.
 *
 * <p><b>`detail` ASLA diff ayrıştırıcısına verilmez.</b> Eski kod `parseDiff(changes || detail)`
 * diyordu: `changes` yoksa `detail` yapısal diff sanılıyor, düz metin bir ayrıntıda `JSON.parse`
 * patlıyor ve `null` dönüyordu — yazılan ayrıntı ekranda HİÇ görünmüyordu.
 */
export function parseDetail(row) {
  const changesObj = tryJson(row?.changes)
  const changes = changesObj ? Object.entries(changesObj) : null
  const detailObj = tryJson(row?.detail)
  const raw = row?.detail
  return {
    changes: changes && changes.length ? changes : null,
    detailObj: detailObj && Object.keys(detailObj).length ? detailObj : null,
    detailText: detailObj ? null : (raw && String(raw).trim() ? String(raw) : null),
  }
}

/** Diff/ayrıntı hücresi: nesne ve diziler `[object Object]` yerine okunur JSON olur. */
export function fmtValue(v) {
  if (v === null || v === undefined || v === '') return '—'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

/** Olay türü → aktör perspektifli FİİL (cümle içinde: "alice · sildi"). */
export function verbFor(et, t) {
  if (!et) return t('audit.verb.did')
  const type = String(et).toUpperCase()
  if (type === 'LOGIN') return t('audit.verb.login')
  if (type === 'LOGIN_FAILED') return t('audit.verb.loginFailed')
  if (type === 'LOGOUT') return t('audit.verb.logout')
  if (type === 'ACCESS_DENIED' || type === 'AUTH_REQUIRED') return t('audit.verb.denied')
  if (type === 'MONITOR_TRIGGER' || type === 'SCHEDULER_RUN') return t('audit.verb.triggered')
  if (type === 'MONITOR_TEST') return t('audit.verb.tested')
  if (type.endsWith('_EXPORT')) return t('audit.verb.exported')
  if (type === 'ACCOUNT_LOCKED') return t('audit.verb.locked')
  if (type === 'SESSION_TERMINATE') return t('audit.verb.terminated')
  if (type.endsWith('_DELETE') || type.endsWith('_PURGE')) return t('audit.verb.deleted')
  if (type.endsWith('_CREATE') || type.endsWith('_ADD')) return t('audit.verb.created')
  if (type.endsWith('_EDIT') || type.endsWith('_UPDATE') || type.endsWith('_SAVE')) return t('audit.verb.updated')
  return t('audit.verb.did')
}

/** Kaynak etiketi: `USER:5`, yalnız tür ya da yalnız kimlik; hiçbiri yoksa boş dize. */
export function resourceLabel(row) {
  if (!row) return ''
  if (row.resource_type) return `${row.resource_type}${row.resource_id ? ':' + row.resource_id : ''}`
  return row.resource_id ? String(row.resource_id) : ''
}

/**
 * Liste satırının KISA özeti (aktörsüz, fiilsiz — o bilgiler satırın kendi sütunlarında):
 * "USER:5 — systemRole: USER → ADMIN (+1)". Öncelik: diff → hata nedeni → JSON ayrıntının ilk
 * iki alanı → düz metin ayrıntı. Hiçbiri yoksa yalnız kaynak (o da yoksa boş dize).
 */
export function summaryLine(row) {
  if (!row) return ''
  const { changes, detailObj, detailText } = parseDetail(row)
  let tail = ''
  if (changes) {
    tail = changes.slice(0, 2).map(([f, c]) => `${f}: ${fmtValue(c?.from)} → ${fmtValue(c?.to)}`).join(', ')
      + (changes.length > 2 ? ` (+${changes.length - 2})` : '')
  } else if (row.failure_reason) {
    tail = row.failure_reason
  } else if (detailObj) {
    tail = Object.entries(detailObj).slice(0, 2).map(([k, v]) => `${k}: ${fmtValue(v)}`).join(', ')
  } else if (detailText) {
    tail = detailText
  }
  return [resourceLabel(row), tail].filter(Boolean).join(' — ')
}

/** Sunucu damgası (UTC, eksiz) → Date; bozuksa null. `utils/localDay.toUtc` ile aynı kabul. */
export function eventDate(iso) {
  if (!iso) return null
  const s = String(iso).trim()
  const d = new Date(/[zZ]$|[+-]\d{2}:?\d{2}$/.test(s) ? s : s + 'Z')
  return Number.isNaN(d.getTime()) ? null : d
}

/**
 * "3 dk önce" — `act.rel.*` anahtarları (Aktivite Logu ve Son Giriş ile aynı dil). Gelecekteki
 * damga (saat kayması) "az önce" okunur; bozuk damga null. Eşik/sözcük çekirdeği ortak: utils/relativeTime.agoText.
 */
export function relativeTime(iso, t, now = Date.now()) {
  const d = eventDate(iso)
  if (!d) return null
  return agoText(d.getTime(), t, now)
}

/** "alice · güncelledi · PORT_MONITOR:7 — warningDays: 30 → 15" tarzı tek satırlık özet. */
export function actionSentence(row, t, diff) {
  const actor = row?.actor || t('audit.systemActor')
  const verb = verbFor(row?.event_type, t)
  const res = row?.resource_type
    ? `${row.resource_type}${row.resource_id ? ':' + row.resource_id : ''}`
    : (row?.resource_id || '')
  let tail = ''
  if (diff && diff.length) {
    tail = ' — ' + diff.slice(0, 2)
      .map(([f, c]) => `${f}: ${fmtValue(c?.from)} → ${fmtValue(c?.to)}`)
      .join(', ') + (diff.length > 2 ? ` (+${diff.length - 2})` : '')
  } else if (row?.failure_reason) {
    tail = ' — ' + row.failure_reason
  }
  return `${actor} · ${verb}${res ? ' · ' + res : ''}${tail}`
}
