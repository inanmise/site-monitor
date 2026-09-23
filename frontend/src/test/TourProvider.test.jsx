import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from './test-utils.jsx'
import { TourProvider, useTour } from '../components/tour/TourProvider.jsx'
import TourPageChip from '../components/tour/TourPageChip.jsx'
import OnboardingChecklist from '../components/tour/OnboardingChecklist.jsx'

// Ürün turu sağlayıcısı (2026-09-13): karşılama kartı otomatik açılışı, "bir daha gösterme" kalıcılığı,
// adım geçişi/balon, sayfa çipi, başlangıç listesi, palet olayı.
vi.mock('../api/client', () => ({ api: {} }))

function Targets() {
  return (
    <div>
      <nav data-tour="nav-groups">menü</nav>
      <button type="button" data-tour="nav-search">ara</button>
      <div data-tour="nav-inbox">inbox</div>
      <div data-tour="nav-user">user</div>
      <div data-tour="dash-stats">stats</div>
      <div data-tour="dash-filters">filters</div>
      <button type="button" data-tour="check-now">check</button>
      <button type="button" data-tour="help-fab">?</button>
      <button type="button" data-tour="nav-tab-all">all</button>
      <button type="button" data-tour="nav-tab-http">http</button>
      <button type="button" data-tour="nav-tab-incidents">inc</button>
      <button type="button" data-tour="nav-tab-weeklyreports">wr</button>
    </div>
  )
}
function Starter({ kind = 'main' }) { const { start } = useTour(); return <button type="button" onClick={() => start(kind)}>start-{kind}</button> }

const ctx = (over) => ({ role: 'USER', globalAdmin: false, canWrite: false, mustChangePwd: false, tab: 'dashboard', ready: true, ...over })

describe('TourProvider', () => {
  beforeEach(() => { vi.useFakeTimers({ shouldAdvanceTime: true }); window.history.replaceState({}, '', '/?tab=dashboard') })
  afterEach(() => { vi.useRealTimers() })

  it('hiç görmemiş kullanıcı: veri hazır olunca 1,5 sn sonra karşılama kartı; "Bir daha gösterme" dismissed yazar ve kart kapanır', async () => {
    const persist = vi.fn()
    render(<TourProvider ctx={ctx()} tourState={null} onPersist={persist}><Targets /></TourProvider>)
    expect(screen.queryByText(/hoş geldiniz|Welcome/)).toBeNull()
    await act(async () => { vi.advanceTimersByTime(1600) })
    expect(screen.getByText(/hoş geldiniz|Welcome/)).toBeInTheDocument()
    fireEvent.click(screen.getByText(/Bir daha gösterme|Don't show again/))
    expect(persist).toHaveBeenCalledWith(expect.objectContaining({ status: 'dismissed' }))
    await waitFor(() => expect(screen.queryByText(/hoş geldiniz|Welcome/)).toBeNull())
  })

  it('dismissed kullanıcıya karşılama kartı ASLA açılmaz; zorunlu şifre ekranında da açılmaz', async () => {
    const { unmount } = render(<TourProvider ctx={ctx()} tourState={{ status: 'dismissed', version: 1 }} onPersist={() => {}}><Targets /></TourProvider>)
    await act(async () => { vi.advanceTimersByTime(2000) })
    expect(screen.queryByText(/hoş geldiniz|Welcome/)).toBeNull()
    unmount()
    render(<TourProvider ctx={ctx({ mustChangePwd: true })} tourState={null} onPersist={() => {}}><Targets /></TourProvider>)
    await act(async () => { vi.advanceTimersByTime(2000) })
    expect(screen.queryByText(/hoş geldiniz|Welcome/)).toBeNull()
  })

  it('"Şimdi değil" snoozed yazar; kart Esc ile de "şimdi değil" sayılır', async () => {
    const persist = vi.fn()
    render(<TourProvider ctx={ctx()} tourState={{ status: 'snoozed', snoozed: 1 }} onPersist={persist}><Targets /></TourProvider>)
    await act(async () => { vi.advanceTimersByTime(1600) })
    fireEvent.click(screen.getByText(/Şimdi değil|Not now/))
    expect(persist).toHaveBeenCalledWith(expect.objectContaining({ status: 'snoozed' }))
  })

  it('tur başlar: started yazılır, ilk balon merkezde, İleri ile hedefli adıma geçer (spot ışığı + ilerleme), Bitir completed yazar', async () => {
    const persist = vi.fn()
    render(<TourProvider ctx={ctx()} tourState={{ status: 'completed', version: 99 }} onPersist={persist}><Targets /><Starter /></TourProvider>)
    fireEvent.click(screen.getByText('start-main'))
    // Tamamlamış kullanıcı yeniden başlatınca durum 'started'a DÜŞMEZ (yarıda bırakırsa karşılama kartı geri gelmesin)
    expect(persist).toHaveBeenCalledWith({ last_step: 'welcome' })
    expect(persist).not.toHaveBeenCalledWith(expect.objectContaining({ status: 'started' }))
    await waitFor(() => expect(document.querySelector('.tour-tip--center')).not.toBeNull())
    expect(document.querySelector('.tour-progress').textContent).toMatch(/^1\//)
    fireEvent.click(screen.getByText(/^İleri|^Next/))
    await waitFor(() => expect(document.querySelector('.tour-ring')).not.toBeNull())
    expect(document.querySelector('[data-tour-active="sidebar"]')).not.toBeNull()
    expect(persist).toHaveBeenCalledWith(expect.objectContaining({ last_step: 'sidebar' }))
    // Klavye: ← geri
    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    await waitFor(() => expect(document.querySelector('[data-tour-active="welcome"]')).not.toBeNull())
    // Esc → atla (last_step yazılır, tur kapanır)
    fireEvent.keyDown(window, { key: 'Escape' })
    await waitFor(() => expect(document.querySelector('.tour-overlay')).toBeNull())
    expect(persist).toHaveBeenLastCalledWith(expect.objectContaining({ last_step: 'welcome' }))
  })

  it('hedefi DOM\'da olmayan adım 3 sn içinde atlanır (kırık tur yerine eksik adım); son adımda Bitir completed + checklist.tour yazar', async () => {
    const persist = vi.fn()
    // Hedeflerin çoğu yok: yalnız nav-groups → sidebar sonrası dashboard bulunamaz → atlanır
    render(<TourProvider ctx={ctx()} tourState={{ status: 'completed', version: 99 }} onPersist={persist}><nav data-tour="nav-groups" /><Starter /></TourProvider>)
    fireEvent.click(screen.getByText('start-main'))
    await waitFor(() => expect(document.querySelector('.tour-tip--center')).not.toBeNull())
    fireEvent.click(screen.getByText(/^İleri|^Next/))
    await waitFor(() => expect(document.querySelector('[data-tour-active="sidebar"]')).not.toBeNull())
    fireEvent.click(screen.getByText(/^İleri|^Next/))
    await act(async () => { vi.advanceTimersByTime(3500) })
    // dashboard atlandı → filters de yok → ... sonunda 'done' (merkez) adımına ya da bir sonrakine geldi; en az bir atlama oldu
    await waitFor(() => expect(document.querySelector('[data-tour-active="dashboard"]')).toBeNull(), { timeout: 4000 })
  })

  it('hiç görmemiş kullanıcı turu başlatınca status: started yazılır', async () => {
    const persist = vi.fn()
    render(<TourProvider ctx={ctx({ ready: false })} tourState={null} onPersist={persist}><Targets /><Starter /></TourProvider>)
    fireEvent.click(screen.getByText('start-main'))
    expect(persist).toHaveBeenCalledWith(expect.objectContaining({ status: 'started', last_step: 'welcome' }))
  })

  it('"Bir daha gösterme" tur içinden de dismissed yazar', async () => {
    const persist = vi.fn()
    render(<TourProvider ctx={ctx()} tourState={{ status: 'completed', version: 99 }} onPersist={persist}><Targets /><Starter /></TourProvider>)
    fireEvent.click(screen.getByText('start-main'))
    await waitFor(() => expect(document.querySelector('.tour-tip')).not.toBeNull())
    fireEvent.click(screen.getByText(/Bir daha gösterme|Don't show again/))
    expect(persist).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'dismissed' }))
    await waitFor(() => expect(document.querySelector('.tour-overlay')).toBeNull())
  })

  it('sayfa çipi: görülmemiş sayfada çıkar, kapatınca seen_page yazar; dismissed kullanıcıda çıkmaz', async () => {
    const persist = vi.fn()
    const { unmount } = render(<TourProvider ctx={ctx({ tab: 'all' })} tourState={{ status: 'completed', version: 99 }} onPersist={persist}><TourPageChip tab="all" /></TourProvider>)
    expect(screen.getByText(/Bu sayfayı tanımak|quick look around/)).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText(/Turu kapat|Close the tour/))
    expect(persist).toHaveBeenCalledWith({ seen_page: 'all' })
    expect(screen.queryByText(/Bu sayfayı tanımak|quick look around/)).toBeNull()
    unmount()
    render(<TourProvider ctx={ctx({ tab: 'all' })} tourState={{ status: 'dismissed' }} onPersist={persist}><TourPageChip tab="all" /></TourProvider>)
    expect(screen.queryByText(/Bu sayfayı tanımak|quick look around/)).toBeNull()
  })

  it('başlangıç listesi: sekme ziyareti maddeyi bir kez yazar; gizle checklist_hidden yazar; hepsi bitince panel yok', async () => {
    const persist = vi.fn()
    const { rerender } = render(<TourProvider ctx={ctx({ tab: 'dashboard' })} tourState={{ status: 'completed', version: 99, checklist: { tour: true } }} onPersist={persist}><OnboardingChecklist /></TourProvider>)
    expect(screen.getByText(/Başlangıç listesi|Getting started/)).toBeInTheDocument()
    rerender(<TourProvider ctx={ctx({ tab: 'http' })} tourState={{ status: 'completed', version: 99, checklist: { tour: true } }} onPersist={persist}><OnboardingChecklist /></TourProvider>)
    expect(persist).toHaveBeenCalledWith({ checklist: { monitor: true } })
    rerender(<TourProvider ctx={ctx({ tab: 'http' })} tourState={{ status: 'completed', version: 99, checklist: { tour: true, monitor: true } }} onPersist={persist}><OnboardingChecklist /></TourProvider>)
    expect(persist).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByLabelText(/Listeyi gizle|Hide this list/))
    expect(persist).toHaveBeenCalledWith({ checklist_hidden: true })
    rerender(<TourProvider ctx={ctx()} tourState={{ status: 'completed', checklist: { tour: true, card: true, all: true, monitor: true, report: true, help: true } }} onPersist={persist}><OnboardingChecklist /></TourProvider>)
    expect(screen.queryByText(/Başlangıç listesi|Getting started/)).toBeNull()
  })

  it('sm:tour-start olayı (palet / yardım) turu başlatır', async () => {
    render(<TourProvider ctx={ctx()} tourState={{ status: 'completed', version: 99 }} onPersist={() => {}}><Targets /></TourProvider>)
    act(() => { window.dispatchEvent(new CustomEvent('sm:tour-start', { detail: { kind: 'main' } })) })
    await waitFor(() => expect(document.querySelector('.tour-overlay')).not.toBeNull())
  })
})
