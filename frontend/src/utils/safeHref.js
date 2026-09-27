/**
 * Dinamik bağlantılar için ŞEMA BEYAZ LİSTESİ (2026-09-27 regresyon BD1 kardeşleri: yenileme kılavuzu bağlantı kartı,
 * duyuru şeridi bağlantısı). Yönetici girdisi bir adres `javascript:` / `data:` / `vbscript:` / `blob:` … şemasıyla
 * href'e YAZILMAZ — çağıran onu düz metin çizer (saklanan-XSS savunma derinliği). Kardeş güvenli desen:
 * `weekly/weeklyLinks.js` (yalnız http/https).
 *
 * <p>Ayrıştırma tarayıcının kendi URL ayrıştırıcısıyla yapılır: `href` yazarken tarayıcı sekme/satır sonunu siler ve
 * baştaki boşlukları atar (`"java\nscript:…"` → `javascript:`); aynı ayrıştırıcıyla bakmak bu hileyi de yakalar.
 * Göreli adres (`/x`, `?tab=…`, `//host/x`) sayfanın şemasını alır — güvenli sayılır.
 *
 * @param {string} raw                  ham adres
 * @param {string[]} [schemes]          izin verilen şemalar (küçük harf, iki noktalı)
 * @returns {string|null}               güvenli href (kırpılmış ham değer) ya da null (tıklanamaz — düz metin çiz)
 */
export const SAFE_LINK_SCHEMES = ['http:', 'https:', 'mailto:']

const RELATIVE_BASE = 'https://relative.invalid/'

export function safeHref(raw, schemes = SAFE_LINK_SCHEMES) {
  const s = String(raw ?? '').trim()
  if (!s) return null
  let u
  try { u = new URL(s, RELATIVE_BASE) } catch { return null }
  return schemes.includes(u.protocol.toLowerCase()) ? s : null
}
