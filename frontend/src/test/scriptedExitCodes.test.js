import { describe, it, expect } from 'vitest'
import { exitLabel, exitHint, diagnosisHint, K6_EXIT_CODES, K6_EXIT_WITH_HINT } from '../components/scriptedExitCodes.js'

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

  it('TIMEOUT: sebep yazılamadığı için "açık istek timeout u verin" ipucu çıkar', () => {
    // Süreç öldürüldüğü icin k6 hiçbir sebep yazamıyor — outputTail bomboş olabilir.
    expect(diagnosisHint(t, { status: 'TIMEOUT', error: 'Süre aşımı — süreç sonlandırıldı' }, 'v0.49.0'))
      .toBe('«scripted.hintTimeoutNoDetail»')
    // Sürüm eşiğinden BAĞIMSIZ: yeni k6'da da aynı tuzak var (varsayılan istek timeout'u 60 sn)
    expect(diagnosisHint(t, { status: 'TIMEOUT' }, 'v1.0.0')).toBe('«scripted.hintTimeoutNoDetail»')
    expect(diagnosisHint(t, { status: 'TIMEOUT' }, null)).toBe('«scripted.hintTimeoutNoDetail»')
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

  it('TIMEOUT ipucu yalnız TIMEOUT durumunda — FAIL/ERROR bunu göstermez', () => {
    expect(diagnosisHint(t, { status: 'FAIL', error: 'k6 check başarısız' }, 'v0.49.0')).toBeNull()
    expect(diagnosisHint(t, { status: 'ERROR', error: 'GoError: reddedildi' }, 'v0.49.0')).toBeNull()
  })

  it('outputTail (camelCase) de okunur — API iki biçimde de gelebiliyor', () => {
    expect(diagnosisHint(t, { outputTail: 'SyntaxError: Unexpected token (1:5)' }, 'v0.49.0'))
      .toBe('«scripted.hintOldEngine»(v0.49.0)')
  })
})
