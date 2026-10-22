import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'

/**
 * 2026-10-22 hata düzeltmeleri:
 *  - UIM-13 / K5: "Rehber & Notlar" sunucunun reddedeceği Düzenle / Sil düğmelerini gösteriyordu (403 + ACCESS_DENIED).
 *    Not satırı `can_modify` taşır (sunucunun requireModify kuralı); bayrak yoksa satırın yazma kapısı (bugünkü kural).
 *  - ALERTS-20: gürültü analizinde "İzlemeyi aç" sertifika satırlarında ölü düğmeydi (`cert` sekme değil).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getMonitorNotes: vi.fn() } }),
}))
import { api } from '../api/client'
import MonitorNotes from '../components/MonitorNotes.jsx'
import { noiseMonitorTarget, runSuggestionAction } from '../components/admin/alerts/noiseModel.js'

const NOTE = (id, extra = {}) => ({ id, problem: `Sorun ${id}`, action_taken: '', root_cause: '', refs: '', author_name: 'Ada',
  created_at: '2026-10-20T08:00:00', updated_at: null, ...extra })
const editBtn = (title) => screen.queryByRole('button', { name: new RegExp(`${title} — (edit|düzenle)`, 'i') })
const delBtn = (title) => screen.queryByRole('button', { name: new RegExp(`${title} — (delete|sil)`, 'i') })

describe('MonitorNotes — not başına yazma kapısı (can_modify)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('can_modify:false olan notta Düzenle / Sil yok, kilit görünür; can_modify:true olanda var', async () => {
    api.monitoring.getMonitorNotes.mockResolvedValue({ success: true, data: { guide: null,
      notes: [NOTE(1, { can_modify: false }), NOTE(2, { can_modify: true })] } })
    const { container } = render(<MonitorNotes type="PING" target="10.0.0.1" canManage />)
    await screen.findByText('Sorun 1')
    expect(editBtn('Sorun 1')).toBeNull()
    expect(delBtn('Sorun 1')).toBeNull()
    expect(container.querySelectorAll('[data-slot="note-locked"]')).toHaveLength(1)
    expect(editBtn('Sorun 2')).not.toBeNull()
    expect(delBtn('Sorun 2')).not.toBeNull()
  })

  it('bayrak yoksa (eski sunucu) satırın kapısı: canManage → düğmeler var, canManage=false → yok', async () => {
    api.monitoring.getMonitorNotes.mockResolvedValue({ success: true, data: { guide: null, notes: [NOTE(1)] } })
    const { unmount } = render(<MonitorNotes type="PING" target="10.0.0.1" canManage />)
    await screen.findByText('Sorun 1')
    expect(editBtn('Sorun 1')).not.toBeNull()
    unmount()
    render(<MonitorNotes type="PING" target="10.0.0.1" canManage={false} />)
    await screen.findByText('Sorun 1')
    expect(editBtn('Sorun 1')).toBeNull()
    expect(screen.queryByRole('button', { name: /not ekle|add note/i })).toBeNull()
  })

  it('ağ hatası işlenmemiş ret bırakmaz; yükleme biter', async () => {
    api.monitoring.getMonitorNotes.mockRejectedValue(new TypeError('Failed to fetch'))
    const { container } = render(<MonitorNotes type="PING" target="10.0.0.1" canManage />)
    await waitFor(() => expect(container.querySelector('[data-slot="note-list"], [data-slot="note-guide"]')).not.toBeNull())
  })
})

describe('ALERTS-20 — gürültü "İzlemeyi aç" hedefi', () => {
  let events = []
  const onNav = (e) => events.push(e.detail)
  beforeEach(() => { events = []; window.addEventListener('sm:navigate', onNav) })
  afterEach(() => window.removeEventListener('sm:navigate', onNav))

  it('sertifika satırı → Sertifika Panosu kartı (domain + open=cert); ACCESSIBILITY → Erişilebilirlik', () => {
    expect(noiseMonitorTarget({ monitorType: 'cert', alertType: 'EXPIRY', domain: 'a.example.com' }))
      .toEqual({ tab: 'dashboard', params: { domain: 'a.example.com', open: 'cert' } })
    expect(noiseMonitorTarget({ monitorType: 'http', alertType: 'ACCESSIBILITY', domain: 'b.example.com' }))
      .toEqual({ tab: 'uptime', params: { q: 'b.example.com' } })
    expect(noiseMonitorTarget({ monitorType: 'http', alertType: 'HTTP_DOWN', domain: 'c' })).toEqual({ tab: 'http', params: { q: 'c' } })
    expect(noiseMonitorTarget({ monitorType: 'cert', alertType: 'EXPIRY', domain: '' })).toBeNull()
    expect(noiseMonitorTarget({ monitorType: 'bogus', alertType: 'X', domain: 'c' })).toBeNull()
  })

  it('öneri eylemi open_monitor + cert → pano kartı (eskiden navigateTo("cert") ölüydü)', () => {
    const r = runSuggestionAction({ target: 'a.example.com', type: 'EXPIRY', action: { kind: 'open_monitor', tab: 'cert', params: { q: 'a.example.com' } } })
    expect(r.tab).toBe('dashboard')
    expect(r.params).toMatchObject({ domain: 'a.example.com', open: 'cert' })
    expect(events.at(-1)?.tab).toBe('dashboard')
  })
})
