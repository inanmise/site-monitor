/**
 * Sayfa Hızı ölçüm ayrıntılarının SAF modeli (2026-11-14, Sayfa Hızı zenginleştirme 1. sürüm, madde 8 / 9 / 15).
 *
 * <ul>
 *   <li><b>Süre kırılımı</b> — DNS / bağlantı / TLS / sunucu ayrı, taze bir bağlantıda ölçülür (yalnız bilgi; TTFB eşiği
 *       gerçek isteğin ilk baytına bakar), HTML indirme = HTML süresi − TTFB. Ölçülmemiş faz listede yer almaz.</li>
 *   <li><b>Tür kırılımı</b> — ölçüm başına saklanan bayt / dosya sayısı. Eski satırda (kırılım alanı yok) `null` döner:
 *       bilinmeyen değer ASLA 0 sayılmaz.</li>
 *   <li><b>Tür serisi</b> — sunucunun kovaları; kırılımı olmayan kova `null` (grafikte boşluk, 0 değil).</li>
 * </ul>
 */

export const PHASE_KEYS = ['dns', 'connect', 'tls', 'server', 'download']

/**
 * Bir uç yanıtı neden başarısız (inceleme F12): `'unsupported'` = eski pod bu ucu bilmiyor (405, ya da eşlenmemiş yolun
 * `SpaNotFoundAdvice` cevabı — 404 + `code: "NOT_FOUND"`), `'gone'` = yeni sunucu izlemeyi bulamadı (kodsuz 404 — silinmiş
 * izleme), `'error'` = diğer her şey. "Silinmiş izleme"yi "bu sürümde yok" diye yanlış söylememek için.
 */
export function failureKind(res) {
  const status = Number(res?.status ?? res?.errorInfo?.status)
  const code = res?.code ?? res?.errorInfo?.code
  if (status === 405) return 'unsupported'
  if (status === 404) return code === 'NOT_FOUND' ? 'unsupported' : 'gone'
  return 'error'
}
export const TYPE_KEYS = ['html', 'js', 'css', 'img', 'font', 'other']

const num = (v) => {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Süre kırılımı: [{key, ms}] — ölçülmüş fazlar (≥ 0), ölçülmemişse boş dizi. */
export function phasesOf(m) {
  if (!m) return []
  const out = []
  const map = { dns: m.dns_ms, connect: m.connect_ms, tls: m.tls_ms, server: m.server_ms }
  for (const key of ['dns', 'connect', 'tls', 'server']) {
    const v = num(map[key])
    if (v != null && v >= 0) out.push({ key, ms: Math.round(v) })
  }
  const html = num(m.html_ms)
  const ttfb = num(m.ttfb_ms)
  if (html != null && ttfb != null && html >= ttfb) out.push({ key: 'download', ms: Math.round(html - ttfb) })
  return out
}

/**
 * Tür kırılımı: { rows: [{key, bytes, count}], total, firstParty, thirdParty } ya da `null` (eski satır / kırılım yok).
 * Sıra sabit (HTML, JS, CSS, görsel, yazı tipi, diğer) — ekranda ve CSV'de aynı.
 */
export function typeBreakdownOf(m) {
  if (!m) return null
  const known = TYPE_KEYS.some((k) => num(m[`${k}_bytes`]) != null)
  if (!known) return null
  const rows = TYPE_KEYS.map((key) => ({
    key,
    bytes: num(m[`${key}_bytes`]) ?? 0,
    count: key === 'html' ? null : num(m[`${key}_count`]),
  }))
  const total = rows.reduce((s, r) => s + r.bytes, 0)
  return { rows, total, firstParty: num(m.first_party_bytes), thirdParty: num(m.third_party_bytes) }
}

/** Ölçüm alt sınır mı (süre tavanı ya da kaynak/bayt tavanı)? — "Kısmi" rozeti ve "≥" öneki. */
export function isPartial(m) {
  return m?.time_truncated === true
}

/**
 * Tür serisi → grafik verisi: [{x (ms), html, js, css, img, font, other (KB, 1 ondalık) | null}]. Değeri olmayan kova
 * tüm türlerde null kalır (yığın çubuk çizilmez = boşluk). Bozuk zaman damgalı nokta atılır.
 */
export function typeSeriesData(points) {
  if (!Array.isArray(points)) return []
  const out = []
  for (const p of points) {
    if (!p || typeof p.ts !== 'string') continue
    const x = Date.parse(p.ts.endsWith('Z') ? p.ts : `${p.ts}Z`)
    if (!Number.isFinite(x)) continue
    const row = { x, n: num(p.n) ?? 0 }
    for (const k of TYPE_KEYS) {
      const v = num(p[k])
      row[k] = v == null ? null : Math.round((v / 1024) * 10) / 10
    }
    out.push(row)
  }
  return out
}

/** Bir çubuğa düşen en küçük piksel genişliği — altında çubuklar yok olur (recharts: bant − 2 px). */
export const MIN_BAR_PX = 4

/**
 * Grafiğin sığdırabileceği kadar kova (inceleme F3): `rows.length` > `maxBars` ise ardışık kovalar gruplanır — her türün
 * değeri o gruptaki ölçümlerin AĞIRLIKLI ortalaması (ağırlık = kovadaki ölçüm sayısı `n`, en az 1); grupta hiç değer yoksa
 * null kalır (boşluk, 0 değil). `x` grubun ilk kovasının zamanı. Gerçekçi aralıkta (30 dk aralıklı izleme, 7 gün saatlik
 * = 168 kova; 30 gün = 720) 390 px telefonda çubuk genişliği eksiye düşüp görünmez oluyordu.
 */
export function rebucketTypeSeries(rows, maxBars) {
  if (!Array.isArray(rows)) return []
  const cap = Math.max(1, Math.floor(Number(maxBars) || 0))
  if (rows.length <= cap) return rows
  const size = Math.ceil(rows.length / cap)
  const out = []
  for (let i = 0; i < rows.length; i += size) {
    const group = rows.slice(i, i + size)
    const row = { x: group[0].x, n: group.reduce((s, r) => s + (Number(r.n) || 0), 0) }
    for (const k of TYPE_KEYS) {
      let sum = 0
      let weight = 0
      for (const r of group) {
        if (r[k] == null) continue
        const w = Math.max(1, Number(r.n) || 0)
        sum += r[k] * w
        weight += w
      }
      row[k] = weight === 0 ? null : Math.round((sum / weight) * 10) / 10
    }
    out.push(row)
  }
  return out
}

/** Seride en az bir kırılımlı kova var mı (yoksa boş durum). */
export function hasTypeData(data) {
  return Array.isArray(data) && data.some((r) => TYPE_KEYS.some((k) => r[k] != null))
}
