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
