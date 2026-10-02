import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, within } from './test-utils.jsx'
import OnlineUsersIndicator from '../components/nav/OnlineUsersIndicator.jsx'
import { PRESENCE_REFRESH_MS, resetPresenceForTests } from '../hooks/usePresence.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatTime: (iso) => (iso ? String(iso).substring(11, 19) : '—'),
  api: withApiFallback({ presence: { online: vi.fn() } }),
}))

import { api } from '../api/client'

/**
 * Çevrimiçi kullanıcı göstergesi (2026-10-02): yeşil figür + sayı, tıklanınca birincil takıma göre dağılım,
 * 30 sn'de bir TEK paylaşılan yoklama, hata durumu, daraltılmış kenar çubuğunda rozet.
 */
const PAYLOAD = {
  success: true,
  data: {
    total: 9, no_team: 2, window_seconds: 120, generated_at: '2026-10-02T12:00:00Z',
    teams: [
      { team_id: 2, team_name: 'SY-Takım A', count: 4 },
      { team_id: 7, team_name: 'SY-Takım B', count: 3 },
    ],
  },
}

beforeEach(() => {
  // Ürünün varsayılan dili İngilizce (2026-10-02 kararı); bu dosya Türkçe metinleri doğrular.
  localStorage.setItem('site-monitor-lang', 'tr')
  resetPresenceForTests()
  api.presence.online.mockReset()
  api.presence.online.mockResolvedValue(PAYLOAD)
})

afterEach(() => {
  resetPresenceForTests()
  vi.useRealTimers()
  localStorage.removeItem('site-monitor-lang')
})

describe('OnlineUsersIndicator', () => {
  it('yeşil figür + çevrimiçi sayısı; erişilebilir ad sayıyı söyler', async () => {
    render(<OnlineUsersIndicator />)
    const btn = await screen.findByRole('button', { name: /Şu an çevrimiçi: 9 kişi/ })
    expect(within(btn).getByText('9')).toBeInTheDocument()
    expect(btn.getAttribute('data-slot')).toBe('online-users')
  })

  it('tıklanınca takım dağılımı: sunucu sırası korunur, takımsız en sonda, toplam başlıkta', async () => {
    render(<OnlineUsersIndicator />)
    const btn = await screen.findByRole('button', { name: /Şu an çevrimiçi: 9 kişi/ })
    fireEvent.click(btn)
    const list = await screen.findByRole('list', { name: 'Takımlara göre çevrimiçi kullanıcılar' })
    const rows = within(list).getAllByRole('listitem').map((li) => li.textContent)
    expect(rows).toEqual(['SY-Takım A4', 'SY-Takım B3', 'Takımsız2'])
    const panel = document.querySelector('[data-slot="online-users-panel"]')
    expect(within(panel).getByText('9')).toBeInTheDocument()
    // "son N dakika" DEĞİL — o an açık olanlar (2026-10-02 kullanıcı kararı)
    expect(within(panel).getByText(/Şu an SiteMonitor'da oturumu açık kullanıcılar/)).toBeInTheDocument()
    expect(panel.textContent).not.toMatch(/dakika/)
    expect(within(panel).getByText(/30 sn'de bir yenilenir/)).toBeInTheDocument()
    // kişi bilgisi yok — yalnız takım adları ve sayılar
    expect(panel.textContent).not.toMatch(/@/)
  })

  it('30 sn\'de bir yeniler; iki gösterge açıkken bile her tikte TEK istek', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    render(<><OnlineUsersIndicator /><OnlineUsersIndicator isMobile /></>)
    await waitFor(() => expect(api.presence.online).toHaveBeenCalledTimes(1))
    await act(async () => { vi.advanceTimersByTime(PRESENCE_REFRESH_MS) })
    await waitFor(() => expect(api.presence.online).toHaveBeenCalledTimes(2))
    api.presence.online.mockResolvedValue({ ...PAYLOAD, data: { ...PAYLOAD.data, total: 11 } })
    await act(async () => { vi.advanceTimersByTime(PRESENCE_REFRESH_MS) })
    await waitFor(() => expect(api.presence.online).toHaveBeenCalledTimes(3))
    expect(await screen.findAllByRole('button', { name: /Şu an çevrimiçi: 11 kişi/ })).toHaveLength(2)
  })

  it('sekme gizliyken yoklama durur, geri gelince bir kez tazeler', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    render(<OnlineUsersIndicator />)
    await waitFor(() => expect(api.presence.online).toHaveBeenCalledTimes(1))
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    await act(async () => { vi.advanceTimersByTime(PRESENCE_REFRESH_MS * 3) })
    expect(api.presence.online).toHaveBeenCalledTimes(1)
    hidden.mockReturnValue(false)
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    await waitFor(() => expect(api.presence.online).toHaveBeenCalledTimes(2))
    hidden.mockRestore()
  })

  it('sunucu hatası: sayı "—", panelde hata + yeniden dene yeni istek açar', async () => {
    api.presence.online.mockResolvedValue({ success: false })
    render(<OnlineUsersIndicator />)
    const btn = await screen.findByRole('button', { name: /Çevrimiçi kullanıcılar — bilgi yükleniyor/ })
    await waitFor(() => expect(api.presence.online).toHaveBeenCalled())
    expect(within(btn).getByText('—')).toBeInTheDocument()
    fireEvent.click(btn)
    const retry = await screen.findByRole('button', { name: /Yeniden dene/ })
    const before = api.presence.online.mock.calls.length
    api.presence.online.mockResolvedValue(PAYLOAD)
    fireEvent.click(retry)
    await waitFor(() => expect(api.presence.online.mock.calls.length).toBeGreaterThan(before))
    expect(await screen.findByRole('button', { name: /Şu an çevrimiçi: 9 kişi/ })).toBeInTheDocument()
  })

  it('daraltılmış kenar çubuğu: sayı yuvarlağın köşesinde rozet, 99 üstü "99+"', async () => {
    api.presence.online.mockResolvedValue({ ...PAYLOAD, data: { ...PAYLOAD.data, total: 120 } })
    render(<OnlineUsersIndicator collapsed />)
    const btn = await screen.findByRole('button', { name: /Şu an çevrimiçi: 120 kişi/ })
    expect(within(btn).getByText('99+')).toBeInTheDocument()
  })
})
