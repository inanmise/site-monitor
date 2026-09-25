/**
 * Genel Bakış (pano) PLATFORM süzgeci — saf yardımcılar (2026-09-25, kullanıcı isteği: "Dashboard sayfasına
 * platform bazlı filtrelemeler ekleyelim").
 *
 * Platform = envanter kaydının `platform` kodu (Ayarlar → Platformlar kataloğu: IIS, OPENSHIFT, KUBERNETES…);
 * /api/certificates her kartta `platform` + `platform_name` (katalog adı) + `platform_detail` döndürür (tel biçimi
 * snake_case). Envanterde girilmemişse `platform` null → süzgeçte "Belirtilmemiş" ({@link PLATFORM_NONE}).
 *
 * Çoklu seçim VEYA ile çalışır (IIS + OpenShift = ikisinden biri); pano boru hattındaki diğer süzgeçlerle VE.
 * URL'de virgülle ayrılmış kod listesi olarak yaşar (`?platform=IIS,__none__`); kodlar A-Z0-9_ slug olduğundan
 * (PlatformService.normalize) virgül çakışmaz.
 */

/** "Belirtilmemiş" seçeneğinin değeri — App'in takım/grup süzgeçlerindeki `__none__` sözleşmesiyle aynı. */
export const PLATFORM_NONE = '__none__'

/**
 * Panonun URL anahtarı. `tab`/`domain`/`monitor`/`incident` uygulamanındır; önekli aileler (a_, r_, d_, i_ …)
 * başka sayfaların. `platform` hiçbir sayfada kullanılmıyor — PAGE_STATE_PARAMS'a kayıtlı (sekme değişince silinir).
 */
export const PLATFORM_URL_KEY = 'platform'

/** URL değeri → tekil, boş olmayan kod listesi (sıra korunur). Bozuk/boş değer → []. */
export function parsePlatformParam(raw) {
  if (raw == null) return []
  const out = []
  for (const part of String(raw).split(',')) {
    const v = part.trim()
    if (v && !out.includes(v)) out.push(v)
  }
  return out
}

/** Seçim → URL değeri; boş seçim → null (useUrlQuerySync paramı siler, URL temiz kalır). */
export function serializePlatformParam(selected) {
  return Array.isArray(selected) && selected.length > 0 ? selected.join(',') : null
}

/** Kartın süzgeç anahtarı: platform kodu, yoksa (null/boş) {@link PLATFORM_NONE}. */
export function platformKeyOf(cert) {
  const p = cert?.platform
  return typeof p === 'string' && p.trim() !== '' ? p : PLATFORM_NONE
}

/** Seçim boşsa her kart geçer; doluysa kartın anahtarı seçimde olmalı (VEYA). */
export function matchesPlatform(cert, selected) {
  if (!Array.isArray(selected) || selected.length === 0) return true
  return selected.includes(platformKeyOf(cert))
}

/** Anahtar → kart sayısı. Panoda PLATFORM DIŞINDAKİ süzgeçlerden geçmiş kartlarla çağrılır (faset sayısı). */
export function countPlatforms(certs) {
  const m = new Map()
  for (const c of certs || []) {
    const k = platformKeyOf(c)
    m.set(k, (m.get(k) || 0) + 1)
  }
  return m
}

/**
 * Süzgeç seçenekleri: {value, label, count, hint?}.
 *
 * Sıra: (1) katalogdaki AKTİF platformlar, sunucunun sırasıyla (sort_order, ad); (2) veride olup aktif katalogda
 * olmayan kodlar (pasife alınmış ya da içe aktarmayla gelmiş) ada göre; (3) URL'den gelip hiçbirinde olmayan seçili
 * kodlar — seçim görünür ve kaldırılabilir kalsın; (4) en sonda "Belirtilmemiş".
 * Etiket: katalog adı → kartın `platform_name`'i → kodun kendisi (InventoryTable `platformNames[c] || c` deseni).
 * Sayı `counts`'tan (yoksa 0) — seçenek listesi tüm veriden türer, sayı o anki süzgeç durumundan.
 */
export function buildPlatformOptions({ catalog = [], certs = [], counts = new Map(), selected = [], noneLabel = PLATFORM_NONE } = {}) {
  const opts = []
  const seen = new Set()
  const push = (value, label, hint) => {
    if (seen.has(value)) return
    seen.add(value)
    opts.push({ value, label: label || value, count: counts.get(value) || 0, ...(hint ? { hint } : {}) })
  }
  for (const p of Array.isArray(catalog) ? catalog : []) {
    if (p?.code) push(p.code, p.name, p.description || undefined)
  }
  const dataOnly = new Map()
  for (const c of certs || []) {
    const k = platformKeyOf(c)
    if (k !== PLATFORM_NONE && !seen.has(k) && !dataOnly.has(k)) dataOnly.set(k, c.platform_name || k)
  }
  ;[...dataOnly.entries()].sort((a, b) => a[1].localeCompare(b[1])).forEach(([code, name]) => push(code, name))
  for (const s of selected || []) if (s !== PLATFORM_NONE) push(s, s)
  push(PLATFORM_NONE, noneLabel)
  return opts
}
