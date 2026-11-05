import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: { keywordSameUrl: vi.fn() } }),
}))
import { api } from '../api/client'
import KeywordSameUrlNotice, { checkableUrl } from '../components/keyword/KeywordSameUrlNotice.jsx'

/**
 * "Bu URL'yi başka izleme de izliyor" (kw kararı 4): gecikmeli sorgu, sıra koruması (geç dönen eski yanıt yeni URL'nin uyarısını
 * ezmez), görünür izlemeler (ad + takım + kelime, yeni sekmede açılan bağlantı), göremediği takımlar yalnız SAYI, okunamazsa
 * "denetlenemedi" + yeniden dene (asla "yok"), 404 / 405 = eski sunucu → hiçbir şey.
 */
const LONG_TEAM = 'Dijital Kanallar ve Müşteri Deneyimi Uygulama Geliştirme Müdürlüğü (Bireysel Bankacılık / İnternet Şubesi)'
const ok = (monitors, hidden = 0) => ({ success: true, data: { url: 'https://www.example.com/', monitors, hidden_count: hidden } })
const notice = () => document.querySelector('[data-slot="kw-same-url"]')

describe('KeywordSameUrlNotice', () => {
  beforeEach(() => { vi.clearAllMocks() })
  afterEach(() => { vi.useRealTimers() })

  it('checkableUrl: şemasız tamamlanır, {timestamp} ayrıştırmayı bozmaz, host yoksa null', () => {
    expect(checkableUrl('www.example.com')).toBe('https://www.example.com')
    expect(checkableUrl('https://x.example.com/a?t={timestamp}')).toBe('https://x.example.com/a?t={timestamp}')
    expect(checkableUrl('')).toBeNull()
    expect(checkableUrl('ftp://x.example.com')).toBeNull()
    expect(checkableUrl('https://')).toBeNull()
  })

  it('görünür izlemeler ad + takım + kelimeyle, bağlantı yeni sekmede; gizli takımlar yalnız sayı; düzenlemede excludeId', async () => {
    api.monitoring.keywordSameUrl.mockResolvedValue(ok([
      { id: 11, name: 'Portal giriş', team_name: LONG_TEAM, keyword: 'Hoş geldiniz', active: true },
      { id: 12, name: 'Portal kampanya', team_name: 'Takım B', keyword: 'Kampanya', active: false },
    ], 3))
    render(<KeywordSameUrlNotice url="https://www.example.com/" excludeId={7} delayMs={0} />)
    await waitFor(() => expect(notice()).not.toBeNull())
    expect(api.monitoring.keywordSameUrl).toHaveBeenCalledWith('https://www.example.com/', 7)
    expect(notice()).toHaveAttribute('data-state', 'shared')
    expect(notice()).toHaveTextContent(/share one incident|tek olayı paylaşır/)
    const items = notice().querySelectorAll('[data-slot="kw-same-url-item"]')
    expect(items).toHaveLength(2)
    const link = within(items[0]).getByRole('link', { name: /Portal giriş/ })
    expect(link).toHaveAttribute('href', '?tab=keyword&monitor=11')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link.getAttribute('rel')).toMatch(/noopener/)
    expect(items[0]).toHaveTextContent(LONG_TEAM)   // takım adı TAM (kesilmez)
    expect(items[0]).toHaveTextContent('« Hoş geldiniz »')
    expect(items[1]).toHaveTextContent(/paused|duraklatılmış/)
    expect(notice().querySelector('[data-slot="kw-same-url-hidden"]')).toHaveTextContent(/3/)
  })

  it('yalnız gizli izleme varsa sayı; tek gizli izleme tekil metin', async () => {
    api.monitoring.keywordSameUrl.mockResolvedValue(ok([], 1))
    render(<KeywordSameUrlNotice url="https://www.example.com/" delayMs={0} />)
    await waitFor(() => expect(notice()).not.toBeNull())
    expect(notice().querySelectorAll('[data-slot="kw-same-url-item"]')).toHaveLength(0)
    expect(notice().querySelector('[data-slot="kw-same-url-hidden"]')).toHaveTextContent(/1 more monitor|1 izleme daha/)
    expect(api.monitoring.keywordSameUrl).toHaveBeenCalledWith('https://www.example.com/', undefined)
  })

  it('kimse yoksa hiçbir şey çizilmez', async () => {
    api.monitoring.keywordSameUrl.mockResolvedValue(ok([], 0))
    render(<KeywordSameUrlNotice url="https://www.example.com/" delayMs={0} />)
    await waitFor(() => expect(api.monitoring.keywordSameUrl).toHaveBeenCalled())
    await act(async () => {})
    expect(notice()).toBeNull()
  })

  it('okunamazsa "denetlenemedi" + yeniden dene (asla "yok"); yeniden dene yeni istek atar', async () => {
    api.monitoring.keywordSameUrl.mockResolvedValueOnce({ success: false, error: 'Sunucu hatası', status: 500 })
    render(<KeywordSameUrlNotice url="https://www.example.com/" delayMs={0} />)
    await waitFor(() => expect(notice()).toHaveAttribute('data-state', 'failed'))
    expect(notice()).toHaveTextContent(/Could not check|denetlenemedi/)
    api.monitoring.keywordSameUrl.mockResolvedValueOnce(ok([{ id: 3, name: 'Diğer', team_name: 'Takım C', keyword: 'x', active: true }]))
    fireEvent.click(within(notice()).getByRole('button', { name: /^(Try again|Yeniden dene)$/ }))
    await waitFor(() => expect(notice()).toHaveAttribute('data-state', 'shared'))
    expect(api.monitoring.keywordSameUrl).toHaveBeenCalledTimes(2)
  })

  it('ağ hatası da "denetlenemedi"', async () => {
    api.monitoring.keywordSameUrl.mockRejectedValue(new Error('net'))
    render(<KeywordSameUrlNotice url="https://www.example.com/" delayMs={0} />)
    await waitFor(() => expect(notice()).toHaveAttribute('data-state', 'failed'))
  })

  it('404 / 405 (eski sunucu) → hiçbir şey', async () => {
    for (const status of [404, 405]) {
      api.monitoring.keywordSameUrl.mockResolvedValueOnce({ success: false, error: 'Not found', status })
      const { unmount } = render(<KeywordSameUrlNotice url="https://www.example.com/" delayMs={0} />)
      await waitFor(() => expect(api.monitoring.keywordSameUrl).toHaveBeenCalled())
      await act(async () => {})
      expect(notice()).toBeNull()
      unmount()
      api.monitoring.keywordSameUrl.mockClear()
    }
  })

  it('gecikme: yazarken her tuşta istek gitmez — son URL için tek istek', async () => {
    vi.useFakeTimers()
    api.monitoring.keywordSameUrl.mockResolvedValue(ok([], 0))
    const { rerender } = render(<KeywordSameUrlNotice url="https://a.example.com/" />)
    await act(async () => { vi.advanceTimersByTime(200) })
    rerender(<KeywordSameUrlNotice url="https://ab.example.com/" />)
    await act(async () => { vi.advanceTimersByTime(399) })
    expect(api.monitoring.keywordSameUrl).not.toHaveBeenCalled()
    await act(async () => { vi.advanceTimersByTime(1) })
    expect(api.monitoring.keywordSameUrl).toHaveBeenCalledTimes(1)
    expect(api.monitoring.keywordSameUrl).toHaveBeenCalledWith('https://ab.example.com/', undefined)
  })

  it('sıra koruması: geç dönen eski URL yanıtı yeni URL\'nin uyarısını ezmez', async () => {
    let resolveOld
    api.monitoring.keywordSameUrl
      .mockImplementationOnce(() => new Promise((r) => { resolveOld = r }))
      .mockResolvedValueOnce(ok([{ id: 2, name: 'Yeni URL izlemesi', team_name: 'Takım A', keyword: 'y', active: true }]))
    const { rerender } = render(<KeywordSameUrlNotice url="https://old.example.com/" delayMs={0} />)
    await waitFor(() => expect(api.monitoring.keywordSameUrl).toHaveBeenCalledTimes(1))
    rerender(<KeywordSameUrlNotice url="https://new.example.com/" delayMs={0} />)
    await waitFor(() => expect(notice()).not.toBeNull())
    await act(async () => { resolveOld(ok([{ id: 1, name: 'Eski URL izlemesi', team_name: 'Takım A', keyword: 'x', active: true }])) })
    expect(notice()).toHaveTextContent('Yeni URL izlemesi')
    expect(notice()).not.toHaveTextContent('Eski URL izlemesi')
  })

  it('geçersiz / boş URL için istek yok', async () => {
    render(<KeywordSameUrlNotice url="" delayMs={0} />)
    await act(async () => {})
    expect(api.monitoring.keywordSameUrl).not.toHaveBeenCalled()
    expect(screen.queryByText(/share one incident|tek olayı paylaşır/)).toBeNull()
  })
})
