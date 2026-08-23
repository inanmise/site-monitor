/**
 * Bayt → okunur boyut. Boyut ekranın HER yerinde aynı biçimde görünsün diye tek kaynak:
 * kart metriği, kaynak tablosu, grafik ekseni ve ipucu hep buradan geçer.
 *
 * <p>{@code truncated} true ise değer ALT SINIRDIR ve "≥" ile gösterilir: okuma bayt tavanında
 * kesilmiş bir kaynağın gerçek boyutu daha yüksektir, çıplak sayı basmak toplamı olduğundan
 * küçük gösterirdi (kullanıcı eşiğini de o küçük sayıya göre kurardı).
 *
 * <p>Taban 1024 — sunucudaki bayt tavanı da 1024 tabanlı, yoksa kırpılan satır "≥ 9,5 MB" gibi
 * tuhaf bir sayı gösterirdi.
 */
export function formatBytes(b, truncated = false) {
  if (b == null) return '—'
  const n = Number(b)
  if (!Number.isFinite(n)) return '—'
  const p = truncated ? '≥ ' : ''
  if (n < 1024) return `${p}${n} B`
  if (n < 1024 * 1024) return `${p}${Math.round(n / 1024)} KB`
  return `${p}${(n / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * Grafik EKSENİ için kısa biçim — eksen etiketi dar olduğu için ondalık taşımaz
 * ("46 MB", "512 KB"). Değer okunmuyorsa eksen işe yaramaz.
 */
export function formatBytesAxis(b) {
  // null/undefined AYRICA elenir: Number(null) === 0, yani salt isFinite kontrolü null'ı
  // "0 B" diye çizerdi — formatBytes onu "—" sayarken eksen sıfır gösterirdi (tutarsız).
  if (b == null) return ''
  const n = Number(b)
  if (!Number.isFinite(n)) return ''
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`
  return `${Math.round(n / (1024 * 1024))} MB`
}
