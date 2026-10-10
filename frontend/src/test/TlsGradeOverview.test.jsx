import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { api } = vi.hoisted(() => ({ api: { getTlsGradeDrops: vi.fn() } }))
vi.mock('../api/client', () => ({ api, formatDateSec: (s) => s || '', formatDate: (s) => s || '' }))

import TlsGradeOverview from '../components/tlsgrade/TlsGradeOverview.jsx'

/**
 * Tüm Sertifikalar → TLS notu dağılımı (2026-10-10): facet sayıları çip olarak, çipe dokununca o nota süzgeç (yeniden
 * dokununca kaldırır), boş not seçilemez; "Son düşüşler" penceresi kapsama özeti + düşüş satırları + dönem seçici.
 */
const FACETS = { grades: { 'A+': 3, A: 10, B: 4, C: 0, D: 0, F: 1, none: 2 } }

beforeEach(() => {
  vi.clearAllMocks()
  api.getTlsGradeDrops.mockResolvedValue({ success: true, data: {
    days: 30,
    rows: [{ domain: 'old.example.com', from: 'A', to: 'C', direction: 'DROP', at: '2026-10-09T10:00:00',
      reasons: ['NO_TLS12'], team_name: 'Payments', current: 'C', recovered: false }],
    coverage: { endpoints: 20, ok: 15, partial: 2, failed: 1, pending: 2, latest_probe_at: '2026-10-10T06:00:00' },
  } })
})

describe('TlsGradeOverview', () => {
  it('facet yoksa çizilmez', () => {
    const { container } = render(<TlsGradeOverview facets={{}} />)
    expect(container.querySelector('[data-slot="tls-grade-overview"]')).toBeNull()
  })

  it('çipler sayılarla; dokununca süzer, seçiliyken kaldırır; sayısı 0 olan çip devre dışı', () => {
    const onSelect = vi.fn()
    const { rerender } = render(<TlsGradeOverview facets={FACETS} value="" onSelect={onSelect} />)
    const b = screen.getByRole('button', { name: 'Grade B: 4 certificates — filter' })
    expect(b).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(b)
    expect(onSelect).toHaveBeenLastCalledWith('B')
    expect(screen.getByRole('button', { name: 'Grade C: 0 certificates — filter' })).toBeDisabled()
    rerender(<TlsGradeOverview facets={FACETS} value="B" onSelect={onSelect} />)
    fireEvent.click(screen.getByRole('button', { name: 'Grade B: 4 certificates — filter' }))
    expect(onSelect).toHaveBeenLastCalledWith('')
    fireEvent.click(screen.getByRole('button', { name: 'Not graded: 2 certificates — filter' }))
    expect(onSelect).toHaveBeenLastCalledWith('none')
    expect(screen.getByText('18 graded certificates')).toBeInTheDocument()
  })

  it('"Son düşüşler" penceresi: kapsama, satır, dönem değişince yeniden ister, satırdan sertifika açılır', async () => {
    const onOpenCert = vi.fn()
    render(<TlsGradeOverview facets={FACETS} onOpenCert={onOpenCert} />)
    fireEvent.click(screen.getByRole('button', { name: /Recent drops/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Endpoints whose TLS grade dropped' })
    await waitFor(() => expect(dialog.querySelector('[data-slot="tls-grade-drop-row"]')).not.toBeNull())
    expect(api.getTlsGradeDrops).toHaveBeenLastCalledWith(30)
    expect(dialog.querySelector('[data-slot="tls-grade-coverage"]')).toHaveTextContent('17/20 endpoints scanned · 1 failed · 2 pending')
    const row = dialog.querySelector('[data-slot="tls-grade-drop-row"]')
    expect(row).toHaveTextContent('old.example.com')
    expect(row).toHaveTextContent('TLS 1.2 is not supported')
    fireEvent.click(within(dialog).getByRole('button', { name: '7 days' }))
    await waitFor(() => expect(api.getTlsGradeDrops).toHaveBeenLastCalledWith(7))
    fireEvent.click(await within(dialog).findByRole('button', { name: 'old.example.com — Open certificate' }))
    expect(onOpenCert).toHaveBeenCalledWith('old.example.com')
  })

  it('düşüş yoksa olumlu boş durum', async () => {
    api.getTlsGradeDrops.mockResolvedValue({ success: true, data: { days: 30, rows: [], coverage: null } })
    render(<TlsGradeOverview facets={FACETS} />)
    fireEvent.click(screen.getByRole('button', { name: /Recent drops/ }))
    expect(await screen.findByText('No grade drops in the last 30 days.')).toBeInTheDocument()
  })
})
