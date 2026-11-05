import { render, screen, waitFor } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />,
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../components/MonitorNotes.jsx', () => ({ default: () => <div data-testid="monitor-notes" /> }))
vi.mock('../components/admin/AlertHistory.jsx', () => ({ default: () => <div data-testid="alert-history" /> }))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

/**
 * `sc_open=draft` (kw paket F1, 2026-11-05): anahtar kelime izlemesinden "Sentetik izlemeye dönüştür" taslağı `new`
 * anahtarına yazar ve bu sayfayı `?tab=scripted&sc_open=draft` ile açar → liste + taslaklar yüklenince form taslakla
 * açılır; parametre URL'den TEK SEFERLİK silinir (yenileme / geri formu tekrar açmasın). Taslak yoksa ya da yetki
 * yoksa form açılmaz ama parametre yine silinir.
 */
const DRAFT = {
  monitor_key: 'new', monitor_id: null, monitor_name: 'Kurumsal site (sentetik)', updated_at: '2026-11-05T09:00:00',
  form_json: JSON.stringify({ name: 'Kurumsal site (sentetik)', script: "import http from 'k6/http'\n// dönüştürülen betik\n", env: [] }),
}

function setUrl(search) { window.history.replaceState(null, '', `/${search}`) }

beforeEach(() => { resetScriptedMocks(api) })
afterEach(() => { setUrl('') })

async function renderPage(canManage = true) {
  api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: true, k6_version: 'v2.3.0', can_manage: canManage, monitors: [] } })
  render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
  await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
}

describe('ScriptedMonitorPage — sc_open=draft', () => {
  it('taslak varsa form dönüştürülen betikle açılır; parametre URL\'den silinir (tab kalır)', async () => {
    api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [DRAFT] } })
    setUrl('?tab=scripted&sc_open=draft')
    await renderPage()
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('dönüştürülen betik'))
    expect(new URLSearchParams(window.location.search).get('sc_open')).toBeNull()
    expect(new URLSearchParams(window.location.search).get('tab')).toBe('scripted')
  })

  it('taslak yoksa form açılmaz, parametre yine silinir', async () => {
    setUrl('?tab=scripted&sc_open=draft')
    await renderPage()
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('sc_open')).toBeNull())
    expect(screen.queryByTestId('code-editor')).toBeNull()
  })

  it('yetki yoksa taslak beklenmez, form açılmaz, parametre silinir', async () => {
    api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [DRAFT] } })
    setUrl('?tab=scripted&sc_open=draft')
    await renderPage(false)
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('sc_open')).toBeNull())
    expect(screen.queryByTestId('code-editor')).toBeNull()
  })

  it('taslaklar okunamazsa ("taslak yok" sanılmaz) form açılmaz ve sayfa kırılmaz', async () => {
    api.monitoring.getScriptedDrafts.mockResolvedValue({ success: false, error: 'Sunucu hatası' })
    setUrl('?tab=scripted&sc_open=draft')
    await renderPage()
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('sc_open')).toBeNull())
    expect(screen.queryByTestId('code-editor')).toBeNull()
  })

  it('parametre yoksa taslak olsa bile form kendiliğinden açılmaz (yalnız "devam et" şeridi)', async () => {
    api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [DRAFT] } })
    setUrl('?tab=scripted')
    await renderPage()
    expect(await screen.findByText(/unfinished draft|tamamlanmamış taslağınız/i)).toBeInTheDocument()
    expect(screen.queryByTestId('code-editor')).toBeNull()
  })
})
