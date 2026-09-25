import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import DeploymentHistoryPanel from '../components/admin/DeploymentHistoryPanel.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    system: { getVersion: vi.fn() },
    admin: {
      getDeployments: vi.fn(), getDeploymentTimeline: vi.fn(), getDeploymentMatrix: vi.fn(),
      getDeploymentsCsvUrl: vi.fn((p) => `/api/admin/deployments/export?env=${p?.env ?? ''}`),
      createDeployment: vi.fn(), backfillDeployments: vi.fn(), deleteDeployment: vi.fn(),
    },
  }),
}))

import { api } from '../api/client'

const ROW = (id, over = {}) => ({
  id, startedAt: '2026-09-11T00:00:00Z', recordedAt: '2026-09-11T00:00:00Z', readyAt: null, lastSeenAt: null,
  endedAt: null, endReason: null, environment: 'prod', version: '20.54.0', previousVersion: '20.53.2', kind: 'UPGRADE',
  source: 'STARTUP', commit: 'abcdef0123', commitShort: 'abcdef01', helm: { release: 'sm', revision: 42 },
  instanceId: 'i1', hostname: 'h1', pod: 'pod-1', node: 'node-1', createdBy: null, note: null, current: true,
  releasedAt: '2026-09-10T22:00:00Z', leadTimeSeconds: 7200, ...over,
})

const TIMELINE = {
  environment: 'prod',
  current: { version: '20.54.0', since: '2026-09-11T00:00:00Z', kind: 'UPGRADE', previousVersion: '20.53.2', restartsSince: 1 },
  transitions: [ROW(3), ROW(1, { id: 1, version: '20.53.2', previousVersion: null, kind: 'FIRST_SEEN', current: false, startedAt: '2026-09-01T00:00:00Z' })],
  restartCount: 1, unknownCount: 0, total: 3,
  summary: { deploymentsLast30d: 2, restartsLast7d: 1, rollbacks: 0, avgReleaseLagSeconds: 5400, skippedReleases: 3 },
  environments: ['prod', 'staging'],
  backfillCandidates: 7,
}

const VERSION = { version: '20.54.0', commit: 'abcdef0123', commitShort: 'abcdef01', environment: 'prod', mismatch: true,
  imageRef: 'registry.example.com/site-monitor:20.54.0', helm: { release: 'sm', revision: 42 }, instance: { pod: 'pod-1', node: 'node-1' }, uptimeSeconds: 600 }

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/')
  api.system.getVersion.mockResolvedValue({ success: true, data: VERSION })
  api.admin.getDeploymentTimeline.mockResolvedValue({ success: true, data: TIMELINE })
  api.admin.getDeployments.mockResolvedValue({ success: true, data: [ROW(3), ROW(2, { id: 2, source: 'MANUAL', kind: 'UNKNOWN', version: '20.50.0', current: false, note: 'bilet 123', createdBy: 'ops' })], total: 2, page: 1, size: 20, total_pages: 1, environments: ['prod', 'staging'] })
  api.admin.getDeploymentMatrix.mockResolvedValue({ success: true, data: { environments: ['prod'], releases: [{ version: '20.54.0', releasedAt: '2026-09-10T22:00:00Z', bump: 'minor', deployedIn: { prod: '2026-09-11T00:00:00Z' }, neverDeployed: false }], truncated: false } })
})

describe('DeploymentHistoryPanel', () => {
  it('koşan sürüm kartı + özet şeridi + zaman çizelgesi + tablo (okuma)', async () => {
    render(<DeploymentHistoryPanel canEdit={false} />)
    await waitFor(() => expect(api.admin.getDeploymentTimeline).toHaveBeenCalled())
    await screen.findByText(/Uyarı|differ|farklı/)                 // mismatch bandı
    expect(screen.getAllByText('v20.54.0').length).toBeGreaterThan(0)
    expect(screen.getByText('3')).toBeInTheDocument()               // atlanan sürüm
    expect(screen.getByText(/1 yeniden başlatma gizlendi|1 restarts hidden/)).toBeInTheDocument()
    expect(screen.getAllByText(/Yükseltme|Upgrade/).length).toBeGreaterThan(0)
    expect(screen.getByText('bilet 123')).toBeInTheDocument()
    // Yazma düğmeleri YOK
    expect(screen.queryByText(/Elle kayıt ekle|Add manual record/)).toBeNull()
    expect(screen.queryByText(/Denetimden geri doldur|Backfill from audit/)).toBeNull()
    // CSV bağlantısı ekranla aynı süzgeçle
    const csv = document.querySelector('a[download="deployment-history.csv"]')
    expect(csv.getAttribute('href')).toContain('/api/admin/deployments/export')
  })

  it('canEdit: elle kayıt modalı doğrulamalı, silme yalnız MANUAL satırda ve onaylı', async () => {
    api.admin.deleteDeployment.mockResolvedValue({ success: true })
    api.admin.createDeployment.mockResolvedValue({ success: true, data: {} })
    render(<DeploymentHistoryPanel canEdit />)
    await screen.findByText('bilet 123')
    // Silme düğmesi: yalnız MANUAL satır (1 adet)
    const dels = document.querySelectorAll('.deploy-table [data-slot="button"][data-variant="destructive"]')
    expect(dels.length).toBe(1)
    // 2026-09-25 (R15): ADI kaydı ayırır (sürüm + başlangıç); ipucu kısa kalır
    expect(dels[0]).toHaveAccessibleName(/^v20\.50\.0 · .+ — (Sil|Delete)$/)
    expect(dels[0]).toHaveAttribute('title', expect.stringMatching(/^(Sil|Delete)$/))
    fireEvent.click(dels[0])
    await screen.findByText(/silinsin mi|Delete this manual/)
    fireEvent.click(screen.getAllByRole('button', { name: /^(Sil|Delete)$/ }).pop())   // sonuncusu: onay diyaloğu (portal)
    await waitFor(() => expect(api.admin.deleteDeployment).toHaveBeenCalledWith(2))
    // Elle kayıt: zorunlu alanlar boşken Kaydet kapalı
    fireEvent.click(screen.getByText(/Elle kayıt ekle|Add manual record/))
    const save = await screen.findByRole('button', { name: /^(Kaydet|Save)$/ })
    expect(save).toBeDisabled()
  })

  it('geri doldurma: onay metni aday sayısını taşır, onaylanınca uç çağrılır', async () => {
    api.admin.backfillDeployments.mockResolvedValue({ success: true, data: { inserted: 7, skippedExisting: 0 } })
    render(<DeploymentHistoryPanel canEdit />)
    await screen.findByText(/Denetimden geri doldur \(7\)|Backfill from audit \(7\)/)
    fireEvent.click(screen.getByText(/Denetimden geri doldur \(7\)|Backfill from audit \(7\)/))
    await screen.findByText(/7 açılış denetim kaydı|7 startup audit rows/)
    fireEvent.click(screen.getAllByRole('button', { name: /Denetimden geri doldur|Backfill from audit/ }).pop())
    await waitFor(() => expect(api.admin.backfillDeployments).toHaveBeenCalledWith('prod'))
  })

  it('boş veri / bozuk yanıtta çökmez ve boş-durum metnini basar', async () => {
    api.admin.getDeploymentTimeline.mockResolvedValue({ success: true, data: [] })
    api.admin.getDeployments.mockResolvedValue({ success: true, data: [] })
    api.system.getVersion.mockResolvedValue(undefined)
    render(<DeploymentHistoryPanel />)
    await waitFor(() => expect(api.admin.getDeployments).toHaveBeenCalled())
    await screen.findAllByText(/Kayıt yok|No records/)
  })

  it('d_ URL param\'ları okunur ve süzgeç olarak gider', async () => {
    window.history.replaceState({}, '', '/?tab=health&sec=releases&d_env=staging&d_source=MANUAL')
    render(<DeploymentHistoryPanel />)
    await waitFor(() => expect(api.admin.getDeployments).toHaveBeenCalledWith(expect.objectContaining({ env: 'staging', source: 'MANUAL' })))
    expect(api.admin.getDeploymentTimeline).toHaveBeenCalledWith('staging')
  })
})
