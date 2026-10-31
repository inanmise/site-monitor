/**
 * Veritabanı Sorgu İzleme (11. tür, 2026-10-31) — SAYFA modeli (saf; React yok).
 *
 * <p>Sayfa (DbQueryMonitorPage), kart (DbQueryMonitorCard), ayrıntı özeti ve deneme paneli aynı kuralları buradan okur;
 * birim testte doğrudan sınanır. Yapı taşlarının (kural oluşturucu, zamanlama, sorgu düzenleyici …) kendi modelleri
 * `buildingBlocks` dizininde — burası yalnız LİSTE / KART / FORM gövdesi / sunucu hatası eşlemesi.
 *
 * <p>Kesin ürün kuralı: SELECT sonucunun SATIRLARI hiçbir sayfa yüzeyinde (deneme paneli dahil) gösterilmez — bu modelde
 * satır verisi okuyan tek bir yardımcı yoktur. Bilinmeyen / okunamayan durum asla "sağlıklı" (`up`) değildir.
 */
import { dateLocale } from '../../i18n/dateLocale.js'
import { zonedMs } from '../../utils/relativeTime.js'

// ── Kart durumu (api §1 "Kart durumu"; monitorSort.STATUS_TIER bunun aynası) ──────────────────────────────

/**
 * Satır → paylaşılan durum sözlüğü (`up | warn | down | unknown`). FAIL → down; eşik aşımı CRITICAL → down, WARNING →
 * warn; yavaş sorgu → warn; OK → up; hiç çalışmamış ya da tanınmayan durum → unknown (asla `up` değil). Duraklatılmış
 * izleme kartta MonitorCard'ın `inactive` kenarıyla gösterilir; sıralamada sorunlu sayılmaz (monitorSort.tierOf).
 */
export function dbqueryStatusKey(m) {
  if (!m) return 'unknown'
  const s = m.status
  if (s === 'FAIL') return 'down'
  if (s === 'BREACH') return m.breach_level === 'CRITICAL' ? 'down' : 'warn'
  if (s === 'OK') return m.slow ? 'warn' : 'up'
  // UNKNOWN (2.A): en az bir kural değerlendirilemedi, karar ihlal değil — nötr; asla 'up' değil
  return 'unknown'
}

/** Durum rozetinin metin anahtarı — kartla ayrıntı aynı dili konuşur. */
export function dbqueryStatusLabelKey(m) {
  const s = m?.status
  if (s === 'FAIL') return 'dbq.status.fail'
  if (s === 'BREACH') return m.breach_level === 'CRITICAL' ? 'dbq.status.breachCritical' : 'dbq.status.breachWarning'
  if (s === 'OK') return m.slow ? 'dbq.status.slow' : 'dbq.status.ok'
  if (s === 'UNKNOWN') return 'dbq.status.unevaluated'
  return 'dbq.status.unknown'
}

/** Geçmiş satırı (kontrol) için aynı eşleme — satırda `status` + `breach_level` + `slow` var. */
export const checkStatusKey = dbqueryStatusKey
export const checkStatusLabelKey = dbqueryStatusLabelKey

/** Seviye rozeti (eşik aşımında) — WARNING / CRITICAL; diğerleri rozet çizmez. */
export function breachLevelKey(level) {
  if (level === 'CRITICAL') return 'dbq.status.levelCritical'
  if (level === 'WARNING') return 'dbq.status.levelWarning'
  return null
}

// ── Veritabanı türleri ────────────────────────────────────────────────────────────────────────────────────

export const DB_TYPES = Object.freeze(['POSTGRESQL', 'MSSQL', 'ORACLE'])
const DB_TYPE_LABEL = { POSTGRESQL: 'PostgreSQL', MSSQL: 'SQL Server', ORACLE: 'Oracle' }
/** Ürün adları çevrilmez (PostgreSQL / SQL Server / Oracle); bilinmeyen tür ham yazılır, boşsa "—". */
export function dbTypeLabel(type) {
  if (!type) return '—'
  return DB_TYPE_LABEL[type] || String(type)
}

// ── Hata nedeni (DbQueryCheckerService FAIL / SKIPPED kodları) ─────────────────────────────────────────────

export const FAILURE_CODES = Object.freeze([
  'DB_UNREACHABLE', 'DB_DNS', 'DB_TIMEOUT', 'DB_AUTH', 'DB_SQL_ERROR', 'DB_DRIVER', 'DB_BLOCKED',
  'DB_GUARD_REJECTED', 'DB_PROFILE_MISSING', 'DB_PROFILE_OTHER_TEAM', 'DB_SECRET_UNREADABLE', 'DB_BUSY',
  // 2.B: yazma / yönetici yetkili hesap (ayar BLOCK) — kayıt ve çalıştırma reddedilir
  'DB_ACCOUNT_WRITABLE',
])
/**
 * Neden kodu → `dbq.fail.<KOD>` (başlık) ve `dbq.fail.<KOD>.fix` (ne yapılmalı). Bilinmeyen kod `OTHER`'a düşer —
 * ham kod kullanıcıya açıklama olarak gösterilmez (yalnız "Teknik ayrıntı" satırında). Kod yoksa null.
 */
export function failureKey(code) {
  if (!code) return null
  return FAILURE_CODES.includes(code) ? `dbq.fail.${code}` : 'dbq.fail.OTHER'
}

// ── Sayılar ───────────────────────────────────────────────────────────────────────────────────────────────

const isNum = (v) => typeof v === 'number' && Number.isFinite(v)

/**
 * Sayılı metin — tam 1 ise `<anahtar>.one` (EN "1 monitor", "1 day"; DÜŞÜK 17), aksi hâlde `<anahtar>`. `display`
 * gösterilecek biçimlenmiş sayı (verilmezse sayının kendisi). İki dilde de `.one` anahtarı bulunur.
 */
export function countText(t, key, n, display = n) {
  return Number(n) === 1 ? t(`${key}.one`, display) : t(key, display)
}

/** Dönen satır sayısı: sınırlıysa "≥ N" (sunucu `max_rows + 1` ile keser); bilinmiyorsa "—". */
export function rowCountText(count, capped) {
  if (!isNum(count)) return '—'
  const n = count.toLocaleString(dateLocale())
  return capped ? `≥ ${n}` : n
}

/** Ölçülen değer (ilk kuralın sayısı): tam sayıysa gruplanmış, ondalıklıysa en çok 4 hane; yoksa "—". */
export function metricValueText(v) {
  if (!isNum(v)) return '—'
  return v.toLocaleString(dateLocale(), { maximumFractionDigits: 4 })
}

/** Süre (ms) → "850 ms" / "12,4 sn"; yoksa "—". */
export function durationText(ms, t) {
  if (!isNum(ms)) return '—'
  if (ms < 1000) return t('dbq.card.ms', Math.round(ms))
  return t('dbq.card.sec', (ms / 1000).toLocaleString(dateLocale(), { maximumFractionDigits: 1 }))
}

// ── Zamanlama özeti (insan dili) ──────────────────────────────────────────────────────────────────────────

/** Saniye → "5 dk" / "2 sa" / "1 gün" (tam bölünmeyen değerde en küçük kesin birim). */
export function intervalText(sec, t) {
  if (!isNum(sec) || sec <= 0) return null
  // Tek birim çoğul sayıyla yazılmaz: "Her 1 gün" değil "Her gün" (DÜŞÜK 17)
  if (sec === 86400) return t('dbq.card.everyDayOne')
  if (sec === 3600) return t('dbq.card.everyHourOne')
  if (sec === 60) return t('dbq.card.everyMinOne')
  if (sec % 86400 === 0) return t('dbq.card.everyDay', sec / 86400)
  if (sec % 3600 === 0) return t('dbq.card.everyHour', sec / 3600)
  if (sec % 60 === 0) return t('dbq.card.everyMin', sec / 60)
  return t('dbq.card.everySec', sec)
}

const DOW = { 0: 7, 7: 7, 1: 1, 2: 2, 3: 3, 4: 4, 5: 5, 6: 6, SUN: 7, MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6 }
const MACROS = { '@hourly': '0 * * * *', '@daily': '0 0 * * *', '@midnight': '0 0 * * *', '@weekly': '0 0 * * 0', '@monthly': '0 0 1 * *' }
const pad2 = (n) => String(n).padStart(2, '0')
const isInt = (s, lo, hi) => /^\d{1,2}$/.test(s) && Number(s) >= lo && Number(s) <= hi

/**
 * Sık cron kalıplarını insan diline çevirir; tanımadığı ifade için null döner (çağıran ifadeyi olduğu gibi gösterir —
 * yanlış bir açıklama uydurmaktan iyidir). Desteklenen: makrolar, `*\/N * * * *`, `M *\/N * * *`, `M H * * *`,
 * `M H * * 1-5`, `M H * * D`, `M H G * *` (5 alan; 6 alanda saniye `0` ise düşürülür). Kısa belirteçler üzerinde düz
 * karşılaştırma — sınırsız düzenli ifade yok.
 */
export function cronText(expr, t) {
  if (typeof expr !== 'string') return null
  const raw = expr.trim()
  if (!raw || raw.length > 120) return null
  const macro = MACROS[raw.toLowerCase()]
  let parts = (macro || raw).split(/\s+/)
  if (parts.length === 6) {
    if (parts[0] !== '0') return null
    parts = parts.slice(1)
  }
  if (parts.length !== 5) return null
  const [mi, h, dom, mon, dow] = parts
  if (mon !== '*') return null
  // */N * * * *
  if (mi.startsWith('*/') && h === '*' && dom === '*' && dow === '*') {
    const n = mi.slice(2)
    return isInt(n, 1, 59) ? t('dbq.card.everyMin', Number(n)) : null
  }
  if (!isInt(mi, 0, 59)) return null
  // M */N * * *  |  M * * * *
  if (dom === '*' && dow === '*' && (h === '*' || h.startsWith('*/'))) {
    const n = h === '*' ? '1' : h.slice(2)
    if (!isInt(n, 1, 23)) return null
    // Saatte bir: "1 saatte bir, :00 dakikasında" değil "Her saat başı" / "Her saat, :15 dakikasında" (DÜŞÜK 17)
    if (Number(n) === 1) return Number(mi) === 0 ? t('dbq.card.cronHourly') : t('dbq.card.cronHourlyAt', pad2(mi))
    return t('dbq.card.cronEveryHourAt', Number(n), pad2(mi))
  }
  if (!isInt(h, 0, 23)) return null
  const at = `${pad2(h)}:${pad2(mi)}`
  if (dom === '*' && dow === '*') return t('dbq.card.cronDaily', at)
  if (dom === '*' && (dow === '1-5' || dow.toUpperCase() === 'MON-FRI')) return t('dbq.card.cronWeekdays', at)
  if (dom === '*' && DOW[dow.toUpperCase()] != null) return t('dbq.card.cronWeekly', t(`dbq.card.dow${DOW[dow.toUpperCase()]}`), at)
  if (dow === '*' && isInt(dom, 1, 31)) return t('dbq.card.cronMonthly', Number(dom), at)
  return null
}

/**
 * Satırın zamanlama özeti: aralık kipinde "Her 5 dk", cron kipinde insan dili ya da ifadenin kendisi ("cron: …").
 * Pencere / yasak / tatil süzgeci varsa kısa ek ("· izinli pencerelerde", "· tatilde atlanır").
 */
export function scheduleSummary(m, t) {
  if (!m) return '—'
  let base
  if (m.schedule_mode === 'CRON') {
    base = cronText(m.cron_expression, t) || (m.cron_expression ? t('dbq.card.cronRaw', m.cron_expression) : '—')
  } else {
    base = intervalText(m.interval_seconds, t) || '—'
  }
  const extras = []
  if (Array.isArray(m.windows) && m.windows.length) extras.push(t('dbq.card.schedWindows'))
  if (Array.isArray(m.blackouts) && m.blackouts.length) extras.push(t('dbq.card.schedBlackouts'))
  if (m.skip_holidays) extras.push(t('dbq.card.schedHolidays'))
  return extras.length ? `${base} · ${extras.join(' · ')}` : base
}

// ── Sonraki çalışma ───────────────────────────────────────────────────────────────────────────────────────

/** Gelecekteki an → "5 dk sonra" / "2 sa sonra" / "3 gün sonra"; geçmiş → "gecikti"; bozuk → null. */
export function untilText(iso, t, now = Date.now()) {
  const at = zonedMs(iso)
  if (!Number.isFinite(at)) return null
  const sec = Math.round((at - now) / 1000)
  if (sec < -60) return t('dbq.card.overdue')
  if (sec < 60) return t('dbq.card.inNow')
  const min = Math.floor(sec / 60); if (min < 60) return t('dbq.card.inMin', min)
  const hr = Math.floor(min / 60); if (hr < 24) return t('dbq.card.inHour', hr)
  return t('dbq.card.inDay', Math.floor(hr / 24))
}

/**
 * UTC ISO → İstanbul saatiyle "31.10.2026 14:05" (ürünün saat dilimi; tarayıcının değil). Bozuk → null. `seconds`:
 * saniye de yazılır (ayrıntıdaki "Son çalışma" — aynı listedeki "Sonraki çalışma" ile aynı saat diliminde, DÜŞÜK 16).
 */
export function istanbulTime(iso, { seconds = false } = {}) {
  const at = zonedMs(iso)
  if (!Number.isFinite(at)) return null
  try {
    return new Date(at).toLocaleString(dateLocale(), {
      timeZone: 'Europe/Istanbul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
      ...(seconds ? { second: '2-digit' } : {}),
    })
  } catch {
    return null
  }
}

// ── Form ↔ satır ──────────────────────────────────────────────────────────────────────────────────────────

/** Zamanlama alanları (ScheduleEditor `value` biçimi — sunucu adlarıyla). */
export const SCHEDULE_KEYS = Object.freeze(['schedule_mode', 'interval_seconds', 'cron_expression', 'windows', 'blackouts',
  'skip_holidays', 'skip_dates'])

export const DEFAULT_SCHEDULE = Object.freeze({
  schedule_mode: 'INTERVAL', interval_seconds: 300, cron_expression: '', windows: [], blackouts: [], skip_holidays: false, skip_dates: [],
})

/** Satır → ScheduleEditor değeri (eksik alanlar varsayılanla). */
export function scheduleFromRow(m) {
  const arr = (v) => (Array.isArray(v) ? v : [])
  return {
    schedule_mode: m?.schedule_mode === 'CRON' ? 'CRON' : 'INTERVAL',
    interval_seconds: isNum(m?.interval_seconds) ? m.interval_seconds : DEFAULT_SCHEDULE.interval_seconds,
    cron_expression: m?.cron_expression || '',
    windows: arr(m?.windows), blackouts: arr(m?.blackouts),
    skip_holidays: !!m?.skip_holidays,
    skip_dates: arr(m?.skip_dates),
  }
}

/**
 * Birlikte anlam taşıyan alan kümeleri — biri değişince HEPSİ gönderilir (düzenlemede yalnız değişen alanlar kuralının
 * istisnası): sunucu bir kümeyi bütün olarak doğrular (zamanlama önizlemesi, sorgunun bağlantının veritabanı türüne göre
 * denetimi, 7/24 anahtarı + grupları — nocMonitorForms kapısı ikisini birlikte bekler).
 */
export const FIELD_GROUPS = Object.freeze([
  // Takım değişince grup adı da gider: sunucu grubu yeni takımda yalnız gövdede `groupName` varsa kaydeder (DÜŞÜK 4)
  ['teamId', 'groupName'],
  ['nocNotify', 'nocGroupIds'],
  ['notifyEmail', 'notifyWebhook', 'alertLevel', 'notificationGroupId'],
  ['profile_id', 'sql_text'],
  ['rules', 'rule_logic'],
  [...SCHEDULE_KEYS],
  ['mail_include_rows', 'mail_columns_mode', 'mail_columns', 'masked_columns'],
  ['confirmAttempts', 'confirmIntervalSeconds', 'recoveryChecks', 'recoveryIntervalSeconds'],
])

/**
 * Düzenleme gövdesi: yüklenen hâlden DEĞİŞEN alanlar + değişen alanın kümesindeki diğerleri (FIELD_GROUPS). Aynı izlemeyi
 * açık tutan başka bir yöneticinin o arada kaydettiği alanlar sessizce eski değere dönmez. Hiçbir şey değişmediyse `{}`.
 */
export function changedFieldGroups(next, base, groups = FIELD_GROUPS) {
  const same = (k) => JSON.stringify(next?.[k]) === JSON.stringify(base?.[k])
  const out = {}
  for (const k of Object.keys(next || {})) if (base == null || !same(k)) out[k] = next[k]
  for (const g of groups) {
    if (g.some((k) => k in out)) for (const k of g) if (k in (next || {})) out[k] = next[k]
  }
  return out
}

/** Liste (dizi) ↔ TagInput CSV metni. Boş öğeler atılır, sıra korunur, tekrar edenler bir kez. */
export function listToCsv(list) {
  return Array.isArray(list) ? [...new Set(list.map((s) => String(s).trim()).filter(Boolean))].join(', ') : ''
}
export function csvToList(csv) {
  return [...new Set(String(csv || '').split(',').map((s) => s.trim()).filter(Boolean))]
}

/** Kural kimliği (api §4: `r-<zaman><rastgele>`) — istemcide üretilir, sunucu boş kimliğe kendisi verir. */
export function newRuleId(now = Date.now(), rnd = Math.random) {
  return `r-${now.toString(36)}${Math.floor(rnd() * 1e6).toString(36)}`
}

/** Son denemenin kolonları (`[{name,type}]`) — kural oluşturucu ve mail kolonu önerileri için. Satır verisi değildir. */
export function columnsFrom(result) {
  const cols = Array.isArray(result?.columns) ? result.columns : []
  return cols.filter((c) => c && typeof c.name === 'string' && c.name).map((c) => ({ name: c.name, type: c.type || '' }))
}

// ── Sunucu doğrulama hataları → alan ──────────────────────────────────────────────────────────────────────

/** Formun yukarıdan aşağı alan sırası — ilk hatalı alana kaydırma bu sırayla (sunucunun `fields` sırası keyfi). */
export const FIELD_ORDER = Object.freeze([
  'name', 'team_id', 'group_name', 'tags', 'purpose', 'profile_id', 'sql_text',
  'rules', 'slow_query_ms',
  'schedule', 'schedule_mode', 'interval_seconds', 'cron_expression', 'windows', 'blackouts', 'skip_dates',
  'query_timeout_seconds', 'max_rows',
  'mail_subject_template', 'mail_columns_mode', 'mail_columns', 'masked_columns',
])

const rank = (k) => {
  const base = k.startsWith('rules[') ? 'rules' : k
  const i = FIELD_ORDER.indexOf(base)
  return i < 0 ? FIELD_ORDER.length : i
}

/** Alan haritasını form sırasına dizer (kural alt alanları `rules`'un yerinde, kendi sıralarıyla). */
export function orderFieldErrors(map) {
  return Object.fromEntries(Object.entries(map || {}).filter(([, v]) => !!v)
    .sort(([a], [b]) => rank(a) - rank(b) || (a < b ? -1 : a > b ? 1 : 0)))
}

/**
 * Alan anahtarı → form bölümü (`FORM_SECTIONS` kimlikleri; bölüm atlama çubuğunun hata sayısı). Tanınmayan anahtar
 * `null` (hiçbir bölüme sayılmaz — ileti yine alanında / bantta).
 */
const FIELD_SECTION = {
  name: 'basic', team_id: 'basic', group_name: 'basic', tags: 'basic', purpose: 'basic',
  profile_id: 'connection',
  sql_text: 'query',
  rules: 'evaluation', slow_query_ms: 'evaluation',
  schedule: 'schedule', schedule_mode: 'schedule', interval_seconds: 'schedule', cron_expression: 'schedule', windows: 'schedule',
  blackouts: 'schedule', skip_dates: 'schedule',
  query_timeout_seconds: 'limits', max_rows: 'limits',
  mail_subject_template: 'alerting', mail_columns_mode: 'alerting', mail_columns: 'alerting', masked_columns: 'alerting',
  confirm_attempts: 'alerting', confirm_interval_seconds: 'alerting', recovery_checks: 'alerting', recovery_interval_seconds: 'alerting',
}
export function sectionOfField(key) {
  if (typeof key !== 'string') return null
  if (key === 'rules' || key.startsWith('rules[')) return 'evaluation'
  return FIELD_SECTION[key] || null
}

/** Hata haritası → `{ bölüm: sayı }` (yalnız ileti taşıyan alanlar). */
export function sectionErrorCounts(errors) {
  const out = {}
  for (const [k, v] of Object.entries(errors || {})) {
    if (!v) continue
    const sec = sectionOfField(k)
    if (sec) out[sec] = (out[sec] || 0) + 1
  }
  return out
}

/** Sunucu yanıtının uyarı listesi (2.B / 2.C — `warnings` zarfta ya da `data` içinde; engellemez). */
export function warningsOf(res) {
  const list = Array.isArray(res?.warnings) ? res.warnings : (Array.isArray(res?.data?.warnings) ? res.data.warnings : [])
  return list.filter((w) => w && typeof w === 'object' && typeof w.code === 'string' && w.code)
}

/**
 * Değerlendirilemeyen kuralların notları (UNKNOWN durumu, 2.A) — `[{ label, note }]`, en çok 3; satır verisi değil.
 */
export function unevaluatedRules(m) {
  const list = Array.isArray(m?.rule_results) ? m.rule_results : []
  return list.filter((r) => r && r.evaluated === false).slice(0, 3).map((r) => ({ id: r.id, label: r.label || null, note: r.note || null, kind: r.kind || null }))
}

/** Zamanlama düzenleyicisine giden alanlar. */
export const SCHEDULE_FIELD_KEYS = Object.freeze(['schedule', 'schedule_mode', 'interval_seconds', 'cron_expression', 'windows', 'blackouts', 'skip_dates'])

/** Hata haritasını yapı taşlarına böler: kural oluşturucu (`rules`, `rules[N].x`), zamanlama, kalanı (form alanları). */
export function splitFieldErrors(errors) {
  const rules = {}, schedule = {}, other = {}
  for (const [k, v] of Object.entries(errors || {})) {
    if (!v) continue
    if (k === 'rules' || k.startsWith('rules[')) rules[k] = v
    else if (SCHEDULE_FIELD_KEYS.includes(k)) schedule[k] = v
    else other[k] = v
  }
  return { rules, schedule, other }
}

/**
 * Kaydetme / deneme yanıtı → `{ fields, violations, plan }`. Sunucu sözleşmesi (api §1):
 *  - 400 VALIDATION_FAILED: `fields` (+ `field` ilk alan) — olduğu gibi alanlara.
 *  - 400 DBQUERY_SQL_REJECTED: `sql_text` + `violations` (sorgu düzenleyicisinde satır:sütunla listelenir).
 *  - 400 DBQUERY_PLAN_TOO_COSTLY: `sql_text` + `plan`.
 *  - 400 DBQUERY_CRON_TOO_FREQUENT / DBQUERY_SCHEDULE_NEVER_RUNS / DBQUERY_CONNECTION_OTHER_TEAM, 409 DUPLICATE_RECORD:
 *    sunucunun `field` alanına (yoksa türün doğal alanına).
 * Alan adı tanınmasa bile ileti kaybolmaz: hiçbir alana düşmeyen hata `null` alanlarla döner ve çağıran bandı gösterir.
 */
const CODE_FIELD = {
  DBQUERY_SQL_REJECTED: 'sql_text', DBQUERY_PLAN_TOO_COSTLY: 'sql_text', DBQUERY_CRON_TOO_FREQUENT: 'cron_expression',
  DBQUERY_SCHEDULE_NEVER_RUNS: 'schedule', DBQUERY_CONNECTION_OTHER_TEAM: 'profile_id', DUPLICATE_RECORD: 'name',
  // Paylaşımla erişilen takımda oluşturma / kopyalama reddi (K2) — takım alanının altında
  DBQUERY_GRANT_TEAM: 'team_id',
  // Yazabilen hesap (ayar BLOCK, 2.B) — bağlantı seçiminin altında
  DB_ACCOUNT_WRITABLE: 'profile_id',
}
export function serverFieldErrors(res) {
  if (!res || res.success !== false) return { fields: null, violations: null, plan: null }
  const fields = {}
  if (res.fields && typeof res.fields === 'object' && !Array.isArray(res.fields)) {
    for (const [k, v] of Object.entries(res.fields)) if (k) fields[k] = typeof v === 'string' && v ? v : (res.error || '')
  }
  const field = typeof res.field === 'string' && res.field ? res.field : CODE_FIELD[res.code]
  if (field && !fields[field]) fields[field] = res.error || ''
  const violations = Array.isArray(res.violations) ? res.violations : null
  const plan = res.plan && typeof res.plan === 'object' ? res.plan : null
  const cleaned = Object.fromEntries(Object.entries(fields).filter(([, v]) => !!v))
  return { fields: Object.keys(cleaned).length ? orderFieldErrors(cleaned) : null, violations, plan }
}

/**
 * Bekleme yanıtının (429) gösterilecek metni (K3, DÜŞÜK 5): sunucu iletisi varsa YALNIZ o — sunucu zaten "N sn / M dk
 * sonra yeniden deneyin" der, arkasına ikinci bir "… sonra yeniden deneyebilirsiniz" eklenmez. İleti yoksa (eski sunucu)
 * bekleme süresinden kurulur; süre de yoksa genel metin.
 */
export function rateLimitText(res, t, fallbackKey) {
  const msg = typeof res?.error === 'string' && res.error.trim() ? res.error.trim()
    : (typeof res?.message === 'string' && res.message.trim() ? res.message.trim() : null)
  if (msg) return msg
  const wait = retryAfterOf(res)
  return wait ? t('dbq.test.retryIn', wait) : t(fallbackKey)
}

/** Bekleme yanıtı (429) → saniye; yoksa null. */
export function retryAfterOf(res) {
  const v = res?.retry_after_seconds ?? res?.retryAfter
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? Math.ceil(n) : null
}

// ── Mail konusu şablonu yer tutucuları (EmailTemplateBuilder / DbQueryMailSubject ile aynı adlar) ───────────

export const SUBJECT_PLACEHOLDERS = Object.freeze(['{ad}', '{amac}', '{satir}', '{deger}', '{seviye}', '{baglanti}', '{zaman}'])

// ── Plan (maliyet) kararı ─────────────────────────────────────────────────────────────────────────────────

/**
 * PlanVerdict → ton: OK success · WARN warning · BLOCK danger · UNKNOWN / OFF (denetim kapalı, 2.A) / desteklenmiyor
 * muted (asla yeşil değil).
 */
export function planTone(plan) {
  if (!plan || plan.supported === false || plan.verdict === 'OFF') return 'neutral'
  if (plan.verdict === 'OK') return 'success'
  if (plan.verdict === 'WARN') return 'warning'
  if (plan.verdict === 'BLOCK') return 'danger'
  return 'neutral'
}

/**
 * "Maliyeti denetle" düğmesi çizilsin mi: denetim kapalı (`plan_check_mode` OFF) ya da Oracle (desteklenmiyor) değilse.
 * Ayar bilinmiyorsa (eski sunucu, varsayılanlar okunamadı) düğme kalır — sunucu kararı söyler.
 */
export function planCheckAvailable(limits, dbType) {
  if (dbType === 'ORACLE') return false
  return String(limits?.plan_check_mode || '').toUpperCase() !== 'OFF'
}
