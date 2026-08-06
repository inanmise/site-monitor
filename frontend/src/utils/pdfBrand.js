import logoUrl from '../assets/brand/logo-ok-192.png'

/**
 * PDF marka header'ı — tüm jspdf dışa aktarımları bu yardımcıyı kullanır; sayfa sayfa elle
 * logo gömme yok (BRAND.md). PDF kalıcı belgedir → durum varyantı KULLANILMAZ, daima nötr "ok".
 */
let cachedDataUrl = null

/** logo-ok-192.png → dataURL (bir kez fetch'lenir; Vite asset URL'i aynı origin). */
export async function brandLogoDataUrl() {
  if (cachedDataUrl) return cachedDataUrl
  const blob = await (await fetch(logoUrl)).blob()
  cachedDataUrl = await new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(r.result)
    r.onerror = reject
    r.readAsDataURL(blob)
  })
  return cachedDataUrl
}

/**
 * Header'ı çizer: sol üstte ~40px logo, başlık metni sağında aynı hizada.
 * Dönüş: başlık bloğunun bittiği y (çağıran akışına devam eder).
 * Logo yüklenemezse sessizce metin-only devam eder (dışa aktarım düşmez).
 */
export async function drawBrandHeader(doc, title, x, y, { logoSize = 40, titleSize = 16 } = {}) {
  let textX = x
  try {
    const dataUrl = await brandLogoDataUrl()
    doc.addImage(dataUrl, 'PNG', x, y, logoSize, logoSize)
    textX = x + logoSize + 12
  } catch { /* logo yoksa yalnız başlık */ }
  doc.setFontSize(titleSize)
  doc.text(title, textX, y + logoSize / 2 + titleSize / 3)
  return y + logoSize + 10
}
