/**
 * Aktivite TAM dışa aktarımı (2026-11-05, E) — saf model. CSV sunucuda üretilir (`GET /api/activity/export?format=csv`,
 * be2 `ActivityExportService`: BOM, formül koruması, en çok 50 000 satır); Excel tarayıcıda, aynı uçtan JSON
 * (`format=json`, en çok 20 000 satır) okunup `utils/xlsxWriter.buildXlsx` ile kurulur. Eskiden CSV yalnız açık sayfayı
 * (50 satır) veriyordu (G).
 *
 * Sütunlar sunucu CSV'siyle aynı sırada: zaman (İstanbul), tür, izleme, hedef, takım, eylem, durum, özet, hata, hata
 * nedeni, HTTP kodu, yanıt ms, kişi, istek kimliği, alarm no.
 */
import { reasonLabel, reasonParams } from './activityModel.js'

export const EXPORT_MAX = Object.freeze({ csv: 50000, json: 20000 })

/** Excel sütun başlığı anahtarları (`act.ex.col.<ad>`) + yaklaşık genişlik (karakter). */
export const EXCEL_COLUMNS = [
  ['time', 20], ['type', 11], ['monitor', 34], ['target', 40], ['team', 26], ['action', 26], ['status', 13],
  ['summary', 30], ['message', 40], ['reason', 28], ['http', 9], ['response', 11], ['actor', 18], ['request', 22], ['alert', 10],
]

/**
 * JSON yanıtından satırlar + tavan bilgisi. Biçimler: `{ data: [..] }`, `{ rows: [..] }`, `{ data: { rows: [..] } }`;
 * `truncated` kökte ya da `data` altında. Tanınmayan gövde → null (okunamadı).
 */
export function unwrapExportJson(res) {
  if (!res || typeof res !== 'object') return null
  let rows = null
  if (Array.isArray(res.data)) rows = res.data
  else if (Array.isArray(res.rows)) rows = res.rows
  else if (res.data && typeof res.data === 'object' && Array.isArray(res.data.rows)) rows = res.data.rows
  if (!rows) return null
  const truncated = !!(res.truncated ?? res.data?.truncated)
  return { rows, truncated }
}

/** UTC ISO (Z'siz) → İstanbul "dd.MM.yyyy HH:mm:ss"; okunamazsa olduğu gibi. */
export function istanbulTime(iso, lang = 'tr') {
  if (!iso) return ''
  const s = String(iso)
  const ms = Date.parse(s.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(s) ? s : s + 'Z')
  if (!Number.isFinite(ms)) return s
  const parts = new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', {
    timeZone: 'Europe/Istanbul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(ms))
  const g = (k) => parts.find((p) => p.type === k)?.value || ''
  return `${g('day')}.${g('month')}.${g('year')} ${g('hour')}:${g('minute')}:${g('second')}`
}

const numOrBlank = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
const label = (t, key, fallback) => {
  const v = t(key)
  return v && v !== key ? v : (fallback ?? '')
}

/** Satır → Excel hücreleri (metinler kullanıcının dilinde; sayılar sayı hücresi). */
export function excelRow(r, t, lang = 'tr') {
  return [
    istanbulTime(r.activity_time, lang),
    r.monitor_type || '',
    r.monitor_name || '',
    r.target || '',
    r.team_name || '',
    r.action ? label(t, 'act.ac.' + r.action, r.action) : '',
    r.result_status ? label(t, 'act.st.' + r.result_status, r.result_status) : '',
    r.result_summary || '',
    r.error_message || '',
    r.failure_reason ? reasonLabel(r.failure_reason, t, reasonParams(r)) : '',
    numOrBlank(r.http_status),
    numOrBlank(r.response_ms),
    r.actor || '',
    r.request_id || '',
    numOrBlank(r.alert_event_id),
  ]
}

/** Çalışma kitabının tek sayfası (dondurulmuş başlık + otomatik süzgeç). */
export function excelSheet(rows, t, lang = 'tr') {
  return {
    name: label(t, 'act.ex.sheet', 'Activity'),
    rows: [EXCEL_COLUMNS.map(([k]) => label(t, 'act.ex.col.' + k, k)), ...(rows || []).map((r) => excelRow(r, t, lang))],
    widths: EXCEL_COLUMNS.map(([, w]) => w),
    freezeHeader: true,
    autoFilter: true,
  }
}

/** "aktivite-20261105-1430.xlsx" (yerel saat; sunucu CSV'si kendi adını verir). */
export function exportFileName(ext, now = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return `aktivite-${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}.${ext}`
}
