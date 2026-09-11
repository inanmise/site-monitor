import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import VersionChip from '../components/VersionChip.jsx'
import { LAST_SEEN_KEY } from '../utils/releaseUi.js'
import { _resetVersionCache } from '../components/VersionPopover.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ system: { getVersion: vi.fn(), getReleaseNotes: vi.fn() } }),
}))
vi.mock('../contexts/BrandingProvider.jsx', () => ({ useAppVersion: () => '20.54.0' }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: (r) => r === 'release_history.read', canEdit: () => false, canExecute: () => false, perms: {} }),
}))

import { api } from '../api/client'

const VERSION = {
  version: '20.54.0', commit: 'abcdef0123456789', commitShort: 'abcdef01', environment: 'prod',
  mismatch: false, helm: { release: 'site-monitor', revision: 42, chartVersion: '20.54.0' },
  instance: { id: 'i1', hostname: 'h', pod: 'p', node: 'n' },
  startedAt: '2026-09-11T00:00:00Z', uptimeSeconds: 3660,
  live: { version: '20.54.0', since: '2026-09-11T00:00:05Z', kind: 'UPGRADE', previousVersion: '20.53.2', restartsSince: 0 },
  release: { version: '20.54.0', releasedAt: '2026-09-10T22:00:00Z', bump: 'minor', breaking: false, counts: { feat: 1 },
    highlights: [{ type: 'feat', scope: 'ui', subject: 'sürüm çipi' }], changes: [{ type: 'feat', scope: 'ui', subject: 'sürüm çipi', sha: '1' }] },
  releaseLagSeconds: 7200,
}

beforeEach(() => {
  vi.clearAllMocks()
  _resetVersionCache()
  try { localStorage.clear() } catch {}
  api.system.getVersion.mockResolvedValue({ success: true, data: VERSION })
  api.system.getReleaseNotes.mockResolvedValue({ success: true, data: { items: [{ version: '20.54.0' }] } })
})

describe('VersionChip', () => {
  it('çip düğme olarak çizilir; ilk ziyarette nokta YOK ve damga yazılır', () => {
    render(<VersionChip onTabChange={vi.fn()} />)
    const chip = screen.getByRole('button', { name: /v20\.54\.0/ })
    expect(chip.getAttribute('aria-haspopup')).toBe('dialog')
    expect(document.querySelector('.sb-version-dot')).toBeNull()
    expect(localStorage.getItem(LAST_SEEN_KEY)).toBe('20.54.0')
  })

  it('E1: eski damga farklıysa nokta çıkar; açınca sunucu verisi gelir, damga güncellenir, nokta söner', async () => {
    localStorage.setItem(LAST_SEEN_KEY, '20.53.2')
    render(<VersionChip onTabChange={vi.fn()} />)
    expect(document.querySelector('.sb-version-dot')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /v20\.54\.0/ }))
    await waitFor(() => expect(api.system.getVersion).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(document.querySelector('.sb-version-pop')).not.toBeNull())
    await screen.findByText(/prod/)
    expect(screen.getByText(/Yükseltme|Upgrade/)).toBeInTheDocument()
    expect(screen.getByText(/rev 42/)).toBeInTheDocument()
    // "son ziyaretten beri" şeridi eski damgayla çekilir
    await waitFor(() => expect(api.system.getReleaseNotes).toHaveBeenCalledWith('20.53.2'))
    expect(localStorage.getItem(LAST_SEEN_KEY)).toBe('20.54.0')
    expect(document.querySelector('.sb-version-dot')).toBeNull()
  })

  it('"Yenilikler" Yardım sekmesine view=releases ile, "Dağıtım geçmişi" Sağlık sekmesine sec=releases ile götürür', async () => {
    const onTabChange = vi.fn()
    render(<VersionChip onTabChange={onTabChange} />)
    fireEvent.click(screen.getByRole('button', { name: /v20\.54\.0/ }))
    await waitFor(() => expect(document.querySelector('.sb-version-pop')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /Yenilikler|What's new/ }))
    expect(onTabChange).toHaveBeenCalledWith('help', { view: 'releases' })
    expect(document.querySelector('.sb-version-pop')).toBeNull()   // gezinince kapanır
    fireEvent.click(screen.getByRole('button', { name: /v20\.54\.0/ }))
    await waitFor(() => expect(document.querySelector('.sb-version-pop')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /Dağıtım geçmişi|Deployment history/ }))
    expect(onTabChange).toHaveBeenCalledWith('health', { sec: 'releases' })
  })

  it('sunucu hatası → yalnız sürüm + hata satırı, popover yine açılır; Escape kapatır', async () => {
    api.system.getVersion.mockResolvedValue({ success: false, error: 'boom' })
    render(<VersionChip onTabChange={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /v20\.54\.0/ }))
    await waitFor(() => expect(document.querySelector('.sb-version-pop')).not.toBeNull())
    expect(screen.getByText(/alınamadı|Could not load/)).toBeInTheDocument()
    expect(document.querySelector('.sb-version-pop-ver').textContent).toBe('v20.54.0')
    await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }) })
    expect(document.querySelector('.sb-version-pop')).toBeNull()
  })

  it('60 sn önbellek: iki açılış tek istek', async () => {
    render(<VersionChip onTabChange={vi.fn()} />)
    const chip = screen.getByRole('button', { name: /v20\.54\.0/ })
    fireEvent.click(chip)
    await waitFor(() => expect(api.system.getVersion).toHaveBeenCalledTimes(1))
    fireEvent.click(chip)   // kapat
    fireEvent.click(chip)   // tekrar aç
    await waitFor(() => expect(document.querySelector('.sb-version-pop')).not.toBeNull())
    expect(api.system.getVersion).toHaveBeenCalledTimes(1)
  })
})
