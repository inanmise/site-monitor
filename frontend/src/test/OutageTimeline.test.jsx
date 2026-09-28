import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, act, within, waitFor } from './test-utils.jsx'
vi.mock('../api/client', () => ({ formatDateSec: (s) => String(s ?? '') }))
import OutageTimeline from '../components/history/OutageTimeline.jsx'

/** Kesinti zaman çizelgesi (2026-09-12, #12): segment konumu/genişliği aralığa oranlı; açık alarm aralık sonuna dek; tıklama Alarm Geçmişi'ne. */
describe('OutageTimeline', () => {
  const range = { from: '2026-09-12T00:00:00', to: '2026-09-12T10:00:00' }   // 10 saat
  it('çözülmüş 1 saatlik alarm %10 genişlik, %20 sol; açık alarm aralık sonuna kadar + çizgili; özet süre + erişilebilirlik', () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    render(<OutageTimeline range={range} alerts={[
      { id: 1, alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: '2026-09-12T02:00:00', resolved: true, resolved_at: '2026-09-12T03:00:00' },
      { id: 2, alert_type: 'PING_DOWN', alert_level: 'WARNING', created_at: '2026-09-12T09:00:00', resolved: false },
    ]} />)
    const segs = document.querySelectorAll('[data-seg]')
    expect(segs.length).toBe(2)
    expect(segs[0].style.left).toBe('20%'); expect(segs[0].style.width).toBe('10%')
    expect(segs[1].style.left).toBe('90%'); expect(segs[1].getAttribute('data-open') === 'true').toBe(true)
    expect(screen.getByText(/2 alarm · toplam 2 sa · erişilebilirlik %80\.00|2 alerts · total 2 h · availability 80\.00%/)).toBeInTheDocument()
    // E2 (2026-09-28e): Alarm Geçmişi'nin GERÇEK sözleşmesi — `alert` + tür + çözülmüş olay için `view=closed` (eskiden
    // `incident` gidiyordu: o anahtarı Alarm Geçmişi okumaz, hedef alarm hiç açılmıyordu). Açık alarmda view YOK.
    fireEvent.click(segs[0])
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'alerthistory', params: { alert: '1', type: 'HTTP_DOWN', view: 'closed' } })
    fireEvent.click(segs[1])
    expect(nav.mock.calls[1][0].detail).toEqual({ tab: 'alerthistory', params: { alert: '2', type: 'PING_DOWN' } })
    window.removeEventListener('sm:navigate', nav)
  })
  it('kesinti yok → yeşil özet; aralık yok → hiçbir şey', () => {
    const { container, unmount } = render(<OutageTimeline range={range} alerts={[]} />)
    expect(screen.getByText(/Bu aralıkta kesinti yok|No outages in this range/)).toBeInTheDocument()
    expect(container.querySelectorAll('[data-seg]').length).toBe(0)
    unmount()
    const { container: c2 } = render(<OutageTimeline range={null} alerts={[]} />)
    expect(c2.querySelector('[data-slot="outage-timeline"]')).toBeNull()
  })

  /* Özet metni "kesinti yok" der — "alarm yok" DEMEZ: hemen yanında uyarı sayısı duruyor (2026-09-22 QA). */
  it('yalnız uyarı varken özet kesintiden söz eder ve uyarı sayısı ayrıca gösterilir', () => {
    render(<OutageTimeline range={range} alerts={[
      { id: 9, alert_type: 'HTTP_SSL', alert_level: 'WARNING', created_at: '2026-09-12T05:00:00', resolved: false },
    ]} />)
    expect(screen.getByText(/Bu aralıkta kesinti yok|No outages in this range/)).toBeInTheDocument()
    expect(screen.queryByText(/alarm yok|No alerts/)).toBeNull()
    expect(screen.getByText(/1 uyarı \(kesinti sayılmaz\)|Advisories: 1/)).toBeInTheDocument()
  })

  it('uyarı türleri (HTTP_SSL, PING_SLOW) segment DEĞİL çentik: erişilebilirliği düşürmez, sayım ayrı (2026-09-22)', () => {
    const { container } = render(<OutageTimeline range={range} alerts={[
      { id: 1, alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: '2026-09-12T02:00:00', resolved: true, resolved_at: '2026-09-12T03:00:00' },
      { id: 5, alert_type: 'HTTP_SSL', alert_level: 'WARNING', created_at: '2026-09-12T05:00:00', resolved: false },
      { id: 6, alert_type: 'PING_SLOW', alert_level: 'WARNING', created_at: '2026-09-12T08:00:00', resolved: true, resolved_at: '2026-09-12T09:00:00' },
    ]} />)
    expect(container.querySelectorAll('[data-seg]').length).toBe(1)   // yalnız HTTP_DOWN
    const marks = container.querySelectorAll('[data-mark]')
    expect(marks.length).toBe(2)
    expect(marks[0].style.left).toBe('50%'); expect(marks[0].getAttribute('data-open') === 'true').toBe(true)
    // İpucu shadcn Tooltip: odakta açılır (role="tooltip" kopyası)
    act(() => { marks[0].focus() })
    expect(screen.getByRole('tooltip').textContent).toMatch(/HTTP_SSL/)
    act(() => { marks[0].blur() })
    // Erişilebilirlik yalnız 1 saatlik kesintiden: %90 — SSL uyarısı (açık, aralık sonuna dek) hesaba GİRMEZ
    expect(screen.getByText(/1 alarm · toplam 1 sa · erişilebilirlik %90\.00|1 alerts · total 1 h · availability 90\.00%/)).toBeInTheDocument()
    expect(screen.getByText(/2 uyarı \(kesinti sayılmaz\)|Advisories: 2/)).toBeInTheDocument()
  })
})

/**
 * DOKUNMATİK (2026-09-28): segment (14 px) ve uyarı çentiği (4×20 px) ETKİLEŞİMLİ ama dokunmatikte hedef olamaz; öğe başına
 * genişletilmiş alan komşu çentikle çakışır. Çubuğun üstünü `pointer: coarse`'da 40 px'lik TEK katman kaplar; dokununca
 * kesintiler + uyarılar ayrıntılarıyla (ipucunun metni GÖRÜNÜR) 40 px'lik satırlar olarak listelenir. jsdom medya sorgusu
 * uygulamaz → görünürlük sözleşmesi sınıf düzeyinde; gerçek ölçüm Playwright'ta.
 */
describe('OutageTimeline — dokunmatik katman ve alarm listesi', () => {
  const range = { from: '2026-09-12T00:00:00', to: '2026-09-12T10:00:00' }
  const ALERTS = [
    { id: 1, alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: '2026-09-12T02:00:00', resolved: true, resolved_at: '2026-09-12T03:00:00' },
    { id: 5, alert_type: 'HTTP_SSL', alert_level: 'WARNING', created_at: '2026-09-12T05:00:00', resolved: false },
    { id: 6, alert_type: 'PING_SLOW', alert_level: 'WARNING', created_at: '2026-09-12T05:06:00', resolved: true, resolved_at: '2026-09-12T06:00:00' },
  ]

  it('katman yalnız dokunmatikte (fare: display:none), çubuğa ortalı 40 px, adı alarm sayısını taşır; alarm yoksa katman yok', () => {
    const { unmount } = render(<OutageTimeline range={range} alerts={ALERTS} />)
    const layer = screen.getByRole('button', { name: /List the alerts in this range \(3\)|Aralıktaki alarmları listele \(3\)/ })
    expect(layer).toHaveAttribute('data-slot', 'outage-timeline-touch')
    expect(layer).toHaveClass('hidden', 'pointer-coarse:block', 'absolute', 'h-10', '-top-[13px]')   // 14 px çubuk: 13 + 14 + 13
    // Yatayda 4 px taşar + segment / çentik dokunmatikte HEDEF ADAYI değil: uçtaki segment (en az 3 px) çubuktan 1–2 px taşıyor,
    // Chromium dokunma ayarlaması açıkta kalan o parçayı seçip Alarm Geçmişi'ni açıyordu (Playwright 390×844, 2026-09-28).
    expect(layer).toHaveClass('-inset-x-1')
    for (const el of document.querySelectorAll('[data-seg], [data-mark]')) expect(el).toHaveClass('pointer-coarse:pointer-events-none')
    expect(layer).toHaveAttribute('aria-haspopup', 'dialog')
    // Başlıktaki uyarı düğmesi katmanın ÜSTÜNDE (katman 13 px yukarı taşar)
    const hint = screen.getByText(/2 uyarı \(kesinti sayılmaz\)|Advisories: 2/).closest('button')
    expect(hint).toHaveClass('relative', 'z-10', 'pointer-coarse:min-h-10')
    unmount()
    render(<OutageTimeline range={range} alerts={[]} />)
    expect(document.querySelector('[data-slot="outage-timeline-touch"]')).toBeNull()
  })

  it('dokununca kesintiler ve uyarılar ayrıntıyla listelenir (40 px satır); satır Alarm Geçmişi\'nde o olayı açar ve liste kapanır', async () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    render(<OutageTimeline range={range} alerts={ALERTS} />)
    fireEvent.click(document.querySelector('[data-slot="outage-timeline-touch"]'))
    const list = await screen.findByRole('dialog', { name: /Alerts in this range|Aralıktaki alarmlar/ })
    const rows = within(list).getAllByRole('button')
    expect(rows).toHaveLength(3)
    for (const r of rows) expect(r).toHaveClass('min-h-10', 'w-full')
    // Kesinti: tür · seviye + başlangıç → bitiş · süre (ipucundaki metin artık görünür)
    expect(rows[0]).toHaveTextContent('HTTP_DOWN · CRITICAL')
    expect(rows[0]).toHaveTextContent('2026-09-12T02:00:00 → 2026-09-12T03:00:00 · ')
    expect(within(list).getByText(/^(Outages|Kesintiler)$/)).toBeInTheDocument()
    // Uyarılar ayrı başlıkla + açık olan "hâlâ açık" rozetli + erişilebilirliğe girmediği açıklaması
    expect(within(list).getByText(/Advisories \(not counted as downtime\)|Uyarılar \(kesinti sayılmaz\)/)).toBeInTheDocument()
    expect(rows[1]).toHaveTextContent('HTTP_SSL · WARNING')
    expect(within(rows[1]).getByText(/still open|hâlâ açık/)).toBeInTheDocument()
    expect(within(rows[2]).queryByText(/still open|hâlâ açık/)).toBeNull()
    expect(within(list).getByText(/they do not affect availability|erişilebilirlik hesabına girmez/)).toBeInTheDocument()

    fireEvent.click(rows[1])
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'alerthistory', params: { alert: '5', type: 'HTTP_SSL' } })
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Alerts in this range|Aralıktaki alarmlar/ })).toBeNull())
    window.removeEventListener('sm:navigate', nav)
  })

  it('uyarı sayısının açıklaması dokun-gör: odaklanabilir düğme, tıklayınca açıklama görünür (yalnız-hover bilgi yok)', async () => {
    render(<OutageTimeline range={range} alerts={ALERTS} />)
    const hint = screen.getByText(/2 uyarı \(kesinti sayılmaz\)|Advisories: 2/).closest('button')
    expect(hint).not.toBeNull()
    fireEvent.click(hint)
    expect((await screen.findByRole('tooltip')).textContent).toMatch(/they do not affect availability|erişilebilirlik hesabına girmez/)
  })
})
