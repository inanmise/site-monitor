// CANLI suite girişi (live-setup projesi): GERÇEK giriş formundan bir kez girilir, oturum çerezleri LIVE_STATE'e yazılır;
// `live` projesindeki testler aynı oturumu kullanır (tek aktif oturum kuralı — her test ayrı girseydi birbirini düşürürdü).
// Parola yalnız ortamdan okunur; bu projede iz (trace) ve ekran görüntüsü kapalıdır (playwright.live.config.js).
import fs from 'node:fs'
import path from 'node:path'
import { test, expect, liveGate, loginViaForm, apiGet, LIVE_STATE, stopwatch } from './fixtures.js'

test.describe('canlı giriş', () => {
  liveGate()

  test('gerçek giriş formu → oturum (409 ise diğer oturumu kapatma onayı)', async ({ page }) => {
    const done = stopwatch('giriş')
    const r = await loginViaForm(page)
    // Oturum gerçekten kuruldu: /api/me 200 + kullanıcı nesnesi; giriş formu artık yok
    const me = await apiGet(page.request, '/api/me')
    expect(me.status, '/api/me').toBe(200)
    expect(me.json?.success, '/api/me success').toBe(true)
    await expect(page.locator('#lp-user')).toHaveCount(0)
    fs.mkdirSync(path.dirname(LIVE_STATE), { recursive: true })
    await page.context().storageState({ path: LIVE_STATE })
    console.log(`[live] giriş tamam (başka oturum kapatıldı: ${r.forced ? 'evet' : 'hayır'})`)
    done()
  })
})
