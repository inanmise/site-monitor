import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, waitFor, within } from '@testing-library/react'
import { render } from './test-utils.jsx'
import AdUserSearch from '../components/admin/aduser/AdUserSearch.jsx'

vi.mock('../api/client', () => ({
  api: { admin: {
    adUserSearch: vi.fn(),
    adUserDetail: vi.fn(),
    adUserProvision: vi.fn(),
  } },
  formatDateSec: (x) => x,
}))
import { api } from '../api/client'

const TEAMS = [{ id: 1, name: 'Dijital Kanallar' }, { id: 2, name: 'Ödeme Sistemleri' }]
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]

function mountSearch(props = {}) {
  return render(<AdUserSearch teams={TEAMS} onClose={props.onClose || (() => {})}
    onAdded={props.onAdded || (() => {})} onEditExisting={props.onEditExisting || (() => {})} />)
}

beforeEach(() => {
  api.admin.adUserSearch.mockReset()
  api.admin.adUserDetail.mockReset()
  api.admin.adUserProvision.mockReset()
})

describe('AdUserSearch', () => {
  it('kısmi arama sonuçları rozetleriyle listelenir (zaten var, ön ek uyarısı)', async () => {
    api.admin.adUserSearch.mockResolvedValue({ success: true, results: [
      { dn: 'dn1', username: 'MP123', display_name: 'Ayşe Yılmaz', email: 'a@x.com', department: 'BT', exists_locally: false, prefix_blocked: false },
      { dn: 'dn2', username: 'XX999', display_name: 'Mehmet Demir', email: 'm@x.com', exists_locally: true, prefix_blocked: false, local_user_id: 7 },
      { dn: 'dn3', username: 'ZZ1', display_name: 'Veli Kaya', email: 'v@x.com', exists_locally: false, prefix_blocked: true },
    ] })
    mountSearch()
    fireEvent.change(slot('aduser-search'), { target: { value: 'ay' } })
    await waitFor(() => expect(slots('aduser-result')).toHaveLength(3))
    expect(api.admin.adUserSearch).toHaveBeenCalledWith('ay')
    const rows = slots('aduser-result')
    expect(slot('aduser-badge-exists', rows[1])).toBeTruthy()
    expect(slot('aduser-badge-prefix', rows[2])).toBeTruthy()
    // zaten var olan satırda "Düzenle" var, "Görüntüle" yok
    expect(slot('aduser-edit-existing', rows[1])).toBeTruthy()
    expect(slot('aduser-view', rows[1])).toBeNull()
  })

  it('az harfle aranmaz', async () => {
    mountSearch()
    fireEvent.change(slot('aduser-search'), { target: { value: 'a' } })
    await new Promise((r) => setTimeout(r, 350))
    expect(api.admin.adUserSearch).not.toHaveBeenCalled()
  })

  it('sonucu seç → detay + türetilen takımlar; "Sisteme ekle" formu açar, takımsız doğrulama durdurur, sonra ekler', async () => {
    api.admin.adUserSearch.mockResolvedValue({ success: true, results: [
      { dn: 'dn1', username: 'MP123', display_name: 'Ayşe Yılmaz', email: 'a@x.com', exists_locally: false },
    ] })
    api.admin.adUserDetail.mockResolvedValue({ success: true, data: {
      username: 'MP123', display_name: 'Ayşe Yılmaz', email: 'a@x.com', employee_id: '12345',
      department: 'BT', title: 'Uzman', derived_teams: ['Ödeme Sistemleri'], exists_locally: false, prefix_blocked: false,
    } })
    const onAdded = vi.fn(); const onClose = vi.fn()
    mountSearch({ onAdded, onClose })
    fireEvent.change(slot('aduser-search'), { target: { value: 'ayş' } })
    await waitFor(() => expect(slot('aduser-view')).toBeTruthy())
    fireEvent.click(slot('aduser-view'))
    await waitFor(() => expect(slot('aduser-detail')).toBeTruthy())
    expect(slot('aduser-derived-teams')).toBeTruthy()

    fireEvent.click(slot('aduser-start-add'))
    await waitFor(() => expect(slot('aduser-add-form')).toBeTruthy())

    // Türetilen takım (Ödeme Sistemleri = id 2) öneri olarak öndolu → provision onu taşır
    api.admin.adUserProvision.mockResolvedValue({ success: true, user: { id: 50, username: 'MP123', display_name: 'Ayşe Yılmaz' }, prefix_blocked: false })
    fireEvent.click(slot('aduser-confirm-add'))
    await waitFor(() => expect(api.admin.adUserProvision).toHaveBeenCalled())
    const payload = api.admin.adUserProvision.mock.calls[0][0]
    expect(payload.username).toBe('MP123')
    expect(payload.team_ids).toEqual([2])
    expect(onAdded).toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()
  })

  it('türetilen takım yoksa, takım seçilmeden eklenemez (doğrulama)', async () => {
    api.admin.adUserSearch.mockResolvedValue({ success: true, results: [
      { dn: 'dn1', username: 'MP123', display_name: 'Ayşe', email: 'a@x.com', exists_locally: false },
    ] })
    api.admin.adUserDetail.mockResolvedValue({ success: true, data: {
      username: 'MP123', display_name: 'Ayşe', email: 'a@x.com', derived_teams: [], exists_locally: false,
    } })
    mountSearch()
    fireEvent.change(slot('aduser-search'), { target: { value: 'ayş' } })
    await waitFor(() => expect(slot('aduser-view')).toBeTruthy())
    fireEvent.click(slot('aduser-view'))
    await waitFor(() => expect(slot('aduser-detail')).toBeTruthy())
    fireEvent.click(slot('aduser-start-add'))
    await waitFor(() => expect(slot('aduser-add-form')).toBeTruthy())
    fireEvent.click(slot('aduser-confirm-add'))
    await waitFor(() => expect(document.querySelector('[data-field="team_ids"]')).toBeTruthy())
    expect(api.admin.adUserProvision).not.toHaveBeenCalled()
  })

  it('provision 409 USER_EXISTS → "zaten sistemde" + Düzenle', async () => {
    api.admin.adUserSearch.mockResolvedValue({ success: true, results: [
      { dn: 'dn1', username: 'MP123', display_name: 'Ayşe', email: 'a@x.com', exists_locally: false },
    ] })
    api.admin.adUserDetail.mockResolvedValue({ success: true, data: {
      username: 'MP123', display_name: 'Ayşe', email: 'a@x.com', derived_teams: ['Dijital Kanallar'], exists_locally: false,
    } })
    api.admin.adUserProvision.mockResolvedValue({ success: false, code: 'USER_EXISTS', error: 'zaten var', user: { id: 9 } })
    const onEditExisting = vi.fn(); const onClose = vi.fn()
    mountSearch({ onEditExisting, onClose })
    fireEvent.change(slot('aduser-search'), { target: { value: 'ayş' } })
    await waitFor(() => expect(slot('aduser-view')).toBeTruthy())
    fireEvent.click(slot('aduser-view'))
    await waitFor(() => expect(slot('aduser-start-add')).toBeTruthy())
    fireEvent.click(slot('aduser-start-add'))
    await waitFor(() => expect(slot('aduser-confirm-add')).toBeTruthy())
    fireEvent.click(slot('aduser-confirm-add'))
    await waitFor(() => expect(slot('aduser-exists-note')).toBeTruthy())
    fireEvent.click(within(slot('aduser-exists-note')).getByRole('button'))
    expect(onEditExisting).toHaveBeenCalledWith(9)
    expect(onClose).toHaveBeenCalled()
  })
})
