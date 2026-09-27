import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import CertTableToolbar from '../components/certtable/CertTableToolbar.jsx'
import { EMPTY_FILTERS, defaultCols } from '../components/certtable/certTableModel.js'

/**
 * Tüm Sertifikalar süzgeç çubuğu (2026-09-27 yeniden tasarım) — masaüstü: baskın arama (temizle × + Esc), ikincil veren
 * kutusu, facet kontrolleri satırda, sayaç rozetli bas-bırak süzgeçler, önekli çipler + "Tümünü temizle" + sonuç sayacı,
 * araç satırı iki gruba (görünüm / eylemler) ayrılmış. Telefon davranışı kardeş dosyada (CertTableToolbar.mobile.test.jsx).
 */
const FACETS = {
  all: 42, teams: [{ id: 1, name: 'Takım A', count: 5 }, { id: 2, name: 'Takım B', count: 3 }], no_team: 0,
  levels: { expired: 1, critical: 2, high: 0, warning: 3, valid: 30, error: 6 }, windows: { expired: 1, 7: 2, 30: 6, 60: 9, 90: 12 },
  tiers: { 1: 4, 2: 8 }, insecure: 1, nonstd_port: 1,
}
function renderBar(over = {}) {
  const props = {
    filters: { ...EMPTY_FILTERS }, onFilter: vi.fn(), onReset: vi.fn(), facets: FACETS, cols: defaultCols(), onCols: vi.fn(),
    density: 'comfortable', onDensity: vi.fn(), sortBy: 'priority|asc', onSort: vi.fn(), presets: [], onSavePreset: vi.fn(),
    onApplyPreset: vi.fn(), onDeletePreset: vi.fn(), exportUrl: '/api/certificates/export.csv', total: 8, onColFilters: vi.fn(),
    ...over,
  }
  render(<CertTableToolbar {...props} />)
  return props
}

describe('CertTableToolbar — masaüstü', () => {
  it('arama: yazınca onFilter(domain); dolu kutuda temizle × ve Esc aramayı boşaltır', () => {
    const p = renderBar({ filters: { ...EMPTY_FILTERS, domain: 'exam' } })
    const box = screen.getByRole('searchbox', { name: /alan adı ara|search domain/i })
    expect(box).toHaveValue('exam')
    // Esc İLK etkileşim: kontrollü kutu prop'tan beslendiği için her iddia çağrı SAYISIYLA sabitlenir (bayat "son çağrı" yok)
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(p.onFilter).toHaveBeenCalledTimes(1)
    expect(p.onFilter).toHaveBeenLastCalledWith(expect.objectContaining({ domain: '' }))
    fireEvent.click(screen.getByRole('button', { name: /aramayı temizle|clear search/i }))
    expect(p.onFilter).toHaveBeenCalledTimes(2)
    expect(p.onFilter).toHaveBeenLastCalledWith(expect.objectContaining({ domain: '' }))
    fireEvent.change(box, { target: { value: 'example' } })
    expect(p.onFilter).toHaveBeenCalledTimes(3)
    expect(p.onFilter).toHaveBeenLastCalledWith(expect.objectContaining({ domain: 'example' }))
    // Veren araması ikincil kutu, ayrı parametre
    fireEvent.change(screen.getByRole('searchbox', { name: /veren ara|search issuer/i }), { target: { value: 'Example CA' } })
    expect(p.onFilter).toHaveBeenLastCalledWith(expect.objectContaining({ issuer: 'Example CA' }))
  })

  it('facet kontrolleri satırda (Sheet tetiği yok): takım/vade/kademe/durum/sıralama seçicileri etiketli; bas-bırak süzgeçler sayaç rozeti taşır', () => {
    renderBar()
    expect(document.querySelector('[data-slot="ct-facets"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="ct-filters-trigger"]')).toBeNull()
    for (const re of [/sorumlu takım|owning team/i, /vade penceresi|expiry window/i, /^(kademe|tier)$/i, /^(durum|status)$/i, /sıralama|sort by/i]) {
      expect(screen.getByLabelText(re)).toHaveAttribute('role', 'combobox')
    }
    expect(document.querySelector('[data-slot="ct-toggle-insecure"]').textContent).toMatch(/1$/)
    expect(document.querySelector('[data-slot="ct-toggle-port"]').textContent).toMatch(/1$/)
  })

  it('çipler önekli ("Takım: Takım A", "Vade: ≤ 30 gün", "Kademe: T1", "Durum: Kritik"), sonuç sayacı "8 / 42"; çip kaldırır, Tümünü temizle sıfırlar', () => {
    const p = renderBar({ filters: { ...EMPTY_FILTERS, team: '1', window: '30', tier: '1', status: 'critical', domain: 'ex' } })
    const chip = (k) => document.querySelector(`[data-filter-chip="${k}"]`).textContent
    expect(chip('team')).toMatch(/^(Takım|Team): Takım A/)
    expect(chip('window')).toMatch(/^(Vade|Expiry): ≤ 30/)
    expect(chip('tier')).toMatch(/^(Kademe|Tier): T1/)
    expect(chip('status')).toMatch(/^(Durum|Status): (Kritik|Critical)/)
    expect(chip('domain')).toMatch(/^(Arama|Search): ex/)
    expect(document.querySelector('[data-slot="ct-result-count"]').textContent).toMatch(/^8 (\/|of) 42/)
    fireEvent.click(document.querySelector('[data-filter-chip="tier"]'))
    expect(p.onFilter).toHaveBeenLastCalledWith(expect.objectContaining({ tier: '', team: '1' }))
    fireEvent.click(document.querySelector('[data-filter-chip="clear"]'))
    expect(p.onReset).toHaveBeenCalled()
  })

  it('süzgeç yokken çip yok, sayaç düz ("8 sertifika"); araç satırı görünüm/eylem gruplarına ayrılmış', () => {
    renderBar({ facets: { ...FACETS, all: 8 } })
    expect(document.querySelectorAll('[data-filter-chip]')).toHaveLength(0)
    expect(document.querySelector('[data-slot="ct-result-count"]').textContent).toMatch(/^8 (sertifika|certificates)$/)
    const view = screen.getByRole('group', { name: /^(görünüm|view)$/i })
    expect(within(view).getByRole('button', { name: /sütunlar|columns/i })).toBeInTheDocument()
    expect(within(view).getByRole('button', { name: /rahat|comfortable/i })).toHaveAttribute('aria-pressed', 'true')
    const actions = screen.getByRole('group', { name: /^(eylemler|actions)$/i })
    expect(within(actions).getByRole('button', { name: /ön ayarlar|presets/i })).toBeInTheDocument()
    expect(within(actions).getByRole('link', { name: /csv/i })).toHaveAttribute('href', '/api/certificates/export.csv')
  })
})
