/**
 * Ayarlar → Platform → Nöbet rutinleri sayfasının SAF modeli (2026-11-26). Sunucu sözleşmesi (`/api/admin/oncall-routines`):
 *  - GET `/settings` → `{ values: { team_ids, push_reminders, completion_notice, handover_notice }, retention_days, read_only,
 *    teams: [{ id, name }] }` — kapsamlı müdür `read_only: true` alır (anahtarlar GLOBAL_ONLY);
 *  - PUT `/settings` gövde = YALNIZ değişen anahtarlar (alan hatası `team_ids`);
 *  - GET `/overview` → `[{ team_id, team_name, module_visible, configured, enabled, tasks_active, today_status,
 *    last30: { workdays, completed, partial, incomplete, missed } }]`.
 *
 * Bilinmeyen asla 0 / yeşil değildir: okunamayan sayı "—", eksik sözleşme alanı "bilinmiyor".
 */
import { isDirty as idsDiffer, normalizeIds } from './noc/operatorTeamsModel.js'

/** Takım özeti: tablo bu kap genişliğinden itibaren, daha darda kartlar. */
export const ORS_TABLE_MIN_PX = 760

/** Kurum geneli bildirim anahtarları (sunucu alan adları, kalıcı). Sıra = ekrandaki sıra. */
export const ORS_SWITCHES = Object.freeze(['push_reminders', 'completion_notice', 'handover_notice'])

/** Veri saklama politikası kimliği (RetentionCatalog, kalıcı) — "Veri Saklama'da aç" bağlantısı onu açıp vurgular. */
export const ORS_RETENTION_POLICY = 'oncall-routine-days'

/** Bugünkü gün durumunun kodları (sunucu §1.6). Bilinmeyen kod `UNKNOWN`. */
export const ORS_TODAY_CODES = Object.freeze([
  'OPEN', 'COMPLETED', 'COMPLETED_PARTIAL', 'INCOMPLETE', 'MISSED', 'NONE', 'NOT_WORKDAY', 'FUTURE', 'PAUSED',
])

const bool = (v) => (v === true ? true : v === false ? false : null)

/**
 * GET yanıtının verisi → sayfa durumu. Anahtar gelmediyse (bozuk / eski yanıt) `null` (bilinmiyor) — sayfa o anahtarı
 * kilitler, uydurmaz. `readOnly` = sunucunun `read_only`'si ya da çağıranın bildiği küresel olmayan yönetici.
 */
export function normalizeSettings(data, readOnly = false) {
  const values = data && typeof data === 'object' && data.values && typeof data.values === 'object' ? data.values : {}
  const out = { team_ids: normalizeIds(values.team_ids) }
  for (const k of ORS_SWITCHES) out[k] = bool(values[k])
  const days = Number(data?.retention_days)
  return {
    values: out,
    retentionDays: Number.isFinite(days) && days > 0 ? Math.floor(days) : null,
    readOnly: !!readOnly || data?.read_only === true,
    teams: Array.isArray(data?.teams)
      ? data.teams.filter((x) => x && x.id != null).map((x) => ({ id: Number(x.id), name: x.name || null }))
      : [],
  }
}

/** Taslak kayıtlıdan farklı mı (takım sırası önemsiz). */
export function isFormDirty(saved, draft) {
  return Object.keys(changedSettings(saved, draft)).length > 0
}

/**
 * Kayıt gövdesi: YALNIZ değişen anahtarlar (eşzamanlı başka bir kaydı ezmez). Bilinmeyen (null) kayıtlı anahtar gönderilmez
 * — kullanıcı onu değiştiremez (anahtar kilitli). Boş nesne = istek atılmaz.
 */
export function changedSettings(saved, draft) {
  const body = {}
  if (!saved || !draft) return body
  // Takım sırası önemsiz (seçim sırası korunur ama aynı küme değişiklik sayılmaz)
  if (idsDiffer(saved.team_ids, draft.team_ids)) body.team_ids = normalizeIds(draft.team_ids)
  for (const k of ORS_SWITCHES) {
    if (saved[k] == null || draft[k] == null) continue
    if (!!saved[k] !== !!draft[k]) body[k] = !!draft[k]
  }
  return body
}

/** Sunucu alan hatası → alan adı (yalnız bu sayfanın alanları; diğerleri üst bantta). */
export function fieldOf(res) {
  const f = res?.field ?? res?.errorInfo?.field ?? null
  if (f === 'team_ids' || ORS_SWITCHES.includes(f)) return f
  const fields = res?.fields && typeof res.fields === 'object' ? Object.keys(res.fields) : []
  return fields.find((x) => x === 'team_ids' || ORS_SWITCHES.includes(x)) || null
}

/** Eski pod (uç yok): 404 / 405. */
export function isUnsupported(res) {
  return !!res && typeof res === 'object' && res.success === false && (res.status === 404 || res.status === 405)
}

const num = (v) => {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) && n >= 0 ? Math.floor(n) : null
}

/**
 * Son 30 gün tamamlanma yüzdesi: (tamamlanan + kısmen tamamlanan) ÷ iş günü, aşağı yuvarlanır (%100 yalnız hepsi). İş günü
 * yoksa ya da veri eksikse null ("—").
 */
export function completionPct(last30) {
  if (!last30 || typeof last30 !== 'object') return null
  const w = num(last30.workdays)
  const c = num(last30.completed)
  const p = num(last30.partial)
  if (w == null || c == null || p == null || w <= 0) return null
  const done = Math.min(w, c + p)
  if (done >= w) return 100
  return Math.floor((done / w) * 100)
}

/** Bugünkü durum kodu (bilinmeyen → UNKNOWN). */
export function todayCode(row) {
  const s = typeof row?.today_status === 'string' ? row.today_status.toUpperCase() : null
  return s && ORS_TODAY_CODES.includes(s) ? s : 'UNKNOWN'
}

/** Bugünkü durumun rozet tonu — yeşil yalnız tamamlandıysa. */
export function todayTone(code) {
  switch (code) {
    case 'COMPLETED':
    case 'COMPLETED_PARTIAL': return 'success'
    case 'MISSED': return 'danger'
    case 'INCOMPLETE': return 'warning'
    case 'OPEN': return 'info'
    default: return 'muted'
  }
}

/** Takımın rutin durumu: `none` (kurulmamış) | `paused` | `active` | `unknown`. */
export function routineState(row) {
  if (row?.configured === false) return 'none'
  if (row?.configured !== true) return 'unknown'
  if (row?.enabled === false) return 'paused'
  if (row?.enabled === true) return 'active'
  return 'unknown'
}

/** Tamamlanma yüzdesinin tonu: ≥ 90 yeşil, ≥ 70 uyarı, altı kırmızı; bilinmiyor gri. */
export function pctTone(pct) {
  if (pct == null) return 'muted'
  if (pct >= 90) return 'success'
  if (pct >= 70) return 'warning'
  return 'danger'
}

/** Özet satırları (geçersiz satır düşer). */
export function overviewRows(data) {
  if (!Array.isArray(data)) return null
  return data.filter((r) => r && typeof r === 'object' && r.team_id != null).map((r) => {
    const code = todayCode(r)
    const pct = completionPct(r.last30)
    return {
      id: r.team_id,
      name: r.team_name || null,
      moduleVisible: r.module_visible === true ? true : r.module_visible === false ? false : null,
      state: routineState(r),
      tasks: num(r.tasks_active),
      today: code,
      todayTone: todayTone(code),
      pct,
      pctTone: pctTone(pct),
      workdays: num(r.last30?.workdays),
      missed: num(r.last30?.missed),
    }
  })
}
