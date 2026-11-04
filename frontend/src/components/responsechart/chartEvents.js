/**
 * Yanıt süresi grafiği — OLAY KATMANI modeli (saf; 2026-11-05, dbq2 C2).
 *
 * <p>Veritabanı sorgu izlemesinin seri yanıtı iki EK alan taşır (başka türler göndermez → grafik bugünkü gibi):
 *  - `alarms: [{ from, to | null, level, type }]` — aralıktaki bu izlemenin DBQUERY_* alarmları (`to` null = sürüyor);
 *  - `changes: [{ at, event, fields: [alan adı …] }]` — izlemenin değişiklik geçmişi (`event` CREATE | UPDATE | RESTORE …);
 *    YALNIZ alan ANAHTARLARI (camelCase, `history/changeFields.fieldLabel` ile etiketlenir), değer yok.
 * Alan `null` = sunucu o bilgiyi OKUYAMADI (asla "olay yok" sayılmaz — `alarmsUnreadable` / `changesUnreadable`); boş dizi =
 * gerçekten yok.
 * Damgalar sunucunun biçimi (UTC, `Z`siz — `toUtc` ile). Görünen pencereyle (`domain`) kesişmeyen olay çizilmez; pencereden
 * taşan alarm kenarda kırpılır (`clippedStart` / `clippedEnd` — açıklamada "pencere başından önce başladı" denir). Tavan
 * aşılırsa en yeniler kalır (`more` = düşen sayı) — sınırsız çizim yok.
 */
import { toUtc } from '../../utils/localDay.js'

export const MAX_CHART_ALARMS = 100
export const MAX_CHART_CHANGES = 100
/** Bir değişiklik işaretinin taşıdığı en çok alan adı (fazlası "+N"). */
export const MAX_CHANGE_FIELDS = 12

const parse = (s) => (typeof s === 'string' && s ? Date.parse(toUtc(s)) : NaN)

/** Alarm seviyesi → iki görsel ton: kritik (CRITICAL / HIGH) ya da uyarı (diğerleri). */
export function alarmTone(level) {
  const l = String(level || '').toUpperCase()
  return l === 'CRITICAL' || l === 'HIGH' ? 'critical' : 'warning'
}

/**
 * Seri yanıtı + görünen pencere → `{ alarms, changes, moreAlarms, moreChanges }`. Alarmlar başlangıca, değişiklikler zamana
 * göre sıralı; değişiklikler 1'den numaralanır (`n` — grafikteki işaret ve altındaki listede aynı numara).
 */
/** Bilinen değişiklik olayları (etiket `chart.ev.event.<OLAY>`); başkası "değişiklik" diye gösterilir. */
export const CHANGE_EVENTS = Object.freeze(['CREATE', 'UPDATE', 'RESTORE'])

export function chartEvents(data, domain) {
  const [d0, d1] = Array.isArray(domain) && domain.length === 2 ? domain : [NaN, NaN]
  const inWindow = Number.isFinite(d0) && Number.isFinite(d1) && d1 > d0
  const alarms = []
  for (const a of Array.isArray(data?.alarms) ? data.alarms : []) {
    if (!a || typeof a !== 'object') continue
    const from = parse(a.from)
    if (!Number.isFinite(from)) continue
    const toRaw = a.to == null || a.to === '' ? null : parse(a.to)
    if (toRaw != null && !Number.isFinite(toRaw)) continue
    const open = toRaw == null
    const end = open ? (inWindow ? d1 : from) : toRaw
    if (end < from) continue
    if (inWindow && (end < d0 || from > d1)) continue
    const x1 = inWindow ? Math.max(from, d0) : from
    const x2 = inWindow ? Math.min(end, d1) : end
    alarms.push({
      from: a.from, to: open ? null : a.to, open, x1, x2: Math.max(x2, x1), level: String(a.level || '').toUpperCase() || null,
      tone: alarmTone(a.level), type: typeof a.type === 'string' ? a.type : null, durationMs: open ? null : end - from,
      clippedStart: inWindow && from < d0, clippedEnd: inWindow && !open && end > d1,
    })
  }
  alarms.sort((a, b) => a.x1 - b.x1 || a.x2 - b.x2)
  const changes = []
  for (const c of Array.isArray(data?.changes) ? data.changes : []) {
    if (!c || typeof c !== 'object') continue
    const x = parse(c.at)
    if (!Number.isFinite(x) || (inWindow && (x < d0 || x > d1))) continue
    const all = (Array.isArray(c.fields) ? c.fields : []).filter((f) => typeof f === 'string' && f.trim()).map((f) => f.trim())
    const fields = [...new Set(all)]
    const event = CHANGE_EVENTS.includes(String(c.event || '').toUpperCase()) ? String(c.event).toUpperCase() : 'UPDATE'
    changes.push({ at: c.at, x, event, fields: fields.slice(0, MAX_CHANGE_FIELDS), moreFields: Math.max(0, fields.length - MAX_CHANGE_FIELDS) })
  }
  changes.sort((a, b) => a.x - b.x)
  const keptAlarms = alarms.slice(-MAX_CHART_ALARMS)
  const keptChanges = changes.slice(-MAX_CHART_CHANGES)
  return {
    alarms: keptAlarms.map((a, i) => ({ ...a, key: `a${i}-${a.x1}` })),
    changes: keptChanges.map((c, i) => ({ ...c, n: i + 1, key: `c${i}-${c.x}` })),
    moreAlarms: alarms.length - keptAlarms.length,
    moreChanges: changes.length - keptChanges.length,
    alarmsUnreadable: !!data && typeof data === 'object' && 'alarms' in data && data.alarms === null,
    changesUnreadable: !!data && typeof data === 'object' && 'changes' in data && data.changes === null,
  }
}

/** Yanıtın olay alanı taşıyıp taşımadığı (sunucu destekliyor mu) — boş dizi de "destekliyor, olay yok" demektir. */
export function hasEventFields(data) {
  if (!data || typeof data !== 'object') return false
  return 'alarms' in data || 'changes' in data
}
