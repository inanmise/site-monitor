import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import ForecastDomainsPanel from '../pages/ForecastDomainsPanel.jsx'

const nav = vi.fn()
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => nav(...a) }))
vi.mock('../api/client', () => ({ formatDateOnly: (s) => 'D(' + s + ')', localDayKey: (iso) => String(iso).slice(0, 10) }))

const t = (k, ...a) => k + (a.length ? '(' + a.join(',') + ')' : '')

/** Vade Takvimi alan adı paneli (2026-09-22, F): KPI, şerit, liste, derin bağlantı; ölçülemeyen satır gizlenmez. */
describe('ForecastDomainsPanel', () => {
  const domains = [
    { id: 1, domain: 'a.example.com', days_remaining: 5, expiry_date: '2026-09-27T00:00:00Z', team_name: 'Takım A', registrar: 'R1', warning_days: 30, critical_days: 7 },
    { id: 2, domain: 'b.example.com', days_remaining: 45, expiry_date: '2026-11-06', renewal_planned_at: '2026-09-01', renewal_overdue: true },
    { id: 3, domain: 'c.example.com', days_remaining: 400, expiry_date: '2027-10-27' },
    { id: 4, domain: 'd.example.com', days_remaining: null, status: 'UNKNOWN' },
    { id: 5, domain: 'e.example.com', days_remaining: -3, expiry_date: '2026-09-19' },
  ]

  it('KPI sayar; varsayılan 90 günlük pencerede 400 günlük satır listede yok, ölçülemeyen satır "—" ile var', () => {
    const { container } = render(<ForecastDomainsPanel domains={domains} t={t} />)
    expect(screen.getByText('forecast.domKpiExpired(1)')).toBeInTheDocument()
    expect(screen.getByText('forecast.domKpiSoon(30,1)')).toBeInTheDocument()
    expect(screen.getByText('forecast.domKpiSoon(90,2)')).toBeInTheDocument()
    expect(screen.getByText('forecast.domKpiOverdue(1)')).toBeInTheDocument()
    expect(screen.getByText('forecast.domKpiUnknown(1)')).toBeInTheDocument()
    expect(screen.queryByText(/c\.example\.com/)).toBeNull()
    expect(screen.getByText(/d\.example\.com/)).toBeInTheDocument()
    // Şerit: 90 çubuk, 5. gün ve 45. gün dolu (shadcn/Tailwind: `data-slot` + `data-has` kancası, sınıf değil)
    const bars = container.querySelectorAll('[data-slot="fc-domains-bar"]')
    expect(bars).toHaveLength(90)
    expect(bars[5].getAttribute('data-has')).toBe('true')
    expect(bars[45].getAttribute('data-has')).toBe('true')
    expect(bars[6].hasAttribute('data-has')).toBe(false)
    // Tablo satırı: kalan gün rozeti + alan adı düğmesi; takım/tescil firması telefon alt satırında da var
    const row = container.querySelector('[data-slot="fc-domain-row"][data-cls="critical"]')
    expect(row).not.toBeNull()
    expect(row.textContent).toMatch(/a\.example\.com/)
    expect(row.querySelector('[data-slot="fc-domain-team"]').textContent).toBe('Takım A')
    expect(row.querySelector('[data-slot="fc-domain-registrar"]').textContent).toBe('R1')
    // Sınıflar: 5 gün ≤ kritik 7 → critical; 45 gün → later (uyarı 30 varsayılanı üstü); dolmuş → "N gün önce doldu"
    expect(container.querySelector('[data-cls="critical"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="fc-domain-row"][data-cls="overdue"]').textContent).toMatch(/inv\.expiredAgo\(3\)/)
    // Varsayılan: kart başlığı var; sayfa içinde (katlanır bölüm başlığı zaten var) heading={false} başlığı çizmez
    expect(container.querySelector('[data-slot="card-title"]').textContent).toMatch(/forecast\.domTitle/)
  })

  it('heading={false}: başlık yok, sayım rozeti ve aralık seçici kalır', () => {
    const { container } = render(<ForecastDomainsPanel domains={domains} t={t} heading={false} />)
    expect(container.querySelector('[data-slot="card-title"]')).toBeNull()
    expect(screen.getByText('forecast.domCount(5)')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'forecast.rangeLabel' })).toBeInTheDocument()
  })

  it('180 gün seçilince uzun vadeli satır gelir; tıklama alan adı izlemesine derin bağlantı verir', () => {
    render(<ForecastDomainsPanel domains={domains} t={t} />)
    fireEvent.click(screen.getByText('forecast.chartDays(180)'))
    expect(screen.queryByText(/c\.example\.com/)).toBeNull()   // 400 > 180
    fireEvent.click(screen.getByText(/a\.example\.com/))
    expect(nav).toHaveBeenCalledWith('domain', { monitor: 1 })
  })

  it('alan adı yoksa boş durum', () => {
    render(<ForecastDomainsPanel domains={[]} t={t} />)
    expect(screen.getByText('forecast.domNone(90)')).toBeInTheDocument()
  })
})
