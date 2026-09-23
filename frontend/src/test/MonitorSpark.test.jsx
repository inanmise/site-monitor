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

describe('MonitorSpark — 30 günlük SLA satırı (2026-09-12, #11)', () => {
  it('hedef altı → kırmızı ok + hatalı saat; hedef üstü → yeşil onay; sparkline yokken SLA tek başına çizilir', () => {
    const { container, unmount } = render(<MonitorSpark spark={{ n: 3, fail: 0, up_pct: 100, buckets: [{ t: 'a', n: 1, ms: 1 }, { t: 'b', n: 2, ms: 2 }], last: [] }}
      sla={{ n: 4000, fail: 8, up_pct: 99.8, bad_hours: 3 }} slaTarget={99.9} slaDays={30} />)
    const sla = container.querySelector('.mspark-sla')
    expect(sla.classList.contains('is-below')).toBe(true)
    expect(sla.textContent).toMatch(/30 gün: %99\.80|30 d: 99\.80%/)
    expect(sla.textContent).toMatch(/hedef %99\.9 ↓|target 99\.9% ↓/)
    expect(sla.textContent).toMatch(/3 hatalı saat|3 bad hours/)
    unmount()
    const { container: c2 } = render(<MonitorSpark spark={undefined} sla={{ n: 100, fail: 0, up_pct: 100, bad_hours: 0 }} slaTarget={99.9} slaDays={30} />)
    expect(c2.querySelector('.mspark')).toBeNull()
    expect(c2.querySelector('.mspark-sla.is-met')).not.toBeNull()
    expect(c2.querySelector('.mspark-sla-bad')).toBeNull()
  })

  it('2026-09-24 dönem etiketleri: "x hatalı saat" yerine 1/7/15/30 gün; hata olan dönemde ⚠ (hedef altı kırmızı, üstü turuncu), hatasız yeşil, kontrolsüz soluk; üzerine gelince ayrıntı', () => {
    const windows = [
      { days: 1, n: 0, fail: 0, up_pct: null, bad_hours: 0 },
      { days: 7, n: 2016, fail: 0, up_pct: 100, bad_hours: 0 },
      { days: 15, n: 4320, fail: 2, up_pct: 99.95, bad_hours: 1, slots: [{ h: '2026-09-20T11', fail: 2 }] },
      { days: 30, n: 8640, fail: 20, up_pct: 99.77, bad_hours: 3, slots: [{ h: '2026-09-20T11', fail: 2 }, { h: '2026-09-02T04', fail: 1 }] },
    ]
    const { container } = render(<MonitorSpark spark={undefined}
      sla={{ n: 8640, fail: 20, up_pct: 99.77, bad_hours: 3, windows }} slaTarget={99.9} slaDays={30} />)
    const sla = container.querySelector('.mspark-sla')
    expect(sla.querySelector('.mspark-sla-bad')).toBeNull()                       // eski "3 hatalı saat" metni yok
    expect(sla.textContent).not.toMatch(/hatalı saat|bad hours/)
    const chips = [...sla.querySelectorAll('.mspark-win')]
    expect(chips.map((c) => c.textContent)).toEqual([
      expect.stringMatching(/^1 gün$|^1 day$/), expect.stringMatching(/^7 gün$|^7 days$/),
      expect.stringMatching(/^15 gün$|^15 days$/), expect.stringMatching(/^30 gün$|^30 days$/)])
    expect(chips.map((c) => c.className.replace('mspark-win ', ''))).toEqual(['is-none', 'is-ok', 'is-warn', 'is-bad'])
    expect(chips.map((c) => c.querySelector('svg') != null)).toEqual([false, false, true, true])   // ⚠ yalnız sorunlu dönemde
    expect(chips[0]).toHaveAttribute('title', expect.stringMatching(/Son 24 saat: bu dönemde kontrol yok|Last 24 hours: no checks in this period/))
    expect(chips[1]).toHaveAttribute('title', expect.stringMatching(/Son 7 gün: 2\.016 kontrol, hata yok · erişilebilirlik %100\.00|Last 7 days: 2,016 checks, no failures · availability 100\.00%/))
    // Hata görülen her saat dilimi kendi satırında, o dilimdeki hata ADEDİYLE (2026-09-24: "5 hata varsa 5 hata alındı")
    const slot = String.raw`[\d./]+ \d{2}[:.]\d{2}–\d{2}[:.]\d{2} — `
    expect(chips[2].getAttribute('title')).toMatch(new RegExp(String.raw`^(Son 15 gün: 4\.320 kontrol, 2 hata · erişilebilirlik %99\.95|Last 15 days: 4,320 checks, 2 failures · availability 99\.95%)\n` + slot + '(2 hata alındı|2 failures)$'))
    const t30 = chips[3].getAttribute('title').split('\n')
    expect(t30[0]).toMatch(/Son 30 gün: 8\.640 kontrol, 20 hata · erişilebilirlik %99\.77 · hedefin \(%99\.9\) altında|Last 30 days: 8,640 checks, 20 failures · availability 99\.77% · below the 99\.9% target/)
    expect(t30[1]).toMatch(new RegExp('^' + slot + '(2 hata alındı|2 failures)$'))
    expect(t30[2]).toMatch(new RegExp('^' + slot + '(1 hata alındı|1 failure)$'))
    expect(t30[3]).toMatch(/^\+1 saat dilimi daha$|^further hourly slots with failures: 1$/)   // bad_hours 3, listede 2
    expect(t30).toHaveLength(4)
    expect(chips[3]).toHaveAttribute('aria-label', chips[3].getAttribute('title'))   // ekran okuyucu da aynı ayrıntıyı duyar
  })
})
