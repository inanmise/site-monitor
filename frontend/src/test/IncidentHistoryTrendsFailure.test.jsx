import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from './test-utils.jsx'
import { EN } from '../i18n/en.js'
import { mergeAfterSave, formFromPostmortem } from '../components/incidenthistory/postmortem/postmortemModel.js'

/**
 * PKG-8b (2026-10-22) UIA-G8: Olay Geçmişi özeti (`/incidents/trends`) okunamazsa başlık KPI'ları sonsuza dek iskelette
 * kalıyor, döküm kartları "0" diyordu. Artık "—" + "özet okunamadı". UIA-4: postmortem kaydı sırasında yazılanlar
 * sunucu yanıtıyla ezilmez (`mergeAfterSave`).
 */
const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {}, admin: {} }) }
})
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '' }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: () => true, canExecute: () => true, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange, placeholder }) => <input aria-label={placeholder || 'date'} value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))
vi.mock('@/components/shadcn/chart', async (importOriginal) => {
  const real = await importOriginal()
  return { ...real, ChartContainer: ({ children }) => <div data-slot="chart">{children}</div>, BarChart: () => <div data-slot="daily-probe" /> }
})

import { api } from '../api/client'
import IncidentHistoryPage from '../components/IncidentHistoryPage.jsx'

const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))

describe('Olay Geçmişi — özet okunamadı (UIA-G8)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    api.incidents.list.mockResolvedValue({ success: true, data: [], total: 0, page: 0, size: 50 })
    api.incidents.options.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  it('özet okuması düşerse başlık KPI\'ları iskelette KALMAZ ("—" + neden), döküm kartları "0" demez', async () => {
    api.incidents.trends.mockImplementation((since, until) => (since && until
      ? Promise.resolve({ success: true, data: { daily: [] } })
      : Promise.resolve({ success: false, status: 500, error: 'x' })))
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="ih-kpi-value"]').length).toBe(4))
    const kpis = document.querySelector('[data-slot="ih-kpis"]')
    expect(kpis.querySelector('[data-slot="skeleton"]')).toBeNull()
    expect([...kpis.querySelectorAll('[data-slot="ih-kpi-value"]')].map((n) => n.textContent)).toEqual(['—', '—', '—', '—'])
    expect(kpis.textContent).toContain(EN['inc.kpiUnavailable'])
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    await flush()
    const total = document.querySelector('[data-slot="stat-item"][data-key="total"] [data-slot="stat-value"]')
    expect(total.textContent).toBe('—')
  })

  it('ağ hatası (reddedilen istek) da aynı durum', async () => {
    api.incidents.trends.mockImplementation((since, until) => (since && until
      ? Promise.resolve({ success: true, data: { daily: [] } })
      : Promise.reject(new Error('net'))))
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(document.querySelector('[data-slot="ih-kpis"]').textContent).toContain(EN['inc.kpiUnavailable']))
  })

  it('özet geldiğinde bugünkü değerler (0 dahil) çizilir', async () => {
    api.incidents.trends.mockImplementation((since, until) => (since && until
      ? Promise.resolve({ success: true, data: { daily: [] } })
      : Promise.resolve({ success: true, data: { summary: { total: 0, open: 0, critical: 0 }, by_severity: {}, by_status: {} } })))
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="ih-kpi-value"]').length).toBe(4))
    expect(document.querySelector('[data-slot="ih-kpi"][data-key="total"] [data-slot="ih-kpi-value"]').textContent).toBe('0')
    expect(document.querySelector('[data-slot="ih-kpis"]').textContent).not.toContain(EN['inc.kpiUnavailable'])
  })
})

describe('postmortem — kayıt sırasında yazılanlar korunur (UIA-4)', () => {
  it('yanıt yalnız gönderildiğinden beri değişmemiş alanlara yazılır', () => {
    const sent = { ...formFromPostmortem({ status: 'DRAFT', summary: 'A', impact: 'B' }) }
    const current = { ...sent, summary: 'A + kayıt sırasında yazılan' }
    const server = formFromPostmortem({ status: 'DRAFT', summary: 'A', impact: 'B (sunucu biçimledi)' })
    const merged = mergeAfterSave(current, sent, server)
    expect(merged.summary).toBe('A + kayıt sırasında yazılan')   // yazılan kaldı
    expect(merged.impact).toBe('B (sunucu biçimledi)')            // değişmemiş alan sunucu kopyasını aldı
    expect(mergeAfterSave(sent, sent, server)).toEqual(server)    // hiçbir şey yazılmadıysa sunucu kopyası
  })
})
