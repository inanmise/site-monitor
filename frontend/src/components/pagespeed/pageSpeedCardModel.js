import { formatBytes } from '../../utils/formatBytes.js'

/**
 * Sayfa Hızı kartının SAF modeli (2026-09-27 kart yeniden tasarımı) — bütçe ölçerlerinin değer, bütçe, oran ve tonu
 * burada hesaplanır; bileşen (PageSpeedMonitorCard) yalnız çizer. Saf olduğu için birim testiyle tam kapsanır.
 *
 * <p>Bütçe = izlemenin dört eşiği (`max_load_ms`, `max_ttfb_ms`, `max_page_kb`, `max_requests`). Sunucu kuralıyla
 * birebir (PageSpeedRules.evaluate): eşik `null` ya da `<= 0` ise o metrik DEĞERLENDİRİLMEZ (0 "sınırsız" demektir),
 * karşılaştırma KESİN büyüktür (tam eşik ihlal sayılmaz), boyut eşiği KB girilir → bayt = KB × 1024.
 */

/** Dört ölçü — sıra kartta soldan sağa / yukarıdan aşağıya. `breach` sunucunun `breached_metrics` anahtarı. */
export const PSPD_METRICS = [
  { key: 'load', breach: 'LOAD', field: 'response_ms', labelKey: 'pspd.mLoad' },
  { key: 'ttfb', breach: 'TTFB', field: 'ttfb_ms', labelKey: 'pspd.mTtfb' },
  { key: 'size', breach: 'SIZE', field: 'total_bytes', labelKey: 'pspd.mSize' },
  { key: 'requests', breach: 'REQUESTS', field: 'request_count', labelKey: 'pspd.mRequests' },
]

/** Bütçenin bu oranına kadar "rahat" (yeşil); üstü ve bütçeye eşit olan "sınırda" (amber); aşan "aşıldı" (kırmızı). */
export const NEAR_BUDGET = 0.8

const positive = (v) => {
  const n = Number(v)
  return v != null && v !== '' && Number.isFinite(n) && n > 0 ? n : null
}

/**
 * Metriğin bütçesi ölçümle AYNI birimde (ms / bayt / istek) ya da yoksa `null`. Grafik bütçe çizgisi de bunu kullanır
 * (eskiden sayfadaki `budgetFor` 0 eşiği de çizgi olarak basıyordu; 0 = eşik yok).
 */
export function budgetFor(m, key) {
  if (!m) return null
  if (key === 'load') return positive(m.max_load_ms)
  if (key === 'ttfb') return positive(m.max_ttfb_ms)
  if (key === 'size') { const kb = positive(m.max_page_kb); return kb == null ? null : kb * 1024 }
  if (key === 'requests') return positive(m.max_requests)
  return null
}

/** Ölçülen değer (sayı) ya da ölçülmediyse `null` — 0 geçerli bir ölçümdür. */
export function measuredFor(m, key) {
  const metric = PSPD_METRICS.find((x) => x.key === key)
  const v = metric && m ? m[metric.field] : null
  const n = Number(v)
  return v == null || v === '' || !Number.isFinite(n) ? null : n
}

/**
 * Süre → okunur parçalar: 1 sn altı tam milisaniye ("340" + "ms"), üstü tek ondalıklı saniye ("1.8" + "s").
 * Ondalık ayırıcı nokta — kartın boyut değeri (formatBytes, "2.0 MB") ve detay penceresiyle aynı dil.
 */
export function humanizeMs(ms) {
  const n = Number(ms)
  if (ms == null || ms === '' || !Number.isFinite(n)) return null
  const r = Math.round(n)   // önce yuvarla: 999.6 ms "1000 ms" değil "1.0 s" okunur
  if (r < 1000) return { num: String(r), unit: 'ms' }
  return { num: (r / 1000).toFixed(1), unit: 's' }
}

/**
 * Bayt → okunur parçalar — biçimin TEK kaynağı formatBytes (1024 tabanı, kırpılmışsa "≥ " öneki); burada yalnız sayı
 * ile birim ayrılır ki kart birimi küçük ve soluk yazabilsin.
 */
export function humanizeBytes(bytes, truncated = false) {
  const s = formatBytes(bytes, truncated)
  const m = /^(≥ )?(\S+) (\S+)$/.exec(s)
  if (!m) return null
  return { prefix: m[1] ? '≥' : '', num: m[2], unit: m[3] }
}

/** value / budget; bütçe ya da değer yoksa `null`. */
export function budgetRatio(value, budget) {
  if (value == null || budget == null || !(budget > 0)) return null
  return value / budget
}

/**
 * Ölçer tonu: `crit` (bütçe aşıldı) · `warn` (sınırda) · `ok` (rahat) · `null` (bütçe ya da ölçüm yok).
 *
 * <p>Son ölçümde sunucu metriği İHLAL saydıysa (`breached`) ton her zaman `crit` — durum rozeti de "Eşik aşıldı" diyor;
 * eşik sonradan gevşetildiyse bile karar bir sonraki ölçüme kadar geçerli.
 *
 * <p>TTFB istisnası KALDIRILDI (2026-11-14, Sayfa Hızı 1. sürüm): sunucu TTFB eşiğini artık kartta görünen değerin TA
 * KENDİSİNE — kontrolün gerçek isteğinin ilk baytına — karşı değerlendirir; eski "sunucu bekleme süresi" kuralı gitti.
 * Çağıranların geçtiği `key` artık okunmaz (imza geriye dönük uyumlu).
 */
export function meterTone({ ratio, breached = false, lowerBound = false } = {}) {
  if (breached) return 'crit'
  if (ratio == null) return null
  // Alt sınır (kısmi ölçüm) bütçenin altındaysa gerçek değer bütçeyi aşmış olabilir — "rahat" / "sınırda" denmez, nötr
  // kalır (inceleme B1; sunucu bu eşiği KARARSIZ sayar). Bütçeyi aşan alt sınır ise kanıttır.
  if (lowerBound && ratio <= 1) return null
  if (ratio > 1) return 'crit'
  if (ratio > NEAR_BUDGET) return 'warn'
  return 'ok'
}

/**
 * Bir kartın dört ölçeri — bileşenin çizdiği her şey (değer parçaları, bütçe, oran, ton, alt sınır bayrağı).
 * `lowerBound`: değer gerçek değerin ALT SINIRI (boyut okuma tavanında kesildi / istek tavanına ulaşıldı / ölçüm süre
 * tavanına takıldı — `time_truncated`, 2026-11-14: yükleme süresi ve boyut tartılamayan kaynaklar kadar eksik).
 */
export function metersFor(m) {
  const breached = new Set(Array.isArray(m?.breached_metrics) ? m.breached_metrics.map((k) => String(k).trim().toUpperCase()) : [])
  return PSPD_METRICS.map((metric) => {
    const value = measuredFor(m, metric.key)
    const budget = budgetFor(m, metric.key)
    const ratio = budgetRatio(value, budget)
    // Asgari boyut ihlali (MINSIZE) da boyut ölçerinde görünür (inceleme F2) — eskiden azami boyut yoksa ölçer yeşildi.
    const isBreached = breached.has(metric.breach) || (metric.key === 'size' && breached.has('MINSIZE'))
    const timeCut = m?.time_truncated === true
    const lowerBound = metric.key === 'size' ? (!!m?.bytes_truncated || timeCut)
      : metric.key === 'requests' ? !!m?.capped
      : metric.key === 'load' ? timeCut : false
    return {
      ...metric,
      value, budget, ratio, breached: isBreached, lowerBound,
      // Alt sınırın NEDENİ süre tavanı mı (açıklama metni ona göre seçilir)
      timeCut: lowerBound && timeCut && (metric.key === 'load' || (metric.key === 'size' && !m?.bytes_truncated)),
      tone: meterTone({ key: metric.key, ratio, breached: isBreached, lowerBound }),
      display: displayParts(metric.key, value, lowerBound),
      budgetDisplay: budget == null ? null : displayParts(metric.key, budget, false),
      percent: ratio == null ? null : Math.round(ratio * 100),
    }
  })
}

/**
 * Kompakt kartın NEDEN satırı (2026-09-27, kart yoğunluğu): bütçeyi AŞAN ölçerler — ana ölçü (`exclude`, yükleme)
 * hariç; onu kompakt kartın ana satırı zaten kırmızı gösterir. Ton kuralı ölçerlerle birebir (`meterTone`): sunucu
 * ihlali her zaman sayılır, bütçeyi geçen değer de (TTFB dahil — 2026-11-14'ten beri sunucu aynı değere bakar).
 */
export function overBudgetMeters(meters, exclude = 'load') {
  return (Array.isArray(meters) ? meters : []).filter((mt) => mt.key !== exclude && mt.tone === 'crit')
}

/**
 * Ölçer DIŞI ihlaller (inceleme F2): tür bütçeleri (JS / görsel / font / üçüncü taraf) ve asgari boyut — dört ölçerde
 * görünmedikleri için kart ve kompakt neden satırı bunları ayrıca adlandırır. Sıra sunucunun BREACH_KEYS sırası.
 */
export const EXTRA_BREACH_KEYS = ['MINSIZE', 'JS', 'IMG', 'FONT', 'THIRD', 'GROWSIZE', 'GROWREQ', 'SIZEBAND']
export function extraBreaches(m) {
  const b = new Set(Array.isArray(m?.breached_metrics) ? m.breached_metrics.map((k) => String(k).trim().toUpperCase()) : [])
  return EXTRA_BREACH_KEYS.filter((k) => b.has(k))
}

/** Değer → { prefix, num, unit } (istek sayısında birim çağıranın i18n'inden gelir: `unit: null`). */
export function displayParts(key, value, lowerBound = false) {
  if (value == null) return null
  if (key === 'size') return humanizeBytes(value, lowerBound)
  const prefix = lowerBound ? '≥' : ''
  if (key === 'requests') return { prefix, num: String(Math.round(value)), unit: null }
  const p = humanizeMs(value)
  return p && { prefix, ...p }
}

/** Parçaları düz metne çevirir ("≥ 11.8 MB", "1.8 s", "84 req") — başlık/ipucu/ekran okuyucu metni için. */
export function partsText(parts, reqUnit = '') {
  if (!parts) return '—'
  const unit = parts.unit ?? reqUnit
  return [parts.prefix, parts.num, unit].filter(Boolean).join(' ')
}

/**
 * Haftalık eşik üstü karşılaştırması — 7 ve 14 günlük SLA pencerelerinden: bu hafta = 7 günün sayısı, geçen hafta
 * = 14 gün − 7 gün. Sayılan alan: sunucu `breach` gönderiyorsa (2026-11-14) YÜKLENEBİLEN ölçümlerden eşiği aşanlar;
 * göndermiyorsa (eski sunucu) `fail` = eşik aşımı YA DA hata. 14 günlük pencerede hiç kontrol yoksa karşılaştırma yok.
 */
export function breachWeek(w7, w14) {
  if (!w7 || !w14 || !w14.n) return null
  const field = w7.breach != null && w14.breach != null ? 'breach' : 'fail'
  const thisWeek = Math.max(0, Number(w7[field]) || 0)
  const lastWeek = Math.max(0, (Number(w14[field]) || 0) - thisWeek)
  const delta = thisWeek - lastWeek
  return { thisWeek, lastWeek, delta, trend: delta > 0 ? 'worse' : delta < 0 ? 'better' : 'same' }
}

/**
 * Bütçe uyumu (2026-11-14, madde 5): kartın erişilebilirliği artık "sayfa yüklenebildi mi"; eşik içinde kalma ayrı bir
 * yüzde. `sla.budget_pct` (sunucu: yüklenebilen ölçümlerden tüm eşikler içinde kalanların yüzdesi) yoksa `null` — eski
 * sunucu ya da hiç yüklenen ölçüm yok. Bilinmeyen değer ASLA 100 sayılmaz.
 *
 * @returns {{ pct: number|null, loaded: number, days: number } | null}
 */
export function budgetCompliance(sla, days = 30, budgetDefined) {
  if (!sla || typeof sla !== 'object' || !('budget_pct' in sla)) return null
  const loaded = Math.max(0, (Number(sla.n) || 0) - (Number(sla.fail) || 0))
  // Hiç eşik / bütçe tanımlı değilse uyum anlamsızdır (inceleme F7): "%100" yeşil değil, "bütçe tanımlı değil" (nötr).
  // Kaynak: izleme satırının `budget_defined`'ı ya da sunucunun `sla.budget_defined`'ı; ikisi de yoksa eski davranış.
  const noBudget = budgetDefined === false || sla.budget_defined === false
  const pct = noBudget || sla.budget_pct == null || !Number.isFinite(Number(sla.budget_pct)) ? null : Number(sla.budget_pct)
  return { pct, loaded, days, noBudget }
}

/** Bütçe uyumu tonu: tüm ölçümler eşik içinde → ok; ≥ %95 → warn; altı → crit; bilinmiyor → none. */
export function complianceTone(pct) {
  if (pct == null) return 'none'
  if (pct >= 100) return 'ok'
  if (pct >= 95) return 'warn'
  return 'crit'
}

/**
 * URL → başlık parçaları: şema yalnız https DEĞİLSE gösterilir (düz http bir bulgudur), host vurgulu, yol + sorgu
 * soluk. Ayrıştırılamayan metin olduğu gibi host'a düşer (uydurma parça yok).
 */
export function urlParts(url) {
  const raw = String(url ?? '')
  try {
    const u = new URL(raw)
    const path = `${u.pathname === '/' ? '' : u.pathname}${u.search}${u.hash}`
    return { scheme: u.protocol === 'https:' ? '' : `${u.protocol}//`, host: u.host, path }
  } catch {
    return { scheme: '', host: raw, path: '' }
  }
}
