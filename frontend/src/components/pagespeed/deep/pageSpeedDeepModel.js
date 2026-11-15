// Sayfa Hızı 2. sürüm (2026-11-15) — SAF model: şelale (madde 16), iki ölçümü karşılaştırma (17), hız notu + öneriler (18),
// rapor satırları (19) ve görsel aracı köprüsünün nesne biçimi (22). React yok, ağ yok. Girdi: `/pagespeed/{id}/waterfall`
// yanıtı ("anlık görüntü": ölçüm satırı + o ölçümün saklanan kaynakları).

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const num = (v) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

/** Kaynak türleri (ekrandaki sıra) — sunucunun normalize türlerinden: IMG + FAVICON = img, IFRAME + OTHER = other. */
export const WF_TYPES = Object.freeze(['html', 'css', 'js', 'img', 'font', 'other'])

/** Sunucu türü → şelale türü. */
export function wfType(raw) {
  switch (String(raw || '').toUpperCase()) {
    case 'CSS': return 'css'
    case 'JS': return 'js'
    case 'IMG': case 'FAVICON': return 'img'
    case 'FONT': return 'font'
    default: return 'other'
  }
}

/**
 * Tür renkleri — DURUM rengi DEĞİL (yeşil / kırmızı / sarı kullanılmaz): mavi / mor ve karışımları, tema belirteçlerinden.
 * Alınamayan kaynak ayrıca kesik kenar + metinle işaretlenir (renk tek başına anlam taşımaz).
 */
export const WF_COLORS = Object.freeze({
  html: 'var(--chart-1)',
  css: 'var(--chart-5)',
  js: 'color-mix(in oklch, var(--chart-1) 50%, var(--chart-5))',
  img: 'color-mix(in oklch, var(--chart-1) 45%, var(--background))',
  font: 'color-mix(in oklch, var(--chart-5) 45%, var(--background))',
  other: 'color-mix(in oklch, var(--muted-foreground) 60%, var(--background))',
})

/** Şelalede en çok çizilen satır (ekran akıcı kalsın; sunucu en çok 2000 döner, fazlası "ilk N" notuyla). */
export const WF_MAX_ROWS = 500

/**
 * Şelale: `{ ok, reason, html, rows, totalMs, ticks, hasStart, truncated, total, shown, unknownStart, checkedAt }`.
 * `reason`: 'none' (kırılım yok) | 'no_start' (bu sürümden önceki ölçüm — başlangıç anı saklanmamış). HTML çubuğu: 0 →
 * html_ms, içinde ilk bayt (ttfb) bekleme + indirme. Bağlantı fazları (DNS / TCP / TLS / sunucu) AYRI bir ölçümdür (faz
 * probu) — aynı zaman ekseninde çizilmez, `html.phases` olarak ayrı verilir. Kaynak çubuğu: start_ms → start_ms + süre.
 */
export function buildWaterfall(data, maxRows = WF_MAX_ROWS) {
  const d = isObj(data) ? data : {}
  const resources = (Array.isArray(d.resources) ? d.resources : []).filter(isObj)
  const check = isObj(d.check) ? d.check : null
  const total = num(d.total) ?? resources.length
  if (d.check_id == null || (!resources.length && !check)) {
    return { ok: false, reason: 'none', rows: [], html: null, totalMs: 0, ticks: [], hasStart: false, truncated: false, total: 0, shown: 0, unknownStart: 0, checkedAt: null }
  }
  const hasStart = d.has_start === true || resources.some((r) => num(r.start_ms) !== null)
  const htmlMs = num(check?.html_ms)
  // İlk bayt (ttfb_ms) AYRI, taze bir bağlantıdaki faz probunun ölçümüdür — ana isteğin ilk baytı saklanmaz; HTML çubuğunun
  // içine çizilmez (inceleme 2026-11-15: iki ayrı ölçüm aynı eksende yanıltırdı). Fazlar `phases` satırında ayrıca söylenir.
  const html = htmlMs !== null ? {
    start: 0, end: htmlMs, bytes: num(check?.html_bytes),
    phases: ['dns', 'connect', 'tls', 'server'].map((k) => ({ key: k, ms: num(check?.[`${k}_ms`]) })).filter((p) => p.ms !== null),
  } : null
  let unknownStart = 0
  const rows = []
  for (const r of resources) {
    const start = num(r.start_ms)
    if (start === null) { unknownStart++; continue }
    const dur = Math.max(0, num(r.duration_ms) ?? 0)
    rows.push({
      key: `${rows.length}:${r.url}`, url: typeof r.url === 'string' ? r.url : '', type: wfType(r.type), rawType: r.type || null,
      start: Math.max(0, start), duration: dur, end: Math.max(0, start) + dur, bytes: num(r.bytes), truncatedBytes: r.truncated === true,
      status: num(r.http_status), failed: r.failed === true, thirdParty: r.third_party === true,
      // Genişletilmiş ölçüm (2026-11-15): kaynağı başvuran stil dosyası; null = HTML öğesi / klasik kip / eski satır
      foundIn: typeof r.found_in === 'string' && r.found_in ? r.found_in : null,
    })
  }
  rows.sort((a, b) => a.start - b.start || a.end - b.end)
  const shownRows = rows.slice(0, Math.max(1, maxRows))
  const totalMs = Math.max(htmlMs ?? 0, ...shownRows.map((r) => r.end), 1)
  if (!hasStart) {
    return { ok: false, reason: 'no_start', rows: [], html, totalMs, ticks: niceTicks(totalMs), hasStart: false, truncated: d.truncated === true,
      total, shown: 0, unknownStart, checkedAt: check?.checked_at ?? null }
  }
  return {
    ok: true, reason: null, html, rows: shownRows, totalMs, ticks: niceTicks(totalMs), hasStart: true,
    truncated: d.truncated === true || rows.length > shownRows.length, total, shown: shownRows.length, unknownStart,
    checkedAt: check?.checked_at ?? null,
  }
}

/** Eksen işaretleri: 0'dan başlayan, en çok 6 "yuvarlak" adım (1 / 2 / 5 × 10^n ms). */
export function niceTicks(totalMs) {
  const t = Math.max(1, Number(totalMs) || 1)
  const raw = t / 5
  const pow = 10 ** Math.floor(Math.log10(raw))
  // En küçük adım 1 ms (ms tam sayıya yuvarlanır; daha küçük adım aynı işareti yineler — React anahtarı çakışırdı)
  const step = Math.max(1, [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= raw) || pow * 10)
  const out = []
  for (let v = 0; v <= t + 1e-9 && out.length < 12; v += step) out.push(Math.round(v))
  return out
}

/** Satırın yüzde konumu (sol + genişlik), en az 0,4 % genişlik (çok kısa istek de görünsün). */
export function barBox(start, end, totalMs) {
  const t = Math.max(1, totalMs)
  const left = Math.max(0, Math.min(100, (start / t) * 100))
  const width = Math.max(0.4, Math.min(100 - left, ((end - start) / t) * 100))
  return { left, width }
}

/** Türe göre toplam: `{ html: {bytes, count}, css: …}` — bayt bilinmeyen satır sayıya girer, bayta girmez. */
export function typeTotals(data) {
  const out = Object.fromEntries(WF_TYPES.map((k) => [k, { bytes: 0, count: 0 }]))
  const d = isObj(data) ? data : {}
  const htmlBytes = num(d.check?.html_bytes)
  if (htmlBytes !== null) { out.html.bytes = htmlBytes; out.html.count = 1 }
  for (const r of Array.isArray(d.resources) ? d.resources : []) {
    if (!isObj(r)) continue
    const k = wfType(r.type)
    out[k].count++
    const b = num(r.bytes)
    if (b !== null) out[k].bytes += b
  }
  return out
}

/**
 * Ölçüm bir ALT SINIR mı (inceleme 2026-11-15 — sunucunun `Result.lowerBound()` kuralı + şelale listesinin kesimi): süre
 * tavanı (`time_truncated`), kaynak tavanı (`capped`), bayt tavanı (`bytes_truncated`) ya da 2000 satır sınırı (`truncated`).
 * Not, rapor ve karşılaştırma bu TEK kuralı kullanır.
 */
export function isLowerBound(data) {
  const d = isObj(data) ? data : {}
  const c = isObj(d.check) ? d.check : {}
  return c.time_truncated === true || c.capped === true || c.bytes_truncated === true || d.truncated === true
}

// ── İki ölçümü karşılaştır (madde 17) ───────────────────────────────────────────────────────────────────────────────

/** Karşılaştırılan özet satırları (hepsinde küçük olan iyi). */
export const CMP_METRICS = Object.freeze(['load', 'ttfb', 'html', 'bytes', 'requests', 'unloaded', 'img', 'js', 'css', 'font', 'third'])
/** Kesik (alt sınır) ölçümde yönü bilinmeyen satırlar — süre / boyut / sayı kesimle küçülür; ilk bayt ve HTML süresi değil. */
export const CMP_CUT_SENSITIVE = Object.freeze(['load', 'bytes', 'requests', 'unloaded', 'img', 'js', 'css', 'font', 'third'])

function snapshotMetrics(data) {
  const c = isObj(data?.check) ? data.check : {}
  const tt = typeTotals(data)
  const res = (Array.isArray(data?.resources) ? data.resources : []).filter(isObj)
  const thirdBytes = res.reduce((n, r) => n + (r.third_party === true ? (num(r.bytes) ?? 0) : 0), 0)
  const hasRes = res.length > 0
  return {
    load: num(c.response_ms), ttfb: num(c.ttfb_ms), html: num(c.html_ms), bytes: num(c.total_bytes),
    requests: num(c.request_count), unloaded: num(c.failed_count),
    img: hasRes ? tt.img.bytes : null, js: hasRes ? tt.js.bytes : null, css: hasRes ? tt.css.bytes : null,
    font: hasRes ? tt.font.bytes : null, third: hasRes ? thirdBytes : null,
  }
}

/** Kaynak farkı eşikleri: büyüme ≥ %10 ve ≥ 5 KB; yavaşlama ≥ %50 ve ≥ 200 ms. */
export const CMP_GROW_PCT = 10
export const CMP_GROW_MIN_BYTES = 5 * 1024
export const CMP_SLOW_PCT = 50
export const CMP_SLOW_MIN_MS = 200
/** Her listede en çok satır. */
export const CMP_LIST_MAX = 20

/**
 * İki anlık görüntüyü karşılaştırır: `{ rows: [{key, a, b, delta, pct, worse}], added, removed, grown, slower, partial }`.
 * `worse`: B, A'dan kötü mü (küçük olan iyi); bilinmeyen değerde null (renk / yön yok). Kaynak farkı yalnız İKİ ölçümün de
 * kaynakları saklıysa (`partial` = biri kesilmiş: liste alt sınır). Adres bazında: eklenen, kalkan, büyüyen, yavaşlayan.
 */
export function compareSnapshots(a, b) {
  const ma = snapshotMetrics(a)
  const mb = snapshotMetrics(b)
  const cut = isLowerBound(a) || isLowerBound(b)
  // Ölçüm kipi farklı (2026-11-15, genişletilmiş ölçüm): toplamlar karşılaştırılamaz — kesik ölçümdeki gibi yön yok
  const modeDiffers = isObj(a?.check) && isObj(b?.check) && (a.check.extended === true) !== (b.check.extended === true)
  const rows = CMP_METRICS.map((key) => {
    const va = ma[key]
    const vb = mb[key]
    const known = va !== null && vb !== null
    const delta = known ? vb - va : null
    const pct = known && va > 0 ? Math.round(((vb - va) / va) * 1000) / 10 : null
    // Kesik ölçümde boyut / sayı / süre küçülmesi "iyileşme" değildir — yön verilmez (renk yok)
    const directional = known && !((cut || modeDiffers) && CMP_CUT_SENSITIVE.includes(key))
    return { key, a: va, b: vb, delta, pct, worse: directional ? (delta === 0 ? false : delta > 0) : null }
  })
  const listA = (Array.isArray(a?.resources) ? a.resources : []).filter((r) => isObj(r) && typeof r.url === 'string')
  const listB = (Array.isArray(b?.resources) ? b.resources : []).filter((r) => isObj(r) && typeof r.url === 'string')
  const mapA = new Map(listA.map((r) => [r.url, r]))
  const mapB = new Map(listB.map((r) => [r.url, r]))
  const added = []
  const removed = []
  const grown = []
  const slower = []
  for (const r of listB) {
    const o = mapA.get(r.url)
    if (!o) { added.push({ url: r.url, type: wfType(r.type), bytes: num(r.bytes) }); continue }
    const ob = num(o.bytes)
    const nb = num(r.bytes)
    if (ob !== null && nb !== null && nb - ob >= CMP_GROW_MIN_BYTES && (ob === 0 || ((nb - ob) / ob) * 100 >= CMP_GROW_PCT)) {
      grown.push({ url: r.url, type: wfType(r.type), a: ob, b: nb, delta: nb - ob })
    }
    const od = num(o.duration_ms)
    const nd = num(r.duration_ms)
    if (od !== null && nd !== null && nd - od >= CMP_SLOW_MIN_MS && (od === 0 || ((nd - od) / od) * 100 >= CMP_SLOW_PCT)) {
      slower.push({ url: r.url, type: wfType(r.type), a: od, b: nd, delta: nd - od })
    }
  }
  for (const r of listA) if (!mapB.has(r.url)) removed.push({ url: r.url, type: wfType(r.type), bytes: num(r.bytes) })
  const byBytes = (x, y) => (y.bytes ?? 0) - (x.bytes ?? 0)
  const byDelta = (x, y) => y.delta - x.delta
  return {
    rows,
    resourcesKnown: listA.length > 0 && listB.length > 0,
    added: { items: added.sort(byBytes).slice(0, CMP_LIST_MAX), total: added.length },
    removed: { items: removed.sort(byBytes).slice(0, CMP_LIST_MAX), total: removed.length },
    grown: { items: grown.sort(byDelta).slice(0, CMP_LIST_MAX), total: grown.length },
    slower: { items: slower.sort(byDelta).slice(0, CMP_LIST_MAX), total: slower.length },
    partial: cut,
    modeDiffers,
  }
}

// ── Hız notu + öneriler (madde 18) ─────────────────────────────────────────────────────────────────────────────────

/**
 * Bileşenler (ağırlık, eğri noktaları [değer, puan]) — doğrusal parçalı, aradaki değer enterpolasyonla. Kaynak analizinin
 * kurallarının HAFİF bir benzeri (tarayıcı yok, sıkıştırma / önbellek bilgisi yok); ölçülmeyen bileşen nota girmez.
 */
export const GRADE_COMPONENTS = Object.freeze([
  { key: 'load', weight: 30, curve: [[1000, 100], [2500, 70], [4000, 40], [8000, 0]] },
  { key: 'ttfb', weight: 15, curve: [[200, 100], [600, 70], [1500, 30], [3000, 0]] },
  { key: 'weight', weight: 20, curve: [[1024 * 1024, 100], [2.5 * 1024 * 1024, 70], [5 * 1024 * 1024, 30], [10 * 1024 * 1024, 0]] },
  { key: 'requests', weight: 10, curve: [[40, 100], [80, 70], [150, 30], [300, 0]] },
  { key: 'images', weight: 10, curve: [[500 * 1024, 100], [1.5 * 1024 * 1024, 60], [4 * 1024 * 1024, 0]] },
  { key: 'js', weight: 10, curve: [[300 * 1024, 100], [800 * 1024, 60], [2 * 1024 * 1024, 0]] },
  { key: 'unloaded', weight: 5, curve: [[0, 100], [1, 60], [3, 20], [5, 0]] },
])

/** Harf eşikleri (kaynak analiziyle aynı): A ≥ 90, B ≥ 80, C ≥ 70, D ≥ 60, E ≥ 50, F. */
export function gradeLetterOf(score) {
  if (score === null || score === undefined || !Number.isFinite(score)) return null
  return score >= 90 ? 'A' : score >= 80 ? 'B' : score >= 70 ? 'C' : score >= 60 ? 'D' : score >= 50 ? 'E' : 'F'
}

/** Eğri üzerinde puan (0–100). */
export function curveScore(value, curve) {
  if (value === null || !Number.isFinite(value)) return null
  if (value <= curve[0][0]) return curve[0][1]
  for (let i = 1; i < curve.length; i++) {
    const [x1, y1] = curve[i]
    const [x0, y0] = curve[i - 1]
    if (value <= x1) return Math.round(y0 + ((value - x0) / (x1 - x0)) * (y1 - y0))
  }
  return curve[curve.length - 1][1]
}

/** Öneri kodları (sıra = önem). Metinler `pspd.grade.rec.<CODE>`. */
export const GRADE_RECS = Object.freeze(['FAILED_RESOURCES', 'SLOW_TTFB', 'HEAVY_PAGE', 'LARGE_IMAGES', 'HEAVY_JS', 'THIRD_PARTY_HEAVY',
  'TOO_MANY_REQUESTS', 'SLOW_RESOURCES', 'ALL_GOOD'])
/** Öneri eşikleri. */
export const LARGE_IMAGE_BYTES = 200 * 1024
export const SLOW_RESOURCE_MS = 1000

/**
 * Hız notu: `{ score, letter, partial, components: [{key, value, score, weight, lost}], recs: [{code, params, urls}] }`.
 * Yük süresi bilinmiyorsa not yok (score null). Ölçüm süre / bayt tavanında kesildiyse (`partial`) not bir ÜST SINIRDIR
 * (gerçek sayfa daha ağır / yavaş olabilir) — ekran "kısmi" der, yeşil göstermez.
 */
export function gradeSnapshot(data) {
  const d = isObj(data) ? data : {}
  const c = isObj(d.check) ? d.check : {}
  const res = (Array.isArray(d.resources) ? d.resources : []).filter(isObj)
  const hasRes = res.length > 0
  const tt = typeTotals(d)
  const values = {
    load: num(c.response_ms), ttfb: num(c.ttfb_ms), weight: num(c.total_bytes), requests: num(c.request_count),
    images: hasRes ? tt.img.bytes : null, js: hasRes ? tt.js.bytes : null, unloaded: num(c.failed_count),
  }
  const comps = GRADE_COMPONENTS.map((g) => {
    const v = values[g.key]
    const s = curveScore(v, g.curve)
    return { key: g.key, value: v, score: s, weight: g.weight, lost: s === null ? null : Math.round(((100 - s) * g.weight) / 10) / 10 }
  })
  const known = comps.filter((x) => x.score !== null)
  const wsum = known.reduce((n, x) => n + x.weight, 0)
  const score = values.load === null || wsum === 0 ? null : Math.round(known.reduce((n, x) => n + x.score * x.weight, 0) / wsum)
  const partial = isLowerBound(d)
  const recs = []
  const failed = values.unloaded ?? res.filter((r) => r.failed === true).length
  if (failed > 0) recs.push({ code: 'FAILED_RESOURCES', params: [failed], urls: res.filter((r) => r.failed === true).slice(0, 5).map((r) => r.url) })
  if (values.ttfb !== null && values.ttfb > 600) recs.push({ code: 'SLOW_TTFB', params: [values.ttfb], urls: [] })
  if (values.weight !== null && values.weight > 2.5 * 1024 * 1024) recs.push({ code: 'HEAVY_PAGE', params: [values.weight], urls: [] })
  const bigImages = res.filter((r) => wfType(r.type) === 'img' && (num(r.bytes) ?? 0) >= LARGE_IMAGE_BYTES)
    .sort((x, y) => (num(y.bytes) ?? 0) - (num(x.bytes) ?? 0))
  if (bigImages.length) {
    recs.push({ code: 'LARGE_IMAGES', params: [bigImages.length, bigImages.reduce((n, r) => n + (num(r.bytes) ?? 0), 0)],
      urls: bigImages.slice(0, 5).map((r) => r.url) })
  }
  if (values.js !== null && values.js > 500 * 1024) recs.push({ code: 'HEAVY_JS', params: [values.js], urls: [] })
  const thirdBytes = res.reduce((n, r) => n + (r.third_party === true ? (num(r.bytes) ?? 0) : 0), 0)
  const allBytes = res.reduce((n, r) => n + (num(r.bytes) ?? 0), 0)
  if (hasRes && thirdBytes > 500 * 1024 && allBytes > 0 && thirdBytes / allBytes > 0.4) {
    recs.push({ code: 'THIRD_PARTY_HEAVY', params: [thirdBytes, Math.round((thirdBytes / allBytes) * 100)], urls: [] })
  }
  if (values.requests !== null && values.requests > 80) recs.push({ code: 'TOO_MANY_REQUESTS', params: [values.requests], urls: [] })
  const slow = res.filter((r) => (num(r.duration_ms) ?? 0) >= SLOW_RESOURCE_MS).sort((x, y) => (num(y.duration_ms) ?? 0) - (num(x.duration_ms) ?? 0))
  if (slow.length) recs.push({ code: 'SLOW_RESOURCES', params: [slow.length], urls: slow.slice(0, 5).map((r) => r.url) })
  // "Her şey yolunda" yalnız ölçüm tamken ve kaynaklar okunduysa (bilinmeyen ≠ temiz)
  if (!recs.length && score !== null && !partial && hasRes) recs.push({ code: 'ALL_GOOD', params: [], urls: [] })
  return { score, letter: gradeLetterOf(score), partial, components: comps, recs }
}

// ── Görsel aracı köprüsü (madde 22) ─────────────────────────────────────────────────────────────────────────────────

/**
 * Sayfa Hızı kaynak satırı → görsel aracının nesne biçimi (`category: 'IMAGE'`, `verdict`). Yalnız görsel türü; alınamayan
 * kaynak `broken` (menü "indirilemez" der). Diğer türlerde null.
 */
export function imageItemOf(r) {
  if (!isObj(r) || typeof r.url !== 'string' || !r.url) return null
  if (String(r.type || '').toUpperCase() !== 'IMG') return null
  return {
    url: r.url, category: 'IMAGE', bytes: num(r.bytes), status: num(r.http_status),
    verdict: r.failed === true ? 'broken' : 'ok', size_source: r.truncated === true ? 'header' : null,
  }
}

/** Görsel aracı deposunun anahtarı (kaynak analizi tarama kimliğinden ayrı ad alanı). */
export const imageScopeKey = (checkId) => `ps-${checkId ?? 'latest'}`

// ── Süre sınırı ─────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Bir okumanın (yanıt gövdesi dahil) üst süresi. `request()`'in zaman aşımı başlıklar gelince biter; gövde takılırsa okuma
 * bu süre dolunca "okunamadı" sayılır (süresiz "yükleniyor" yok, yeniden dene düğmesi çıkar).
 */
export const SNAPSHOT_DEADLINE_MS = 60_000
/** Üst süre dolduğunda {@link withDeadline}'in döndürdüğü işaret. */
export const DEADLINE = Symbol('pspd-deadline')

/** Söz `ms` içinde sonuçlanmazsa {@link DEADLINE}; ret aynen geçer; zamanlayıcı her durumda temizlenir. */
export function withDeadline(promise, ms) {
  let timer
  return Promise.race([
    Promise.resolve(promise),
    new Promise((resolve) => { timer = setTimeout(() => resolve(DEADLINE), ms) }),
  ]).finally(() => clearTimeout(timer))
}
