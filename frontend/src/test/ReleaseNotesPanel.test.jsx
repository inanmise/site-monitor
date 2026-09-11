import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import ReleaseNotesPanel from '../components/ReleaseNotesPanel.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ system: { getReleases: vi.fn() } }),
}))
vi.mock('../contexts/BrandingProvider.jsx', () => ({ useAppVersion: () => '20.54.0' }))

import { api } from '../api/client'

const REL = (version, extra = {}) => ({
  version, tag: `v${version}`, releasedAt: '2026-09-10T20:00:00Z', commit: 'abcdef0123', commitShort: 'abcdef01',
  prevVersion: '20.53.0', bump: 'minor', breaking: false, counts: { feat: 1, fix: 1, other: 0 },
  changes: [
    { type: 'feat', scope: 'ui', breaking: false, sha: 'a1', subject: 'yeni sürüm çipi' },
    { type: 'fix', scope: null, breaking: false, sha: 'b2', subject: 'timeout retry kaldırıldı' },
  ],
  truncated: false, omitted: 0, deployedHere: null, collapsedPatches: [], ...extra,
})

const PAGE = {
  items: [
    REL('20.54.0', { deployedHere: '2026-09-11T00:00:05Z', collapsedPatches: ['20.53.1', '20.53.2'] }),
    REL('20.53.0', { bump: 'minor' }),
  ],
  page: 1, size: 25, total: 2, density: 'deployed', currentVersion: '20.54.0', environment: 'prod',
  releaseIndex: { loaded: true, count: 629, newestVersion: '20.54.0', generatedAt: '2026-09-11T00:00:00Z' },
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear() } catch {}
  api.system.getReleases.mockResolvedValue({ success: true, data: PAGE })
})

describe('ReleaseNotesPanel', () => {
  it('katlanmış listeyi çizer: ŞU AN rozeti, dağıtım bilgisi, katlanan yama çipi', async () => {
    render(<ReleaseNotesPanel />)
    await waitFor(() => expect(api.system.getReleases).toHaveBeenCalled())
    expect(api.system.getReleases.mock.calls[0][0]).toMatchObject({ density: 'deployed', type: 'all', page: 1 })
    await screen.findByText('v20.54.0')
    expect(screen.getByText(/ŞU AN|CURRENT/)).toBeInTheDocument()
    expect(screen.getByText(/\+2 yama|\+2 patches/)).toBeInTheDocument()
    expect(screen.getByText(/hiç dağıtılmadı|Never deployed/)).toBeInTheDocument()   // 20.53.0
  })

  it('satıra tıklayınca değişiklikler gruplu açılır; katlanan yama çipi "tüm sürümler" + aramaya geçer', async () => {
    render(<ReleaseNotesPanel />)
    await screen.findByText('v20.54.0')
    fireEvent.click(screen.getByText('v20.54.0').closest('[role="button"]'))
    expect(screen.getByText('yeni sürüm çipi')).toBeInTheDocument()
    expect(screen.getByText('timeout retry kaldırıldı')).toBeInTheDocument()
    expect(document.querySelector('.rel-group--feat')).not.toBeNull()
    fireEvent.click(screen.getByText(/\+2 yama|\+2 patches/))
    await waitFor(() => expect(api.system.getReleases).toHaveBeenCalledWith(expect.objectContaining({ density: 'all', q: '20.53.1' })))
  })

  it('dizin yüklü değilse uyarı, hata yanıtında hata bandı', async () => {
    api.system.getReleases.mockResolvedValue({ success: true, data: { ...PAGE, items: [], total: 0, releaseIndex: { loaded: false } } })
    const r1 = render(<ReleaseNotesPanel />)
    await screen.findByText(/Yayın dizini bu imajda yok|release index is not in this image/)
    r1.unmount()
    api.system.getReleases.mockResolvedValue({ success: false, error: 'boom' })
    render(<ReleaseNotesPanel />)
    await screen.findByText('boom')
  })

  it('E1 şeridi: eski damga varsa "son ziyaretinizden beri" bandı çıkar', async () => {
    localStorage.setItem('sm.release.lastSeenVersion', '20.53.0')
    render(<ReleaseNotesPanel />)
    await screen.findByText(/Son ziyaretinizden|since your last visit/)
  })
})
