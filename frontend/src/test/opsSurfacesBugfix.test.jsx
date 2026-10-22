import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * Operasyon ekranları — 2026-10-22 hata düzeltmeleri:
 *  - UIM-29: 7/24 sekmesi konsol / kapsam kararını girişteki /me'den veriyordu — CANLI `scope.noc_console` öncelikli.
 *  - UIM-35: Fırtına sayfasının "Fırtına ayarları" düğmesi ADMIN olmayana gösteriliyordu ("Erişim yok"a düşüyordu).
 *  - UIM-21 / UIM-47: toplu işlem çubuğu görünmeyen seçimleri sayıyordu; envanter türevi Port/DNS takım değişimi "güncellendi" diyordu.
 *  - UIM-36: fırtına / takım hücresi / Bugün panelinde ham alarm türü ve seviye kodları.
 *  - UIM-37: takım dağılımı okunamayınca sonsuz "Yükleniyor…". UIM-45: Sentetik sürümler okunamayınca "Henüz sürüm yok".
 *  - UIM-39: Durum sayfası `sp_` URL anahtarları sekme değişince temizlenmiyordu.
 */
const scopeBox = vi.hoisted(() => ({ scope: null }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ scope: scopeBox.scope, perms: {}, canView: () => true, canEdit: () => true, canExecute: () => true,
    refresh: () => {}, loaded: true, failed: false }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../pages/NocCoveragePage.jsx', () => ({ default: (p) => <div data-testid="coverage" data-noc-operator={String(!!p.nocOperator)} /> }))
vi.mock('../components/noc/console/NocConsole.jsx', () => ({ default: () => <div data-testid="console" /> }))
const confirmMock = vi.hoisted(() => vi.fn(() => Promise.resolve(true)))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: { getAlertTeamStats: vi.fn(), getAlerts: vi.fn() },
    monitoring: { getScriptedVersions: vi.fn(), getScriptedVersion: vi.fn() },
    me: { today: vi.fn() },
  }),
}))
import { api } from '../api/client'
import NocPage from '../pages/NocPage.jsx'
import StormStatusPage from '../components/StormStatusPage.jsx'
import BulkActionBar from '../components/ui/BulkActionBar.jsx'
import StormTeamCards from '../components/storm/StormTeamCards.jsx'
import { stormTimeline } from '../components/storm/stormModel.js'
import AlertTeamStatsPanel from '../components/admin/alerts/AlertTeamStatsPanel.jsx'
import AlertTeamCellModal from '../components/admin/alerts/AlertTeamCellModal.jsx'
import ScriptedVersionsTab from '../components/scripted/ScriptedVersionsTab.jsx'
import TodayPanel from '../components/TodayPanel.jsx'
import { PAGE_STATE_PREFIXES } from '../hooks/useUrlQuerySync.js'

beforeEach(() => { vi.clearAllMocks(); scopeBox.scope = null; window.history.replaceState({}, '', '/?tab=noc'); try { sessionStorage.clear(); localStorage.clear() } catch { /* yok */ } })
afterEach(() => window.history.replaceState({}, '', '/'))

describe('UIM-29 — 7/24 sekmesi CANLI kapsamı izler', () => {
  it('girişte operatör değil ama canlı scope.noc_console=true → konsol sekmeleri var', () => {
    scopeBox.scope = { noc_console: true, noc_operator: true }
    render(<NocPage nocOperator={false} />)
    expect(screen.getByRole('tablist')).toBeInTheDocument()
    expect(screen.getByTestId('console')).toBeInTheDocument()
  })

  it('girişte operatördü, takımdan çıkarıldı (scope.noc_console=false) → yalnız kapsam', () => {
    scopeBox.scope = { noc_console: false, noc_operator: false }
    render(<NocPage nocOperator />)
    expect(screen.queryByRole('tablist')).toBeNull()
    expect(screen.getByTestId('coverage')).toHaveAttribute('data-noc-operator', 'false')
  })

  it('scope yoksa (eski sunucu) girişteki değerler: operatör konsolla açılır', () => {
    render(<NocPage nocOperator />)
    expect(screen.getByTestId('console')).toBeInTheDocument()
  })

  it('UIM-30: operatörde n_view=coverage bağlantısı kapsamı açar', () => {
    window.history.replaceState({}, '', '/?tab=noc&n_view=coverage&n_team=5')
    render(<NocPage nocOperator />)
    expect(screen.getByRole('tab', { name: /coverage|kapsam/i })).toHaveAttribute('aria-selected', 'true')
  })
})

describe('UIM-35 — "Fırtına ayarları" yalnız Ayarlar\'ı açabilene', () => {
  const settingsBtn = () => screen.queryByRole('button', { name: /fırtına ayarları|storm settings/i })
  it('canOpenSettings=false → düğme yok; true → var', async () => {
    const { unmount } = render(<StormStatusPage canOpenSettings={false} />)
    await waitFor(() => expect(document.querySelector('[data-slot="sf-page"]')).not.toBeNull())
    expect(settingsBtn()).toBeNull()
    unmount()
    render(<StormStatusPage canOpenSettings />)
    expect(settingsBtn()).not.toBeNull()
  })
  it('prop verilmezse yalnız global yönetici (scope.global_admin); bilinmiyorsa gizli', () => {
    const { unmount } = render(<StormStatusPage />)
    expect(settingsBtn()).toBeNull()
    unmount()
    scopeBox.scope = { global_admin: true }
    render(<StormStatusPage />)
    expect(settingsBtn()).not.toBeNull()
  })
})

describe('UIM-21 / UIM-47 — toplu işlem çubuğu', () => {
  const api2 = () => ({ update: vi.fn().mockResolvedValue({ success: true }), remove: vi.fn().mockResolvedValue({ success: true }) })
  it('sayaç GÖRÜNEN seçili satırlar: seçim {1,2,9}, görünen 1 ve 2 → "2 seçili"; görünmeyen seçim budanır', async () => {
    const onPrune = vi.fn()
    render(<BulkActionBar selected={new Set([1, 2, 9])} items={[{ id: 1, active: true }, { id: 2, active: true }]} api={api2()} onPrune={onPrune} />)
    expect(screen.getByText(/2 seçili|2 selected/)).toBeInTheDocument()
    await waitFor(() => expect(onPrune).toHaveBeenCalled())
    expect([...onPrune.mock.calls.at(-1)[0]].sort()).toEqual([1, 2])
  })
  it('seçimlerin hepsi başka sayfadaysa çubuk çizilmez', () => {
    render(<BulkActionBar selected={new Set([9])} items={[{ id: 1, active: true }]} api={api2()} />)
    expect(screen.queryByRole('region', { name: /^(Toplu işlem|Bulk actions)$/ })).toBeNull()
  })
  it('DNS: envanter türevi satırın takımı değiştirilmez, atlandığı söylenir', async () => {
    const a = api2()
    const rows = [{ id: 1, name: 'bağımsız', active: true, standalone: true }, { id: 2, name: 'türev', active: true, standalone: false }]
    render(<BulkActionBar selected={new Set([1, 2])} items={rows} api={a} nocType="DNS" teams={[{ id: 7, name: 'Takım A' }]} onClear={() => {}} onDone={() => {}} />)
    fireEvent.mouseDown(screen.getByRole('combobox', { name: /^(Takım|Team)/ }))
    fireEvent.mouseDown([...document.querySelectorAll('[role="listbox"] [role="option"]')].find((o) => o.textContent.trim() === 'Takım A'))
    fireEvent.click(screen.getAllByRole('button', { name: /^(Uygula|Apply)$/ })[0])
    await waitFor(() => expect(a.update).toHaveBeenCalledTimes(1))
    expect(a.update).toHaveBeenCalledWith(1, { teamId: 7 })
    expect(await screen.findByText(/1 (izleme atlandı|monitor\(s\) skipped)/)).toBeInTheDocument()
  })
})

describe('UIM-36 — ham kodlar yerine etiketler', () => {
  it('fırtına takım kartı pencere öğelerinde alarm türü etiketi', () => {
    render(<StormTeamCards teams={[{ team_id: 1, team_name: 'A', status: 'NEAR', threshold: 5, window_minutes: 5, window_targets: 1,
      window_alerts: 1, storms: [], window_items: [{ id: 1, domain: 'x.example.com', alert_type: 'PING_DOWN' }] }]} />)
    const items = document.querySelector('[data-slot="sf-window-items"]')
    expect(items.textContent).not.toContain('PING_DOWN')
  })
  it('fırtına zaman çizelgesi tetikleyeni etiketle yazar', () => {
    const t = (k, ...a) => (k === 'incov.type.PING_DOWN' ? 'Ping kesintisi' : k.startsWith('incov.type.') ? k : `${k}|${a.join('|')}`)
    const ev = stormTimeline({ created_at: '2026-10-22T08:00:00', trigger: { domain: 'x', alert_type: 'PING_DOWN' } }, t)
    expect(ev.find((e) => e.kind === 'trigger').text).toContain('Ping kesintisi')
  })
  // EN etiketi "CRITICAL" ham kodla aynı yazılır — ayrımı TR etiketiyle sına
  it('takım hücresi penceresi seviyeyi etiketle gösterir', async () => {
    localStorage.setItem('site-monitor-lang', 'tr')
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [{ id: 1, alert_level: 'CRITICAL', domain: 'd.example.com',
      alert_type: 'HTTP_DOWN', created_at: '2026-10-22T08:00:00', resolved: false }], total: 1 })
    render(<AlertTeamCellModal cell={{ team: { team_id: 5, team_name: 'A' }, bucket: 'open', windowDays: 30, count: 1 }} onClose={() => {}} />)
    await screen.findByText('d.example.com')
    expect(document.querySelector('[data-cell-row]').textContent).not.toContain('CRITICAL')
    expect(document.querySelector('[data-cell-row]').textContent).toContain('KRİTİK')
  })
  it('Bugün paneli açık alarm satırı: seviye ve tür etiketli', async () => {
    localStorage.setItem('site-monitor-lang', 'tr')
    api.me.today.mockResolvedValue({ success: true, data: {
      certs: { count: 0, items: [] },
      alerts: { count: 1, critical: 1, items: [{ id: 9, domain: 'down.example.com', type: 'HTTP_DOWN', level: 'CRITICAL', acknowledged: false }] },
      flapping: { count: 0, items: [] }, slow: { count: 0, items: [] }, stale: { count: 0, paused: 0, items: [] },
      domains: { count: 0, expired: 0, items: [] }, notifications: { count: 0, email: 0, webhook: 0, push: 0, items: [] },
      health: { count: 0, critical: 0, items: [] }, weekly: { count: 0, missing: 0, items: [] } } })
    render(<TodayPanel onOpenDomain={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: /Sizin için|For you/ }))
    const row = (await screen.findByRole('button', { name: 'down.example.com' })).closest('li')
    expect(row.textContent).not.toContain('CRITICAL')
    expect(row.textContent).toContain('KRİTİK')
    expect(row.textContent).not.toContain('HTTP_DOWN')
  })
})

describe('UIM-37 / UIM-45 — okuma hatası "yükleniyor" ya da "yok" değildir', () => {
  it('takım dağılımı okunamazsa hata + yeniden dene', async () => {
    sessionStorage.setItem('alh-teamstats-open', 'true')
    api.admin.getAlertTeamStats.mockResolvedValueOnce({ success: false, error: 'x' })
    render(<AlertTeamStatsPanel onPickTeam={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="alhts-error"]')).not.toBeNull())
    api.admin.getAlertTeamStats.mockResolvedValueOnce({ success: true, data: { teams: [], total_open: 0, total_last7: 0 } })
    fireEvent.click(document.querySelector('[data-slot="alhts-error"] button'))
    await waitFor(() => expect(document.querySelector('[data-slot="alhts-error"]')).toBeNull())
  })

  it('Sentetik sürümler okunamazsa "Henüz sürüm yok" değil hata; ağ hatası sonsuz bekleme değil', async () => {
    const t = (k) => k
    api.monitoring.getScriptedVersions.mockResolvedValueOnce({ success: false, error: 'boom' })
    const { unmount } = render(<ScriptedVersionsTab t={t} monitor={{ id: 3 }} canEdit={false} onLoadIntoEditor={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="sc-versions-error"]')).not.toBeNull())
    expect(screen.queryByText('scripted.versionNone')).toBeNull()
    unmount()
    api.monitoring.getScriptedVersions.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    render(<ScriptedVersionsTab t={t} monitor={{ id: 4 }} canEdit={false} onLoadIntoEditor={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="sc-versions-error"]')).not.toBeNull())
  })
})

describe('UIM-39 — Durum sayfası URL anahtarları sekme değişince temizlenir', () => {
  it("PAGE_STATE_PREFIXES 'sp_' içerir", () => {
    expect(PAGE_STATE_PREFIXES).toContain('sp_')
  })
})
