import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest'
import { api } from '../api/client'

/**
 * API istemcisi duman testi (2026-11-16): her `api.*` sarmalayıcısı sahte `fetch` ile bir kez çağrılır.
 *  • Hiçbiri yakalanmamış bir TypeError ile ("x is not a function", yanlış adlandırılmış yardımcı) düşmez.
 *  • Gönderilen HER istek bir iptal sinyali taşır — projenin "zaman aşımı olmayan istek yok" kuralı (2026-11-15;
 *    `clientTimeoutGate.test.js` kaynağı tarar, bu test çalışan yolu doğrular).
 *  • İstekler yalnız `/api/` altına gider.
 * Bağımsız değişkenler sahte ('1', '2' …) — amaç sözleşme değil sarmalayıcının çalışması; her çağrı 3 sn ile sınırlı.
 */

function collect(obj, path, out, seen) {
  if (!obj || typeof obj !== 'object' || seen.has(obj)) return out
  seen.add(obj)
  for (const [k, v] of Object.entries(obj)) {
    if (typeof v === 'function') out.push([`${path}.${k}`, v, obj])
    else if (v && typeof v === 'object' && !Array.isArray(v)) collect(v, `${path}.${k}`, out, seen)
  }
  return out
}

const calls = []
const realFetch = globalThis.fetch
const realCreate = URL.createObjectURL
const realRevoke = URL.revokeObjectURL

beforeAll(() => {
  globalThis.fetch = vi.fn(async (url, init = {}) => {
    calls.push({ url: String(url), signal: init.signal })
    return new Response(JSON.stringify({ success: true, data: [], total: 0 }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })
  })
  URL.createObjectURL = vi.fn(() => 'blob:sm-test')
  URL.revokeObjectURL = vi.fn()
})
afterAll(() => {
  globalThis.fetch = realFetch
  URL.createObjectURL = realCreate
  URL.revokeObjectURL = realRevoke
})

const bounded = (p) => Promise.race([Promise.resolve(p).catch((e) => ({ __error: e })), new Promise((r) => setTimeout(() => r({ __timeout: true }), 3000))])

describe('api istemcisi — her sarmalayıcı', () => {
  it('çağrılır: TypeError ile düşmez, istekler /api/ altına gider ve HER istek iptal sinyali taşır', async () => {
    const fns = collect(api, 'api', [], new Set())
    expect(fns.length).toBeGreaterThan(200)
    const typeErrors = []
    let issued = 0
    for (const [name, fn, self] of fns) {
      const before = calls.length
      let out
      try {
        out = await bounded(fn.call(self, '1', '2', '3', '4'))
      } catch (e) {
        out = { __error: e }
      }
      const e = out?.__error
      // Sahte bağımsız değişkenle beklenen hatalar (ör. dizi yerine metin) kabul; ad / bağlam hatası değil
      // (dizi bekleyen yere metin → ".map is not a function" beklenen; sayılmaz)
      const argShape = /\.(?:map|join|filter|forEach|slice|some|every|reduce|flatMap|entries) is not a function/.test(e?.message || '')
      if (e instanceof ReferenceError
        || (e instanceof TypeError && !argShape && /is not a function|is not defined|Cannot read properties of undefined \(reading '(?:request|get|post|put|delete)'\)/.test(e.message))) {
        typeErrors.push(`${name}: ${e.message}`)
      }
      if (calls.length > before) issued++
    }
    expect(typeErrors, typeErrors.join(' | ')).toEqual([])
    expect(issued).toBeGreaterThan(fns.length * 0.7)
    expect(calls.length).toBeGreaterThan(0)
    const outside = calls.filter((c) => !/(^|\/)api\//.test(new URL(c.url, 'http://x').pathname.replace(/^\//, '')) && !c.url.includes('/api/'))
    expect(outside.map((c) => c.url)).toEqual([])
    const unbounded = calls.filter((c) => !(c.signal && typeof c.signal.aborted === 'boolean'))
    expect(unbounded.map((c) => c.url)).toEqual([])
  }, 120_000)
})
