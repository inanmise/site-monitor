import { describe, it, expect, vi, afterEach } from 'vitest'
import { api } from '../api/client.js'

/**
 * `api.incidents.trends` — yalın günlük seri (inceleme 2026-11-26, bulgu #7): sayfanın günlük grafiği `view=daily` ister,
 * sunucu yalnız günlük seriyi hesaplar (özet / kırılım / MTTD yok). İki tarihli çağrıda `view` sorguya girer, `opts`'un geri
 * kalanı (süre sınırı, iptal) isteğe gider; `view` yoksa adres bugünküyle birebir aynı. Nesne çağrısında `view` süzgeçlerle
 * gider; sayfa / boyut / sıralama gitmez.
 */
function captureFetch() {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true, status: 200,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data: {} }),
    text: async () => '{"success":true,"data":{}}',
  })
  return global.fetch
}
const urlOf = (f, i) => String(f.mock.calls[i][0])

describe('api.incidents.trends — view=daily', () => {
  const originalFetch = global.fetch
  afterEach(() => { global.fetch = originalFetch })

  it('iki tarihli çağrı: view yoksa bugünkü adres; view=daily sorguya girer', async () => {
    const f = captureFetch()
    await api.incidents.trends('2026-11-01T00:00:00Z', '2026-11-30T23:59:59Z')
    await api.incidents.trends('2026-11-01T00:00:00Z', '2026-11-30T23:59:59Z', { view: 'daily' })
    const plain = new URL(urlOf(f, 0), 'http://x')
    expect(plain.pathname).toMatch(/\/incidents\/trends$/)
    expect([...plain.searchParams.keys()]).toEqual(['since', 'until'])
    const daily = new URL(urlOf(f, 1), 'http://x')
    expect(daily.searchParams.get('view')).toBe('daily')
    expect(daily.searchParams.get('since')).toBe('2026-11-01T00:00:00Z')
  })

  it('nesne çağrısı: süzgeçler + view gider; sayfa, boyut, sıralama, alanlar gitmez', async () => {
    const f = captureFetch()
    await api.incidents.trends({ tag: 'prod', channel_exact: 'ATM', view: 'daily', sort: 'severity', page: 2, size: 50,
      fields: 'summary', since: '2026-11-01T00:00:00Z' })
    const u = new URL(urlOf(f, 0), 'http://x')
    expect(u.searchParams.get('tag')).toBe('prod')
    expect(u.searchParams.get('channel_exact')).toBe('ATM')
    expect(u.searchParams.get('view')).toBe('daily')
    for (const k of ['sort', 'page', 'size', 'fields']) expect(u.searchParams.has(k), k).toBe(false)
  })
})
