import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

// CodeEditor (prismjs/CSS) jsdom'da ağır → basit textarea ile mock
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => (
    <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../components/scripted/StepTrend.jsx', () => ({ default: (p) => <div data-testid="step-trend" data-monitor={p.monitorId} /> }))
vi.mock('../components/MonitorNotes.jsx', () => ({ default: () => <div data-testid="monitor-notes" /> }))
vi.mock('../components/admin/AlertHistory.jsx', () => ({ default: () => <div data-testid="alert-history" /> }))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

beforeEach(() => resetScriptedMocks(api))

const history = (items) => ({ success: true, data: {
  items, counts: { total: items.length, fail: items.filter((x) => x.status !== 'PASS').length }, buckets: [], alerts: [],
  range: { from: '2026-10-30T00:00:00', to: '2026-10-31T00:00:00' }, total: items.length, page: 0, size: 50,
} })

const STEPS_JSON = JSON.stringify([
  { name: 'Giriş', duration_ms: 812, requests: 2, failed_requests: 0, p95_ms: 410, checks_passed: 2, checks_failed: 0 },
  { name: 'Giriş › OTP', duration_ms: 3000, requests: 1, failed_requests: 1, p95_ms: 3000, checks_passed: 0, checks_failed: 1, error_code: 1210 },
])

async function openDetail(name = 'OIDC Login') {
  fireEvent.click(await screen.findByText(name))
  await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
}

describe('ScriptedMonitorPage — koşum ayrıntısı (k6r D4) ve B4 düzeltmeleri', () => {

  it('satır içi ayrıntı: tıklanan satırın HEMEN ALTINDA açılır; adımlar / eşikler / bağlam / TLS yeni alanlardan çizilir', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue(history([
      { id: 501, checked_at: '2026-10-30T10:00:00', status: 'FAIL', duration_ms: 3900, exit_code: 99,
        error: "k6 eşikleri aşıldı (çıkış 99):\nthresholds crossed\nsüreç bütçesi=60 sn, script istek timeout'u=20s",
        steps_json: STEPS_JSON, steps_truncated: false,
        thresholds_json: JSON.stringify([{ metric: 'http_req_duration', expr: 'p(95)<500', ok: false }]),
        tls_json: JSON.stringify([{ host: 'ibank.example.com', tls_version: 'tls1.3', requests: 3, cert_state: 'untracked' }]),
        context_json: JSON.stringify({ process_budget_s: 60, request_timeout_s: 20, egress: 'proxy', corporate_ca: true, inverted_budget: false }),
        failure_reason: 'CONNECT_REFUSED', failure_detail: JSON.stringify({ step: 'Giriş › OTP', error_code: 1210, threshold: 'http_req_duration p(95)<500' }) },
      { id: 500, checked_at: '2026-10-30T09:55:00', status: 'PASS', duration_ms: 812, checks_passed: 2, checks_failed: 0 },
    ]))
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openDetail()

    // O4: Detay hücresi iki nokta ile biten etiket DEĞİL — düşen eşiğin adı
    const cell = await waitFor(() => {
      const el = document.querySelector('.upt-rt-error')
      if (!el) throw new Error('hücre yok')
      return el
    })
    expect(cell.textContent).toMatch(/http_req_duration p\(95\)<500/)
    expect(cell.textContent).not.toMatch(/\(çıkış 99\):$/)

    const open = await screen.findByRole('button', { name: /^FMT:2026-10-30T10:00:00 · / })
    fireEvent.click(open)
    const inline = await waitFor(() => {
      const el = document.querySelector('[data-slot="inline-run-detail"]')
      if (!el) throw new Error('satır içi ayrıntı yok')
      return el
    })
    // Tabloda: tıklanan satırın hemen ardından gelen tam genişlik ek satırda
    const extraRow = inline.closest('[data-slot="hist-row-extra"]')
    expect(extraRow).not.toBeNull()
    if (extraRow.tagName === 'TR') {
      expect(extraRow.previousElementSibling.textContent).toContain('FMT:2026-10-30T10:00:00')
    }
    expect(inline.querySelectorAll('[data-slot="step-row"]')).toHaveLength(2)
    expect(inline.querySelector('[data-slot="threshold-row"][data-ok="false"]')).not.toBeNull()
    expect(inline.querySelector('[data-slot="tls-row"]').dataset.state).toBe('untracked')
    expect(inline.querySelector('[data-ctx="egressProxy"]')).not.toBeNull()
    // Bağlam çip olunca Türkçe "süreç bütçesi=" satırı hata metninde TEKRAR basılmaz
    expect(inline.querySelector('[data-slot="error-text"]').textContent).not.toContain('süreç bütçesi=')
    // Hata sınıfı paneli (sunucu kodu) + ayrıntıda adım
    expect(inline.querySelector('[data-slot="chkfail-panel"]').dataset.code).toBe('CONNECT_REFUSED')
    // Kapatınca kaybolur
    fireEvent.click(open)
    await waitFor(() => expect(document.querySelector('[data-slot="inline-run-detail"]')).toBeNull())
  })

  it('eski satır (yeni alan yok): bugünkü görünüm — doğrulama listesi + Türkçe bağlam metinde; adım / eşik / TLS / bağlam çizilmez', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue(history([
      { id: 77, checked_at: '2026-10-30T10:00:00', status: 'FAIL', duration_ms: 900, exit_code: 0,
        error: "Request Failed — status 500\nsüreç bütçesi=60 sn, script istek timeout'u=20s",
        checks_json: JSON.stringify([{ name: 'durum 200', passed: false }]) },
    ]))
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openDetail()
    fireEvent.click(await screen.findByRole('button', { name: /^FMT:2026-10-30T10:00:00 · / }))
    const inline = await waitFor(() => {
      const el = document.querySelector('[data-slot="inline-run-detail"]')
      if (!el) throw new Error('yok')
      return el
    })
    expect(inline.querySelector('[data-slot="step-list"]').dataset.mode).toBe('checks')
    expect(inline.querySelector('[data-slot="threshold-list"]')).toBeNull()
    expect(inline.querySelector('[data-slot="tls-list"]')).toBeNull()
    expect(inline.querySelector('[data-slot="run-context"]')).toBeNull()
    expect(inline.querySelector('[data-slot="error-text"]').textContent).toContain('süreç bütçesi=60 sn')
  })

  it('O6: TIMEOUT ayrıntısında "Süreç başlatılamadı" çıkış etiketi yok; O3: çok satırlı hata SARILIR (kod çerçevesi değil)', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue(history([
      { id: 88, checked_at: '2026-10-30T10:00:00', status: 'TIMEOUT', duration_ms: 60000, exit_code: -1,
        error: 'Süreç zaman aşımı — k6 60 sn içinde bitmedi:\nRequest Failed — request timeout' },
    ]))
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openDetail()
    fireEvent.click(await screen.findByRole('button', { name: /^FMT:2026-10-30T10:00:00 · / }))
    const detail = await waitFor(() => {
      const el = document.querySelector('[data-slot="check-detail"]')
      if (!el) throw new Error('yok')
      return el
    })
    expect(detail.querySelector('[data-slot="exit-label"]')).toBeNull()
    const err = detail.querySelector('[data-slot="error-text"]')
    expect(err.dataset.frame).toBeUndefined()
    expect(err.className).toMatch(/whitespace-pre-wrap/)
  })

  it('"bu sürüm bozdu" bandı kontrol sekmesinde; "önceki sürümü yükle" Sürümler sekmesiyle AYNI işleyici — editöre yükler, kaydetmez', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, k6_version: 'v2.3.0', can_manage: true,
      monitors: [{ id: 1, name: 'OIDC Login', status: 'FAIL', team_id: 5, team_name: 'SY-A', script: 'yeni()', script_version: '2.0.1',
        run_script_version: '2.0.1', checked_at: '2026-10-30T10:00:00', can_edit: true, can_check: true, can_delete: true }],
    } })
    api.monitoring.getScriptedVersions.mockResolvedValue({ success: true, data: { versions: [
      { id: 33, version: '2.0.1', sequence_no: 3, event_type: 'EDIT', run_count: 6, fail_count: 6, current: true, created_at: '2026-10-30T09:00:00' },
      { id: 32, version: '2.0.0', sequence_no: 2, event_type: 'EDIT', run_count: 512, fail_count: 3, created_at: '2026-10-28T09:00:00' },
    ] } })
    api.monitoring.getScriptedVersion.mockResolvedValue({ success: true, data: { id: 32, version: '2.0.0', script: 'eski_surum()', env: [] } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openDetail()
    const band = await waitFor(() => {
      const el = document.querySelector('[data-slot="version-regression"]')
      if (!el) throw new Error('bant yok')
      return el
    })
    fireEvent.click(within(band).getByRole('button'))
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toBe('eski_surum()'))
    expect(api.monitoring.updateScriptedMonitor).not.toHaveBeenCalled()
  })

  it('Grafik sekmesi adım trendini de çizer', async () => {
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openDetail()
    fireEvent.mouseDown(screen.getByRole('tab', { name: /duration chart|süre grafiği/i }), { button: 0 })
    expect((await screen.findByTestId('step-trend')).dataset.monitor).toBe('1')
  })

  it('D4: başarı oranı yerel ondalıkla (iki hane); E2: geçmiş satırı düğmesi dokunmatikte 40 px', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 1, checked_at: '2026-10-30T10:00:00', status: 'PASS', duration_ms: 100 }],
      counts: { total: 288, fail: 10 }, buckets: [], alerts: [],
      range: { from: '2026-10-30T00:00:00', to: '2026-10-31T00:00:00' }, total: 1, page: 0, size: 50 } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openDetail()
    const dlg = screen.getByRole('dialog')
    // Özet ve kutucuk AYNI biçimi taşır (eskiden özet "%96.5", kutucuk "%96,53")
    await waitFor(() => expect(within(dlg).getAllByText(/^(%96[.,]53|96[.,]53%)$/)).toHaveLength(2))
    const btn = await screen.findByRole('button', { name: /^FMT:2026-10-30T10:00:00 · / })
    expect(btn.className).toMatch(/pointer-coarse:min-h-10/)
  })

  it('D6: duraklatılmış ve sistemin kapattığı izleme "çalışıyor" / "arızalı" sayılmaz; E8: arama hedef host ve açıklamada da', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, k6_version: 'v2.3.0', can_manage: true,
      monitors: [
        { id: 1, name: 'Canlı geçen', status: 'PASS', active: true, team_id: 5, team_name: 'SY-A', checked_at: '2026-10-30T10:00:00',
          env: [{ name: 'BASE_URL', secret: false, value: 'https://ibank.example.com' }], script: 'x' },
        { id: 2, name: 'Canlı düşen', status: 'FAIL', active: true, team_id: 5, team_name: 'SY-A', checked_at: '2026-10-30T10:00:00',
          description: 'Kampanya zinciri kontrolü' },
        { id: 3, name: 'Duraklatılmış geçen', status: 'PASS', active: false, team_id: 5, team_name: 'SY-A', checked_at: '2026-10-27T10:00:00' },
        { id: 4, name: 'Sistemin kapattığı', status: 'TIMEOUT', active: false, disabled_reason: 'anomali', team_id: 5, team_name: 'SY-A', checked_at: '2026-10-29T10:00:00' },
      ],
    } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('Canlı geçen')
    fireEvent.click(container.querySelector('[data-slot="stats-toggle"]'))
    const val = (tone) => container.querySelector(`[data-slot="stat-item"][data-tone="${tone}"] [data-slot="stat-value"]`).textContent
    expect(val('total')).toBe('4')
    expect(val('valid')).toBe('1')
    expect(val('critical')).toBe('1')
    expect(val('paused')).toBe('2')

    const search = screen.getByRole('textbox', { name: /search name, group or target|ad, grup, hedef ara/i })
    fireEvent.change(search, { target: { value: 'ibank.example' } })
    await waitFor(() => expect(screen.queryByText('Canlı düşen')).toBeNull())
    expect(screen.getByText('Canlı geçen')).toBeInTheDocument()
    fireEvent.change(search, { target: { value: 'kampanya zinciri' } })
    await waitFor(() => expect(screen.getByText('Canlı düşen')).toBeInTheDocument())
    expect(screen.queryByText('Canlı geçen')).toBeNull()
  })
})
