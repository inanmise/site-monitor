import { describe, it, expect } from 'vitest'
import { render, screen } from './test-utils.jsx'
import PageIssueList from '../components/page/PageIssueList.jsx'

describe('PageIssueList (E1/E2/E4: sorunlu kaynak detayı)', () => {
  it('kaynak URL, HTTP durumu, bulunduğu sayfa (link) ve neden/ne yapmalı gösterir', () => {
    render(<PageIssueList issues={[{
      id: 1, resource_type: 'JS', resource_url: 'https://x/a.js', source_page: 'https://x/urunler',
      issue_type: 'BROKEN', first_party: true, http_status: 404, duration_ms: 12,
    }]} />)
    expect(screen.getByText('https://x/a.js')).toBeTruthy()
    expect(screen.getByText('HTTP 404')).toBeTruthy()
    const link = screen.getByRole('link', { name: /urunler/ })
    expect(link.getAttribute('href')).toBe('https://x/urunler')
    // E4: 404 → "bulunamadı / not found" nedeni (dil-bağımsız)
    expect(document.querySelector('[data-slot="page-issue-row"]').textContent).toMatch(/bulunamad|not found/i)
  })

  it('3P rozeti YALNIZ açıkça üçüncü-taraf (first_party === false) için; bilinmiyorsa yok', () => {
    const { rerender } = render(<PageIssueList issues={[{
      id: 1, resource_type: 'IMG', resource_url: 'u', issue_type: 'BROKEN', first_party: false, http_status: 404,
    }]} />)
    expect(screen.getByText('·3P')).toBeTruthy()
    rerender(<PageIssueList issues={[{
      id: 2, resource_type: 'IMG', resource_url: 'u2', issue_type: 'BROKEN', http_status: 404,   // first_party yok (E2 bağlam)
    }]} />)
    expect(screen.queryByText('·3P')).toBeNull()
  })

  it('"… +N daha" sayaç satırı (E2 bağlam) kaynak satırı DEĞİL', () => {
    render(<PageIssueList issues={[{ more: '… +3 daha' }]} />)
    expect(screen.getByText('… +3 daha')).toBeTruthy()
    expect(document.querySelector('[data-slot="page-issue-row"]')).toBeNull()
  })

  it('max üstü kaynakta "+N kaynak daha" sayacı', () => {
    const many = Array.from({ length: 5 }, (_, i) => ({
      id: i, resource_type: 'IMG', resource_url: 'u' + i, issue_type: 'TIMEOUT',
    }))
    render(<PageIssueList issues={many} max={2} />)
    expect(document.querySelectorAll('[data-slot="page-issue-row"]').length).toBe(2)
    expect(screen.getByText(/\+3/)).toBeTruthy()
  })

  it('boş / geçersiz liste → hiçbir şey çizmez', () => {
    const { container, rerender } = render(<PageIssueList issues={[]} />)
    expect(container.querySelector('[data-slot="page-issue-list"]')).toBeNull()
    rerender(<PageIssueList issues={null} />)
    expect(container.querySelector('[data-slot="page-issue-list"]')).toBeNull()
  })
})
