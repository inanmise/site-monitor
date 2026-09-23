import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * ALARM KİME GİDİYOR — bu ekran eskalasyon kontaklarını yönetir ve buraya kadar SIFIR testi vardı.
 * Sessiz bozulma senaryosu net: bir kontak yanlışlıkla silinir ya da pasife çekilirse KRİTİK alarm
 * kimseye ulaşmaz ve hiçbir hata görünmez — kimse fark etmez. Bu yüzden burada odak:
 *  - silme ONAY istiyor mu, iptalde istek gitmiyor mu,
 *  - kaydetme payload'ı doğru mu (özellikle team_id/user_id sayıya çevrimi ve webhook alanları),
 *  - sunucu reddinde ekran "kaydedildi" demiyor mu.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ admin: {
    getContacts: vi.fn(),
    getUsers: vi.fn(),
    getTeams: vi.fn(),
    addContact: vi.fn(),
    updateContact: vi.fn(),
    deleteContact: vi.fn(),
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

const CONTACTS = [
  { id: 1, user_id: 7, name: 'Ali V', email: 'ali@example.com', role: 'PO',
    min_alert_level: 'WARNING', active: true, team_id: 5, team_name: 'SY-A' },
  { id: 2, user_id: 8, name: 'Ayse Y', email: 'ayse@example.com', role: 'MANAGER',
    min_alert_level: 'CRITICAL', active: true, team_id: 5, team_name: 'SY-A' },
]
const USERS = [
  { id: 7, username: 'ali', display_name: 'Ali V', email: 'ali@example.com', active: true },
  { id: 8, username: 'ayakut',   display_name: 'Ayse Y', email: 'ayse@example.com', active: true },
]

const renderEc = () => render(<LangProvider><EscalationContacts systemRole="ADMIN" /></LangProvider>)

describe('EscalationContacts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock.mockResolvedValue(true)
    api.admin.getContacts.mockResolvedValue({ success: true, data: CONTACTS })
    api.admin.getUsers.mockResolvedValue({ success: true, data: USERS })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.admin.deleteContact.mockResolvedValue({ success: true })
    api.admin.addContact.mockResolvedValue({ success: true, data: {} })
    api.admin.updateContact.mockResolvedValue({ success: true, data: {} })
  })

  it('kontak listesini basar', async () => {
    renderEc()
    expect(await screen.findByText('Ali V')).toBeInTheDocument()
    expect(screen.getByText('Ayse Y')).toBeInTheDocument()
  })

  it('SİLME onay ister; iptal edilirse kontak SİLİNMEZ (alarm sessizce susmasın)', async () => {
    confirmMock.mockResolvedValue(false)
    renderEc()
    await screen.findByText('Ali V')

    const kebabs = screen.getAllByRole('button', { name: /actions|işlem/i })
    fireEvent.click(kebabs[0])
    fireEvent.click(await screen.findByText(/^Delete$|^Sil$/i))

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.deleteContact).not.toHaveBeenCalled()
  })

  it('silme onaylanınca DOĞRU id ile silinir ve liste yeniden yüklenir', async () => {
    renderEc()
    await screen.findByText('Ali V')

    const kebabs = screen.getAllByRole('button', { name: /actions|işlem/i })
    fireEvent.click(kebabs[1])                     // ikinci satır → id=2
    fireEvent.click(await screen.findByText(/^Delete$|^Sil$/i))

    await waitFor(() => expect(api.admin.deleteContact).toHaveBeenCalledWith(2))
    await waitFor(() => expect(api.admin.getContacts).toHaveBeenCalledTimes(2))
  })

  it('silme sunucuda başarısız olursa hata bildirilir (sessizce başarılı sayılmaz)', async () => {
    api.admin.deleteContact.mockResolvedValue({ success: false, error: 'son kontak silinemez' })
    renderEc()
    await screen.findByText('Ali V')

    fireEvent.click(screen.getAllByRole('button', { name: /actions|işlem/i })[0])
    fireEvent.click(await screen.findByText(/^Delete$|^Sil$/i))

    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    expect(toastMock.success).not.toHaveBeenCalled()
  })

  it('düzenleme kaydında payload sayısal alanları ÇEVİRİR ve doğru id ile güncellenir', async () => {
    renderEc()
    await screen.findByText('Ali V')

    fireEvent.click(screen.getAllByRole('button', { name: /actions|işlem/i })[0])
    fireEvent.click(await screen.findByText(/^Edit$|^Düzenle$/i))
    fireEvent.click(await screen.findByRole('button', { name: /^Save$|^Kaydet$/i }))

    await waitFor(() => expect(api.admin.updateContact).toHaveBeenCalled())
    const [id, payload] = api.admin.updateContact.mock.calls[0]
    expect(id).toBe(1)
    expect(payload.user_id).toBe(7)          // string DEĞİL sayı
    expect(payload.team_id).toBe(5)
    expect(payload.role).toBe('PO')
    expect(payload.min_alert_level).toBe('WARNING')
    expect(api.admin.addContact).not.toHaveBeenCalled()   // düzenleme create'e düşmemeli
  })

  it('kaydetme sunucuda reddedilirse modal KAPANMAZ ve liste yeniden yüklenmez', async () => {
    api.admin.updateContact.mockResolvedValue({ success: false, error: 'çakışma' })
    renderEc()
    await screen.findByText('Ali V')

    fireEvent.click(screen.getAllByRole('button', { name: /actions|işlem/i })[0])
    fireEvent.click(await screen.findByText(/^Edit$|^Düzenle$/i))
    const saveBtn = await screen.findByRole('button', { name: /^Save$|^Kaydet$/i })
    fireEvent.click(saveBtn)

    await waitFor(() => expect(api.admin.updateContact).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /^Save$|^Kaydet$/i })).toBeInTheDocument()
    expect(api.admin.getContacts).toHaveBeenCalledTimes(1)   // yeniden yükleme YOK
  })

  it('boş liste çökmez (API data:null döndürse bile)', async () => {
    api.admin.getContacts.mockResolvedValue({ success: true, data: [] })
    api.admin.getUsers.mockResolvedValue({ success: true, data: null })
    renderEc()

    await waitFor(() => expect(api.admin.getContacts).toHaveBeenCalled())
    expect(screen.queryByText('Ali V')).toBeNull()
  })
})
