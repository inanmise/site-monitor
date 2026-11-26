import { describe, it, expect, vi } from 'vitest'
import { Radar } from 'lucide-react'
import { act, render, screen, within } from './test-utils.jsx'
import { TAB_META, iconFor } from '../components/palette/paletteModel.js'
import { VALID_TABS } from '../utils/appRoutes.js'

vi.mock('../api/client', () => ({ api: { search: vi.fn(() => Promise.resolve({ success: true, data: [] })) } }))
import CommandPalette from '../components/CommandPalette.jsx'

/**
 * Komut paleti sekme tablosu (H paketi 2026-11-26, deneyim 6): İzleme Panosu (`monitoring`) TAB_META'da yoktu — palette
 * genel kutucuk ikonu ve bölümsüz görünüyordu. Kenar çubuğuyla aynı Radar ikonu + "İzleme" bölümü; kapı: her geçerli
 * sekmenin meta kaydı var (yeni sekme eklenince unutulmaz).
 */
describe('palet sekme tablosu', () => {
  it('İzleme Panosu: Radar ikonu + İzleme bölümü', () => {
    expect(TAB_META.monitoring).toEqual({ Icon: Radar, section: 'nav.groupMonitoring' })
  })

  it('KAPI: her geçerli sekmenin (VALID_TABS) palet meta kaydı var', () => {
    const missing = [...VALID_TABS].filter((id) => !TAB_META[id])
    expect(missing).toEqual([])
    expect(iconFor('monitoring')).toBe(Radar)
  })

  it('palette İzleme Panosu satırı Radar ikonu ve İzleme bölüm etiketiyle çizilir', async () => {
    render(<CommandPalette tabs={[{ id: 'monitoring', label: 'İzleme Panosu' }]} onTabChange={() => {}} />)
    act(() => { window.dispatchEvent(new CustomEvent('sm:palette')) })
    const row = await screen.findByRole('option', { name: /İzleme Panosu/ })
    expect(row.querySelector('svg.lucide-radar')).not.toBeNull()
    expect(within(row).getByText(/^(İzleme|Monitoring)$/)).toBeInTheDocument()
  })
})
