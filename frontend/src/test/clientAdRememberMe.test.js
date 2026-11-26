import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// Sert yönlendirme jsdom'da taklit edilemez — tek noktadan geçer ve burada sahtelenir.
vi.mock('../utils/accountInactive.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, assignLocation: vi.fn() }
})

import { api } from '../api/client.js'
import { assignLocation, ACCOUNT_INACTIVE_EVENT } from '../utils/accountInactive.js'

/**
 * Beni-hatırla AD reddi (H paketi 2026-11-26, yönetim B4): LDAP hesabı AD'de devre dışı / doğrulanamadı → sunucu
 * belirteçleri siler ve açılıştaki `/me` 401 döner. İstemci bunu sıradan "oturum yok" gibi işler: `null` → App giriş
 * formunu çizer. Pasif hesap penceresi, bakım penceresi ya da `/?session=expired` yönlendirmesi YOK (oturum bayrağı
 * açılışta yoktur).
 */
function mockFetch(body, status) {
  global.fetch = vi.fn().mockResolvedValue({ status, ok: status < 400, headers: { get: () => null }, json: () => Promise.resolve(body) })
}

describe('api/client — açılışta beni-hatırla AD reddi', () => {
  let inactive
  const onInactive = () => { inactive++ }
  beforeEach(() => {
    inactive = 0
    vi.mocked(assignLocation).mockClear()
    sessionStorage.clear()
    window.addEventListener(ACCOUNT_INACTIVE_EVENT, onInactive)
  })
  afterEach(() => {
    window.removeEventListener(ACCOUNT_INACTIVE_EVENT, onInactive)
    sessionStorage.clear()
  })

  for (const code of ['AD_ACCOUNT_DISABLED', 'AD_UNVERIFIED']) {
    it(`${code}: getMe null döner (giriş formu), yönlendirme ve pencere sinyali yok`, async () => {
      mockFetch({ success: false, code, error_code: code, error: 'AD reddi' }, 401)
      await expect(api.getMe()).resolves.toBeNull()
      expect(assignLocation).not.toHaveBeenCalled()
      expect(inactive).toBe(0)
    })
  }
})
