import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { render, screen } from './test-utils.jsx'
import { inventoryAddedDomain, announceInventoryAdded, INVENTORY_ADDED_EVENT } from '../utils/inventoryEvent.js'
import { useNewDomainWarmup, WARMUP_DELAYS } from '../hooks/useNewDomainWarmup.js'
import CertificateCard from '../components/CertificateCard.jsx'

/**
 * 2026-09-28 (kullanıcı: "yeni eklenen alan adının kartında sağlık, açık alarm, sorumlu kişi bir süre boş görünüyor,
 * sonradan geliyor"): ekleme ucu ilk kontrolü arka planda başlatır; Genel Bakış `sm:inventory-added` ile o alan adının
 * verisi gelene dek kısa aralıklarla tazeler ve kart bu sürede boş değil "hesaplanıyor" gösterir.
 */
describe('inventoryAddedDomain — yeni kart doğuran yazmalar', () => {
  it('POST /admin/inventory başarılı yanıtı alan adını döner', () => {
    expect(inventoryAddedDomain('/admin/inventory', { method: 'POST' }, { success: true, data: { domain: 'new.example.com' } }))
      .toBe('new.example.com')
  })
  it('geri yükleme ve aktarım da sayılır', () => {
    const body = { success: true, data: { domain: 'moved.example.com' } }
    expect(inventoryAddedDomain('/admin/inventory/12/restore', { method: 'POST' }, body)).toBe('moved.example.com')
    expect(inventoryAddedDomain('/admin/inventory/12/transfer', { method: 'POST' }, body)).toBe('moved.example.com')
    expect(inventoryAddedDomain('/admin/inventory/12/transfer-ug', { method: 'POST' }, body)).toBe('moved.example.com')
  })
  it('GET, güncelleme, toplu ve başka uçlar sayılmaz; alan adı yoksa null', () => {
    const body = { success: true, data: { domain: 'x.example.com' } }
    expect(inventoryAddedDomain('/admin/inventory', { method: 'GET' }, body)).toBeNull()
    expect(inventoryAddedDomain('/admin/inventory/12', { method: 'PUT' }, body)).toBeNull()
    expect(inventoryAddedDomain('/admin/inventory/bulk', { method: 'POST' }, body)).toBeNull()
    expect(inventoryAddedDomain('/monitoring/http', { method: 'POST' }, body)).toBeNull()
    expect(inventoryAddedDomain('/admin/inventory', { method: 'POST' }, { success: true, data: {} })).toBeNull()
  })
  it('olay alan adıyla yayılır', () => {
    const seen = vi.fn()
    window.addEventListener(INVENTORY_ADDED_EVENT, seen)
    announceInventoryAdded('new.example.com')
    announceInventoryAdded(null)
    window.removeEventListener(INVENTORY_ADDED_EVENT, seen)
    expect(seen).toHaveBeenCalledTimes(1)
    expect(seen.mock.calls[0][0].detail).toEqual({ domain: 'new.example.com' })
  })
})

const fresh = { domain: 'new.example.com', port: 443, alert_level: null, days_remaining: null, checked_at: null, team_name: 'Takım A' }

describe('CertificateCard — ısınma durumu', () => {
  it('ilk kontrol sürerken alt satır "İlk kontrol yapılıyor…" (boş "henüz kontrol edilmedi" değil)', () => {
    render(<CertificateCard cert={fresh} onClick={vi.fn()} warming />)
    const at = document.querySelector('[data-slot="cert-checked-at"]')
    expect(at).toHaveAttribute('data-warming', 'true')
    expect(at).toHaveTextContent(/İlk kontrol yapılıyor|Running the first check/)
  })
  it('Zengin görünümde ekler gelmemişken bekleme iskeleti; ekler gelince iskelet kalkar', () => {
    const { rerender } = render(<CertificateCard cert={fresh} onClick={vi.fn()} warming extrasPending />)
    expect(document.querySelector('[data-slot="cert-extras-pending"]')).not.toBeNull()
    rerender(<CertificateCard cert={{ ...fresh, checked_at: '2026-09-28T01:00:00' }} onClick={vi.fn()}
      extra={{ health: { score: 14, max: 14, findings: [] }, alerts: { open: 0 }, contacts: [] }} />)
    expect(document.querySelector('[data-slot="cert-extras-pending"]')).toBeNull()
  })
  it('ısınma yokken eski davranış: "henüz kontrol edilmedi", iskelet yok', () => {
    render(<CertificateCard cert={fresh} onClick={vi.fn()} />)
    expect(document.querySelector('[data-slot="cert-checked-at"]')).toHaveAttribute('data-never', 'true')
    expect(document.querySelector('[data-slot="cert-extras-pending"]')).toBeNull()
    expect(screen.queryByRole('status', { name: /hesaplanıyor|Working out/ })).toBeNull()
  })
})

describe('useNewDomainWarmup — döngü ve oturuma bağlılık', () => {
  afterEach(() => { vi.useRealTimers() })
  const added = (d) => act(() => { announceInventoryAdded(d) })
  const advance = (ms) => act(async () => { await vi.advanceTimersByTimeAsync(ms) })

  it('veri hazır olunca küme boşalır ve onReady bir kez çağrılır', async () => {
    vi.useFakeTimers()
    const refresh = vi.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true)
    const onReady = vi.fn()
    const { result } = renderHook(() => useNewDomainWarmup(true, refresh, onReady))
    added('new.example.com')
    expect(result.current.has('new.example.com')).toBe(true)
    await advance(WARMUP_DELAYS[0])
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(result.current.has('new.example.com')).toBe(true)
    await advance(WARMUP_DELAYS[1])
    expect(refresh).toHaveBeenCalledTimes(2)
    expect(result.current.size).toBe(0)
    expect(onReady).toHaveBeenCalledTimes(1)
  })

  it('hiç hazır olmazsa deneme sayısı kadar sorar, sonra durur (onReady yok)', async () => {
    vi.useFakeTimers()
    const refresh = vi.fn().mockResolvedValue(false)
    const onReady = vi.fn()
    const { result } = renderHook(() => useNewDomainWarmup(true, refresh, onReady))
    added('slow.example.com')
    await advance(WARMUP_DELAYS.reduce((a, b) => a + b, 0) + 60_000)
    expect(refresh).toHaveBeenCalledTimes(WARMUP_DELAYS.length)
    expect(result.current.size).toBe(0)
    expect(onReady).not.toHaveBeenCalled()
  })

  it('ÇIKIŞ (active=false): küme boşalır, zamanlayıcılar durur, yoldaki yanıt isAlive()=false görür', async () => {
    vi.useFakeTimers()
    let seenAlive = null
    let release
    const refresh = vi.fn((d, isAlive) => new Promise((r) => { release = () => { seenAlive = isAlive(); r(false) } }))
    const { result, rerender } = renderHook(({ on }) => useNewDomainWarmup(on, refresh, vi.fn()), { initialProps: { on: true } })
    added('new.example.com')
    await advance(WARMUP_DELAYS[0])
    expect(refresh).toHaveBeenCalledTimes(1)
    rerender({ on: false })
    expect(result.current.size).toBe(0)
    await act(async () => { release() })
    expect(seenAlive).toBe(false)
    await advance(300_000)
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('oturum yokken olay yok sayılır', async () => {
    vi.useFakeTimers()
    const refresh = vi.fn().mockResolvedValue(true)
    const { result } = renderHook(() => useNewDomainWarmup(false, refresh, vi.fn()))
    added('new.example.com')
    await advance(10_000)
    expect(result.current.size).toBe(0)
    expect(refresh).not.toHaveBeenCalled()
  })
})
