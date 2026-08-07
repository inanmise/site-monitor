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
vi.mock('../components/MonitorNotes.jsx', () => ({
  default: (props) => <div data-testid="monitor-notes" data-type={props.type} data-target={props.target} />,
}))
vi.mock('../components/admin/AlertHistory.jsx', () => ({
  default: (props) => <div data-testid="alert-history" data-domain={props.domain} />,
}))

vi.mock('../api/client', () => ({
  // GERÇEK davranış pini: formatDateSec undefined/null'a 'N/A' basar — 2026-08 regresyonunda
  // test mock'u `s ?? ''` ile bunu maskelemişti ve alan-adı hatası (checkedAt vs checked_at) kaçmıştı.
  formatDateSec: (s) => (s ? `FMT:${s}` : 'N/A'),
  formatDate: (s) => s ?? '',
  api: {
    monitoring: {
      getScriptedMonitors: vi.fn(),
      getScriptedHistory: vi.fn(() => Promise.resolve({ success: true, data: { checks: [], total: 0, down: 0 } })),
      createScriptedMonitor: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      updateScriptedMonitor: vi.fn(),
      deleteScriptedMonitor: vi.fn(),
      triggerScriptedCheck: vi.fn(),
      testScripted: vi.fn(() => Promise.resolve({ success: true, data: { status: 'PASS', checks_passed: 3, checks_failed: 0, duration_ms: 820, output_tail: 'out' } })),
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: { scripted: { intervalSeconds: 300, timeoutSeconds: 60 } } })),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  },
}))

import { api } from '../api/client'

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.getScriptedHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
  api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
  api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { scripted: { intervalSeconds: 300, timeoutSeconds: 60 } } })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true,
    data: {
      k6_available: true, k6_version: 'v0.49.0', can_manage: true,
      monitors: [{ id: 1, name: 'OIDC Login', status: 'PASS', team_id: 5, team_name: 'SY-A', duration_ms: 800, checks_passed: 3, checks_failed: 0, checked_at: '2026-07-31T10:00:00' }],
    },
  })
})

describe('ScriptedMonitorPage', () => {
  it('izleme kartını KANONİK upt-card yapısıyla listeler + "Yeni Monitör" görünür', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(await screen.findByText('OIDC Login')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /new monitor|yeni monitör/i })).toBeInTheDocument()
    // Kanonik kart ailesi: upt-grid içinde upt-card, durum sınıfı + rozet + foot aksiyonları
    expect(container.querySelector('.upt-grid')).not.toBeNull()
    const card = container.querySelector('.upt-card')
    expect(card).not.toBeNull()
    expect(card.className).toContain('upt-card--up')          // PASS → up renk ailesi
    expect(card.querySelector('.upt-badge')).not.toBeNull()
    expect(card.querySelector('.upt-card-domain')).not.toBeNull()
    // Aksiyonlar .upt-card-foot İÇİNDE (2026-08 şikayeti: butonlar kayıyordu)
    expect(card.querySelector('.upt-card-foot .mon-btn-check')).not.toBeNull()
    expect(card.querySelector('.upt-card-foot .mon-btn-edit')).not.toBeNull()
    // Tanımsız eski sınıflar terk edildi
    expect(container.querySelector('.mon-card')).toBeNull()
    expect(container.querySelector('.btn-xs')).toBeNull()
  })

  it('k6 yoksa "devre dışı" banner gösterir', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: false, monitors: [], can_manage: true } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(await screen.findByText(/devre dışı|disabled/i)).toBeInTheDocument()
  })

  it('detay modalı: snake_case geçmiş satırları DOĞRU çözülür (Time≠N/A, Duration≠—) + 4 sekme', async () => {
    // 2026-08 regresyon pini: API snake_case döndürür (checked_at/duration_ms/checks_*);
    // bileşen camelCase okuyunca Time=N/A, Duration=— görünüyordu.
    api.monitoring.getScriptedHistory.mockResolvedValue({ success: true, data: {
      checks: [
        { id: 11, status: 'PASS',  checked_at: '2026-08-07T09:00:00', duration_ms: 812, checks_passed: 3, checks_failed: 0 },
        { id: 12, status: 'ERROR', checked_at: '2026-08-07T08:00:00', duration_ms: null, error: 'k6 binary bulunamadı' },
      ],
      total: 2, down: 1,
    } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('OIDC Login')
    fireEvent.click(screen.getByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getScriptedHistory).toHaveBeenCalled())

    // Zaman damgası çözüldü (N/A DEĞİL) + süre ms olarak görünür
    expect(await screen.findByText('FMT:2026-08-07T09:00:00')).toBeInTheDocument()
    expect(screen.getByText('812ms')).toBeInTheDocument()
    expect(screen.queryByText('N/A')).toBeNull()
    // Süresi null olan (koşamamış) satır — 0ms değil "—"
    expect(screen.getByText('FMT:2026-08-07T08:00:00')).toBeInTheDocument()
    expect(screen.getByText('k6 binary bulunamadı')).toBeInTheDocument()

    // 4 sekme (kanonik desen)
    expect(screen.getByRole('button', { name: /check history|kontrol geçmişi/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /alert history|alarm geçmişi/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /duration chart|süre grafiği/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guide|rehber/i })).toBeInTheDocument()

    // Özet şeridi: son koşum zamanı modalda DA görünür (kartta + modal özetinde ≥2 kez)
    expect(screen.getAllByText('FMT:2026-07-31T10:00:00').length).toBeGreaterThanOrEqual(2)
  })

  it('detay sekmeleri: Alarm → AlertHistory(name), Rehber&Notlar → MonitorNotes(type=SCRIPTED, target=name)', async () => {
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('OIDC Login')
    fireEvent.click(screen.getByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getScriptedHistory).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: /alert history|alarm geçmişi/i }))
    expect((await screen.findByTestId('alert-history')).dataset.domain).toBe('OIDC Login')

    fireEvent.click(screen.getByRole('button', { name: /guide|rehber/i }))
    const notes = await screen.findByTestId('monitor-notes')
    expect(notes.dataset.type).toBe('SCRIPTED')
    expect(notes.dataset.target).toBe('OIDC Login')
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
    expect(document.querySelector('.form-grid input').value).toMatch(/\(Kopya\)$/)
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
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
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
    expect(container.querySelectorAll('.upt-card')).toHaveLength(50)
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

  it('hiç koşmamış monitör (unknown, null metrikler): süre "—" gösterir, ✓/✗ metriği gizli', async () => {
    // Backend artık latest==null dalında checks_* anahtarlarını NULL koyar (0 değil).
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true,
      monitors: [{ id: 9, name: 'Hiç Koşmadı', status: 'unknown', team_id: 5, team_name: 'SY-A',
        duration_ms: null, checks_passed: null, checks_failed: null, checked_at: null }],
    } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('Hiç Koşmadı')
    const card = container.querySelector('.upt-card')
    expect(card.className).toContain('upt-card--unknown')
    expect(card.querySelector('.upt-metric-val').textContent).toBe('—')   // 0ms DEĞİL
    expect(card.textContent).not.toContain('0✓/0✗')                       // yanıltıcı sayaç yok
    expect(screen.getByText(/never run|henüz çalışmadı/i)).toBeInTheDocument()
  })
})
