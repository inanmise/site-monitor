import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'

vi.mock('../api/client', () => ({
  api: { monitoring: { maintenance: { active: vi.fn() } } },
}))

import { api } from '../api/client'
import MaintenanceBadge from '../components/ui/MaintenanceBadge.jsx'

/**
 * Bakım rozeti önbelleği (2026-10-09): yalnız başarılı yanıt önbelleğe giriyordu — uç düşükken her kart her
 * bağlanışında yeniden istek atıyordu. Başarısız yanıt da 60 sn hatırlanır; sonra yeniden denenir.
 */

describe('MaintenanceBadge — başarısız yanıt kısa süre hatırlanır', () => {
  afterEach(() => { vi.useRealTimers() })

  it('ret / success:false → 60 sn içinde yeni bağlanışlar istek atmaz; süre dolunca yeniden dener', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-09T10:00:00Z'))
    const active = vi.mocked(api.monitoring.maintenance.active)
    active.mockRejectedValue(new Error('down'))

    const first = render(<MaintenanceBadge target="a.example.com" />)
    await waitFor(() => expect(active).toHaveBeenCalledTimes(1))
    first.unmount()
    render(<><MaintenanceBadge target="a.example.com" /><MaintenanceBadge target="b.example.com" /></>)
    await new Promise((r) => setTimeout(r, 20))
    expect(active).toHaveBeenCalledTimes(1)

    vi.setSystemTime(new Date('2026-10-09T10:01:01Z'))
    active.mockResolvedValue({ success: false })
    render(<MaintenanceBadge target="a.example.com" />)
    await waitFor(() => expect(active).toHaveBeenCalledTimes(2))
    render(<MaintenanceBadge target="a.example.com" />)
    await new Promise((r) => setTimeout(r, 20))
    expect(active).toHaveBeenCalledTimes(2)              // success:false da hatırlandı

    vi.setSystemTime(new Date('2026-10-09T10:02:02Z'))
    active.mockResolvedValue({ success: true, data: { all: false, targets: ['a.example.com'] } })
    render(<MaintenanceBadge target="a.example.com" />)
    expect(await screen.findByText(/under maintenance|bakımda/i)).toBeInTheDocument()
    expect(active).toHaveBeenCalledTimes(3)
  })

  it('asılı istek (yanıt hiç gelmez) sonraki rozetleri sonsuza dek bekletmez: 180 sn sonra yeni istek (2026-11-15)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-09T12:00:00Z'))              // önceki testin önbelleği bayat
    const active = vi.mocked(api.monitoring.maintenance.active)
    active.mockReset()
    active.mockReturnValueOnce(new Promise(() => {}))
    render(<MaintenanceBadge target="c.example.com" />)
    await waitFor(() => expect(active).toHaveBeenCalledTimes(1))
    render(<MaintenanceBadge target="c.example.com" />)
    await new Promise((r) => setTimeout(r, 20))
    expect(active).toHaveBeenCalledTimes(1)                          // taze süren isteğe katılır
    vi.setSystemTime(new Date('2026-10-09T12:03:01Z'))
    active.mockResolvedValue({ success: true, data: { all: false, targets: ['c.example.com'] } })
    render(<MaintenanceBadge target="c.example.com" />)
    expect(await screen.findAllByText(/under maintenance|bakımda/i)).not.toHaveLength(0)
    expect(active).toHaveBeenCalledTimes(2)
  })
})
