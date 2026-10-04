import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'

/**
 * 7/24 sekmesi görünüm seçimi (2026-10-04): 7/24 operatörü KONSOLLA açılır (konsol + kapsam sekmeleri), global görücü
 * kapsamla açılır ama konsola geçebilir, sıradan kullanıcı yalnız kapsamı görür (sekme listesi YOK). Görünüm `n_view`.
 */
vi.mock('../pages/NocCoveragePage.jsx', () => ({ default: (p) => <div data-testid="coverage" data-noc-operator={String(!!p.nocOperator)} /> }))
vi.mock('../components/noc/console/NocConsole.jsx', () => ({ default: (p) => <div data-testid="console" data-can-write={String(!!p.canWrite)} /> }))
import NocPage, { defaultNocView } from '../pages/NocPage.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

beforeEach(() => window.history.replaceState({}, '', '/?tab=noc'))
afterEach(() => window.history.replaceState({}, '', '/'))

describe('NocPage', () => {
  it('7/24 operatörü: konsol varsayılan, kapsam sekmesine geçer; arama izni konsola iletilir', async () => {
    render(<NocPage nocOperator nocCanWrite />)
    expect(screen.getByRole('tab', { name: /24\/7 console/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('console')).toHaveAttribute('data-can-write', 'true')
    pressMenuTrigger(screen.getByRole('tab', { name: /24\/7 coverage/ }))
    await waitFor(() => expect(screen.getByTestId('coverage')).toHaveAttribute('data-noc-operator', 'true'))
    await waitFor(() => expect(window.location.search).toMatch(/n_view=coverage/))
  })

  it('global görücü (admin/AUDIT): kapsamla açılır, konsol sekmesi var', () => {
    render(<NocPage globalViewer />)
    expect(screen.getByRole('tab', { name: /24\/7 coverage/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: /24\/7 console/ })).toBeInTheDocument()
  })

  it('sıradan kullanıcı: yalnız kapsam, sekme listesi yok — URL n_view=console yok sayılır', () => {
    window.history.replaceState({}, '', '/?tab=noc&n_view=console')
    render(<NocPage />)
    expect(screen.queryByRole('tablist')).toBeNull()
    expect(screen.getByTestId('coverage')).toBeInTheDocument()
    expect(screen.queryByTestId('console')).toBeNull()
  })

  it('derin bağlantı n_view=console global görücüde konsolu açar; varsayılan görünüm kuralı', () => {
    window.history.replaceState({}, '', '/?tab=noc&n_view=console')
    render(<NocPage globalViewer />)
    expect(screen.getByTestId('console')).toBeInTheDocument()
    expect(defaultNocView(true)).toBe('console')
    expect(defaultNocView(false)).toBe('coverage')
  })
})
