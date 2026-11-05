/**
 * Kişisel Panom "Veri kuralları" kutusu (`data_rules`, 2026-11-05, dbq2 C5) — SAF özet. Kaynak mevcut uç
 * `GET /api/monitoring/dbquery` (veritabanı sorgu izlemeleri; kapsam sunucuda — kullanıcının görebildikleri). Satır verisi
 * okunmaz: yalnız durum alanları.
 *
 * <p>Ton (renkli panel dahil): hatalı (FAIL) varsa kırmızı; eşik aşımı (BREACH), yavaş sorgu ya da açık alarm varsa turuncu; etkin
 * izlemelerin HEPSİ son çalıştırmada temizse yeşil; değerlendirilemeyen / hiç çalışmamış izleme varsa ya da etkin izleme
 * yoksa gri (bilinmeyen asla yeşil değildir). Okuma yetkisi yoksa (`visible:false`) gri "yetki yok".
 */

const COLLATOR = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
/** Sorun sırası: hata → kritik ihlal → uyarı ihlali → açık alarm (son çalıştırma temiz) → değerlendirilemedi. */
const PROBLEM_RANK = { fail: 0, critical: 1, warning: 2, slow: 3, alarm: 4, unknown: 5 }

/** Tek izlemenin sorun sınıfı ya da null (temiz / duraklatılmış). */
export function problemOf(m) {
  if (!m || m.active === false) return null
  if (m.status === 'FAIL') return 'fail'
  if (m.status === 'BREACH') return m.breach_level === 'CRITICAL' ? 'critical' : 'warning'
  // Yavaş sorgu (OK + slow) sayfadaki gibi ayrı uyarı sınıfı — "temiz" sayılmaz, kutu yeşil olmaz (D9)
  if (m.status === 'OK' && m.slow) return 'slow'
  if (m.active_alarm) return 'alarm'
  if (m.status !== 'OK') return 'unknown'
  return null
}

/**
 * Liste → `{ hidden, active, paused, fail, breach, alarm, unknown, clean, problems, rows, more, tone }`. `breach` = eşik
 * aşımı (uyarı + kritik); `alarm` = son çalıştırması temiz ama alarmı henüz kapanmamış; `rows` en çok `limit` sorunlu izleme
 * (en ağır önce, sonra ad), `more` kalan sorunlu sayısı.
 */
export function dataRulesSummary(data, limit = 5) {
  if (data && typeof data === 'object' && !Array.isArray(data) && data.visible === false) {
    return { hidden: true, active: 0, paused: 0, fail: 0, breach: 0, alarm: 0, slow: 0, unknown: 0, clean: 0, problems: 0, rows: [], more: 0, tone: 'muted' }
  }
  const list = (Array.isArray(data) ? data : []).filter((m) => m && typeof m === 'object')
  const out = { hidden: false, active: 0, paused: 0, fail: 0, breach: 0, alarm: 0, slow: 0, unknown: 0, clean: 0 }
  const problems = []
  for (const m of list) {
    if (m.active === false) { out.paused += 1; continue }
    out.active += 1
    const p = problemOf(m)
    if (p === 'fail') out.fail += 1
    else if (p === 'critical' || p === 'warning') out.breach += 1
    else if (p === 'alarm') out.alarm += 1
    else if (p === 'slow') out.slow += 1
    else if (p === 'unknown') out.unknown += 1
    else out.clean += 1
    if (p) problems.push({ m, p })
  }
  problems.sort((a, b) => PROBLEM_RANK[a.p] - PROBLEM_RANK[b.p] || COLLATOR.compare(String(a.m.name || ''), String(b.m.name || '')))
  const cap = Math.max(0, limit | 0)
  const tone = out.fail > 0 ? 'danger'
    : out.breach > 0 || out.alarm > 0 || out.slow > 0 ? 'warning'
    : out.active > 0 && out.unknown === 0 ? 'success'
    : 'muted'
  return {
    ...out, problems: problems.length,
    rows: problems.slice(0, cap).map(({ m, p }) => ({
      id: m.id, name: m.name || null, problem: p, connection: m.profile?.name || null, team: m.team_name || null,
    })),
    more: Math.max(0, problems.length - cap),
    tone,
  }
}
