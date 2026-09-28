/**
 * Bir monitörün PAYLAŞILABİLİR mutlak derin bağlantısı.
 *
 * Kanonik biçim `?tab=<sekme>&monitor=<id>` — useUrlQuerySync'in PAGE_STATE_PARAMS'ıyla ve
 * uygulamanın derin-link okuyucusuyla AYNI sözleşme. Bu biçim daha önce IncidentsPage içinde
 * satır içinde kuruluyordu; kart düğmeleri üçüncü bir kopyayı doğuracaktı, o yüzden ortak.
 *
 * <p><b>Filtre/arama/sayfa parametreleri BİLEREK atılır.</b> Paylaşılan bağlantı "şu monitör"
 * demektir, "benim o anki filtrelerim" değil: adres çubuğunu olduğu gibi kopyalamak, linki
 * alan kişiye çoğu zaman BOŞ bir liste açtırırdı (ör. gönderen "yalnız hatalılar" filtresini
 * açık bırakmışsa).
 *
 * <p>Mutlak URL üretilir çünkü bu metin Slack/e-posta'ya yapıştırılıyor; göreli `?tab=…`
 * oraya düştüğünde tıklanabilir olmaz.
 */

/** `window.location` yoksa (jsdom kenar durumu) göreli biçime düşer — asla patlamaz. */
function base() {
  try {
    if (typeof window !== 'undefined' && window.location) {
      return `${window.location.origin}${window.location.pathname}`
    }
  } catch { /* jsdom/SSR — göreli biçim yeterli */ }
  return ''
}

/** Id ile anahtarlanan izleme türleri (http, dns, port, ping, keyword, page, domain, scripted). */
export function monitorDeepLink(tab, monitorId) {
  if (!tab || monitorId == null) return null
  return `${base()}?tab=${encodeURIComponent(tab)}&monitor=${encodeURIComponent(monitorId)}`
}

/**
 * Domain ile anahtarlanan yüzeyler (Uptime kartı ve sertifika sayfaları) — bunların
 * monitör id'si yok, derin bağlantı alan adı üzerinden kurulur.
 */
export function domainDeepLink(tab, domain) {
  if (!tab || !domain) return null
  return `${base()}?tab=${encodeURIComponent(tab)}&domain=${encodeURIComponent(domain)}`
}

/**
 * Tek seferlik "vardığında aç" eylemi — uygulama düzeyi `open` parametresi (2026-09-28, 7/24 Kapsamı → izleme).
 *
 * <p>`monitor`/`domain` NEYİ gösterdiğini söyler, `open` varışta NE AÇILACAĞINI:
 * <ul>
 *   <li>`cert` — Pano (`tab=dashboard&domain=<d>`): alanın sertifika penceresi (yalnız `domain` eskisi gibi süzer).</li>
 *   <li>`noc`  — izleme sayfası (`monitor=<id>`) ya da Pano (`domain=<d>`, SSL envanteri): DÜZENLEME formu, "7/24 izleme
 *       ekibine bildir" alanı görünüme kaydırılmış ve odaklı. Düzenleme yetkisi yoksa detay/sertifika penceresi açılır.</li>
 * </ul>
 * Tüketilince adresten SİLİNİR (olaylardaki `action` gibi): Geri/yenile pencereyi ya da formu yeniden açmasın.
 * Sekme değişince de silinir (PAGE_STATE_PARAMS). Okuyucular: hooks/useMonitorDeepLink, hooks/useCertDeepLink.
 */
export const DEEP_OPEN_PARAM = 'open'
export const DEEP_OPEN = Object.freeze({ CERT: 'cert', NOC: 'noc' })

/**
 * Genel mutlak derin bağlantı: `?tab=<sekme>&<anahtar>=<değer>…` — boş/null değerler yazılmaz, sıra korunur.
 * `monitorDeepLink(tab, id)` ile `tabDeepLink(tab, { monitor: id })` AYNI metni üretir.
 */
export function tabDeepLink(tab, params) {
  if (!tab) return null
  const parts = [`tab=${encodeURIComponent(tab)}`]
  for (const [k, v] of Object.entries(params || {})) {
    if (v == null || v === '') continue
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
  }
  return `${base()}?${parts.join('&')}`
}

/** SSL sertifikası: Pano'da alanın sertifika penceresini açan bağlantı (`action` = DEEP_OPEN değeri). */
export function certDeepLink(domain, action = DEEP_OPEN.CERT) {
  if (!domain) return null
  return tabDeepLink('dashboard', { domain, [DEEP_OPEN_PARAM]: action })
}
