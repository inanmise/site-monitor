import { describe, it, expect, vi, beforeEach } from 'vitest'
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
    fireEvent.click(screen.getByLabelText('close'))
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
