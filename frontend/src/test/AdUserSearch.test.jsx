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
    // 2026-11-13: kullanıcı adı da geçer — çağıran TAM satırı sunucudan okur (eksik satırla düzenleyici açılmaz)
    expect(onEditExisting).toHaveBeenCalledWith(9, 'MP123')
    expect(onClose).toHaveBeenCalled()
  })

  // ── 2026-11-13 hata taraması ──
  it('kullanıcı adı olmayan (posta kişisi) ve "$" ile biten (bilgisayar) satırlar gösterilmez; "Düzenle" kullanıcı adını da verir', async () => {
    api.admin.adUserSearch.mockResolvedValue({ success: true, results: [
      { dn: 'dn0', username: null, display_name: 'Posta Kişisi', email: 'kisi@x.com', exists_locally: false },
      { dn: 'dnpc', username: 'PC01$', display_name: 'PC01', exists_locally: false },
      { dn: 'dn1', username: 'MP123', display_name: 'Ayşe Yılmaz', email: 'a@x.com', exists_locally: true, local_user_id: 7 },
    ] })
    const onEditExisting = vi.fn()
    mountSearch({ onEditExisting })
    fireEvent.change(slot('aduser-search'), { target: { value: 'ay' } })
    await waitFor(() => expect(slots('aduser-result')).toHaveLength(1))
    expect(slots('aduser-result')[0].getAttribute('data-username')).toBe('MP123')
    fireEvent.click(slot('aduser-edit-existing'))
    expect(onEditExisting).toHaveBeenCalledWith(7, 'MP123')
  })

  it('AD erişilemez (503 AD_UNAVAILABLE): sunucu metni gösterilir ("bulunamadı" denmez), "Yeniden dene" detayı yeniden okur', async () => {
    api.admin.adUserSearch.mockResolvedValue({ success: true, results: [
      { dn: 'dn1', username: 'MP123', display_name: 'Ayşe', email: 'a@x.com', exists_locally: false },
    ] })
    api.admin.adUserDetail
      .mockResolvedValueOnce({ success: false, code: 'AD_UNAVAILABLE', error: "Active Directory'ye şu an ulaşılamıyor" })
      .mockResolvedValueOnce({ success: true, data: { username: 'MP123', display_name: 'Ayşe', derived_teams: [], exists_locally: false } })
    mountSearch()
    fireEvent.change(slot('aduser-search'), { target: { value: 'ayş' } })
    await waitFor(() => expect(slot('aduser-view')).toBeTruthy())
    fireEvent.click(slot('aduser-view'))
    await waitFor(() => expect(slot('aduser-detail-error')).toBeTruthy())
    expect(slot('aduser-detail-error').getAttribute('data-code')).toBe('AD_UNAVAILABLE')
    expect(slot('aduser-detail-error').textContent).toMatch(/ulaşılamıyor/)
    expect(slot('aduser-detail-error').textContent).not.toMatch(/bulunamadı|not found/i)
    fireEvent.click(slot('aduser-detail-retry'))
    await waitFor(() => expect(slot('aduser-detail')).toBeTruthy())
    expect(api.admin.adUserDetail).toHaveBeenCalledTimes(2)
    expect(slot('aduser-detail-error')).toBeNull()
  })

  it('bulunamadı (404) için "Yeniden dene" yok', async () => {
    api.admin.adUserSearch.mockResolvedValue({ success: true, results: [
      { dn: 'dn1', username: 'MP123', display_name: 'Ayşe', email: 'a@x.com', exists_locally: false },
    ] })
    api.admin.adUserDetail.mockResolvedValue({ success: false, code: 'AD_USER_NOT_FOUND', error: "AD'de bu kullanıcı bulunamadı" })
    mountSearch()
    fireEvent.change(slot('aduser-search'), { target: { value: 'ayş' } })
    await waitFor(() => expect(slot('aduser-view')).toBeTruthy())
    fireEvent.click(slot('aduser-view'))
    await waitFor(() => expect(slot('aduser-detail-error')).toBeTruthy())
    expect(slot('aduser-detail-retry')).toBeNull()
  })

  it("org rolü seçilmezse \"AD'den belirlensin (ilk girişte)\" — yük boş org_role taşır", async () => {
    api.admin.adUserSearch.mockResolvedValue({ success: true, results: [
      { dn: 'dn1', username: 'MP123', display_name: 'Ayşe', email: 'a@x.com', exists_locally: false },
    ] })
    api.admin.adUserDetail.mockResolvedValue({ success: true, data: {
      username: 'MP123', display_name: 'Ayşe', derived_teams: ['Dijital Kanallar'], exists_locally: false,
    } })
    api.admin.adUserProvision.mockResolvedValue({ success: true, user: { id: 50, username: 'MP123' } })
    mountSearch()
    fireEvent.change(slot('aduser-search'), { target: { value: 'ayş' } })
    await waitFor(() => expect(slot('aduser-view')).toBeTruthy())
    fireEvent.click(slot('aduser-view'))
    await waitFor(() => expect(slot('aduser-start-add')).toBeTruthy())
    fireEvent.click(slot('aduser-start-add'))
    await waitFor(() => expect(slot('aduser-add-form')).toBeTruthy())
    expect(slot('aduser-add-form').textContent).toMatch(/AD'den belirlensin|Set from AD/)
    fireEvent.click(slot('aduser-confirm-add'))
    await waitFor(() => expect(api.admin.adUserProvision).toHaveBeenCalled())
    expect(api.admin.adUserProvision.mock.calls[0][0].org_role).toBe('')
  })
})
