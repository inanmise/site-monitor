import { describe, it, expect } from 'vitest'
import { render, screen } from './test-utils.jsx'
import MonitorSpark from '../components/ui/MonitorSpark.jsx'

/** Kart mini trendi (2026-09-12, #4/#14): veri yoksa hiçbir şey; varsa çizgi + son 5 nokta + erişilebilirlik. */
describe('MonitorSpark', () => {
  it('kayıt yok ya da n=0 → hiçbir şey çizilmez (kart düzeni bozulmaz)', () => {
    const { container } = render(<><MonitorSpark spark={undefined} /><MonitorSpark spark={{ n: 0, buckets: [], last: [] }} /></>)
    expect(container.querySelector('.mspark')).toBeNull()
  })

  it('kovalardan çizgi, son kontrollerden 5 nokta (hata kırmızı), erişilebilirlik yüzdesi tonlu', () => {
    const spark = {
      n: 12, fail: 1, up_pct: 91.7,
      buckets: [{ t: '2026-09-12T08', n: 6, fail: 0, ms: 200 }, { t: '2026-09-12T09', n: 6, fail: 1, ms: 350 }],
      last: [{ at: '2026-09-12T09:10:00', ok: true, ms: 210 }, { at: '2026-09-12T09:20:00', ok: false, ms: null },
             { at: '2026-09-12T09:30:00', ok: true, ms: 220 }, { at: '2026-09-12T09:40:00', ok: true, ms: 230 }, { at: '2026-09-12T09:50:00', ok: true, ms: 240 }],
    }
    const { container } = render(<MonitorSpark spark={spark} />)
    expect(container.querySelector('.mspark')).not.toBeNull()
    expect(container.querySelector('svg.spark')).not.toBeNull()
    expect(container.querySelectorAll('.mspark-dot').length).toBe(5)
    expect(container.querySelectorAll('.mspark-dot.is-fail').length).toBe(1)
    expect(screen.getByText('350ms')).toBeInTheDocument()   // son kovanın ortalaması
    const up = container.querySelector('.mspark-up')
    expect(up.textContent).toMatch(/91\.7/)
    expect(up.classList.contains('is-bad')).toBe(true)
  })

  it('tek kova → çizgi yerine düz şerit; %99.9+ yeşil', () => {
    const { container } = render(<MonitorSpark spark={{ n: 3, fail: 0, up_pct: 100, buckets: [{ t: 'x', n: 3, fail: 0, ms: 100 }], last: [] }} />)
    expect(container.querySelector('.mspark-flat')).not.toBeNull()
    expect(container.querySelector('.mspark-up').classList.contains('is-ok')).toBe(true)
  })
})
