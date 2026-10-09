import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, act, waitFor } from '@testing-library/react'

const getPermissions = vi.hoisted(() => vi.fn())
vi.mock('../api/client', () => ({ api: { me: { getPermissions } } }))

import { PermissionsProvider, usePermissions } from '../contexts/PermissionsProvider.jsx'

/**
 * Yeniden çizim kilidi (2026-10-09): bağlam değeri useMemo'lu, içeriği DEĞİŞMEYEN yoklama state'i yenilemez.
 * Eskiden 60 sn'lik yoklama her seferinde yeni nesne yazıyor ve değer her çizimde yeni nesne oluyordu — her
 * `usePermissions` tüketicisi dakikada bir boşuna yeniden çiziliyordu.
 */
let renders = 0
let latest = null
function Consumer() {
  renders++
  latest = usePermissions()
  return <span data-testid="can">{String(latest.canView('alerts.read'))}</span>
}

beforeEach(() => { renders = 0; latest = null; getPermissions.mockReset() })

describe('PermissionsProvider — eşit yetki yanıtı yeniden çizim üretmez', () => {
  it('aynı içerikli (yeni nesne) yanıt tüketiciyi yeniden çizmez; perms kimliği korunur; değişen yanıt çizer', async () => {
    getPermissions.mockImplementation(async () => ({ success: true, data: { 'alerts.read': { view: true } } }))
    render(<PermissionsProvider user={{ username: 'u' }}><Consumer /></PermissionsProvider>)
    await waitFor(() => expect(screen.getByTestId('can')).toHaveTextContent('true'))
    const settled = renders
    const permsBefore = latest.perms

    await act(async () => { await latest.refresh() })   // aynı içerik, yeni nesne
    await act(async () => { await latest.refresh() })
    expect(getPermissions).toHaveBeenCalledTimes(3)
    expect(renders).toBe(settled)
    expect(latest.perms).toBe(permsBefore)

    getPermissions.mockImplementation(async () => ({ success: true, data: { 'alerts.read': { view: false } } }))
    await act(async () => { await latest.refresh() })
    expect(renders).toBe(settled + 1)
    expect(screen.getByTestId('can')).toHaveTextContent('false')
  })

  it('üst bileşen yeniden çizilince bağlam değeri aynı kalır (useMemo) — memo tüketiciler yeniden çizilmez', async () => {
    getPermissions.mockResolvedValue({ success: true, data: {} })
    const { rerender } = render(<PermissionsProvider user={{ username: 'u' }}><Consumer /></PermissionsProvider>)
    await waitFor(() => expect(getPermissions).toHaveBeenCalled())
    await act(async () => {})
    const settled = renders
    const user = { username: 'u' }
    // Aynı çocuk ÖĞESİ (aynı referans) — React bağlam değişmedikçe çocuğu atlar.
    const child = <Consumer />
    rerender(<PermissionsProvider user={user}>{child}</PermissionsProvider>)
    await act(async () => {})                               // yeni kullanıcı nesnesi → yeni refresh → yoklama (aynı içerik)
    const afterFirst = renders
    expect(afterFirst - settled).toBeLessThanOrEqual(1)
    rerender(<PermissionsProvider user={user}>{child}</PermissionsProvider>)
    await act(async () => {})
    expect(renders).toBe(afterFirst)                        // değer aynı (useMemo) → tüketici çizilmez
  })

  it('kullanıcı yokken boş snapshot; tekrar temizleme yeniden çizim üretmez', async () => {
    render(<PermissionsProvider user={null}><Consumer /></PermissionsProvider>)
    await act(async () => {})
    const settled = renders
    await act(async () => { await latest.refresh() })
    expect(getPermissions).not.toHaveBeenCalled()
    expect(renders).toBe(settled)
    expect(latest.perms).toEqual({})
  })
})
