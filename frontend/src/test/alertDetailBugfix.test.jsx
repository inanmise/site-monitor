import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

/**
 * Alarm detayı — 2026-10-22 hata düzeltmeleri:
 *  - UIM-34: kullanıcının KENDİ kapsamı dışındaki alarmda (paylaşımla görülen / başka takımın) "Olay kaydı aç" formu sahip
 *    takımla ön dolduruluyordu → kaydet 403. Takım boş gelir; kapsam içindeki alarmda bugünkü gibi dolu.
 *  - L-H7: bilgilendirilen kişiler yuvarlak rozette kırpılıyordu — ad + e-posta sarar.
 */
const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {}, admin: {} }) }
})
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '' }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: () => true, canExecute: () => false, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange, placeholder }) => <input aria-label={placeholder || 'date'} value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({
  default: ({ value, onChange }) => <textarea data-slot="md-edit" value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))
import { api } from '../api/client'
import { AlertDetailBody } from '../components/admin/alerts/AlertDetail.jsx'

const ALERT = { id: 50, domain: 'db.example.com', alert_type: 'PING_DOWN', alert_level: 'CRITICAL', resolved: false, acknowledged: false,
  created_at: '2026-10-01T06:00:00', team_id: 1, team_name: 'Takım A', message: 'KRİTİK' }
const NOW = Date.parse('2026-10-01T07:00:00Z')

beforeEach(() => {
  vi.clearAllMocks()
  api.incidents.byAlert.mockResolvedValue({ success: true, data: [] })
  api.incidents.options.mockResolvedValue({ success: true, data: [] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }] })
})

async function openForm(props) {
  render(<AlertDetailBody alert={ALERT} teamName="Takım A" nowMs={NOW} {...props} />)
  await waitFor(() => expect(api.incidents.byAlert).toHaveBeenCalled())
  fireEvent.click(screen.getByRole('button', { name: /^(Olay kaydı aç|Open incident record)$/ }))
  return screen.findByRole('dialog', { name: /Yeni Olay Kaydı|New Incident/ })
}

describe('UIM-34 — kapsam dışı alarmda olay formu sahip takımla dolmaz', () => {
  it('paylaşımla görülen alarm (actBlocked="shared") → takım boş', async () => {
    const dlg = await openForm({ actBlocked: 'shared' })
    expect(within(dlg).getByRole('combobox', { name: /^(Takım|Team)/ })).toHaveValue('')
  })
  it('kapsam içi alarm (actBlocked yok) → bugünkü gibi sahip takım', async () => {
    const dlg = await openForm({})
    expect(within(dlg).getByRole('combobox', { name: /^(Takım|Team)/ })).toHaveValue('1')
  })
})

describe('L-H7 — bilgilendirilen kişiler kırpılmaz', () => {
  it('uzun AD adı + e-posta sararak tam görünür', async () => {
    const name = 'Ayşe Yılmaz-Karaosmanoğlu (Dijital Kanallar Direktörlüğü / Kıdemli Uygulama Yöneticisi)'
    render(<AlertDetailBody alert={{ ...ALERT, notified_contacts: JSON.stringify([{ name, email: 'ayse.yilmaz-karaosmanoglu@example.com', role: 'MANAGER' }]) }}
      teamName="Takım A" nowMs={NOW} />)
    const chip = await waitFor(() => { const c = document.querySelector('[data-slot="alert-contact"]'); if (!c) throw new Error('yok'); return c })
    expect(chip.textContent).toContain(name)
    expect(chip.textContent).toContain('ayse.yilmaz-karaosmanoglu@example.com')
    expect(chip.className).not.toMatch(/\btruncate\b|rounded-full/)
  })
})
