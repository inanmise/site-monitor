import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

// CodeEditor (prismjs/CSS) jsdom'da ağır → basit textarea (ScriptedMonitorPage.form.test ile aynı)
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => (
    <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

/**
 * GEÇ GELEN "TEST" SONUCU BAŞKA FORMA DÜŞMEZ (2026-10-09, doğrulanmış hata). Formdaki "Test" isteği (k6'da 180 sn'ye kadar)
 * sırasız kalmıştı: kullanıcı formu kapatıp başka bir izlemenin formunu açınca önceki formun sonucu yeni formun paneline
 * yazılıyor, "test ediliyor" kilidi de yeni formda kalıyordu. Artık her form açılışı / kapanışı (ve Sentetik'te script
 * kaynağı seçimi) `testSeq`'i artırır: bayat yanıt yok sayılır, düğme hemen serbest kalır.
 */
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))

describe('HttpMonitorPage — form Test sırası', () => {
  const A = {
    id: 1, name: 'Example', url: 'https://www.example.com/', method: 'GET', expected_status: '200-399',
    group_name: 'G', tags: 'prod', team_id: 5, team_name: 'SY-A', status: 'up', http_status: 200, response_ms: 12,
    interval_seconds: 600, timeout_ms: 7000, active: true, checked_at: '2026-06-24T00:00:00',
  }
  const LATE = { condition_met: true, http_status: 200, response_ms: 4321, expected_status: '200-399' }

  beforeEach(() => {
    resetScriptedMocks(api)
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [A] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { http: {} } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  })

  const openNewWithUrl = async () => {
    await waitFor(() => expect(document.querySelector('[data-monitor-open]')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /new monitor/i }))
    const form = await screen.findByRole('dialog', { name: /New HTTP Monitor/ })
    fireEvent.change(within(form).getByPlaceholderText('https://example.com'), { target: { value: 'https://late.example.com' } })
    return form
  }

  it('form kapatılıp başka izlemenin formu açılınca: geç sonuç yeni forma YAZILMAZ, Test düğmesi kilitli KALMAZ', async () => {
    const d = deferred()
    api.monitoring.testHttp.mockImplementation(() => d.p)
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    let form = await openNewWithUrl()
    fireEvent.click(within(form).getByRole('button', { name: /^test$/i }))
    await waitFor(() => expect(api.monitoring.testHttp).toHaveBeenCalledTimes(1))
    fireEvent.click(within(form).getByRole('button', { name: /^cancel$/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    // Başka bir izlemenin düzenleme formu
    fireEvent.click(screen.getByRole('button', { name: `${A.url} — Edit` }))
    form = await screen.findByRole('dialog', { name: /Edit HTTP Monitor/ })
    // Önceki formun testi hâlâ yolda — yeni formun Test düğmesi serbest
    expect(within(form).getByRole('button', { name: /^test$/i })).not.toBeDisabled()

    await act(async () => { d.resolve({ success: true, data: LATE }) })
    await flush()
    expect(within(form).queryByText(/4321ms/)).toBeNull()
    expect(within(form).getByRole('button', { name: /^test$/i })).not.toBeDisabled()
  })

  it('aynı form açıkken sonuç normal gösterilir ve kilit kalkar (mevcut davranış)', async () => {
    const d = deferred()
    api.monitoring.testHttp.mockImplementation(() => d.p)
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const form = await openNewWithUrl()
    const btn = within(form).getByRole('button', { name: /^test$/i })
    fireEvent.click(btn)
    await waitFor(() => expect(btn).toBeDisabled())
    await act(async () => { d.resolve({ success: true, data: LATE }) })
    expect(await within(form).findByText(/4321ms/)).toBeInTheDocument()
    expect(btn).not.toBeDisabled()
  })
})

describe('ScriptedMonitorPage — k6 Test sırası', () => {
  const sourceTrigger = () => document.querySelector('.sc-source-select button[role="combobox"]')
  const pickSource = (labelRe) => {
    if (sourceTrigger()?.getAttribute('aria-expanded') !== 'true') fireEvent.mouseDown(sourceTrigger())
    for (const b of document.querySelectorAll('[role="listbox"] button[aria-expanded]')) {
      if (b.getAttribute('aria-expanded') !== 'true') fireEvent.mouseDown(b)
    }
    fireEvent.mouseDown([...document.querySelectorAll('[role="listbox"] [role="option"]')].find((o) => labelRe.test(o.textContent)))
  }
  const LATE = { status: 'PASS', checks_passed: 3, checks_failed: 0, duration_ms: 98765, output_tail: 'late' }

  beforeEach(() => {
    resetScriptedMocks(api)
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  })

  const openNewWithScript = async () => {
    await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor/i }))
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){}' } })
    fireEvent.click(screen.getByRole('button', { name: /^test run$/i }))
    await waitFor(() => expect(api.monitoring.testScripted).toHaveBeenCalledTimes(1))
  }

  it('form kapatılıp yeniden açılınca: uçuşan k6 testinin sonucu yeni forma düşmez, düğme serbest', async () => {
    const d = deferred()
    api.monitoring.testScripted.mockImplementation(() => d.p)
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openNewWithScript()
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /^cancel$/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    fireEvent.click(screen.getByRole('button', { name: /new monitor/i }))
    await screen.findByRole('dialog')
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){ /* B */ }' } })
    expect(screen.getByRole('button', { name: /^test run$/i })).not.toBeDisabled()

    await act(async () => { d.resolve({ success: true, data: LATE }) })
    await flush()
    expect(document.querySelector('[role="dialog"] [data-slot="test-run"]')).toBeNull()
    expect(screen.queryByText(/98765|98\.8/)).toBeNull()
  })

  it('script kaynağı değişince: önceki script\'in geç test sonucu yeni seçimin paneline düşmez', async () => {
    const d = deferred()
    api.monitoring.testScripted.mockImplementation(() => d.p)
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openNewWithScript()
    pickSource(/smoke/i)
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('www.example.com'))

    await act(async () => { d.resolve({ success: true, data: LATE }) })
    await flush()
    expect(document.querySelector('[role="dialog"] [data-slot="test-run"]')).toBeNull()
    expect(screen.getByRole('button', { name: /^test run$/i })).not.toBeDisabled()
  })
})
