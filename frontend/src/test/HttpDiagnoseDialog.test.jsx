import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { HISTORY_ROWS, MONITOR_ROW, SECRET, okSingle, pathDiffers } from './helpers/httpDiagnoseFixtures.js'
import HttpDiagnoseDialog from '../components/http/diagnose/HttpDiagnoseDialog.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  getRecentFailures: () => [],
  api: withApiFallback({
    monitoring: {
      diagnoseHttp: vi.fn(),
      httpDiagnoseHistory: vi.fn(),
      httpDiagnoseRun: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * HTTP uçtan uca tanılama penceresi (2026-10-02). Sözleşme: açılışta ASLA koşmaz; başlat → hüküm/yollar/ayrıntı;
 * PATH_DIFFERS (vekil yolu yanıt alamıyor, doğrudan 24 ms'de 401) iki yol kartını, dolu hüküm metnini ve takılan adımı
 * gösterir; gizli başlık rozetli ve değeri yok; gövde önizlemesi + kesik notu; döküm süzgeci; rapor panoya; 429 şeridi;
 * geçmişten kayıtlı çalıştırma; İptal isteği keser.
 */
const dlg = () => screen.getByRole('dialog', { name: /http tanılama|http diagnosis/i })
const startBtn = () => within(dlg()).getByRole('button', { name: /^(Tanılamayı başlat|Start diagnosis)$/ })

function renderDialog(props = {}) {
  const onClose = vi.fn()
  const onRunChange = vi.fn()
  render(<HttpDiagnoseDialog monitor={MONITOR_ROW} onClose={onClose} onRunChange={onRunChange} {...props} />)
  return { onClose, onRunChange }
}

async function runWith(data) {
  api.monitoring.diagnoseHttp.mockResolvedValueOnce({ success: true, data })
  fireEvent.click(startBtn())
  return waitFor(() => {
    const el = document.querySelector('[data-slot="httpdx-result"]')
    if (!el) throw new Error('sonuç yok')
    return el
  })
}

describe('HttpDiagnoseDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.httpDiagnoseHistory.mockResolvedValue({ success: true, data: HISTORY_ROWS })
    api.monitoring.httpDiagnoseRun.mockResolvedValue({ success: true, data: (() => {
      const d = pathDiffers()
      for (const p of d.paths) for (const h of p.hops) if (h.response?.body) { h.response.body.preview = null; h.response.body.preview_truncated = false }
      return d
    })() })
  })

  it('başlangıç ekranı: neyin deneneceğini söyler, düğmeye basılana kadar API ÇAĞRILMAZ', async () => {
    renderDialog()
    const start = dlg().querySelector('[data-slot="httpdx-start"]')
    expect(start).not.toBeNull()
    expect(dlg().querySelector('[data-slot="httpdx-own-route"]').getAttribute('data-via')).toBe('proxy')
    expect(within(start).getByText(/10000 ms/)).toBeInTheDocument()
    expect(within(dlg()).getByRole('switch', { name: /öteki yolu da dene|also try the other route/i })).toBeChecked()
    expect(dlg().querySelector('[data-slot="httpdx-url"]').textContent).toBe('http://binbirfikir.example.com/')
    await act(async () => { await Promise.resolve() })
    expect(api.monitoring.diagnoseHttp).not.toHaveBeenCalled()
    // rapor/JSON sonuç yokken kapalı
    expect(within(dlg()).getByRole('button', { name: /raporu kopyala|copy report/i })).toBeDisabled()
    expect(within(dlg()).getByRole('button', { name: /json indir|download json/i })).toBeDisabled()
  })

  it('karşılaştırma anahtarı kapatılırsa compare:false gönderilir', async () => {
    renderDialog()
    fireEvent.click(within(dlg()).getByRole('switch', { name: /öteki yolu da dene|also try the other route/i }))
    api.monitoring.diagnoseHttp.mockResolvedValueOnce({ success: true, data: okSingle() })
    fireEvent.click(startBtn())
    await waitFor(() => expect(api.monitoring.diagnoseHttp).toHaveBeenCalledTimes(1))
    const [id, body, opts] = api.monitoring.diagnoseHttp.mock.calls[0]
    expect(id).toBe(36)
    expect(body).toEqual({ compare: false })
    expect(opts.signal).toBeInstanceOf(AbortSignal)
  })

  it('PATH_DIFFERS: iki yol kartı, dolu hüküm metni, takılan adım vurgulu, fark uyarısı; çalıştırma no bildirilir', async () => {
    const { onRunChange } = renderDialog()
    await runWith(pathDiffers())
    expect(api.monitoring.diagnoseHttp.mock.calls[0][1]).toEqual({ compare: true })

    const verdict = dlg().querySelector('[data-slot="httpdx-verdict"]')
    expect(verdict.getAttribute('data-status')).toBe('fail')
    expect(verdict.querySelector('[data-slot="httpdx-verdict-badge"]').getAttribute('data-status')).toBe('fail')
    expect(verdict.querySelector('[data-slot="httpdx-verdict-title"]').textContent)
      .toMatch(/İzlemenin yolu takılıyor, öteki yol çalışıyor|Monitor's route is stuck, the other route works/)
    const body = verdict.querySelector('[data-slot="httpdx-verdict-body"]').textContent
    expect(body).toMatch(/Vekil yolunda istek tamamlanamadı; Doğrudan yol HTTP 401|over the Proxy route; the Direct route got an HTTP 401/)
    expect(body).not.toMatch(/\{[a-z_]+\}/)   // yer tutucu kalmadı
    expect(verdict.querySelector('[data-slot="httpdx-verdict-step"]').getAttribute('data-step')).toBe('response')
    // diğer bulgular (hükümle aynı olan tekrar etmez)
    expect([...verdict.querySelectorAll('[data-slot="httpdx-findings"] > li')].map((li) => li.getAttribute('data-code'))).toEqual(['RESPONSE_TIMEOUT', 'AUTH_REQUIRED'])

    const cmp = dlg().querySelector('[data-slot="httpdx-compare"]')
    expect(cmp).not.toBeNull()
    expect(cmp.querySelector('[data-slot="httpdx-compare-diff"]')).not.toBeNull()
    const cards = cmp.querySelectorAll('[data-slot="httpdx-path-card"]')
    expect([...cards].map((c) => c.getAttribute('data-path'))).toEqual(['monitor', 'alternate'])
    const mon = cmp.querySelector('[data-slot="httpdx-path-card"][data-path="monitor"]')
    expect(mon.querySelector('[data-slot="httpdx-route"]').textContent).toMatch(/(Vekil|Proxy) · dmzproxy\.example\.local:8080/)
    const failedStep = mon.querySelector('[data-step="response"]')
    expect(failedStep.getAttribute('data-status')).toBe('fail')
    expect(failedStep.getAttribute('data-failed')).toBe('true')
    expect(mon.querySelector('[data-step="dns"]').getAttribute('data-failed')).toBeNull()
    const alt = cmp.querySelector('[data-slot="httpdx-path-card"][data-path="alternate"]')
    expect(alt.querySelector('[data-metric="status"]').textContent).toMatch(/^401/)
    expect(alt.querySelector('[data-metric="total"]').textContent).toMatch(/^24 ms/)

    // ayrıntı sekmeleri: varsayılan hükmün yolu (izlemenin yolu); şelalede "yanıt beklendi" bölümü
    expect(within(dlg()).getByRole('tab', { selected: true }).getAttribute('data-path')).toBe('monitor')
    const wait = dlg().querySelector('[data-slot="httpdx-path-detail"][data-path="monitor"] [data-segment="waitResponse"]')
    expect(wait.getAttribute('data-ms')).toBe('9997')
    expect(onRunChange).toHaveBeenLastCalledWith(123)
    // kaynak + çıkış notu
    expect(dlg().querySelector('[data-slot="httpdx-source"]').textContent).toContain('worker-22.example.local')
    expect(dlg().querySelector('[data-slot="httpdx-egress"]')).not.toBeNull()
  })

  it('maskeli başlık "gizli" rozetiyle çizilir; sır değeri hiçbir yerde görünmez', async () => {
    renderDialog()
    await runWith(pathDiffers())
    const detail = dlg().querySelector('[data-slot="httpdx-path-detail"][data-path="monitor"]')
    const hop = detail.querySelector('[data-slot="httpdx-hop"][data-hop="0"]')
    expect(hop.getAttribute('data-state')).toBe('open')   // takılan hop açık başlar
    const masked = hop.querySelectorAll('[data-slot="httpdx-request"] [data-slot="httpdx-header"][data-masked="true"]')
    expect(masked).toHaveLength(2)
    for (const row of masked) expect(within(row).getByText(/^(gizli|hidden)$/)).toBeInTheDocument()
    expect(document.body.textContent).not.toContain(SECRET)
    expect(hop.querySelector('[data-slot="httpdx-request"]').textContent).toContain('••••')
  })

  it('öteki yol sekmesi: yönlendirme zinciri, gövde önizlemesi + 32 KB kesik notu, döküm süzgeci', async () => {
    renderDialog()
    await runWith(pathDiffers())
    pressMenuTrigger(within(dlg()).getByRole('tab', { name: /öteki yol|other route/i }))
    const detail = await waitFor(() => {
      const el = dlg().querySelector('[data-slot="httpdx-path-detail"][data-path="alternate"]')
      if (!el) throw new Error('öteki yol çizilmedi')
      return el
    })
    const hops = detail.querySelectorAll('[data-slot="httpdx-hop"]')
    expect(hops).toHaveLength(2)
    expect(hops[1].getAttribute('data-state')).toBe('open')   // son hop açık
    expect(hops[1].querySelector('[data-slot="httpdx-body"]').textContent).toContain('<title>Giriş gerekli</title>')
    expect(hops[1].querySelector('[data-slot="httpdx-body-truncated"]')).not.toBeNull()
    expect(hops[1].querySelector('[data-slot="httpdx-status-line"]').textContent).toBe('HTTP/1.1 401 Unauthorized')
    // ilk hop'u aç → yönlendirme bölümü
    fireEvent.click(within(hops[0]).getByRole('button'))
    await waitFor(() => expect(hops[0].querySelector('[data-slot="httpdx-redirect"]')).not.toBeNull())
    expect(hops[0].querySelector('[data-slot="httpdx-redirect"]').textContent).toContain('/login')

    // döküm süzgeci: Gönderilen → yalnız ">" satırları
    const tr = detail.querySelector('[data-slot="httpdx-transcript"]')
    const lines = () => [...tr.querySelectorAll('[data-slot="httpdx-transcript-lines"] [data-kind]')]
    expect(lines()).toHaveLength(12)
    fireEvent.click(within(tr).getByRole('button', { name: /^(Gönderilen|Sent)$/ }))
    await waitFor(() => expect(lines().every((l) => l.getAttribute('data-kind') === 'sent')).toBe(true))
    expect(lines()).toHaveLength(4)
    fireEvent.click(within(tr).getByRole('button', { name: /^(Alınan|Received)$/ }))
    await waitFor(() => expect(lines().every((l) => l.textContent.startsWith('<'))).toBe(true))
  })

  it('Raporu kopyala → Markdown panoya gider (maskeli), başarı bildirimi', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    navigator.clipboard.writeText = writeText
    renderDialog()
    await runWith(pathDiffers())
    const trigger = within(dlg()).getByRole('button', { name: /raporu kopyala|copy report/i })
    expect(trigger).toBeEnabled()
    pressMenuTrigger(trigger)
    fireEvent.click(await screen.findByRole('menuitem', { name: /markdown/i }))
    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1))
    const text = writeText.mock.calls[0][0]
    expect(text).toMatch(/^# SiteMonitor — HTTP (tanılama raporu|diagnosis report)/)
    expect(text).toContain('Authorization: ••••')
    expect(text).not.toContain(SECRET)
    expect(await screen.findByText(/rapor panoya kopyalandı|report copied to the clipboard/i)).toBeInTheDocument()
  })

  it('JSON indir sonucu Blob olarak indirir', async () => {
    const create = vi.fn(() => 'blob:x')
    const revoke = vi.fn()
    const orig = { c: URL.createObjectURL, r: URL.revokeObjectURL }
    URL.createObjectURL = create
    URL.revokeObjectURL = revoke
    // jsdom gezinmeyi uygulamaz ("navigation to another Document") — bağlantı tıklaması yakalanır, adı doğrulanır
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () { clicked.lastDownload = this.download })
    try {
      renderDialog()
      await runWith(pathDiffers())
      fireEvent.click(within(dlg()).getByRole('button', { name: /json indir|download json/i }))
      expect(create).toHaveBeenCalledTimes(1)
      expect(create.mock.calls[0][0]).toBeInstanceOf(Blob)
      expect(clicked.lastDownload).toBe('http-diagnose-36-run123.json')
    } finally { URL.createObjectURL = orig.c; URL.revokeObjectURL = orig.r; clicked.mockRestore() }
  })

  it('vekil yok (tek yol, OK): karşılaştırma ve sekme yok; TLS zinciri kartları', async () => {
    renderDialog({ monitor: { ...MONITOR_ROW, id: 7, proxy_effective: 'direct', proxy_source: 'monitor' } })
    await runWith(okSingle())
    expect(dlg().querySelector('[data-slot="httpdx-compare"]')).toBeNull()
    expect(dlg().querySelector('[data-slot="httpdx-compare-diff"]')).toBeNull()
    expect(dlg().querySelector('[data-slot="httpdx-paths"]')).not.toBeNull()
    expect(dlg().querySelectorAll('[data-slot="httpdx-path-card"]')).toHaveLength(1)
    expect(within(dlg()).queryByRole('tablist')).toBeNull()
    expect(dlg().querySelector('[data-slot="httpdx-verdict"]').getAttribute('data-status')).toBe('ok')
    const certs = dlg().querySelectorAll('[data-slot="httpdx-chain"] [data-slot="httpdx-cert"]')
    expect(certs).toHaveLength(2)
    expect(certs[0].getAttribute('data-days')).toBe('91')
    expect(dlg().querySelector('[data-slot="httpdx-client"]').getAttribute('data-agrees')).toBe('true')
  })

  it('429 → hız sınırı şeridi (geri sayım), hata şeridi DEĞİL', async () => {
    renderDialog()
    api.monitoring.diagnoseHttp.mockResolvedValueOnce({ success: false, status: 429, error: 'too many' })
    fireEvent.click(startBtn())
    await waitFor(() => expect(dlg().querySelector('[data-slot="httpdx-rate-limit"]')).not.toBeNull())
    expect(dlg().querySelector('[data-slot="httpdx-rate-limit"]').textContent).toMatch(/60 (sn|s)/)
    expect(dlg().querySelector('[data-slot="httpdx-error-msg"]')).toBeNull()
  })

  it('403 → satır içi hata + yeniden dene (sunucu mesajı gösterilir)', async () => {
    renderDialog()
    api.monitoring.diagnoseHttp.mockResolvedValueOnce({ success: false, status: 403, error: 'Bu izlemeyi tanılama yetkiniz yok' })
    fireEvent.click(startBtn())
    const msg = await waitFor(() => {
      const el = dlg().querySelector('[data-slot="httpdx-error-msg"]')
      if (!el) throw new Error('hata yok')
      return el
    })
    expect(msg.getAttribute('data-kind')).toBe('forbidden')
    expect(msg.textContent).toBe('Bu izlemeyi tanılama yetkiniz yok')
    api.monitoring.diagnoseHttp.mockResolvedValueOnce({ success: true, data: okSingle() })
    fireEvent.click(within(dlg()).getByRole('button', { name: /^(yeniden dene|try again)$/i }))
    await waitFor(() => expect(dlg().querySelector('[data-slot="httpdx-result"]')).not.toBeNull())
    expect(api.monitoring.diagnoseHttp).toHaveBeenCalledTimes(2)
  })

  it('Geçmiş: liste → satır → KAYITLI çalıştırma (gövde önizlemesi saklanmaz notu); canlı koşu yok', async () => {
    const { onRunChange } = renderDialog()
    fireEvent.click(within(dlg()).getByRole('button', { name: /^(geçmiş|history)$/i }))
    const row = await waitFor(() => {
      const el = dlg().querySelector('[data-slot="httpdx-history-row"][data-run="123"]')
      if (!el) throw new Error('satır yok')
      return el
    })
    expect(api.monitoring.httpDiagnoseHistory).toHaveBeenCalledWith(36)
    expect(row.textContent).toMatch(/İzlemenin yolu takılıyor|Monitor's route is stuck/)
    expect(row.querySelector('[data-status="fail"]')).not.toBeNull()
    fireEvent.click(row)
    await waitFor(() => expect(dlg().querySelector('[data-slot="httpdx-result"][data-stored="true"]')).not.toBeNull())
    expect(api.monitoring.httpDiagnoseRun).toHaveBeenCalledWith(36, 123)
    expect(api.monitoring.diagnoseHttp).not.toHaveBeenCalled()
    expect(dlg().textContent).toMatch(/Kayıtlı çalıştırma #123|Stored run #123/)
    pressMenuTrigger(within(dlg()).getByRole('tab', { name: /öteki yol|other route/i }))
    await waitFor(() => expect(dlg().querySelector('[data-slot="httpdx-path-detail"][data-path="alternate"] [data-slot="httpdx-body-note"]')).not.toBeNull())
    expect(onRunChange).toHaveBeenLastCalledWith(123)
  })

  it('derin bağlantı (initialRunId) kayıtlı çalıştırmayı açar, canlı tanılama BAŞLATMAZ', async () => {
    renderDialog({ initialRunId: 123 })
    await waitFor(() => expect(dlg().querySelector('[data-slot="httpdx-result"][data-stored="true"]')).not.toBeNull())
    expect(api.monitoring.httpDiagnoseRun).toHaveBeenCalledWith(36, 123)
    expect(api.monitoring.diagnoseHttp).not.toHaveBeenCalled()
  })

  it('İptal: istek kesilir (signal aborted), iptal şeridi + Geçmiş ipucu; geç yanıt yok sayılır', async () => {
    let seen
    api.monitoring.diagnoseHttp.mockImplementationOnce((id, body, { signal }) => new Promise((resolve) => {
      seen = signal
      signal.addEventListener('abort', () => setTimeout(() => resolve({ success: true, data: pathDiffers() }), 0))
    }))
    renderDialog()
    fireEvent.click(startBtn())
    await waitFor(() => expect(dlg().querySelector('[data-slot="httpdx-running"]')).not.toBeNull())
    expect(dlg().querySelector('[data-slot="httpdx-elapsed"]')).not.toBeNull()
    fireEvent.click(within(dlg()).getByRole('button', { name: /^(iptal|cancel)$/i }))
    expect(seen.aborted).toBe(true)
    await waitFor(() => expect(dlg().querySelector('[data-slot="httpdx-cancelled"]')).not.toBeNull())
    await act(async () => { await new Promise((r) => setTimeout(r, 10)) })
    expect(dlg().querySelector('[data-slot="httpdx-result"]')).toBeNull()   // iptalden sonra gelen yanıt çizilmez
  })
})
