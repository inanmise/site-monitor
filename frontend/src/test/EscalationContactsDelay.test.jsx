import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Zamana bağlı eskalasyon adımı (2026-10-01, opt-in) — kişi formundaki "Gecikme (dk)" alanı.
 * Güvence: alan boş bırakılırsa istek gövdesi BUGÜNKÜYLE aynı (anahtar yok → sunucu alana dokunmaz). Değer 1–1440 tam sayı;
 * hatalı değer tost değil ALANIN ALTINDA gösterilir ve istek gitmez. Listede gecikmeli kişi "N dk sonra" rozeti taşır.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({ admin: {
    getContacts: vi.fn(),
    getUsers: vi.fn(),
    addContact: vi.fn(),
    updateContact: vi.fn(),
    deleteContact: vi.fn(),
  } }),
}))

import { api } from '../api/client'
import EscalationContacts, { parseDelayInput, contactDelay } from '../components/admin/EscalationContacts.jsx'

const CONTACTS = [
  { id: 1, user_id: 7, name: 'Ali V', email: 'ali@example.com', role: 'PO', min_alert_level: 'WARNING', active: true, team_id: 5 },
  { id: 2, user_id: 8, name: 'Ayse Y', email: 'ayse@example.com', role: 'MANAGER', min_alert_level: 'CRITICAL', active: true,
    team_id: 5, delay_minutes: 45 },
]
const USERS = [
  { id: 7, username: 'ali', display_name: 'Ali V', email: 'ali@example.com', active: true },
  { id: 8, username: 'ayse', display_name: 'Ayse Y', email: 'ayse@example.com', active: true },
]
const TEAMS = [{ id: 5, name: 'SY-A' }]

const SAVE = /^(Save|Kaydet)$/
const DELAY = /^(Escalation delay \(min\)|Eskalasyon gecikmesi \(dk\))/
const INVALID = /^(Enter a whole number from 1 to 1440, or leave it empty\.|1 ile 1440 arasında tam sayı girin ya da boş bırakın\.)$/

const renderEc = () => render(<EscalationContacts systemRole="ADMIN" teams={TEAMS} />)

async function openAddWithUser() {
  renderEc()
  await screen.findByText('Ali V')
  fireEvent.click(screen.getByRole('button', { name: /^(Add Contact|Kişi Ekle)$/ }))
  const userBox = await screen.findByRole('combobox', { name: /^(User|Kullanıcı)/ })
  fireEvent.mouseDown(userBox)
  fireEvent.mouseDown(await screen.findByRole('option', { name: /Ali V/ }))
  await waitFor(() => expect(userBox).toHaveTextContent('Ali V'))
}

async function openEdit(rowIndex) {
  renderEc()
  await screen.findByText('Ali V')
  pressMenuTrigger(screen.getAllByRole('button', { name: /actions|işlem/i })[rowIndex])
  fireEvent.click(await screen.findByText(/^Edit$|^Düzenle$/i))
  await screen.findByRole('button', { name: SAVE })
}

describe('EscalationContacts — eskalasyon gecikmesi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getContacts.mockResolvedValue({ success: true, data: CONTACTS })
    api.admin.getUsers.mockResolvedValue({ success: true, data: USERS })
    api.admin.addContact.mockResolvedValue({ success: true, data: {} })
    api.admin.updateContact.mockResolvedValue({ success: true, data: {} })
  })

  it('parseDelayInput: boş → anlık; 1–1440 tam sayı → değer; 0 / 1441 / ondalık / metin → geçersiz', () => {
    expect(parseDelayInput('')).toEqual({ value: null, invalid: false })
    expect(parseDelayInput('  ')).toEqual({ value: null, invalid: false })
    expect(parseDelayInput('30')).toEqual({ value: 30, invalid: false })
    expect(parseDelayInput('1440')).toEqual({ value: 1440, invalid: false })
    for (const bad of ['0', '1441', '1.5', '-3', 'abc', '1e2']) expect(parseDelayInput(bad).invalid).toBe(true)
    expect(contactDelay({ delay_minutes: 45 })).toBe(45)
    expect(contactDelay({ delay_minutes: 0 })).toBeNull()
    expect(contactDelay({})).toBeNull()
  })

  it('YENİ kişi, gecikme boş: istek gövdesinde delay_minutes anahtarı YOK (bugünkü gövdeyle aynı)', async () => {
    await openAddWithUser()
    fireEvent.click(screen.getByRole('button', { name: SAVE }))
    await waitFor(() => expect(api.admin.addContact).toHaveBeenCalled())
    const payload = api.admin.addContact.mock.calls[0][0]
    expect(payload).not.toHaveProperty('delay_minutes')
    expect(Object.keys(payload).sort()).toEqual(
      ['active', 'min_alert_level', 'role', 'team_id', 'user_id', 'webhook_type', 'webhook_url'])
  })

  it('YENİ kişi, gecikme 30: sayı olarak gönderilir', async () => {
    await openAddWithUser()
    fireEvent.change(screen.getByRole('spinbutton', { name: DELAY }), { target: { value: '30' } })
    fireEvent.click(screen.getByRole('button', { name: SAVE }))
    await waitFor(() => expect(api.admin.addContact).toHaveBeenCalled())
    expect(api.admin.addContact.mock.calls[0][0]).toMatchObject({ user_id: 7, team_id: 5, delay_minutes: 30 })
  })

  it('geçersiz gecikme: istek GİTMEZ, hata alanın altında (aria-invalid), düzenleyince hata silinir', async () => {
    await openAddWithUser()
    const input = screen.getByRole('spinbutton', { name: DELAY })
    fireEvent.change(input, { target: { value: '2000' } })
    fireEvent.click(screen.getByRole('button', { name: SAVE }))

    const err = await screen.findByText(INVALID)
    expect(api.admin.addContact).not.toHaveBeenCalled()
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input.getAttribute('aria-describedby') || '').toContain(err.id)
    expect(err.closest('[data-field]')).toHaveAttribute('data-field', 'delay_minutes')

    fireEvent.change(input, { target: { value: '15' } })
    await waitFor(() => expect(screen.queryByText(INVALID)).toBeNull())
    fireEvent.click(screen.getByRole('button', { name: SAVE }))
    await waitFor(() => expect(api.admin.addContact).toHaveBeenCalled())
    expect(api.admin.addContact.mock.calls[0][0].delay_minutes).toBe(15)
  })

  it('gecikmesiz kişiyi düzenleme: gövde değişmez (anahtar yok)', async () => {
    await openEdit(0)
    expect(screen.getByRole('spinbutton', { name: DELAY })).toHaveValue(null)
    fireEvent.click(screen.getByRole('button', { name: SAVE }))
    await waitFor(() => expect(api.admin.updateContact).toHaveBeenCalled())
    const [id, payload] = api.admin.updateContact.mock.calls[0]
    expect(id).toBe(1)
    expect(payload).not.toHaveProperty('delay_minutes')
  })

  it('gecikmeli kişiyi düzenleme: alan mevcut değeri gösterir; silinirse açıkça null gider (anlık davranışa dönüş)', async () => {
    await openEdit(1)
    const input = screen.getByRole('spinbutton', { name: DELAY })
    expect(input).toHaveValue(45)
    fireEvent.change(input, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: SAVE }))
    await waitFor(() => expect(api.admin.updateContact).toHaveBeenCalled())
    const [id, payload] = api.admin.updateContact.mock.calls[0]
    expect(id).toBe(2)
    expect(payload).toHaveProperty('delay_minutes', null)
  })

  it('listede gecikmeli kişi "N dk sonra" rozetini taşır; gecikmesiz kişide rozet yok', async () => {
    renderEc()
    const ayseRow = (await screen.findByText('Ayse Y')).closest('tr')
    const badge = ayseRow.querySelector('[data-slot="ec-delay"]')
    expect(badge).not.toBeNull()
    expect(badge.textContent).toMatch(/^(after 45 min|45 dk sonra)$/)
    const aliRow = screen.getByText('Ali V').closest('tr')
    expect(within(aliRow).queryByText(/after \d+ min|\d+ dk sonra/)).toBeNull()
    expect(aliRow.querySelector('[data-slot="ec-delay"]')).toBeNull()
  })

  it('alan yardım metni boş = hemen (bugünkü davranış) ve onaysız kalma koşulunu açıklar', async () => {
    await openAddWithUser()
    const input = screen.getByRole('spinbutton', { name: DELAY })
    const hintId = (input.getAttribute('aria-describedby') || '').split(' ')[0]
    expect(document.getElementById(hintId).textContent)
      .toMatch(/current behaviour|bugünkü davranış/)
    expect(document.getElementById(hintId).textContent).toMatch(/unacknowledged|onaylanmadan/)
  })
})
