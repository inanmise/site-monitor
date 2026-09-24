import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import ExpiryForecastPage from '../pages/ExpiryForecastPage.jsx'

/**
 * Vade Takvimi (2026-09-12 zenginleştirme): tek uç /api/forecast; dolmuş + erişilemeyen görünür (#1),
 * süzgeç (#2), renew-by (#3), tazelik/ortam/hata bandı (#4), eşikler (#5), eylemler (#6), takım tablosu (#7),
 * toplu iş/kapsama (#8), dışa aktar (#9), zamanında oranı (#10), boş durum (#12).
 */
const { apiMock } = vi.hoisted(() => {
  const target = { getForecast: vi.fn(), forecastPlan: vi.fn(), forecastUnplan: vi.fn(), refreshCertificateHealth: vi.fn() }
  return { apiMock: new Proxy(target, { get(t, prop) { if (prop in t || typeof prop === 'symbol') return t[prop]; t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] })); return t[prop] } }) }
})
vi.mock('../api/client', async () => {
  const real = await vi.importActual('../api/client')
  return { api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '', localDayKey: real.localDayKey }
})
import { api } from '../api/client'

// Yerel gün (toISOString UTC günü verir: 00:00–03:00 İstanbul'da fikstür bir gün geri kayıp "80 gün" 79 oluyordu)
function inDays(n) { const d = new Date(); d.setDate(d.getDate() + n); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const cert = (domain, days, extra = {}) => ({
  domain, days_remaining: days, status: days == null ? 'error' : 'valid', not_after: days == null ? null : `${inDays(days)}T12:00:00`,
  renew_by: days == null ? null : inDays(days - 14), lead_days: 14, tier: 1, team_id: 1, team_name: 'Takım A', issuer_cn: 'CA One',
  fingerprint: 'F' + domain, renewal_plan_state: 'none', checked_at: '2026-09-12T10:00:00', ...extra,
})
const DATA = {
  certs: [
    cert('crit.example.com', 3), cert('high.example.com', 10), cert('warn.example.com', 25, { team_id: 2, team_name: 'Takım B', tier: 2 }),
    cert('ok.example.com', 80), cert('gone.example.com', -4), cert('err.example.com', null, { error: 'connect timeout' }),
    cert('shared.example.com', 25, { fingerprint: 'Fwarn.example.com', team_id: 2, team_name: 'Takım B' }),
  ],
  thresholds: { warning: 30, high: 15, critical: 7 },
  lead_days: { default: 14, t1: 30, t2: 14, t3: 14, t4: 14 },
  data_as_of: '2026-09-12T10:00:00', environment: 'test',
  renewals: { window_days: 90, on_time: 3, late: 1, months: [{ month: '2026-08', on_time: 2, late: 1 }, { month: '2026-09', on_time: 1, late: 0 }],
    events: [{ domain: 'old.example.com', renewed_at: '2026-09-01T00:00:00', prev_not_after: '2026-10-01T00:00:00', renew_by: '2026-09-17', on_time: true }] },
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/')
  api.getForecast.mockResolvedValue({ success: true, data: DATA })
  api.refreshCertificateHealth.mockResolvedValue({ success: true })
})

describe('ExpiryForecastPage', () => {
  it('tek uç; ortam etiketi + veri damgası; dolmuş ve erişilemeyen KPI ve listede; eşik notu alarm eşiklerinden', async () => {
    render(<ExpiryForecastPage />)
    await waitFor(() => expect(api.getForecast).toHaveBeenCalled())
    expect(await screen.findByText(/TEST/)).toBeInTheDocument()
    expect(screen.getByText(/2026-09-12T10:00:00/)).toBeInTheDocument()
    expect(screen.getByText(/OVERDUE|GECİKMİŞ/)).toBeInTheDocument()
    expect(screen.getByText(/1 expired · 1 unreachable|1 süresi dolmuş · 1 erişilemiyor/)).toBeInTheDocument()
    expect(screen.getByText('gone.example.com')).toBeInTheDocument()
    expect(screen.getByText('err.example.com')).toBeInTheDocument()
    expect(screen.getByText(/Thresholds: critical ≤7 · high ≤15 · warning ≤30|Eşikler: kritik ≤7/)).toBeInTheDocument()
    expect(screen.getByText(/≤ 7 DAYS|≤ 7 GÜN/)).toBeInTheDocument()
  })

  it('#2 takım süzgeci listeyi daraltır ve URL f_team taşır; #7 takım tablosunda "Süz" aynı işi yapar', async () => {
    render(<ExpiryForecastPage />)
    await screen.findByText('crit.example.com')
    const rows = () => [...document.querySelectorAll('.fc-exp-row .fc-exp-domain')].map((b) => b.textContent)
    expect(rows()).toContain('warn.example.com')
    const filterBtns = screen.getAllByRole('button', { name: /^Filter$|^Süz$/ })
    fireEvent.click(filterBtns[1])   // Takım B satırı (ikinci)
    await waitFor(() => expect(rows().sort()).toEqual(['shared.example.com', 'warn.example.com']))
    await waitFor(() => expect(window.location.search).toContain('f_team=2'))
    fireEvent.click(screen.getAllByRole('button', { name: /Clear filters|Süzgeçleri temizle/ })[0])   // süzgeç çubuğundaki (tablo satırında da var)
    await waitFor(() => expect(rows().length).toBeGreaterThan(4))
  })

  it('takım tablosu hücresi tıklanır → o takım+kova listesi modalda; 12 kayıtlı gün modali SAYFALI (10 + sayfalama)', async () => {
    const many = Array.from({ length: 12 }, (_, i) => cert(`gun${i}.example.com`, 5, { team_id: 3, team_name: 'Takım C' }))
    api.getForecast.mockResolvedValue({ success: true, data: { ...DATA, certs: [...DATA.certs, ...many] } })
    render(<ExpiryForecastPage />)
    await screen.findByText('crit.example.com')
    // Takım C satırı: ≤7 hücresi 12 → tıkla
    const rowC = [...document.querySelectorAll('.fc-team-table tbody tr')].find((tr) => tr.textContent.includes('Takım C'))
    const crit = [...rowC.querySelectorAll('.fc-cell-btn')].find((b) => b.textContent.trim() === '12')
    expect(crit).toBeTruthy()
    fireEvent.click(crit)
    const modal = await screen.findByRole('dialog')
    expect(modal.textContent).toMatch(/Takım C/)
    expect(modal.querySelectorAll('.fc-day-row')).toHaveLength(10)          // 10'luk sayfa
    expect(within(modal).getByRole('navigation', { name: /Sayfalama|Pagination/ })).toBeInTheDocument()
    expect(modal.textContent).toMatch(/1[–-]10/)                             // 1–10 / 12
    fireEvent.click([...modal.querySelectorAll('button')].find((b) => /sonraki|next|›|»/i.test(b.textContent + (b.getAttribute('aria-label') || ''))))
    await waitFor(() => expect(modal.querySelectorAll('.fc-day-row')).toHaveLength(2))
    // Sıfır hücresi düğme değil
    expect(rowC.querySelector('.fc-zero')).not.toBeNull()
  })

  it('#3 renew-by satırda; #8 paylaşılan sertifika + veren çipi; #10 zamanında oranı', async () => {
    render(<ExpiryForecastPage />)
    await screen.findByText('crit.example.com')
    const row = screen.getByText('crit.example.com').closest('.fc-exp-row')
    expect(within(row).getByText(/renew by|en geç/)).toBeInTheDocument()
    expect(screen.getByText(/1 certificate → 2 domains|1 sertifika → 2 alan/)).toBeInTheDocument()
    expect(screen.getByText('CA One')).toBeInTheDocument()
    expect(screen.getByText(/ON TIME %|ZAMANINDA %/)).toBeInTheDocument()
    expect(screen.getByText(/3 on time · 1 late|3 zamanında · 1 geç/)).toBeInTheDocument()
  })

  it('#6 şimdi kontrol et sağlık ucunu çağırır; plan modalı POST ile tarih+not gönderir ve satır "planlı" olur', async () => {
    api.forecastPlan.mockResolvedValue({ success: true, data: { domain: 'crit.example.com', renewal_planned_at: '2026-09-20', renewal_planned_by: 'Admin', renewal_planned_note: 'x' } })
    render(<ExpiryForecastPage />)
    await screen.findByText('crit.example.com')
    const row = screen.getByText('crit.example.com').closest('.fc-exp-row')
    fireEvent.click(within(row).getByRole('button', { name: /Check now|Şimdi kontrol et/ }))
    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalledWith('crit.example.com'))
    // Plan düğmesinin adı artık ALAN ADINI da taşıyor (satırlar ayırt edilsin diye).
    fireEvent.click(within(row).getByRole('button', { name: /plan/i }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(within(dlg).getByLabelText(/Planned renewal date|Planlanan yenileme tarihi/), { target: { value: '2026-09-20' } })
    fireEvent.change(within(dlg).getByLabelText(/^Note$|^Not$/), { target: { value: 'x' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Save plan|Planı kaydet/ }))
    await waitFor(() => expect(api.forecastPlan).toHaveBeenCalledWith('crit.example.com', '2026-09-20', 'x'))
    expect(await within(screen.getByText('crit.example.com').closest('.fc-exp-row')).findByText(/planned · 2026-09-20|planlı · 2026-09-20/)).toBeInTheDocument()
  })

  it('#4 uç REDDEDERSE hata bandı çizilir, sayfa çökmez; #12 aralıkta hiç yoksa sıradaki bitiş ipucu', async () => {
    api.getForecast.mockRejectedValueOnce(new Error('boom'))
    render(<ExpiryForecastPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/boom/)
  })

  it('#12 boş aralık: sıradaki bitiş ipucu ve "genişlet" düğmesi', async () => {
    api.getForecast.mockResolvedValue({ success: true, data: { ...DATA, certs: [cert('far.example.com', 80)], renewals: { on_time: 0, late: 0, months: [], events: [] } } })
    render(<ExpiryForecastPage />)
    await waitFor(() => expect(api.getForecast).toHaveBeenCalled())
    expect((await screen.findAllByText(/No certificates expire in the next 30 days|Önümüzdeki 30 günde/)).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/next: far.example.com \(80 days\)|sıradaki: far.example.com \(80 gün\)/).length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: /Widen to 90 days|90 güne genişlet/ }))
    expect(await screen.findByText('far.example.com')).toBeInTheDocument()
  })
})
