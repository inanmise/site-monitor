import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from './test-utils.jsx'

vi.mock('../api/client', () => ({ api: { admin: { getUsers: vi.fn() } } }))
const nav = vi.fn()
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => nav(...a) }))
const stub = vi.hoisted(() => (slotName) => async () => {
  const React = await import('react')
  return {
    default: (props) => React.createElement('div', { 'data-slot': slotName, 'data-users': props.users ? props.users.length : '' },
      React.createElement('button', { type: 'button', onClick: props.onClose }, 'kapat'),
      React.createElement('button', { type: 'button', onClick: () => (props.onDone || props.onSaved)?.() }, 'kaydet'),
      props.onCreateGroup && React.createElement('button', { type: 'button', onClick: () => props.onCreateGroup(4) }, 'grup oluştur')),
  }
})
vi.mock('../components/admin/teams/TeamBulkContact.jsx', stub('stub-contact'))
vi.mock('../components/admin/teams/TeamBulkDefaultGroup.jsx', stub('stub-group'))
vi.mock('../components/admin/oncall/OnCallScheduleModal.jsx', stub('stub-oncall'))

import { api } from '../api/client'
import GapsFix from '../components/gaps/GapsFix.jsx'
import { LOAD_DEADLINE_MS } from '../components/gaps/gapsModel.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/** Eksikler penceresinden yerinde düzeltme (E paketi): var olan düzeltme pencereleri; kişi listesi okuması süre sınırlı. */
const slot = (n) => document.querySelector(`[data-slot="${n}"]`)
const TEAM = { id: 4, name: 'Dijital Kanallar' }

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})
afterEach(() => { vi.useRealTimers() })

describe('eksikler — yerinde düzeltme', () => {
  it('eskalasyon kişisi: Takımlar ekranının kapsamlı listesi (/admin/users), yalnız etkin kullanıcılar; okunurken bekleme penceresi (boş ekran yok), kaydedince yenileme', async () => {
    let resolve
    api.admin.getUsers.mockReturnValue(new Promise((r) => { resolve = r }))
    const onClose = vi.fn()
    const onChanged = vi.fn()
    render(<GapsFix fix={{ kind: 'contact', team: TEAM }} onClose={onClose} onChanged={onChanged} />)
    expect(await screen.findByText('Kişi listesi okunuyor…')).toBeInTheDocument()
    expect(slot('gaps-fix-loading')).not.toBeNull()
    await act(async () => { resolve({ success: true, data: [{ id: 1, active: true }, { id: 2, active: false }, { id: 3 }] }) })
    await waitFor(() => expect(slot('stub-contact')).not.toBeNull())
    expect(slot('stub-contact').getAttribute('data-users')).toBe('2')
    expect(slot('gaps-fix-loading')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'kaydet' }))
    expect(onChanged).toHaveBeenCalledTimes(1)
  })

  it('kişi listesi takılırsa üst sürede HATA penceresi (süresiz bekleme yok, boş seçici YOK); bekleme penceresi kapatılabilir', async () => {
    vi.useFakeTimers()
    api.admin.getUsers.mockReturnValue(new Promise(() => {}))
    const onClose = vi.fn()
    render(<GapsFix fix={{ kind: 'contact', team: TEAM }} onClose={onClose} onChanged={() => {}} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(10) })
    expect(slot('gaps-fix-loading')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Kapat' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(LOAD_DEADLINE_MS + 10) })
    // 2026-11-15 (prod kapısı): süre dolması "kullanıcı yok" DEĞİL — seçici boş listeyle açılmaz
    expect(slot('stub-contact')).toBeNull()
    expect(slot('gaps-fix-users-failed')).not.toBeNull()
    expect(slot('gaps-fix-loading')).toBeNull()
  })

  it('kişi listesi okunamazsa (hata yanıtı) tehlike bandı + "Yeniden dene"; yeniden deneme listeyi okuyup seçiciyi açar', async () => {
    api.admin.getUsers.mockResolvedValueOnce({ success: false, error: 'boom' })
    render(<GapsFix fix={{ kind: 'contact', team: TEAM }} onClose={() => {}} onChanged={() => {}} />)
    await waitFor(() => expect(slot('gaps-fix-users-failed')).not.toBeNull())
    expect(slot('stub-contact')).toBeNull()
    const band = slot('gaps-fix-users-failed').querySelector('[data-slot="alert"]')
    expect(band).toHaveAttribute('data-tone', 'danger')
    expect(band.textContent).toMatch(/Eklenebilecek kişilerin listesi okunamadı/)
    expect(band.textContent).toMatch(/hiç kullanıcı olmadığı anlamına gelmez/)
    const retry = screen.getByRole('button', { name: 'Yeniden dene' })
    expect(retry.className).toMatch(/pointer-coarse:min-h-10/)
    api.admin.getUsers.mockResolvedValueOnce({ success: true, data: [{ id: 1, active: true }] })
    fireEvent.click(retry)
    await waitFor(() => expect(slot('stub-contact')).not.toBeNull())
    expect(slot('stub-contact').getAttribute('data-users')).toBe('1')
    expect(slot('gaps-fix-users-failed')).toBeNull()
    expect(api.admin.getUsers).toHaveBeenCalledTimes(2)
  })

  it('beklenmeyen gövde (dizi değil) ve ağ hatası da okuma hatasıdır', async () => {
    api.admin.getUsers.mockResolvedValueOnce({ success: true, data: null })
    const r = render(<GapsFix fix={{ kind: 'contact', team: TEAM }} onClose={() => {}} onChanged={() => {}} />)
    await waitFor(() => expect(slot('gaps-fix-users-failed')).not.toBeNull())
    r.unmount()
    api.admin.getUsers.mockRejectedValueOnce(new Error('ağ'))
    render(<GapsFix fix={{ kind: 'contact', team: TEAM }} onClose={() => {}} onChanged={() => {}} />)
    await waitFor(() => expect(slot('gaps-fix-users-failed')).not.toBeNull())
    expect(slot('stub-contact')).toBeNull()
  })

  it('varsayılan grup ve nöbet: kendi pencereleri; "grup oluştur" bildirim gruplarına götürür', async () => {
    const onClose = vi.fn()
    const onNavigate = vi.fn()
    const r = render(<GapsFix fix={{ kind: 'group', team: TEAM }} onClose={onClose} onChanged={() => {}} onNavigate={onNavigate} />)
    await waitFor(() => expect(slot('stub-group')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'grup oluştur' }))
    // Gezinme çağıranın (eksikler penceresini de kapatır, aynı sekmede yeniden bağlar)
    expect(onNavigate).toHaveBeenCalledWith({ tab: 'admin', params: { g_tab: 'notifyGroups', g_team: '4' } })
    expect(nav).not.toHaveBeenCalled()
    r.unmount()
    render(<GapsFix fix={{ kind: 'oncall', team: TEAM }} onClose={() => {}} onChanged={() => {}} />)
    await waitFor(() => expect(slot('stub-oncall')).not.toBeNull())
    expect(api.admin.getUsers).not.toHaveBeenCalled()
  })
})
