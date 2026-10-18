import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useT } from '../i18n/index.jsx'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'
import { useSecretReveal, SecretRevealButton, SecretRevealPanel, REVEAL_TTL_MS }
  from '../components/scripted/ScriptedSecretReveal.jsx'

// Sentetik izlemede gizli env değerini GÖSTER (2026-10-19, kullanıcı isteği: "admin kullanıcısı gizli bilgileri review
// edebilmeli"). Düğme yalnız sunucu satırının `can_reveal`'ı + KAYITLI gizli değişkende; değer 30 sn sonra, sekme /
// pencere değişince ve form kapanınca gizlenir; forma, taslağa, kayıt yüküne hiç girmez; hatalar satır içinde.

vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => (
    <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../components/MonitorNotes.jsx', () => ({ default: () => <div data-testid="monitor-notes" /> }))
vi.mock('../components/admin/AlertHistory.jsx', () => ({ default: () => <div data-testid="alert-history" /> }))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

const SECRET = 'Pz9-Gizli-Deger-7781'

beforeEach(() => {
  resetScriptedMocks(api)
  api.monitoring.revealScriptedSecret.mockResolvedValue({ success: true, data: { name: 'TOKEN', value: SECRET } })
})
afterEach(() => { vi.useRealTimers() })

/** Bileşen parçalarını sayfa dışında koşturan küçük koşum (sahte saat ile). */
function Harness({ target }) {
  const t = useT()
  const r = useSecretReveal(target)
  if (!target) return <div data-testid="empty" />
  return (
    <div>
      <SecretRevealButton t={t} name={target.name} state={r.state} onReveal={r.reveal} onHide={r.hide} />
      <SecretRevealPanel t={t} name={target.name} state={r.state} onHide={r.hide} />
    </div>
  )
}

const showBtn = (name = 'TOKEN') => screen.getByRole('button', { name: new RegExp(`^(Gizli değeri göster|Show secret value): ${name}$`) })
const valueBox = () => document.querySelector('[data-slot="env-secret-value"]')

describe('useSecretReveal + düğme / panel', () => {
  it('göster → salt okunur kutuda değer; 30 sn sonra kendiliğinden gizlenir', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    render(<Harness target={{ monitorId: 41, name: 'TOKEN' }} />)
    fireEvent.click(showBtn())
    await waitFor(() => expect(valueBox()).not.toBeNull())
    expect(api.monitoring.revealScriptedSecret).toHaveBeenCalledWith(41, 'TOKEN')
    expect(valueBox().value).toBe(SECRET)
    expect(valueBox().readOnly).toBe(true)
    expect(valueBox().getAttribute('autocomplete')).toBe('off')
    expect(screen.getByRole('button', { name: /^(Gizli değeri gizle|Hide secret value): TOKEN$/ })).toHaveAttribute('aria-pressed', 'true')

    await act(async () => { await vi.advanceTimersByTimeAsync(REVEAL_TTL_MS - 1000) })
    expect(valueBox()).not.toBeNull()
    await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
    expect(valueBox()).toBeNull()
    expect(screen.queryByText(SECRET)).toBeNull()
    expect(showBtn()).toHaveAttribute('aria-pressed', 'false')
  })

  it('sekme gizlenince ve pencere odağı kaybedince gizlenir; Gizle düğmesi de gizler', async () => {
    render(<Harness target={{ monitorId: 41, name: 'TOKEN' }} />)
    fireEvent.click(showBtn())
    await waitFor(() => expect(valueBox()).not.toBeNull())
    const vis = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState')
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    try {
      act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    } finally {
      delete document.visibilityState
      if (vis) Object.defineProperty(Document.prototype, 'visibilityState', vis)
    }
    expect(valueBox()).toBeNull()

    fireEvent.click(showBtn())
    await waitFor(() => expect(valueBox()).not.toBeNull())
    act(() => { window.dispatchEvent(new Event('blur')) })
    expect(valueBox()).toBeNull()

    fireEvent.click(showBtn())
    await waitFor(() => expect(valueBox()).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /^(Gizle|Hide)$/ }))
    expect(valueBox()).toBeNull()
  })

  it('gizlendikten / söküldükten sonra gelen geç yanıt hiçbir yere yazılmaz', async () => {
    let resolve
    api.monitoring.revealScriptedSecret.mockImplementation(() => new Promise((r) => { resolve = r }))
    const { unmount } = render(<Harness target={{ monitorId: 41, name: 'TOKEN' }} />)
    fireEvent.click(showBtn())
    act(() => { window.dispatchEvent(new Event('blur')) })   // okunurken pencere değişti
    await act(async () => { resolve({ success: true, data: { name: 'TOKEN', value: SECRET } }) })
    expect(valueBox()).toBeNull()

    fireEvent.click(showBtn())
    unmount()                                                 // form kapandı
    await act(async () => { resolve({ success: true, data: { name: 'TOKEN', value: SECRET } }) })
    expect(document.body.textContent).not.toContain(SECRET)
  })

  it('satır başka değişkene dönünce (ad değişti / satır silindi) gösterilen değer düşer', async () => {
    const { rerender } = render(<Harness target={{ monitorId: 41, name: 'TOKEN' }} />)
    fireEvent.click(showBtn())
    await waitFor(() => expect(valueBox()).not.toBeNull())
    rerender(<Harness target={{ monitorId: 41, name: 'OTHER' }} />)
    expect(valueBox()).toBeNull()
    expect(showBtn('OTHER')).toBeInTheDocument()
  })

  it('sunucu hatası (403 / 404 / 409 / 429) iletisi satır içinde; ağ hatası da; Kapat düşürür', async () => {
    const msg = 'Çok sık gizli değer gösterimi — kullanıcı başına dakikada en çok 10, saatte en çok 30 kez. 42 sn sonra yeniden deneyin.'
    api.monitoring.revealScriptedSecret.mockResolvedValueOnce({ success: false, code: 'RATE_LIMITED', error: msg })
    render(<Harness target={{ monitorId: 41, name: 'TOKEN' }} />)
    fireEvent.click(showBtn())
    const panel = await waitFor(() => {
      const p = document.querySelector('[data-slot="env-secret-reveal"][data-state="error"]')
      expect(p).not.toBeNull()
      return p
    })
    expect(within(panel).getByText(msg)).toBeInTheDocument()
    fireEvent.click(within(panel).getByRole('button', { name: /^(Kapat|Close)$/ }))
    expect(document.querySelector('[data-slot="env-secret-reveal"]')).toBeNull()

    api.monitoring.revealScriptedSecret.mockRejectedValueOnce(Object.assign(new Error('Sunucuya ulaşılamadı; bağlantınızı denetleyip yeniden deneyin.'), {}))
    fireEvent.click(showBtn())
    expect(await screen.findByText(/Sunucuya ulaşılamadı/)).toBeInTheDocument()

    api.monitoring.revealScriptedSecret.mockResolvedValueOnce({ success: false })
    fireEvent.click(showBtn())
    expect(await screen.findByText(/(Gizli değer gösterilemedi|The secret value couldn’t be shown)/)).toBeInTheDocument()
  })

  it('Kopyala değeri panoya yazar', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    const orig = navigator.clipboard
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    try {
      render(<Harness target={{ monitorId: 41, name: 'TOKEN' }} />)
      fireEvent.click(showBtn())
      await waitFor(() => expect(valueBox()).not.toBeNull())
      fireEvent.click(screen.getByRole('button', { name: /^(Kopyala|Copy)$/ }))
      await waitFor(() => expect(writeText).toHaveBeenCalledWith(SECRET))
    } finally {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: orig })
    }
  })

  it('hedef yoksa (null) hiçbir şey çizilmez ve istek atılmaz', () => {
    render(<Harness target={null} />)
    expect(screen.getByTestId('empty')).toBeInTheDocument()
    expect(api.monitoring.revealScriptedSecret).not.toHaveBeenCalled()
  })
})

// ── Sayfa: düğme görünürlüğü + değer forma / taslağa / kayda girmez ─────────────────────────────────────

const MON = {
  id: 41, name: 'Ödeme akışı', status: 'PASS', team_id: 5, team_name: 'SY-A', group_name: 'Senaryolar', tags: 'prod',
  checked_at: '2026-10-19T10:00:00', script: 'export default function(){}', can_check: true, can_edit: true, can_delete: true,
  can_reveal: true,
  env: [
    { name: 'BASE_URL', secret: false, value: 'https://x.example.com' },
    { name: 'TOKEN', secret: true, value_set: true },
    { name: 'EMPTY_SECRET', secret: true, value_set: false },
  ],
}

async function renderPage(monitor = MON) {
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [monitor] },
  })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" globalAdmin />)
  await screen.findByText(monitor.name)
}
const openEdit = (name = MON.name) =>
  fireEvent.click(within(document.querySelector('.upt-grid')).getByRole('button', { name: new RegExp(`^${name} — (Düzenle|Edit)$`, 'i') }))
const revealButtons = () => [...document.querySelectorAll('[data-slot="env-secret-reveal-toggle"]')]

describe('ScriptedMonitorPage — gizli değeri göster', () => {
  it('can_reveal: yalnız değeri KAYITLI gizli satırda düğme; düz ve boş gizli satırda yok; ipucu görünür', async () => {
    await renderPage()
    openEdit()
    const dlg = screen.getByRole('dialog')
    expect(revealButtons()).toHaveLength(1)
    expect(within(dlg).getByRole('button', { name: /^(Gizli değeri göster|Show secret value): TOKEN$/ })).toBeInTheDocument()
    expect(within(dlg).queryByRole('button', { name: /: BASE_URL$/ })).toBeNull()
    expect(within(dlg).queryByRole('button', { name: /: EMPTY_SECRET$/ })).toBeNull()
    expect(dlg.querySelector('[data-slot="env-reveal-hint"]')).not.toBeNull()
  })

  it('can_reveal false / yok → düğme ve ipucu YOK (salt okuyan, üye, paylaşım)', async () => {
    await renderPage({ ...MON, can_reveal: false })
    openEdit()
    expect(revealButtons()).toHaveLength(0)
    expect(document.querySelector('[data-slot="env-reveal-hint"]')).toBeNull()
  })

  it('Kopyala (yeni kayıt modu) gizli değer göstermez', async () => {
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(revealButtons()).toHaveLength(0)
  })

  it('gösterilen değer forma, taslağa ve kayıt yüküne GİRMEZ; form kapanınca gider, yeniden açılışta gizli', async () => {
    api.monitoring.updateScriptedMonitor.mockResolvedValue({ success: true, data: { ...MON } })
    await renderPage()
    openEdit()
    fireEvent.click(screen.getByRole('button', { name: /^(Gizli değeri göster|Show secret value): TOKEN$/ }))
    await waitFor(() => expect(valueBox()?.value).toBe(SECRET))
    // satırın kendi değer kutusu boş kalır (göstermek yeni değer yazmaz)
    const tokenRow = [...document.querySelectorAll('[data-slot="env-row"]')].find((r) => r.querySelector('input')?.value === 'TOKEN')
    expect(tokenRow.querySelectorAll('input')[1].value).toBe('')

    // taslak: kirli form kapanışta yazılır — değer içinde olmamalı
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){ /* degisti */ }' } })
    fireEvent.click(screen.getByRole('button', { name: /^(save|kaydet)$/i }))
    await waitFor(() => expect(api.monitoring.updateScriptedMonitor).toHaveBeenCalled())
    const payload = JSON.stringify(api.monitoring.updateScriptedMonitor.mock.calls[0][1])
    expect(payload).not.toContain(SECRET)
    expect(api.monitoring.updateScriptedMonitor.mock.calls[0][1].env).toContainEqual({ name: 'TOKEN', secret: true })
    for (const call of api.monitoring.saveScriptedDraft.mock.calls) expect(JSON.stringify(call)).not.toContain(SECRET)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.body.textContent).not.toContain(SECRET)

    openEdit()
    expect(valueBox()).toBeNull()
    expect(document.body.innerHTML).not.toContain(SECRET)
  })

  it('kapanışta yazılan taslak da değeri taşımaz', async () => {
    await renderPage()
    openEdit()
    fireEvent.click(screen.getByRole('button', { name: /^(Gizli değeri göster|Show secret value): TOKEN$/ }))
    await waitFor(() => expect(valueBox()?.value).toBe(SECRET))
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){ /* taslak */ }' } })
    api.monitoring.saveScriptedDraft.mockClear()
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /^(İptal|Cancel)$/ }))
    await waitFor(() => expect(api.monitoring.saveScriptedDraft).toHaveBeenCalled())
    for (const call of api.monitoring.saveScriptedDraft.mock.calls) expect(JSON.stringify(call)).not.toContain(SECRET)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.body.textContent).not.toContain(SECRET)
    for (let i = 0; i < localStorage.length; i++) expect(localStorage.getItem(localStorage.key(i)) || '').not.toContain(SECRET)
  })
})
