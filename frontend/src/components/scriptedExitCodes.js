/**
 * k6 çıkış kodu → kullanıcının DİLİNDE etiket.
 *
 * Backend `error` alanına zaten insan-okur bir açıklama koyuyor ama repo standardı gereği
 * o metin Türkçe; bu katman aynı bilgiyi arayüz diline çevirir. Kaynak: k6 `errext/exitcodes/codes.go`,
 * v0.49.0 etiketi (imajdaki sürüm) — tahmin değil.
 *
 * Saf fonksiyon: `t` dışarıdan gelir, bileşene bağımlı değil, test edilebilir.
 */

/** k6 v0.49.0'da tanımlı sıfır-dışı çıkış kodları. */
export const K6_EXIT_CODES = [97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109]

/** Operatörün BİR ŞEY YAPMASI gereken kodlar — bunlara ek bir çare satırı gösterilir. */
export const K6_EXIT_WITH_HINT = [104, 105, 106, 109]

/**
 * @returns {string|null} etiket; kod yoksa null (çağıran satırı hiç render etmez)
 */
export function exitLabel(t, code) {
  if (code == null || code === '') return null
  const n = Number(code)
  if (!Number.isFinite(n)) return null
  if (n === -1) return t('scripted.exit_neg1')
  if (n === 0) return null                       // 0 = normal çıkış, etiketlenecek bir şey yok
  const key = `scripted.exit_${n}`
  const label = t(key)
  return label === key ? t('scripted.exit_unknown', n) : label
}

/** @returns {string|null} yalnız eylem gerektiren kodlar için çare cümlesi. */
export function exitHint(t, code) {
  const n = Number(code)
  return K6_EXIT_WITH_HINT.includes(n) ? t(`scripted.exitHelp_${n}`) : null
}
