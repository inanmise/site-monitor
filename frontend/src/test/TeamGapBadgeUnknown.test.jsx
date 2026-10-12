import { describe, it, expect } from 'vitest'
import { render } from './test-utils.jsx'
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
})
