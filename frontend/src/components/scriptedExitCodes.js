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

/**
 * Babel'in k6'dan çıkarıldığı ve modern sözdiziminin doğrudan çalışmaya başladığı k6 sürümü.
 * (k6 v0.53.0'dan itibaren `base` ve `extended` modlar `global` aliası dışında aynı — yani
 * transpile katmanı yok.)
 */
const K6_MODERN_SYNTAX_SINCE = [0, 53, 0]

/** "v0.49.0" → [0,49,0]; ayrıştırılamazsa null. */
function parseK6Version(raw) {
  const m = /(\d+)\.(\d+)\.(\d+)/.exec(String(raw ?? ''))
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null
}

function lessThan(a, b) {
  for (let i = 0; i < 3; i++) {
    if (a[i] !== b[i]) return a[i] < b[i]
  }
  return false
}

/**
 * Zaman aşımına uğramış bir koşumda backend sebebi gösterebildi mi?
 *
 * Sözleşme (`ScriptedCheckerService.summarizeError`): sebep ayıklanabildiğinde başlık satırının
 * ardına `":\n" + satırlar` eklenir — yani ÇOK SATIRLI bir `error` "sebep var" demektir; tek
 * satır kalması "k6 sebebi yazamadan öldürüldü"dür. Bu sözleşme her iki tarafta da testle pinli
 * (`ScriptedCheckerServiceTest.timeoutText_carriesReasonWhenK6Printed`).
 *
 * Bu düzeltmeden ÖNCE kaydedilmiş satırlarda sebep hiç yoktu; onlarda ipucu doğru şekilde çıkar.
 *
 * @param {{error?: string}} check
 */
function hasTimeoutReason(check) {
  return /\n/.test(String(check.error ?? ''))
}

/**
 * Çıkış kodu tek başına yetmediğinde metne bakan tanı ipucu.
 *
 * Şu an tek kural var ve en sık kırılmayı kapatıyor: k6 0.49 içindeki gömülü **Babel 6**
 * ES2020 sözdizimini (`?.`, `??`) ve hatta ES2018 nesne spread'ini (`{...o}`) ayrıştıramıyor.
 * Kullanıcı ekranda "Unexpected token" görüyor ve bunu kendi yazım hatası sanıyor — oysa script
 * modern k6'da (ve laptopunda) sorunsuz çalışıyor. Bu ipucu olmadan teşhis pratikte imkânsız.
 *
 * SÜRÜM EŞİĞİ ŞART: k6 yükseltildiğinde ipucu kendiliğinden susmalı, yoksa yanlış tavsiye verir.
 * Sürüm bilinmiyorsa (null/ayrıştırılamaz) ipucu GÖSTERİLMEZ — yanlış yönlendirmektense sessiz kal.
 *
 * @param {(k: string, ...a: unknown[]) => string} t
 * @param {{error?: string, output_tail?: string, outputTail?: string}} check
 * @param {string|null|undefined} k6Version  örn. "v0.49.0"
 * @returns {string|null}
 */
export function diagnosisHint(t, check, k6Version) {
  if (!check) return null
  const text = `${check.error ?? ''}\n${check.output_tail ?? check.outputTail ?? ''}`

  // Kural 1 — süreç zaman aşımı VE ortada sebep yok. k6'nın KENDİ varsayılan istek timeout'u da
  // 60 sn; monitörün süreç timeout'u da 60 sn olunca istek daha kendi kendine düşemeden süreci
  // öldürüyoruz ve k6 sebebi ("Request Failed error=…") yazmaya hiç fırsat bulamıyor. Sonuç:
  // sıfır teşhis. Script'te açık ve daha kısa bir istek timeout'u vermek bunu çözer.
  //
  // Sebep GÖSTERİLEBİLDİYSE ipucu SUSAR: metni "k6 sebebi yazamadı" diye başlıyor ve tam da
  // yazılmış bir sebebin altında görünmesi kullanıcıyı yanlış yola sokar (üstelik tavsiye edilen
  // şey — açık istek timeout'u — zaten yapılmıştır; sebep onun sayesinde çıktı).
  if (check.status === 'TIMEOUT') return hasTimeoutReason(check) ? null : t('scripted.hintTimeoutNoDetail')

  // Kural 2 — eski motor sözdizimi duvarı (k6 < 0.53, gömülü Babel 6).
  const ver = parseK6Version(k6Version)
  if (!ver || !lessThan(ver, K6_MODERN_SYNTAX_SINCE)) return null
  if (!text.includes('SyntaxError')) return null
  if (!text.includes('Unexpected token') && !text.includes('babel.min.js')) return null

  return t('scripted.hintOldEngine', k6Version)
}
