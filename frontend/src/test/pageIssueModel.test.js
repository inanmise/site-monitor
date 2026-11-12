import { describe, it, expect } from 'vitest'
import { issueReasonKey, issuesForCheckedAt } from '../components/page/pageIssueModel.js'

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
