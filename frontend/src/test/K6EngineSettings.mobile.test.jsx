import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Ayarlar → k6 motoru, dar kapta (telefon; kap < 760 px): sonuçlar KART düzeninde — tablo yok, kartta gerçek kontrol / aday /
// hata satırları ve "Yeniden tara". Genişlik kancası 400 px'e sabitlenir (TechErrorReportSettings.mobile ile aynı desen).
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400], useElementWidthState: () => [400, () => {}] }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ admin: { k6Engine: vi.fn(), k6Scans: vi.fn(), k6Scan: vi.fn(), k6ScanStart: vi.fn() } }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import K6EngineSettings from '../components/admin/k6engine/K6EngineSettings.jsx'

const LONG = 'Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Giriş Senaryosu (Dijital Kanallar / Bireysel Bankacılık)'
const ENGINE = { bin: '/opt/sitemonitor/k6/releases/v2.3.0/linux-amd64/k6', available: true, version: 'v2.3.0', modern_syntax: true, probed_at: '2026-10-29T20:15:03' }
const scan = { id: 3, state: 'DONE', scope: 'ALL', total: 1, done: 1, regressions: 1, candidate_version: 'v2.3.0', active_version: 'v0.49.0', started_at: '2026-10-29T18:00:00' }

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.admin.k6Engine.mockResolvedValue({ success: true, data: {
    pod: 'site-monitor-7c9d', engines: { active: { ...ENGINE, version: 'v0.49.0', bin: '/usr/local/bin/k6' }, rollback: null, candidate: ENGINE },
    syntax_version: 'v0.49.0', settings: { rollback_bin: '', candidate_bin: ENGINE.bin, godebug: '', user_agent: '', excluded_ids: [] },
    godebug_keys: [], scripted_enabled: true, can_switch: true, can_rollback: false,
    monitors: [{ id: 21, name: LONG, team_id: 1, team_name: 'Dijital Kanallar', active: true }], scan,
  } })
  api.admin.k6Scans.mockResolvedValue({ success: true, data: { scans: [scan] } })
  api.admin.k6Scan.mockResolvedValue({ success: true, data: { ...scan, counts: { REGRESSION: 1 }, results: [
    { monitor_id: 21, monitor_name: LONG, team_id: 1, team_name: 'Dijital Kanallar', diff: 'REGRESSION', status: 'ERROR', exit_code: 107,
      duration_ms: 65_000, error_class: 'SYNTAX', error: 'SyntaxError: Unexpected token ?. at file:///script.js:12:4', archive: 'DEFINITE',
      baseline_status: 'PASS', baseline_k6_version: 'v0.49.0', baseline_checked_at: '2026-10-29T17:00:00', baseline_duration_ms: 900 },
  ] } })
})

describe('K6EngineSettings — dar kap (kartlar)', () => {
  it('sonuçlar kart düzeninde: tablo yok, uzun ad tam, gerçek kontrol / aday / hata satırları, Yeniden tara', async () => {
    render(<K6EngineSettings />)
    await screen.findByText('Motorlar')
    await waitFor(() => expect(document.querySelector('[data-slot="k6e-res-list"]')).toHaveAttribute('data-layout', 'cards'))
    expect(document.querySelector('[data-slot="k6e-res-table"]')).toBeNull()
    const card = document.querySelector('[data-slot="k6e-res-row"][data-id="21"]')
    expect(within(card).getByText(LONG)).toBeInTheDocument()
    expect(within(card).getByText('Gerçek kontrol')).toBeInTheDocument()
    expect(within(card).getByText('Aday')).toBeInTheDocument()
    expect(within(card).getByText('Sözdizimi')).toBeInTheDocument()
    expect(within(card).getByText('1 dk 5 sn')).toBeInTheDocument()
    expect(card.querySelector('[data-slot="k6e-archive"]')).toHaveAttribute('data-archive', 'DEFINITE')
    // Uzun ikili yolu kırpılmaz (sarar)
    expect(screen.getAllByText(ENGINE.bin)[0].className).toContain('[overflow-wrap:anywhere]')
    fireEvent.click(within(card).getByRole('button', { name: /Hata metnini göster/ }))
    expect(card.querySelector('[data-slot="k6e-problem-pre"]')).toHaveTextContent('Unexpected token ?.')
    api.admin.k6ScanStart.mockResolvedValue({ success: true, data: { ...scan, id: 4, state: 'RUNNING', scope: 'SELECTED', done: 0 } })
    fireEvent.click(within(card).getByRole('button', { name: `Yeniden tara: ${LONG}` }))
    await waitFor(() => expect(api.admin.k6ScanStart).toHaveBeenCalledWith({ monitor_ids: [21] }))
  })
})
