/**
 * Aktivite TAM dışa aktarımı (2026-11-05, E) — saf model. CSV sunucuda üretilir (`GET /api/activity/export?format=csv`,
 * be2 `ActivityExportService`: BOM, formül koruması, en çok 50 000 satır); Excel tarayıcıda, aynı uçtan JSON
 * (`format=json`, en çok 20 000 satır) okunup `utils/xlsxWriter.buildXlsx` ile kurulur. Eskiden CSV yalnız açık sayfayı
 * (50 satır) veriyordu (G).
 *
 * Sütunlar sunucu CSV'siyle aynı sırada: zaman (İstanbul), tür, izleme, hedef, takım, eylem, durum, özet, hata, hata
 * nedeni, HTTP kodu, yanıt ms, kişi, istek kimliği, alarm no.
 */
import { actorOf, localizeSummary, reasonLabel, reasonParams } from './activityModel.js'

export const EXPORT_MAX = Object.freeze({ csv: 50000, json: 20000 })

/**
 * Dışa aktarım aralığı (be2 d1097f7a `ActivityExportService.DEFAULT_DAYS` / `MAX_DAYS` — kapı activityRelatedCodes.test):
 * `from` verilmezse sunucu son 30 günü aktarır (`range_defaulted`); 90 günü aşan aralık 400 (alan `from`).
 */
export const EXPORT_RANGE = Object.freeze({ defaultDays: 30, maxDays: 90 })

const DAY_MS = 86_400_000
const ms = (iso) => {
  if (!iso) return NaN
  const v = String(iso)
  return Date.parse(v.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(v) ? v : v + 'Z')
}

/**
 * İstek parametrelerinin aralığı sunucunun sınırını aşıyor mu: `from` var ve (`to` ya da şimdi) − `from` > 90 gün →
 * `'tooLong'`, aksi null. `from` yoksa sunucu kendisi 30 güne sınırlar (sorun değil).
 */
export function exportRangeProblem(params, now = Date.now()) {
  const from = ms(params?.from)
  if (!Number.isFinite(from)) return null
  const toRaw = ms(params?.to)
  const to = Number.isFinite(toRaw) ? toRaw : now
  return to - from > EXPORT_RANGE.maxDays * DAY_MS ? 'tooLong' : null
}

/** Excel sütun başlığı anahtarları (`act.ex.col.<ad>`) + yaklaşık genişlik (karakter). */
export const EXCEL_COLUMNS = [
  ['time', 20], ['type', 11], ['monitor', 34], ['target', 40], ['team', 26], ['action', 26], ['status', 13],
  ['summary', 30], ['message', 40], ['reason', 28], ['http', 9], ['response', 11], ['actor', 18], ['request', 22], ['alert', 10],
]

/**
 * JSON yanıtından satırlar + tavan bilgisi. be2 biçimi `{ data: { columns, time_zone, rows: [[…]], row_count, max_rows,
 * truncated } }` — satır dizileri `columns` adlarıyla nesneye çevrilir (`time` sütunu İstanbul yerel "yyyy-MM-dd HH:mm:ss",
 * `time_local` olarak taşınır). Nesne satırları (`{ data: [..] }`, `{ rows: [..] }`) da okunur. Tanınmayan → null.
 */
export function unwrapExportJson(res) {
  if (!res || typeof res !== 'object') return null
  const body = res.data && typeof res.data === 'object' && !Array.isArray(res.data) ? res.data : res
  let rows = null
  if (Array.isArray(res.data)) rows = res.data
  else if (Array.isArray(body.rows)) rows = body.rows
  if (!rows) return null
  const cols = Array.isArray(body.columns) ? body.columns.map(String) : null
  const objects = rows.map((r) => {
    if (!Array.isArray(r)) return r && typeof r === 'object' ? r : {}
    if (!cols) return {}
    const o = {}
    cols.forEach((c, i) => { o[c === 'time' ? 'time_local' : c] = r[i] })
    return o
  })
  const truncated = !!(body.truncated ?? res.truncated)
  const maxRows = Number(body.max_rows ?? res.max_rows)
  const defaultDays = Number(body.range_default_days)
  return {
    rows: objects, truncated, maxRows: Number.isFinite(maxRows) && maxRows > 0 ? maxRows : null,
    // be2: aralık seçilmediyse sunucu son N günü verdi (eski sunucu alanı yollamaz → false)
    rangeDefaulted: body.range_defaulted === true,
    rangeDefaultDays: Number.isFinite(defaultDays) && defaultDays > 0 ? defaultDays : null,
  }
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

/**
 * Satır → Excel hücreleri (metinler kullanıcının dilinde; sayılar sayı hücresi). Özet ekrandaki jeton çevirisiyle
 * (`localizeSummary`), kişi `actorOf` kuralıyla ("scheduler" / "system" çevrilir — inceleme CT D2 / CT D3).
 */
export function excelRow(r, t, lang = 'tr') {
  return [
    r.time_local != null ? String(r.time_local) : istanbulTime(r.activity_time, lang),
    r.monitor_type || '',
    r.monitor_name || '',
    r.target || '',
    r.team_name || '',
    r.action ? label(t, 'act.ac.' + r.action, r.action) : '',
    r.result_status ? label(t, 'act.st.' + r.result_status, r.result_status) : '',
    r.result_summary ? localizeSummary(r.result_summary, t) : '',
    r.error_message || '',
    r.failure_reason ? reasonLabel(r.failure_reason, t, reasonParams(r)) : '',
    numOrBlank(r.http_status),
    numOrBlank(r.response_ms),
    actorOf(r.actor, r.action, t).text,
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
