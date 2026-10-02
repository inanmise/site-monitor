import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import UserEditModal from '../components/admin/UserEditModal.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { updateUser: vi.fn() } }),
}))

import { api } from '../api/client'

const sampleUser = {
  id: 7,
  username: 'ali',
  display_name: 'Ali V',
  email: 'ali@example.com',
  employee_id: '12345',
  system_role: 'ADMIN',
  org_role: 'TECH',
  team_id: 3,
  active: true,
}

const teams = [{ id: 3, name: 'Payments' }, { id: 4, name: 'Cards' }]

describe('UserEditModal', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('returns null when no user is supplied', () => {
    const { container } = render(<UserEditModal user={null} teams={teams} onClose={() => {}} />)
    expect(container.firstChild).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('renders the username as disabled and pre-fills email and employee id', () => {
    render(<UserEditModal user={sampleUser} teams={teams} onClose={() => {}} />)
    const username = screen.getByDisplayValue('ali')
    expect(username).toBeDefined()
    expect(username.disabled).toBe(true)
    expect(screen.getByDisplayValue('ali@example.com')).toBeDefined()
    expect(screen.getByDisplayValue('12345')).toBeDefined()
  })

  it('invokes api.admin.updateUser and onSaved on successful save', async () => {
    api.admin.updateUser.mockResolvedValueOnce({ success: true, data: { ...sampleUser, display_name: 'New Name' } })
    const onSaved = vi.fn()
    const onClose = vi.fn()

    render(<UserEditModal user={sampleUser} teams={teams} onClose={onClose} onSaved={onSaved} />)
    // 2026-10-02 (paylaşılan düzenleyici): değişiklik yokken Kaydet kapalı — önce bir alan değişir.
    fireEvent.change(screen.getByDisplayValue('Ali V'), { target: { value: 'New Name' } })

    const saveBtn = screen.getByRole('button', { name: /^(kaydet|save)$/i })
    fireEvent.click(saveBtn)

    await waitFor(() => expect(api.admin.updateUser).toHaveBeenCalledWith(
      7,
      expect.objectContaining({
        username: 'ali',
        email: 'ali@example.com',
        display_name: 'New Name',
        team_id: 3,
        system_role: 'ADMIN',
        org_role: 'TECH',
      }),
    ))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('keeps the modal open when the api returns an error', async () => {
    api.admin.updateUser.mockResolvedValueOnce({ success: false, error: 'duplicate' })
    const onSaved = vi.fn()
    const onClose = vi.fn()

    render(<UserEditModal user={sampleUser} teams={teams} onClose={onClose} onSaved={onSaved} />)
    fireEvent.change(screen.getByDisplayValue('Ali V'), { target: { value: 'New Name' } })
    fireEvent.click(screen.getByRole('button', { name: /^(kaydet|save)$/i }))

    await waitFor(() => expect(api.admin.updateUser).toHaveBeenCalled())
    expect(onSaved).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })

  // 2026-10-02 (kullanıcı kararı): aktiflik değişiminin sonucu kaydetmeden ÖNCE formda yazar.
  // Paylaşılan düzenleyicide aktiflik bir seçim kartındaki shadcn Switch (adı "Active").
  it('aktif kullanıcının "Aktif" anahtarı kapatılınca pasifleştirme sonucu görünür (oturumlar kapanır, giriş/bildirim yok)', () => {
    render(<UserEditModal user={sampleUser} teams={teams} onClose={() => {}} />)
    expect(screen.queryByText(/signed out of every open session/i)).toBeNull()
    fireEvent.click(screen.getByRole('switch', { name: /^Active$/ }))
    expect(screen.getByText(/signed out of every open session/i)).toBeDefined()
  })

  it('pasif kullanıcı yeniden aktifleştirilirken "oturumlar geri gelmez" bilgisi görünür', () => {
    render(<UserEditModal user={{ ...sampleUser, active: false }} teams={teams} onClose={() => {}} />)
    expect(screen.queryByText(/closed sessions do not come back/i)).toBeNull()
    fireEvent.click(screen.getByRole('switch', { name: /^Active$/ }))
    expect(screen.getByText(/closed sessions do not come back/i)).toBeDefined()
  })
})
