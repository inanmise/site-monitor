import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import {
  DNS_HISTORY, DNS_ROW, PING_HISTORY, PING_ROW, PORT_HISTORY, PORT_ROW, dnsStale, pingFiltered, portPathDiffers, stored,
} from './helpers/netDiagnoseFixtures.js'
import NetDiagnoseDialog from '../components/diagnose/NetDiagnoseDialog.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
// Kap genişliği (useElementWidth) — 0 = ölçüm yok (DNS tablo); dar değer kart görünümünü zorlar.
const width = vi.hoisted(() => ({ value: 0 }))
vi.mock('../hooks/useElementWidth.js', () => ({
  useElementWidthState: () => [width.value, () => {}],
  useElementWidth: () => [() => {}, width.value],
}))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  getRecentFailures: () => [],
  api: withApiFallback({
    monitoring: {
      diagnosePing: vi.fn(), pingDiagnoseHistory: vi.fn(), pingDiagnoseRun: vi.fn(),
      diagnosePort: vi.fn(), portDiagnoseHistory: vi.fn(), portDiagnoseRun: vi.fn(),
      diagnoseDns: vi.fn(), dnsDiagnoseHistory: vi.fn(), dnsDiagnoseRun: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Ping / Port / DNS uçtan uca tanılama penceresi (2026-10-05). Sözleşme: açılışta ASLA koşmaz (initialRunId yalnız kayıtlı
 * çalıştırmayı açar); başlangıç ekranı türe göre neyin deneneceğini söyler, Ping'de traceroute anahtarı `{traceroute:true}`
 * gönderir; sonuç: hüküm → bulgular (sunucu sırası) → (Port) yol karşılaştırması → adımlar (takılan adım açık) → (DNS)
 * çözücü / yetkili satırları (geniş kapta tablo, dar kapta kart) → izleme istemcisi → döküm; geçmiş → kayıtlı sonuç;
 * 429 şeridi; İptal isteği keser.
 */
const DLG_NAME = { ping: /ping tanılama|ping diagnosis/i, port: /port tanılama|port diagnosis/i, dns: /dns tanılama|dns diagnosis/i }
const ROWS = { ping: PING_ROW, port: PORT_ROW, dns: DNS_ROW }
const dlg = (type = 'ping') => screen.getByRole('dialog', { name: DLG_NAME[type] })
const body = (type = 'ping') => dlg(type).querySelector('[data-slot="ndx-body"]')
const startBtn = (type = 'ping') => within(dlg(type)).getByRole('button', { name: /^(Tanılamayı başlat|Start diagnosis)$/ })

function renderDialog(type = 'ping', props = {}) {
  const onClose = vi.fn()
  const onRunChange = vi.fn()
  render(<NetDiagnoseDialog type={type} monitor={{ ...ROWS[type], can_diagnose: true }} onClose={onClose} onRunChange={onRunChange} {...props} />)
  return { onClose, onRunChange }
}

const RUN = { ping: () => api.monitoring.diagnosePing, port: () => api.monitoring.diagnosePort, dns: () => api.monitoring.diagnoseDns }

async function runWith(type, data) {
  RUN[type]().mockResolvedValueOnce({ success: true, data })
  fireEvent.click(startBtn(type))
  return waitFor(() => {
    const el = dlg(type).querySelector('[data-slot="ndx-result"]')
    if (!el) throw new Error('sonuç yok')
    return el
  })
}

describe('NetDiagnoseDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    width.value = 0
    api.monitoring.pingDiagnoseHistory.mockResolvedValue({ success: true, data: PING_HISTORY })
    api.monitoring.portDiagnoseHistory.mockResolvedValue({ success: true, data: PORT_HISTORY })
    api.monitoring.dnsDiagnoseHistory.mockResolvedValue({ success: true, data: DNS_HISTORY })
    api.monitoring.pingDiagnoseRun.mockResolvedValue({ success: true, data: stored(pingFiltered()) })
    api.monitoring.portDiagnoseRun.mockResolvedValue({ success: true, data: stored(portPathDiffers()) })
    api.monitoring.dnsDiagnoseRun.mockResolvedValue({ success: true, data: stored(dnsStale()) })
  })

  it.each(['ping', 'port', 'dns'])('%s: başlangıç ekranı neyin deneneceğini söyler; düğmeye basılana kadar API ÇAĞRILMAZ', async (type) => {
    renderDialog(type)
    expect(body(type)).toHaveAttribute('data-phase', 'start')
    expect(body(type)).toHaveAttribute('data-type', type)
    const start = dlg(type).querySelector('[data-slot="ndx-start"]')
    expect(start).not.toBeNull()
    expect(start.querySelector('[data-slot="ndx-start-target"]').textContent)
      .toContain({ ping: 'gw-01.example.test', port: 'db-01.example.test:5432', dns: 'www.example.test' }[type])
    expect(start.querySelector('[data-slot="ndx-own-route"]')).toHaveAttribute('data-via', type === 'port' ? 'proxy' : 'direct')
    // traceroute anahtarı yalnız Ping'de
    expect(!!dlg(type).querySelector('[data-slot="ndx-traceroute"]')).toBe(type === 'ping')
    await act(async () => { await Promise.resolve() })
    expect(RUN[type]()).not.toHaveBeenCalled()
    expect(api.monitoring[`${type}DiagnoseRun`]).not.toHaveBeenCalled()
    // rapor / JSON sonuç yokken kapalı
    expect(within(dlg(type)).getByRole('button', { name: /raporu kopyala|copy report/i })).toBeDisabled()
    expect(within(dlg(type)).getByRole('button', { name: /json indir|download json/i })).toBeDisabled()
  })

  it('ping: traceroute anahtarı kapalıyken {traceroute:false} gönderilir; iptal sinyali verilir', async () => {
    renderDialog('ping')
    expect(within(dlg()).getByRole('switch', { name: /traceroute da çalıştır|also run a traceroute/i })).not.toBeChecked()
    api.monitoring.diagnosePing.mockResolvedValueOnce({ success: true, data: pingFiltered() })
    fireEvent.click(startBtn('ping'))
    await waitFor(() => expect(api.monitoring.diagnosePing).toHaveBeenCalledTimes(1))
    expect(api.monitoring.diagnosePing.mock.calls[0][0]).toBe(41)
    expect(api.monitoring.diagnosePing.mock.calls[0][1]).toEqual({ traceroute: false })
    expect(api.monitoring.diagnosePing.mock.calls[0][2].signal).toBeInstanceOf(AbortSignal)
    await waitFor(() => expect(dlg().querySelector('[data-slot="ndx-result"]')).not.toBeNull())
  })

  it('ping: "Traceroute da çalıştır" açılınca {traceroute:true} gönderilir; koşu ipucu traceroute süresini anar', async () => {
    renderDialog('ping')
    const sw = within(dlg()).getByRole('switch', { name: /traceroute da çalıştır|also run a traceroute/i })
    fireEvent.click(sw)
    expect(sw).toBeChecked()
    expect(dlg().querySelector('[data-slot="ndx-traceroute"]')).toHaveAttribute('data-on', 'true')
    let release
    api.monitoring.diagnosePing.mockImplementationOnce(() => new Promise((r) => { release = r }))
    fireEvent.click(startBtn('ping'))
    await waitFor(() => expect(api.monitoring.diagnosePing).toHaveBeenCalledTimes(1))
    expect(api.monitoring.diagnosePing.mock.calls[0][1]).toEqual({ traceroute: true })
    const running = await waitFor(() => {
      const el = dlg().querySelector('[data-slot="ndx-running"]')
      if (!el) throw new Error('koşu yok')
      return el
    })
    expect(running.textContent).toMatch(/20 (sn|s)/)
    await act(async () => { release({ success: true, data: pingFiltered() }) })
    await waitFor(() => expect(dlg().querySelector('[data-slot="ndx-result"]')).not.toBeNull())
  })

  it('ping sonucu: hüküm (data-status/code), bulgular sunucu sırasında, adımlar (takılan adım açık, atlanan gerekçeli), istemci, döküm', async () => {
    const { onRunChange } = renderDialog('ping')
    await runWith('ping', pingFiltered())
    const v = dlg().querySelector('[data-slot="ndx-verdict"]')
    expect(v).toHaveAttribute('data-status', 'fail')
    expect(v).toHaveAttribute('data-code', 'ICMP_FILTERED_HOST_ALIVE')
    expect(v.querySelector('[data-slot="ndx-verdict-title"]').textContent).toMatch(/ICMP is filtered, the host is up|ICMP engelleniyor, hedef ayakta/)
    expect(v.querySelector('[data-slot="ndx-verdict-body"]').textContent).toContain('192.0.2.10')
    expect(v.querySelector('[data-slot="ndx-verdict-body"]').textContent).not.toMatch(/\{[a-z_]+\}/)
    const findings = [...dlg().querySelectorAll('[data-slot="ndx-finding"]')]
    expect(findings.map((f) => [f.getAttribute('data-code'), f.getAttribute('data-severity')]))
      .toEqual([['PROXY_NOT_APPLICABLE', 'info'], ['TRACEROUTE_UNAVAILABLE', 'info']])

    const steps = [...dlg().querySelectorAll('[data-slot="ndx-step"]')]
    expect(steps.map((s) => [s.getAttribute('data-step'), s.getAttribute('data-status')]))
      .toEqual([['policy', 'ok'], ['dns', 'ok'], ['icmp', 'fail'], ['tcp_alive', 'ok'], ['traceroute', 'skip']])
    const icmp = steps[2]
    expect(icmp).toHaveAttribute('data-state', 'open')   // takılan adım açık başlar
    expect(icmp.querySelector('[data-key="loss_pct"]').textContent).toMatch(/100/)
    expect(icmp.querySelector('[data-slot="ndx-step-error"]').textContent).toContain('%100 paket kaybı')
    expect(steps[0]).toHaveAttribute('data-state', 'closed')
    expect(steps[4].querySelector('[data-slot="ndx-skip-reason"]').textContent).toMatch(/tool not available|araç yok/)
    // kapalı adımı aç → nesne dizisi satırları
    fireEvent.click(within(steps[3]).getByRole('button'))
    await waitFor(() => expect(steps[3]).toHaveAttribute('data-state', 'open'))
    expect(steps[3].querySelector('[data-key="probes"]').textContent).toMatch(/443/)

    expect(dlg().querySelector('[data-slot="ndx-client"]')).toHaveAttribute('data-state', 'fail')
    const tr = dlg().querySelector('[data-slot="ndx-transcript"]')
    expect([...tr.querySelectorAll('[data-kind]')].map((l) => l.getAttribute('data-kind'))).toEqual(['info', 'section', 'sent', 'recv', 'section', 'info'])
    expect(dlg().querySelector('[data-slot="ndx-source"]').textContent).toContain('worker-07.example.test')
    expect(onRunChange).toHaveBeenLastCalledWith(501)
    // sonuçtan sonra rapor + JSON açık
    expect(within(dlg()).getByRole('button', { name: /raporu kopyala|copy report/i })).toBeEnabled()
  })

  it('port sonucu: PATH_DIFFERS, iki yol kartı (vekil adresi, sonuç rozeti, adım hattı, yol hükmü), yol sekmeleri', async () => {
    renderDialog('port')
    await runWith('port', portPathDiffers())
    expect(api.monitoring.diagnosePort.mock.calls[0][0]).toBe(52)
    expect(dlg('port').querySelector('[data-slot="ndx-verdict"]')).toHaveAttribute('data-code', 'PATH_DIFFERS')
    expect(dlg('port').querySelector('[data-slot="ndx-verdict-body"]').textContent)
      .toMatch(/fails over the Proxy route and succeeds over the Direct route|Vekil yolunda başarısız, Doğrudan yolunda başarılı/)
    const paths = dlg('port').querySelector('[data-slot="ndx-paths"]')
    expect(paths.querySelector('[data-slot="ndx-paths-differ"]')).not.toBeNull()
    const cards = [...paths.querySelectorAll('[data-slot="ndx-path"]')]
    expect(cards.map((c) => [c.getAttribute('data-path'), c.getAttribute('data-outcome')])).toEqual([['monitor', 'fail'], ['alternate', 'ok']])
    expect(cards[0].querySelector('[data-slot="ndx-route"]').textContent).toMatch(/(Vekil|Proxy) · proxy\.example\.test:8080/)
    expect(cards[0].querySelector('[data-step="proxy_tunnel"]')).toHaveAttribute('data-status', 'fail')
    expect(cards[0].querySelector('[data-slot="ndx-path-verdict"]')).toHaveAttribute('data-code', 'PROXY_PORT_NOT_ALLOWED')
    expect(cards[1].querySelector('[data-metric="ip"]').textContent).toContain('203.0.113.11')
    // bulgular: yol etiketiyle
    const f = [...dlg('port').querySelectorAll('[data-slot="ndx-finding"]')].map((x) => x.getAttribute('data-code'))
    expect(f).toEqual(['PROXY_PORT_NOT_ALLOWED', 'SOME_IPS_DOWN'])
    // adımlar yol sekmelerinde: izlemenin yolu varsayılan, öteki yol sekmesinde connect adımı
    expect(dlg('port').querySelector('[data-slot="ndx-steps"][data-path="monitor"] [data-step="proxy_tunnel"]')).not.toBeNull()
    pressMenuTrigger(within(dlg('port')).getByRole('tab', { name: /öteki yol|other route/i }))
    const alt = await waitFor(() => {
      const el = dlg('port').querySelector('[data-slot="ndx-steps"][data-path="alternate"]')
      if (!el) throw new Error('öteki yol adımları yok')
      return el
    })
    const connect = alt.querySelector('[data-step="connect"]')
    expect(connect).toHaveAttribute('data-status', 'ok')
    fireEvent.click(within(connect).getByRole('button'))
    await waitFor(() => expect(connect.querySelector('[data-key="tried"]')).not.toBeNull())
    expect(connect.querySelector('[data-key="tried"]').textContent).toMatch(/203\.0\.113\.12/)
    expect(dlg('port').querySelector('[data-slot="ndx-client"]').textContent).toMatch(/403 Forbidden/)
  })

  it('dns sonucu: çözücü ve yetkili satırları TABLO (geniş kap); durum + rozet; beklenmeyen değer hükmü', async () => {
    renderDialog('dns')
    await runWith('dns', dnsStale())
    expect(dlg('dns').querySelector('[data-slot="ndx-verdict"]')).toHaveAttribute('data-code', 'EXPECTED_MISMATCH')
    const dns = dlg('dns').querySelector('[data-slot="ndx-dns"]')
    expect(dns).toHaveAttribute('data-view', 'table')
    const res = [...dns.querySelectorAll('[data-slot="ndx-dns-resolvers"] [data-slot="ndx-dns-row"]')]
    expect(res.map((r) => r.tagName)).toEqual(['TR', 'TR'])
    expect(res.map((r) => r.getAttribute('data-status'))).toEqual(['ok', 'fail'])
    expect(res[0].textContent).toContain('198.51.100.7')
    expect(res[1].textContent).toMatch(/timed out|zaman aşımı/)
    const auth = [...dns.querySelectorAll('[data-slot="ndx-dns-auth"] [data-slot="ndx-dns-row"]')]
    expect(auth).toHaveLength(2)
    expect(auth[0].textContent).toContain('ns1.example.test')
    expect(auth[1].textContent).toMatch(/TC/)
    // dnssec adımı atlandı (gerekmedi), compare takıldı → açık
    expect(dlg('dns').querySelector('[data-slot="ndx-step"][data-step="dnssec"]')).toHaveAttribute('data-status', 'skip')
    expect(dlg('dns').querySelector('[data-slot="ndx-step"][data-step="compare"]')).toHaveAttribute('data-state', 'open')
    expect(dlg('dns').querySelector('[data-slot="ndx-client"]')).toHaveAttribute('data-state', 'ok')
  })

  it('dns: dar kapta (360 px) satırlar KART olur', async () => {
    width.value = 360
    renderDialog('dns')
    await runWith('dns', dnsStale())
    const dns = dlg('dns').querySelector('[data-slot="ndx-dns"]')
    expect(dns).toHaveAttribute('data-view', 'cards')
    const rows = [...dns.querySelectorAll('[data-slot="ndx-dns-row"]')]
    expect(rows).toHaveLength(4)
    expect(rows.every((r) => r.tagName === 'LI')).toBe(true)
    expect(dns.querySelector('table')).toBeNull()
  })

  it('Geçmiş: liste → satır → KAYITLI çalıştırma; canlı koşu yok', async () => {
    const { onRunChange } = renderDialog('ping')
    fireEvent.click(within(dlg()).getByRole('button', { name: /^(geçmiş|history)$/i }))
    const row = await waitFor(() => {
      const el = dlg().querySelector('[data-slot="ndx-history-row"][data-run="501"]')
      if (!el) throw new Error('satır yok')
      return el
    })
    expect(api.monitoring.pingDiagnoseHistory).toHaveBeenCalledWith(41)
    expect(row.textContent).toMatch(/ICMP is filtered|ICMP engelleniyor/)
    expect(row.textContent).toMatch(/with traceroute|traceroute ile/)
    expect(row.querySelector('[data-status="fail"]')).not.toBeNull()
    expect(dlg().querySelectorAll('[data-slot="ndx-history-row"]')).toHaveLength(2)
    fireEvent.click(row)
    await waitFor(() => expect(dlg().querySelector('[data-slot="ndx-result"][data-stored="true"]')).not.toBeNull())
    expect(api.monitoring.pingDiagnoseRun).toHaveBeenCalledWith(41, 501)
    expect(api.monitoring.diagnosePing).not.toHaveBeenCalled()
    expect(dlg().querySelector('[data-slot="ndx-stored"]')).not.toBeNull()
    expect(onRunChange).toHaveBeenLastCalledWith(501)
  })

  it.each(['ping', 'port', 'dns'])('%s: derin bağlantı (initialRunId) kayıtlı çalıştırmayı açar, canlı tanılama BAŞLATMAZ', async (type) => {
    const runId = { ping: 501, port: 602, dns: 703 }[type]
    renderDialog(type, { initialRunId: runId })
    await waitFor(() => expect(dlg(type).querySelector('[data-slot="ndx-result"][data-stored="true"]')).not.toBeNull())
    expect(api.monitoring[`${type}DiagnoseRun`]).toHaveBeenCalledWith(ROWS[type].id, runId)
    expect(RUN[type]()).not.toHaveBeenCalled()
    expect(dlg(type).querySelector('[data-slot="ndx-verdict"]')).not.toBeNull()
    expect(dlg(type).querySelectorAll('[data-slot="ndx-finding"]').length).toBeGreaterThan(0)
  })

  it('429 → hız sınırı şeridi (geri sayım), hata şeridi DEĞİL', async () => {
    renderDialog('port')
    api.monitoring.diagnosePort.mockResolvedValueOnce({ success: false, status: 429, error: 'too many' })
    fireEvent.click(startBtn('port'))
    await waitFor(() => expect(dlg('port').querySelector('[data-slot="ndx-rate-limit"]')).not.toBeNull())
    expect(dlg('port').querySelector('[data-slot="ndx-rate-limit"]').textContent).toMatch(/60 (sn|s)/)
    expect(dlg('port').querySelector('[data-slot="ndx-error-msg"]')).toBeNull()
  })

  it('403 → satır içi hata + yeniden dene (sunucu mesajı gösterilir)', async () => {
    renderDialog('dns')
    api.monitoring.diagnoseDns.mockResolvedValueOnce({ success: false, status: 403, error: 'Bu izlemeyi tanılama yetkiniz yok' })
    fireEvent.click(startBtn('dns'))
    const msg = await waitFor(() => {
      const el = dlg('dns').querySelector('[data-slot="ndx-error-msg"]')
      if (!el) throw new Error('hata yok')
      return el
    })
    expect(msg).toHaveAttribute('data-kind', 'forbidden')
    expect(msg.textContent).toBe('Bu izlemeyi tanılama yetkiniz yok')
    api.monitoring.diagnoseDns.mockResolvedValueOnce({ success: true, data: dnsStale() })
    fireEvent.click(within(dlg('dns')).getByRole('button', { name: /^(yeniden dene|try again)$/i }))
    await waitFor(() => expect(dlg('dns').querySelector('[data-slot="ndx-result"]')).not.toBeNull())
    expect(api.monitoring.diagnoseDns).toHaveBeenCalledTimes(2)
  })

  it('İptal: istek kesilir (signal aborted), iptal şeridi; geç yanıt yok sayılır', async () => {
    let seen
    api.monitoring.diagnosePing.mockImplementationOnce((id, b, { signal }) => new Promise((resolve) => {
      seen = signal
      signal.addEventListener('abort', () => setTimeout(() => resolve({ success: true, data: pingFiltered() }), 0))
    }))
    renderDialog('ping')
    fireEvent.click(startBtn('ping'))
    await waitFor(() => expect(dlg().querySelector('[data-slot="ndx-running"]')).not.toBeNull())
    expect(dlg().querySelector('[data-slot="ndx-elapsed"]')).not.toBeNull()
    fireEvent.click(dlg().querySelector('[data-slot="ndx-cancel"]'))
    expect(seen.aborted).toBe(true)
    await waitFor(() => expect(dlg().querySelector('[data-slot="ndx-cancelled"]')).not.toBeNull())
    await act(async () => { await new Promise((r) => setTimeout(r, 10)) })
    expect(dlg().querySelector('[data-slot="ndx-result"]')).toBeNull()
  })

  it('Raporu kopyala → Markdown panoya gider (maskeli), başarı bildirimi', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    navigator.clipboard.writeText = writeText
    renderDialog('port')
    await runWith('port', portPathDiffers())
    pressMenuTrigger(within(dlg('port')).getByRole('button', { name: /raporu kopyala|copy report/i }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /markdown/i }))
    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1))
    const text = writeText.mock.calls[0][0]
    expect(text).toMatch(/^# SiteMonitor — Port (tanılama raporu|diagnosis report)/)
    expect(text).toContain('Proxy-Authorization: ••••')
    expect(await screen.findByText(/rapor panoya kopyalandı|report copied to the clipboard/i)).toBeInTheDocument()
  })

  it('JSON indir: dosya adı <tür>-diagnose-<izleme>-run<no>.json', async () => {
    const create = vi.fn(() => 'blob:x')
    const orig = { c: URL.createObjectURL, r: URL.revokeObjectURL }
    URL.createObjectURL = create
    URL.revokeObjectURL = vi.fn()
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () { clicked.lastDownload = this.download })
    try {
      renderDialog('dns')
      await runWith('dns', dnsStale())
      fireEvent.click(within(dlg('dns')).getByRole('button', { name: /json indir|download json/i }))
      expect(create).toHaveBeenCalledTimes(1)
      expect(clicked.lastDownload).toBe('dns-diagnose-63-run703.json')
    } finally { URL.createObjectURL = orig.c; URL.revokeObjectURL = orig.r; clicked.mockRestore() }
  })
})
