/**
 * URL alan izleme formları (Sayfa Bütünlüğü / HTTP / Kelime) için giriş normalizasyonu.
 *
 * Şemasız bir adres ("www.axess.com.tr") kontrol edilemez — URI ayrıştırıcısı bunu göreli bir
 * referans sayar, host çıkmaz — ve eskiden sessizce sahte "kesinti" alarmı üretiyordu. Alan
 * terk edildiğinde (onBlur) kullanıcıya ne kaydedileceği gösterilsin diye burada düzeltiyoruz;
 * backend (MonitorUrls.normalize) yine otoritedir ve aynı kuralları uygular.
 *
 * Mevcut şema ASLA değiştirilmez: "http://" yazan kullanıcı https'e taşınmaz (iç servis 443'te olmayabilir).
 */
export function normalizeUrl(raw) {
  const t = (raw ?? '').trim()
  if (!t) return ''
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(t)) return t   // şema var → dokunma
  if (t.startsWith('//')) return `https:${t}`        // protokol-relatif
  return `https://${t}`
}

export default normalizeUrl
