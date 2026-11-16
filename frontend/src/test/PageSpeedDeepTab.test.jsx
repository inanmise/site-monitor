import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import PageSpeedDeepTab from '../components/pagespeed/deep/PageSpeedDeepTab.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getPageSpeedWaterfall: vi.fn(),
      getPageSpeedResources: vi.fn(),
      pageSpeedImage: vi.fn(),
    },
  }),
}))
const report = vi.hoisted(() => ({ exportPageSpeedPdf: vi.fn(), exportPageSpeedXlsx: vi.fn() }))
vi.mock('../components/pagespeed/deep/pageSpeedReport.js', () => report)
import { api } from '../api/client'

/**
 * Sayfa Hızı 2. sürüm (2026-11-15): "Şelale ve hız notu" sekmesi — anlık görüntü okuma durumları (hazır / eski sürüm /
 * okunamadı / başlangıçsız eski ölçüm), ölçüm seçici, iki ölçümü karşılaştırma, rapor indirme sonucu, görsel satırında
 * görsel aracı, geç gelen yanıtın atılması.
 */
const KB = 1024
/** Seçici görünene dek bekler (querySelector null döndürürse waitFor yeniden dener). */
const el = (sel, root = document) => waitFor(() => {
  const n = root.querySelector(sel)
  if (!n) throw new Error(`yok: ${sel}`)
  return n
})
const monitor = { id: 9, name: 'Ödeme sayfası', url: 'https://x.com/odeme', can_check: true }

function snapshot(checkId, over = {}, resources) {
  return {
    success: true,
    data: {
      check_id: checkId, has_start: true, truncated: false,
      check: { checked_at: '2026-11-15T10:00:00', ttfb_ms: 120, html_ms: 300, response_ms: 900, total_bytes: 900 * KB,
        html_bytes: 40 * KB, request_count: 3, failed_count: 0, dns_ms: 10, connect_ms: 20, tls_ms: 30, server_ms: 60, ...over },
      resources: resources ?? [
        { url: 'https://x.com/app.css', type: 'CSS', start_ms: 310, duration_ms: 50, bytes: 10 * KB, http_status: 200 },
        { url: 'https://x.com/hero.jpg', type: 'IMG', start_ms: 400, duration_ms: 200, bytes: 120 * KB, http_status: 200 },
      ],
      total: (resources ?? [1, 2]).length,
    },
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { items: [], breaches: [] } })
  api.monitoring.getPageSpeedWaterfall.mockResolvedValue(snapshot(41))
})

describe('PageSpeedDeepTab', () => {
  it('son ölçüm: hız notu, şelale (HTML + kaynak satırları), görsel satırında görsel aracı menüsü', async () => {
    render(<PageSpeedDeepTab monitor={monitor} />)
    const grade = await waitFor(() => {
      const g = document.querySelector('[data-slot="pspd-grade"]')
      if (!g) throw new Error('grade')
      return g
    })
    expect(grade.getAttribute('data-letter')).toBe('A')
    const rows = document.querySelectorAll('[data-slot="pspd-wf-row"]')
    expect([...rows].map((r) => r.getAttribute('data-type'))).toEqual(['html', 'css', 'img'])
    expect(api.monitoring.getPageSpeedWaterfall).toHaveBeenCalledWith(9, { checkId: undefined })
    // görsel satırı: aç → görsel aracı
    const imgRow = rows[2]
    fireEvent.click(within(imgRow).getByRole('button'))
    const detail = await el('[data-slot="pspd-wf-detail"]', imgRow)
    expect(detail.textContent).toContain('https://x.com/hero.jpg')
    expect(detail.querySelector('[data-slot="prs-it-actions"]')).toBeTruthy()
    // CSS satırında görsel aracı yok
    fireEvent.click(within(rows[1]).getByRole('button'))
    const cssDetail = await el('[data-slot="pspd-wf-detail"]', rows[1])
    expect(cssDetail.querySelector('[data-slot="prs-it-actions"]')).toBeNull()
    // tek ölçüm: karşılaştırma kapalı + ipucu
    expect(document.querySelector('[data-slot="pspd-deep-compare"]').disabled).toBe(true)
    expect(document.querySelector('[data-slot="pspd-deep-compare-hint"]')).toBeTruthy()
  })

  it('eski pod (404 NOT_FOUND) → "bu sürümde yok"; 500 → okunamadı + yeniden dene', async () => {
    api.monitoring.getPageSpeedWaterfall.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'x' })
    const { unmount } = render(<PageSpeedDeepTab monitor={monitor} />)
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-deep-unsupported"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="pspd-grade"]')).toBeNull()
    unmount()

    api.monitoring.getPageSpeedWaterfall.mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu yanıt vermedi — biraz sonra yeniden deneyin.' })
    render(<PageSpeedDeepTab monitor={monitor} />)
    const failed = await el('[data-slot="pspd-deep-failed"]')
    expect(failed.textContent).toContain('Sunucu yanıt vermedi')
    fireEvent.click(within(failed).getByRole('button'))
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-grade"]')).toBeTruthy())
  })

  it('kodsuz 404 = izleme silinmiş (eski pod sayılmaz); 405 = bu sürümde yok', async () => {
    api.monitoring.getPageSpeedWaterfall.mockResolvedValueOnce({ success: false, status: 404, error: 'Sayfa hızı izlemesi bulunamadı' })
    const { unmount } = render(<PageSpeedDeepTab monitor={monitor} />)
    await el('[data-slot="pspd-deep-gone"]')
    expect(document.querySelector('[data-slot="pspd-deep-unsupported"]')).toBeNull()
    unmount()
    api.monitoring.getPageSpeedWaterfall.mockResolvedValueOnce({ success: false, status: 405 })
    render(<PageSpeedDeepTab monitor={monitor} />)
    await el('[data-slot="pspd-deep-unsupported"]')
  })

  it('son ölçümde kaynak saklanmadıysa not "son ölçüm" diye sunulmaz — nötr ton, tarihiyle', async () => {
    render(<PageSpeedDeepTab monitor={{ ...monitor, last_check: '2026-11-15T11:30:00' }} />)
    const g = await el('[data-slot="pspd-grade"]')
    expect(g.getAttribute('data-older')).toBe('true')
    expect(g.textContent).toContain('latest measurement with stored resources')
    expect(g.querySelector('.text-success')).toBeNull()
  })

  it('aynı tarih: son ölçüm (yeşil ton serbest)', async () => {
    render(<PageSpeedDeepTab monitor={{ ...monitor, last_check: '2026-11-15T10:00:00' }} />)
    const g = await el('[data-slot="pspd-grade"]')
    expect(g.getAttribute('data-older')).toBeNull()
  })

  it('başlangıç anı saklanmamış eski ölçüm: not çizilir, şelale yerine açıklama', async () => {
    api.monitoring.getPageSpeedWaterfall.mockResolvedValue({ ...snapshot(5, {}, [{ url: 'https://x.com/a.js', type: 'JS', start_ms: null, duration_ms: 10, bytes: 5 }]),
      data: { ...snapshot(5, {}, [{ url: 'https://x.com/a.js', type: 'JS', start_ms: null, duration_ms: 10, bytes: 5 }]).data, has_start: false } })
    render(<PageSpeedDeepTab monitor={monitor} />)
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-wf-nostart"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="pspd-grade"]')).toBeTruthy()
  })

  it('kaynaksız ve ölçümsüz yanıt: boş durum (hız notu çizilmez)', async () => {
    api.monitoring.getPageSpeedWaterfall.mockResolvedValue({ success: true, data: { check_id: null, check: null, resources: [], total: 0 } })
    render(<PageSpeedDeepTab monitor={monitor} />)
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-deep-empty"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="pspd-grade"]')).toBeNull()
  })

  it('ihlal anı seçilir → o ölçüm okunur; karşılaştırma iki ölçümü yan yana koyar (son ölçüm önbellekten)', async () => {
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { items: [], breaches: [{ check_id: 33, checked_at: '2026-11-14T08:00:00' }] } })
    api.monitoring.getPageSpeedWaterfall.mockImplementation((id, { checkId } = {}) =>
      Promise.resolve(checkId == null ? snapshot(41) : snapshot(33, { response_ms: 4200, ttfb_ms: 900 })))
    render(<PageSpeedDeepTab monitor={monitor} />)
    const sel = await el('[data-slot="pspd-deep-snapshot"]')
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-grade"]')).toBeTruthy())
    fireEvent.change(sel, { target: { value: '33' } })
    await waitFor(() => expect(api.monitoring.getPageSpeedWaterfall).toHaveBeenCalledWith(9, { checkId: '33' }))
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-grade"]')?.getAttribute('data-letter')).not.toBe('A'))
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-grade"]')).toBeTruthy())

    fireEvent.click(document.querySelector('[data-slot="pspd-deep-compare"]'))
    const dlg = await el('[data-slot="pspd-cmp"]')
    await waitFor(() => expect(dlg.querySelector('[data-slot="pspd-cmp-table"]')).toBeTruthy())
    const load = dlg.querySelector('[data-slot="pspd-cmp-row"][data-key="load"]')
    expect(load.getAttribute('data-worse')).toBe('true')       // A = son ölçüm (900), B = ihlal (4200)
    // her iki ölçüm de önbellekte: ek okuma yok (son + ihlal = 2 çağrı)
    expect(api.monitoring.getPageSpeedWaterfall).toHaveBeenCalledTimes(2)
  })

  it('rapor: indirme başlarsa başarı, başlamazsa açık hata; düğme hazırlanırken kapalı', async () => {
    let resolvePdf
    report.exportPageSpeedPdf.mockImplementation(() => new Promise((r) => { resolvePdf = r }))
    report.exportPageSpeedXlsx.mockResolvedValue(false)
    render(<PageSpeedDeepTab monitor={monitor} />)
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-grade"]')).toBeTruthy())
    const pdf = document.querySelector('[data-slot="pspd-deep-pdf"]')
    fireEvent.click(pdf)
    await waitFor(() => expect(report.exportPageSpeedPdf).toHaveBeenCalled())
    expect(pdf.disabled).toBe(true)
    expect(document.querySelector('[data-slot="pspd-deep-xlsx"]').disabled).toBe(true)
    const arg = report.exportPageSpeedPdf.mock.calls[0][0]
    expect(arg.monitor.id).toBe(9)
    expect(arg.data.check_id).toBe(41)
    expect(arg.grade.letter).toBe('A')
    resolvePdf(true)
    expect(await screen.findByText('The PDF report was downloaded.')).toBeTruthy()
    await waitFor(() => expect(pdf.disabled).toBe(false))
    fireEvent.click(document.querySelector('[data-slot="pspd-deep-xlsx"]'))
    expect(await screen.findByText(/could not start the download/)).toBeTruthy()
  })

  it('yeni ölçüm yüklenirken eski anlık görüntü rapora gitmez (rapor düğmeleri kapalı)', async () => {
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { items: [], breaches: [{ check_id: 33, checked_at: 'b' }] } })
    let resolveB
    api.monitoring.getPageSpeedWaterfall.mockImplementation((id, { checkId } = {}) => (checkId == null
      ? Promise.resolve(snapshot(41)) : new Promise((r) => { resolveB = () => r(snapshot(33, { response_ms: 4200 })) })))
    render(<PageSpeedDeepTab monitor={monitor} />)
    await el('[data-slot="pspd-grade"]')
    const sel = await el('[data-slot="pspd-deep-snapshot"]')
    fireEvent.change(sel, { target: { value: '33' } })
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-deep-pdf"]').disabled).toBe(true))
    expect(document.querySelector('[data-slot="pspd-deep-xlsx"]').disabled).toBe(true)
    expect(document.querySelector('[data-slot="pspd-grade"]')).toBeNull()   // eski not gösterilmez
    resolveB()
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-deep-pdf"]').disabled).toBe(false))
  })

  it('geç gelen eski yanıt atılır (seçici hızlı değişince)', async () => {
    // İhlal anları şelale yanıtında (inceleme 2026-11-17). Seçilen ihlal anı YAVAŞ; kullanıcı hemen "son ölçüm"e döner —
    // geç gelen ihlal yanıtı son ölçümün notunu ezmemeli.
    const breaches = [{ check_id: 33, checked_at: 'b' }]
    let resolveSlow
    api.monitoring.getPageSpeedWaterfall.mockImplementation((id, { checkId } = {}) => (checkId == null
      ? Promise.resolve({ ...snapshot(41), data: { ...snapshot(41).data, breaches } })
      : new Promise((r) => { resolveSlow = () => r({ ...snapshot(33, { response_ms: 4200 }), data: { ...snapshot(33, { response_ms: 4200 }).data, breaches } }) })))
    render(<PageSpeedDeepTab monitor={monitor} />)
    const sel = await el('[data-slot="pspd-deep-snapshot"]')
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-grade"]')?.getAttribute('data-letter')).toBe('A'))
    fireEvent.change(sel, { target: { value: '33' } })
    await waitFor(() => expect(resolveSlow).toBeTypeOf('function'))
    fireEvent.change(sel, { target: { value: '' } })
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-grade"]')?.getAttribute('data-letter')).toBe('A'))
    resolveSlow()
    await new Promise((r) => setTimeout(r, 20))
    expect(document.querySelector('[data-slot="pspd-grade"]').getAttribute('data-letter')).toBe('A')
    expect(document.querySelector('[data-slot="pspd-wf-row"][data-type="html"]')).toBeTruthy()
  })

  it('"Şimdi kontrol et" yetkisi yoksa görsel aracı indirme yapmaz (menü "yetki yok" der)', async () => {
    render(<PageSpeedDeepTab monitor={{ ...monitor, can_check: false }} />)
    const rows = await waitFor(() => {
      const r = document.querySelectorAll('[data-slot="pspd-wf-row"]')
      if (r.length < 3) throw new Error('rows')
      return r
    })
    fireEvent.click(within(rows[2]).getByRole('button'))
    const actions = await el('[data-slot="prs-it-actions"]', rows[2])
    expect(actions.getAttribute('data-unavailable')).toBe('no_permission')
  })
})
