import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'

/**
 * Alarmdan olay kaydı açma (2026-10-01) — alarm detayındaki "Olay kaydı aç" düğmesi ve "Olay kaydı #N" bağlantısı.
 * Pinlenenler: düğme YALNIZ `incidents.manage` (edit) sahibine (Olay & Hata Geçmişi'nin "Yeni kayıt" kapısı), bağlı kayıt
 * sorgusu yalnız `incidents.view` sahibine ve tek istek; form Olay & Hata Geçmişi'nin KENDİ formudur ve alarmdan ön
 * doldurulur; kaydetmeden kayıt oluşmaz; kayıt `alert_event_id` taşır; kayıt sonrası düğmenin yerini bağlantı alır;
 * bağlantı Olay & Hata Geçmişi'nde o kaydı açar.
 */

const { apiMock, perms } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {}, admin: {} }), perms: { view: true, manage: true } }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: {},
    canView: (r) => (r === 'incidents.view' ? perms.view : false),
    canEdit: (r) => (r === 'incidents.manage' ? perms.manage : false),
    canExecute: () => false,
    refresh: () => {},
  }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange, placeholder, invalid }) => (
    <input aria-label={placeholder || 'date'} aria-invalid={invalid || undefined} value={value || ''} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({
  default: ({ value, onChange, editable = true }) => (editable
    ? <textarea data-slot="md-edit" value={value || ''} onChange={(e) => onChange(e.target.value)} />
    : <div data-slot="md-view">{value}</div>),
}))

import { api } from '../api/client'
import { AlertDetailBody } from '../components/admin/alerts/AlertDetail.jsx'

const ALERT = {
  id: 50, domain: 'db.example.com', alert_type: 'PING_DOWN', alert_level: 'CRITICAL', resolved: false, acknowledged: false,
  created_at: '2026-10-01T06:00:00', team_id: 1, team_name: 'Takım A', message: 'KRİTİK: db.example.com ping yanıt vermiyor',
}

const openBtn = () => screen.queryByRole('button', { name: /^(Olay kaydı aç|Open incident record)$/ })
const linkBtn = (id) => screen.queryByRole('button', { name: new RegExp(`^(Olay kaydı|Incident record) #${id}$`) })

let navEvents = []
const onNav = (e) => navEvents.push(e.detail)

beforeEach(() => {
  vi.clearAllMocks()
  perms.view = true
  perms.manage = true
  navEvents = []
  window.addEventListener('sm:navigate', onNav)
  api.incidents.byAlert.mockResolvedValue({ success: true, data: [] })
  api.incidents.options.mockResolvedValue({ success: true, data: [] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }] })
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
})
afterEach(() => window.removeEventListener('sm:navigate', onNav))

describe('görünürlük (izin)', () => {
  it('incidents.manage yoksa "Olay kaydı aç" düğmesi YOK; incidents.view yoksa bağlı kayıt sorgusu da atılmaz', async () => {
    perms.manage = false
    perms.view = false
    render(<AlertDetailBody alert={ALERT} nowMs={Date.parse('2026-10-01T07:00:00Z')} />)
    await waitFor(() => expect(api.admin.getAlertNotifications).toHaveBeenCalled())
    expect(openBtn()).toBeNull()
    expect(api.incidents.byAlert).not.toHaveBeenCalled()
  })

  it('incidents.manage varsa düğme görünür; bağlı kayıt TEK istekle (alarm kimliğiyle) sorulur', async () => {
    render(<AlertDetailBody alert={ALERT} nowMs={Date.parse('2026-10-01T07:00:00Z')} />)
    expect(openBtn()).not.toBeNull()
    await waitFor(() => expect(api.incidents.byAlert).toHaveBeenCalledTimes(1))
    expect(api.incidents.byAlert).toHaveBeenCalledWith(50)
  })

  it('yalnız görüntüleyici (manage yok): bağlı kayıt varsa bağlantı görünür, düğme görünmez', async () => {
    perms.manage = false
    api.incidents.byAlert.mockResolvedValue({ success: true, data: [{ id: 12, title: 'Ödeme kesintisi', status: 'OPEN' }] })
    render(<AlertDetailBody alert={ALERT} nowMs={Date.parse('2026-10-01T07:00:00Z')} />)
    await waitFor(() => expect(linkBtn(12)).not.toBeNull())
    expect(openBtn()).toBeNull()
  })
})

describe('bağlı kayıt', () => {
  it('kayıt varsa düğme yerine "Olay kaydı #N"; tıklayınca Olay & Hata Geçmişi o kaydın ayrıntısıyla açılır', async () => {
    api.incidents.byAlert.mockResolvedValue({ success: true, data: [{ id: 12, title: 'Ödeme kesintisi', status: 'OPEN' }] })
    render(<AlertDetailBody alert={ALERT} nowMs={Date.parse('2026-10-01T07:00:00Z')} />)
    await waitFor(() => expect(linkBtn(12)).not.toBeNull())
    expect(openBtn()).toBeNull()
    expect(linkBtn(12)).toHaveAttribute('title', 'Ödeme kesintisi')
    fireEvent.click(linkBtn(12))
    expect(navEvents).toEqual([{ tab: 'incident-history', params: { ih_id: '12' } }])
  })
})

describe('form (ön dolgu + kayıt)', () => {
  async function openForm() {
    render(<AlertDetailBody alert={ALERT} teamName="Takım A" nowMs={Date.parse('2026-10-01T07:00:00Z')} />)
    await waitFor(() => expect(api.incidents.byAlert).toHaveBeenCalled())
    fireEvent.click(openBtn())
    return screen.findByRole('dialog', { name: /Yeni Olay Kaydı|New Incident/ })
  }

  it('Olay & Hata Geçmişi formu alarmdan ön doldurulmuş açılır; kaydetmeden kayıt OLUŞMAZ', async () => {
    const dlg = await openForm()
    expect(dlg.querySelector('[data-slot="incident-form"]')).not.toBeNull()
    expect(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ }).value).toMatch(/^db\.example\.com · (Erişilebilirlik|Reachability) \(Ping\)$/)
    expect(within(dlg).getByRole('textbox', { name: /^(Oluş Zamanı|Occurred)/ })).toHaveValue('2026-10-01T06:00:00')
    expect(within(dlg).getByRole('combobox', { name: /^(Takım|Team)/ })).toHaveValue('1')
    expect(within(dlg).getByRole('combobox', { name: /^(Önem|Severity)/ })).toHaveValue('CRITICAL')
    expect(within(dlg).getByRole('combobox', { name: /^(Durum|Status)/ })).toHaveValue('OPEN')
    expect(within(dlg).getByRole('combobox', { name: /^(Kategori|Category)/ })).toHaveValue('NETWORK')
    await waitFor(() => expect(api.admin.getTeams).toHaveBeenCalled())
    expect(api.incidents.options).toHaveBeenCalledWith('CHANNEL')
    expect(api.incidents.create).not.toHaveBeenCalled()
  })

  it('Kaydet: alert_event_id + ön dolgu (düzenlenmiş başlıkla) gider; başarıda pencere kapanır, düğmenin yerini "Olay kaydı #N" alır', async () => {
    const dlg = await openForm()
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ }), { target: { value: 'DB erişim kesintisi' } })
    api.incidents.create.mockResolvedValueOnce({ success: true, data: { id: 31, title: 'DB erişim kesintisi', status: 'OPEN', alert_event_id: 50 } })
    await act(async () => { fireEvent.click(dlg.querySelector('[data-action="save"]')) })
    expect(api.incidents.create).toHaveBeenCalledTimes(1)
    const payload = api.incidents.create.mock.calls[0][0]
    expect(payload).toMatchObject({
      alert_event_id: 50, title: 'DB erişim kesintisi', occurred_at: '2026-10-01T06:00:00', severity: 'CRITICAL',
      status: 'OPEN', category: 'NETWORK', team_id: '1', team_name: 'Takım A', service: 'db.example.com',
    })
    expect(payload.description).toContain('KRİTİK: db.example.com ping yanıt vermiyor')
    expect(payload.description).toMatch(/(Kaynak alarm|Source alert) #50: .*tab=alerthistory.*alert=50/)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(linkBtn(31)).not.toBeNull())
    expect(openBtn()).toBeNull()
  })

  it('sunucu reddi (ör. 403) form içinde satır içi kalır; bağlantı oluşmaz', async () => {
    const dlg = await openForm()
    api.incidents.create.mockResolvedValueOnce({ success: false, error: 'Bu alarmı görme yetkiniz yok' })
    await act(async () => { fireEvent.click(dlg.querySelector('[data-action="save"]')) })
    expect(within(dlg).getByRole('alert').textContent).toContain('Bu alarmı görme yetkiniz yok')
    expect(screen.queryByRole('button', { name: /^(Olay kaydı|Incident record) #/ })).toBeNull()
  })
})
