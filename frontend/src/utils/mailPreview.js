// E-posta HTML'i bir <iframe srcDoc> içinde önizlenirken, mail'deki linkler (ör. "Site Monitor'de Görüntüle"
// CTA'sı) tıklanınca iframe'in KENDİSİNİ hedefe götürür. Hedef uygulama URL'i ise X-Frame-Options: DENY +
// CSP frame-ancestors 'none' döndürdüğünden tarayıcı çerçevede göstermeyi reddeder ("refused to connect").
//
// Çözüm: önizlenen HTML'e <base target="_blank"> enjekte et → tüm linkler YENİ SEKMEDE açılsın (çerçeveyi
// değil). iframe'de sandbox="allow-popups allow-popups-to-escape-sandbox" ile birlikte kullanılır; açılan sekme
// sandbox'tan çıkıp uygulamayı normal üst-düzey gezinme olarak yükler (X-Frame-Options tetiklenmez).
// allow-scripts / allow-same-origin EKLENMEZ → mail HTML'i statik + güvenli kalır.

/** srcDoc için önizleme HTML'ine <base target="_blank"> ekler (varsa <head> içine, yoksa başa). */
export function mailPreviewSrcDoc(html) {
  if (!html) return html
  const base = '<base target="_blank">'
  if (/<head[^>]*>/i.test(html)) return html.replace(/<head[^>]*>/i, (m) => m + base)
  return base + html
}

/** Mail önizleme iframe'i için sandbox değeri: linkler yeni sekmede açılabilsin ama içerik script çalıştırmasın. */
export const MAIL_PREVIEW_SANDBOX = 'allow-popups allow-popups-to-escape-sandbox'
