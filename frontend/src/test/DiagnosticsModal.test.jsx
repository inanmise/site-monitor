import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within, act } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import DiagnosticsModal from '../components/admin/DiagnosticsModal.jsx'

/**
 * Bağlantı Tanılama penceresi — 2026-09-26 yeniden tasarım (hüküm şeridi, adım hattı, zamanlama, ham ayrıntılar,
 * rapor kopyala, 429 şeridi, yetkili geçmiş bölümü + öncekiyle karşılaştırma, telefonda dikey hat).
 * Beş uç (temel, OpenSSL, ağ, HSTS, istemci IP) hâlâ aynı API çağrılarıyla koşar; sorgular rol/ad/`data-slot` ile.
 * Sabitler backend tel biçiminde (snake_case, ConnectionDiagnosticsService.diagnose).
 */

const { adminProxy, perms, mobile, failures, copyMock } = vi.hoisted(() => {
  const target = {
    runDiagnostics:        vi.fn(),
    runOpensslDiagnostics: vi.fn(),
    runNetworkDiagnostics: vi.fn(),
    runHstsDiagnostics:    vi.fn(),
    clientIpDebug:         vi.fn(),
    diagHistory:           vi.fn(),
    diagHistoryDetail:     vi.fn(),
  }
  return {
    adminProxy: new Proxy(target, {
      get(t, prop) {
        if (prop in t || typeof prop === 'symbol') return t[prop]
        t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
        return t[prop]
      },
    }),
    perms: { history: true },
    mobile: { value: false },
    failures: { list: [] },
    copyMock: vi.fn(() => Promise.resolve(true)),
  }
})

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  getRecentFailures: () => failures.list,
  api: { admin: adminProxy },
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: (r) => (r === 'diagnostics.history' ? perms.history : true), canEdit: () => true, canExecute: () => true, perms: {} }),
}))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.value }))
vi.mock('../utils/copyText.js', () => ({ copyText: (v) => copyMock(v) }))

import { api } from '../api/client'

function combo(via, mode, ok, extra = {}) {
  return {
    id: `${via}+${mode}`, via, tls_mode: mode, status: ok ? 'ok' : 'error', step_reached: ok ? 'cert-ok' : 'unknown', elapsed_ms: 200,
    source_ip: '10.0.0.5', source_port: 40000, peer_ip: via === 'proxy' ? '10.10.0.8' : '203.0.113.10', peer_port: via === 'proxy' ? 8080 : 443,
    ...(ok ? { subject: 'CN=api.example.com', days_remaining: 42, tls_version: mode === 'browser' ? 'TLSv1.2' : 'TLSv1.3', cipher_suite: 'TLS_AES_256_GCM_SHA384', alpn: mode === 'browser' ? 'h2' : null, error: null }
      : { error_class: 'UNKNOWN', error: 'Probe failed' }),
    ...extra,
  }
}

const OK_DATA = {
  domain: 'api.example.com', port: 443, proxy_configured: true, proxy_address: 'proxy.example.com:8080',
  source: { hostname: 'pod-a', ips: ['10.0.0.5'], node_name: 'worker-1' },
  dns: { ips: ['203.0.113.10', '203.0.113.11'], error: null, elapsed_ms: 14 },
  tls_client: { java_version: '25.0.1', java_vendor: 'Vendor', browser: { protocols: ['TLSv1.2'] }, default: { protocols: ['TLSv1.3'] } },
  combos: [combo('direct', 'browser', true, { elapsed_ms: 212 }), combo('direct', 'default', true, { elapsed_ms: 198 }), combo('proxy', 'browser', true, { elapsed_ms: 341 }), combo('proxy', 'default', true, { elapsed_ms: 356 })],
  elapsed_ms: 412,
}
const DIAG_OK = { success: true, data: OK_DATA }

const TLS_FAIL_DATA = {
  ...OK_DATA,
  combos: [
    combo('direct', 'browser', false, { step_reached: 'tls-handshake', elapsed_ms: 5004, error_class: 'TIMEOUT', error: 'Read timed out during TLS handshake' }),
    combo('direct', 'default', false, { step_reached: 'tls-handshake', elapsed_ms: 5002, error_class: 'TIMEOUT', error: 'Read timed out' }),
    combo('proxy', 'browser', true), combo('proxy', 'default', true),
  ],
}

beforeEach(() => {
  vi.clearAllMocks()
  perms.history = true
  mobile.value = false
  failures.list = []
  api.admin.runDiagnostics.mockResolvedValue(DIAG_OK)
  api.admin.runOpensslDiagnostics.mockResolvedValue({ success: true, data: { available: true, version: 'OpenSSL 3.0', protocols: [{ proto: 'TLSv1.2', supported: true, risk: 'LOW' }], negotiated: {}, certificate: { issuer: 'CN=Example CA' }, raw: [] } })
  api.admin.runNetworkDiagnostics.mockResolvedValue({ success: true, data: { checks: [{ key: 'ping', label: 'Ping', status: 'ok', summary: '12 ms', output: 'PING RAW OUTPUT' }] } })
  api.admin.runHstsDiagnostics.mockResolvedValue({ success: true, data: { status: 'ok', verdict: 'ENFORCED', status_line: 'HTTP/1.1 200 OK', header_present: true, raw_value: 'max-age=31536000', max_age: 31536000, http_redirects_to_https: true, checks: [], notes: [] } })
  api.admin.clientIpDebug.mockResolvedValue({ success: true, data: { resolved: '10.1.2.3', headers: { 'x-forwarded-for': '10.1.2.3' } } })
  api.admin.diagHistory.mockResolvedValue({ success: true, data: [] })
})

function open() {
  return render(<DiagnosticsModal domain="api.example.com" port={443} onClose={() => {}} />)
}

const stepCard = (key) => document.querySelector(`[data-slot="diag-step-card"][data-step="${key}"]`)

describe('DiagnosticsModal', () => {
  it('açılışta temel tanıyı KENDİLİĞİNDEN çalıştırır; port verilmezse 443', async () => {
    open()
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledWith('api.example.com', 443))
    render(<DiagnosticsModal domain="a.example.com" onClose={() => {}} />)
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledWith('a.example.com', 443))
  })

  it('koşarken iskelet + ilerleme notu; sonuç gelince iskelet kalkar', async () => {
    let resolve
    api.admin.runDiagnostics.mockReturnValue(new Promise((r) => { resolve = r }))
    open()
    expect(document.querySelector('[data-slot="diag-skeleton"]')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/Running diagnostics/i)
    await act(async () => { resolve(DIAG_OK) })
    await waitFor(() => expect(document.querySelector('[data-slot="diag-skeleton"]')).toBeNull())
  })

  it('sağlıklı koşu: hüküm şeridi success + "Reachable 4/4", altı adım kartı durumlarıyla, HTTP "Not run"', async () => {
    open()
    const verdict = await screen.findByText(/Reachable — 4\/4 paths succeeded/)
    expect(verdict.closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'success')
    expect(document.querySelector('[data-slot="diag-verdict"]')).toHaveAttribute('data-verdict-tone', 'success')
    const cards = document.querySelectorAll('[data-slot="diag-step-card"]')
    expect([...cards].map((c) => c.getAttribute('data-step'))).toEqual(['dns', 'tcp', 'proxy', 'tls', 'cert', 'http'])
    expect([...cards].map((c) => c.getAttribute('data-status'))).toEqual(['ok', 'ok', 'ok', 'ok', 'ok', 'pending'])
    // DNS kartı: çözümlenen IP'ler kopyalanabilir
    const dns = stepCard('dns')
    expect(within(dns).getByRole('button', { name: 'Copy 203.0.113.10' })).toBeInTheDocument()
    // Sertifika kartı: CN + kalan gün rozeti
    const cert = stepCard('cert')
    expect(within(cert).getByText('api.example.com')).toBeInTheDocument()
    expect(within(cert).getByText('42 days left')).toHaveAttribute('data-days', '42')
    // TLS kartı: anlaşılan sürüm + cipher
    expect(within(stepCard('tls')).getAllByText('TLSv1.2').length).toBeGreaterThan(0)
    // Yatay şerit: adım başına düğme
    const stepper = document.querySelector('[data-slot="diag-stepper"]')
    expect(within(stepper).getAllByRole('button')).toHaveLength(6)
    expect(document.querySelector('[data-slot="diag-pipeline"]')).toHaveAttribute('data-orientation', 'horizontal')
    // Kaynak satırı
    expect(document.querySelector('[data-slot="diag-source"]')).toHaveTextContent('Probed from pod-a')
  })

  it('doğrudan yol TLS\'te düşüyor: hüküm warning "Reachable only via the proxy", TLS kartı partial ve hata sınıfını gösterir', async () => {
    api.admin.runDiagnostics.mockResolvedValue({ success: true, data: TLS_FAIL_DATA })
    open()
    await screen.findByText('Reachable only via the proxy')
    expect(document.querySelector('[data-slot="diag-verdict"]')).toHaveAttribute('data-verdict-tone', 'warning')
    expect(screen.getByText(/The direct path fails at TLS Handshake/)).toBeInTheDocument()
    const tls = stepCard('tls')
    expect(tls).toHaveAttribute('data-status', 'warning')
    expect(within(tls).getAllByText('TIMEOUT')).toHaveLength(2)
    expect(within(tls).getByText(/Read timed out during TLS handshake/)).toBeInTheDocument()
    expect(within(tls).getByText('failed after 5004 ms')).toBeInTheDocument()
  })

  it('zamanlama şeritleri: DNS + dört yol, ms değerleri ve toplam', async () => {
    open()
    await screen.findByText(/Reachable — 4\/4/)
    const timing = document.querySelector('[data-slot="diag-timing"]')
    const lanes = timing.querySelectorAll('[data-lane]')
    expect([...lanes].map((l) => l.getAttribute('data-lane'))).toEqual(['dns', 'direct+browser', 'direct+default', 'proxy+browser', 'proxy+default'])
    expect([...lanes].map((l) => l.getAttribute('data-ms'))).toEqual(['14', '212', '198', '341', '356'])
    expect(timing).toHaveTextContent('Total run time: 412 ms')
  })

  it('ham ayrıntılar kapalı başlar; tetikleyiciye basınca JSON görünür', async () => {
    const user = userEvent.setup()
    open()
    await screen.findByText(/Reachable — 4\/4/)
    const dns = stepCard('dns')
    const trig = within(dns).getByRole('button', { name: /Raw details/ })
    expect(trig).toHaveAttribute('aria-expanded', 'false')
    expect(within(dns).queryByText(/"elapsed_ms": 14/)).toBeNull()
    await user.click(trig)
    expect(trig).toHaveAttribute('aria-expanded', 'true')
    expect(within(dns).getByText(/"elapsed_ms": 14/)).toBeInTheDocument()
  })

  it('Raporu kopyala: düz metin raporu panoya yazar ve bildirir', async () => {
    const user = userEvent.setup()
    open()
    await screen.findByText(/Reachable — 4\/4/)
    await user.click(screen.getByRole('button', { name: 'Copy report' }))
    await waitFor(() => expect(copyMock).toHaveBeenCalled())
    const text = copyMock.mock.calls[0][0]
    expect(text).toContain('Target: api.example.com:443')
    expect(text).toContain('Verdict: Reachable — 4/4 paths succeeded')
    expect(text).toContain('DNS: OK (14 ms) — 203.0.113.10, 203.0.113.11')
    expect(text).toContain('proxy · default: OK · cert-ok · 356 ms')
  })

  it('Run Again yeniden koşar; 429 → geri sayımlı dostça şerit, süre dolunca yeniden dene düğmesi', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      open()
      await screen.findByText(/Reachable — 4\/4/)
      failures.list = [{ path: '/admin/diagnostics', status: 429, at: 'x' }]
      api.admin.runDiagnostics.mockResolvedValue({ success: false, error: 'Çok fazla tanılama isteği' })
      await user.click(screen.getByRole('button', { name: /Run Again/ }))
      await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledTimes(2))
      const banner = await screen.findByRole('alert')
      expect(banner).toHaveAttribute('data-tone', 'warning')
      expect(banner).toHaveTextContent('Rate limit reached')
      expect(banner).toHaveTextContent(/try again in 60 s/)
      // ham hata metni ve adım hattı yok
      expect(screen.queryByText('Çok fazla tanılama isteği')).toBeNull()
      expect(document.querySelector('[data-slot="diag-pipeline"]')).toBeNull()
      await act(async () => { vi.advanceTimersByTime(61_000) })
      expect(banner).toHaveTextContent(/The wait is over/)
      failures.list = []
      api.admin.runDiagnostics.mockResolvedValue(DIAG_OK)
      await user.click(within(banner).getByRole('button', { name: /Run Again/ }))
      await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledTimes(3))
    } finally {
      vi.useRealTimers()
    }
  })

  it('başarısız yanıt (success:false, 429 değil) ham hata şeridini çizer; sunucu önerdiyse "Try … instead" düğmesi', async () => {
    api.admin.runDiagnostics.mockResolvedValue({ success: false, error: 'timeout', suggested_host: 'www.api.example.com' })
    const user = userEvent.setup()
    open()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('timeout')
    await user.click(within(alert).getByRole('button', { name: 'Try www.api.example.com instead' }))
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenLastCalledWith('www.api.example.com', 443))
  })

  it('tanı ucu fırlatırsa pencere ÇÖKMEZ (kapat düğmesi ayakta)', async () => {
    api.admin.runDiagnostics.mockRejectedValue(new Error('network down'))
    open()
    await screen.findByRole('alert')
    // X (aria-label) + altlık düğmesi: ikisi de "Dismiss" — pencere ayakta
    expect(screen.getAllByRole('button', { name: /Dismiss/ }).length).toBeGreaterThan(0)
  })

  it('HTTP adımı "Not run": kartın düğmesi HSTS analizini koşar ve adım durum satırıyla dolar', async () => {
    const user = userEvent.setup()
    open()
    await screen.findByText(/Reachable — 4\/4/)
    const http = stepCard('http')
    expect(http).toHaveAttribute('data-status', 'pending')
    await user.click(within(http).getByRole('button', { name: /Run HTTP check/ }))
    await waitFor(() => expect(api.admin.runHstsDiagnostics).toHaveBeenCalledWith('api.example.com', 443))
    await waitFor(() => expect(stepCard('http')).toHaveAttribute('data-status', 'ok'))
    expect(within(stepCard('http')).getByText('HTTP/1.1 200 OK')).toBeInTheDocument()
  })

  it('Ek analizler: openssl / ağ / istemci IP düğmeleri ilgili ucu çağırır; ağ kontrolü kapalı Collapsible, ham çıktı tıklayınca', async () => {
    const user = userEvent.setup()
    open()
    await screen.findByText(/Reachable — 4\/4/)
    await user.click(screen.getByRole('button', { name: /Show or hide more analyses/ }))
    await user.click(screen.getByRole('button', { name: /Deep SSL\/TLS Scan/ }))
    await user.click(await screen.findByRole('button', { name: /Run Deep Scan/ }))
    await waitFor(() => expect(api.admin.runOpensslDiagnostics).toHaveBeenCalledWith('api.example.com', 443))
    // openssl sonucu sertifika kartına da işlenir (veren)
    await waitFor(() => expect(within(stepCard('cert')).getByText('CN=Example CA')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: /Network Deep Analysis/ }))
    await user.click(await screen.findByRole('button', { name: /Run Network Analysis/ }))
    await waitFor(() => expect(api.admin.runNetworkDiagnostics).toHaveBeenCalledWith('api.example.com', 443))
    const trig = await screen.findByRole('button', { name: /Ping/ })
    expect(trig).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('PING RAW OUTPUT')).toBeNull()
    await user.click(trig)
    expect(screen.getByText('PING RAW OUTPUT')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Client IP \/ Proxy Headers/ }))
    await user.click(await screen.findByRole('button', { name: /Run Client IP Diagnostics/ }))
    await waitFor(() => expect(api.admin.clientIpDebug).toHaveBeenCalled())
  })

  it('geçmiş bölümü yetkiye bağlı: diagnostics.history yoksa hiç çizilmez ve uç çağrılmaz', async () => {
    perms.history = false
    open()
    await screen.findByText(/Reachable — 4\/4/)
    expect(document.querySelector('[data-slot="diag-history-section"]')).toBeNull()
    expect(screen.queryByRole('button', { name: /diagnostics history/i })).toBeNull()
    expect(api.admin.diagHistory).not.toHaveBeenCalled()
  })

  it('geçmiş: pencerenin İÇİNDE katlanır bölüm (iç içe dialog yok); açınca liste yüklenir, satır düğmesinin adı satırı taşır, boş liste durum bloğu', async () => {
    api.admin.diagHistory.mockResolvedValue({ success: true, data: [
      { id: 7, run_type: 'CONNECTION', executed_at: '2026-08-19T10:00:00', executed_by: 'admin', success: true, source_ip: '10.0.0.9', summary: '4/4 kombinasyon OK' },
      { id: 6, run_type: 'DOMAIN_EXPIRY', executed_at: '2026-08-18T10:00:00', executed_by: 'admin', success: false, source_ip: '10.0.0.9' },
    ] })
    const user = userEvent.setup()
    open()
    await screen.findByText(/Reachable — 4\/4/)
    expect(api.admin.diagHistory).not.toHaveBeenCalled()      // tembel: bölüm açılana kadar istek yok
    await user.click(screen.getByRole('button', { name: /Show or hide diagnostics history/ }))
    await waitFor(() => expect(api.admin.diagHistory).toHaveBeenCalledWith('api.example.com'))
    const hist = await screen.findByTestId('diag-history')
    expect(screen.getAllByRole('dialog')).toHaveLength(1)
    expect(within(hist).getByRole('button', { name: 'View — Connection Matrix · 2026-08-19T10:00:00' })).toBeInTheDocument()
    expect(within(hist).getByText('Domain Expiry')).toBeInTheDocument()

    // boş liste
    api.admin.diagHistory.mockResolvedValue({ success: true, data: [] })
    const { unmount } = render(<DiagnosticsModal domain="b.example.com" onClose={() => {}} />)
    await screen.findAllByText(/Reachable — 4\/4/)
    await user.click(screen.getAllByRole('button', { name: /Show or hide diagnostics history/ })[1])
    await waitFor(() => expect(api.admin.diagHistory).toHaveBeenCalledWith('b.example.com'))
    expect(await screen.findByText(/No diagnostics records/)).toBeInTheDocument()
    unmount()
  })

  it('geçmiş ayrıntısı: bağlantı koşusu hüküm + matris (0 ms "—" olmaz); "Compare with previous" farkı DiffTable ile çizer', async () => {
    api.admin.diagHistory.mockResolvedValue({ success: true, data: [
      { id: 8, run_type: 'CONNECTION', executed_at: '2026-08-20T10:00:00', executed_by: 'admin', success: false, summary: '2/4' },
      { id: 7, run_type: 'CONNECTION', executed_at: '2026-08-19T10:00:00', executed_by: 'admin', success: true, summary: '4/4' },
    ] })
    api.admin.diagHistoryDetail.mockImplementation((id) => Promise.resolve({ success: true, data: {
      id, run_type: 'CONNECTION', executed_by: 'admin', executed_at: id === 8 ? '2026-08-20T10:00:00' : '2026-08-19T10:00:00', domain: 'api.example.com', port: 443,
      result_json: JSON.stringify(id === 8
        ? { ...TLS_FAIL_DATA, combos: TLS_FAIL_DATA.combos.map((c) => (c.status === 'ok' ? { ...c, elapsed_ms: 0 } : c)) }
        : OK_DATA),
    } }))
    const user = userEvent.setup()
    open()
    await screen.findByText(/Reachable — 4\/4/)
    await user.click(screen.getByRole('button', { name: /Show or hide diagnostics history/ }))
    const hist = await screen.findByTestId('diag-history')
    await user.click(within(hist).getByRole('button', { name: /View — Connection Matrix · 2026-08-20/ }))
    await waitFor(() => expect(api.admin.diagHistoryDetail).toHaveBeenCalledWith(8))
    const detail = await waitFor(() => document.querySelector('[data-slot="diag-history-detail"]'))
    expect(within(detail).getByText('Reachable only via the proxy')).toBeInTheDocument()
    const okRow = within(detail).getAllByRole('row').find((r) => r.getAttribute('data-combo-status') === 'ok')
    expect(okRow.textContent).toContain('0')
    await user.click(within(detail).getByRole('button', { name: /Compare with previous/ }))
    await waitFor(() => expect(api.admin.diagHistoryDetail).toHaveBeenCalledWith(7))
    const cmp = await waitFor(() => document.querySelector('[data-slot="diag-compare"]'))
    expect(cmp).toHaveTextContent('Differences from the previous run (2026-08-19T10:00:00)')
    const fromCells = cmp.querySelectorAll('[data-diff="from"]')
    expect(fromCells.length).toBeGreaterThan(0)
    expect(cmp.querySelector('[data-diff="to"]').textContent).toBe('2/4')
    // geri dön → liste
    await user.click(within(detail).getByRole('button', { name: /Back to list/ }))
    expect(await screen.findByTestId('diag-history')).toBeInTheDocument()
  })

  it('telefon: hat dikey (data-orientation), zamanlama bölümü kapalı başlar', async () => {
    mobile.value = true
    open()
    await screen.findByText(/Reachable — 4\/4/)
    expect(document.querySelector('[data-slot="diag-pipeline"]')).toHaveAttribute('data-orientation', 'vertical')
    const timingTrig = within(document.querySelector('[data-slot="diag-timing-section"]')).getByRole('button', { name: /Timing/ })
    expect(timingTrig).toHaveAttribute('aria-expanded', 'false')
    expect(document.querySelector('[data-slot="diag-timing"]')).toBeNull()
    mobile.value = false
  })
})
