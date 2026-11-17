import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, waitFor } from './test-utils.jsx'

/**
 * Performans (2026-11-17): eksikler penceresinin App'teki ince kabuğu. Ağır denetleyici (`GapsPromptCore.jsx`) yalnız
 * gerektiğinde iner: kendiliğinden gösterme koşulları sağlanınca ya da menü / Panom isteğiyle. Modül inerken gelen istek
 * kaybolmaz; denetleyiciye devredilir. Bu sekmede zaten gösterildiyse ve istek yoksa modül hiç istenmez.
 */
const core = vi.hoisted(() => ({ mounts: [] }))
vi.mock('../components/gaps/GapsPromptCore.jsx', async () => {
  const React = await import('react')
  function FakeGapsCore(props) {
    React.useEffect(() => {
      core.mounts.push({ pending: props.pendingRef?.current ?? null, username: props.username })
      if (props.pendingRef) props.pendingRef.current = null
    }, [])  // eslint-disable-line react-hooks/exhaustive-deps
    return React.createElement('div', { 'data-slot': 'test-gaps-core' })
  }
  return { default: FakeGapsCore }
})
const perms = vi.hoisted(() => ({ loaded: true }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => false, loaded: perms.loaded, scope: null, perms: {} }),
}))

const PROMPTED = 'sm.gaps.prompted'
const coreEl = () => document.querySelector('[data-slot="test-gaps-core"]')

async function freshShell() {
  vi.resetModules()
  const mod = await import('../components/gaps/GapsPrompt.jsx')
  const bus = await import('../components/gaps/gapsBus.js')
  const reset = await import('../utils/sessionResetBus.js')
  return { GapsPrompt: mod.default, bus, reset }
}

beforeEach(() => {
  core.mounts = []
  perms.loaded = true
  try { sessionStorage.clear(); localStorage.clear() } catch { /* yok */ }
})

describe('eksikler kabuğu — denetleyici tembel', () => {
  it('bu sekmede zaten gösterildiyse ve istek yoksa denetleyici yüklenmez; menü isteği yükler ve "aç" olarak devreder', async () => {
    sessionStorage.setItem(PROMPTED, 'ayse')
    const { GapsPrompt, bus } = await freshShell()
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await new Promise((r) => setTimeout(r, 50))
    expect(coreEl()).toBeNull()
    act(() => bus.openGapsDialog())
    await waitFor(() => expect(coreEl()).not.toBeNull())
    expect(core.mounts).toEqual([{ pending: 'open', username: 'ayse' }])
  })

  it('kendiliğinden gösterme koşulları sağlanınca (yetkiler okundu) denetleyici istek olmadan yüklenir', async () => {
    perms.loaded = false
    const { GapsPrompt } = await freshShell()
    const r = render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await new Promise((res) => setTimeout(res, 50))
    expect(coreEl()).toBeNull()                       // yetkiler gelmeden yüklenmez
    perms.loaded = true
    r.rerender(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await waitFor(() => expect(coreEl()).not.toBeNull())
    expect(core.mounts).toEqual([{ pending: null, username: 'ayse' }])
  })

  it('Panom "Denetle" isteği "yenile" olarak devredilir; ardından gelen menü isteği "aç"a yükseltir', async () => {
    sessionStorage.setItem(PROMPTED, 'ayse')
    const { GapsPrompt, bus } = await freshShell()
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    act(() => { bus.requestGapsRefresh(); bus.openGapsDialog() })
    await waitFor(() => expect(coreEl()).not.toBeNull())
    expect(core.mounts[0].pending).toBe('open')
  })

  it('oturum sıfırlanınca "gösterildi" işareti denetleyici yüklenmemişken de silinir', async () => {
    sessionStorage.setItem(PROMPTED, 'ayse')
    const { GapsPrompt, reset } = await freshShell()
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    act(() => reset.emitSessionReset())
    expect(sessionStorage.getItem(PROMPTED)).toBeNull()
  })

  it('kapalıyken (enabled=false) istekler denetleyiciyi yüklemez', async () => {
    const { GapsPrompt, bus } = await freshShell()
    render(<GapsPrompt username="ayse" enabled={false} myTeamIds={[4]} manageTeamIds={[]} />)
    act(() => bus.openGapsDialog())
    await new Promise((r) => setTimeout(r, 50))
    expect(coreEl()).toBeNull()
  })
})
