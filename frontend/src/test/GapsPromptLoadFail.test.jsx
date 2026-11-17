import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, screen, waitFor } from './test-utils.jsx'

/**
 * Eksikler denetleyicisi (GapsPromptCore) tembel iner (performans 2026-11-17). İnceleme 2026-11-17: modül inemezse
 * "Eksiklerim" tıklaması sessizce kayboluyor ve bekleyen 'open' isteği sonraki bir Panom yenilemesinde pencereyi
 * KENDİLİĞİNDEN açıyordu. Şimdi: kişinin açma isteği bildirimle söylenir, bekleyen istek unutulur.
 */
const mode = vi.hoisted(() => ({ fail: true, err: new Error('ağ kesildi') }))
vi.mock('../utils/importWithDeadline.js', async () => {
  const React = await import('react')
  function FakeCore({ pendingRef }) {
    return React.createElement('div', { 'data-testid': 'gaps-core', 'data-pending': String(pendingRef.current) })
  }
  return {
    importWithDeadline: vi.fn(() => (mode.fail ? Promise.reject(mode.err) : Promise.resolve({ default: FakeCore }))),
  }
})
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => false, loaded: true, scope: null, perms: {} }),
}))

import { importWithDeadline } from '../utils/importWithDeadline.js'
import GapsPrompt from '../components/gaps/GapsPrompt.jsx'
import { openGapsDialog, requestGapsRefresh } from '../components/gaps/gapsBus.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

beforeEach(() => {
  vi.clearAllMocks()
  mode.fail = true
  try { localStorage.clear(); sessionStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})

describe('eksikler denetleyicisi inemezse', () => {
  // Sıra önemli: inen denetleyici modül düzeyinde önbelleğe alınır — başarısız yükleme denemesi ÖNCE koşar
  it('kendiliğinden yoldaki başarısız yükleme bildirim göstermez', async () => {
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await waitFor(() => expect(importWithDeadline).toHaveBeenCalledTimes(1))
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
    expect(screen.queryByText(/Eksiklerim penceresi açılamadı/)).toBeNull()
  })
  it('menü isteği bildirimle söylenir; bekleyen istek unutulur, sonraki Panom yenilemesi pencereyi açmaz', async () => {
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await waitFor(() => expect(importWithDeadline).toHaveBeenCalledTimes(1))       // kendiliğinden yol: sessiz deneme
    expect(screen.queryByText(/Eksiklerim penceresi açılamadı/)).toBeNull()

    act(() => openGapsDialog())
    expect(await screen.findByText(/Eksiklerim penceresi açılamadı/)).toBeInTheDocument()
    expect(importWithDeadline).toHaveBeenCalledTimes(2)

    // Ağ düzeldi; Panom "yenile" ister → denetleyici iner ama 'open' DEĞİL 'refresh' devralır (pencere kendiliğinden açılmaz)
    mode.fail = false
    act(() => requestGapsRefresh())
    const core = await screen.findByTestId('gaps-core')
    expect(core).toHaveAttribute('data-pending', 'refresh')
  })

})
