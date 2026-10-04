import { describe, it, expect } from 'vitest'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'
import {
  cardReason, detailRows, excerptHasKeyword, failureTexts, hintTexts, hintsOf, reasonOf, reasonTone,
} from '../components/keyword/keywordFailureModel.js'
import {
  KEYWORD_DIAG_PATH, KW_FAILURE_CODES, KW_FINDING_CODES, KW_HINT_CODES,
} from '../components/keyword/diagnose/keywordDiagCodes.js'
import { findingText, failureKind } from '../components/http/diagnose/httpDiagnoseModel.js'

/** useT ile aynı konumsal yer tutucu kuralı ({0}, {1} …) — gerçek sözlükten. */
const tOf = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const t = tOf(EN)
const tTr = tOf(TR)

const MON = { keyword: 'Kampanya', operator: 'GTE', match_count: 1, url: 'https://shop.example.com/', timeout_ms: 8000 }

/**
 * Keyword kontrol geçmişi hata teşhisi modeli (2026-10-04): sunucu kodu → kullanıcının dilinde kısa etiket + neden /
 * etkisi / ne yapmalı; eski satırdan EN YAKIN neden; ipucu süzme; kart çipi; kod katalogları backend ile aynı ve her
 * kodun TR + EN metni var (ham anahtar ekrana düşmez).
 */
describe('keywordFailureModel', () => {
  it('sunucu kodu: kısa etiket + üç metin adlı parametrelerle dolu (ham anahtar / yer tutucu kalmaz)', () => {
    const row = { ok: false, failure_reason: 'KEYWORD_NOT_FOUND', http_status: 200, occurrences: 0, body_bytes: 15360 }
    const f = failureTexts(row, MON, t)
    expect(f).toMatchObject({ code: 'KEYWORD_NOT_FOUND', legacy: false, tone: 'danger', short: 'Keyword not found' })
    expect(f.why).toContain('« Kampanya »')
    expect(f.why).toContain('HTTP 200')
    expect(f.why).toContain('15 KB')
    expect(f.why).toContain('at least 1 time')
    for (const s of [f.short, f.why, f.effect, f.fix]) expect(s).not.toMatch(/\{[a-z_]+\}|kwfail\./)
    const tr = failureTexts({ ...row, failure_reason: 'HTTP_STATUS', http_status: 503 }, MON, tTr)
    expect(tr.short).toBe('HTTP 503 döndü')
  })

  it('başarılı satırda neden yok', () => {
    expect(reasonOf({ ok: true }, MON)).toBeNull()
    expect(failureTexts({ ok: true }, MON, t)).toBeNull()
  })

  it('eski satır (kod yok): hata metninden / durum kodundan / adetten EN YAKIN neden, legacy=true', () => {
    expect(reasonOf({ ok: false, error: 'HTTP connect timed out' }, MON)).toEqual({ code: 'TIMEOUT_READ', legacy: true })
    expect(reasonOf({ ok: false, error: 'çözümlenemeyen host: x.example' }, MON)).toEqual({ code: 'DNS', legacy: true })
    expect(reasonOf({ ok: false, error: 'garip bir şey' }, MON)).toEqual({ code: 'UNKNOWN', legacy: true })
    expect(reasonOf({ ok: false, http_status: 503, occurrences: 0 }, MON)).toEqual({ code: 'HTTP_STATUS', legacy: true })
    expect(reasonOf({ ok: false, http_status: 200, occurrences: 0 }, MON)).toEqual({ code: 'KEYWORD_NOT_FOUND', legacy: true })
    expect(reasonOf({ ok: false, http_status: 200, occurrences: 2 }, { ...MON, operator: 'LTE', match_count: 0 }))
      .toEqual({ code: 'KEYWORD_FOUND_FORBIDDEN', legacy: true })
    expect(reasonOf({ ok: false, http_status: 200, occurrences: 1 }, { ...MON, match_count: 3 }))
      .toEqual({ code: 'KEYWORD_COUNT_MISMATCH', legacy: true })
    // bilinmeyen sunucu kodu da eski kural gibi davranır (ham anahtar çizilmez)
    expect(reasonOf({ ok: false, failure_reason: 'SOMETHING_NEW', http_status: 200, occurrences: 0 }, MON).code).toBe('KEYWORD_NOT_FOUND')
  })

  it('ton: ayar/politika kökenli nedenler uyarı, diğerleri tehlike', () => {
    expect(reasonTone('CONFIG_ERROR')).toBe('warning')
    expect(reasonTone('SSRF_BLOCKED')).toBe('warning')
    expect(reasonTone('BODY_TRUNCATED')).toBe('warning')
    expect(reasonTone('DNS')).toBe('danger')
  })

  it('ipuçları: bilinmeyen kod süzülür; metinler doludur', () => {
    expect(hintsOf({ hints: ['LOGIN_PAGE', 'X_UNKNOWN', 'JS_RENDERED'] })).toEqual(['LOGIN_PAGE', 'JS_RENDERED'])
    expect(hintsOf({})).toEqual([])
    const h = hintTexts('REDIRECTED_ELSEWHERE', { final_url: 'https://portal.example.net/x', redirect_count: 2 }, MON, t)
    expect(h.cause).toContain('portal.example.net')
    expect(h.cause).toContain('2 redirects')
  })

  it('ayrıntı satırları: yalnız kayıtta olanlar (eski satırda kısa liste, uydurma yok)', () => {
    const full = detailRows({ ok: false, http_status: 200, occurrences: 0, final_url: 'https://shop.example.com/giris',
      redirect_count: 1, content_type: 'text/html', body_bytes: 2_000_000, body_truncated: true, charset: 'UTF-8',
      response_ms: 312, via: 'proxy' }, MON, t)
    expect(full.map((r) => r.key)).toEqual(['status', 'count', 'finalUrl', 'contentType', 'size', 'charset', 'responseMs', 'route'])
    expect(full.find((r) => r.key === 'size')).toMatchObject({ tone: 'warn', sub: 'cut at the 2 MB read limit' })
    expect(full.find((r) => r.key === 'route').value).toBe('Proxy')
    const legacy = detailRows({ ok: false, error: 'x', http_status: null }, MON, t)
    expect(legacy.map((r) => r.key)).toEqual(['status'])
    expect(legacy[0]).toMatchObject({ value: 'no response', tone: 'bad' })
  })

  it('kart nedeni: istek hatasında kısa neden; koşul ailesinde ilk ipucu; eski satırda null', () => {
    expect(cardReason({ status: 'error', failure_reason: 'TIMEOUT_READ', keyword: 'x' }, t)).toMatchObject({ label: 'Response timed out', hint: false })
    expect(cardReason({ status: 'down', failure_reason: 'KEYWORD_NOT_FOUND', hints: ['LOGIN_PAGE'] }, t))
      .toMatchObject({ code: 'LOGIN_PAGE', label: 'Sign-in page returned', hint: true, tone: 'warning' })
    expect(cardReason({ status: 'down', failure_reason: 'KEYWORD_NOT_FOUND', hints: [] }, t)).toBeNull()
    expect(cardReason({ status: 'down', failure_reason: 'EMPTY_BODY' }, t)).toMatchObject({ label: 'Empty response' })
    expect(cardReason({ status: 'down', error: 'x' }, t)).toBeNull()
    expect(cardReason({ status: 'up', failure_reason: 'DNS' }, t)).toBeNull()
  })

  it('alıntıda anahtar kelime: harf kuralıyla; boşta null', () => {
    expect(excerptHasKeyword('Hoş geldiniz KAMPANYA', 'kampanya')).toBe(true)
    expect(excerptHasKeyword('Hoş geldiniz KAMPANYA', 'kampanya', true)).toBe(false)
    expect(excerptHasKeyword('', 'x')).toBeNull()
  })

  it('kataloglar backend ile aynı boyutta ve her kodun TR + EN metni var', () => {
    expect(KW_FAILURE_CODES).toHaveLength(21)
    expect(KW_HINT_CODES).toHaveLength(10)
    expect(KW_FINDING_CODES).toHaveLength(10)
    for (const dict of [TR, EN]) {
      for (const c of KW_FAILURE_CODES) for (const p of ['short', 'why', 'effect', 'fix']) expect(dict[`kwfail.${c}.${p}`], `kwfail.${c}.${p}`).toBeTruthy()
      for (const c of KW_HINT_CODES) for (const p of ['title', 'cause', 'effect', 'fix']) expect(dict[`kwhint.${c}.${p}`], `kwhint.${c}.${p}`).toBeTruthy()
      for (const c of KW_FINDING_CODES) for (const p of ['title', 'body']) expect(dict[`kwdx.finding.${c}.${p}`], `kwdx.finding.${c}.${p}`).toBeTruthy()
    }
  })

  it('ortak bulgu çevirisi: keyword kodları kwdx/kwhint ad alanından, HTTP kodları değişmeden; PATH_DIFFERS keyword varyantı', () => {
    expect(findingText({ code: 'KEYWORD_NOT_FOUND', params: { keyword: 'Kampanya', status: 200, bytes: 10, expected: 'at least 1 time' } }, t))
      .toMatchObject({ known: true, title: 'Keyword not on the page' })
    expect(findingText({ code: 'LOGIN_PAGE', params: {} }, t)).toMatchObject({ title: 'Sign-in page returned' })
    expect(findingText({ code: 'DNS_FAIL', params: { host: 'x' } }, t).title).toBe(EN['httpdx.finding.DNS_FAIL.title'])
    const pd = findingText({ code: 'PATH_DIFFERS', params: { failing_route: 'proxy', working_route: 'direct', working_status: 200, reason: 'keyword' } }, t)
    expect(pd.title).toBe(EN['httpdx.finding.PATH_DIFFERS.title.keyword'])
    expect(findingText({ code: 'PATH_DIFFERS', params: {} }, t).title).toBe(EN['httpdx.finding.PATH_DIFFERS.title'])
  })

  it('hız sınırı tespiti uç yoluna göre: keyword 429 HTTP penceresini, HTTP 429 keyword penceresini etkilemez', () => {
    const kwFail = [{ path: '/api/monitoring/keyword/3/diagnose', status: 429 }]
    expect(failureKind({ success: false }, kwFail, KEYWORD_DIAG_PATH)).toBe('rateLimited')
    expect(failureKind({ success: false, status: 500 }, kwFail)).toBe('other')
  })
})
