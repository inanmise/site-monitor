import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, fireEvent, waitFor, within } from './test-utils.jsx'
import PageSpeedDeepTab from '../components/pagespeed/deep/PageSpeedDeepTab.jsx'
import PageSpeedGradeCard from '../components/pagespeed/deep/PageSpeedGradeCard.jsx'

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
// Şelale satırlarının çizim sayısı: her satır çiziminde bir kez çağrılan barBox sayılır (not edilmiş satır kanıtı)
const counter = vi.hoisted(() => ({ barBox: 0 }))
vi.mock('../components/pagespeed/deep/pageSpeedDeepModel.js', async (orig) => {
  const real = await orig()
  return { ...real, barBox: (...a) => { counter.barBox++; return real.barBox(...a) } }
})
const { gradeSnapshot } = await import('../components/pagespeed/deep/pageSpeedDeepModel.js')
import { api } from '../api/client'

/**
 * Sayfa Hızı şelale sekmesi — bağımsız inceleme düzeltmeleri (2026-11-17): canlı yoklama son ölçümü ilerletince sekme yeni
 * ölçümü izler ve arada "daha eski" demez; ihlal anları şelale yanıtından (ağır kaynak listesi ucu yalnız eski podda); ölçülemeyen
 * bileşenle not yeşil olmaz; şelale satırları not edilmiş (bir satıra basmak tüm satırları yeniden çizmez).
 */
const KB = 1024
const el = (sel, root = document) => waitFor(() => {
  const n = root.querySelector(sel)
  if (!n) throw new Error(`yok: ${sel}`)
  return n
})
const monitor = { id: 9, name: 'Ödeme sayfası', url: 'https://x.com/odeme', can_check: true }

function snapshot(checkId, at, over = {}, extra = {}) {
  return {
    success: true,
    data: {
      check_id: checkId, has_start: true, truncated: false, total: 2,
      check: { checked_at: at, ttfb_ms: 120, html_ms: 300, response_ms: 900, total_bytes: 900 * KB, html_bytes: 40 * KB,
        request_count: 3, failed_count: 0, dns_ms: 10, connect_ms: 20, tls_ms: 30, server_ms: 60, ...over },
      resources: [
        { url: 'https://x.com/app.css', type: 'CSS', start_ms: 310, duration_ms: 50, bytes: 10 * KB, http_status: 200 },
        { url: 'https://x.com/hero.jpg', type: 'IMG', start_ms: 400, duration_ms: 200, bytes: 120 * KB, http_status: 200 },
      ],
      ...extra,
    },
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  counter.barBox = 0
  api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { breaches: [] } })
})

describe('şelale sekmesi — son ölçümü izleme (inceleme 2026-11-17, madde 1)', () => {
  it('yoklama son ölçümü ilerletince yeni ölçüm okunur; arada ve sonra "daha eski" denmez', async () => {
    let resolveNew
    api.monitoring.getPageSpeedWaterfall
      .mockResolvedValueOnce(snapshot(41, '2026-11-15T10:00:00', {}, { breaches: [] }))
      .mockImplementationOnce(() => new Promise((r) => { resolveNew = () => r(snapshot(42, '2026-11-15T10:30:00', {}, { breaches: [] })) }))
    const { rerender } = render(<PageSpeedDeepTab monitor={{ ...monitor, last_check: '2026-11-15T10:00:00' }} />)
    const g = await el('[data-slot="pspd-grade"]')
    expect(g.getAttribute('data-older')).toBeNull()
    // Canlı liste yoklaması: izlemenin son ölçümü 10:30
    rerender(<PageSpeedDeepTab monitor={{ ...monitor, last_check: '2026-11-15T10:30:00' }} />)
    await waitFor(() => expect(api.monitoring.getPageSpeedWaterfall).toHaveBeenCalledTimes(2))
    expect(api.monitoring.getPageSpeedWaterfall).toHaveBeenLastCalledWith(9, { checkId: undefined })
    // Okuma sürerken eski veri yerinde ve "daha eski" DEĞİL (karşılaştırma okuma anındaki son ölçümle)
    expect(document.querySelector('[data-slot="pspd-grade"]').getAttribute('data-older')).toBeNull()
    resolveNew()
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-grade"]').textContent).toContain('2026-11-15T10:30:00'))
    expect(document.querySelector('[data-slot="pspd-grade"]').getAttribute('data-older')).toBeNull()
  })

  it('yeni ölçüm kaynak saklamadıysa (sayfa açılamadı) izlenen okuma eski ölçümü döner → nötr "daha eski" notu doğru', async () => {
    api.monitoring.getPageSpeedWaterfall.mockResolvedValue(snapshot(41, '2026-11-15T10:00:00', {}, { breaches: [] }))
    const { rerender } = render(<PageSpeedDeepTab monitor={{ ...monitor, last_check: '2026-11-15T10:00:00' }} />)
    await el('[data-slot="pspd-grade"]')
    rerender(<PageSpeedDeepTab monitor={{ ...monitor, last_check: '2026-11-15T10:30:00' }} />)
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-grade"]').getAttribute('data-older')).toBe('true'))
    expect(document.querySelector('[data-slot="pspd-grade"] .text-success')).toBeNull()
  })

  it('ihlal anı seçiliyken son ölçüm ilerlerse seçili ölçüm değişmez (izleme yalnız "son ölçüm"de)', async () => {
    const breaches = [{ check_id: 33, checked_at: '2026-11-14T08:00:00' }]
    api.monitoring.getPageSpeedWaterfall.mockImplementation((id, { checkId } = {}) =>
      Promise.resolve(checkId == null ? snapshot(41, '2026-11-15T10:00:00', {}, { breaches })
        : snapshot(33, '2026-11-14T08:00:00', { response_ms: 4200 }, { breaches })))
    const { rerender } = render(<PageSpeedDeepTab monitor={{ ...monitor, last_check: '2026-11-15T10:00:00' }} />)
    const sel = await el('[data-slot="pspd-deep-snapshot"]')
    fireEvent.change(sel, { target: { value: '33' } })
    await waitFor(() => expect(api.monitoring.getPageSpeedWaterfall).toHaveBeenCalledWith(9, { checkId: '33' }))
    const calls = api.monitoring.getPageSpeedWaterfall.mock.calls.length
    rerender(<PageSpeedDeepTab monitor={{ ...monitor, last_check: '2026-11-15T10:30:00' }} />)
    await new Promise((r) => setTimeout(r, 30))
    expect(api.monitoring.getPageSpeedWaterfall.mock.calls.length).toBe(calls)
    expect(document.querySelector('[data-slot="pspd-deep-snapshot"]').value).toBe('33')
  })

  it('izlenen okuma başarısızsa gösterilen veri kalır ve "yeni ölçüm okunamadı" denir; yeniden dene okur', async () => {
    api.monitoring.getPageSpeedWaterfall
      .mockResolvedValueOnce(snapshot(41, '2026-11-15T10:00:00', {}, { breaches: [] }))
      .mockResolvedValueOnce({ success: false, status: 503, error: 'Sunucu yanıt vermedi' })
      .mockResolvedValueOnce(snapshot(42, '2026-11-15T10:30:00', {}, { breaches: [] }))
    const { rerender } = render(<PageSpeedDeepTab monitor={{ ...monitor, last_check: '2026-11-15T10:00:00' }} />)
    await el('[data-slot="pspd-grade"]')
    rerender(<PageSpeedDeepTab monitor={{ ...monitor, last_check: '2026-11-15T10:30:00' }} />)
    const banner = await el('[data-slot="pspd-deep-follow-failed"]')
    expect(document.querySelector('[data-slot="pspd-grade"]')).toBeTruthy()
    expect(document.querySelector('[data-slot="pspd-deep-failed"]')).toBeNull()
    fireEvent.click(within(banner).getByRole('button'))
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-deep-follow-failed"]')).toBeNull())
    expect(document.querySelector('[data-slot="pspd-grade"]').textContent).toContain('2026-11-15T10:30:00')
  })
})

describe('ihlal anları şelale yanıtından (madde 7)', () => {
  it('yeni sunucu: kaynak listesi ucu HİÇ çağrılmaz, seçici şelale yanıtının ihlal anlarını gösterir', async () => {
    api.monitoring.getPageSpeedWaterfall.mockResolvedValue(snapshot(41, '2026-11-15T10:00:00', {},
      { breaches: [{ check_id: 33, checked_at: '2026-11-14T08:00:00' }] }))
    render(<PageSpeedDeepTab monitor={monitor} />)
    const sel = await el('[data-slot="pspd-deep-snapshot"]')
    expect([...sel.querySelectorAll('option')].map((o) => o.value)).toEqual(['', '33'])
    expect(api.monitoring.getPageSpeedResources).not.toHaveBeenCalled()
  })

  it('eski pod (yanıtta breaches yok): kaynak listesi ucu BİR kez çağrılır', async () => {
    api.monitoring.getPageSpeedWaterfall.mockResolvedValue(snapshot(41, '2026-11-15T10:00:00'))
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { breaches: [{ check_id: 33, checked_at: 'b' }] } })
    const { rerender } = render(<PageSpeedDeepTab monitor={{ ...monitor, last_check: 'a' }} />)
    await el('[data-slot="pspd-deep-snapshot"]')
    rerender(<PageSpeedDeepTab monitor={{ ...monitor, last_check: 'c' }} />)
    await waitFor(() => expect(api.monitoring.getPageSpeedWaterfall).toHaveBeenCalledTimes(2))
    await new Promise((r) => setTimeout(r, 20))
    expect(api.monitoring.getPageSpeedResources).toHaveBeenCalledTimes(1)
    expect(api.monitoring.getPageSpeedResources).toHaveBeenCalledWith(9, { size: 1 })
  })
})

describe('hız notu — ölçülemeyen bileşen (madde 8)', () => {
  it('TTFB ölçülemediyse not EKSİK: yeşil ton yok, eksik bileşen adlandırılır, "her şey yolunda" yok', () => {
    const g = gradeSnapshot(snapshot(41, 'x', { ttfb_ms: null }).data)
    expect(g.score).not.toBeNull()
    expect(g.letter).toBe('A')
    expect(g.incomplete).toBe(true)
    expect(g.missing).toEqual(['ttfb'])
    expect(g.recs.some((r) => r.code === 'ALL_GOOD')).toBe(false)
    render(<PageSpeedGradeCard grade={g} checkedAt="x" isLatest />)
    const card = document.querySelector('[data-slot="pspd-grade"]')
    expect(card.getAttribute('data-incomplete')).toBe('true')
    expect(card.querySelector('.text-success')).toBeNull()
    expect(card.querySelector('[data-slot="pspd-grade-incomplete"]').textContent).toContain('First byte (TTFB)')
  })

  it('her bileşen ölçüldüyse yeşil serbest; yalnız HTML sayfa (istek 1) görsel / JS = 0 sayılır', () => {
    const full = gradeSnapshot(snapshot(41, 'x').data)
    expect(full.incomplete).toBe(false)
    const { unmount } = render(<PageSpeedGradeCard grade={full} checkedAt="x" isLatest />)
    expect(document.querySelector('[data-slot="pspd-grade"] .text-success')).toBeTruthy()
    unmount()
    const htmlOnly = gradeSnapshot({ ...snapshot(41, 'x', { request_count: 1 }).data, resources: [] })
    expect(htmlOnly.incomplete).toBe(false)
    expect(htmlOnly.recs.map((r) => r.code)).toEqual(['ALL_GOOD'])
    // Kaynak saklanmamış ve istek > 1: görsel / JS bilinmiyor → eksik
    const unknown = gradeSnapshot({ ...snapshot(41, 'x').data, resources: [] })
    expect(unknown.missing).toEqual(['images', 'js'])
  })
})

describe('şelale satırları not edilmiş (madde 5)', () => {
  it('bir satıra basmak yalnız o satırı yeniden çizer (500 satırda tüm satırlar değil)', async () => {
    const resources = Array.from({ length: 60 }, (_, i) => ({
      url: `https://x.com/r${i}.js`, type: 'JS', start_ms: 300 + i, duration_ms: 20, bytes: KB, http_status: 200,
    }))
    api.monitoring.getPageSpeedWaterfall.mockResolvedValue({ success: true, data: {
      ...snapshot(41, '2026-11-15T10:00:00', {}, { breaches: [] }).data, resources, total: 60 } })
    render(<PageSpeedDeepTab monitor={monitor} />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="pspd-wf-row"]').length).toBe(61))
    await new Promise((r) => setTimeout(r, 20))
    const before = counter.barBox
    const rows = document.querySelectorAll('[data-slot="pspd-wf-row"]')
    fireEvent.click(within(rows[5]).getByRole('button'))
    await el('[data-slot="pspd-wf-detail"]', rows[5])
    // Açılan satır bir kez yeniden çizilir; diğer 59 kaynak + HTML satırı çizilmez
    expect(counter.barBox - before).toBeLessThanOrEqual(2)
    fireEvent.click(within(rows[9]).getByRole('button'))
    await el('[data-slot="pspd-wf-detail"]', rows[9])
    expect(rows[5].querySelector('[data-slot="pspd-wf-detail"]')).toBeNull()
  })
})
