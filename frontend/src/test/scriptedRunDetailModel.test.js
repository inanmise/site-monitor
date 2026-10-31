import { describe, it, expect } from 'vitest'
import {
  ROOT_STEP, STEP_SERIES_COLORS, VERSION_REGRESSION, certDaysTone, failureHeadText, historyDetailText, normalizeSteps, parseChecks,
  runContextChips, runContextOf, stepSeriesData, stepState, stepsOf, stepsTruncated, stoppedAfter, thresholdsOf, tlsOf, tlsVersionLabel,
  versionRegression,
} from '../components/scripted/runDetailModel.js'
import { checksSummary, isHalted, stripRunContext } from '../components/scriptedExitCodes.js'
import { failureOf, scriptedLegacyCode, detailRows } from '../components/checks/checkFailureModel.js'
import { isCodeFrame } from '../components/ScriptedMonitorPage.jsx'
import { cellBackground } from '../components/history/DensityStrip.jsx'
import { diffTileTone } from '../components/admin/k6engine/k6EngineModel.js'

/** Anahtarı ve argümanları döndüren t — testler ANLAMA bakar. */
const t = (k, ...a) => (a.length ? `${k}:${a.join('|')}` : k)

/**
 * Sentetik izleme KOŞUM AYRINTISI modeli (2026-10-31, k6r D4 + B4 fe2 hataları). Tel biçimi sözleşmede: adımlar, eşikler,
 * TLS, bağlam; ESKİ satır bu alanları taşımaz → okuyucular null döner ve ekran bugünkü davranışta kalır.
 */
describe('runDetailModel — adımlar', () => {
  const STEPS = [
    { name: 'Giriş', duration_ms: 812, requests: 2, failed_requests: 0, p95_ms: 410, checks_passed: 2, checks_failed: 0, error_code: null },
    { name: '', duration_ms: null, requests: 1, failed_requests: 0, p95_ms: 90, checks_passed: 0, checks_failed: 0, error_code: null },
    { name: 'Giriş › OTP', duration_ms: 3000, requests: 1, failed_requests: 1, p95_ms: 3000, checks_passed: 0, checks_failed: 1, error_code: 1210 },
  ]

  it('stepsOf: dizi (test yanıtı), steps_json METNİ (geçmiş satırı), eski satır null, bozuk metin null', () => {
    expect(stepsOf({ steps: STEPS })).toHaveLength(3)
    expect(stepsOf({ steps_json: JSON.stringify(STEPS) })[2]).toEqual({
      name: 'Giriş › OTP', durationMs: 3000, requests: 1, failedRequests: 1, p95Ms: 3000, checksPassed: 0, checksFailed: 1, errorCode: 1210,
    })
    expect(stepsOf({ status: 'PASS' })).toBeNull()          // eski satır: alan yok → uydurulmaz
    expect(stepsOf({ steps_json: '{bozuk' })).toBeNull()
    expect(stepsOf({ steps: [] })).toEqual([])              // koşum adım üretmedi (alan var, boş)
    expect(stepsOf(null)).toBeNull()
    // kök adım: ad boş dize
    expect(stepsOf({ steps: STEPS })[1].name).toBe(ROOT_STEP)
    // camelCase de kabul (form test paneli istemci tarafında üretebilir)
    expect(normalizeSteps([{ name: 'a', durationMs: 5, failedRequests: 2 }])[0]).toMatchObject({ durationMs: 5, failedRequests: 2 })
  })

  it('stepState: bir istek ya da doğrulama düştüyse fail', () => {
    const [ok, , bad] = stepsOf({ steps: STEPS })
    expect(stepState(ok)).toBe('ok')
    expect(stepState(bad)).toBe('fail')
    expect(stepState({ failedRequests: 0, checksFailed: 2 })).toBe('fail')
  })

  it('stoppedAfter: yalnız ERROR / TIMEOUT; son BAŞARILI adım; hiç yoksa "start"; adım yoksa null', () => {
    const steps = stepsOf({ steps: STEPS })
    expect(stoppedAfter(steps, 'ERROR')).toEqual({ kind: 'after', name: ROOT_STEP })
    expect(stoppedAfter(steps.slice(0, 1), 'TIMEOUT')).toEqual({ kind: 'after', name: 'Giriş' })
    expect(stoppedAfter(steps.slice(2), 'ERROR')).toEqual({ kind: 'start' })
    expect(stoppedAfter(steps, 'FAIL')).toBeNull()      // senaryo sona kadar koştu — "durdu" iddiası yok
    expect(stoppedAfter(steps, 'PASS')).toBeNull()
    expect(stoppedAfter([], 'ERROR')).toBeNull()
  })

  it('stepsTruncated: sunucu bayrağı', () => {
    expect(stepsTruncated({ steps_truncated: true })).toBe(true)
    expect(stepsTruncated({})).toBe(false)
  })
})

describe('runDetailModel — doğrulamalar, eşikler, TLS, bağlam', () => {
  it('parseChecks: dizi, sonda {truncated:true} işareti, {checks, truncated} nesnesi, bozuk metin', () => {
    expect(parseChecks('[{"name":"a","passed":true},{"name":"b","passed":false}]'))
      .toEqual({ items: [{ name: 'a', passed: true }, { name: 'b', passed: false }], truncated: false })
    expect(parseChecks('[{"name":"a","passed":true},{"truncated":true}]')).toEqual({ items: [{ name: 'a', passed: true }], truncated: true })
    expect(parseChecks({ checks: [{ name: 'x', passed: false }], truncated: true })).toEqual({ items: [{ name: 'x', passed: false }], truncated: true })
    expect(parseChecks('{bozuk')).toEqual({ items: [], truncated: false })
    expect(parseChecks(null)).toEqual({ items: [], truncated: false })
  })

  it('thresholdsOf: dizi ya da metin; ok = geçti; alan yoksa null', () => {
    expect(thresholdsOf({ thresholds: [{ metric: 'http_req_duration', expr: 'p(95)<500', ok: false }] }))
      .toEqual([{ metric: 'http_req_duration', expr: 'p(95)<500', ok: false }])
    expect(thresholdsOf({ thresholds_json: '[{"metric":"checks","expr":"rate>0.5","ok":true}]'})[0].ok).toBe(true)
    expect(thresholdsOf({})).toBeNull()
  })

  it('tlsOf: tracked + sertifika → kalan gün; untracked; "tracked" ama sertifika görünmüyorsa unknown; sürüm etiketi', () => {
    const rows = tlsOf({ tls: [
      { host: 'a.example.com', tls_version: 'tls1.3', requests: 3, cert_state: 'tracked', cert: { domain: 'a.example.com', days_remaining: 12, status: 'valid' } },
      { host: 'b.example.com', tls_version: 'tls1.2', requests: 1, cert_state: 'untracked' },
      { host: 'c.example.com', tls_version: 'tls1.3', requests: 1, cert_state: 'tracked' },
      { host: 'd.example.com', tls_version: 'x', cert_state: 'garip' },
    ] })
    expect(rows.map((r) => r.state)).toEqual(['tracked', 'untracked', 'unknown', 'unknown'])
    expect(rows[0]).toMatchObject({ version: 'TLS 1.3', requests: 3, cert: { domain: 'a.example.com', days: 12 } })
    expect(rows[2].cert).toBeNull()
    expect(tlsOf({})).toBeNull()
    expect(tlsVersionLabel('tls1.2')).toBe('TLS 1.2')
    expect(tlsVersionLabel('weird')).toBe('weird')
    expect(certDaysTone(-1)).toBe('danger')
    expect(certDaysTone(7)).toBe('danger')
    expect(certDaysTone(20)).toBe('warning')
    expect(certDaysTone(90)).toBe('success')
    expect(certDaysTone(null)).toBe('muted')     // bilinmiyor asla yeşil değil
  })

  it('runContextOf + runContextChips: yapılandırılmış bağlam; zaman aşımı yoksa uyarı; ters bütçe kırmızı', () => {
    const ctx = runContextOf({ run_context: { process_budget_s: 60, request_timeout_s: null, egress: 'proxy', corporate_ca: true, inverted_budget: false } })
    expect(runContextChips(ctx).map((c) => [c.key, c.tone])).toEqual([
      ['budget', 'neutral'], ['reqTimeoutNone', 'warn'], ['egressProxy', 'neutral'], ['caYes', 'neutral'],
    ])
    expect(runContextOf({ context_json: '{"process_budget_s":10,"request_timeout_s":20,"egress":"direct","corporate_ca":false,"inverted_budget":true}' }))
      .toMatchObject({ inverted_budget: true })
    const inv = runContextChips({ process_budget_s: 10, request_timeout_s: 20, egress: 'direct', corporate_ca: false, inverted_budget: true })
    expect(inv.map((c) => c.key)).toEqual(['budget', 'reqTimeout', 'egressDirect', 'caNo', 'inverted'])
    expect(inv.at(-1)).toEqual({ key: 'inverted', tone: 'bad', params: [20, 10] })
    expect(runContextChips(null)).toEqual([])
    expect(runContextOf({})).toBeNull()
  })
})

describe('historyDetailText — geçmiş "Detay" hücresi (O4)', () => {
  it('FAIL + düşen doğrulama: iki nokta ile biten etiket DEĞİL, düşen doğrulamanın adı (+N)', () => {
    const row = { status: 'FAIL', exit_code: 99, error: 'k6 eşikleri aşıldı (çıkış 99):\nthresholds crossed',
      checks_json: JSON.stringify([{ name: 'kart › 3D Secure', passed: false }, { name: 'onay', passed: false }]) }
    expect(historyDetailText(t, row)).toBe('scripted.card.failedCheck:kart › 3D Secure scripted.card.moreChecks:1')
  })
  it('eşik (çıkış 99): sunucunun düşen eşiği varsa adıyla; yoksa başlık + asıl sebep (ikinci satır)', () => {
    const base = { status: 'FAIL', exit_code: 99, error: 'k6 eşikleri aşıldı (çıkış 99):\nthresholds on metrics have been crossed' }
    expect(historyDetailText(t, { ...base, failure_detail: '{"threshold":"http_req_duration p(95)<500"}' }))
      .toBe('scripted.detail.thresholdCrossed:http_req_duration p(95)<500')
    expect(historyDetailText(t, base)).toBe('scripted.card.reasonThreshold — thresholds on metrics have been crossed')
  })
  it('ERROR: hatanın özü (iki noktalı başlık atlanır); TIMEOUT: sınırla; sebepsiz FAIL: hatanın ilk satırı', () => {
    expect(historyDetailText(t, { status: 'ERROR', exit_code: 107, error: 'Script çalışma-zamanı hatası (çıkış 107):\nTypeError: x is undefined' }))
      .toBe('TypeError: x is undefined')
    expect(historyDetailText(t, { status: 'TIMEOUT', timeout_seconds: 60, error: 'Süre aşımı' })).toBe('scripted.card.reasonTimeout:60 s')
    expect(historyDetailText(t, { status: 'FAIL', error: 'Request Failed — request timeout' })).toBe('Request Failed — request timeout')
    expect(historyDetailText(t, { status: 'PASS', error: null })).toBeNull()
  })
  it('failureHeadText: kart ve geçmiş AYNI başlığı kullanır', () => {
    expect(failureHeadText(t, { kind: 'check', name: 'a' })).toBe('scripted.card.failedCheck:a')
    expect(failureHeadText(t, { kind: 'timeout', limit: null })).toBe('scripted.card.reasonTimeoutNoLimit')
    expect(failureHeadText(t, { kind: 'error' })).toBeNull()
    expect(failureHeadText(t, null)).toBeNull()
  })
})

describe('versionRegression — "bu sürüm bozdu" bandı (eşikler tek yerde)', () => {
  const v = (id, version, seq, runs, fails, current = false) => ({ id, version, sequence_no: seq, run_count: runs, fail_count: fails, current })

  it('en yeni ≥ 3 koşum, hata oranı ≥ %50 ve öncekinden ≥ 30 puan yüksek → karar', () => {
    const r = versionRegression([v(3, '2.0.1', 3, 6, 6, true), v(2, '2.0.0', 2, 512, 3), v(1, '1.0.0', 1, 4120, 12)])
    expect(r.latest.version).toBe('2.0.1')
    expect(r.prev.version).toBe('2.0.0')
    expect(r.rate).toBe(1)
    expect(r.prevRate).toBeCloseTo(3 / 512)
  })
  it('eşikler: en yenide < 3 koşum, oran < %50, fark < 30 puan → karar yok', () => {
    expect(versionRegression([v(3, 'b', 3, 2, 2, true), v(2, 'a', 2, 100, 0)])).toBeNull()
    expect(versionRegression([v(3, 'b', 3, 10, 4, true), v(2, 'a', 2, 100, 0)])).toBeNull()          // %40 < %50
    expect(versionRegression([v(3, 'b', 3, 10, 7, true), v(2, 'a', 2, 10, 5)])).toBeNull()           // fark 20 puan
    expect(versionRegression([v(3, 'b', 3, 10, 8, true), v(2, 'a', 2, 10, 5)])).not.toBeNull()       // fark 30 puan
    expect(VERSION_REGRESSION).toEqual({ minRuns: 3, minRate: 0.5, minDelta: 0.3 })
  })
  it('koşulmamış / tek koşumluk ara sürüm atlanır; karşılaştırılacak sürüm yoksa karar yok; sıra sequence_no ile', () => {
    const r = versionRegression([v(1, '1.0.0', 0, 500, 5), v(3, '1.0.2', 2, 6, 6, true), v(2, '1.0.1', 1, 1, 0)])
    expect(r.prev.version).toBe('1.0.0')
    expect(versionRegression([v(3, '1.0.2', 2, 6, 6, true), v(2, '1.0.1', 1, 2, 0)])).toBeNull()
    expect(versionRegression([v(1, '1.0.0', 0, 6, 6, true)])).toBeNull()
    expect(versionRegression(null)).toBeNull()
  })
})

describe('stepSeriesData — adım trendi grafiği', () => {
  it('aynı koşumun noktaları aynı satırda birleşir; anahtarlar s0…; başarısız noktalar sayılır; zamana göre sıralı', () => {
    const d = stepSeriesData({ steps: [
      { name: 'Giriş', points: [{ ts: '2026-10-30T10:05:00', duration_ms: 120, failed: false }, { ts: '2026-10-30T10:00:00', duration_ms: 100, failed: true }] },
      { name: '', points: [{ ts: '2026-10-30T10:00:00', duration_ms: 40, failed: false }] },
    ] })
    expect(d.series.map((s) => [s.key, s.name, s.failed, s.points])).toEqual([['s0', 'Giriş', 1, 2], ['s1', '', 0, 1]])
    expect(d.series[0].color).toBe(STEP_SERIES_COLORS[0])
    expect(d.rows).toHaveLength(2)
    expect(d.rows[0]).toMatchObject({ s0: 100, s0f: true, s1: 40 })
    expect(d.rows[1]).toMatchObject({ s0: 120 })
    expect(d.rows[0].t).toBeLessThan(d.rows[1].t)
    expect(d.failed).toBe(1)
    expect(stepSeriesData(null)).toEqual({ series: [], rows: [], failed: 0 })
  })
})

describe('B4 fe2 düzeltmeleri — saf kurallar', () => {
  it('O5: ERROR / TIMEOUT koşumunda düşen doğrulama yoksa sayaç NÖTR "yarıda kaldı"', () => {
    expect(checksSummary(t, { status: 'ERROR', checks_passed: 3, checks_failed: 0 })).toEqual({ tone: 'none', icon: '—', text: 'scripted.checksHalted:3' })
    expect(checksSummary(t, { status: 'TIMEOUT', checks_passed: 3, checks_failed: 0 }).tone).toBe('none')
    expect(checksSummary(t, { status: 'ERROR', checks_passed: 1, checks_failed: 2 }).tone).toBe('bad')
    expect(checksSummary(t, { status: 'PASS', checks_passed: 3, checks_failed: 0 }).tone).toBe('ok')
    expect(isHalted('ERROR')).toBe(true)
    expect(isHalted('FAIL')).toBe(false)
  })

  it('stripRunContext: Türkçe bağlam satırı ve ters bütçe cümlesi gösterimden atılır', () => {
    expect(stripRunContext('Süre aşımı — süreç sonlandırıldı\nsüreç bütçesi=60 sn, script istek timeout\'u=20s')).toBe('Süre aşımı — süreç sonlandırıldı')
    expect(stripRunContext('a\nİstek timeout\'u (90 sn) süreç bütçesinden büyük')).toBe('a')
    expect(stripRunContext(null)).toBe('')
  })

  it('O3: çok satırlı hata YALNIZ caret satırı varsa kod çerçevesi; "etiket:\\nsebep" sarılır', () => {
    expect(isCodeFrame('script: Unexpected token (46:29)\n> 46 |   const c = a?.b;\n     |                ^')).toBe(true)
    expect(isCodeFrame('k6 eşikleri aşıldı (çıkış 99):\nthresholds crossed\nsüreç bütçesi=60 sn')).toBe(false)
    expect(isCodeFrame('tek satır')).toBe(false)
    expect(isCodeFrame(null)).toBe(false)
    // uzun satır düzenli ifadeye girmez (sınır 400)
    expect(isCodeFrame('a\n' + ' '.repeat(5000) + '^')).toBe(false)
  })

  it('O7: hatalı kova KIRMIZI tonla (yeşille karıştırılmaz); hatasız soluk yeşil', () => {
    expect(cellBackground(0, 0)).toBe('color-mix(in srgb, var(--success) 30%, transparent)')
    expect(cellBackground(4, 33)).toBe('color-mix(in srgb, var(--danger) 35%, transparent)')
    expect(cellBackground(12, 100)).toBe('color-mix(in srgb, var(--danger) 100%, transparent)')
    expect(cellBackground(1, 60)).not.toContain('--success')
  })

  it('E10: sıfır değerli fark kutucuğu nötr', () => {
    expect(diffTileTone('FLAKY', 0)).toBe('total')
    expect(diffTileTone('REGRESSION', 0)).toBe('total')
    expect(diffTileTone('FLAKY', 2)).toBe('warning')
    expect(diffTileTone('REGRESSION', 1)).toBe('critical')
    expect(diffTileTone('X', 1)).toBe('total')
  })
})

describe('checkFailureModel — sentetik (k6) hata sınıfı', () => {
  it('sağlık: yalnız PASS; durum yoksa ok bayrağı', () => {
    expect(failureOf('scripted', { status: 'PASS' })).toBeNull()
    expect(failureOf('scripted', { ok: true })).toBeNull()
    expect(failureOf('scripted', { status: 'FAIL', failure_reason: 'CONNECT_REFUSED' }))
      .toMatchObject({ code: 'CONNECT_REFUSED', legacy: false, phase: 'CONNECT' })
  })
  it('eski satır: en yakın aday kod (katalog dışıysa failureOf UNKNOWN\'a düşer)', () => {
    expect(scriptedLegacyCode({ status: 'TIMEOUT' })).toBe('SCRIPT_PROCESS_TIMEOUT')
    expect(scriptedLegacyCode({ status: 'FAIL', exit_code: 99 })).toBe('SCRIPT_THRESHOLD_FAILED')
    expect(scriptedLegacyCode({ status: 'FAIL', checks_failed: 2 })).toBe('SCRIPT_CHECK_FAILED')
    expect(scriptedLegacyCode({ status: 'NO_CHECKS' })).toBe('SCRIPT_NO_CHECKS')
    expect(scriptedLegacyCode({ status: 'ERROR', exit_code: 108 })).toBe('SCRIPT_ABORTED')
    // ağ metni bağlam satırından AYRI okunur ("timeout'u" bağlam kelimesi zaman aşımı sayılmaz)
    expect(scriptedLegacyCode({ status: 'ERROR', error: 'connection refused\nsüreç bütçesi=60 sn, script istek timeout\'u=20s' })).toBe('CONNECT_REFUSED')
    expect(scriptedLegacyCode({ status: 'ERROR', error: 'TypeError\nsüreç bütçesi=60 sn, script istek timeout\'u=20s' })).toBe('SCRIPT_EXCEPTION')
    expect(scriptedLegacyCode({ status: 'FAIL', error: 'garip' })).toBe('UNKNOWN')
    const f = failureOf('scripted', { status: 'ERROR', error: 'connection refused' })
    expect(f).toMatchObject({ code: 'CONNECT_REFUSED', legacy: true })
  })
  it('ayrıntı satırları: adım, k6 kodu, düşen eşik; hedef senaryonun host\'u', () => {
    const row = { status: 'FAIL', failure_reason: 'CONNECT_REFUSED',
      failure_detail: '{"step":"Giriş › OTP","error_code":1210,"threshold":"http_req_duration p(95)<500"}' }
    const f = failureOf('scripted', row, { env: [{ name: 'BASE_URL', value: 'https://ibank.example.com' }] })
    const rows = detailRows('scripted', row, { env: [{ name: 'BASE_URL', value: 'https://ibank.example.com' }] }, t, f)
    expect(rows.map((r) => [r.key, r.value])).toEqual(expect.arrayContaining([
      ['target', 'ibank.example.com'], ['step', 'Giriş › OTP'], ['k6Code', '1210'], ['threshold', 'http_req_duration p(95)<500'],
    ]))
  })
})
