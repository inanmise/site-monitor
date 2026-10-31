import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

const { nav, perms } = vi.hoisted(() => ({ nav: vi.fn(), perms: { canEditInventory: false } }))
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => nav(...a) }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    canView: () => true, canExecute: () => true, refresh: vi.fn(), scope: null, loaded: true, failed: false,
    canEdit: (r) => (r === 'inventory.crud' ? perms.canEditInventory : true),
  }),
  PermissionsProvider: ({ children }) => children,
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? `D:${s}` : ''),
  formatDateSec: (s) => (s ? `S:${s}` : ''),
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getScriptedVersions: vi.fn(),
      getScriptedVersion: vi.fn(),
      scriptedStepSeries: vi.fn(),
    },
  }),
}))

import { api } from '../api/client'
import StepList from '../components/scripted/StepList.jsx'
import { RunContextChips, ScriptedFailureClass, ThresholdList, TlsList, VersionRegressionBand } from '../components/scripted/RunDetailParts.jsx'
import StepTrend from '../components/scripted/StepTrend.jsx'
import { tlsOf } from '../components/scripted/runDetailModel.js'

beforeEach(() => {
  vi.clearAllMocks()
  perms.canEditInventory = false
})

const STEPS = [
  { name: 'Giriş', duration_ms: 812, requests: 2, failed_requests: 0, p95_ms: 410, checks_passed: 2, checks_failed: 0 },
  { name: '', duration_ms: null, requests: 1, failed_requests: 0, p95_ms: 90, checks_passed: 0, checks_failed: 0 },
  { name: 'Giriş › OTP doğrulama adımı — uzun bir grup adı (mobil bankacılık / bireysel)', duration_ms: 3000, requests: 1,
    failed_requests: 1, p95_ms: 3000, checks_passed: 0, checks_failed: 1, error_code: 1210 },
]

describe('StepList — paylaşılan adım listesi (CheckDetail + form test paneli)', () => {
  it('adımlar: ad, kök, süre, istek (başarısız), p95, doğrulama, k6 kodu; durum ekran okuyucuya söylenir', () => {
    const { container } = render(<StepList steps={STEPS} status="FAIL" />)
    const list = container.querySelector('[data-slot="step-list"]')
    expect(list.dataset.mode).toBe('steps')
    const rows = container.querySelectorAll('[data-slot="step-row"]')
    expect(rows).toHaveLength(3)
    expect([...rows].map((r) => r.dataset.state)).toEqual(['ok', 'ok', 'fail'])
    expect(rows[1].dataset.root).toBe('true')
    expect(rows[1].textContent).toMatch(/Root|Kök/)
    expect(rows[0].querySelector('[data-chip="duration"]').textContent).toMatch(/812 ms/)
    expect(rows[0].querySelector('[data-chip="p95"]').textContent).toMatch(/410 ms/)
    expect(rows[2].querySelector('[data-chip="requests"]').textContent).toMatch(/1/)
    expect(rows[2].querySelector('[data-chip="error-code"]').textContent).toMatch(/1210/)
    expect(rows[2].querySelector('[data-chip="checks"]').textContent).toMatch(/1\/1/)
    // renk + ikon tek başına anlam taşımaz: sr metni
    expect(rows[2].querySelector('.sr-only').textContent).toMatch(/Not passed|Başarısız/)
    expect(rows[0].querySelector('.sr-only').textContent).toMatch(/Passed|Geçti/)
    // uzun ad KESİLMEZ
    expect(rows[2].querySelector('[data-slot="step-name"]').className).toMatch(/overflow-wrap:anywhere/)
    expect(rows[2].querySelector('[data-slot="step-name"]').className).not.toMatch(/truncate/)
    // FAIL'de "durdu" iddiası yok
    expect(container.querySelector('[data-slot="step-stopped"]')).toBeNull()
  })

  it('ERROR / TIMEOUT: son başarılı adımdan sonra durdu notu; hiç başarılı adım yoksa "ilk adımda"', () => {
    const { container, rerender } = render(<StepList steps={STEPS} status="ERROR" />)
    const note = container.querySelector('[data-slot="step-stopped"]')
    expect(note.dataset.kind).toBe('after')
    expect(note.textContent).toMatch(/Root|Kök/)   // son başarılı adım kök
    rerender(<StepList steps={[STEPS[2]]} status="TIMEOUT" />)
    expect(container.querySelector('[data-slot="step-stopped"]').dataset.kind).toBe('start')
  })

  it('steps_json METNİ de kabul edilir; kırpıldıysa not', () => {
    const { container } = render(<StepList steps={JSON.stringify(STEPS.slice(0, 1))} truncated status="PASS" />)
    expect(container.querySelectorAll('[data-slot="step-row"]')).toHaveLength(1)
    expect(container.querySelector('[data-slot="step-truncated"]')).not.toBeNull()
  })

  it('adım yoksa (eski satır) checks_json listesi — ✓/✗ ekran okuyucuya "geçti / başarısız" der (E1)', () => {
    const { container } = render(<StepList steps={null} status="FAIL"
      checksJson={JSON.stringify([{ name: 'durum 200', passed: true }, { name: 'gövde', passed: false }])} />)
    expect(container.querySelector('[data-slot="step-list"]').dataset.mode).toBe('checks')
    const rows = container.querySelectorAll('[data-slot="check-row"]')
    expect([...rows].map((r) => r.dataset.passed)).toEqual(['true', 'false'])
    expect(rows[1].textContent).toMatch(/(Not passed|Başarısız): gövde/)
    expect(rows[0].querySelector('[aria-hidden="true"]').textContent).toBe('✓')
  })

  it('gösterilecek bir şey yoksa hiçbir şey çizilmez', () => {
    const { container } = render(<StepList steps={null} checksJson={null} status="ERROR" />)
    expect(container.innerHTML).toBe('')
    const { container: c2 } = render(<StepList steps={[]} checksJson="[]" status="PASS" />)
    expect(c2.innerHTML).toBe('')
  })
})

describe('ThresholdList / TlsList / RunContextChips', () => {
  it('eşikler: düşen önce ve kırmızı; sayaç; alan yoksa çizilmez', () => {
    const { container } = render(<ThresholdList thresholds={[
      { metric: 'checks', expr: 'rate>0.99', ok: true }, { metric: 'http_req_duration', expr: 'p(95)<500', ok: false }]} />)
    const rows = container.querySelectorAll('[data-slot="threshold-row"]')
    expect([...rows].map((r) => r.dataset.ok)).toEqual(['false', 'true'])
    expect(rows[0].textContent).toMatch(/http_req_duration/)
    expect(rows[0].textContent).toMatch(/p\(95\)<500/)
    expect(container.textContent).toMatch(/1/)
    const { container: c2 } = render(<ThresholdList thresholds={null} />)
    expect(c2.innerHTML).toBe('')
  })

  it('TLS: izlenen sertifika kalan gün + sertifika penceresine gider; izlenmeyen yalnız yazma izniyle "Envantere ekle"; bilinmeyen nötr', () => {
    const tls = tlsOf({ tls: [
      { host: 'ibank.example.com', tls_version: 'tls1.3', requests: 3, cert_state: 'tracked', cert: { domain: 'ibank.example.com', days_remaining: 12 } },
      { host: 'cdn.example.net', tls_version: 'tls1.2', requests: 1, cert_state: 'untracked' },
      { host: 'gizli.example.org', tls_version: 'tls1.3', requests: 1, cert_state: 'tracked' },
    ] })
    const { container, unmount } = render(<TlsList tls={tls} />)
    const rows = container.querySelectorAll('[data-slot="tls-row"]')
    expect([...rows].map((r) => r.dataset.state)).toEqual(['tracked', 'untracked', 'unknown'])
    expect(rows[0].textContent).toMatch(/TLS 1\.3/)
    expect(rows[0].querySelector('[data-slot="tls-cert-days"]').dataset.tone).toBe('warning')
    fireEvent.click(rows[0].querySelector('[data-slot="tls-open-cert"]'))
    expect(nav).toHaveBeenCalledWith('dashboard', { domain: 'ibank.example.com', open: 'cert' })
    expect(rows[1].querySelector('[data-slot="tls-untracked"]')).not.toBeNull()
    expect(rows[1].querySelector('[data-slot="tls-add-inventory"]')).toBeNull()   // izin yok → düğme yok
    expect(rows[2].querySelector('[data-slot="tls-unknown"]').dataset.tone).toBe('muted')   // bilinmiyor asla yeşil değil
    unmount()

    perms.canEditInventory = true
    const { container: c2 } = render(<TlsList tls={tls} />)
    fireEvent.click(c2.querySelector('[data-slot="tls-add-inventory"]'))
    expect(nav).toHaveBeenCalledWith('domains', { i_q: 'cdn.example.net' })
  })

  it('bağlam çipleri arayüz dilinde; ters bütçede açıklayıcı cümle; bağlam yoksa hiçbir şey', () => {
    const { container } = render(<RunContextChips context={{ process_budget_s: 10, request_timeout_s: 20, egress: 'proxy', corporate_ca: true, inverted_budget: true }} />)
    const chips = container.querySelectorAll('[data-ctx]')
    expect([...chips].map((c) => c.dataset.ctx)).toEqual(['budget', 'reqTimeout', 'egressProxy', 'caYes', 'inverted'])
    expect(container.querySelector('[data-ctx="inverted"]').dataset.tone).toBe('bad')
    expect(container.querySelector('[data-slot="run-context-inverted"]').textContent).toMatch(/20/)
    expect(container.textContent).not.toMatch(/süreç bütçesi=/)   // Türkçe ham bağlam satırı yok
    const { container: c2 } = render(<RunContextChips context={null} />)
    expect(c2.innerHTML).toBe('')
  })
})

describe('ScriptedFailureClass — hata sınıfı paneli', () => {
  it('sunucu kodu varsa ortak teşhis paneli (ham metin tekrarlanmaz); eski satırda kod çıkarılamıyorsa çizilmez', () => {
    const { container } = render(<ScriptedFailureClass monitor={{ env: [] }}
      check={{ status: 'FAIL', ok: false, failure_reason: 'CONNECT_REFUSED', error: 'connect: connection refused',
        failure_detail: '{"step":"Giriş","error_code":1212}' }} />)
    const panel = container.querySelector('[data-slot="chkfail-panel"]')
    expect(panel.dataset.code).toBe('CONNECT_REFUSED')
    expect(panel.dataset.type).toBe('scripted')
    expect(container.querySelector('[data-slot="chkfail-technical"]')).toBeNull()
    expect(container.querySelector('[data-key="step"]').textContent).toBe('Giriş')
    expect(container.querySelector('[data-key="k6Code"]').textContent).toBe('1212')
    const { container: c2 } = render(<ScriptedFailureClass monitor={{}} check={{ status: 'FAIL', error: 'garip bir şey' }} />)
    expect(c2.innerHTML).toBe('')
    const { container: c3 } = render(<ScriptedFailureClass monitor={{}} check={{ status: 'PASS' }} />)
    expect(c3.innerHTML).toBe('')
  })
})

describe('VersionRegressionBand — "bu sürüm bozdu"', () => {
  const VERSIONS = [
    { id: 33, version: '2.0.1', sequence_no: 3, run_count: 6, fail_count: 6, current: true },
    { id: 32, version: '2.0.0', sequence_no: 2, run_count: 512, fail_count: 3 },
  ]

  it('en yeni sürüm belirgin kötüleştiyse bant + önceki sürümü yükle → mevcut yükleme işleyicisi (satır + gövde)', async () => {
    api.monitoring.getScriptedVersions.mockResolvedValue({ success: true, data: { versions: VERSIONS } })
    api.monitoring.getScriptedVersion.mockResolvedValue({ success: true, data: { script: 'eski()', env: [] } })
    const onLoad = vi.fn()
    const { container } = render(<VersionRegressionBand monitor={{ id: 9, script_version: '2.0.1' }} canEdit onLoad={onLoad} />)
    const band = await waitFor(() => {
      const el = container.querySelector('[data-slot="version-regression"]')
      if (!el) throw new Error('bant yok')
      return el
    })
    expect(band.dataset.version).toBe('2.0.1')
    expect(band.dataset.prev).toBe('2.0.0')
    expect(band.textContent).toMatch(/512/)
    fireEvent.click(within(band).getByRole('button'))
    await waitFor(() => expect(onLoad).toHaveBeenCalledWith(VERSIONS[1], { script: 'eski()', env: [] }))
    expect(api.monitoring.getScriptedVersion).toHaveBeenCalledWith(9, 32)
  })

  it('düzenleme yetkisi yoksa düğme yok; gövde okunamazsa hata bantta, işleyici çağrılmaz', async () => {
    api.monitoring.getScriptedVersions.mockResolvedValue({ success: true, data: { versions: VERSIONS } })
    const { container, unmount } = render(<VersionRegressionBand monitor={{ id: 9, script_version: '2.0.1' }} canEdit={false} onLoad={vi.fn()} />)
    await waitFor(() => expect(container.querySelector('[data-slot="version-regression"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="version-regression-load"]')).toBeNull()
    unmount()

    api.monitoring.getScriptedVersion.mockResolvedValue({ success: false, error: 'okunamadı' })
    const onLoad = vi.fn()
    const { container: c2 } = render(<VersionRegressionBand monitor={{ id: 9, script_version: '2.0.1' }} canEdit onLoad={onLoad} />)
    await waitFor(() => expect(c2.querySelector('[data-slot="version-regression-load"]')).not.toBeNull())
    fireEvent.click(c2.querySelector('[data-slot="version-regression-load"]'))
    await waitFor(() => expect(c2.querySelector('[data-slot="version-regression-error"]').textContent).toBe('okunamadı'))
    expect(onLoad).not.toHaveBeenCalled()
  })

  it('geç yanıt (inceleme 6): yükleme sürerken izleme değişirse ya da bant ayrılırsa onLoad ÇAĞRILMAZ; düğme kilitli kalmaz', async () => {
    api.monitoring.getScriptedVersions.mockResolvedValue({ success: true, data: { versions: VERSIONS } })
    const pending = []
    api.monitoring.getScriptedVersion.mockImplementation(() => new Promise((r) => { pending.push(r) }))
    const onLoad = vi.fn()
    const loadBtn = (c) => c.querySelector('[data-slot="version-regression-load"]')
    const { container, rerender, unmount } = render(<VersionRegressionBand monitor={{ id: 9, script_version: '2.0.1' }} canEdit onLoad={onLoad} />)
    await waitFor(() => expect(loadBtn(container)).not.toBeNull())
    fireEvent.click(loadBtn(container))
    expect(loadBtn(container).disabled).toBe(true)
    // başka izlemeye geçildi — eski izlemenin gövdesi sonra gelir
    rerender(<VersionRegressionBand monitor={{ id: 10, script_version: '2.0.1' }} canEdit onLoad={onLoad} />)
    await act(async () => { pending[0]({ success: true, data: { script: 'eski izleme' } }) })
    expect(onLoad).not.toHaveBeenCalled()
    await waitFor(() => expect(loadBtn(container)).not.toBeNull())
    expect(loadBtn(container).disabled).toBe(false)
    expect(container.querySelector('[data-slot="version-regression-error"]')).toBeNull()

    // aynı izlemede iki hızlı tıklama yok (düğme kilitli); bant ayrılırsa geç gövde de onLoad çağırmaz
    fireEvent.click(loadBtn(container))
    unmount()
    await act(async () => { pending[1]({ success: true, data: { script: 'ayrıldıktan sonra' } }) })
    expect(onLoad).not.toHaveBeenCalled()
    expect(api.monitoring.getScriptedVersion).toHaveBeenLastCalledWith(10, 32)
  })

  it('karar yoksa, liste okunamazsa ya da sürüm yoksa bant çizilmez (iddia yok)', async () => {
    api.monitoring.getScriptedVersions.mockResolvedValue({ success: true, data: { versions: [
      { id: 2, version: '1.0.1', sequence_no: 1, run_count: 10, fail_count: 0, current: true }, { id: 1, version: '1.0.0', sequence_no: 0, run_count: 10, fail_count: 0 }] } })
    const { container, unmount } = render(<VersionRegressionBand monitor={{ id: 9, script_version: '1.0.1' }} canEdit onLoad={vi.fn()} />)
    await waitFor(() => expect(api.monitoring.getScriptedVersions).toHaveBeenCalled())
    expect(container.querySelector('[data-slot="version-regression"]')).toBeNull()
    unmount()
    api.monitoring.getScriptedVersions.mockRejectedValue(new TypeError('Failed to fetch'))
    const { container: c2, unmount: u2 } = render(<VersionRegressionBand monitor={{ id: 9, script_version: '1.0.1' }} canEdit onLoad={vi.fn()} />)
    await waitFor(() => expect(api.monitoring.getScriptedVersions).toHaveBeenCalledTimes(2))
    expect(c2.querySelector('[data-slot="version-regression"]')).toBeNull()
    u2()
    render(<VersionRegressionBand monitor={{ id: 9, script_version: null }} canEdit onLoad={vi.fn()} />)
    expect(api.monitoring.getScriptedVersions).toHaveBeenCalledTimes(2)   // sürüm yoksa istek bile yok
  })
})

describe('StepTrend — adım trendi', () => {
  const DATA = { steps: [
    { name: 'Giriş', points: [{ ts: '2026-10-30T10:00:00', duration_ms: 100, failed: false }, { ts: '2026-10-30T10:05:00', duration_ms: 300, failed: true }] },
    { name: '', points: [{ ts: '2026-10-30T10:00:00', duration_ms: 40, failed: false }] },
  ], truncated: false }

  it('veri: seri düğmeleri (kök "Kök"), başarısız sayısı, ekran okuyucu özeti; aralık değişince yeniden ister', async () => {
    api.monitoring.scriptedStepSeries.mockResolvedValue({ success: true, data: DATA })
    const { container } = render(<StepTrend monitorId={4} />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="step-trend-series"] [data-series]')).toHaveLength(2))
    expect(api.monitoring.scriptedStepSeries).toHaveBeenCalledWith(4, 7)
    const series = container.querySelectorAll('[data-slot="step-trend-series"] [data-series]')
    expect(series[0].textContent).toMatch(/Giriş/)
    expect(series[0].textContent).toMatch(/1/)
    expect(series[1].textContent).toMatch(/Root|Kök/)
    expect(container.querySelector('[data-slot="step-trend-summary"]').textContent).toMatch(/Giriş/)
    fireEvent.mouseDown(screen.getByRole('radio', { name: /30/ }), { button: 0 })
    fireEvent.click(screen.getByRole('radio', { name: /30/ }))
    await waitFor(() => expect(api.monitoring.scriptedStepSeries).toHaveBeenLastCalledWith(4, 30))
  })

  it('okunamadı ≠ boş: hata + Yeniden dene; eski sunucu (404) nötr "desteklenmiyor"; boş veri ayrı durum', async () => {
    api.monitoring.scriptedStepSeries.mockResolvedValueOnce({ success: false, error: 'boom', status: 500 })
    const { container, unmount } = render(<StepTrend monitorId={4} />)
    await waitFor(() => expect(container.querySelector('[data-slot="step-trend-error"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="step-trend-empty"]')).toBeNull()
    api.monitoring.scriptedStepSeries.mockResolvedValueOnce({ success: true, data: DATA })
    fireEvent.click(within(container.querySelector('[data-slot="step-trend-error"]')).getByRole('button'))
    await waitFor(() => expect(container.querySelector('[data-slot="step-trend-series"]')).not.toBeNull())
    unmount()

    api.monitoring.scriptedStepSeries.mockResolvedValueOnce({ success: false, error: 'Not Found', status: 404 })
    const { container: c2, unmount: u2 } = render(<StepTrend monitorId={4} />)
    await waitFor(() => expect(c2.querySelector('[data-slot="step-trend-unsupported"]')).not.toBeNull())
    u2()

    api.monitoring.scriptedStepSeries.mockResolvedValueOnce({ success: true, data: { steps: [], truncated: false } })
    const { container: c3 } = render(<StepTrend monitorId={4} />)
    await waitFor(() => expect(c3.querySelector('[data-slot="step-trend-empty"]')).not.toBeNull())
    expect(c3.querySelector('[data-slot="step-trend-error"]')).toBeNull()
  })

  it('aralık değişince seri seçimi varsayılana döner (inceleme 7 — s0, s1 … anahtarları yeni veride başka adımlardır)', async () => {
    api.monitoring.scriptedStepSeries.mockResolvedValue({ success: true, data: DATA })
    const { container } = render(<StepTrend monitorId={4} />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="step-trend-series"] [data-series]')).toHaveLength(2))
    const s0 = () => container.querySelector('[data-series="s0"]')
    expect(s0().getAttribute('data-state')).toBe('on')
    fireEvent.click(s0())
    expect(s0().getAttribute('data-state')).toBe('off')
    fireEvent.mouseDown(screen.getByRole('radio', { name: /30/ }), { button: 0 })
    fireEvent.click(screen.getByRole('radio', { name: /30/ }))
    await waitFor(() => expect(api.monitoring.scriptedStepSeries).toHaveBeenLastCalledWith(4, 30))
    await waitFor(() => expect(s0().getAttribute('data-state')).toBe('on'))
    // aynı aralıkta yapılan seçim korunur
    fireEvent.click(s0())
    expect(s0().getAttribute('data-state')).toBe('off')
  })

  it('ağ hatası (throw) da okunamadı durumudur', async () => {
    api.monitoring.scriptedStepSeries.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const { container } = render(<StepTrend monitorId={4} />)
    await waitFor(() => expect(container.querySelector('[data-slot="step-trend-error"]')).not.toBeNull())
  })
})
