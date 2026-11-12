import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils'

/**
 * Bildirim grupları TEMİZLİK (2026-11-12, #7): usage-counts ucundan kullanım sayısı → satır rozeti (N izleme /
 * kullanılmıyor), KPI kartları (kullanılmayan/adressiz/pasif) ve "kullanılmayan" süzgeci. Dil-bağımsız: data-slot/data-usage.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const state = vi.hoisted(() => ({ groups: [], counts: {} }))

vi.mock('../api/client', () => ({
  formatDateSec: (v) => v ?? '',
  api: withApiFallback({
    notificationGroups: {
      list: vi.fn(async () => ({ success: true, data: { groups: state.groups, writable_team_ids: ['1'], team_emails: { 1: 'sy-a@x' } } })),
      usageCounts: vi.fn(async () => ({ success: true, data: { counts: state.counts } })),
      usage: vi.fn(async () => ({ success: true, data: { total: 0, by_type: {}, items: [] } })),
      create: vi.fn(async () => ({ success: true, data: {} })),
      update: vi.fn(async () => ({ success: true, data: {} })),
      remove: vi.fn(async () => ({ success: true })),
      reassign: vi.fn(async () => ({ success: true, data: {} })),
      makeDefault: vi.fn(async () => ({ success: true, data: {} })),
      history: vi.fn(async () => ({ success: true, data: { items: [], truncated: false, hidden: 0 } })),
    },
  }),
}))
vi.mock('../components/ui/Dialog.jsx', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useDialog: () => ({ showConfirm: async () => true }) }
})

const { api } = await import('../api/client')
const NotificationGroups = (await import('../components/admin/NotificationGroups.jsx')).default
const TEAMS = [{ id: 1, name: 'SY-A' }]
const grp = (id, name, over = {}) => ({ id, team_id: 1, name, emails: ['a@x'], is_default: false, active: true, can_write: true, ...over })

beforeEach(() => {
  vi.clearAllMocks()
  // g10 kullanımda (3), g11 kullanılmıyor (0), g12 adressiz + kullanılmıyor
  state.groups = [grp(10, 'Kullanilan'), grp(11, 'Bos-A'), grp(12, 'Adressiz', { emails: [] })]
  state.counts = { 10: 3 }
})

describe('NotificationGroups — temizlik', () => {
  it('satır kullanım rozetleri: kullanılan → sayı, kullanılmayan → 0', async () => {
    render(<NotificationGroups teams={TEAMS} systemRole="ADMIN" />)
    await screen.findByText('Kullanilan')
    await waitFor(() => expect(document.querySelector('[data-slot="ng-usage"][data-usage="3"]')).toBeTruthy())
    expect(document.querySelectorAll('[data-slot="ng-usage"][data-usage="0"]').length).toBe(2)   // g11, g12
  })

  it('"Kullanılmayan" KPI kartı listeyi süzer (yalnız 0 kullanımlı aktif gruplar)', async () => {
    render(<NotificationGroups teams={TEAMS} systemRole="ADMIN" />)
    await screen.findByText('Kullanilan')
    const unused = await waitFor(() => {
      const el = document.querySelector('[data-slot="stat-item"][data-key="unused"]')
      expect(el).toBeTruthy()
      return el
    })
    expect(unused.querySelector('[data-slot="stat-value"]').textContent).toBe('2')
    fireEvent.click(unused)
    await waitFor(() => {
      expect(screen.queryByText('Kullanilan')).toBeNull()   // kullanımda → gizli
      expect(screen.getByText('Bos-A')).toBeInTheDocument()
      expect(screen.getByText('Adressiz')).toBeInTheDocument()
    })
  })
})
