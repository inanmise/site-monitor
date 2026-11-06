import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor, render } from '@testing-library/react'
import { memo } from 'react'

/**
 * Performans (2026-11-06, PF4 O4 / D1 / D2): aynı içerik aynı başvuruyu korur ve memo bileşenlere kararlı işleyiciler gider.
 *  - replaceEqualDeep / keepSameSet: değişmeyen alt ağaç ESKİ başvuru, içerik her zaman yeni değerle aynı;
 *  - useLatestCallback / useKeyedHandler: kimlik sabit, çağrıda son çizimin fonksiyonu;
 *  - useOpenAlerts / useInbox: aynı yanıtlı yoklama yeni nesne ÜRETMEZ (menü / zil yeniden çizilmez);
 *  - useUserPrefsController: aynı belgeli odak yenilemesi bağlamı DEĞİŞTİRMEZ (bütün uygulama yeniden çizilmez).
 */
const mocks = vi.hoisted(() => ({ openAlerts: null, inbox: null, prefs: { doc: {} } }))
vi.mock('../api/client', () => ({
  api: {
    me: {
      openAlerts: vi.fn(() => Promise.resolve(mocks.openAlerts())),
      inbox: vi.fn(() => Promise.resolve(mocks.inbox())),
      inboxHistory: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getPreferences: vi.fn(() => Promise.resolve({ success: true, prefs: JSON.parse(JSON.stringify(mocks.prefs.doc)) })),
      savePreferences: vi.fn(() => Promise.resolve({ success: true, prefs: {} })),
    },
  },
}))

import { replaceEqualDeep, keepSameSet } from '../utils/replaceEqualDeep.js'
import { useLatestCallback, useKeyedHandler } from '../hooks/useLatestCallback.js'
import { useOpenAlerts } from '../hooks/useOpenAlerts.js'
import { useInbox } from '../components/inbox/useInbox.js'
import { useUserPrefsController } from '../hooks/useUserPrefs.js'

describe('replaceEqualDeep', () => {
  it('derin eşitse ÖNCEKİ başvuru döner', () => {
    const a = { x: [1, { y: 'z' }], n: null, b: true }
    const b = JSON.parse(JSON.stringify(a))
    expect(replaceEqualDeep(a, b)).toBe(a)
  })

  it('değişen öğe yeni, değişmeyen kardeşler ESKİ başvuru; içerik yeni değerle birebir', () => {
    const prev = [{ domain: 'a', d: 1 }, { domain: 'b', d: 2 }, { domain: 'c', d: 3 }]
    const next = [{ domain: 'a', d: 1 }, { domain: 'b', d: 20 }, { domain: 'c', d: 3 }]
    const out = replaceEqualDeep(prev, next)
    expect(out).not.toBe(prev)
    expect(out).toEqual(next)
    expect(out[0]).toBe(prev[0])
    expect(out[1]).not.toBe(prev[1])
    expect(out[2]).toBe(prev[2])
  })

  it('anahtar eklenir / silinir, dizi boyu değişir → yeni; aynı boyda farklı anahtar takımı da yeni', () => {
    expect(replaceEqualDeep({ a: 1 }, { a: 1, b: 2 })).toEqual({ a: 1, b: 2 })
    const p = { a: 1, b: 2 }
    expect(replaceEqualDeep(p, { a: 1 })).not.toBe(p)
    const q = { a: 1, b: 2 }
    const r = replaceEqualDeep(q, { a: 1, c: 2 })
    expect(r).toEqual({ a: 1, c: 2 })
    const arr = [1, 2]
    expect(replaceEqualDeep(arr, [1, 2, 3])).toEqual([1, 2, 3])
    expect(replaceEqualDeep([1, 2, 3], [1, 2])).toEqual([1, 2])
  })

  it('ilkeller, null / undefined, NaN, dizi ↔ nesne, düz olmayan nesne', () => {
    expect(replaceEqualDeep(1, 2)).toBe(2)
    expect(replaceEqualDeep(null, { a: 1 })).toEqual({ a: 1 })
    expect(replaceEqualDeep({ a: 1 }, null)).toBe(null)
    expect(replaceEqualDeep(undefined, [])).toEqual([])
    const nan = { v: NaN }
    expect(replaceEqualDeep(nan, { v: NaN })).toBe(nan)
    expect(replaceEqualDeep([1], { 0: 1 })).toEqual({ 0: 1 })
    const d1 = new Date(0)
    const d2 = new Date(0)
    expect(replaceEqualDeep({ d: d1 }, { d: d2 }).d).toBe(d2)   // Date paylaşılmaz (=== değil)
  })

  it('çok derin değer yığını taşırmaz', () => {
    const deep = (n) => { let o = { v: 1 }; for (let i = 0; i < n; i++) o = { c: o }; return o }
    const out = replaceEqualDeep(deep(200), deep(200))
    expect(out).toEqual(deep(200))
  })

  it('keepSameSet: aynı içerik → önceki küme; farklı → yeni', () => {
    const a = new Set(['x', 'y'])
    expect(keepSameSet(a, new Set(['y', 'x']))).toBe(a)
    const b = new Set(['x'])
    expect(keepSameSet(a, b)).toBe(b)
    const c = new Set(['x', 'z'])
    expect(keepSameSet(a, c)).toBe(c)
    expect(keepSameSet(null, b)).toBe(b)
  })
})

describe('useLatestCallback / useKeyedHandler', () => {
  it('kimlik çizimler boyunca sabit, çağrı SON fonksiyonu çalıştırır', () => {
    const { result, rerender } = renderHook(({ v }) => useLatestCallback(() => v), { initialProps: { v: 1 } })
    const first = result.current
    rerender({ v: 2 })
    expect(result.current).toBe(first)
    expect(result.current()).toBe(2)
  })

  it('anahtar başına aynı işleyici; çağrıda son fonksiyon + anahtar; olay argümanı İLETİLMEZ', () => {
    const calls = []
    const { result, rerender } = renderHook(({ tag }) => useKeyedHandler((k, extra) => calls.push([tag, k, extra])), { initialProps: { tag: 'a' } })
    const hA = result.current('x.example.com')
    expect(result.current('x.example.com')).toBe(hA)
    expect(result.current('y.example.com')).not.toBe(hA)
    rerender({ tag: 'b' })
    expect(result.current('x.example.com')).toBe(hA)
    hA({ type: 'click' })
    expect(calls).toEqual([['b', 'x.example.com', undefined]])
  })

  it('memo bileşen kararlı işleyiciyle ebeveyn yeniden çizilince ÇİZİLMEZ', () => {
    let childRenders = 0
    const Child = memo(function Child({ onClick }) { childRenders++; return <button type="button" onClick={onClick}>b</button> })
    function Parent({ n }) {
      const h = useLatestCallback(() => n)
      return <><span>{n}</span><Child onClick={h} /></>
    }
    const { rerender } = render(<Parent n={1} />)
    rerender(<Parent n={2} />)
    rerender(<Parent n={3} />)
    expect(childRenders).toBe(1)
  })
})

describe('yoklama kancaları — aynı içerik aynı başvuru', () => {
  it('useOpenAlerts: aynı yanıtlı ikinci yoklama dönen nesneyi DEĞİŞTİRMEZ; farklı yanıt değiştirir', async () => {
    mocks.openAlerts = () => ({ success: true, data: { tabs: { http: { open: 2, critical: 1 } }, total: 2, sampled: false, visible: true } })
    const { result } = renderHook(() => useOpenAlerts())
    await waitFor(() => expect(result.current.loaded).toBe(true))
    const first = result.current
    await act(async () => { await result.current.refresh() })
    expect(result.current).toBe(first)
    mocks.openAlerts = () => ({ success: true, data: { tabs: { http: { open: 3, critical: 1 } }, total: 3, sampled: false, visible: true } })
    await act(async () => { await result.current.refresh() })
    expect(result.current).not.toBe(first)
    expect(result.current.total).toBe(3)
  })

  it('useInbox: aynı liste yeni dizi üretmez (zil yeniden çizilmez)', async () => {
    mocks.inbox = () => ({ success: true, data: [{ key: 'a-1', title: 'x' }, { key: 'a-2', title: 'y' }] })
    const { result } = renderHook(() => useInbox('ali', { open: false, view: 'current' }))
    await waitFor(() => expect(result.current.items).not.toBeNull())
    const items = result.current.items
    await act(async () => { await result.current.retry() })
    expect(result.current.items).toBe(items)
    mocks.inbox = () => ({ success: true, data: [{ key: 'a-1', title: 'x' }] })
    await act(async () => { await result.current.retry() })
    expect(result.current.items).not.toBe(items)
    expect(result.current.items).toHaveLength(1)
  })
})

describe('useUserPrefsController — değişmeyen belge (D1)', () => {
  beforeEach(() => { localStorage.clear(); mocks.prefs.doc = { favorites: [{ type: 'http', id: 5 }], landingTab: 'alerts' } })
  afterEach(() => { localStorage.clear() })

  it('odak yenilemesi aynı belgeyi getirirse bağlam nesnesi DEĞİŞMEZ; farklı belge değiştirir', async () => {
    const { api } = await import('../api/client')
    const { result, unmount } = renderHook(() => useUserPrefsController('ali', { debounceMs: 30 }))
    await waitFor(() => expect(result.current.ready).toBe(true))
    const ctx = result.current
    const before = api.me.getPreferences.mock.calls.length
    // Başka sekmenin kaydı (revizyon damgası) yenilemeyi HEMEN tetikler
    act(() => { window.dispatchEvent(new StorageEvent('storage', { key: 'sm.prefs.rev', newValue: String(Date.now()) })) })
    await waitFor(() => expect(api.me.getPreferences.mock.calls.length).toBe(before + 1))
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
    expect(result.current).toBe(ctx)

    mocks.prefs.doc = { ...mocks.prefs.doc, landingTab: 'domains' }
    act(() => { window.dispatchEvent(new StorageEvent('storage', { key: 'sm.prefs.rev', newValue: String(Date.now() + 1) })) })
    await waitFor(() => expect(result.current.landingTab).toBe('domains'))
    expect(result.current).not.toBe(ctx)
    unmount()
  })
})
