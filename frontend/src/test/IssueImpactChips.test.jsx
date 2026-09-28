import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import { ImpactChips } from '../components/issues/IssueBadges.jsx'
import IssuesToolbar from '../components/issues/IssuesToolbar.jsx'
import { FILTER_DEFAULTS } from '../components/issues/issuesModel.js'

/**
 * Sorun Bildirimleri — etki çipleri ve süzgeci (2026-09-28). Liste satırı/kart KOMPAKT: kısa etiketle en çok 2 çip +
 * "+N more" (kalanların adları ekran okuyucuya görünür metin); ayrıntı tam cümleyle + "Diğer" metni. Eski kayıt (etki
 * yok) hiçbir şey çizmez. Araç çubuğunda "Etki" seçimi süzgeç yaması üretir ve etkin süzgeç çipi kısa etiketi yazar.
 */
const ROW = { id: 1, impacts: ['SLOW', 'LOGIN', 'MOBILE', 'OTHER'], impactOther: 'VPN açıkken' }

describe('ImpactChips', () => {
  it('kompakt: kanonik sırada ilk 2 kısa etiket + "+2 more" (gizli adlar ekran okuyucuda)', () => {
    const { container } = render(<ImpactChips row={ROW} compact />)
    const chips = [...container.querySelectorAll('[data-impact]')]
    expect(chips.map((c) => c.getAttribute('data-impact'))).toEqual(['LOGIN', 'SLOW'])
    expect(chips.map((c) => c.textContent)).toEqual(['Sign-in / session', 'Slowness'])
    const more = container.querySelector('[data-slot="issue-impacts-more"]')
    expect(more.textContent).toContain('+2 more')
    expect(more.textContent).toContain('Mobile display, Other')
  })

  it('ayrıntı: tüm etkiler tam cümleyle; "Diğer" serbest metniyle', () => {
    const { container } = render(<ImpactChips row={ROW} />)
    const chips = [...container.querySelectorAll('[data-impact]')].map((c) => c.textContent)
    expect(chips).toEqual(["I can't sign in, or I keep getting signed out", 'The app is slow',
      "It doesn't display properly on my phone or tablet", 'Something else: VPN açıkken'])
    expect(container.querySelector('[data-slot="issue-impacts-more"]')).toBeNull()
  })

  it('eski kayıt (etki yok / bilinmeyen kod) hiçbir şey çizmez', () => {
    const { container } = render(<><ImpactChips row={{ id: 2 }} compact /><ImpactChips row={{ id: 3, impacts: ['BOGUS'] }} /></>)
    expect(container.querySelector('[data-slot="issue-impacts"]')).toBeNull()
  })
})

describe('IssuesToolbar — etki süzgeci', () => {
  it('"Impact" seçimi { impact } yaması üretir; etkin süzgeç çipi kısa etiketi yazar ve × ile kalkar', () => {
    const patch = vi.fn()
    render(<IssuesToolbar filters={{ ...FILTER_DEFAULTS, impact: 'SLOW' }} patch={patch} reset={() => {}} count={3} />)
    const select = screen.getByRole('combobox', { name: 'Impact' })
    expect(within(select).getAllByRole('option')).toHaveLength(13)   // "All impacts" + 12
    fireEvent.change(select, { target: { value: 'NO_ALERTS' } })
    expect(patch).toHaveBeenCalledWith({ impact: 'NO_ALERTS' })
    const chip = document.querySelector('[data-filter="impact"]')
    expect(chip.textContent).toContain('Slowness')
    fireEvent.click(within(chip).getByRole('button'))
    expect(patch).toHaveBeenLastCalledWith({ impact: '' })
  })
})
