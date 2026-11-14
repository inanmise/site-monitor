/**
 * Kişisel Panom zaman serisi kutuları (2026-11-14) — SAF model: alarm eğilimi, favori izlemelerin yanıt süresi ve 7 günlük
 * erişilebilirlik göstergesi. Hesap yok: sunucunun döndürdüğü sayılar sınıflanır, kovalara yerleştirilir, tonlanır.
 *
 * Ortak kurallar: boş kova BOŞLUKTUR (asla 0 değil — yanıt süresinde ölçüm yoksa çizgi kesilir); okunamayan / tanınmayan /
 * yetkisiz veri hiçbir zaman yeşil değildir (ton `muted`, nedeni metinle); eski sunucunun (uç yok: 404 / 405) yanıtı
 * `unsupported` olarak taşınır — kutu "bu sürümde yok" der, sonsuz hata göstermez.
 */

const n = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0)
const num = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

/** Getirici yardımcıları: HTTP durumu (`request()` sayılamaz `errorInfo`da taşır; `status` yalnız bazı uçlarda). */
export function statusOf(res) {
  if (!res || res.success !== false) return null
  return res.errorInfo?.status ?? res.status ?? null
}
/** Eski pod: uç yok (404 / 405) — yeni sunucu bu uçlara bu durumları hiç döndürmez. */
export const isUnsupported = (res) => { const s = statusOf(res); return s === 404 || s === 405 }
export const isForbidden = (res) => statusOf(res) === 403

// ── Alarm eğilimi ────────────────────────────────────────────────────────────────────────────────────────────────

export const TREND_RANGES = Object.freeze(['24h', '7d'])
export const TREND_DEFAULT_RANGE = '24h'
const TREND_KEY = 'sm.mydash.trend'

/** Seçili dönem — izleyici başına kolaylık, yalnız bu tarayıcıda (kişisel veri yok; TV ayarı gibi eşitlenmez). */
export function readTrendRange() {
  try {
    const v = localStorage.getItem(TREND_KEY)
    return TREND_RANGES.includes(v) ? v : TREND_DEFAULT_RANGE
  } catch {
    return TREND_DEFAULT_RANGE
  }
}

export function writeTrendRange(range) {
  try { localStorage.setItem(TREND_KEY, TREND_RANGES.includes(range) ? range : TREND_DEFAULT_RANGE) } catch { /* depolama kapalı */ }
}

/**
 * `/api/me/alert-trend` yanıtı → ekran özeti. `points` sunucunun kova sırasıyla (eskiden yeniye); sayım kovasında 0 gerçek
 * bir değerdir (o saatte / günde alarm açılmadı) — boşluk değil. Ton: yetki yok / tanınmayan yanıt → gri; dönemde hiç alarm
 * açılmadıysa yeşil; açılan kapanandan fazlaysa turuncu (açık alarm birikiyor); aksi hâlde nötr bilgi.
 */
export function alertTrendSummary(data) {
  const base = { hidden: false, unsupported: false, valid: false, range: null, bucket: null, points: [], opened: 0, closed: 0,
    critical: 0, silent: 0, net: 0, max: 0, peak: null, from: null, tone: 'muted' }
  if (!data || typeof data !== 'object') return base
  if (data.unsupported === true) return { ...base, unsupported: true }
  if (data.visible === false) return { ...base, hidden: true }
  if (!Array.isArray(data.buckets) || data.buckets.length === 0) return base
  const points = data.buckets.filter((b) => b && typeof b === 'object' && typeof b.t === 'string')
    .map((b) => ({ t: b.t, day: typeof b.day === 'string' ? b.day : null, opened: Math.max(0, n(b.opened)), closed: Math.max(0, n(b.closed)) }))
  if (points.length === 0) return base
  const opened = points.reduce((a, p) => a + p.opened, 0)
  const closed = points.reduce((a, p) => a + p.closed, 0)
  let peak = null
  for (const p of points) if (p.opened > 0 && (!peak || p.opened > peak.opened)) peak = p
  const max = points.reduce((a, p) => Math.max(a, p.opened, p.closed), 0)
  const net = opened - closed
  return {
    ...base, valid: true, range: TREND_RANGES.includes(data.range) ? data.range : null,
    bucket: data.bucket === 'day' ? 'day' : 'hour', points, opened, closed,
    critical: Math.max(0, n(data.totals?.critical)), silent: Math.max(0, n(data.totals?.closed_silent)),
    net, max, peak, from: typeof data.from === 'string' ? data.from : null,
    tone: opened === 0 ? 'success' : net > 0 ? 'warning' : 'info',
  }
}

/** Yatay eksen etiketi gösterilecek kova sıraları: günlükte hepsi, saatlikte 6 saatte bir + son kova (etiketler çarpışmasın). */
export function trendTickIndexes(count, bucket) {
  const out = []
  if (count <= 0) return out
  if (bucket === 'day' || count <= 8) { for (let i = 0; i < count; i++) out.push(i); return out }
  for (let i = 0; i < count; i += 6) out.push(i)
  if (out[out.length - 1] !== count - 1 && count - 1 - out[out.length - 1] >= 3) out.push(count - 1)
  return out
}

const IST = 'Europe/Istanbul'
const FORMATS = new Map()
function fmt(locale, key, opts) {
  const id = `${locale}|${key}`
  let f = FORMATS.get(id)
  if (!f) { f = new Intl.DateTimeFormat(locale, opts); FORMATS.set(id, f) }
  return f
}
const utcDate = (iso) => {
  if (typeof iso !== 'string' || !iso) return null
  const d = new Date(/[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`)
  return Number.isNaN(d.getTime()) ? null : d
}

/**
 * Kovanın eksen etiketi ve uzun adı (ipucu + ekran okuyucu listesi). Saatlik kova İstanbul saatiyle (kurumun takvimi — günlük
 * kovalar da İstanbul günü; tarayıcının saat dilimi ne olursa olsun iki görünüm aynı saati söyler); günlük kova sunucunun
 * `day` alanından (yyyy-MM-dd, saat dilimi dönüşümü YOK), alan yoksa kova başlangıcının İstanbul günü.
 */
export function trendPointLabels(point, bucket, locale = 'tr-TR') {
  if (!point) return { label: '', title: '' }
  if (bucket === 'day') {
    const d = point.day ? utcDate(`${point.day}T00:00:00`) : null
    if (d) {
      return {
        label: fmt(locale, 'd', { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(d),
        title: fmt(locale, 'dl', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' }).format(d),
      }
    }
    const at = utcDate(point.t)
    if (!at) return { label: '', title: '' }
    return {
      label: fmt(locale, 'di', { day: 'numeric', month: 'short', timeZone: IST }).format(at),
      title: fmt(locale, 'dli', { weekday: 'long', day: 'numeric', month: 'long', timeZone: IST }).format(at),
    }
  }
  const at = utcDate(point.t)
  if (!at) return { label: '', title: '' }
  return {
    label: fmt(locale, 'h', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: IST }).format(at),
    title: fmt(locale, 'hl', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: IST }).format(at),
  }
}

// ── Favori izlemelerin yanıt süresi ──────────────────────────────────────────────────────────────────────────────

/** Yanıt süresi ölçen türler — sunucu `MonitorSparklineService.KINDS` ile aynı (alan adı kaydı izlemesi süre ölçmez). */
export const FAV_CHART_TYPES = Object.freeze(['http', 'ping', 'port', 'dns', 'keyword', 'page', 'pagespeed', 'scripted', 'dbquery'])
export const FAV_HOURS = 24
/** Gösterilen satır sayısı (boyuta göre); TV'de kısa — duvar ekranı kaydırılamaz, kutu ~300 px'lik hücreye sığmalı. */
export const FAV_ROWS = Object.freeze({ sm: 3, md: 5, lg: 8 })
export const FAV_ROWS_TV = Object.freeze({ sm: 2, md: 3, lg: 5 })

/**
 * Favoriler → istek planı: tür başına kimlikler (yalnız süre ölçen türler; eklenme sırası korunur), süre ölçmeyenlerin sayısı
 * ve getirici kimliği için kararlı anahtar.
 */
export function favPlan(favorites) {
  const chartable = []
  const byType = {}
  let skipped = 0
  for (const f of Array.isArray(favorites) ? favorites : []) {
    const id = Number(f?.id)
    if (!f || !Number.isInteger(id) || id < 1) continue
    if (!FAV_CHART_TYPES.includes(f.type)) { skipped += 1; continue }
    chartable.push({ type: f.type, id, name: typeof f.name === 'string' && f.name.trim() ? f.name.trim() : null })
    ;(byType[f.type] || (byType[f.type] = [])).push(id)
  }
  const key = Object.keys(byType).sort().map((type) => `${type}:${byType[type].slice().sort((a, b) => a - b).join('.')}`).join('|')
  return { chartable, byType, skipped, key }
}

const HOUR_MS = 3_600_000
/** UTC saat anahtarı (`yyyy-MM-ddTHH`) — sunucunun kova anahtarıyla aynı biçim. */
export function hourKey(ms) { return new Date(ms).toISOString().slice(0, 13) }

/**
 * Sunucu kovaları (yalnız kontrolü olan saatler) → son `hours` saatin TAM yuva dizisi (eskiden yeniye). Kontrolü olmayan
 * saat ve ölçümü olmayan (hepsi hatalı) saat `ms: null` — çizgide BOŞLUK, asla 0.
 */
export function hourSlots(buckets, nowMs = Date.now(), hours = FAV_HOURS) {
  const by = new Map()
  for (const b of Array.isArray(buckets) ? buckets : []) if (b && typeof b.t === 'string') by.set(b.t.slice(0, 13), b)
  const last = Math.floor(nowMs / HOUR_MS) * HOUR_MS
  const out = []
  for (let i = hours - 1; i >= 0; i--) {
    const at = last - i * HOUR_MS
    const b = by.get(hourKey(at))
    out.push({ at, ms: b ? num(b.ms) : null, n: b ? n(b.n) : 0, fail: b ? n(b.fail) : 0 })
  }
  return out
}

/**
 * Plan + tür başına trend yanıtı → satırlar. Durum: `missing` (yanıtta yok — silinmiş ya da görme yetkisi yok), `nodata`
 * (son 24 saatte kontrol yok — duraklatılmış / yeni), `failing` (son kontrol hatalı), `ok`. Sıra: hata verenler önce, sonra
 * favoriye eklenme sırası.
 */
export function favRows(plan, byTypeData, nowMs = Date.now()) {
  const rows = (plan?.chartable || []).map((f, order) => {
    const src = byTypeData && typeof byTypeData === 'object' ? byTypeData[f.type] : null
    const d = src && typeof src === 'object' ? src[String(f.id)] : null
    const base = { key: `${f.type}:${f.id}`, type: f.type, id: f.id, name: f.name, order, lastMs: null, lastAt: null, avgMs: null,
      checks: 0, fail: 0, upPct: null, slots: [] }
    if (!d || typeof d !== 'object') return { ...base, state: 'missing' }
    const checks = n(d.n), fail = n(d.fail)
    if (checks <= 0) return { ...base, state: 'nodata' }
    const slots = hourSlots(d.buckets, nowMs)
    const last = Array.isArray(d.last) && d.last.length ? d.last[d.last.length - 1] : null
    // Son 5 kontrol yoksa (eski motor) son kovadan: o saatte hata varsa "hata veriyor"
    const lastBucket = Array.isArray(d.buckets) && d.buckets.length ? d.buckets[d.buckets.length - 1] : null
    const failing = last ? last.ok === false : !!lastBucket && n(lastBucket.fail) > 0 && n(lastBucket.fail) >= n(lastBucket.n)
    const measured = slots.filter((s) => s.ms != null)
    // Ortalama: ölçümü olan saatlerin kontrol sayısıyla ağırlıklı ortalaması
    const weight = measured.reduce((a, s) => a + Math.max(1, s.n - s.fail), 0)
    const avgMs = weight > 0 ? Math.round(measured.reduce((a, s) => a + s.ms * Math.max(1, s.n - s.fail), 0) / weight) : null
    const lastMs = last ? num(last.ms) : (measured.length ? measured[measured.length - 1].ms : null)
    return { ...base, state: failing ? 'failing' : 'ok', lastMs: failing ? null : lastMs, lastAt: last?.at || null, avgMs, checks, fail,
      upPct: num(d.up_pct), slots }
  })
  const rank = (r) => (r.state === 'failing' ? 0 : 1)
  return rows.sort((a, b) => rank(a) - rank(b) || a.order - b.order)
}

/**
 * Satırlar → kutu özeti. Ton TÜM favorilerden (yalnız gösterilenlerden değil): hata veren varsa kırmızı; hepsi ölçülmüş ve
 * yanıt veriyorsa yeşil; ölçülemeyen / bulunamayan varsa ya da hiç satır yoksa gri (bilinmeyen yeşil değildir).
 */
export function favSummary(rows, limit) {
  const list = Array.isArray(rows) ? rows : []
  const count = (s) => list.filter((r) => r.state === s).length
  const failing = count('failing'), ok = count('ok'), nodata = count('nodata'), missing = count('missing')
  const cap = Math.max(0, limit | 0)
  return {
    rows: list.slice(0, cap), more: Math.max(0, list.length - cap), total: list.length, failing, ok, nodata, missing,
    tone: failing > 0 ? 'danger' : list.length > 0 && ok === list.length ? 'success' : 'muted',
  }
}

// ── 7 günlük erişilebilirlik göstergesi ──────────────────────────────────────────────────────────────────────────

/** Hedefin bu kadar puandan fazla altı kırmızı; daha azı turuncu (tek yerde — kutu açıklaması da bunu söyler). */
export const AVAIL_DANGER_GAP = 1

/**
 * Gösterge ölçeği: yay `lo` … 100 arasını gösterir, hedef HER ZAMAN yayın %90'ındadır (lo = 100 − 10 × hata payı; hata payı
 * = 100 − hedef, en az 0,1). %99,9 hedefte yay %99 – %100; %95 hedefte %50 – %100. Değer `lo`nun altındaysa yay boş kalır —
 * sayı ve renk gerçeği söyler.
 */
export function gaugeScale(target) {
  const t = num(target)
  const budget = Math.max(0.1, 100 - (t == null ? 99.9 : Math.min(100, Math.max(0, t))))
  const lo = Math.max(0, 100 - 10 * budget)
  return { lo, hi: 100 }
}

/** Değerin yay üzerindeki payı (0 … 1); değer yoksa null. */
export function gaugeFraction(pct, target) {
  const v = num(pct)
  if (v == null) return null
  const { lo, hi } = gaugeScale(target)
  if (hi <= lo) return v >= hi ? 1 : 0
  return Math.min(1, Math.max(0, (v - lo) / (hi - lo)))
}

/**
 * Ölçülen değeri iki ondalığa AŞAĞI keser — İstatistikler sayfasının kuralının (`statsInsights.slaPct(v, { floor: true })`)
 * BİREBİR kopyası, kayan nokta payı dahil (99,86 × 100 = 9985,999…). `statsInsights.js` buraya ALINMAZ: istatistik modeli
 * sertifika tablosu modelini de çeker ve Panom parçasına binerdi; iki kural `seriesModel.test.js`'te aynı değerlerle sınanır.
 */
export function floorPct2(v) {
  const x = num(v)
  return x == null ? null : Math.floor(x * 100 + 1e-7) / 100
}

/**
 * Hedefin ALTINDAKİ bir değerin gösterimi (inceleme 2026-11-14): kesin orandan (kontrol / hata biliniyorsa) iki ondalığa aşağı
 * kesilir; kayan nokta payı yüzünden hedefe eşit çıkarsa bir adım (0,01) aşağı alınır — hedefin altındaki değer ASLA hedefte
 * ya da üstünde yazılmaz (sunucunun iki ondalıklı yukarı yuvarlaması %99,8955'i "99,90" yazıyordu).
 */
export function belowTargetPct(exact, target) {
  let f = floorPct2(exact)
  const t = num(target)
  if (f == null) return null
  if (t != null && f >= t) f = Math.floor(t * 100 - 1e-7) / 100
  return f
}

/** Kesin oran (yüzde): başarılı ÷ kontrol × 100; kontrol yoksa null. */
export function exactPct(checks, failed) {
  const c = n(checks)
  if (c <= 0) return null
  return ((c - Math.min(Math.max(0, n(failed)), c)) * 100) / c
}

/**
 * `/api/monitoring/availability-week` yanıtı → ekran özeti. Hüküm KESİN orandan (inceleme 2026-11-14): sunucunun
 * `meets_target`'ı (artık kesin oranla) VE kontrol / hata sayısından hesaplanan oran — HERHANGİ biri "altında" diyorsa
 * altındadır (yeşil yalnız ikisi de "hedefte" iken); ikisi de yoksa gösterim değeri. Hedefin altındaki değer gösterimde aşağı kesilir ({@link belowTargetPct}) — "99,90 · hedef 99,9" ile
 * turuncu ton yan yana gelmez. Ton: ölçüm / hedef yok → gri; hedefte → yeşil; hedefin en çok {@link AVAIL_DANGER_GAP} puan
 * altı → turuncu; daha düşük → kırmızı.
 */
export function availabilitySummary(data) {
  const base = { hidden: false, unsupported: false, valid: false, pct: null, target: null, meets: null, days: 7, monitors: 0,
    unmeasured: 0, belowTarget: 0, checks: 0, failed: 0, worst: [], byType: [], fraction: null, targetFraction: null, tone: 'muted' }
  if (!data || typeof data !== 'object') return base
  if (data.unsupported === true) return { ...base, unsupported: true }
  if (data.visible === false) return { ...base, hidden: true }
  if (!('availability_pct' in data) || !('target_pct' in data)) return base
  const shown = num(data.availability_pct), target = num(data.target_pct)
  const exact = exactPct(data.checks, data.failed)
  const serverMeets = typeof data.meets_target === 'boolean' ? data.meets_target : null
  const exactMeets = exact != null && target != null ? exact >= target : null
  const meets = shown == null || target == null ? null
    : serverMeets === false || exactMeets === false ? false
    : serverMeets ?? exactMeets ?? shown >= target
  const value = exact ?? shown   // tonu belirleyen değer (kesin)
  const pct = shown == null || meets !== false ? shown : belowTargetPct(value, target)
  const worst = (Array.isArray(data.worst) ? data.worst : []).filter((w) => w && typeof w === 'object' && w.id != null && typeof w.type === 'string')
    .map((w) => {
      const wExact = exactPct(w.checks, w.failed)
      const wShown = num(w.up_pct)
      const wValue = wExact ?? wShown
      const wBelow = target != null && wValue != null && wValue < target
      return { type: w.type, id: w.id, name: typeof w.name === 'string' && w.name.trim() ? w.name : null,
        upPct: wBelow ? belowTargetPct(wValue, target) : wShown, exact: wValue,
        checks: n(w.checks), failed: n(w.failed), badHours: n(w.bad_hours) }
    })
  const byType = (Array.isArray(data.by_type) ? data.by_type : []).filter((x) => x && typeof x.type === 'string')
    .map((x) => ({ type: x.type, monitors: n(x.monitors), pct: num(x.availability_pct), belowTarget: n(x.below_target) }))
  const scale = gaugeScale(target)
  return {
    ...base, valid: true, pct, target, meets, days: n(data.days) || 7, monitors: n(data.monitors),
    // null = sunucu bilmiyor (aktif envanter okunamadı) — 0 ile karıştırılmaz
    unmeasured: num(data.unmeasured),
    belowTarget: n(data.below_target), checks: n(data.checks), failed: n(data.failed), worst, byType,
    fraction: gaugeFraction(pct, target),
    targetFraction: target == null ? null : Math.min(1, Math.max(0, (target - scale.lo) / (scale.hi - scale.lo))),
    tone: shown == null || target == null ? 'muted' : meets ? 'success' : value >= target - AVAIL_DANGER_GAP ? 'warning' : 'danger',
  }
}
