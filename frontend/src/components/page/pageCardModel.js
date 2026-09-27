/**
 * Sayfa Bütünlüğü izleme KARTI modeli — saf yardımcılar (React yok; kart ve testleri paylaşır).
 *
 * <p>Satır alanları (GET /monitoring/page, `enrichPage`): `status` OK|DEGRADED|DOWN|CONFIG_ERROR|unknown,
 * `total_resources` (sayfada/taramada bulunan kaynak), `broken_resources` (kırık), `timeout_count` (zaman aşımı —
 * null: 2026-08-04 öncesi kayıt, o zaman kırığa dahildi), `mixed_content_count` (https sayfada http kaynak — YÜKLENMİŞ ama
 * güvensiz, kırık sayılmaz), `pages_crawled` (site taramasında gezilen sayfa), `http_status` + `response_ms` (sayfanın
 * kendisi), `error` (sunucu metni), yapılandırma: `mode` SINGLE_PAGE|SITE_CRAWL, `crawl_depth`, `crawl_max_pages`,
 * `exclude_patterns`, `alert_third_party` / `alert_mixed_content` / `alert_timeout`, `timeout_ms`.
 *
 * <p>Listede OLMAYANLAR (kart uydurmaz): yavaş (SLOW) ve belirsiz (BLOCKED) kaynak sayıları (yalnız sorun listesinde,
 * detay penceresinin Sorunlar sekmesinde), içerik parmak izi / temel çizgi yaşı (`content_hash` yanıta konmuyor).
 */

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * URL → başlık parçaları: şema yalnız https DEĞİLSE gösterilir (düz http bir bulgudur), host vurgulu, yol + sorgu
 * soluk (kök "/" yazılmaz). Ayrıştırılamayan metin (ör. şemasız "intranet" — yapılandırma hatası) olduğu gibi host'a düşer.
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

/**
 * Bütünlük sonucu — kartın kalbi. null: hiç kontrol edilmemiş (kaynak sayısı yok).
 * `problems` = kırık + zaman aşımı (yüklenemeyen kaynak); `healthy` = toplam − problems. Mixed content AYRI sayılır
 * (kaynak yüklendi ama güvensiz) — sağlıklı oranını düşürmez, kendi çipiyle görünür.
 * Ton: kırık → crit · yalnız zaman aşımı / mixed → warn · sorunsuz → ok · hiç kaynak yok → none.
 *
 * @returns {{total:number, broken:number, timeouts:number, mixed:number, problems:number, healthy:number,
 *            pages:number|null, tone:'ok'|'warn'|'crit'|'none'} | null}
 */
export function integrityResult(m) {
  const total = num(m?.total_resources)
  if (total == null) return null
  const broken = Math.max(0, num(m?.broken_resources) ?? 0)
  const timeouts = Math.max(0, num(m?.timeout_count) ?? 0)
  const mixed = Math.max(0, num(m?.mixed_content_count) ?? 0)
  const problems = broken + timeouts
  const healthy = Math.max(0, total - problems)
  const tone = broken > 0 ? 'crit' : timeouts > 0 || mixed > 0 ? 'warn' : total > 0 ? 'ok' : 'none'
  const pages = m?.mode === 'SITE_CRAWL' ? num(m?.pages_crawled) : null
  return { total, broken, timeouts, mixed, problems, healthy, pages, tone }
}

/** HTTP durum kodu tonu: 2xx ok · 3xx nötr (yönlendirme izlendi) · 4xx/5xx bad · yok nötr. */
export function httpTone(code) {
  const c = num(code)
  if (c == null) return 'neutral'
  if (c >= 400) return 'bad'
  if (c >= 200 && c < 300) return 'ok'
  return 'neutral'
}

/**
 * Sayfanın KENDİSİ taranamadıysa nedeni. Sunucu kuralı (PageCheckerService): DOWN yalnız ana sayfa alınamayınca
 * (engellendi / gövde yok / HTTP ≥ 400 / bağlantı hatası) — kaynak sorunları DEGRADED'dır ve çiplerle anlatılır.
 * null: sayfa tarandı (ya da hiç kontrol edilmedi).
 * - `config`: CONFIG_ERROR (URL'de host yok vb.) — kesinti değil, alarm üretmez; mor ton.
 * - `down`: ana sayfa alınamadı; `detail` sunucu metninin ilk satırı, yoksa HTTP kodu.
 */
export function pageFailure(m) {
  const s = m?.status
  const err = String(m?.error ?? '').split(/\r?\n/).map((x) => x.trim()).filter(Boolean)[0] || null
  // Sunucunun KENDİ ürettiği sabit Türkçe metinler arayüz diline çevrilir (MonitorUrls.CONFIG_ERROR_MSG,
  // PageCheckerService "ana sayfa HTTP n" / "ana sayfa alınamadı"); ağ katmanının ham metni (ör. "Connection refused")
  // olduğu gibi gösterilir. `detailKey` → çağıran t() ile yazar.
  if (s === 'CONFIG_ERROR') {
    return /^yapılandırma hatası: URL'de geçerli bir host yok/.test(err || '')
      ? { kind: 'config', detail: null, detailKey: 'page.card.configNoHost' }
      : { kind: 'config', detail: err }
  }
  if (s !== 'DOWN') return null
  const code = num(m?.http_status)
  const serverHttp = /^ana sayfa HTTP (\d{3})$/.exec(err || '')
  if (serverHttp) return { kind: 'down', detail: `HTTP ${serverHttp[1]}` }
  if (!err || err === 'ana sayfa alınamadı') return { kind: 'down', detail: code != null ? `HTTP ${code}` : null }
  return { kind: 'down', detail: err }
}

/** Hariç tutma desenlerinin sayısı (satır başına bir desen; boş satırlar sayılmaz). */
export function exclusionCount(patterns) {
  return String(patterns || '').split(/\r?\n/).map((s) => s.trim()).filter(Boolean).length
}

/**
 * Süre → okunur parçalar: 1 sn altı tam milisaniye ("386" + "ms"), üstü tek ondalıklı saniye ("1.7" + "s").
 */
export function humanizeMs(ms) {
  const n = num(ms)
  if (n == null || n < 0) return null
  const r = Math.round(n)
  if (r < 1000) return { num: String(r), unit: 'ms' }
  return { num: (r / 1000).toFixed(1).replace(/\.0$/, ''), unit: 's' }
}
