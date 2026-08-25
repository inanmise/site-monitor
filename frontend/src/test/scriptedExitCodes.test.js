import { describe, it, expect } from 'vitest'
import { exitLabel, exitHint, diagnosisHint, k6SyntaxLevel, K6_EXIT_CODES, K6_EXIT_WITH_HINT,
  readPhases, formatBytes, checksSummary, stuckLabel } from '../components/scriptedExitCodes.js'

/**
 * `exitLabel` "çeviri var mı"yı `t(key) === key` kimlik testiyle anlıyor; bu yüzden stub anahtarı
 * OLDUĞU GİBİ döndüremez (yoksa her kod "bilinmiyor" sanılır). Sahte sözlük gerçek anahtar
 * kümesini taklit eder: bilinenler sarmalanır, bilinmeyenler anahtarın kendisini döndürür.
 */
const KNOWN = new Set([
  ...K6_EXIT_CODES.map(c => `scripted.exit_${c}`),
  'scripted.exit_neg1',
  ...K6_EXIT_WITH_HINT.map(c => `scripted.exitHelp_${c}`),
  'scripted.hintOldEngine',
  'scripted.hintTimeoutNoDetail',
  'scripted.hintRequestTimeout',
  'scripted.hintTimeoutRunDiagnostics',
])
const t = (k, ...a) => {
  const args = a.length ? `(${a.join(',')})` : ''
  return KNOWN.has(k) ? `«${k}»${args}` : `${k}${args}`
}

describe('exitLabel', () => {
  it('bilinen kodları çevirir, 0 için etiket üretmez', () => {
    expect(exitLabel(t, 107)).toBe('«scripted.exit_107»')
    expect(exitLabel(t, -1)).toBe('«scripted.exit_neg1»')
    expect(exitLabel(t, 0)).toBeNull()          // normal çıkış — etiketlenecek bir şey yok
    expect(exitLabel(t, null)).toBeNull()
    expect(exitLabel(t, '')).toBeNull()
    expect(exitLabel(t, 'abc')).toBeNull()
  })

  it('tanımsız kod → exit_unknown', () => {
    // `t` anahtarı geri verdiği için "çeviri yok" dalı tetiklenir
    expect(exitLabel(t, 42)).toBe('scripted.exit_unknown(42)')
  })

  it('çare cümlesi yalnız eylem gerektiren kodlarda', () => {
    for (const c of K6_EXIT_WITH_HINT) expect(exitHint(t, c)).toBe(`«scripted.exitHelp_${c}»`)
    for (const c of K6_EXIT_CODES.filter(c => !K6_EXIT_WITH_HINT.includes(c))) {
      expect(exitHint(t, c)).toBeNull()
    }
  })
})

describe('k6SyntaxLevel — sürüm rozeti', () => {
  it('0.53 ÖNCESİ legacy (gömülü Babel 6), sonrası modern', () => {
    expect(k6SyntaxLevel('v0.49.0')).toBe('legacy')
    expect(k6SyntaxLevel('0.52.9')).toBe('legacy')
    expect(k6SyntaxLevel('v0.53.0')).toBe('modern')   // eşiğin KENDİSİ modern
    expect(k6SyntaxLevel('v1.0.0')).toBe('modern')
  })

  it('sürüm okunamıyorsa null — yanlış sözdizimi tavsiyesi vermektense sus', () => {
    expect(k6SyntaxLevel(null)).toBeNull()
    expect(k6SyntaxLevel(undefined)).toBeNull()
    expect(k6SyntaxLevel('bilinmiyor')).toBeNull()
  })

  it('diagnosisHint ile AYNI eşiği kullanır (tek kaynak — biri değişip diğeri kalmasın)', () => {
    const syntaxErr = { error: 'SyntaxError: Unexpected token (1:5)' }
    // legacy → eski-motor ipucu var; modern → yok
    expect(k6SyntaxLevel('v0.49.0')).toBe('legacy')
    expect(diagnosisHint(t, syntaxErr, 'v0.49.0')).not.toBeNull()
    expect(k6SyntaxLevel('v0.53.0')).toBe('modern')
    expect(diagnosisHint(t, syntaxErr, 'v0.53.0')).toBeNull()
  })
})

describe('diagnosisHint — k6 sözdizimi duvarı', () => {
  // Gerçek k6 v0.49.0 çıktısının çekirdeği (optional chaining içeren script).
  const syntaxErr = {
    error: 'script çalışma-zamanı hatası (çıkış 107):\nSyntaxError: script: Unexpected token (46:29)',
    output_tail: 'level=error msg="SyntaxError: ... babel.min.js"',
  }

  it('eski k6 + SyntaxError → ipucu gösterilir', () => {
    expect(diagnosisHint(t, syntaxErr, 'v0.49.0')).toBe('«scripted.hintOldEngine»(v0.49.0)')
    expect(diagnosisHint(t, syntaxErr, '0.52.9')).toBe('«scripted.hintOldEngine»(0.52.9)')
  })

  it('k6 ≥0.53 → ipucu SUSAR (yükseltmeden sonra yanlış tavsiye vermemeli)', () => {
    expect(diagnosisHint(t, syntaxErr, 'v0.53.0')).toBeNull()
    expect(diagnosisHint(t, syntaxErr, 'v1.0.0')).toBeNull()
    expect(diagnosisHint(t, syntaxErr, 'v0.57.2')).toBeNull()
  })

  it('sürüm bilinmiyorsa ipucu gösterilmez — yanlış yönlendirmektense sessiz kal', () => {
    expect(diagnosisHint(t, syntaxErr, null)).toBeNull()
    expect(diagnosisHint(t, syntaxErr, undefined)).toBeNull()
    expect(diagnosisHint(t, syntaxErr, 'bilinmiyor')).toBeNull()
  })

  it('SyntaxError olmayan hatalarda tetiklenmez', () => {
    expect(diagnosisHint(t, { error: 'Süre aşımı — süreç sonlandırıldı' }, 'v0.49.0')).toBeNull()
    expect(diagnosisHint(t, { error: 'GoError: connection refused' }, 'v0.49.0')).toBeNull()
    // "SyntaxError" var ama token/babel imzası yok → karar veremiyoruz, sus
    expect(diagnosisHint(t, { error: 'SyntaxError: bilinmeyen bir şey' }, 'v0.49.0')).toBeNull()
    expect(diagnosisHint(t, null, 'v0.49.0')).toBeNull()
  })

  it('TIMEOUT: sebep yazılamadığında yönlendirme çıkar (sürüm eşiğinden BAĞIMSIZ)', () => {
    // DEĞİŞTİ (2026-08): eskiden burada koşulsuz "script'e açık istek timeout'u ekleyin" iddia
    // ediliyordu. Sahada 289 koşumluk bir monitörde bu tavsiye YANLIŞTI — script'te açık timeout
    // zaten vardı, sebep süreç bütçesinin ondan küçük olmasıydı. Artık doğru metni backend'in
    // yazdığı bağlam satırı belirliyor; hangi dalın hangi metni seçtiği aşağıdaki
    // "zaman aşımı dalları" grubunda tek tek pinli. Burada kalan sözleşme: TIMEOUT sebepsizse
    // kullanıcı ASLA yönlendirmesiz bırakılmaz ve karar k6 sürümüne bağlı değildir.
    for (const version of ['v0.49.0', 'v1.0.0', null]) {
      expect(diagnosisHint(t, { status: 'TIMEOUT', error: 'Süre aşımı — süreç sonlandırıldı' }, version))
        .toBe('«scripted.hintTimeoutRunDiagnostics»')
      expect(diagnosisHint(t, { status: 'TIMEOUT' }, version)).toBe('«scripted.hintTimeoutRunDiagnostics»')
    }
  })

  it('TIMEOUT: sebep GÖSTERİLEBİLDİYSE ipucu susar (yazılmış sebebin altında "yazamadı" demesin)', () => {
    // Backend sözleşmesi: sebep bulunduğunda başlık satırının ardına ':\n' + satırlar eklenir.
    const withReason = {
      status: 'TIMEOUT',
      error: 'Süre aşımı — süreç sonlandırıldı:\n'
           + 'Request Failed — Post "http://192.0.2.1/v1/chat/completions": request timeout',
    }
    expect(diagnosisHint(t, withReason, 'v0.49.0')).toBeNull()
    // Sebep varken bile ESKİ MOTOR kuralına düşmez: TIMEOUT dalı kararı verip biter.
    expect(diagnosisHint(t, withReason, 'v1.0.0')).toBeNull()
  })

  it('FAIL + "request timeout": ipucu çıkar (288 koşum boyunca hiçbir yönlendirme yoktu)', () => {
    const failWithTimeout = {
      status: 'FAIL',
      error: 'k6 check/threshold başarısız:\nRequest Failed — Get "https://www.example.com": request timeout',
    }
    expect(diagnosisHint(t, failWithTimeout, 'v0.49.0')).toBe('«scripted.hintRequestTimeout»')
    // Sürümden BAĞIMSIZ: yeni k6'da da aynı tuzak var
    expect(diagnosisHint(t, failWithTimeout, 'v1.0.0')).toBe('«scripted.hintRequestTimeout»')
  })

  it('sebep yalnız output_tail\'de olsa bile ipucu çıkar (eski kayıtlar geriye dönük aydınlanır)', () => {
    const oldRow = {
      status: 'FAIL',
      error: 'k6 check/threshold başarısız — başarılı (çıkış 0)',   // düzeltme ÖNCESİ biçim
      output_tail: 'level=warning msg="Request Failed" error="Post \\"https://x\\": request timeout"',
    }
    expect(diagnosisHint(t, oldRow, 'v0.49.0')).toBe('«scripted.hintRequestTimeout»')
  })

  it('PASS koşumunda ipucu çıkmaz (çıktıda geçse bile)', () => {
    expect(diagnosisHint(t, { status: 'PASS', output_tail: 'request timeout' }, 'v0.49.0')).toBeNull()
  })

  it('TIMEOUT ipucu yalnız TIMEOUT durumunda — FAIL/ERROR bunu göstermez', () => {
    expect(diagnosisHint(t, { status: 'FAIL', error: 'k6 check başarısız' }, 'v0.49.0')).toBeNull()
    expect(diagnosisHint(t, { status: 'ERROR', error: 'GoError: reddedildi' }, 'v0.49.0')).toBeNull()
  })

  it('outputTail (camelCase) de okunur — API iki biçimde de gelebiliyor', () => {
    expect(diagnosisHint(t, { outputTail: 'SyntaxError: Unexpected token (1:5)' }, 'v0.49.0'))
      .toBe('«scripted.hintOldEngine»(v0.49.0)')
  })
})

/**
 * Faz kırılımı — sahadaki en pahalı boşluğun kapatıldığı yer. 288 koşum "request timeout" derken
 * DNS/TCP/TLS/TTFB ayrımı hiçbir ekranda görünmüyordu; veri k6'dan geliyordu ama atılıyordu.
 */
describe('readPhases', () => {
  // Aşağıdaki değerler UYDURMA DEĞİL: k6 v0.49 ile gerçek koşumlardan ölçüldü.
  // k6 fazların HEPSİNİ her zaman basar; girilmemiş faz 0 gelir (metrik eksilmez).

  it('TCP bağlanıp TLS düşen koşumda takılma noktası TLS (ölçülmüş biçim)', () => {
    // `k6 run https://example.com:80` → connecting=40.8, gerisi 0, 281 B gönderildi/316 B alındı
    const r = readPhases({ phases: {
      blocked_ms: 0, connecting_ms: 41, tls_ms: 0, sending_ms: 0,
      waiting_ms: 0, receiving_ms: 0, data_sent: 281, data_received: 316,
    } })

    expect(r.any).toBe(true)
    expect(r.reached).toBe('connecting')
    expect(r.stuckAt).toBe('tls')
    expect(r.dataSent).toBe(281)
  })

  it('ARADAKİ sıfır yanıltmaz — son POZİTİF faz esas alınır', () => {
    // Aynı ölçümde blocked=0 çıktı (DNS önbellekli) ama DNS elbette gerçekleşmişti.
    // "İlk sıfır" mantığı kullanılsaydı panel DNS'i suçlardı — yanlış teşhis.
    const r = readPhases({ phases: { blocked_ms: 0, connecting_ms: 41, tls_ms: 0 } })
    expect(r.stuckAt).toBe('tls')
    expect(r.phases.find(p => p.key === 'blocked').done).toBe(true)
  })

  it('hiçbir faz pozitif değilse istek hiç yol alamamıştır (ilk faz işaretlenir)', () => {
    // `k6 run https://10.255.255.1:9443` → altı faz da 0, data_sent=0
    const r = readPhases({ phases: {
      blocked_ms: 0, connecting_ms: 0, tls_ms: 0, sending_ms: 0, waiting_ms: 0, receiving_ms: 0,
    } })
    expect(r.reached).toBeNull()
    expect(r.stuckAt).toBe('blocked')
  })

  it('tüm fazlar pozitifse takılma yok (başarılı istek biçimi)', () => {
    // `k6 run https://example.com` → 221.7 / 49.9 / 147.8 / 8.0 / 59.1 / 0.7
    const r = readPhases({ phases: {
      blocked_ms: 222, connecting_ms: 50, tls_ms: 148, sending_ms: 8, waiting_ms: 59, receiving_ms: 1,
    } })
    expect(r.stuckAt).toBeNull()
    expect(r.reached).toBe('receiving')
  })

  it('PASS koşumunda takılma noktası HİÇ üretilmez (son faz 0\'a yuvarlanabiliyor)', () => {
    const passing = { phases: {
      blocked_ms: 222, connecting_ms: 50, tls_ms: 148, sending_ms: 8, waiting_ms: 59, receiving_ms: 0,
    } }
    expect(readPhases(passing, false).stuckAt).toBe('receiving')   // başarısızsa işaretlenir
    expect(readPhases(passing, true).stuckAt).toBeNull()           // PASS'te asla
  })

  it('kontrol geçmişindeki DÜZ alanları da okur (req_*_ms) — iki uç iki biçim döndürüyor', () => {
    const r = readPhases({ req_blocked_ms: 2, req_connecting_ms: 5, data_sent: 100 })
    expect(r.reached).toBe('connecting')
    expect(r.stuckAt).toBe('tls')
    expect(r.dataSent).toBe(100)
  })

  it('faz alanı hiç gelmediyse any=false — düzeltme öncesi satırlarda panel çizilmez', () => {
    expect(readPhases({}).any).toBe(false)
    expect(readPhases(null).any).toBe(false)
    expect(readPhases({ phases: null }).any).toBe(false)
  })
})

describe('formatBytes', () => {
  it('byte/KB/MB eşiklerini insan-okur biçimde verir', () => {
    expect(formatBytes(381)).toBe('381 B')
    expect(formatBytes(2048)).toBe('2.0 KB')
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB')
  })

  it('yok/geçersiz değer → null (satır hiç basılmaz)', () => {
    expect(formatBytes(null)).toBeNull()
    expect(formatBytes(undefined)).toBeNull()
    expect(formatBytes('abc')).toBeNull()
  })
})

/**
 * Doğrulama özeti — kullanıcı `2✓/0✗` notasyonunu "anlaşılmıyor" diye bildirdi. Bu fonksiyon
 * dört ekranın TEK kaynağı; biçim değişirse hepsi birden değişir.
 */
describe('checksSummary', () => {
  it('hepsi geçtiyse yalnız GEÇEN sayısı gösterilir ("2/2" gürültü)', () => {
    const s = checksSummary(t, { checks_passed: 2, checks_failed: 0 })
    expect(s).toMatchObject({ tone: 'ok', icon: '✓' })
    expect(s.text).toBe('scripted.checksPassed(2)')
  })

  it('kalan varsa KALAN/TOPLAM gösterilir (asıl bilgi kaç tanesinin düştüğü)', () => {
    const s = checksSummary(t, { checks_passed: 2, checks_failed: 1 })
    expect(s).toMatchObject({ tone: 'bad', icon: '✗' })
    expect(s.text).toBe('scripted.checksFailed(1,3)')      // 1 kaldı / toplam 3
  })

  it('script hiç doğrulama yapmadıysa ayrı ton (NO_CHECKS karşılığı)', () => {
    expect(checksSummary(t, { checks_passed: 0, checks_failed: 0 }))
      .toMatchObject({ tone: 'none', icon: '—' })
  })

  it('sayaç HİÇ yoksa null — çağıran "—" basar, 0/0 ile karışmaz', () => {
    expect(checksSummary(t, { checks_passed: null, checks_failed: null })).toBeNull()
    expect(checksSummary(t, {})).toBeNull()
    expect(checksSummary(t, null)).toBeNull()
  })

  it('camelCase alanları da okur (API iki biçimde de gelebiliyor)', () => {
    expect(checksSummary(t, { checksPassed: 3, checksFailed: 0 }).text)
      .toBe('scripted.checksPassed(3)')
  })
})

describe('stuckLabel', () => {
  it('takılan faza ÖZEL anahtar döner (Türkçe ek uyumu çalışma anında üretilemez)', () => {
    // TCP açıldı, TLS tamamlanmadı → takılma noktası TLS
    const check = { status: 'FAIL', phases: {
      blocked_ms: 0, connecting_ms: 41, tls_ms: 0, sending_ms: 0, waiting_ms: 0, receiving_ms: 0 } }
    expect(stuckLabel(t, check)).toBe('scripted.stuck_tls')
  })

  it('hiç yol alamamışsa ilk fazı işaretler', () => {
    const check = { status: 'FAIL', phases: {
      blocked_ms: 0, connecting_ms: 0, tls_ms: 0, sending_ms: 0, waiting_ms: 0, receiving_ms: 0 } }
    expect(stuckLabel(t, check)).toBe('scripted.stuck_blocked')
  })

  it('PASS koşumunda ve faz verisi olmayan satırda rozet YOK', () => {
    expect(stuckLabel(t, { status: 'PASS', phases: {
      blocked_ms: 1, connecting_ms: 2, tls_ms: 3, sending_ms: 4, waiting_ms: 5, receiving_ms: 0 } })).toBeNull()
    expect(stuckLabel(t, { status: 'FAIL' })).toBeNull()
  })
})

/**
 * ZAMAN AŞIMI İPUCU DALLARI — sahada 289 koşum boyunca YANLIŞ tavsiye veren kural.
 *
 * Kural koşulsuzdu: statü TIMEOUT ve `error` tek satırsa daima "script'e AÇIK bir istek timeout'u
 * ekleyin" diyordu. Gerçek monitörün script'inde `timeout: '20s'` ZATEN vardı; sebep, monitörün
 * süreç bütçesinin (10 sn) istek timeout'undan KÜÇÜK olmasıydı — k6 sebebi yazmaya fırsat
 * bulamadan süreci öldürüyorduk. Kullanıcı iki ay boyunca çoktan yaptığı şeyi yapması söylendi.
 *
 * Artık dalı backend'in yazdığı bağlam satırı belirliyor. Bu testler her dalın DOĞRU metni
 * seçtiğini pinler; en önemlisi de yanlış metnin bir daha çıkmamasını.
 */
describe('diagnosisHint — zaman aşımı dalları', () => {
  const CTX = "süreç bütçesi=60s · script istek timeout'u=20s · çıkış=doğrudan · kurumsal CA=verildi"
  const INVERSE = "İstek timeout'u (20 sn) monitörün süreç bütçesine (10 sn) eşit ya da ondan büyük: "
    + "k6 isteği kendi düşüremeden süreci öldürüyoruz ve başarısızlığın SEBEBİ hiç yazılamıyor."

  it('SAHA VAKASI: ters bütçede genel ipucu SUSAR — backend iki sayıyı zaten yazdı', () => {
    const check = { status: 'TIMEOUT',
      error: `Süre aşımı — süreç sonlandırıldı\n${INVERSE}\nsüreç bütçesi=10s · script istek timeout'u=20s · çıkış=doğrudan · kurumsal CA=verildi` }

    expect(diagnosisHint(t, check, 'v0.49.0')).toBeNull()
  })

  it('script\'te açık timeout YOKSA eski ipucu doğrudur ve gösterilir', () => {
    const check = { status: 'TIMEOUT',
      error: "Süre aşımı — süreç sonlandırıldı\nsüreç bütçesi=60s · script istek timeout'u=verilmemiş (k6 varsayılanı 60s) · çıkış=doğrudan · kurumsal CA=verildi" }

    expect(diagnosisHint(t, check, 'v0.49.0')).toBe('«scripted.hintTimeoutNoDetail»')
  })

  it('bütçe sırası doğru ama sebep yoksa Bağlantı Teşhisi\'ne yönlendirir (script suçlanmaz)', () => {
    const check = { status: 'TIMEOUT', error: `Süre aşımı — süreç sonlandırıldı\n${CTX}` }

    expect(diagnosisHint(t, check, 'v0.49.0')).toBe('«scripted.hintTimeoutRunDiagnostics»')
  })

  it('k6 GERÇEK sebebi yazdıysa ipucu susar — bağlam satırı "sebep" sanılmaz', () => {
    const check = { status: 'TIMEOUT',
      error: `Süre aşımı — süreç sonlandırıldı:\nRequest Failed error="dial tcp: i/o timeout"\n${CTX}` }

    expect(diagnosisHint(t, check, 'v0.49.0')).toBeNull()
  })

  it('REGRESYON: bağlam satırının kendisi "sebep var" saydırmaz', () => {
    // Bağlam `\n` ile ekleniyor; ayıklanmasaydı her zaman aşımı "sebebi var" görünür ve
    // gerçekten sebepsiz kalan koşumlarda hiçbir yönlendirme çıkmazdı — düzeltme teşhisi körleştirirdi.
    const onlyContext = { status: 'TIMEOUT', error: `Süre aşımı — süreç sonlandırıldı\n${CTX}` }

    expect(diagnosisHint(t, onlyContext, 'v0.49.0')).not.toBeNull()
  })

  it('bağlam satırı olmayan ESKİ kayıtlar da yönlendirme alır (sessiz kalmaz)', () => {
    const legacy = { status: 'TIMEOUT', error: 'Süre aşımı — süreç sonlandırıldı' }

    expect(diagnosisHint(t, legacy, 'v0.49.0')).toBe('«scripted.hintTimeoutRunDiagnostics»')
  })
})
