/**
 * Gönderim günlüğü tetikleyici etiketleri — SMTP logu ve webhook push logu için TEK kaynak (20.165.1 bugfix, UIA-14 /
 * UIA-C4). Eskiden SMTP logu 9 kodu, push logu `userpush.trigger.*` anahtarlarını tanıyordu; geri kalanı (fırtına,
 * 7/24, bekçi, yönetici özeti, kanal raporu, TLS notu düşüşü …) iki dilde de HAM kod basılıyordu.
 *
 * Kural: önce görünümün kendi sözlüğü (aynı kod iki kanalda farklı anlam taşıyabilir: SMTP `ESCALATION` = eskalasyon
 * e-postası, push `ESCALATION` = seviye yükseldi), sonra ortak sözlük, sonra diğer kanalın sözlüğü, sonra `DEPLOYMENT_*`
 * öneki; bilinmeyen kod ham
 * gösterilir (eski satırlar / sunucunun yeni bir tetikleyicisi kırılmasın). Anahtarlar AÇIKÇA yazılı — kullanılmayan
 * anahtar kapısı dinamik anahtarı göremez. Kapı: `logTriggerLabels.test.js` sunucunun tetikleyici sabitlerini okur.
 */

/** SMTP (notification_logs) tetikleyicileri. */
const SMTP_KEYS = {
  INITIAL: 'health.triggerInitial',
  ESCALATION: 'health.triggerEscalation',
  ESCALATION_STEP: 'health.triggerEscalationStep',
  DAILY_REALERT: 'health.triggerDailyRealert',
  MANUAL: 'health.triggerManual',
  RESOLUTION: 'health.triggerResolution',
  QUIET_HOURS: 'health.triggerQuietHours',
  QUIET_DIGEST: 'health.triggerQuietDigest',
  SYSTEM_MAINTENANCE: 'health.triggerSystemMaintenance',
  TEST: 'logtrig.TEST',
}

/** Push (user_push_deliveries) tetikleyicileri. */
const PUSH_KEYS = {
  OPEN: 'userpush.trigger.OPEN',
  ESCALATION: 'userpush.trigger.ESCALATION',
  RE_ALERT: 'userpush.trigger.RE_ALERT',
  RESOLVE: 'userpush.trigger.RESOLVE',
  RESEND: 'userpush.trigger.RESEND',
  TEST: 'userpush.trigger.TEST',
  WEAK_ALGO: 'userpush.trigger.WEAK_ALGO',
  WEEKLY_REPORT: 'userpush.trigger.WEEKLY_REPORT',
  STORM: 'userpush.trigger.STORM',
  STORM_RESOLVED: 'userpush.trigger.STORM_RESOLVED',
  SCRIPTED_DISABLED: 'userpush.trigger.SCRIPTED_DISABLED',
  OVERFLOW_SUMMARY: 'userpush.trigger.OVERFLOW_SUMMARY',
  ESCALATION_STEP: 'userpush.trigger.ESCALATION_STEP',
}

/** Her iki günlükte de aynı anlamdaki tetikleyiciler. */
const COMMON_KEYS = {
  STORM: 'logtrig.STORM',
  STORM_INITIAL: 'logtrig.STORM_INITIAL',
  STORM_REALERT: 'logtrig.STORM_REALERT',
  STORM_RESOLVE: 'logtrig.STORM_RESOLVE',
  RESOLVE: 'userpush.trigger.RESOLVE',
  NOC_OPEN: 'logtrig.NOC_OPEN',
  NOC_RESOLVE: 'logtrig.NOC_RESOLVE',
  NOC_STORM: 'logtrig.NOC_STORM',
  NOC_STORM_UPDATE: 'logtrig.NOC_STORM_UPDATE',
  NOC_STORM_RESOLVE: 'logtrig.NOC_STORM_RESOLVE',
  NOC_TEST: 'logtrig.NOC_TEST',
  WATCHDOG: 'logtrig.WATCHDOG',
  WATCHDOG_TEST: 'logtrig.WATCHDOG_TEST',
  EXECUTIVE_SUMMARY: 'logtrig.EXECUTIVE_SUMMARY',
  EXECUTIVE_SUMMARY_TEST: 'logtrig.EXECUTIVE_SUMMARY_TEST',
  INCIDENT_ACTION: 'logtrig.INCIDENT_ACTION',
  INCIDENT_NOTIFY: 'logtrig.INCIDENT_NOTIFY',
  NEW_DEVICE: 'logtrig.NEW_DEVICE',
  NETWORK_OUTAGE: 'logtrig.NETWORK_OUTAGE',
  TLS_GRADE_DROP: 'userpush.trigger.TLS_GRADE_DROP',
  DOMAIN_EXPIRY_REMINDER: 'userpush.trigger.DOMAIN_EXPIRY_REMINDER',
  TECH_ERROR_REPORT: 'logtrig.TECH_ERROR_REPORT',
  TECH_ERROR_REPORT_TEST: 'logtrig.TECH_ERROR_REPORT_TEST',
  TECH_ERROR_REMINDER: 'userpush.trigger.TECH_ERROR_REMINDER',
  TECH_ERROR_APPROVAL: 'userpush.trigger.TECH_ERROR_APPROVAL',
  TECH_ERROR_APPROVAL_REMINDER: 'userpush.trigger.TECH_ERROR_APPROVAL_REMINDER',
  TECH_ERROR_DECISION: 'userpush.trigger.TECH_ERROR_DECISION',
  TECH_ERROR_SENT: 'userpush.trigger.TECH_ERROR_SENT',
  TECH_ERROR_SEND_FAILED: 'userpush.trigger.TECH_ERROR_SEND_FAILED',
  CERT_INVENTORY_REPORT: 'logtrig.CERT_INVENTORY_REPORT',
  CERT_INVENTORY_REPORT_TEST: 'logtrig.CERT_INVENTORY_REPORT_TEST',
  LOGIN_ANOMALY_TEST: 'logtrig.LOGIN_ANOMALY_TEST',
  WEBHOOK_TEST: 'logtrig.WEBHOOK_TEST',
  OVERFLOW_SUMMARY: 'userpush.trigger.OVERFLOW_SUMMARY',
  ESCALATION_STEP: 'health.triggerEscalationStep',
  TEST: 'logtrig.TEST',
  DEPLOYMENT_UPGRADE: 'logtrig.DEPLOYMENT_UPGRADE',
  DEPLOYMENT_ROLLBACK: 'logtrig.DEPLOYMENT_ROLLBACK',
}

/** Kapının ve süzgeç listelerinin okuduğu bilinen kodlar (her iki görünüm). */
export const KNOWN_TRIGGER_CODES = Object.freeze([...new Set([
  ...Object.keys(SMTP_KEYS), ...Object.keys(PUSH_KEYS), ...Object.keys(COMMON_KEYS),
])])

/** Kodun i18n anahtarı (kind: 'smtp' | 'push'); bilinmiyorsa null. */
export function triggerKey(code, kind = 'smtp') {
  if (!code) return null
  const own = kind === 'push' ? PUSH_KEYS : SMTP_KEYS
  const other = kind === 'push' ? SMTP_KEYS : PUSH_KEYS
  if (own[code]) return own[code]
  if (COMMON_KEYS[code]) return COMMON_KEYS[code]
  if (other[code]) return other[code]   // diğer kanalın kodu bu günlükte görünürse de ham basılmaz
  if (/^DEPLOYMENT_[A-Z_]+$/.test(code)) return 'logtrig.DEPLOYMENT'
  return null
}

/** Ekrandaki etiket: bilinen kod çevrilir, bilinmeyen ham gösterilir, boş "—". */
export function triggerText(code, t, kind = 'smtp') {
  if (!code) return '—'
  const k = triggerKey(code, kind)
  if (!k) return code
  const v = t(k)
  return v === k ? code : v
}
