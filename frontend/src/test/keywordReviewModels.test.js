import { describe, it, expect } from 'vitest'
import { CONDITION_CODES, changeNoteOf, changeState, nonRuleFailure, verdictOf } from '../components/keyword/keywordCardModel.js'
import { ruleOutcomeOf } from '../components/keyword/keywordFailureModel.js'
import { rulesVerdict } from '../components/keyword/rules/RuleTestResults.jsx'
import { fillGaps, matchesOf, shapeContentSeries } from '../components/keyword/detail/contentDetailModel.js'
import { keywordToK6 } from '../components/keyword/k6/keywordToK6.js'

/** Keyword son inceleme (kw-review-fe.md) — saf model düzeltmeleri. */

const t = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)

describe('ruleOutcomeOf — kural sonuçlarının yanındaki genel karar (Y1)', () => {
  it('ok alanı önce; yoksa durumdan; bilinmiyorsa null', () => {
    expect(ruleOutcomeOf({ ok: true }, {}, t)).toEqual({ ok: true, reason: null })
    expect(ruleOutcomeOf({ status: 'up' }, {}, t)).toEqual({ ok: true, reason: null })
    expect(ruleOutcomeOf({ status: 'unknown' }, {}, t)).toEqual({ ok: null, reason: null })
    expect(ruleOutcomeOf(null, {}, t)).toEqual({ ok: null, reason: null })
  })

  it('kapalı + kural DIŞI neden → kısa neden metni; kural nedeninde / kod yokken / bilinmeyen kodda null', () => {
    expect(ruleOutcomeOf({ ok: false, failure_reason: 'HTTP_STATUS', http_status: 503 }, {}, t).reason).toBe('kwfail.HTTP_STATUS.short')
    expect(ruleOutcomeOf({ status: 'down', failure_reason: 'BODY_TOO_SMALL' }, {}, t)).toEqual({ ok: false, reason: 'kwfail.BODY_TOO_SMALL.short' })
    expect(ruleOutcomeOf({ ok: false, failure_reason: 'RULES_NOT_MET' }, {}, t)).toEqual({ ok: false, reason: null })
    expect(ruleOutcomeOf({ ok: false }, {}, t)).toEqual({ ok: false, reason: null })
    expect(ruleOutcomeOf({ ok: false, failure_reason: 'UYDURMA' }, {}, t)).toEqual({ ok: false, reason: null })
  })
})

describe('rulesVerdict', () => {
  it('liste yok / boş → null (hüküm yok); VE hepsi, VEYA biri', () => {
    expect(rulesVerdict(null)).toBeNull()
    expect(rulesVerdict([])).toBeNull()
    const mixed = [{ ok: true }, { ok: false }]
    expect(rulesVerdict(mixed, 'AND')).toBe(false)
    expect(rulesVerdict(mixed, 'OR')).toBe(true)
    expect(rulesVerdict([{ ok: false }, { ok: null }], 'OR')).toBe(false)
  })
})

describe('verdictOf — kart hükmü kural dışı nedenden (O1)', () => {
  const m = { status: 'down', operator: 'GTE', match_count: 1, occurrences: 3, keyword: 'A' }

  it('koşul ailesi dışındaki kod → `other`, sayaç yok, kod taşınır', () => {
    for (const code of ['HTTP_STATUS', 'FINAL_URL_MISMATCH', 'REDIRECT_NOT_FOLLOWED', 'BODY_TOO_SMALL', 'BODY_TOO_LARGE']) {
      const v = verdictOf({ ...m, failure_reason: code })
      expect(v, code).toMatchObject({ kind: 'other', tone: 'bad', count: null, code })
    }
  })

  it('koşul kodları, kod yok (eski satır), sağlıklı ve hata durumu eskisi gibi', () => {
    for (const code of CONDITION_CODES) expect(verdictOf({ ...m, failure_reason: code }).kind, code).not.toBe('other')
    expect(verdictOf({ ...m, occurrences: 0 }).kind).toBe('missing')
    expect(verdictOf({ ...m, status: 'up', failure_reason: 'HTTP_STATUS' }).kind).toBe('found')
    expect(verdictOf({ ...m, status: 'error', failure_reason: 'TIMEOUT_READ' }).kind).toBe('error')
    expect(nonRuleFailure({ failure_reason: '' })).toBe(false)
    expect(nonRuleFailure({})).toBe(false)
  })
})

describe('changeState / changeNoteOf — ölçülemeyen değişim nötr', () => {
  it('bilinen not → skipped (değişti öncelikli); tanınmayan dolu kod → genel `OTHER` (ham kod yok); boş → yok; kapalı → null', () => {
    const on = { change_detect: true, change_baseline: { taken_at: 'x' } }
    expect(changeState({ ...on, change_note: 'EMPTY_BODY' })).toBe('skipped')
    expect(changeState({ ...on, change_note: 'EMPTY_BODY', content_changed: true })).toBe('changed')
    expect(changeState({ ...on, change_note: 'UYDURMA' })).toBe('skipped')
    expect(changeState({ ...on, change_note: null })).toBe('watching')
    expect(changeState({ ...on, change_note: '  ' })).toBe('watching')
    expect(changeState({ change_detect: false, change_note: 'EMPTY_BODY' })).toBeNull()
    expect(changeNoteOf('SELECTOR_NOT_FOUND')).toBe('SELECTOR_NOT_FOUND')
    expect(changeNoteOf('x')).toBe('OTHER')
    expect(changeNoteOf(null)).toBeNull()
    expect(changeNoteOf(5)).toBeNull()
  })
})

describe('İçerik serisi boşlukları (DÜŞÜK-4)', () => {
  it('iki dolu kova arasındaki eksik kovalar NULL satırla doldurulur (çizgi üstünden geçmez)', () => {
    const rows = shapeContentSeries({ bucket_seconds: 3600, points: [
      { ts: '2026-11-05T00:00:00Z', checks: 2, occ_avg: 3, occ_min: 2, occ_max: 4, bytes_avg: 100 },
      { ts: '2026-11-05T03:00:00Z', checks: 1, occ_avg: 5, occ_min: 5, occ_max: 5, bytes_avg: 120 },
    ] })
    expect(rows).toHaveLength(4)
    expect(rows.map((r) => r.occAvg)).toEqual([3, null, null, 5])
    expect(rows[1]).toMatchObject({ checks: 0, gap: true, bytes: null, occBand: null })
    expect(rows[2].t - rows[1].t).toBe(3600_000)
  })

  it('kova süresi bilinmiyorsa / bitişik kovalarda dokunulmaz; tavan aşılmaz', () => {
    const two = [{ t: 0 }, { t: 7200_000 }]
    expect(fillGaps(two, null)).toBe(two)
    expect(fillGaps([{ t: 0 }, { t: 3600_000 }], 3600)).toHaveLength(2)
    const huge = fillGaps([{ t: 0 }, { t: 1e12 }], 1)
    expect(huge.length).toBeLessThanOrEqual(2502)
    expect(huge.at(-1).t).toBe(1e12)
  })
})

describe('matchesOf — notlar (DÜŞÜK-5)', () => {
  it('bilinen not taşınır, bilinmeyen düşer', () => {
    const d = matchesOf({ rules: [{ id: 'r1', count: 0, note: 'SCOPE_INVALID' }, { id: 'r2', count: 1, note: 'X' }] })
    expect(d.rules.map((r) => r.note)).toEqual(['SCOPE_INVALID', null])
  })
})

describe('keywordToK6 — düz yazılan gövde / sorgu notu (DÜŞÜK-10)', () => {
  const row = { id: 1, url: 'https://x.example.com/a', keyword: 'A', operator: 'GTE', match_count: 1 }
  const codes = (r) => keywordToK6(r, { t }).notes.map((n) => n.code)

  it('POST gövdesi ya da adres sorgusu varsa gizli env önerisi; yoksa yok', () => {
    expect(codes(row)).not.toContain('PLAIN_REQUEST_DATA')
    expect(codes({ ...row, url: 'https://x.example.com/a?token=abc' })).toContain('PLAIN_REQUEST_DATA')
    expect(codes({ ...row, url: 'https://x.example.com/a?' })).not.toContain('PLAIN_REQUEST_DATA')
    expect(codes({ ...row, method: 'POST', request_body: '{"p":1}' })).toContain('PLAIN_REQUEST_DATA')
    expect(codes({ ...row, method: 'POST', request_body: '   ' })).not.toContain('PLAIN_REQUEST_DATA')
    expect(codes({ ...row, method: 'GET', request_body: 'x=1' })).not.toContain('PLAIN_REQUEST_DATA')
  })
})
