/**
 * Kılavuz (whitepaper) bölüm ayıklayıcısı — HelpDrawer'ın açılış parçası ile tembel gövdesinin ortak yardımcısı
 * (2026-10-09: gövde ayrı parçaya taşındı; HelpDrawer bu adı geriye uyum için yeniden dışa aktarır).
 *
 * "### 14.N …" başlığından bir sonraki "### " ya da "## " başlığına kadar olan parçayı döner.
 */
export function extractSection(md, number) {
  if (!md || !number) return null
  const lines = md.split(/\r?\n/)
  const start = lines.findIndex((l) => new RegExp(`^###\\s+${number.replace('.', '\\.')}(\\s|$)`).test(l))
  if (start < 0) return null
  let end = lines.length
  for (let i = start + 1; i < lines.length; i++) { if (/^##\s|^###\s/.test(lines[i])) { end = i; break } }
  return lines.slice(start, end).join('\n')
}
