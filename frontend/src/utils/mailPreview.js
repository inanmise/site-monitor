// E-posta HTML'i bir <iframe srcDoc> içinde önizlenirken, mail'deki linkler (ör. "Site Monitor'de Görüntüle"
// CTA'sı) tıklanınca iframe'in KENDİSİNİ hedefe götürür. Hedef uygulama URL'i ise X-Frame-Options: DENY +
// CSP frame-ancestors 'none' döndürdüğünden tarayıcı çerçevede göstermeyi reddeder ("refused to connect").
//
// Çözüm: önizlenen HTML'e <base target="_blank"> enjekte et → tüm linkler YENİ SEKMEDE açılsın (çerçeveyi
// değil). iframe'de sandbox="allow-popups allow-popups-to-escape-sandbox" ile birlikte kullanılır; açılan sekme
// sandbox'tan çıkıp uygulamayı normal üst-düzey gezinme olarak yükler (X-Frame-Options tetiklenmez).
// allow-scripts / allow-same-origin EKLENMEZ → mail HTML'i statik + güvenli kalır.

import { LOGO_OK, LOGO_WARNING, LOGO_CRITICAL } from '../assets/brand/mailLogos.js'

// Mail logosu gerçek gönderimde bir MIME inline ekidir: <img src="cid:brand-logo">
// (backend BrandMailAssets.headerLockup / addInline). notification_logs.message HTML'i HAM saklanır,
// ek baytları saklanmaz → tarayıcı "cid:" şemasını hiçbir koşulda çözemez ve önizlemede logo KIRIK çıkar.
//
// Neden data: URI, neden /brand/... URL'i değil: önizleme iframe'leri sandbox'ta allow-same-origin
// TAŞIMAZ (bazıları sandbox=""), yani opak origin'de çalışır. srcdoc üst dokümanın CSP'sini devralır
// ve CSP "img-src 'self' data:" — opak origin'de 'self' EŞLEŞMEZ, data: her koşulda geçerlidir.
// Böylece sandbox değerlerine dokunmadan tüm önizleme noktaları tek yerde düzelir.
const BRAND_LOGO_RE = /src=(["'])cid:brand-logo\1/gi

const LOGOS = { ok: LOGO_OK, warning: LOGO_WARNING, critical: LOGO_CRITICAL }

/**
 * Bir bildirim kaydının hangi logo varyantıyla gönderildiğini türetir.
 * Backend kuralının (BrandMailAssets.variantForLevel) birebir aynısı: çözülme mailleri daima "ok",
 * CRITICAL alarmlar "critical", diğer tüm seviyeler "warning". İkisi ayrışırsa önizlemede yanlış
 * renkte logo görünür (işlevsel bozulma değil), bu yüzden kural burada da yazılı tutuluyor.
 */
export function mailLogoVariant({ trigger, level } = {}) {
  if (String(trigger || '').toUpperCase() === 'RESOLUTION') return 'ok'
  return String(level || '').toUpperCase() === 'CRITICAL' ? 'critical' : 'warning'
}

/**
 * srcDoc için önizleme HTML'ini hazırlar: <base target="_blank"> ekler ve marka logosunun
 * çözülemeyen cid: referansını gömülü data: URI ile değiştirir.
 * Diğer cid: referanslarına DOKUNULMAZ — login-issue ekran görüntüleri (cid:shotN) ve haftalık
 * rapor görselleri (cid:img{id}) kendi rewrite'larına sahiptir.
 */
export function mailPreviewSrcDoc(html, { logoVariant = 'ok' } = {}) {
  if (!html) return html
  const logo = LOGOS[logoVariant] || LOGOS.ok
  const withLogo = html.replace(BRAND_LOGO_RE, () => `src="${logo}"`)
  const base = '<base target="_blank">'
  if (/<head[^>]*>/i.test(withLogo)) return withLogo.replace(/<head[^>]*>/i, (m) => m + base)
  return base + withLogo
}

/** Mail önizleme iframe'i için sandbox değeri: linkler yeni sekmede açılabilsin ama içerik script çalıştırmasın. */
export const MAIL_PREVIEW_SANDBOX = 'allow-popups allow-popups-to-escape-sandbox'
