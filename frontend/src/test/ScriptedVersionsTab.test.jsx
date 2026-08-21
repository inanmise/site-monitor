import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'

// CodeEditor prismjs'e bağlı ve jsdom'da ağır — sayfa testlerindeki desenle sadeleştirilir.
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value }) => <textarea data-testid="code-editor" readOnly value={value} />,
}))

const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: {} }))
      return t[prop]
    },
  })
  return { apiMock: deep({ monitoring: {}, users: {} }) }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
}))

import { api } from '../api/client'
import ScriptedVersionsTab from '../components/scripted/ScriptedVersionsTab.jsx'

const t = (k, ...a) => {
  const map = {
    'scripted.verDiffSummary': `${a[0]} satır eklendi, ${a[1]} satır silindi`,
    'scripted.verDiffNone': 'Script değişmedi',
    'scripted.verDiffFirst': 'İlk sürüm',
    'scripted.verDiffEnvAdded': `Eklenen: ${a[0]}`,
    'scripted.verRunsBad': `${a[0]} koşum / ${a[1]} hata`,
    'scripted.verRunsOk': `${a[0]} koşum · hepsi geçti`,
    'scripted.verTabDiff': 'Değişiklik',
    'scripted.verTabScript': 'Script',
  }
  return map[k] ?? k
}

const VERSIONS = [
  { id: 3, version: '1.0.3', event_type: 'EDIT', created_at: '2026-08-20T10:35:01', created_by: 'N70678',
    current: true, run_count: 8, fail_count: 8 },
  { id: 2, version: '1.0.2', event_type: 'EDIT', created_at: '2026-08-20T09:54:19', created_by: 'N70678',
    run_count: 41, fail_count: 0 },
]

const V2_SCRIPT = "const params = {\n  headers: {},\n};\nhttp.post(URL, body, params);"
const V3_SCRIPT = "const params = {\n  headers: {},\n  timeout: REQUEST_TIMEOUT,\n};\nhttp.post(URL, body, params);"

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.getScriptedVersions.mockResolvedValue({ success: true, data: { versions: VERSIONS } })
  api.monitoring.getScriptedVersion.mockImplementation((_mid, vid) =>
    Promise.resolve({ success: true, data: { script: vid === 3 ? V3_SCRIPT : V2_SCRIPT, env: [] } }))
})

const setup = () => render(
  <ScriptedVersionsTab t={t} monitor={{ id: 1 }} canEdit onLoadIntoEditor={() => {}} />,
)

describe('ScriptedVersionsTab', () => {
  it('sürüm listesini çizer ve koşum sonucu rozetini gösterir', async () => {
    const { container } = setup()
    await waitFor(() => expect(screen.getByText('v1.0.3')).toBeInTheDocument())

    // Bozuk sürüm kırmızı rozetle ayrışır — olayın tek bakışta okunması bunu gerektiriyordu.
    expect(container.textContent).toContain('8 koşum / 8 hata')
    expect(container.textContent).toContain('41 koşum · hepsi geçti')
    expect(container.querySelector('.sc-ver-runs--bad')).not.toBeNull()
  })

  /** Asıl soru: "hangi düzenleme bozdu?" — varsayılan görünüm doğrudan farkı göstermeli. */
  it('sürüm seçilince VARSAYILAN görünüm diff olur ve eklenen satırı işaretler', async () => {
    const { container } = setup()
    await waitFor(() => expect(screen.getByText('v1.0.3')).toBeInTheDocument())

    fireEvent.click(screen.getByText('v1.0.3').closest('tr'))
    await waitFor(() => expect(container.querySelector('.sc-diff')).not.toBeNull())

    const added = container.querySelector('.sc-diff-row--add')
    expect(added).not.toBeNull()
    expect(added.textContent).toContain('REQUEST_TIMEOUT')
    expect(container.textContent).toContain('1 satır eklendi, 0 satır silindi')
  })

  it('Script sekmesine geçilince salt-okunur gövde gösterilir', async () => {
    const { container } = setup()
    await waitFor(() => expect(screen.getByText('v1.0.3')).toBeInTheDocument())
    fireEvent.click(screen.getByText('v1.0.3').closest('tr'))
    await waitFor(() => expect(container.querySelector('.sc-diff')).not.toBeNull())

    fireEvent.click(screen.getByText('Script'))
    await waitFor(() => expect(screen.getByTestId('code-editor')).toBeInTheDocument())
    expect(screen.getByTestId('code-editor').value).toContain('REQUEST_TIMEOUT')
  })

  it('EN ESKİ sürümde karşılaştırılacak önceki sürüm yok — diff yerine açıklama', async () => {
    const { container } = setup()
    await waitFor(() => expect(screen.getByText('v1.0.2')).toBeInTheDocument())

    fireEvent.click(screen.getByText('v1.0.2').closest('tr'))
    await waitFor(() => expect(container.textContent).toContain('İlk sürüm'))
    expect(container.querySelector('.sc-diff')).toBeNull()
  })

  it('sürüm gövdeleri bir kez çekilir (seçim ileri-geri gezinmesi yeni istek üretmez)', async () => {
    const { container } = setup()
    await waitFor(() => expect(screen.getByText('v1.0.3')).toBeInTheDocument())

    // Satır referansı BİR KEZ alınır: seçimden sonra önizleme başlığında da aynı sürüm
    // çipi olduğu için getByText('v1.0.3') iki eşleşme bulurdu.
    const row = container.querySelector('tbody tr')
    fireEvent.click(row)
    await waitFor(() => expect(container.querySelector('.sc-diff')).not.toBeNull())
    const afterFirst = api.monitoring.getScriptedVersion.mock.calls.length

    fireEvent.click(row)   // kapat
    fireEvent.click(row)   // tekrar aç
    await waitFor(() => expect(container.querySelector('.sc-diff')).not.toBeNull())

    expect(api.monitoring.getScriptedVersion.mock.calls.length).toBe(afterFirst)
  })
})
