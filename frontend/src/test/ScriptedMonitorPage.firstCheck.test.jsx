import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

/**
 * Sentetik İzleme — KAYIT SONRASI İLK KOŞUM karta düşer (2026-09-28, kullanıcı bildirimi: "Test et → başarılı → Kaydet.
 * Sonrası bir tur daha koşum yapıyor, sonra kaydediliyor. Fakat açılan kartta veriler yansımıyor, boş bir görünüm oluyor").
 *
 * "Bir tur daha koşum" = kaydetme sonrası doğrulama koşumu (runSmokeCheck → POST /scripted/{id}/check). O koşum ZATEN
 * kalıcı bir kontroldü (geçmişe yazılır) ama sonucu yalnız forma yazılıyor, liste de koşumdan ÖNCE çekiliyordu → pencere
 * kapanınca kart bir sonraki yenilemeye kadar boş. Artık AYNI koşum kartın yolundan (checkNow → track + setMonitors)
 * geçer: ikinci bir koşum YOK, kart dönen göstergeyle bekler ve sonuçla dolar; bant kapatılsa da kart dolar.
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

const RESULT = { status: 'PASS', ok: true, duration_ms: 820, checks_passed: 3, checks_failed: 0, exit_code: 0, checked_at: '2026-09-28T09:00:00' }
const NEVER = { status: 'unknown', ok: null, duration_ms: null, checks_passed: null, checks_failed: null, exit_code: null, checked_at: null }
const mon = (id, name, over = {}) => ({
  id, name, team_id: 5, team_name: 'Takım A', group_name: 'Kurumsal Web', tags: 'prod', active: true,
  script: 'export default function(){}', script_version: '1.0.2', timeout_seconds: 60, use_proxy: 'AUTO',
  interval_seconds: 300, confirm_attempts: 3, confirm_interval_seconds: 30, recovery_checks: 3, recovery_interval_seconds: 30,
  slow_response_enabled: false, slow_threshold_ms: 15000, env: [], ...RESULT, ...over,
})
const SRC = mon(1, 'login-flow')
const deferred = () => { let resolve; const p = new Promise((res) => { resolve = res }); return { p, resolve } }
const dialog = () => screen.queryByRole('dialog')
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')
const cardBtn = (name, action) => screen.getByRole('button', { name: new RegExp(`^${esc(name)} — (${action})$`, 'i') })
const cardOf = (name) => cardBtn(name, 'detayları aç|open details').closest('[data-slot="card"]')

async function renderPage(monitors = [SRC]) {
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors },
  })
  const utils = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" />)
  await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalled())
  await screen.findByText(monitors[0].name)
  return utils
}

beforeEach(() => resetScriptedMocks(api))

describe('ScriptedMonitorPage — kayıt sonrası ilk koşum karta düşer', () => {
  it('oluşturma: TEK koşum (doğrulama = ilk kontrol) yeni kimlikle; kart "İlk kontrol yapılıyor…" → sonuç; form bandı da sonucu gösterir', async () => {
    const created = mon(2, 'login-flow (Copy)', { script_version: '1.0.0', ...NEVER })
    api.monitoring.createScriptedMonitor.mockImplementation(async () => {
      api.monitoring.getScriptedMonitors.mockResolvedValue({
        success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [SRC, created] },
      })
      return { success: true, data: created }
    })
    const run = deferred()
    api.monitoring.triggerScriptedCheck.mockReturnValue(run.p)
    await renderPage()

    fireEvent.click(cardBtn('login-flow', 'kopyala|duplicate'))
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(save|kaydet)$/i }))

    await waitFor(() => expect(api.monitoring.triggerScriptedCheck).toHaveBeenCalledWith(2))
    // Liste koşumdan ÖNCE tazelendi → yeni kart ızgarada ve koşuyor.
    await waitFor(() => expect(cardOf('login-flow (Copy)')).toHaveAttribute('data-running', 'true'))
    expect(cardOf('login-flow (Copy)').querySelector('[data-slot="monitor-first-check"]'))
      .toHaveTextContent(/^(Running the first check…|İlk kontrol yapılıyor…)$/)
    expect(document.querySelector('[data-slot="smoke-msg"]')).not.toBeNull()   // form bandı: doğrulama koşumu sürüyor

    await act(async () => { run.resolve({ success: true, data: { ...created, ...RESULT } }) })

    await waitFor(() => expect(cardOf('login-flow (Copy)')).not.toHaveAttribute('data-running'))
    const card = cardOf('login-flow (Copy)')
    expect(card.querySelector('[data-slot="monitor-first-check"]')).toBeNull()
    expect(card.querySelector('[data-slot="scripted-result"]')).toHaveAttribute('data-tone', 'ok')
    // İKİNCİ koşum yok: doğrulama koşumu kartın ilk kontrolüdür.
    expect(api.monitoring.triggerScriptedCheck).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[data-slot="smoke-msg"]')).toBeNull()
  })

  it('bant koşum sürerken KAPATILIRSA kart yine sonuçla dolar (kart birleştirmesi form sırasına bağlı değil)', async () => {
    const created = mon(2, 'login-flow (Copy)', { script_version: '1.0.0', ...NEVER })
    api.monitoring.createScriptedMonitor.mockImplementation(async () => {
      api.monitoring.getScriptedMonitors.mockResolvedValue({
        success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [SRC, created] },
      })
      return { success: true, data: created }
    })
    const run = deferred()
    api.monitoring.triggerScriptedCheck.mockReturnValue(run.p)
    await renderPage()

    fireEvent.click(cardBtn('login-flow', 'kopyala|duplicate'))
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(save|kaydet)$/i }))
    await waitFor(() => expect(document.querySelector('[data-slot="smoke-msg"]')).not.toBeNull())
    fireEvent.click(within(document.querySelector('[data-slot="smoke-msg"]').closest('[data-slot="alert"]')).getByRole('button', { name: /^(close|kapat)$/i }))
    await waitFor(() => expect(dialog()).toBeNull())
    expect(cardOf('login-flow (Copy)')).toHaveAttribute('data-running', 'true')

    await act(async () => { run.resolve({ success: true, data: { ...created, ...RESULT } }) })
    await waitFor(() => expect(cardOf('login-flow (Copy)').querySelector('[data-slot="scripted-result"]')).toHaveAttribute('data-tone', 'ok'))
  })

  it('düzenleme: yeni SÜRÜM (script değişti) → taze koşum aynı izleme için', async () => {
    await renderPage()
    api.monitoring.updateScriptedMonitor.mockResolvedValueOnce({ success: true, data: { ...SRC, script_version: '1.0.3' } })
    api.monitoring.triggerScriptedCheck.mockResolvedValue({ success: true, data: { ...SRC, script_version: '1.0.3' } })
    fireEvent.click(cardBtn('login-flow', 'düzenle|edit'))
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(save|kaydet)$/i }))
    await waitFor(() => expect(api.monitoring.triggerScriptedCheck).toHaveBeenCalledWith(1))
    expect(api.monitoring.triggerScriptedCheck).toHaveBeenCalledTimes(1)
  })

  it('düzenleme: yalnız meta (ad / etiket; sürüm aynı) → koşum BAŞLAMAZ, pencere kapanır', async () => {
    await renderPage()
    api.monitoring.updateScriptedMonitor.mockResolvedValueOnce({ success: true, data: { ...SRC, tags: 'prod,web' } })
    fireEvent.click(cardBtn('login-flow', 'düzenle|edit'))
    fireEvent.click(within(dialog()).getByRole('button', { name: /^(save|kaydet)$/i }))
    await waitFor(() => expect(api.monitoring.updateScriptedMonitor).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(dialog()).toBeNull())
    await act(async () => {})
    expect(api.monitoring.triggerScriptedCheck).not.toHaveBeenCalled()
  })
})
