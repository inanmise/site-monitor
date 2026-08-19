import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:          vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getDomainMonitors:   vi.fn(),
      getDomainHistory:    vi.fn(),
      // Detay modali paylasilan CheckHistoryTab'i mount ediyor -> bu iki uc mock'ta OLMAK ZORUNDA,
      // yoksa modal render'i patlar ve kart hic cizilmez (diger 5 sayfa testinde zaten var).
      getCheckHistory:       vi.fn(() => Promise.resolve({ success: true, data: {
        items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
        range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      createDomainMonitor: vi.fn(),
      updateDomainMonitor: vi.fn(),
      deleteDomainMonitor: vi.fn(),
      triggerDomainCheck:  vi.fn(),
      testDomain:          vi.fn(),
      monitorDefaults:     vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'akbank', domain: 'akbank.com.tr', team_name: 'SY-A', group_name: 'Kurumsal',
  status: 'OK', source: 'RDAP', days_remaining: 120, expiry_date: '2026-08-13', registrar: 'TR Registry',
  status_codes: ['clientTransferProhibited'], nameservers: ['ns1.akbank.com.tr'], ns_resolves: true,
  active: true, interval_seconds: 86400, warning_days: 30, critical_days: 7, checked_at: '2026-07-10T00:00:00',
}

describe('DomainMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getDomainHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { domain: { intervalSeconds: 86400, warningDays: 30, criticalDays: 7, thresholds: '60,30,14,7,3,1' } } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 3, name: 'SY-A' }] })
  })

  it('alan adı monitörünü listeler', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    expect(await screen.findByText('akbank.com.tr')).toBeInTheDocument()
    expect(screen.getByText('TR Registry')).toBeInTheDocument()
  })

  it('Yeni Monitör butonu ADMIN için modal açar (alan adı alanı)', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    expect(screen.getByText(/new domain monitor|yeni alan adı monit/i)).toBeInTheDocument()
  })

  it('alan adı girip kaydet → createDomainMonitor doğru payload ile çağrılır', async () => {
    api.monitoring.createDomainMonitor.mockResolvedValue({ success: true, data: {} })
    render(<DomainMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)   // USER → takım otomatik dolar (zorunlu takım)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByPlaceholderText('example.com'), { target: { value: 'example.org' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createDomainMonitor).toHaveBeenCalled())
    expect(api.monitoring.createDomainMonitor.mock.calls[0][0].domain).toBe('example.org')
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'akbank', domain: 'akbank.com.tr', status: 'OK', source: 'RDAP',
      checked_at: '2026-07-10T00:00:00',
      team_id: 3, team_name: 'SY-A', group_name: 'Kurumsal',
      thresholds_csv: '90,45,10,2', warning_days: 45, critical_days: 9,
      interval_seconds: 43200, check_timeout_ms: 12000, active: false,
    }] })
    api.monitoring.createDomainMonitor.mockResolvedValue({ success: true, data: {} })

    render(<DomainMonitorPage systemRole="ADMIN" teamId={3} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    await screen.findByText('akbank.com.tr')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    expect(screen.getByPlaceholderText('akbank.com.tr').value).toMatch(/\(Kopya\)$/)

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createDomainMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateDomainMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createDomainMonitor.mock.calls[0][0]).toEqual({
      name: 'akbank (Kopya)', domain: 'akbank.com.tr', groupName: 'Kurumsal', teamId: 3,
      thresholdsCsv: '90,45,10,2', warningDays: 45, criticalDays: 9,
      intervalSeconds: 43200, checkTimeoutMs: 12000,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
    })
  })

  it('ADMIN: takım seçilmeden Kaydet devre dışı (zorunlu takım)', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByPlaceholderText('example.com'), { target: { value: 'example.org' } })
    expect(screen.getByRole('button', { name: /^save$|^kaydet$/i })).toBeDisabled()   // takım yok → engellendi
  })

  it('sayfalama: 120 kayıt → 50 kart + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, domain: `d${i + 1}.example.org` }))
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: many })
    const { container, unmount } = render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    await screen.findByText('d1.example.org')
    expect(container.querySelectorAll('.upt-card')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120 records')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('d51.example.org')
    expect(screen.queryByText('d1.example.org')).toBeNull()
    expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    unmount()

    // Tek sayfa (30 kayıt): gezinme yok ama kayıt bilgisi var
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: many.slice(0, 30) })
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('d1.example.org')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })

  it('REGRESYON: detay modali acikken kontrol butonu patlamaz ve kilitli kalmaz', async () => {
    // Eski kod burada TANIMSIZ loadHistory(m.id, rangeDays) cagiriyordu -> ReferenceError;
    // ardindan gelen setChecking(null) hic calismadigi icin buton kalici disabled kaliyordu.
    // Ayni hata ScriptedMonitorPage'de duzeltilmisti, bu 6 kopyaya tasinmamisti.
    localStorage.clear()
    api.monitoring.triggerDomainCheck.mockResolvedValue({ success: true, data: { ...monitor } })
    window.history.replaceState({}, '', '/?monitor=1')   // detay modalini ac -> selected.id === m.id
    try {
      render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())

      const runBtn = document.querySelector('.mon-btn-check')
      expect(runBtn).not.toBeNull()
      fireEvent.click(runBtn)

      await waitFor(() => expect(api.monitoring.triggerDomainCheck).toHaveBeenCalledWith(1))
      await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmiyor
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })
})
