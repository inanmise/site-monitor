import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, within } from './test-utils.jsx'

vi.mock('../api/client', () => ({
  api: {
    systemMaintenance: {
      endNow: vi.fn(() => Promise.resolve({ success: true, data: { id: 7 } })),
      extend: vi.fn(() => Promise.resolve({ success: true, data: { id: 7, end_at: '2026-10-02T20:30:00Z' } })),
    },
  },
}))

import { api } from '../api/client'
import SystemMaintenanceLayer from '../components/maintenance/SystemMaintenanceLayer.jsx'

/**
 * Sistem Bakım Modu — uygulama katmanı (2026-10-02): uyarı şeridi + son 60 sn kapatılamayan pencere SUNUCU saatine göre
 * (sahte zamanlayıcı; istemci saati sunucudan 2 dk geride), duyuru/uyarı şeridi kapatma, global yönetici şeridi
 * (Uzat · Hemen bitir), oturum kesildi (ended) kipinin kısa geri sayımı.
 */
const SERVER_NOW = Date.parse('2026-10-02T18:55:00Z')     // İstanbul 21:55 (sunucu)
const OFFSET = 2 * 60_000                                   // sunucu istemciden 2 dk ileride
const CLIENT_NOW = SERVER_NOW - OFFSET
const block = (over = {}) => ({
  state: 'warning', id: 7, revision: 1, warn_minutes: 10, announce_hours: 24,
  start_at: '2026-10-02T19:00:00Z', end_at: '2026-10-02T20:00:00Z', message_tr: null, message_en: null, contact: null, ...over,
})

describe('SystemMaintenanceLayer — kullanıcı (global yönetici değil)', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.useFakeTimers()
    vi.setSystemTime(CLIENT_NOW)
  })
  afterEach(() => { vi.useRealTimers() })

  it('uyarı şeridi kalan süreyi SUNUCU saatine göre gösterir (5:00, istemci saatiyle 7:00 olurdu)', () => {
    render(<SystemMaintenanceLayer block={block()} offset={OFFSET} onExpire={vi.fn()} />)
    const strip = document.querySelector('[data-slot="maint-warning"]')
    expect(strip).not.toBeNull()
    expect(strip.querySelector('[data-slot="maint-countdown"]')).toHaveTextContent('5:00')
    expect(screen.queryByRole('alertdialog')).toBeNull()
    act(() => { vi.advanceTimersByTime(30_000) })
    expect(strip.querySelector('[data-slot="maint-countdown"]')).toHaveTextContent('4:30')
  })

  it('son 60 sn: kapatılamayan pencere açılır; 0\'da çıkış BİR kez; Escape kapatmaz', () => {
    const onExpire = vi.fn()
    render(<SystemMaintenanceLayer block={block()} offset={OFFSET} onExpire={onExpire} />)
    act(() => { vi.advanceTimersByTime(4 * 60_000 + 1000) })   // başlangıca 59 sn
    const dlg = screen.getByRole('alertdialog')
    expect(dlg).toHaveAttribute('data-mode', 'final')
    expect(dlg.querySelector('[data-slot="maint-dialog-countdown"]')).toHaveTextContent('59')
    fireEvent.keyDown(dlg, { key: 'Escape' })
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(onExpire).not.toHaveBeenCalled()
    act(() => { vi.advanceTimersByTime(60_000) })
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('"Şimdi çıkış yap" pencereyi beklemeden çıkışı tetikler (tek sefer)', () => {
    const onExpire = vi.fn()
    render(<SystemMaintenanceLayer block={block()} offset={OFFSET} onExpire={onExpire} />)
    act(() => { vi.advanceTimersByTime(4 * 60_000 + 30_000) })
    const btn = document.querySelector('[data-slot="maint-dialog-logout"]')
    fireEvent.click(btn)
    fireEvent.click(btn)
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('uyarı şeridi kapatılır ve kapalı kalır (pencere + sürüm); son 60 sn penceresi yine açılır', () => {
    const { rerender } = render(<SystemMaintenanceLayer block={block()} offset={OFFSET} onExpire={vi.fn()} />)
    fireEvent.click(document.querySelector('[data-slot="maint-dismiss"]'))
    expect(document.querySelector('[data-slot="maint-warning"]')).toBeNull()
    rerender(<SystemMaintenanceLayer block={block()} offset={OFFSET} onExpire={vi.fn()} />)
    expect(document.querySelector('[data-slot="maint-warning"]')).toBeNull()
    act(() => { vi.advanceTimersByTime(4 * 60_000 + 5000) })
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    // saat değişti (sürüm 2) → şerit yeniden
    rerender(<SystemMaintenanceLayer block={block({ revision: 2, start_at: '2026-10-02T19:05:00Z', end_at: '2026-10-02T20:05:00Z' })}
      offset={OFFSET} onExpire={vi.fn()} />)
    expect(document.querySelector('[data-slot="maint-warning"]')).not.toBeNull()
  })

  it('duyuru şeridi (uyarı öncesi) kapatılabilir', () => {
    render(<SystemMaintenanceLayer block={block({ state: 'announced', start_at: '2026-10-03T19:00:00Z', end_at: '2026-10-03T20:00:00Z' })}
      offset={OFFSET} onExpire={vi.fn()} />)
    const strip = document.querySelector('[data-slot="maint-announce"]')
    expect(strip).toHaveTextContent(/03\.10\.2026 22:00 – 23:00/)
    fireEvent.click(within(strip).getByRole('button'))
    expect(document.querySelector('[data-slot="maint-announce"]')).toBeNull()
  })

  it('oturum sunucuda kesildi (ended): 10 sn geri sayımlı pencere, 0\'da çıkış', () => {
    const onExpire = vi.fn()
    render(<SystemMaintenanceLayer ended block={null} offset={0} onExpire={onExpire} />)
    const dlg = screen.getByRole('alertdialog')
    expect(dlg).toHaveAttribute('data-mode', 'ended')
    expect(dlg.querySelector('[data-slot="maint-dialog-countdown"]')).toHaveTextContent('10')
    act(() => { vi.advanceTimersByTime(10_000) })
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('bakım yokken hiçbir şey çizilmez', () => {
    render(<SystemMaintenanceLayer block={{ state: 'none' }} offset={0} onExpire={vi.fn()} />)
    expect(document.querySelector('[data-slot="maint-strips"]')).toBeNull()
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })
})

describe('SystemMaintenanceLayer — global yönetici', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(api.systemMaintenance.endNow).mockClear()
  })

  it('bakımda kalıcı şerit "bitiş 23:00 · Uzat · Hemen bitir"; pencere/çıkış YOK; Hemen bitir onayla API', async () => {
    const onChanged = vi.fn()
    const onExpire = vi.fn()
    const active = block({ state: 'active', start_at: new Date(Date.now() - 60_000).toISOString(),
      end_at: new Date(Date.now() + 3_600_000).toISOString() })
    render(<SystemMaintenanceLayer globalAdmin block={active} offset={0} onExpire={onExpire} onChanged={onChanged}
      onOpenSettings={vi.fn()} />)
    const strip = document.querySelector('[data-slot="maint-admin"]')
    expect(strip).not.toBeNull()
    expect(strip.querySelector('[data-slot="maint-extend"]')).not.toBeNull()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(onExpire).not.toHaveBeenCalled()

    fireEvent.click(strip.querySelector('[data-slot="maint-end-now"]'))
    const confirm = await screen.findByRole('dialog', { name: /End maintenance now|Bakım şimdi bitirilsin/ })
    fireEvent.click(within(confirm).getByRole('button', { name: /^(End now|Hemen bitir)$/ }))
    await vi.waitFor(() => expect(api.systemMaintenance.endNow).toHaveBeenCalledWith(7))
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled())
  })

  it('başlamak üzereyken bilgi şeridi (Uzat/Bitir yok), kullanıcı uyarı şeridi değil', () => {
    render(<SystemMaintenanceLayer globalAdmin block={block({ start_at: new Date(Date.now() + 5 * 60_000).toISOString(),
      end_at: new Date(Date.now() + 65 * 60_000).toISOString() })} offset={0} onExpire={vi.fn()} />)
    const strip = document.querySelector('[data-slot="maint-admin"]')
    expect(strip).not.toBeNull()
    expect(strip.querySelector('[data-slot="maint-end-now"]')).toBeNull()
    expect(document.querySelector('[data-slot="maint-warning"]')).toBeNull()
  })
})

/**
 * "Bakım tamamlandı" şeridi (2026-10-02, kullanıcı isteği: bakım bitince kullanıcılara bir uyarı daha) — sunucu bildirim
 * süresince `state: 'ended'`: global yönetici dahil herkese başarı tonunda kapatılabilir şerit; başlangıç/bitiş (gerçek)
 * + yöneticinin mesajı; pencere/sayaç yok. Kapatma pencere + sürüm başına (tür `ended`).
 */
describe('SystemMaintenanceLayer — bakım tamamlandı şeridi', () => {
  const ended = (over = {}) => ({
    state: 'ended', id: 7, revision: 2, start_at: '2026-10-02T19:00:00Z', end_at: '2026-10-02T19:40:00Z',
    planned_end_at: '2026-10-02T20:00:00Z', message_tr: 'Veritabanı yükseltmesi', message_en: 'Database upgrade', contact: null, ...over,
  })
  beforeEach(() => { localStorage.clear() })

  it('kullanıcı: başarı tonlu şerit — başlangıç + gerçek bitiş + mesaj; pencere/sayaç YOK', () => {
    const onExpire = vi.fn()
    render(<SystemMaintenanceLayer block={ended()} offset={0} onExpire={onExpire} />)
    const strip = document.querySelector('[data-slot="maint-ended"]')
    expect(strip).not.toBeNull()
    expect(strip).toHaveAttribute('data-tone', 'success')
    expect(strip).toHaveTextContent(/Maintenance completed/)
    expect(strip).toHaveTextContent(/Planned maintenance has been completed \(start 22:00 · end 22:40\)\. SiteMonitor is available again\./)
    expect(strip).toHaveTextContent(/Database upgrade/)
    expect(strip.querySelector('[data-slot="maint-countdown"]')).toBeNull()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(document.querySelector('[data-slot="maint-warning"], [data-slot="maint-announce"], [data-slot="maint-admin"]')).toBeNull()
    expect(onExpire).not.toHaveBeenCalled()
  })

  it('gece yarısını aşan pencerede tarihli başlangıç/bitiş', () => {
    render(<SystemMaintenanceLayer block={ended({ start_at: '2026-10-02T20:00:00Z', end_at: '2026-10-02T22:30:00Z' })}
      offset={0} onExpire={vi.fn()} />)
    expect(document.querySelector('[data-slot="maint-ended"]'))
      .toHaveTextContent(/start 02\.10\.2026 23:00 · end 03\.10\.2026 01:30/)
  })

  it('kapatılır ve AYNI pencere + sürüm için kapalı kalır; yeni sürüm / yeni bakım yeniden gösterir', () => {
    const { rerender, unmount } = render(<SystemMaintenanceLayer block={ended()} offset={0} onExpire={vi.fn()} />)
    fireEvent.click(document.querySelector('[data-slot="maint-ended"] [data-slot="maint-dismiss"]'))
    expect(document.querySelector('[data-slot="maint-ended"]')).toBeNull()
    expect(localStorage.getItem('sm.maint.dismiss.ended.7.2')).toBe('1')
    rerender(<SystemMaintenanceLayer block={{ ...ended() }} offset={0} onExpire={vi.fn()} />)
    expect(document.querySelector('[data-slot="maint-ended"]')).toBeNull()
    unmount()
    // sayfa yenilendi: kalıcı (localStorage)
    const second = render(<SystemMaintenanceLayer block={ended()} offset={0} onExpire={vi.fn()} />)
    expect(document.querySelector('[data-slot="maint-ended"]')).toBeNull()
    second.rerender(<SystemMaintenanceLayer block={ended({ revision: 3 })} offset={0} onExpire={vi.fn()} />)
    expect(document.querySelector('[data-slot="maint-ended"]')).not.toBeNull()
    second.rerender(<SystemMaintenanceLayer block={ended({ id: 8, revision: 2 })} offset={0} onExpire={vi.fn()} />)
    expect(document.querySelector('[data-slot="maint-ended"]')).not.toBeNull()
    // duyuru / uyarı kapatması "tamamlandı" şeridini etkilemez (ayrı tür)
    expect(localStorage.getItem('sm.maint.dismiss.announce.8.2')).toBeNull()
  })

  it('global yönetici de görür (bakım sonunda içeride yalnız onlar vardır); admin şeridi yok', () => {
    render(<SystemMaintenanceLayer globalAdmin block={ended()} offset={0} onExpire={vi.fn()} onOpenSettings={vi.fn()} />)
    expect(document.querySelector('[data-slot="maint-ended"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="maint-admin"]')).toBeNull()
  })

  it('kabuksuz görünümde (stripsHidden) çizilmez', () => {
    render(<SystemMaintenanceLayer stripsHidden block={ended()} offset={0} onExpire={vi.fn()} />)
    expect(document.querySelector('[data-slot="maint-ended"]')).toBeNull()
  })
})
