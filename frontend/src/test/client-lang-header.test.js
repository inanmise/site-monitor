import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api } from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/**
 * Sunucu tost/hata metinleri arayüz dilini X-Lang başlığından öğrenir (backend Msg.t).
 * Başlık düşerse İngilizce arayüzde Türkçe "Ayarlar kaydedildi…" geri gelir (QA ISSUE-001,
 * 2026-09-10) — hiçbir bileşen testi bunu yakalamaz çünkü mesaj sunucudan gelir.
 */
function captureFetch() {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true, status: 200,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true }),
    text: async () => '{"success":true}',
  })
  return global.fetch
}

describe('api request → X-Lang başlığı', () => {
  const originalFetch = global.fetch
  beforeEach(() => { try { localStorage.removeItem(LANG_STORAGE_KEY) } catch {} })
  afterEach(() => { global.fetch = originalFetch; try { localStorage.removeItem(LANG_STORAGE_KEY) } catch {} })

  it('saklı dil yoksa en gönderir (i18n storedLang varsayılanıyla aynı)', async () => {
    const f = captureFetch()
    await api.getMe()
    const [, opts] = f.mock.calls[0]
    expect(opts.headers['X-Lang']).toBe('en')
  })

  it('kullanıcı Türkçeye geçtiyse tr gönderir', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'tr')
    const f = captureFetch()
    await api.getMe()
    const [, opts] = f.mock.calls[0]
    expect(opts.headers['X-Lang']).toBe('tr')
  })

  it('çağıranın kendi başlıkları X-Lang ile birlikte gider (ezmez)', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'tr')
    const f = captureFetch()
    await api.getMe()
    const [, opts] = f.mock.calls[0]
    expect(opts.headers['Content-Type']).toBe('application/json')
    expect(opts.headers['X-Lang']).toBe('tr')
  })
})
