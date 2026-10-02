import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Kayıtlı görünümler (2026-10-02, öneri 23) — genel shadcn "Görünümler" menüsü: kaydet (ad istemi) → savedViews PUT;
 * uygula → `sm:apply-view` (sekme + o anki sayfa-durumu parametreleri; geçici durumlar — sayfa, açık ayrıntı — yok);
 * şu anki görünüm ✓; yeniden adlandır / sil (onaylı). Tercihler yüklenmeden hiç çizilmez (araç çubuğu bugünkü gibi).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const server = { doc: {} }
vi.mock('../api/client', () => ({
  api: withApiFallback({ me: { getPreferences: vi.fn(), savePreferences: vi.fn() } }),
}))

import { api } from '../api/client'
import { useUserPrefsController, UserPrefsContext } from '../hooks/useUserPrefs.js'
import SavedViewsMenu from '../components/ui/SavedViewsMenu.jsx'
import { VIEW_SPECS } from '../hooks/userPrefsModel.js'

function PrefsShell({ children }) {
  const ctl = useUserPrefsController('ali', { debounceMs: 20 })
  return <UserPrefsContext.Provider value={ctl}>{children}</UserPrefsContext.Provider>
}

const trigger = () => screen.findByRole('button', { name: /^(Views|Görünümler)/ })
const menuItem = (rx) => screen.getByRole('menuitem', { name: rx })

let applied
const onApply = (e) => applied.push(e.detail)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  server.doc = {}
  applied = []
  window.addEventListener('sm:apply-view', onApply)
  window.history.replaceState({}, '', '/?tab=http&team=3&q=api&page=2&monitor=9&mtab=history')
  api.me.getPreferences.mockImplementation(() => Promise.resolve({ success: true, prefs: server.doc }))
  api.me.savePreferences.mockImplementation((patch) => {
    server.doc = { ...server.doc, ...patch }
    return Promise.resolve({ success: true, prefs: server.doc })
  })
})
afterEach(() => {
  window.removeEventListener('sm:apply-view', onApply)
  window.history.replaceState({}, '', '/')
})

describe('SavedViewsMenu', () => {
  it('sağlayıcı yoksa (tercihler hazır değil) hiçbir şey çizmez', () => {
    const { container } = render(<SavedViewsMenu listKey="http" {...VIEW_SPECS.monitor} />)
    expect(container.querySelector('[data-slot="saved-views-trigger"]')).toBeNull()
  })

  it('kaydet: ad istemi → o anki süzgeçler (sayfa / açık ayrıntı HARİÇ) savedViews.<liste> olarak gider', async () => {
    render(<PrefsShell><SavedViewsMenu listKey="http" tab="http" {...VIEW_SPECS.monitor} /></PrefsShell>)
    const btn = await trigger()
    expect(btn).toHaveAccessibleName(/^(Views|Görünümler)$/)
    pressMenuTrigger(btn)
    expect(menuItem(/No saved views yet|Henüz kayıtlı görünüm yok/)).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(menuItem(/Save this view|Bu görünümü kaydet/))
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(dlg.querySelector('input'), { target: { value: '  Ekibimin API izlemeleri ' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0]).toEqual({
      savedViews: { http: [{ name: 'Ekibimin API izlemeleri', params: { team: '3', q: 'api' } }] },
    })
    expect(await trigger()).toHaveAccessibleName(/(Views|Görünümler) \(1 (saved|kayıtlı)\)/)
  })

  it('şu anki görünüm ✓ ile işaretli; başka görünüm seçilince sm:apply-view (sekme + parametreler) yayılır', async () => {
    server.doc = { savedViews: { http: [
      { name: 'Ekibim', params: { team: '3', q: 'api' } },
      { name: 'Arızalar', params: { stat: 'down' } },
    ] } }
    render(<PrefsShell><SavedViewsMenu listKey="http" tab="http" {...VIEW_SPECS.monitor} /></PrefsShell>)
    pressMenuTrigger(await trigger())
    const mine = menuItem(/Ekibim/)
    expect(mine).toHaveAttribute('data-active', 'true')
    expect(menuItem(/Arızalar/)).not.toHaveAttribute('data-active')
    fireEvent.click(menuItem(/Arızalar/))
    expect(applied).toEqual([{ tab: 'http', params: { stat: 'down' } }])
    expect(api.me.savePreferences).not.toHaveBeenCalled()
  })

  it('sil: alt menü → onay → görünüm düşer (son görünüm silinince anahtar null)', async () => {
    server.doc = { savedViews: { http: [{ name: 'Ekibim', params: { team: '3' } }] } }
    render(<PrefsShell><SavedViewsMenu listKey="http" tab="http" {...VIEW_SPECS.monitor} /></PrefsShell>)
    pressMenuTrigger(await trigger())
    const sub = menuItem(/^(Delete|Sil)$/)
    fireEvent.keyDown(sub, { key: 'ArrowRight' })
    const target = await waitFor(() => {
      const items = screen.getAllByRole('menuitem', { name: /Ekibim/ })
      expect(items.length).toBeGreaterThan(1)
      return items[items.length - 1]
    })
    fireEvent.click(target)
    const dlg = await screen.findByRole('dialog')
    expect(dlg.textContent).toMatch(/Ekibim/)
    fireEvent.click(screen.getByRole('button', { name: /^(Delete|Sil)$/ }))
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0]).toEqual({ savedViews: null })
  })

  it('yeniden adlandır: aynı adlı başka görünüm varsa hata, yoksa ad değişir', async () => {
    server.doc = { savedViews: { http: [{ name: 'A', params: {} }, { name: 'B', params: { q: 'x' } }] } }
    render(<PrefsShell><SavedViewsMenu listKey="http" tab="http" {...VIEW_SPECS.monitor} /></PrefsShell>)
    pressMenuTrigger(await trigger())
    fireEvent.keyDown(menuItem(/^(Rename|Yeniden adlandır)$/), { key: 'ArrowRight' })
    const items = await waitFor(() => {
      const list = screen.getAllByRole('menuitem', { name: /^B$/ })
      expect(list.length).toBeGreaterThan(1)
      return list
    })
    fireEvent.click(items[items.length - 1])
    const dlg = await screen.findByRole('dialog')
    const input = dlg.querySelector('input')
    expect(input.value).toBe('B')
    fireEvent.change(input, { target: { value: 'Sorgu x' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Rename|Yeniden adlandır)$/ }))
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0].savedViews.http.map((v) => v.name)).toEqual(['A', 'Sorgu x'])
  })
})
