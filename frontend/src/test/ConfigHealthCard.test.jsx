import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ admin: { getConfigHealth: vi.fn() } }),
}))
import { api } from '../api/client'
import ConfigHealthCard from '../components/admin/ConfigHealthCard.jsx'

/** Yapılandırma sağlığı kartı (2026-09-12, #25): sorun varsa açık liste, tıklayınca bölüme/sekmeye gider. */
describe('ConfigHealthCard', () => {
  beforeEach(() => { vi.clearAllMocks(); try { localStorage.removeItem('cfg-health-open') } catch { /* yoksay */ } })

  it('sorunlu → kart yine KAPALI (2026-09-13), başlıkta sayaç; açınca liste; ayar bölümü satırı onOpenSection, nav satırı sm:navigate; detay kodu çevrilir', async () => {
    api.admin.getConfigHealth.mockResolvedValue({ success: true, data: {
      overall: 'bad', bad: 1, warn: 1, ok: 2,
      checks: [
        { key: 'smtp', status: 'bad', detail: 'tested_fail:2026-09-12T08:00:00', tab: 'smtp' },
        { key: 'unowned', status: 'warn', detail: '12', tab: 'inventory' },
        { key: 'push', status: 'ok', detail: 'ready', tab: 'userpush' },
        { key: 'reminder', status: 'ok', detail: 'FRI 15:00', tab: 'general' },
      ],
    } })
    const onOpen = vi.fn()
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<ConfigHealthCard onOpenSection={onOpen} />)
    const head = await screen.findByRole('button', { name: /Yapılandırma sağlığı|Configuration health/ })
    expect(head).toHaveAttribute('aria-expanded', 'false')   // sorun olsa da varsayılan kapalı
    expect(document.querySelectorAll('.cfg-row').length).toBe(0)
    fireEvent.click(head)
    await screen.findByText(/E-posta \(SMTP\)|Email \(SMTP\)/)
    expect(screen.getByText(/son test BAŞARISIZ · 2026-09-12T08:00:00|last test FAILED · 2026-09-12T08:00:00/)).toBeInTheDocument()
    expect(screen.getByText(/^1 sorun$|^1 problem$/)).toBeInTheDocument()   // tekil (QA ISSUE-011)
    const rows = document.querySelectorAll('.cfg-row')
    fireEvent.click(rows[0].querySelector('.cfg-row-go'))
    expect(onOpen).toHaveBeenCalledWith('smtp', null)   // ikinci arg: odaklanacak alan anahtarı (yalnız general satırları)
    fireEvent.click(rows[1].querySelector('.cfg-row-go'))
    await waitFor(() => expect(nav).toHaveBeenCalled())
    expect(nav.mock.calls[0][0].detail.tab).toBe('admin')   // envanter = Yönetim sekmesi
    window.removeEventListener('sm:navigate', nav)
  })

  it('her şey tamam → kapalı şerit, "4 tamam"; açınca satırlar', async () => {
    api.admin.getConfigHealth.mockResolvedValue({ success: true, data: { overall: 'ok', bad: 0, warn: 0, ok: 4,
      checks: [{ key: 'smtp', status: 'ok', detail: 'tested_ok:x', tab: 'smtp' }] } })
    render(<ConfigHealthCard onOpenSection={() => {}} />)
    const head = await screen.findByRole('button', { name: /Yapılandırma sağlığı|Configuration health/ })
    expect(head).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByText(/4 tamam|4 OK/)).toBeInTheDocument()
    fireEvent.click(head)
    expect(document.querySelectorAll('.cfg-row').length).toBeGreaterThan(0)
  })

  it('uç başarısız → kart çizilmez (ayar sayfası etkilenmez)', async () => {
    api.admin.getConfigHealth.mockResolvedValue({ success: false })
    const { container } = render(<ConfigHealthCard />)
    await waitFor(() => expect(api.admin.getConfigHealth).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelector('.cfg-health')).toBeNull())
  })
})
