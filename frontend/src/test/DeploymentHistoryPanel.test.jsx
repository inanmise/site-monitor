import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import DeploymentHistoryPanel from '../components/admin/DeploymentHistoryPanel.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    system: { getVersion: vi.fn(), getReleases: vi.fn() },
    admin: {
      getDeployments: vi.fn(), getDeploymentTimeline: vi.fn(), getDeploymentMatrix: vi.fn(),
      getDeploymentsCsvUrl: vi.fn((p) => `/api/admin/deployments/export?env=${p?.env ?? ''}&source=${p?.source ?? ''}&q=${p?.q ?? ''}`),
      createDeployment: vi.fn(), backfillDeployments: vi.fn(), deleteDeployment: vi.fn(),
    },
  }),
}))

import { api } from '../api/client'

// Göreli fixture tarihleri (sabit tarih = zaman bombası: 90 günlük pencere ve "şu an" süreleri kayar).
const DAY = 86_400_000
const ago = (days) => `${new Date(Date.now() - days * DAY).toISOString().slice(0, 19)}Z`

const ROW = (id, over = {}) => ({
  id, startedAt: ago(2), recordedAt: ago(2), readyAt: null, lastSeenAt: null,
  endedAt: null, endReason: null, environment: 'prod', version: '20.54.0', previousVersion: '20.53.2', kind: 'UPGRADE',
  source: 'STARTUP', commit: 'abcdef0123456789', commitShort: 'abcdef01', helm: { release: 'sm', revision: 42 },
  instanceId: 'i1', hostname: 'h1', pod: 'pod-1', node: 'node-1', createdBy: null, note: null, current: true,
  releasedAt: ago(2.1), leadTimeSeconds: 7200, ...over,
})

const TRANSITIONS = [
  ROW(3),
  ROW(4, { version: '20.53.2', previousVersion: '20.54.0', kind: 'ROLLBACK', current: false, startedAt: ago(5), commit: 'fedcba9876543210', commitShort: 'fedcba98' }),
  ROW(2, { version: '20.54.0', previousVersion: '20.53.2', kind: 'UPGRADE', current: false, startedAt: ago(6), source: 'MANUAL', note: 'bilet 123', createdBy: 'ops', commit: null, commitShort: null, pod: null, hostname: null }),
  ROW(1, { version: '20.53.2', previousVersion: null, kind: 'FIRST_SEEN', current: false, startedAt: ago(20) }),
]

const TIMELINE = {
  environment: 'prod',
  current: { version: '20.54.0', since: ago(2), kind: 'UPGRADE', previousVersion: '20.53.2', restartsSince: 1 },
  transitions: TRANSITIONS,
  restartCount: 1, unknownCount: 0, total: 5,
  summary: { deploymentsLast30d: 4, restartsLast7d: 1, rollbacks: 1, avgReleaseLagSeconds: 5400, skippedReleases: 13 },
  environments: ['prod', 'staging'],
  backfillCandidates: 7,
}

const VERSION = { version: '20.54.0', commit: 'abcdef0123456789', commitShort: 'abcdef01', environment: 'prod', mismatch: true,
  imageRef: 'registry.example.com/site-monitor:20.54.0', helm: { release: 'sm', revision: 42 }, instance: { pod: 'pod-1', node: 'node-1' }, uptimeSeconds: 600,
  live: TIMELINE.current, release: { version: '20.54.0', releasedAt: ago(2.1), bump: 'minor', breaking: false,
    highlights: [{ type: 'feat', scope: 'health', subject: 'Status console' }] }, releaseLagSeconds: 7200, releaseIndex: { loaded: true } }

const RECORDS = [ROW(3), ROW(9, { kind: 'RESTART', previousVersion: '20.54.0', current: false, startedAt: ago(3) }),
  ROW(2, { source: 'MANUAL', kind: 'UNKNOWN', version: '20.50.0', current: false, note: 'bilet 123', createdBy: 'ops', startedAt: ago(6) })]

const byName = (re) => screen.getByRole('button', { name: re })
const tab = (re) => screen.getByRole('tab', { name: re })

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  window.history.replaceState({}, '', '/')
  api.system.getVersion.mockResolvedValue({ success: true, data: VERSION })
  api.system.getReleases.mockResolvedValue({ success: true, data: { items: [], releaseIndex: { loaded: true }, total: 0 } })
  api.admin.getDeploymentTimeline.mockResolvedValue({ success: true, data: TIMELINE })
  api.admin.getDeployments.mockResolvedValue({ success: true, data: RECORDS, total: 3, page: 1, size: 25, total_pages: 1, environments: ['prod', 'staging'] })
  api.admin.getDeploymentMatrix.mockResolvedValue({ success: true, data: { environments: ['prod'], releases: [{ version: '20.54.0', releasedAt: ago(2.1), bump: 'minor', deployedIn: { prod: ago(2) }, neverDeployed: false }], truncated: false } })
})

afterEach(() => { vi.restoreAllMocks() })

describe('DeploymentHistoryPanel — koşan sürüm, göstergeler, zaman çizelgesi (okuma)', () => {
  it('koşan sürüm kartı büyük sürümü, uyuşmazlık bandını ve öne çıkanları gösterir; salt okurda yazma eylemi yok', async () => {
    render(<DeploymentHistoryPanel canEdit={false} />)
    const card = await screen.findByText('v20.54.0', { selector: '[data-slot="deploy-current-version"]' })
    expect(card).toBeInTheDocument()
    expect(screen.getByText(/imaj etiketi farklı|image tag differ/)).toBeInTheDocument()
    const hl = document.querySelector('[data-slot="deploy-highlights"]')
    expect(within(hl).getByText('Status console')).toBeInTheDocument()
    // Yazma eylemleri YOK (salt okur)
    expect(screen.queryByRole('button', { name: /Elle kayıt ekle|Add manual record/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Denetimden geri doldur|Backfill from audit/ })).toBeNull()
    expect(document.querySelector('[data-action="delete"]')).toBeNull()
  })

  it('göstergeler sunucu özetinden + 90 günlük türetimden; KpiCard kancaları', async () => {
    render(<DeploymentHistoryPanel />)
    await screen.findAllByText('v20.54.0')
    const stats = document.querySelector('[data-slot="deploy-stats"]')
    expect(within(stats.querySelector('[data-kpi="skipped"]')).getByText('13')).toBeInTheDocument()
    expect(within(stats.querySelector('[data-kpi="last30"]')).getByText('4')).toBeInTheDocument()
    // 90 gün: 4 sürüm değişimi, 1 geri alma → %25; geri alma varken uyarı tonu
    const rate = stats.querySelector('[data-kpi="rollbackRate"]')
    expect(rate).toHaveTextContent(/25/)
    expect(rate).toHaveAttribute('data-tone', 'warn')
  })

  it('zaman çizelgesi: ay grupları + kartlar, katlanan yeniden başlatma notu; kayıtlar görünüm açılmadan İSTENMEZ', async () => {
    render(<DeploymentHistoryPanel />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="deploy-card"]').length).toBe(4))
    expect(document.querySelectorAll('[data-slot="deploy-month"]').length).toBeGreaterThan(0)
    expect(screen.getByText(/1 yeniden başlatma gizlendi|1 restarts hidden/)).toBeInTheDocument()
    const rollback = document.querySelector('[data-slot="deploy-card"][data-kind="ROLLBACK"]')
    expect(within(rollback).getByText(/Geri alma|Rollback/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="deploy-card"][data-current="true"]')).not.toBeNull()
    expect(screen.getByText('bilet 123')).toBeInTheDocument()
    expect(api.admin.getDeployments).not.toHaveBeenCalled()
    // CSV bağlantısı ekranla aynı süzgeçle
    const csv = screen.getByRole('link', { name: /CSV/ })
    expect(csv.getAttribute('href')).toContain('/api/admin/deployments/export')
  })

  it('kart ayrıntısı açılır (ad kaydı ayırır); commit kısa gösterilir, TAM kopyalanır', async () => {
    render(<DeploymentHistoryPanel />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="deploy-card"]').length).toBe(4))
    const card = document.querySelector('[data-slot="deploy-card"][data-kind="ROLLBACK"]')
    const toggle = within(card).getByRole('button', { name: /^v20\.53\.2 · .+ — (Ayrıntılar|Details)$/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const details = card.querySelector('[data-slot="deploy-details"]')
    expect(details.querySelector('[data-field="commit"] code')).toHaveTextContent('fedcba9876543210')
    expect(within(card).getAllByRole('button', { name: /^fedcba98 — (Kopyala|Copy)$/ }).length).toBeGreaterThan(0)
  })

  it('istemci süzgeci: d_kind=ROLLBACK yalnız geri almayı gösterir; çip kaldırılınca hepsi döner', async () => {
    window.history.replaceState({}, '', '/?tab=health&sec=releases&d_kind=ROLLBACK')
    render(<DeploymentHistoryPanel />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="deploy-card"]').length).toBe(1))
    expect(document.querySelector('[data-slot="deploy-card"]')).toHaveAttribute('data-kind', 'ROLLBACK')
    const chip = document.querySelector('[data-chip="kind"]')
    expect(chip).toHaveAccessibleName(/(Süzgeci kaldır|Remove filter): /)
    fireEvent.click(chip)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="deploy-card"]').length).toBe(4))
  })

  it('arama süzgeci eşleşme yoksa "eşleşen yok" + Süzgeçleri temizle (sahte "kayıt yok" değil)', async () => {
    window.history.replaceState({}, '', '/?tab=health&sec=releases&d_q=zzz-yok')
    render(<DeploymentHistoryPanel />)
    await screen.findByText(/Süzgeçlerle eşleşen dağıtım yok|No deployments match these filters/)
    expect(screen.queryByText(/^(Kayıt yok|No records)$/)).toBeNull()
    fireEvent.click(screen.getAllByRole('button', { name: /^(Süzgeçleri temizle|Clear filters)$/ })[0])
    await waitFor(() => expect(document.querySelectorAll('[data-slot="deploy-card"]').length).toBe(4))
  })

  it('zaman çizelgesi ucu düşerse hata bandı + Tekrar dene (boş liste gibi gösterilmez)', async () => {
    api.admin.getDeploymentTimeline.mockResolvedValueOnce({ success: false, error: 'boom' })
    render(<DeploymentHistoryPanel />)
    const banner = await screen.findByText('boom')
    expect(banner.closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'danger')
    expect(screen.queryByText(/^(Kayıt yok|No records)$/)).toBeNull()
    fireEvent.click(screen.getAllByRole('button', { name: /^(Tekrar dene|Try again)$/ })[0])
    await waitFor(() => expect(document.querySelectorAll('[data-slot="deploy-card"]').length).toBe(4))
  })
})

describe('DeploymentHistoryPanel — kayıtlar görünümü', () => {
  it('sekme açılınca kayıtlar yüklenir; tablo sıralanabilir başlık taşır; sıralama uca gider', async () => {
    render(<DeploymentHistoryPanel />)
    await screen.findAllByText('v20.54.0')
    pressMenuTrigger(tab(/Kayıtlar|Records/))
    const table = await screen.findByTestId('deploy-table')
    expect(api.admin.getDeployments).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 25, sort: 'started_at', dir: 'desc' }))
    expect(within(table).getAllByRole('row').length).toBe(RECORDS.length + 1)
    expect(within(table).getByRole('columnheader', { name: /Başlangıç|Started/ })).toHaveAttribute('aria-sort', 'descending')
    fireEvent.click(within(table).getByRole('button', { name: /^(Sürüm|Version)$/ }))
    await waitFor(() => expect(api.admin.getDeployments).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'version', dir: 'asc' })))
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('d_view')).toBe('table'))
  })

  it('canEdit: silme yalnız MANUAL satırda, adı kaydı ayırır, onaylı', async () => {
    api.admin.deleteDeployment.mockResolvedValue({ success: true })
    window.history.replaceState({}, '', '/?tab=health&sec=releases&d_view=table')
    render(<DeploymentHistoryPanel canEdit />)
    const table = await screen.findByTestId('deploy-table')
    const dels = table.querySelectorAll('[data-action="delete"]')
    expect(dels.length).toBe(1)
    expect(dels[0]).toHaveAccessibleName(/^v20\.50\.0 · .+ — (Sil|Delete)$/)
    fireEvent.click(dels[0])
    await screen.findByText(/silinsin mi|Delete this manual/)
    fireEvent.click(screen.getAllByRole('button', { name: /^(Sil|Delete)$/ }).pop())
    await waitFor(() => expect(api.admin.deleteDeployment).toHaveBeenCalledWith(2))
  })

  it('kayıt ucu düşerse hata bandı; boş liste "Kayıt yok"', async () => {
    window.history.replaceState({}, '', '/?tab=health&sec=releases&d_view=table')
    api.admin.getDeployments.mockResolvedValueOnce({ success: false, error: 'kayit-hatasi' })
    render(<DeploymentHistoryPanel />)
    await screen.findByText('kayit-hatasi')
    expect(screen.queryByTestId('deploy-table')).toBeNull()
  })

  it("d_ URL param'ları okunur ve süzgeç olarak gider", async () => {
    window.history.replaceState({}, '', '/?tab=health&sec=releases&d_view=table&d_env=staging&d_source=MANUAL')
    render(<DeploymentHistoryPanel />)
    await waitFor(() => expect(api.admin.getDeployments).toHaveBeenCalledWith(expect.objectContaining({ env: 'staging', source: 'MANUAL' })))
    expect(api.admin.getDeploymentTimeline).toHaveBeenCalledWith('staging')
    await waitFor(() => expect(screen.getByRole('combobox', { name: /^(Ortam|Environment)$/ })).toHaveValue('staging'))
  })

  // Derin bağlantı regresyonu (2026-09-26): mount'ta sıfırlama d_page'i 1'e düşürüyordu. Standart: panel ön ayarı (25).
  it("d_page derin bağlantısı mount'ta korunur; boyut panel ön ayarından (25)", async () => {
    window.history.replaceState({}, '', '/?tab=health&sec=releases&d_view=table&d_page=3')
    api.admin.getDeployments.mockResolvedValue({ success: true, data: [ROW(3)], total: 90, page: 3, size: 25, total_pages: 4, environments: ['prod'] })
    render(<DeploymentHistoryPanel />)
    await waitFor(() => expect(api.admin.getDeployments).toHaveBeenCalled())
    expect(api.admin.getDeployments.mock.calls[0][0]).toMatchObject({ page: 3, size: 25 })
    await screen.findByRole('navigation', { name: /Sayfalama|Pagination/ })
    await new Promise(r => setTimeout(r, 400))
    for (const [args] of api.admin.getDeployments.mock.calls) expect(args).toMatchObject({ page: 3 })
    expect(screen.getByRole('button', { name: /^(Sayfa|Page) 3$/ })).toHaveAttribute('aria-current', 'page')
    expect(new URLSearchParams(window.location.search).get('d_page')).toBe('3')
  })

  it('dar kapta (telefon / kenar çubuğu açık tablet) tablo yerine kart listesi', async () => {
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(420)
    window.history.replaceState({}, '', '/?tab=health&sec=releases&d_view=table')
    render(<DeploymentHistoryPanel />)
    await waitFor(() => expect(document.querySelector('[data-slot="deploy-record-cards"]')).not.toBeNull())
    expect(screen.queryByTestId('deploy-table')).toBeNull()
    expect(document.querySelector('[data-slot="deploy-panel"]')).toHaveAttribute('data-narrow', 'true')
    // Süzgeçler Sheet'e iner
    expect(document.querySelector('[data-filters-trigger]')).not.toBeNull()
  })
})

describe('DeploymentHistoryPanel — yazma eylemleri, matris, sürüm notları', () => {
  it('elle kayıt: boş gönderimde alan hataları görünür, uç ÇAĞRILMAZ', async () => {
    render(<DeploymentHistoryPanel canEdit />)
    await screen.findAllByText('v20.54.0')
    fireEvent.click(byName(/Elle kayıt ekle|Add manual record/))
    const dialog = await screen.findByRole('dialog')
    const env = within(dialog).getByRole('textbox', { name: /Ortam|Environment/ })
    expect(env).toHaveValue('prod')                                  // varsayılan: koşan ortam
    fireEvent.change(env, { target: { value: 'PROD 1' } })
    fireEvent.click(within(dialog).getByRole('button', { name: /^(Kaydet|Save)$/ }))
    expect(within(dialog).getByText(/Küçük harf, rakam|lower-case letters, digits/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Sürüm X\.Y\.Z|X\.Y\.Z format \(e\.g/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Not zorunlu|A note is required/)).toBeInTheDocument()
    expect(within(dialog).getByRole('textbox', { name: /Ortam|Environment/ })).toHaveAttribute('aria-invalid', 'true')
    expect(api.admin.createDeployment).not.toHaveBeenCalled()
  })

  it('geri doldurma: onay metni aday sayısını taşır, onaylanınca uç çağrılır', async () => {
    api.admin.backfillDeployments.mockResolvedValue({ success: true, data: { inserted: 7, skippedExisting: 0 } })
    render(<DeploymentHistoryPanel canEdit />)
    const btn = await screen.findByRole('button', { name: /(Denetimden geri doldur|Backfill from audit) 7/ })
    fireEvent.click(btn)
    await screen.findByText(/7 açılış denetim kaydı|7 startup audit rows/)
    fireEvent.click(screen.getAllByRole('button', { name: /Denetimden geri doldur|Backfill from audit/ }).pop())
    await waitFor(() => expect(api.admin.backfillDeployments).toHaveBeenCalledWith('prod'))
  })

  it('matris: sekme açılmadan istek yok; açılınca yüklenir, ortam sütunu ve koşan sürüm rozeti', async () => {
    render(<DeploymentHistoryPanel />)
    await screen.findAllByText('v20.54.0')
    expect(api.admin.getDeploymentMatrix).not.toHaveBeenCalled()
    pressMenuTrigger(tab(/Ortamlar|Environments/))
    await waitFor(() => expect(api.admin.getDeploymentMatrix).toHaveBeenCalledWith(false))
    const matrix = await screen.findByRole('table')
    expect(within(matrix).getByRole('columnheader', { name: 'prod' })).toBeInTheDocument()
    expect(matrix.querySelector('[data-slot="deploy-current-badge"]')).not.toBeNull()
  })

  it('"Tüm sürüm notları" Sürüm notları görünümünü açar (yayın dizini uçtan)', async () => {
    render(<DeploymentHistoryPanel />)
    fireEvent.click(await screen.findByRole('button', { name: /Tüm sürüm notları|All release notes/ }))
    await waitFor(() => expect(tab(/Sürüm notları|Release notes/)).toHaveAttribute('aria-selected', 'true'))
    await waitFor(() => expect(api.system.getReleases).toHaveBeenCalled())
    expect(document.querySelector('[data-slot="release-notes"]')).not.toBeNull()
  })

  it('boş veri / bozuk yanıtta çökmez ve boş-durum metnini basar', async () => {
    api.admin.getDeploymentTimeline.mockResolvedValue({ success: true, data: [] })
    api.admin.getDeployments.mockResolvedValue({ success: true, data: [] })
    api.system.getVersion.mockResolvedValue(undefined)
    render(<DeploymentHistoryPanel />)
    await screen.findAllByText(/^(Kayıt yok|No records)$/)
    expect(screen.getByText(/Koşan sürüm bilgisi alınamadı|Could not load the running version/)).toBeInTheDocument()
  })
})
