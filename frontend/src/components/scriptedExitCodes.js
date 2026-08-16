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
 * Script yazarken hangi motorla karşı karşıya olunduğu — sürüm rozeti için.
 *
 * Kullanıcı "kendi laptopunda çalışan" script'i buraya yapıştırıyor ve ortamdaki k6 daha eskiyse
 * `?.` / `??` / `{...nesne}` "Unexpected token" ile patlıyor. Bu bilgiyi HATA sonrası ipucu olarak
 * vermek geç kalıyor; rozet yazmadan ÖNCE söyler. {@link K6_MODERN_SYNTAX_SINCE} eşiği tek kaynak,
 * `diagnosisHint` ile aynı sabit kullanılır.
 *
 * @param {string|null|undefined} raw  örn. "v0.49.0"
 * @returns {'legacy'|'modern'|null} sürüm okunamazsa null (rozet gösterilmez — yanlış bilgi vermektense sus)
 */
export function k6SyntaxLevel(raw) {
  const ver = parseK6Version(raw)
  if (!ver) return null
  return lessThan(ver, K6_MODERN_SYNTAX_SINCE) ? 'legacy' : 'modern'
}

/**
 * İSTEK FAZLARI — sıra anlamlıdır ve teşhisin tamamı bu sıraya dayanır.
 *
 * 2026-08'e kadar bu veri k6 tarafından üretilip backend'de atılıyordu; sahada 288 koşum
 * "request timeout" derken DNS mi TCP mi TLS mi yanıt bekleme mi olduğu hiç bilinemedi.
 *
 * ÖLÇÜLDÜ (k6 v0.49, gerçek koşumlar): k6 fazların TAMAMINI her zaman basar; girilmemiş faz
 * `0` gelir, metrik hiç eksilmez. Yani "yok = girilmedi" varsayımı YANLIŞTIR. Doğru okuma
 * SON SIFIR-OLMAYAN fazdır:
 *   - başarılı istek      → altı faz da > 0
 *   - TCP bağlanır, TLS düşer → connecting > 0, tls/sending/waiting/receiving = 0
 *   - hiç bağlanamaz      → hepsi 0
 * Aradaki sıfırlar yanıltmaz (ör. DNS önbellekliyse blocked=0 olabilir) çünkü SON pozitif
 * faz esas alınır.
 */
export const REQUEST_PHASES = [
  { key: 'blocked', field: 'blocked_ms', flat: 'req_blocked_ms' },
  { key: 'connecting', field: 'connecting_ms', flat: 'req_connecting_ms' },
  { key: 'tls', field: 'tls_ms', flat: 'req_tls_ms' },
  { key: 'sending', field: 'sending_ms', flat: 'req_sending_ms' },
  { key: 'waiting', field: 'waiting_ms', flat: 'req_waiting_ms' },
  { key: 'receiving', field: 'receiving_ms', flat: 'req_receiving_ms' },
]

/**
 * Koşum satırından faz kırılımını okur.
 *
 * İKİ KAYNAK var ve ikisi de meşru: liste/test uçları iç içe `phases` nesnesi döner, kontrol
 * geçmişi ucu ise entity'yi düz serileştirdiği için `req_*_ms` alanlarını düz döner. Tek bir
 * okuyucu ikisini de kabul eder — aksi halde aynı panel geçmişte boş görünürdü.
 *
 * `passed=true` iken takılma noktası HİÇ üretilmez: başarılı bir koşumda son fazın (receiving)
 * ölçümü yuvarlanarak 0 çıkabiliyor ve panel sağlıklı bir isteği "takıldı" diye suçlardı.
 *
 * @param {object} check   koşum satırı (iç içe `phases` ya da düz `req_*_ms`)
 * @param {boolean} passed koşum başarılı mı (PASS)
 * @returns {{phases: Array<{key: string, ms: number|null, done: boolean}>, stuckAt: string|null,
 *            reached: string|null, dataSent: number|null, dataReceived: number|null, any: boolean}}
 *   `stuckAt`: son SIFIR-OLMAYAN fazdan sonraki faz; hiçbiri pozitif değilse ilk faz
 *              (istek hiç yol alamadı); tamamlandıysa ya da `passed` ise null.
 *   `any`: faz alanı hiç GELMEDİYSE false — düzeltme öncesi kaydedilmiş satır; panel çizilmez.
 */
export function readPhases(check, passed = false) {
  const src = check?.phases ?? check ?? {}
  const raw = REQUEST_PHASES.map(p => {
    const v = src[p.field] ?? check?.[p.flat]
    return { key: p.key, ms: v == null ? null : Number(v) }
  })
  const any = raw.some(p => p.ms != null)
  // k6 girilmemiş fazı 0 basar (ölçüldü) ⇒ "nereye kadar gelindi" = SON POZİTİF faz.
  let lastPositive = -1
  raw.forEach((p, i) => { if (p.ms != null && p.ms > 0) lastPositive = i })

  let stuckAt = null
  if (any && !passed) {
    if (lastPositive < 0) stuckAt = raw[0].key                      // hiç yol alamadı
    else if (lastPositive < raw.length - 1) stuckAt = raw[lastPositive + 1].key
  }
  const phases = raw.map((p, i) => ({ ...p, done: i <= lastPositive }))
  const num = (v) => (v == null ? null : Number(v))
  return {
    phases,
    any,
    reached: lastPositive >= 0 ? raw[lastPositive].key : null,
    stuckAt,
    dataSent: num(src.data_sent ?? check?.data_sent),
    dataReceived: num(src.data_received ?? check?.data_received),
  }
}

/**
 * Koşumun DOĞRULAMA özeti — k6 `check()` sayaçlarının insan-okur hâli.
 *
 * Eskiden dört ekranda ham notasyon basılıyordu: `2✓/0✗`. Sayının ne olduğu hiçbir yerde
 * yazmadığı için kullanıcı "bu nedir anlaşılmıyor" dedi. Terim uydurulmuyor: statü etiketi zaten
 * "Doğrulama yok" (`scripted.status_NO_CHECKS`) diyor, yani projenin sözlüğünde k6 check'inin
 * karşılığı "doğrulama".
 *
 * Sayı biçimi bilinçli: BAŞARISIZDA "kalan / toplam" gösterilir (kaç tanesinin düştüğü asıl
 * bilgidir), başarılıda yalnız geçen sayısı — "2 / 2" gereksiz gürültü.
 *
 * @returns {{tone:'ok'|'bad'|'none', icon:string, text:string}|null}
 *   null ⇒ sayaç HİÇ yok (koşum olmamış) — çağıran "—" basar; 0/0 ile karıştırılmaz.
 */
export function checksSummary(t, check) {
  if (!check) return null
  const passed = check.checks_passed ?? check.checksPassed
  const failed = check.checks_failed ?? check.checksFailed
  if (passed == null && failed == null) return null
  const p = Number(passed || 0), f = Number(failed || 0)
  if (f > 0) return { tone: 'bad', icon: '✗', text: t('scripted.checksFailed', f, p + f) }
  if (p > 0) return { tone: 'ok', icon: '✓', text: t('scripted.checksPassed', p) }
  // Script koştu ama hiçbir şey doğrulamadı — NO_CHECKS statüsünün hücredeki karşılığı.
  return { tone: 'none', icon: '—', text: t('scripted.checksNone') }
}

/**
 * Takıldığı fazın kısa etiketi ("TLS'te takıldı").
 *
 * Anahtarlar faz BAŞINA hazır: Türkçe ek uyumu ("TLS'te" / "TCP'de" / "gönderimde") runtime'da
 * `{0}` ile üretilemez.
 */
export function stuckLabel(t, check) {
  const { stuckAt } = readPhases(check, isPass(check?.status))
  if (!stuckAt) return null
  switch (stuckAt) {
    case 'blocked':    return t('scripted.stuck_blocked')
    case 'connecting': return t('scripted.stuck_connecting')
    case 'tls':        return t('scripted.stuck_tls')
    case 'sending':    return t('scripted.stuck_sending')
    case 'waiting':    return t('scripted.stuck_waiting')
    case 'receiving':  return t('scripted.stuck_receiving')
    default:           return null
  }
}

/** Byte → insan-okur ("381 B" / "1,2 KB"). Grafik değil, tanı metni için — tek ondalık yeter. */
export function formatBytes(n) {
  if (n == null || !Number.isFinite(Number(n))) return null
  const v = Number(n)
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  return `${(v / 1024 / 1024).toFixed(1)} MB`
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
  return /\n/.test(withoutRunContext(check))
}

/** Backend'in her başarısız koşuma eklediği bağlam satırının imzası. */
const RUN_CONTEXT_MARK = 'süreç bütçesi='
/** Ters bütçe cümlesinin başlangıcı — bağlamın parçası, "k6 sebebi" değil. */
const INVERSE_BUDGET_MARK = "İstek timeout'u ("

/**
 * Koşum bağlamını (bütçe/çıkış satırı + ters bütçe cümlesi) metinden AYIKLAR.
 *
 * <p>Şart: bağlam satırı `\n` ile ekleniyor ve {@link hasTimeoutReason} "satır sayısı" ile karar
 * veriyordu. Ayıklanmazsa bağlam eklendiği andan itibaren HER zaman aşımı "sebebi var" sayılır ve
 * gerçekten sebepsiz kalan koşumlarda hiçbir yönlendirme çıkmaz — düzeltmenin kendisi teşhisi
 * körleştirirdi.
 */
function withoutRunContext(check) {
  const err = String(check.error ?? '')
  const cut = [err.indexOf(RUN_CONTEXT_MARK), err.indexOf(INVERSE_BUDGET_MARK)]
    .filter((i) => i >= 0)
  return (cut.length ? err.slice(0, Math.min(...cut)) : err).trim()
}

/** Bu koşumda ters bütçe (istek timeout'u ≥ süreç bütçesi) tespit edilmiş mi? */
function hasInverseBudget(check) {
  return String(check.error ?? '').includes(INVERSE_BUDGET_MARK)
}

/** Bağlam satırı "script'te açık istek timeout'u yok" diyor mu? */
function lacksExplicitTimeout(check) {
  return /script istek timeout'u=verilmemiş/.test(String(check.error ?? ''))
}

/** PASS koşumunda tanı ipucu gösterilmez (çıktıda "request timeout" geçse bile — ör. eski satır metni). */
function isPass(status) {
  return status === 'PASS'
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
  //
  // 2026-08 düzeltmesi — bu kural KOŞULSUZ çalışıyordu ve sahada tam tersini yaptı: script'inde
  // `timeout: '20s'` YAZAN bir monitörde 289 koşum boyunca "script'e açık timeout ekleyin" dedi.
  // Kullanıcı önerileni zaten yapmıştı; gerçek sebep monitörün süreç bütçesinin (10 sn) istek
  // timeout'undan KÜÇÜK olmasıydı. Artık dört dal var ve hangisinin doğru olduğuna backend'in
  // yazdığı bağlam satırı karar veriyor — tahmin yok.
  if (check.status === 'TIMEOUT') {
    // (a) Ters bütçe: backend zaten iki sayıyı ve iki çıkış yolunu yazdı. Üstüne genel bir ipucu
    //     koymak sinyali sulandırır — sus.
    if (hasInverseBudget(check)) return null
    // (b) k6 sebebi yazabilmiş → yönlendirmeye gerek yok.
    if (hasTimeoutReason(check)) return null
    // (c) Script'te açık timeout YOK → eski ipucu burada gerçekten doğru.
    if (lacksExplicitTimeout(check)) return t('scripted.hintTimeoutNoDetail')
    // (d) Bütçe sırası doğru, açık timeout var, yine de sebep yok → sıra Bağlantı Teşhisi'nde.
    return t('scripted.hintTimeoutRunDiagnostics')
  }

  // Kural 2 — k6'nın KENDİ istek timeout'u düştü (statü FAIL/ERROR; süreç öldürülmedi).
  //
  // Bu, sahada en pahalı sessizlikti: monitör 288 koşumun 288'inde "request timeout" ile düşerken
  // hiçbir yönlendirme çıkmıyordu, çünkü ipucu yalnız TIMEOUT statüsünde çalışıyordu. İki farklı
  // kök neden aynı metni üretir ve ayrımı kullanıcı yapamaz:
  //   (a) hedef gerçekten yavaş  → açık timeout'u yükselt (monitör timeout'u tavanı 180 sn)
  //   (b) çıkışta vekil/güvenlik duvarı var → TCP bağlanır, yanıt hiç gelmez (vekilsiz koşum yutulur)
  // `text` hem `error` hem `output_tail` içerdiğinden ipucu ESKİ kayıtlarda da görünür.
  if (!isPass(check.status) && text.includes('request timeout')) return t('scripted.hintRequestTimeout')

  // Kural 3 — eski motor sözdizimi duvarı (k6 < 0.53, gömülü Babel 6).
  const ver = parseK6Version(k6Version)
  if (!ver || !lessThan(ver, K6_MODERN_SYNTAX_SINCE)) return null
  if (!text.includes('SyntaxError')) return null
  if (!text.includes('Unexpected token') && !text.includes('babel.min.js')) return null

  return t('scripted.hintOldEngine', k6Version)
}
