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

  it('2026-10-12: ilk snapshot gelene kadar loaded=false (boş {} "yetki yok" ile karışmaz); hata önceki durumu korur', async () => {
    let resolve
    getPermissions.mockImplementation(() => new Promise((r) => { resolve = r }))
    render(<PermissionsProvider user="u"><Consumer /></PermissionsProvider>)
    expect(latest.loaded).toBe(false)
    await act(async () => { resolve({ success: true, data: { 'alerts.read': { view: true } } }) })
    await waitFor(() => expect(latest.loaded).toBe(true))
    getPermissions.mockRejectedValue(new Error('ağ'))
    await act(async () => { await latest.refresh() })
    expect(latest.loaded).toBe(true)
    expect(latest.canView('alerts.read')).toBe(true)
  })

  it('2026-10-13: görüş kapsamı (scope) yanıttan okunur, tazelenince değişir; eski sunucu göndermezse null', async () => {
    getPermissions.mockResolvedValue({ success: true, data: { 'alerts.read': { view: true } },
      scope: { monitoring_all: true, noc_operator: true, noc_console: true } })
    render(<PermissionsProvider user="u"><Consumer /></PermissionsProvider>)
    await waitFor(() => expect(latest.scope).toEqual({ monitoring_all: true, noc_operator: true, noc_console: true }))
    // 7/24 takımından çıkarıldı: bir sonraki yoklamada kapsam daralır (yeniden giriş gerekmez)
    getPermissions.mockResolvedValue({ success: true, data: { 'alerts.read': { view: true } },
      scope: { monitoring_all: false, noc_operator: false, noc_console: false } })
    await act(async () => { await latest.refresh() })
    expect(latest.scope.noc_console).toBe(false)
    getPermissions.mockResolvedValue({ success: true, data: { 'alerts.read': { view: true } } })
    await act(async () => { await latest.refresh() })
    expect(latest.scope).toBeNull()
  })

  it('2026-10-12: sağlayıcı yoksa loaded=true (eski davranış)', () => {
    render(<Consumer />)
    expect(latest.loaded).toBe(true)
    expect(latest.failed).toBe(false)
  })

  it('inceleme 4: ilk snapshot OKUNAMAZSA failed=true (sonsuz yükleniyor değil); başarılı yeniden okuma temizler', async () => {
    getPermissions.mockResolvedValueOnce({ success: false, error: 'x' })
    render(<PermissionsProvider user="u"><Consumer /></PermissionsProvider>)
    await waitFor(() => expect(latest.failed).toBe(true))
    expect(latest.loaded).toBe(false)
    getPermissions.mockResolvedValueOnce({ success: true, data: { 'alerts.read': { view: true } } })
    await act(async () => { await latest.refresh() })
    expect(latest.loaded).toBe(true)
    expect(latest.failed).toBe(false)
    expect(latest.canView('alerts.read')).toBe(true)
  })

  it('inceleme 4: çıkıştan SONRA gelen eski yanıt uygulanmaz; başka kullanıcı önceki snapshot ile bir an bile çalışmaz', async () => {
    let resolveA
    getPermissions.mockImplementationOnce(() => new Promise((r) => { resolveA = r }))
    const { rerender } = render(<PermissionsProvider user="ayse"><Consumer /></PermissionsProvider>)
    rerender(<PermissionsProvider user={null}><Consumer /></PermissionsProvider>)        // çıkış
    await act(async () => { resolveA({ success: true, data: { 'alerts.read': { view: true } } }) })
    expect(latest.loaded).toBe(false)
    expect(latest.canView('alerts.read')).toBe(false)                                  // geç yanıt yazılmadı

    getPermissions.mockResolvedValueOnce({ success: true, data: { 'alerts.read': { view: true } } })
    rerender(<PermissionsProvider user="ayse"><Consumer /></PermissionsProvider>)
    await waitFor(() => expect(latest.canView('alerts.read')).toBe(true))
    let resolveB
    getPermissions.mockImplementationOnce(() => new Promise((r) => { resolveB = r }))
    rerender(<PermissionsProvider user="mehmet"><Consumer /></PermissionsProvider>)
    await waitFor(() => expect(latest.loaded).toBe(false))
    expect(latest.canView('alerts.read')).toBe(false)                                  // önceki kullanıcının yetkisi düştü
    await act(async () => { resolveB({ success: true, data: {} }) })
    expect(latest.loaded).toBe(true)
    expect(latest.canView('alerts.read')).toBe(false)
  })

  it('inceleme 5: kullanıcı değişince YENİ kullanıcının İLK çizimi bile önceki kullanıcının yetkisini görmez', async () => {
    const seen = []
    function Spy({ who }) {
      const p = usePermissions()
      seen.push([who, p.canView('alerts.read'), p.loaded])
      return null
    }
    getPermissions.mockResolvedValueOnce({ success: true, data: { 'alerts.read': { view: true } } })
    const { rerender } = render(<PermissionsProvider user="ayse"><Spy who="ayse" /></PermissionsProvider>)
    await waitFor(() => expect(seen.some(([w, can]) => w === 'ayse' && can)).toBe(true))
    getPermissions.mockImplementationOnce(() => new Promise(() => {}))   // yeni kullanıcının yanıtı gelmesin
    rerender(<PermissionsProvider user="mehmet"><Spy who="mehmet" /></PermissionsProvider>)
    const mehmet = seen.filter(([w]) => w === 'mehmet')
    expect(mehmet.length).toBeGreaterThan(0)
    expect(mehmet.every(([, can, loaded]) => can === false && loaded === false)).toBe(true)
  })
})
