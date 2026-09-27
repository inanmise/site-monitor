import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import MonitorSpark, { windowState } from '../components/ui/MonitorSpark.jsx'

/**
 * Kart mini trendi + erişilebilirlik satırı. 2026-09-26 yeniden tasarım: dört "1 / 7 / 15 / 30 gün" hapı (yalnız
 * `title` ipucuyla bilgi veren, telefonda iki satıra taşan) → TEK satırlık shadcn düğmesi + Popover ayrıntısı.
 * Sorgular role/ad ya da data-slot'a bağlı (legacy `mspark-*` sınıfları kalktı).
 */
const SPARK = {
  n: 12, fail: 1, up_pct: 91.7,
  buckets: [{ t: '2026-09-12T08', n: 6, fail: 0, ms: 200 }, { t: '2026-09-12T09', n: 6, fail: 1, ms: 350 }],
  last: [{ at: '2026-09-12T09:10:00', ok: true, ms: 210 }, { at: '2026-09-12T09:20:00', ok: false, ms: null },
         { at: '2026-09-12T09:30:00', ok: true, ms: 220 }, { at: '2026-09-12T09:40:00', ok: true, ms: 230 }, { at: '2026-09-12T09:50:00', ok: true, ms: 240 }],
}

const WINDOWS = [
  { days: 1, n: 0, fail: 0, up_pct: null, bad_hours: 0 },
  { days: 7, n: 2016, fail: 0, up_pct: 100, bad_hours: 0 },
  { days: 15, n: 4320, fail: 2, up_pct: 99.95, bad_hours: 1, slots: [{ h: '2026-09-20T11', fail: 2 }] },
  { days: 30, n: 8640, fail: 20, up_pct: 99.77, bad_hours: 9, slots: [
    { h: '2026-09-02T04', fail: 1 }, { h: '2026-09-20T11', fail: 2 }, { h: '2026-09-21T01', fail: 5 },
    { h: '2026-09-10T08', fail: 1 }, { h: '2026-09-11T08', fail: 1 }, { h: '2026-09-12T08', fail: 1 }, { h: '2026-09-13T08', fail: 1 },
  ] },
]
const SLOT = String.raw`[\d./]+ \d{2}[:.]\d{2}–\d{2}[:.]\d{2}`

const trigger = () => screen.getByRole('button', { name: /30 günlük erişilebilirlik|30-day availability/i })

describe('MonitorSpark — trend satırı', () => {
  it('kayıt yok ya da n=0 → hiçbir şey çizilmez (kart düzeni bozulmaz)', () => {
    const { container } = render(<><MonitorSpark spark={undefined} /><MonitorSpark spark={{ n: 0, buckets: [], last: [] }} /></>)
    expect(container.querySelector('[data-slot="monitor-spark"]')).toBeNull()
  })

  it('kovalardan çizgi, son 5 kontrol (hatalı kırmızı), son kova ms, 24 sa yüzdesi tonlu', () => {
    render(<MonitorSpark spark={SPARK} />)
    expect(screen.getByRole('img', { name: /yanıt süresi trendi|response-time trend/i }).tagName.toLowerCase()).toBe('svg')
    const dots = within(screen.getByRole('list', { name: /Son 5 kontrol|Last 5 checks/ })).getAllByRole('listitem')
    expect(dots).toHaveLength(5)
    expect(dots.filter((d) => d.dataset.ok === 'false')).toHaveLength(1)
    // Hatalı nokta ekran okuyucuya da zamanı + "hata"yı söyler (eski yalnız-fare `title` ipucunun yerine)
    expect(dots[1].textContent).toMatch(/2026-09-12 09:20:00 · (hata|failed)/)
    expect(screen.getByText('350ms')).toBeInTheDocument()
    const up = document.querySelector('[data-slot="monitor-trend-up"]')
    expect(up.dataset.tone).toBe('bad')
    expect(up.textContent).toMatch(/91\.7/)
    expect(up.textContent).toMatch(/erişilebilir|available/)
  })

  it('son saatte ölçüm yoksa (ms null — düşen izleme) "0ms" YAZILMAZ; çizgi yine çizilir', () => {
    const down = { ...SPARK, buckets: [{ t: '2026-09-12T08', n: 6, fail: 0, ms: 200 }, { t: '2026-09-12T09', n: 6, fail: 6, ms: null }] }
    render(<MonitorSpark spark={down} />)
    expect(screen.getByRole('img', { name: /yanıt süresi trendi|response-time trend/i })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="monitor-trend-ms"]')).toBeNull()
    expect(screen.queryByText('0ms')).toBeNull()
  })

  it('tek kova → çizgi yerine düz şerit; %99.9+ yeşil ton', () => {
    render(<MonitorSpark spark={{ n: 3, fail: 0, up_pct: 100, buckets: [{ t: 'x', n: 3, fail: 0, ms: 100 }], last: [] }} />)
    expect(document.querySelector('[data-slot="monitor-trend-flat"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="monitor-trend-up"]').dataset.tone).toBe('ok')
  })
})

describe('MonitorSpark — erişilebilirlik satırı (tek düğme + Popover)', () => {
  it('dönem durumu: kontrol yok / hatasız / hata var ama hedefte / hedefin altında', () => {
    expect(WINDOWS.map((w) => windowState(w, 99.9))).toEqual(['none', 'ok', 'warn', 'bad'])
    expect(windowState({ days: 7, n: 10, fail: 1, up_pct: 90 }, null)).toBe('warn')   // hedef yoksa "altında" denemez
  })

  it('kartta TEK satır: etiket · 30 günlük yüzde (hedef altı ↓ kırmızı) · dört nokta; eski hap/metin YOK', () => {
    const { container } = render(<MonitorSpark spark={undefined} slaTarget={99.9} slaDays={30}
      sla={{ n: 8640, fail: 20, up_pct: 99.77, bad_hours: 9, windows: WINDOWS }} />)
    const btn = trigger()
    expect(btn).toHaveAttribute('data-slot', 'availability-trigger')
    expect(btn).toHaveAttribute('data-verdict', 'below')
    expect(btn).toHaveAccessibleName(/99\.77.*(hedefin \(%99\.9\) altında|below the 99\.9% target)/)
    expect(btn.textContent).toMatch(/(Erişilebilirlik|Availability)/)
    expect(btn.querySelector('[data-slot="availability-pct"]').textContent).toMatch(/99\.77.*↓/)
    expect([...btn.querySelectorAll('[data-slot="availability-dot"]')].map((d) => d.dataset.state)).toEqual(['none', 'ok', 'warn', 'bad'])
    // Bilgi artık hover'a bağlı değil: hiçbir öğe title/role="img" hilesiyle ayrıntı taşımıyor
    expect(container.querySelector('[title]')).toBeNull()
    expect(container.textContent).not.toMatch(/hatalı saat|bad hours/)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('dokunma/tık Popover açar: dönem başına çubuk + yüzde + "N kontrol · M hata"; hedef satırı; en kötü dönemin dilimleri', () => {
    render(<MonitorSpark spark={undefined} slaTarget={99.9} slaDays={30}
      sla={{ n: 8640, fail: 20, up_pct: 99.77, bad_hours: 9, windows: WINDOWS }} rowLabel="a.example.com" />)
    const btn = screen.getByRole('button', { name: /^a\.example\.com — (30 günlük erişilebilirlik|30-day availability)/ })
    fireEvent.click(btn)
    expect(btn).toHaveAttribute('aria-expanded', 'true')
    const dlg = screen.getByRole('dialog', { name: /30 günlük erişilebilirlik|30-day availability/ })
    expect(dlg.textContent).toMatch(/hedef %99\.9 — hedefin \(%99\.9\) altında|target 99\.9% — below the 99\.9% target/)

    const periods = [...dlg.querySelectorAll('[data-slot="availability-period"]')]
    expect(periods.map((p) => p.dataset.days)).toEqual(['1', '7', '15', '30'])
    expect(periods[0].textContent).toMatch(/Son 24 saat.*—.*Bu dönemde kontrol yok|Last 24 hours.*—.*No checks in this period/)
    expect(periods[1].textContent).toMatch(/Son 7 gün.*%100\.00.*2\.016 kontrol · 0 hata|Last 7 days.*100\.00%.*2,016 checks · 0 failures/)
    expect(periods[3].textContent).toMatch(/8\.640 kontrol · 20 hata · hedefin \(%99\.9\) altında|8,640 checks · 20 failures · below the 99\.9% target/)
    expect(within(dlg).getAllByRole('progressbar', { hidden: true })).toHaveLength(4)
    // Varsayılan seçim: en düşük yüzdeli HATALI dönem (30 gün, %99.77)
    expect(periods[3]).toHaveAttribute('aria-pressed', 'true')
    const slots = within(dlg).getByRole('list')
    const items = within(slots).getAllByRole('listitem')
    expect(items).toHaveLength(6)                                                         // en yeni 6
    expect(items[0].textContent).toMatch(new RegExp(`^${SLOT}(5 hata alındı|5 failures)$`))   // 21.09 01:00 en yeni
    expect(dlg.textContent).toMatch(/\+3 saat dilimi daha|further hourly slots with failures: 3/)   // bad_hours 9 − 6
  })

  it('başka dönem seçilince dilim listesi o döneme geçer; hatasız dönemde "hata görülmedi"', () => {
    render(<MonitorSpark spark={undefined} slaTarget={99.9} slaDays={30}
      sla={{ n: 8640, fail: 20, up_pct: 99.77, bad_hours: 9, windows: WINDOWS }} />)
    fireEvent.click(trigger())
    const dlg = screen.getByRole('dialog')
    const [, d7, d15] = [...dlg.querySelectorAll('[data-slot="availability-period"]')]
    fireEvent.click(d15)
    expect(d15).toHaveAttribute('aria-pressed', 'true')
    const items = within(within(dlg).getByRole('list')).getAllByRole('listitem')
    expect(items).toHaveLength(1)
    expect(items[0].textContent).toMatch(new RegExp(`^${SLOT}(2 hata alındı|2 failures)$`))
    fireEvent.click(d7)
    expect(within(dlg).queryByRole('list')).toBeNull()
    expect(dlg.textContent).toMatch(/Bu dönemde hata görülmedi|No failures in this period/)
  })

  it('hedef üstü → yeşil ✓; sparkline yokken satır tek başına çizilir', () => {
    render(<MonitorSpark spark={undefined} sla={{ n: 100, fail: 0, up_pct: 100, bad_hours: 0 }} slaTarget={99.9} slaDays={30} />)
    expect(document.querySelector('[data-slot="monitor-trend"]')).toBeNull()
    expect(trigger()).toHaveAttribute('data-verdict', 'met')
    expect(trigger().querySelector('[data-slot="availability-pct"]').textContent).toMatch(/100\.00.*✓/)
  })

  it('sunucu `windows` göndermiyorsa (eski sunucu): noktasız düğme; pencerede 30 günlük özet + hatalı saat sayısı', () => {
    render(<MonitorSpark spark={SPARK} sla={{ n: 4000, fail: 8, up_pct: 99.8, bad_hours: 3 }} slaTarget={99.9} slaDays={30} />)
    const btn = trigger()
    expect(btn.querySelector('[data-slot="availability-dot"]')).toBeNull()
    fireEvent.click(btn)
    const dlg = screen.getByRole('dialog')
    expect(dlg.textContent).toMatch(/4\.000 kontrol · 8 hata|4,000 checks · 8 failures/)
    expect(dlg.textContent).toMatch(/3 hatalı saat|3 bad hours/)
  })

  it('yüzde yoksa (up_pct null) erişilebilirlik satırı çizilmez', () => {
    render(<MonitorSpark spark={undefined} sla={{ n: 10, fail: 0, up_pct: null }} slaTarget={99.9} />)
    expect(document.querySelector('[data-slot="monitor-spark"]')).toBeNull()
  })
})
