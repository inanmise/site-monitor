import { render, screen, fireEvent, within } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'
import MonitorCheckRunModal from '../components/check/MonitorCheckRunModal.jsx'

// CodeEditor (prismjs/CSS) jsdom'da ağır → basit textarea ile mock (kardeş sentetik testleriyle aynı düzenek)
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => (
    <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

beforeEach(() => resetScriptedMocks(api))

const monitor = (id, teamId, extra = {}) => ({
  id, name: `Senaryo ${id}`, status: 'PASS', team_id: teamId, team_name: teamId === 5 ? 'Takım A' : 'Takım B',
  duration_ms: 800, checks_passed: 3, checks_failed: 0, checked_at: '2026-09-29T09:00:00', ...extra,
})

/**
 * PROD OLAYI (2026-09-29): kapsamlı yönetici Sentetik İzleme'de "Şimdi Kontrol Et (39)" gördü. Sayı rol dizesinden
 * ("ADMIN") türüyordu; artık sunucunun satır bayrağı `can_check` (tetik ucunun kapısıyla aynı kural) da şart —
 * düğme yalnız çalıştırılabilecek izlemeleri sayar. Diğer sekiz sayfa: checkAllServerScope.test.jsx.
 */
describe('ScriptedMonitorPage — toplu kontrol sayısı sunucunun can_check bayrağından', () => {
  it('kapsamlı yönetici: can_check=false satır sayıya GİRMEZ; bayraksız satır eski davranışla girer', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({
      success: true,
      data: {
        k6_available: true, k6_version: 'v0.49.0', can_manage: true,
        monitors: [monitor(1, 5, { can_check: true }), monitor(2, 9, { can_check: false }), monitor(3, 5)],
      },
    })
    render(<ScriptedMonitorPage systemRole="ADMIN" globalAdmin={false} teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    expect(await screen.findByRole('button', { name: /^(Şimdi Kontrol Et|Check Now) \(2\)$/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^(Şimdi Kontrol Et|Check Now) \(3\)$/ })).toBeNull()
  })
})

// D-10 (2026-09-29): elle k6 kotası doluysa sunucu `skipped_code: MANUAL_POOL_BUSY` döner — ileti arayüz dilinde.
describe('ScriptedMonitorPage — elle k6 kotası dolu', () => {
  it('kart ▶ → atlanan koşumun sebebi arayüz dilinde (sunucunun Türkçe yedek metni DEĞİL)', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({
      success: true,
      data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [monitor(1, 5, { can_check: true })] },
    })
    api.monitoring.triggerScriptedCheck.mockResolvedValue({
      success: true,
      data: { ...monitor(1, 5), skipped: true, skipped_reason: 'k6 havuzunun elle kontrol payı dolu', skipped_code: 'MANUAL_POOL_BUSY' },
    })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    const card = await screen.findByText('Senaryo 1')
    const footer = card.closest('[data-slot="card"]').querySelector('[data-slot="card-footer"]')
    fireEvent.click(within(footer).getByRole('button', { name: /^Senaryo 1 — (Şimdi Çalıştır|Run now)$/i }))
    expect(await screen.findByText(/share for manual checks is busy|elle kontrol payı şu an dolu/)).toBeInTheDocument()
    expect(container).toBeTruthy()
  })
})

// O-b3 (2026-09-29): toplu kontrolde havuz dolu olduğu için YÜRÜTÜLMEYEN kontroller sessiz kalmaz — sayıyla söylenir
// (kart eski sonucu gösterdiği için kullanıcı onu "yeni" sanmasın).
describe('Toplu kontrol ilerlemesi — havuz dolu olduğu için atlananların sayısı', () => {
  const row = (id, over = {}) => ({
    monitor: { id, name: `Senaryo ${id}` },
    start: new Date('2026-09-29T09:00:00'), end: new Date('2026-09-29T09:00:01'),
    ms: 10, ok: true, data: {}, error: null, ...over,
  })
  const busy = { ok: false, error: 'atlandı', data: { skipped: true, skipped_code: 'MANUAL_POOL_BUSY' } }
  const runOf = (rows) => ({ rows, total: rows.length, done: true, teamLabel: null, startedAt: 1000, finishedAt: 2000 })

  it('iki MANUAL_POOL_BUSY satırı → uyarı bandında 2; başka hata ve başarılı satır sayılmaz', () => {
    const run = runOf([row(1, busy), row(2, busy), row(3, { ok: false, error: 'boom', data: null }), row(4)])
    render(<MonitorCheckRunModal run={run} type="scripted" onClose={() => {}} onCancel={() => {}} />)
    const banner = document.querySelector('[data-slot="alert"][data-tone="warning"]')
    expect(banner).not.toBeNull()
    expect(banner.textContent).toMatch(/k6 havuzu dolu olduğu için 2 kontrol atlandı|pool was full: 2 /)
  })

  it('atlanan yoksa bant çizilmez', () => {
    render(<MonitorCheckRunModal run={runOf([row(1), row(2, { ok: false, error: 'boom', data: null })])} type="scripted"
      onClose={() => {}} onCancel={() => {}} />)
    expect(document.querySelector('[data-slot="alert"][data-tone="warning"]')).toBeNull()
  })
})
