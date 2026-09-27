import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #4, #5, #9) — Sentetik İzleme yarışları:
 *  #4  checkNow await sonrası bayat `selected` → A'nın sonucu, o arada açılan B'nin penceresini A ile değiştiriyordu.
 *  #5  runSmokeCheck korumasızdı → A'nın geç doğrulama sonucu B'nin formuna düşüyor, bandın "Kapat"ı B'nin kaydedilmemiş
 *      taslağını (skipDraft) atıyordu; ağ hatasında bant "koşuyor"da takılı kalıyordu.
 *  #9  DiagTab.run try/catch'siz → ağ hatasında durum { loading } kalıyor, "Teşhisi Çalıştır" kalıcı kilitleniyordu.
 * Denetimli promise'lerle belirlenimci.
 */
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />,
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../components/MonitorNotes.jsx', () => ({ default: () => <div data-testid="monitor-notes" /> }))
vi.mock('../components/admin/AlertHistory.jsx', () => ({ default: () => <div data-testid="alert-history" /> }))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

const mon = (id, name, over = {}) => ({
  id, name, status: 'PASS', team_id: 5, team_name: 'SY-A', group_name: 'SY-A grubu', tags: 'prod',
  script: 'export default function(){}', script_version: '1.0.2', checked_at: '2026-08-13T10:00:00', ...over,
})
const A = mon(3, 'alpha-run')
const B = mon(4, 'bravo-run')
const deferred = () => { let resolve, reject; const p = new Promise((res, rej) => { resolve = res; reject = rej }); return { p, resolve, reject } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const dialog = () => screen.queryByRole('dialog')
const dialogTitle = () => {
  const d = dialog()
  const id = d?.getAttribute('aria-labelledby')
  return (id && document.getElementById(id)?.textContent) || d?.textContent || ''
}

async function renderPage() {
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [A, B] },
  })
  const utils = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
  await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalled())
  await screen.findByText('alpha-run')
  return utils
}
const cardBtn = (container, name, action) =>
  within(container).getByRole('button', { name: new RegExp(`^${name} — (${action})$`, 'i') })

beforeEach(() => resetScriptedMocks(api))

describe('ScriptedMonitorPage — yarışlar', () => {
  it('#4 checkNow: A\'nın geç sonucu, o arada açılan B\'nin penceresini A ile DEĞİŞTİRMEZ', async () => {
    const run = deferred()
    api.monitoring.triggerScriptedCheck.mockReturnValue(run.p)
    const { container } = await renderPage()
    fireEvent.click(cardBtn(container, 'alpha-run', 'detayları aç|open details'))
    await waitFor(() => expect(dialogTitle()).toMatch('alpha-run'))

    fireEvent.click(within(dialog()).getAllByRole('button', { name: /^(run now|şimdi çalıştır)$/i })[0])
    await waitFor(() => expect(api.monitoring.triggerScriptedCheck).toHaveBeenCalledWith(3))
    fireEvent.click(within(dialog()).getAllByRole('button', { name: /^(Close|Kapat)$/ })[0])
    await waitFor(() => expect(dialog()).toBeNull())
    fireEvent.click(cardBtn(container, 'bravo-run', 'detayları aç|open details'))
    await waitFor(() => expect(dialogTitle()).toMatch('bravo-run'))

    await act(async () => { run.resolve({ success: true, data: { ...A, status: 'FAIL', duration_ms: 900 } }) })
    await flush()
    expect(dialogTitle()).toMatch('bravo-run')
    expect(dialogTitle()).not.toMatch('alpha-run')
  })

  it('#5 runSmokeCheck: A\'nın geç doğrulama sonucu, o arada açılan B\'nin formuna DÜŞMEZ (Kapat bandı B\'de görünmez)', async () => {
    const smoke = deferred()
    const { container } = await renderPage()
    api.monitoring.updateScriptedMonitor.mockResolvedValue({ success: true, data: { id: A.id, script_version: '1.0.3' } })
    api.monitoring.triggerScriptedCheck.mockReturnValue(smoke.p)

    fireEvent.click(cardBtn(container, 'alpha-run', 'düzenle|edit'))
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.triggerScriptedCheck).toHaveBeenCalledWith(A.id))
    await waitFor(() => expect(document.querySelector('[data-slot="smoke-msg"]')).not.toBeNull())

    // Kullanıcı A'nın formunu kapatır (İptal) ve B'yi düzenlemeye açar.
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(cancel|iptal)$/i }))
    await waitFor(() => expect(dialog()).toBeNull())
    fireEvent.click(cardBtn(container, 'bravo-run', 'düzenle|edit'))
    await waitFor(() => expect(within(dialog()).getByRole('textbox', { name: /^(Ad|Name)\b/ }).value).toBe('bravo-run'))
    expect(document.querySelector('[data-slot="smoke-msg"]')).toBeNull()

    await act(async () => { smoke.resolve({ success: true, data: { queued: true } }) })
    await flush()
    expect(document.querySelector('[data-slot="smoke-msg"]')).toBeNull()
    expect(within(dialog()).getByRole('textbox', { name: /^(Ad|Name)\b/ }).value).toBe('bravo-run')
  })

  it('#5 runSmokeCheck: ağ hatası (reject) bandı "koşuyor"da BIRAKMAZ', async () => {
    const smoke = deferred()
    const { container } = await renderPage()
    api.monitoring.updateScriptedMonitor.mockResolvedValue({ success: true, data: { id: A.id, script_version: '1.0.3' } })
    api.monitoring.triggerScriptedCheck.mockReturnValue(smoke.p)

    fireEvent.click(cardBtn(container, 'alpha-run', 'düzenle|edit'))
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(document.querySelector('[data-slot="smoke-msg"]')).not.toBeNull())

    await act(async () => { smoke.reject(new Error('Failed to fetch')) })
    await flush()
    expect(document.querySelector('[data-slot="smoke-msg"]')).toBeNull()
    expect(dialog()).not.toBeNull()   // form açık kalır (sürüm zaten kalıcı)
  })

  it('#9 DiagTab: ağ hatası (reject) "Teşhisi Çalıştır"ı kalıcı KİLİTLEMEZ; hata bandı görünür', async () => {
    api.monitoring.diagnoseScripted = vi.fn().mockRejectedValue(new Error('Failed to fetch'))
    const { container } = await renderPage()
    fireEvent.click(cardBtn(container, 'alpha-run', 'detayları aç|open details'))
    fireEvent.mouseDown(await screen.findByRole('tab', { name: /connection diagnostics|bağlantı teşhisi/i }), { button: 0 })
    const runBtn = await screen.findByRole('button', { name: /run diagnostics|teşhisi çalıştır/i })
    fireEvent.click(runBtn)
    await waitFor(() => expect(api.monitoring.diagnoseScripted).toHaveBeenCalledWith(3, undefined))
    await flush()
    expect(await screen.findByText('Failed to fetch')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /run diagnostics|teşhisi çalıştır/i })).not.toBeDisabled()
  })
})
