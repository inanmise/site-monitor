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
    // Şerit: 90 çubuk, 5. gün ve 45. gün dolu
    const bars = container.querySelectorAll('.fc-domains-bar')
    expect(bars).toHaveLength(90)
    expect(bars[5].classList.contains('has')).toBe(true)
    expect(bars[45].classList.contains('has')).toBe(true)
    expect(bars[6].classList.contains('has')).toBe(false)
    // Sınıflar: 5 gün ≤ kritik 7 → critical; 45 gün → later (uyarı 30 varsayılanı üstü); dolmuş → overdue
    expect(container.querySelector('.fc-exp-cls--critical')).not.toBeNull()
    expect(screen.getByText(/dom\.expiredAgo 3/)).toBeInTheDocument()
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
