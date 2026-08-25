/**
 * ScriptedMonitorPage testlerinin ORTAK koşum takımı.
 *
 * <p>Sayfa testi tek dosyada 879 satıra ulaşmıştı; aynı mock bloğu üç kez kopyalanmasın diye
 * API mock’u ve varsayılan yanıtlar buraya alındı. `vi.mock` dosya kapsamlı ve hoist edilir:
 * her test dosyası fabrikayı `async () => (await import(...)).apiClientMock()` biçiminde çağırır,
 * böylece hoist sırasında tanımsız değişken sorunu oluşmaz.
 */
import { vi } from 'vitest'
import { withApiFallback } from '../apiMock.js'

/**
 * Şablon kütüphanesinin test fixture'ı — `scripted-templates.json`'daki YERLEŞİK katalogun
 * küçültülmüş ama sadık bir örneği (alan adları liste ucunun ürettiği satırla birebir).
 *
 * Neden burada: şablonlar 20.25 ile statik `scriptedTemplates.js` modülünden çıkıp API'ye taşındı.
 * Sayfa testleri artık ucu mock'lamazsa seçicide şablon grubu SESSİZCE boşalır ve testler
 * "seçenek yok" diye kırılır — ki bu, ürün hatası değil fixture eksikliğidir.
 *
 * `script` alanı bilerek YOK: liste ucu gövdeyi taşımaz (yüzlerce şablonda yanıt şişmesin diye),
 * gövde `getScriptedTemplate(id)` ile tekil çekilir. Gerçek sözleşme buysa fixture de öyle olmalı.
 */
export const TEMPLATE_ROWS = [
  {
    id: 101, name: 'Sistem sağlık kontrolü (smoke)', name_en: 'System health check (smoke)',
    description: 'env GEREKTİRMEZ; www.example.com adresine GET atıp içeriği doğrular.',
    description_en: 'No env needed; GETs www.example.com and validates its content.',
    when_to_use: 'Sentetik İzleme\'yi ilk kez kurarken.', when_to_use_en: 'When setting up Synthetic Monitoring.',
    category: 'availability',
    tags: [], team_id: null, team_name: null, scope: 'general',
    builtin: true, builtin_key: 'smoke-health', select_token: 'smoke-health',
    current_version: '1.0.0', active: true, env: [], script_chars: 620,
    updated_at: '2026-08-20T10:00:00', updated_by_name: 'Sistem',
    can_edit: false, can_delete: false, can_promote: false, can_demote: false, can_permanent_delete: false,
  },
  {
    id: 102, name: 'JSON sağlık ucu (Actuator / mikroservis)', name_en: 'JSON health endpoint (Actuator / microservice)',
    description: 'Actuator health ucunu çağırır ve status alanını doğrular.',
    description_en: 'Calls the Actuator health endpoint and validates the status field.',
    when_to_use: 'Mikroservis sağlık ucu izlemesinde.', when_to_use_en: 'For microservice health endpoints.',
    category: 'api',
    tags: [], team_id: null, team_name: null, scope: 'general',
    builtin: true, builtin_key: 'json-health', select_token: 'json-health',
    current_version: '1.0.0', active: true,
    env: [{ name: 'BASE_URL', secret: false }], script_chars: 540,
    updated_at: '2026-08-20T10:00:00', updated_by_name: 'Sistem',
    can_edit: false, can_delete: false, can_promote: false, can_demote: false, can_permanent_delete: false,
  },
  {
    id: 103, name: 'OAuth2 client_credentials → korumalı API', name_en: 'OAuth2 client_credentials → protected API',
    description: 'Token alır, korumalı API\'yi Bearer ile çağırır.',
    description_en: 'Gets a token, calls the protected API with Bearer.',
    when_to_use: 'Servisten servise entegrasyonlarda.', when_to_use_en: 'For service-to-service integrations.',
    category: 'identity',
    tags: [], team_id: null, team_name: null, scope: 'general',
    builtin: true, builtin_key: 'oauth2-client-credentials', select_token: 'oauth2-client-credentials',
    current_version: '1.0.0', active: true,
    env: [
      { name: 'TOKEN_URL', secret: false },
      { name: 'CLIENT_ID', secret: false },
      { name: 'CLIENT_SECRET', secret: true },
      { name: 'API_URL', secret: false },
    ],
    script_chars: 980,
    updated_at: '2026-08-20T10:00:00', updated_by_name: 'Sistem',
    can_edit: false, can_delete: false, can_promote: false, can_demote: false, can_permanent_delete: false,
  },
]

/** Tekil ucun döndürdüğü gövdeler (id → script). */
export const TEMPLATE_SCRIPTS = {
  101: "import http from 'k6/http';\nimport { check } from 'k6';\n\nexport default function () {\n  const res = http.get('https://www.example.com/', { timeout: '20s' });\n  check(res, { 'status 200': (r) => r.status === 200 });\n}\n",
  102: "import http from 'k6/http';\nimport { check } from 'k6';\n\nexport default function () {\n  const res = http.get(__ENV.BASE_URL + '/actuator/health', { timeout: '20s' });\n  check(res, { 'UP': (r) => r.json('status') === 'UP' });\n}\n",
  103: "import http from 'k6/http';\nimport { check } from 'k6';\n\nexport default function () {\n  const tok = http.post(__ENV.TOKEN_URL, { grant_type: 'client_credentials', client_id: __ENV.CLIENT_ID, client_secret: __ENV.CLIENT_SECRET }, { timeout: '20s' });\n  check(tok, { 'token': (r) => r.status === 200 });\n}\n",
}

export function apiClientMock() {
  return {
    // GERÇEK davranış pini: formatDateSec undefined/null'a 'N/A' basar — 2026-08 regresyonunda
    // test mock'u `s ?? ''` ile bunu maskelemişti ve alan-adı hatası (checkedAt vs checked_at) kaçmıştı.
    formatDateSec: (s) => (s ? `FMT:${s}` : 'N/A'),
    formatDateOnly: (s) => s ?? '',
    formatDate: (s) => s ?? '',
    api: withApiFallback({
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
        // Şablon uçları da `monitoring` ad alanında — client.js'te tanımlandıkları yer.
        // Yanlış ad alanına yazmak testi yeşil bırakır (withApiFallback bilinmeyen adı üretir)
        // ama tarayıcıda "is not a function" olur; kapısı `api-call-sites.test.js`.
        getScriptedTemplates: vi.fn(),
        getScriptedTemplate: vi.fn(),
      },
      admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
    }),
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
  api.monitoring.getScriptedTemplates.mockResolvedValue({
    success: true,
    data: {
      templates: TEMPLATE_ROWS,
      can_create_general: false, can_view_trash: false, writable_team_ids: [5], k6_version: 'v0.49.0',
    },
  })
  api.monitoring.getScriptedTemplate.mockImplementation((id) => Promise.resolve(
    TEMPLATE_SCRIPTS[id]
      ? { success: true, data: { ...TEMPLATE_ROWS.find(r => r.id === id), script: TEMPLATE_SCRIPTS[id] } }
      : { success: false, error: 'Şablon bulunamadı' }))
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true,
    data: {
      k6_available: true, k6_version: 'v0.49.0', can_manage: true,
      monitors: [{ id: 1, name: 'OIDC Login', status: 'PASS', team_id: 5, team_name: 'SY-A', duration_ms: 800, checks_passed: 3, checks_failed: 0, checked_at: '2026-07-31T10:00:00' }],
    },
  })
}
