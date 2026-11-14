/**
 * Kişisel Panom Sayfa Hızı kutuları (2026-11-14, Sayfa Hızı zenginleştirme madde 21) — SAF özetler. Kaynak mevcut uç
 * `GET /api/monitoring/pagespeed` (kapsam sunucuda — kullanıcının görebildiği izlemeler). Yeni uç YOK.
 *
 * <ul>
 *   <li>`pagespeed_slowest` — son ölçümde en yavaş yüklenen sayfalar (yükleme süresine göre azalan). Süre tavanına takılan
 *       ölçüm alt sınırdır ("≥"). Sayfası alınamayan ya da hiç ölçülmemiş izleme sıralamaya GİRMEZ (süre yok), ayrıca sayılır.</li>
 *   <li>`pagespeed_over_budget` — son ölçümde en az bir eşiği / bütçeyi aşan sayfalar (aşılan ölçülerle). Yeşil yalnız
 *       etkin izlemelerin HEPSİ ölçülmüş ve hiçbiri aşmamışken; alınamayan / bilinmeyen varsa gri (karar verilemez).</li>
 * </ul>
 */

const COLLATOR = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

const num = (v) => {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

const hiddenOf = (data) => data && typeof data === 'object' && !Array.isArray(data) && data.visible === false

const breachedOf = (m) => {
  const b = m?.breached_metrics
  if (Array.isArray(b)) return b.map((x) => String(x).trim().toUpperCase()).filter(Boolean)
  if (typeof b === 'string') return b.split(',').map((x) => x.trim().toUpperCase()).filter(Boolean)
  return []
}

/** Etkin izlemenin son durumu: 'down' (alınamadı) · 'unknown' (ölçüm yok) · 'slow' (eşik aşıldı) · 'ok'. */
export function pageState(m) {
  if (!m || m.active === false) return null
  if (m.status === 'DOWN' || m.ok === false) return 'down'
  if (m.status === 'SLOW' || breachedOf(m).length > 0) return 'slow'
  if (m.status === 'OK') return 'ok'
  return 'unknown'
}

/**
 * En yavaş sayfalar → `{ hidden, active, measured, down, unknown, rows, more, tone }`. `rows` en çok `limit` satır:
 * `{ id, name, url, ms, lowerBound, budget, over, team }` — `over` = yükleme bütçesini aştı (sunucu kararı ya da değer >
 * bütçe). Ton: alınamayan varsa kırmızı; listedeki biri bütçeyi aştıysa turuncu; ölçülen hepsi bütçe içinde ve bilinmeyen
 * yoksa yeşil; aksi gri (bilinmeyen asla yeşil değil).
 */
export function pageSpeedSlowest(data, limit = 5) {
  if (hiddenOf(data)) return { hidden: true, active: 0, measured: 0, down: 0, unknown: 0, rows: [], more: 0, tone: 'muted' }
  const list = (Array.isArray(data) ? data : []).filter((m) => m && typeof m === 'object' && m.active !== false)
  let down = 0, unknown = 0
  const measured = []
  for (const m of list) {
    const st = pageState(m)
    const ms = num(m.response_ms)
    if (st === 'down') { down += 1; continue }
    if (st === 'unknown' || ms == null) { unknown += 1; continue }
    const budget = num(m.max_load_ms)
    const over = breachedOf(m).includes('LOAD') || (budget != null && budget > 0 && ms > budget)
    measured.push({ id: m.id, name: m.name || m.url || null, url: m.url || null, ms, lowerBound: m.time_truncated === true,
      budget: budget != null && budget > 0 ? budget : null, over, team: m.team_name || null })
  }
  measured.sort((a, b) => b.ms - a.ms || COLLATOR.compare(String(a.name || ''), String(b.name || '')))
  const cap = Math.max(0, limit | 0)
  const rows = measured.slice(0, cap)
  const tone = down > 0 ? 'danger'
    : measured.some((r) => r.over) ? 'warning'
    : measured.length > 0 && unknown === 0 ? 'success'
    : 'muted'
  return { hidden: false, active: list.length, measured: measured.length, down, unknown, rows, more: Math.max(0, measured.length - cap), tone }
}

/**
 * Eşik / bütçe aşan sayfalar → `{ hidden, active, over, down, unknown, ok, rows, more, tone }`. `rows`: `{ id, name, url,
 * metrics (aşılan anahtarlar), team }`, en çok ölçü aşan önce, sonra ad.
 */
export function pageSpeedOverBudget(data, limit = 5) {
  if (hiddenOf(data)) return { hidden: true, active: 0, over: 0, down: 0, unknown: 0, ok: 0, rows: [], more: 0, tone: 'muted' }
  const list = (Array.isArray(data) ? data : []).filter((m) => m && typeof m === 'object' && m.active !== false)
  const out = { active: list.length, over: 0, down: 0, unknown: 0, ok: 0 }
  const overRows = []
  for (const m of list) {
    const st = pageState(m)
    if (st === 'down') out.down += 1
    else if (st === 'unknown') out.unknown += 1
    else if (st === 'slow') {
      out.over += 1
      overRows.push({ id: m.id, name: m.name || m.url || null, url: m.url || null, metrics: breachedOf(m), team: m.team_name || null })
    } else out.ok += 1
  }
  overRows.sort((a, b) => b.metrics.length - a.metrics.length || COLLATOR.compare(String(a.name || ''), String(b.name || '')))
  const cap = Math.max(0, limit | 0)
  const tone = out.over > 0 ? 'warning'
    : out.active > 0 && out.down === 0 && out.unknown === 0 ? 'success'
    : 'muted'
  return { hidden: false, ...out, rows: overRows.slice(0, cap), more: Math.max(0, overRows.length - cap), tone }
}
