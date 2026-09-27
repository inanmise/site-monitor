import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { StrictMode } from 'react'
import { render, screen, fireEvent, waitFor } from './test-utils'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ getBranding: vi.fn(async () => ({ success: true, data: {} })) }),
}))
import { api } from '../api/client'
import { BrandingProvider } from '../contexts/BrandingProvider.jsx'
import AnnouncementBanner from '../components/AnnouncementBanner.jsx'

const wrap = () => render(<BrandingProvider><AnnouncementBanner /></BrandingProvider>)

describe('AnnouncementBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('kapalı ya da metin boşsa render edilmez', async () => {
    api.getBranding.mockResolvedValueOnce({ success: true, data: { banner_enabled: false, banner_text: 'x', banner_version: 1 } })
    wrap()
    await waitFor(() => expect(api.getBranding).toHaveBeenCalled())
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('açık + metin doluysa görünür; link etiketiyle render edilir', async () => {
    api.getBranding.mockResolvedValueOnce({ success: true, data: {
      banner_enabled: true, banner_text: 'Planlı bakım', banner_tone: 'WARNING',
      banner_link: 'http://wiki.local', banner_link_label: 'Wiki', banner_version: 2 } })
    wrap()
    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(screen.getByText('Planlı bakım')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Wiki' })).toHaveAttribute('href', 'http://wiki.local')
  })

  it('X ile kapatınca kaybolur ve versiyon localStorage\'a yazılır', async () => {
    api.getBranding.mockResolvedValueOnce({ success: true, data: {
      banner_enabled: true, banner_text: 'Duyuru', banner_version: 3 } })
    wrap()
    await screen.findByRole('status')
    fireEvent.click(screen.getByLabelText(/close/i))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(localStorage.getItem('sm.banner.dismissedVersion')).toBe('3')
  })

  it('kapatılan versiyondan SONRA metin güncellenirse (versiyon artar) yeniden görünür', async () => {
    localStorage.setItem('sm.banner.dismissedVersion', '3')   // kullanıcı v3'ü kapatmıştı
    api.getBranding.mockResolvedValueOnce({ success: true, data: {
      banner_enabled: true, banner_text: 'Yeni duyuru', banner_version: 4 } })
    wrap()
    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(screen.getByText('Yeni duyuru')).toBeInTheDocument()
  })
})

describe('AnnouncementBanner — giriş "hero" kartı', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); sessionStorage.clear() })
  afterEach(() => { vi.useRealTimers() })

  // 2026-09-26: zamanlayıcı gösterim kararıyla aynı efektteyken StrictMode'un çift çağrısı onu temizliyor, ikinci
  // çalışma "zaten gösterildi" diye erken dönüyordu → hero kart ekranda asılı kalıyordu (geliştirme ortamı).
  it('StrictMode altında da 1.5 sn sonra üst şeride toplanır; oturumda bir kez', async () => {
    api.getBranding.mockResolvedValue({ success: true, data: {
      banner_enabled: true, banner_text: 'Planlı bakım', banner_tone: 'WARNING', banner_version: 7 } })
    // Uygulamadaki sıra: branding sağlayıcısı ÖNCE yüklenir, şerit girişten SONRA takılır (App.jsx) — StrictMode'un
    // takılma anındaki çift efekt çağrısı tam bu durumda zamanlayıcıyı öldürüyordu.
    const Gate = ({ show }) => (show ? <AnnouncementBanner heroOnMount /> : null)
    const { rerender, unmount } = render(<StrictMode><BrandingProvider><Gate show={false} /></BrandingProvider></StrictMode>)
    await waitFor(() => expect(api.getBranding).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    rerender(<StrictMode><BrandingProvider><Gate show /></BrandingProvider></StrictMode>)
    await waitFor(() => expect(document.querySelector('[data-slot="announcement-hero"]')).not.toBeNull())
    await waitFor(() => expect(document.querySelector('[data-slot="announcement-hero"]')).toBeNull(), { timeout: 3000 })
    expect(screen.getByRole('status')).toBeVisible()
    unmount()
    render(<BrandingProvider><AnnouncementBanner heroOnMount /></BrandingProvider>)
    await screen.findByRole('status')
    expect(document.querySelector('[data-slot="announcement-hero"]')).toBeNull()   // aynı oturumda yeniden çıkmaz
  })
})
