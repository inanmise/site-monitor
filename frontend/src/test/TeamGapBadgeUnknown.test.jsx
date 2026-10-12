import { describe, it, expect } from 'vitest'
import { render, fireEvent, act } from './test-utils.jsx'
import TeamGapBadge from '../components/admin/teams/TeamGapBadge.jsx'

/**
 * 2026-10-12 inceleme bulgusu: sayaçlar / kullanıcı listesi gelmeden (ya da hiç gelmezse) eksiği görünmeyen satır yeşil
 * "Eksiksiz" diyordu — bilinmeyen ne eksik ne eksiksizdir. Artık nötr "henüz tam denetlenmedi".
 */
describe('TeamGapBadge — bilinmeyen durum', () => {
  it('kısmi veri + eksik yok → nötr "henüz tam denetlenmedi"; tam veri + eksik yok → "Eksiksiz"', () => {
    const { container, rerender } = render(<TeamGapBadge team={{ id: 1, name: 'Takım A' }} gaps={[]} partial />)
    const el = container.querySelector('[data-slot="team-gap-badge"]')
    expect(el.getAttribute('data-state')).toBe('unknown')
    expect(el.textContent).toMatch(/Not fully checked yet|Henüz tam denetlenmedi/)
    rerender(<TeamGapBadge team={{ id: 1, name: 'Takım A' }} gaps={[]} partial={false} />)
    expect(container.querySelector('[data-slot="team-gap-badge"]').getAttribute('data-state')).toBe('complete')
  })

  it('inceleme 5: "henüz denetlenmedi" penceresi açıkken veri gelip satır "N eksik"e dönerse eksik penceresi KENDİLİĞİNDEN açılmaz', async () => {
    const team = { id: 1, name: 'Takım A' }
    const { container, rerender } = render(<TeamGapBadge team={team} gaps={[]} partial />)
    fireEvent.click(container.querySelector('[data-slot="team-gap-badge"]'))
    expect(document.querySelector('[data-slot="team-gap-unknown-pop"]')).not.toBeNull()
    await act(async () => { rerender(<TeamGapBadge team={team} gaps={['email']} partial={false} />) })
    expect(document.querySelector('[data-slot="team-gap-pop"]')).toBeNull()
    expect(document.querySelector('[data-slot="team-gap-unknown-pop"]')).toBeNull()
    // kullanıcı açarsa açılır
    fireEvent.click(container.querySelector('[data-slot="team-gap-badge"]'))
    expect(document.querySelector('[data-slot="team-gap-pop"]')).not.toBeNull()
  })
})
