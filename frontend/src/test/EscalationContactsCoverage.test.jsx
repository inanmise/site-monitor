import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Kişiler → eskalasyon KAPSAM matrisi (2026-11-12, çapraz zenginleştirme #1). Boşluk = varlık var ama CRITICAL kişisi
 * yok → en sert alarm kimseye tırmanmaz. Panel bunu yüzeye çıkarır (boşlukta varsayılan AÇIK), KPI süzer, "Kişi ekle"
 * formu o takım + WARNING eşiğiyle açar. Diyalog varsayılan dilden bağımsız kancalarla (data-slot/data-tone/takım adı).
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({ admin: {
    getContacts: vi.fn(), getUsers: vi.fn(), teamStats: vi.fn(),
    addContact: vi.fn(), updateContact: vi.fn(), deleteContact: vi.fn(),
  } }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Toast.jsx', () => ({
  useToast: () => toastMock,
  ToastProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import EscalationContacts from '../components/admin/EscalationContacts.jsx'

const TEAMS = [{ id: 5, name: 'SY-A' }, { id: 6, name: 'SY-B' }]
// Takım 5: WARNING + CRITICAL kişi → kritik kapsanır (ok). Takım 6: kişi yok ama varlık var → kritik boşluk (gap).
const CONTACTS = [
  { id: 1, user_id: 7, name: 'Ali V', email: 'ali@x', role: 'PO', min_alert_level: 'WARNING', active: true, team_id: 5 },
  { id: 2, user_id: 8, name: 'Ayse Y', email: 'ayse@x', role: 'MANAGER', min_alert_level: 'CRITICAL', active: true, team_id: 5 },
]
const USERS = [{ id: 7, active: true }, { id: 8, active: true }]
const STATS = { 5: { monitors: 3 }, 6: { monitors: 2 } }

const renderEc = () => render(<LangProvider><EscalationContacts systemRole="ADMIN" teams={TEAMS} /></LangProvider>)
const rows = () => document.querySelectorAll('[data-slot="cov-row"]')

beforeEach(() => {
  vi.clearAllMocks()
  confirmMock.mockResolvedValue(true)
  api.admin.getContacts.mockResolvedValue({ success: true, data: CONTACTS })
  api.admin.getUsers.mockResolvedValue({ success: true, data: USERS })
  api.admin.teamStats.mockResolvedValue({ success: true, data: STATS })
})

describe('EscalationContacts — kapsam matrisi', () => {
  it('panel çizilir; boşlukta otomatik açılır; takım tonları doğru (6=gap, 5=ok)', async () => {
    renderEc()
    expect(document.querySelector('[data-slot="contacts-coverage"]')).toBeTruthy()
    await waitFor(() => expect(rows().length).toBe(2))   // otomatik açıldı → içerik görünür
    const byTeam = Object.fromEntries([...rows()].map(r => [r.getAttribute('data-team'), r.getAttribute('data-tone')]))
    expect(byTeam['6']).toBe('gap')
    expect(byTeam['5']).toBe('ok')
  })

  it('KPI "Kritik boşluk" matrisi süzer (yalnız gap satırı kalır)', async () => {
    renderEc()
    await waitFor(() => expect(rows().length).toBe(2))
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="gap"]'))
    await waitFor(() => {
      expect(rows().length).toBe(1)
      expect(rows()[0].getAttribute('data-team')).toBe('6')
    })
  })

  it('"Kişi ekle" → ekle formu o takım (SY-B) + WARNING eşiğiyle önceden dolu açılır', async () => {
    renderEc()
    const addBtn = await waitFor(() => {
      const b = document.querySelector('[data-slot="cov-quickadd"][data-team="6"]')
      expect(b).toBeTruthy()
      return b
    })
    fireEvent.click(addBtn)
    const dlg = await screen.findByRole('dialog')
    // SearchableSelect seçili etiketi tetikte gösterir (takım adı + seviye) — dilden bağımsız kontrol
    expect(within(dlg).getByText('SY-B')).toBeInTheDocument()
    expect(within(dlg).getByText(/WARNING|UYARI/)).toBeInTheDocument()
  })
})
