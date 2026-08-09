/**
 * Panoya kopyalama — projedeki TEK yardımcı. Üç kademe:
 *   1) navigator.clipboard.writeText (izin verilmiş güvenli bağlam)
 *   2) geçici <textarea> + document.execCommand('copy') (http origin / izin reddi)
 *   3) false döner — karar çağırana ait
 *
 * Bilinçli olarak toast/i18n bağımlılığı YOKTUR: bu yardımcı hata yüzeyinde (ErrorBoundary
 * fallback'i) de kullanılıyor ve useToast() provider yoksa throw ediyor. Kopyalama, çökmüş
 * bir ekranda referans numarasını almanın tek yolu olabilir; yeni bir çökme riski taşımamalı.
 *
 * @returns {Promise<boolean>} kopyalandıysa true
 */
export async function copyText(value) {
  const text = String(value ?? '')
  if (!text) return false

  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch { /* güvensiz bağlam veya izin yok → fallback */ }

  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return !!ok
  } catch {
    return false
  }
}
