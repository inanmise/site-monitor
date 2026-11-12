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

// Birleşik alıcı önizlemesi (2026-11-13): inactive_emails → rozet, KPI süzgeci, adres açılır listesi, sessiz-kayıp bandı.
describe('NotificationGroups — alıcı önizlemesi', () => {
  beforeEach(() => {
    state.groups = [
      grp(20, 'Hepsi-Pasif', { emails: ['gone@x.com'], inactive_emails: ['gone@x.com'], is_default: true }),
      grp(21, 'Kismi', { emails: ['ok@x.com', 'gone2@x.com'], inactive_emails: ['gone2@x.com'] }),
      grp(22, 'Temiz', { emails: ['ok2@x.com'], inactive_emails: [] }),
    ]
    state.counts = { 21: 1, 22: 2 }
  })

  it('satır rozetleri: hepsi pasif → "etkin alıcı yok", kısmi → pasif adres; temiz grupta rozet yok', async () => {
    render(<NotificationGroups teams={TEAMS} systemRole="ADMIN" />)
    await screen.findByText('Hepsi-Pasif')
    expect(document.querySelectorAll('[data-slot="ng-recipients"][data-state="none"]').length).toBe(1)
    expect(document.querySelectorAll('[data-slot="ng-recipients"][data-state="partial"]').length).toBe(1)
    expect(document.querySelectorAll('[data-slot="ng-recipients"]').length).toBe(2)
  })

  it('varsayılan grubun tüm adresleri pasifse sessiz-kayıp bandı grubu adlandırır', async () => {
    render(<NotificationGroups teams={TEAMS} systemRole="ADMIN" />)
    const band = await waitFor(() => {
      const el = document.querySelector('[data-slot="ng-dead-banner"]')
      expect(el).toBeTruthy()
      return el
    })
    expect(band.textContent).toContain('Hepsi-Pasif')
    expect(band.textContent).toContain('SY-A')
    expect(band.textContent).not.toContain('Kismi')
  })

  it('"Pasif alıcılı" KPI = 2 ve tıklanınca yalnız o grupları gösterir', async () => {
    render(<NotificationGroups teams={TEAMS} systemRole="ADMIN" />)
    await screen.findByText('Temiz')
    const tile = document.querySelector('[data-slot="stat-item"][data-key="passive"]')
    expect(tile.querySelector('[data-slot="stat-value"]').textContent).toBe('2')
    fireEvent.click(tile)
    await waitFor(() => {
      expect(screen.queryByText('Temiz')).toBeNull()
      expect(screen.getByText('Hepsi-Pasif')).toBeInTheDocument()
      expect(screen.getByText('Kismi')).toBeInTheDocument()
    })
  })

  it('adres açılır listesi teslim edilen ve düşen adresleri ayırır', async () => {
    render(<NotificationGroups teams={TEAMS} systemRole="ADMIN" />)
    const row = (await screen.findByText('Kismi')).closest('tr')
    const chip = row.querySelector('[data-slot="ng-mailchip"]')
    expect(chip.getAttribute('data-dropped')).toBe('1')
    fireEvent.click(chip.closest('button'))
    const preview = await waitFor(() => {
      const el = document.querySelector('[data-slot="ng-recipients-preview"]')
      expect(el).toBeTruthy()
      return el
    })
    expect(preview.textContent).toContain('ok@x.com')
    expect([...preview.querySelectorAll('[data-slot="ng-mail-dropped"]')].map(x => x.textContent)).toEqual(['gone2@x.com'])
  })

  it('sunucu inactive_emails göndermezse (bilinmiyor) rozet / band yok, KPI "—"', async () => {
    state.groups = [grp(30, 'Eski-Sunucu', { emails: ['a@x.com'], is_default: true })]
    state.counts = {}
    render(<NotificationGroups teams={TEAMS} systemRole="ADMIN" />)
    await screen.findByText('Eski-Sunucu')
    expect(document.querySelector('[data-slot="ng-recipients"]')).toBeNull()
    expect(document.querySelector('[data-slot="ng-dead-banner"]')).toBeNull()
    const tile = document.querySelector('[data-slot="stat-item"][data-key="passive"]')
    expect(tile.querySelector('[data-slot="stat-value"]').textContent).toBe('—')
    expect(document.querySelector('[data-slot="ng-mailchip"]').getAttribute('data-dropped')).toBe('unknown')
  })
})
