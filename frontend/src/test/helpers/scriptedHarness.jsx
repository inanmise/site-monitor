/**
 * ScriptedMonitorPage testlerinin ORTAK koşum takımı.
 *
 * <p>Sayfa testi tek dosyada 879 satıra ulaşmıştı; aynı mock bloğu üç kez kopyalanmasın diye
 * API mock’u ve varsayılan yanıtlar buraya alındı. `vi.mock` dosya kapsamlı ve hoist edilir:
 * her test dosyası fabrikayı `async () => (await import(...)).apiClientMock()` biçiminde çağırır,
 * böylece hoist sırasında tanımsız değişken sorunu oluşmaz.
 */
import { vi } from 'vitest'

export function apiClientMock() {
  return {
    // GERÇEK davranış pini: formatDateSec undefined/null'a 'N/A' basar — 2026-08 regresyonunda
    // test mock'u `s ?? ''` ile bunu maskelemişti ve alan-adı hatası (checkedAt vs checked_at) kaçmıştı.
    formatDateSec: (s) => (s ? `FMT:${s}` : 'N/A'),
    formatDateOnly: (s) => s ?? '',
    formatDate: (s) => s ?? '',
    api: {
      monitoring: {
        getScriptedMonitors: vi.fn(),
        getScriptedVersions: vi.fn(() => Promise.resolve({ success: true, data: { versions: [], current_version: null } })),
        getScriptedVersion: vi.fn(),
        saveScriptedDraft: vi.fn(() => Promise.resolve({ success: true, data: {} })),
        getScriptedDrafts: vi.fn(() => Promise.resolve({ success: true, data: { drafts: [] } })),
        deleteScriptedDraft: vi.fn(() => Promise.resolve({ success: true })),
        getCheckHistory: vi.fn(() => Promise.resolve({ success: true, data: { items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [], range: { from: '', to: '' }, total: 0, page: 0, size: 50 } })),
        getCheckHistoryCsvUrl: vi.fn(() => '#'),
        createScriptedMonitor: vi.fn(() => Promise.resolve({ success: true, data: {} })),
        updateScriptedMonitor: vi.fn(),
        deleteScriptedMonitor: vi.fn(),
        triggerScriptedCheck: vi.fn(),
        testScripted: vi.fn(() => Promise.resolve({ success: true, data: { status: 'PASS', checks_passed: 3, checks_failed: 0, duration_ms: 820, output_tail: 'out' } })),
        listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
        monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: { scripted: { intervalSeconds: 300, timeoutSeconds: 60 } } })),
      },
      admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
    },
  }
}

/** Her testten önce varsayılan (mutlu yol) yanıtları kurar. */
export function resetScriptedMocks(api) {
  vi.clearAllMocks()
  api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
  api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
  api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [] } })
  api.monitoring.saveScriptedDraft.mockResolvedValue({ success: true, data: {} })
  api.monitoring.getScriptedVersions.mockResolvedValue({ success: true, data: { versions: [], current_version: null } })
  api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { scripted: { intervalSeconds: 300, timeoutSeconds: 60 } } })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true,
    data: {
      k6_available: true, k6_version: 'v0.49.0', can_manage: true,
      monitors: [{ id: 1, name: 'OIDC Login', status: 'PASS', team_id: 5, team_name: 'SY-A', duration_ms: 800, checks_passed: 3, checks_failed: 0, checked_at: '2026-07-31T10:00:00' }],
    },
  })
}
