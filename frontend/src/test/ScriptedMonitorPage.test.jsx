import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

// CodeEditor (prismjs/CSS) jsdom'da ağır → basit textarea ile mock
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => (
    <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: {
    monitoring: {
      getScriptedMonitors: vi.fn(),
      getScriptedHistory: vi.fn(() => Promise.resolve({ success: true, data: { checks: [] } })),
      createScriptedMonitor: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      updateScriptedMonitor: vi.fn(),
      deleteScriptedMonitor: vi.fn(),
      triggerScriptedCheck: vi.fn(),
      testScripted: vi.fn(() => Promise.resolve({ success: true, data: { status: 'PASS', checks_passed: 3, checks_failed: 0, duration_ms: 820, output_tail: 'out' } })),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  },
}))

import { api } from '../api/client'

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true,
    data: {
      k6_available: true, k6_version: 'v0.49.0', can_manage: true,
      monitors: [{ id: 1, name: 'OIDC Login', status: 'PASS', team_name: 'SY-A', duration_ms: 800, checks_passed: 3, checks_failed: 0, checked_at: '2026-07-31T10:00:00' }],
    },
  })
})

describe('ScriptedMonitorPage', () => {
  it('izleme kartını listeler + k6 sürüm/manage ile "Yeni Monitör" görünür', async () => {
    render(<ScriptedMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(await screen.findByText('OIDC Login')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /new monitor|yeni monitör/i })).toBeInTheDocument()
  })

  it('k6 yoksa "devre dışı" banner gösterir', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: false, monitors: [], can_manage: true } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(await screen.findByText(/devre dışı|disabled/i)).toBeInTheDocument()
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (ad "(Kopya)", gizli env değeri taşınmaz)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getScriptedMonitors.mockResolvedValue({
      success: true,
      data: {
        k6_available: true, k6_version: 'v0.49.0', can_manage: true,
        monitors: [{
          id: 1, name: 'OIDC Login', status: 'PASS', checked_at: '2026-07-31T10:00:00',
          description: 'Giriş senaryosu', group_name: 'Senaryolar', team_id: 5, team_name: 'SY-A',
          tags: 'prod,kritik', notify_email: false,
          interval_seconds: 900, timeout_seconds: 45,
          confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
          active: false, script: 'export default function(){}',
          // gizli env değeri şifreli saklanır → kopyaya taşınamaz (yalnız ad+secret bayrağı gider)
          env: [{ name: 'BASE_URL', secret: false, value: 'https://x.example.com' },
                { name: 'PASSWORD', secret: true, value_set: true }],
        }],
      },
    })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.createScriptedMonitor.mockResolvedValue({ success: true, data: {} })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    await screen.findByText('OIDC Login')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu: modal={} → id yok)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    expect(document.querySelector('.modal-body input.input').value).toMatch(/\(Kopya\)$/)
    expect(screen.getByTestId('code-editor').value).toBe('export default function(){}')

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createScriptedMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateScriptedMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createScriptedMonitor.mock.calls[0][0]).toEqual({
      name: 'OIDC Login (Kopya)', description: 'Giriş senaryosu',
      groupName: 'Senaryolar', teamId: 5, tags: 'prod,kritik', notifyEmail: false,
      intervalSeconds: 900, timeoutSeconds: 45,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      script: 'export default function(){}',
      env: [{ name: 'BASE_URL', secret: false, value: 'https://x.example.com' },
            { name: 'PASSWORD', secret: true }],   // gizli değer taşınmaz → kullanıcı yeniden girer
    })
  })

  it('Test Çalıştır → testScripted çağırır ve sonucu gösterir', async () => {
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){}' } })
    fireEvent.click(screen.getByRole('button', { name: /^test run$|^test çalıştır$/i }))
    await waitFor(() => expect(api.monitoring.testScripted).toHaveBeenCalled())
    expect(await screen.findByText(/820 ms/)).toBeInTheDocument()   // test-sonucu banner'ına özgü süre
  })

  it('sayfalama: 120 kayıt → 50 kart + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ id: i + 1, name: `SC-${i + 1}`, status: 'PASS', team_name: 'SY-A', checked_at: '2026-07-31T10:00:00' }))
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: true, can_manage: true, monitors: many } })
    const { container, unmount } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    await screen.findByText('SC-1')
    expect(container.querySelectorAll('.mon-card')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120 records')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('SC-51')
    expect(screen.queryByText('SC-1')).toBeNull()
    expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    unmount()

    // Tek sayfa (30 kayıt): gezinme yok ama kayıt bilgisi var
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: true, can_manage: true, monitors: many.slice(0, 30) } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('SC-1')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })
})
