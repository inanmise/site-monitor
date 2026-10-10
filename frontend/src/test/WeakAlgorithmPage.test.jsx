import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { EN } from '../i18n/en.js'   // varsayılan arayüz dili İngilizce (2026-10-02)

/**
 * Zayıf Algoritma sayfası sekmeleri (2026-10-10): varsayılan "Bulgular" (eski rapor aynen), "Kripto envanteri & PQC"
 * tembel yüklenir; seçim URL'de `ci_view=crypto` (varsayılan yazılmaz) — paylaşılan bağlantı doğrudan sekmeyi açar.
 */
vi.mock('../components/admin/WeakAlgorithmReport.jsx', () => ({ default: () => <div data-testid="wa-findings">findings</div> }))
vi.mock('../components/cryptoinv/CryptoInventoryView.jsx', () => ({ default: () => <div data-testid="crypto-view">crypto</div> }))

import WeakAlgorithmPage from '../components/admin/WeakAlgorithmPage.jsx'

describe('WeakAlgorithmPage', () => {
  beforeEach(() => { window.history.replaceState({}, '', '/?tab=weakalgo') })

  it('varsayılan sekme bulgular; envanter sekmesi seçilince tembel görünüm açılır ve URL\'ye yazılır', async () => {
    render(<WeakAlgorithmPage />)
    expect(screen.getByRole('tablist', { name: EN['cinv.tabs'] })).toBeTruthy()
    expect(screen.getByTestId('wa-findings')).toBeTruthy()
    expect(screen.queryByTestId('crypto-view')).toBeNull()
    fireEvent.mouseDown(screen.getByRole('tab', { name: EN['cinv.tab.crypto'] }), { button: 0 })
    expect(await screen.findByTestId('crypto-view')).toBeTruthy()
    expect(screen.queryByTestId('wa-findings')).toBeNull()
    await waitFor(() => expect(window.location.search).toContain('ci_view=crypto'))
    fireEvent.mouseDown(screen.getByRole('tab', { name: EN['cinv.tab.findings'] }), { button: 0 })
    expect(await screen.findByTestId('wa-findings')).toBeTruthy()
    await waitFor(() => expect(window.location.search).not.toContain('ci_view'))
  })

  it('derin bağlantı ?ci_view=crypto doğrudan envanteri açar', async () => {
    window.history.replaceState({}, '', '/?tab=weakalgo&ci_view=crypto')
    render(<WeakAlgorithmPage />)
    expect(await screen.findByTestId('crypto-view')).toBeTruthy()
    expect(screen.getByRole('tab', { name: EN['cinv.tab.crypto'] }).getAttribute('aria-selected')).toBe('true')
  })
})
