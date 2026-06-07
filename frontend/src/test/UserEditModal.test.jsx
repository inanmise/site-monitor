import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import UserEditModal from '../components/admin/UserEditModal.jsx'

vi.mock('../api/client', () => ({
  api: { admin: { updateUser: vi.fn() } },
}))

import { api } from '../api/client'

const sampleUser = {
  id: 7,
  username: 'einanmis',
  display_name: 'Erdi I',
  email: 'erdi@example.com',
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
    expect(container.querySelector('.modal-overlay')).toBeNull()
  })

  it('renders the username as disabled and pre-fills email and employee id', () => {
    render(<UserEditModal user={sampleUser} teams={teams} onClose={() => {}} />)
    const username = screen.getByDisplayValue('einanmis')
    expect(username).toBeDefined()
    expect(username.disabled).toBe(true)
    expect(screen.getByDisplayValue('erdi@example.com')).toBeDefined()
    expect(screen.getByDisplayValue('12345')).toBeDefined()
  })

  it('invokes api.admin.updateUser and onSaved on successful save', async () => {
    api.admin.updateUser.mockResolvedValueOnce({ success: true, data: { ...sampleUser, display_name: 'New Name' } })
    const onSaved = vi.fn()
    const onClose = vi.fn()

    render(<UserEditModal user={sampleUser} teams={teams} onClose={onClose} onSaved={onSaved} />)

    const saveBtn = screen.getByRole('button', { name: /kaydet|save/i })
    fireEvent.click(saveBtn)

    await waitFor(() => expect(api.admin.updateUser).toHaveBeenCalledWith(
      7,
      expect.objectContaining({
        username: 'einanmis',
        email: 'erdi@example.com',
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
    fireEvent.click(screen.getByRole('button', { name: /kaydet|save/i }))

    await waitFor(() => expect(api.admin.updateUser).toHaveBeenCalled())
    expect(onSaved).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })
})
