import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import CertFilterRow from '../components/certtable/CertFilterRow.jsx'
import { EMPTY_FILTERS } from '../components/certtable/certTableModel.js'

/** Tüm Sertifikalar kolon süzgeç satırı (2026-09-22): hücre sayısı = seçim + kolonlar + işlem; sunucu süzgeçlerine eşleme. */
describe('CertFilterRow', () => {
  const cols = ['domain', 'issuer', 'expiry', 'days', 'status', 'trust', 'checked']
  const facets = { teams: [{ id: 5, name: 'Takım A', count: 3 }], no_team: 1, tiers: { 1: 4 }, insecure: 2 }

  it('hücreler başlıkla hizalı; sunucunun süzemediği kolon boş; domain metni ve pencere/durum/güven seçimleri filtreye yazar', () => {
    const onFilter = vi.fn()
    render(<table><thead><CertFilterRow filters={EMPTY_FILTERS} onFilter={onFilter} cols={cols} facets={facets} showSelect pageRows={[{ port: 443 }, { port: 8443 }]} /></thead></table>)
    const row = screen.getByTestId('ct-filter-row')
    expect(row.children).toHaveLength(1 + cols.length + 1)
    expect(row.querySelector('[data-col="expiry"]').children).toHaveLength(0)
    expect(row.querySelector('[data-col="checked"]').children).toHaveLength(0)
    fireEvent.change(screen.getByPlaceholderText(/Domain ara|Search domain/), { target: { value: 'juz' } })
    expect(onFilter).toHaveBeenLastCalledWith({ ...EMPTY_FILTERS, domain: 'juz' })
    // Kalan gün → window
    fireEvent.mouseDown(row.querySelector('[data-col="days"] button[role="combobox"]'))
    fireEvent.mouseDown(screen.getByText(/≤ 90/))
    expect(onFilter).toHaveBeenLastCalledWith({ ...EMPTY_FILTERS, window: '90' })
    // Güven → insecure boolean
    fireEvent.mouseDown(row.querySelector('[data-col="trust"] button[role="combobox"]'))
    fireEvent.mouseDown(screen.getByText(/güvensiz|insecure/i))
    expect(onFilter).toHaveBeenLastCalledWith({ ...EMPTY_FILTERS, insecure: true })
  })

  it('takım seçenekleri facet\'ten (sayı ile) + Takımsız; port seçenekleri sayfadaki satırlardan', () => {
    render(<table><thead><CertFilterRow filters={EMPTY_FILTERS} onFilter={() => {}} cols={['domain', 'team', 'port']} facets={facets} showSelect={false} pageRows={[{ port: 443 }, { port: 8443 }]} /></thead></table>)
    const row = screen.getByTestId('ct-filter-row')
    fireEvent.mouseDown(row.querySelector('[data-col="team"] button[role="combobox"]'))
    expect(screen.getByText('Takım A (3)')).toBeInTheDocument()
    expect(screen.getByText(/Takımsız \(1\)|No team \(1\)/)).toBeInTheDocument()
    fireEvent.mouseDown(row.querySelector('[data-col="port"] button[role="combobox"]'))
    expect(screen.getByText('8443')).toBeInTheDocument()
  })
})
