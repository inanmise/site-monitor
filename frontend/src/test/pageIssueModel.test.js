import { describe, it, expect } from 'vitest'
import { issueReasonKey, issuesForCheckedAt, issuesForCheck, expectedIssueCount } from '../components/page/pageIssueModel.js'

describe('pageIssueModel — issueReasonKey (E4: neden sınıfı)', () => {
  it('BROKEN HTTP durumuna göre incelir', () => {
    expect(issueReasonKey({ issue_type: 'BROKEN', http_status: 404 })).toBe('BROKEN_404')
    expect(issueReasonKey({ issue_type: 'BROKEN', http_status: 410 })).toBe('BROKEN_404')
    expect(issueReasonKey({ issue_type: 'BROKEN', http_status: 500 })).toBe('BROKEN_5XX')
    expect(issueReasonKey({ issue_type: 'BROKEN', http_status: 503 })).toBe('BROKEN_5XX')
    expect(issueReasonKey({ issue_type: 'BROKEN', http_status: 403 })).toBe('BROKEN_4XX')
    expect(issueReasonKey({ issue_type: 'BROKEN', http_status: 302 })).toBe('BROKEN_3XX')
    expect(issueReasonKey({ issue_type: 'BROKEN', http_status: 200 })).toBe('BROKEN_OTHER')
    expect(issueReasonKey({ issue_type: 'BROKEN', http_status: null })).toBe('BROKEN_NO_RESPONSE')
    expect(issueReasonKey({ issue_type: 'BROKEN' })).toBe('BROKEN_NO_RESPONSE')
  })
  it('diğer sorun türleri doğrudan eşlenir', () => {
    expect(issueReasonKey({ issue_type: 'TIMEOUT' })).toBe('TIMEOUT')
    expect(issueReasonKey({ issue_type: 'MIXED_CONTENT' })).toBe('MIXED_CONTENT')
    expect(issueReasonKey({ issue_type: 'BLOCKED' })).toBe('BLOCKED')
    expect(issueReasonKey({ issue_type: 'SLOW' })).toBe('SLOW')
    expect(issueReasonKey({ issue_type: 'WAT' })).toBe('OTHER')
    expect(issueReasonKey(null)).toBe('OTHER')
  })
})

describe('pageIssueModel — issuesForCheckedAt (E1: kontrol eşleştirme)', () => {
  const issues = [
    { id: 1, checked_at: '2026-11-11T09:47:31', resource_url: 'a' },
    { id: 2, checked_at: '2026-11-11T09:47:31', resource_url: 'b' },
    { id: 3, checked_at: '2026-11-11T09:55:03', resource_url: 'c' },
  ]
  it('checked_at birebir eşleşenleri döndürür', () => {
    expect(issuesForCheckedAt(issues, '2026-11-11T09:47:31').map(r => r.resource_url)).toEqual(['a', 'b'])
    expect(issuesForCheckedAt(issues, '2026-11-11T09:55:03').map(r => r.resource_url)).toEqual(['c'])
  })
  it('eşleşme yok / geçersiz giriş → boş', () => {
    expect(issuesForCheckedAt(issues, '2026-01-01T00:00:00')).toEqual([])
    expect(issuesForCheckedAt(null, 'x')).toEqual([])
    expect(issuesForCheckedAt(issues, null)).toEqual([])
  })
})

describe('pageIssueModel — 2026-11-13 hata taraması', () => {
  it('bulgu 8: durum kodlu BLOCKED (401/403/429/451/503, diğer 4xx) ayrı neden; kodsuz BLOCKED (SSRF / DNS) eskisi', () => {
    for (const s of [401, 403, 429, 451, 503, 400, 405]) {
      expect(issueReasonKey({ issue_type: 'BLOCKED', http_status: s })).toBe('BLOCKED_HTTP')
    }
    expect(issueReasonKey({ issue_type: 'BLOCKED', http_status: null })).toBe('BLOCKED')
    expect(issueReasonKey({ issue_type: 'BLOCKED' })).toBe('BLOCKED')
  })

  it('bulgu 7: kontrol kimliğiyle eşleşir (aynı saniyedeki başka kontrol karışmaz); kimliksiz satır checked_at ile', () => {
    const at = '2026-11-11T09:47:31'
    const rows = [
      { id: 1, check_id: 501, checked_at: at, resource_url: 'a' },
      { id: 2, check_id: 502, checked_at: at, resource_url: 'b' },
      { id: 3, checked_at: at, resource_url: 'eski' },
    ]
    expect(issuesForCheck(rows, { id: 501, checked_at: at }).map(r => r.resource_url)).toEqual(['a', 'eski'])
    expect(issuesForCheck(rows, { checked_at: at }).map(r => r.resource_url)).toEqual(['a', 'b', 'eski'])
    expect(issuesForCheck(null, { id: 1 })).toEqual([])
    expect(issuesForCheck(rows, null)).toEqual([])
  })

  it('bulgu 7: beklenen EN AZ sorun sayısı sayaçlardan; sayaç yoksa bilinmez (null, "eksik" denmez)', () => {
    expect(expectedIssueCount({ broken_resources: 1, timeout_count: 4, mixed_content_count: 0 })).toBe(5)
    expect(expectedIssueCount({ broken_resources: 2, timeout_count: null })).toBe(2)
    expect(expectedIssueCount({})).toBeNull()
    expect(expectedIssueCount(null)).toBeNull()
  })
})
