import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import CertificatesTable from '../components/CertificatesTable.jsx'

/**
 * Tüm Sertifikalar — "TLS notu" sütunu (2026-10-10): rozet sütunda, notsuz satırda tire; dağılım şeridindeki çip ve
 * sütun süzgeci sunucuya `filter_grade` gönderir, çip + adres (`c_gr`) taşır; rozete dokunmak satırı açmaz, "TLS notu
 * ayrıntısı" pencerenin Sağlık sekmesini açar; başlık tıklaması `sort_by=tls_grade`.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ getCertificatesPaginated: vi.fn(), getTlsGradeDrops: vi.fn() }),
  formatDate: (s) => s || 'N/A',
  formatDateSec: (s) => s || '',
}))
import { api } from '../api/client'

const FACETS = { all: 3, levels: {}, windows: {}, teams: [], no_team: 0, insecure: 0, tiers: {}, nonstd_port: 0,
  trust: {}, grades: { 'A+': 1, A: 0, B: 1, C: 0, D: 0, F: 0, none: 1 } }
const row = (over) => ({
  domain: 'x.example.com', issuer_cn: 'Example CA', subject: 'CN=x', not_after: '2027-01-01T00:00:00', days_remaining: 200,
  warning: false, status: 'valid', alert_level: 'valid', checked_at: '2026-10-09T08:00:00', fingerprint: 'FP', ...over,
})
const ROWS = [
  row({ domain: 'best.example.com', tls_grade: 'A+' }),
  row({ domain: 'old.example.com', tls_grade: 'B', tls_grade_reasons: ['TLS10_ENABLED'], tls_grade_drop: { from: 'A', to: 'B', at: '2026-10-09T10:00:00' } }),
  row({ domain: 'upload-key', cert_source: 'MANUAL' }),
]
const page = (data) => ({ success: true, data, facets: FACETS, pagination: { current_page: 1, total: data.length, total_pages: 1 } })

describe('CertificatesTable — TLS notu sütunu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
    api.getCertificatesPaginated.mockImplementation((q) => Promise.resolve(page(q.filter_grade === 'B' ? [ROWS[1]] : ROWS)))
  })
  afterEach(() => { localStorage.clear(); window.history.replaceState(null, '', '/') })

  it('rozet sütunda; notsuz satırda tire; rozete dokunmak satırı açmaz, ayrıntı Sağlık sekmesini açar', async () => {
    const onRowClick = vi.fn()
    render(<CertificatesTable onRowClick={onRowClick} />)
    await screen.findByText('old.example.com')
    const cell = (d) => document.querySelector(`tr[data-domain="${d}"] td[data-label="TLS grade"]`)
    expect(cell('best.example.com').querySelector('[data-slot="tls-grade"]')).toHaveAttribute('data-grade', 'A+')
    expect(cell('upload-key').querySelector('[data-slot="tls-grade"]')).toBeNull()
    expect(cell('upload-key')).toHaveTextContent('—')
    const trigger = within(cell('old.example.com')).getByRole('button', { name: /TLS grade B/ })
    expect(trigger.querySelector('[data-slot="tls-grade"]')).toHaveAttribute('data-dropped', 'true')
    fireEvent.click(trigger)
    expect(onRowClick).not.toHaveBeenCalled()
    fireEvent.click(await screen.findByRole('button', { name: /TLS grade details/ }))
    expect(onRowClick).toHaveBeenCalledWith('old.example.com', 'health')
  })

  it('dağılım çipi süzer: filter_grade=B, çip + adres c_gr; ikinci basış kaldırır', async () => {
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('old.example.com')
    fireEvent.click(screen.getByRole('button', { name: 'Grade B: 1 certificates — filter' }))
    await waitFor(() => expect(api.getCertificatesPaginated.mock.calls.at(-1)[0].filter_grade).toBe('B'))
    await waitFor(() => expect(document.querySelectorAll('tr[data-domain]')).toHaveLength(1))
    expect(document.querySelector('[data-filter-chip="grade"]')).toHaveTextContent('TLS grade: B')
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('c_gr')).toBe('B'))
    fireEvent.click(screen.getByRole('button', { name: 'Grade B: 1 certificates — filter' }))
    await waitFor(() => expect(api.getCertificatesPaginated.mock.calls.at(-1)[0].filter_grade).toBeUndefined())
  })

  it('adresteki c_gr açılışta süzgeç olur; geçersiz değer yok sayılır', async () => {
    window.history.replaceState(null, '', '/?c_gr=B')
    const { unmount } = render(<CertificatesTable onRowClick={() => {}} />)
    await waitFor(() => expect(api.getCertificatesPaginated.mock.calls.at(-1)[0].filter_grade).toBe('B'))
    unmount()
    window.history.replaceState(null, '', '/?c_gr=Z')
    render(<CertificatesTable onRowClick={() => {}} />)
    await waitFor(() => expect(api.getCertificatesPaginated).toHaveBeenCalled())
    expect(api.getCertificatesPaginated.mock.calls.at(-1)[0].filter_grade).toBeUndefined()
  })

  it('başlık tıklaması sunucu sıralamasına sort_by=tls_grade gönderir', async () => {
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('old.example.com')
    fireEvent.click(within(document.querySelector('thead th[data-col="grade"]')).getByRole('button'))
    await waitFor(() => expect(api.getCertificatesPaginated.mock.calls.at(-1)[0].sort_by).toBe('tls_grade'))
  })
})
