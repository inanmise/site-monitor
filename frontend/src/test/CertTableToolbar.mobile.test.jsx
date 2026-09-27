import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'

/**
 * Tüm Sertifikalar süzgeç çubuğu — TELEFON davranışı (2026-09-27): facet'ler satırda değil, "Süzgeçler (n)" düğmesiyle açılan
 * Sheet'te; arama satırda kalır; araç düğmeleri ikon + erişilebilir ad. `useIsMobile` modül düzeyinde mock'landığı için ayrı dosya.
 */
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => true }))

import CertTableToolbar from '../components/certtable/CertTableToolbar.jsx'
import { EMPTY_FILTERS, defaultCols } from '../components/certtable/certTableModel.js'

const FACETS = { all: 42, teams: [{ id: 1, name: 'Takım A', count: 5 }], no_team: 0, levels: {}, windows: {}, tiers: {}, insecure: 0, nonstd_port: 0 }
function renderBar(filters) {
  const props = {
    filters: { ...EMPTY_FILTERS, ...filters }, onFilter: vi.fn(), onReset: vi.fn(), facets: FACETS, cols: defaultCols(), onCols: vi.fn(),
    density: 'compact', onDensity: vi.fn(), sortBy: 'priority|asc', onSort: vi.fn(), presets: [], onSavePreset: vi.fn(),
    onApplyPreset: vi.fn(), onDeletePreset: vi.fn(), exportUrl: '/x.csv', total: 3, onColFilters: vi.fn(),
  }
  render(<CertTableToolbar {...props} />)
  return props
}

describe('CertTableToolbar — telefon', () => {
  it('facet\'ler satırda değil; "Süzgeçler" tetiği metin alanları HARİÇ aktif facet sayısını taşır; Sheet facet kontrollerini açar; Tümünü temizle sıfırlar', () => {
    const p = renderBar({ team: '1', tier: '1', domain: 'ex' })
    expect(document.querySelector('[data-slot="ct-facets"]')).toBeNull()
    expect(screen.getByRole('searchbox', { name: /alan adı ara|search domain/i })).toBeInTheDocument()   // arama satırda
    const trigger = document.querySelector('[data-slot="ct-filters-trigger"]')
    expect(trigger.textContent).toMatch(/2$/)   // team + tier (domain sayılmaz)
    fireEvent.click(trigger)
    const sheet = screen.getByRole('dialog', { name: /süzgeçler|filters/i })
    expect(within(sheet).getByLabelText(/sorumlu takım|owning team/i)).toHaveAttribute('role', 'combobox')
    expect(sheet.querySelector('[data-slot="ct-facets"]')).not.toBeNull()
    fireEvent.click(within(sheet).getByRole('button', { name: /tümünü temizle|clear all/i }))
    expect(p.onReset).toHaveBeenCalled()
  })

  it('araç düğmeleri ikon + erişilebilir ad (Sütunlar, Ön ayarlar, CSV) — ad aria-label\'dan gelir', () => {
    renderBar({})
    expect(screen.getByRole('button', { name: /sütunlar|columns/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /ön ayarlar|presets/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /csv/i })).toBeInTheDocument()
  })
})
