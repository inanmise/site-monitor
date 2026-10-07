// CANLI suite çıkışı (live-teardown projesi, live-setup'ın teardown'u): paylaşılan oturum kapatılır, çerez dosyası silinir.
// Kayıt temizliği burada DEĞİL — her spec kendi afterAll'unda kendi kayıtlarını siler (oturum o sırada hâlâ açık).
import fs from 'node:fs'
import { test, liveGate, liveRequest, apiCall, LIVE_STATE } from './fixtures.js'

test.describe('canlı çıkış', () => {
  liveGate()

  test('oturumu kapat + çerez dosyasını sil', async ({ playwright }) => {
    if (!fs.existsSync(LIVE_STATE)) return
    const req = await liveRequest(playwright)
    try {
      const r = await apiCall(req, 'POST', '/api/logout')
      console.log(`[live] çıkış → HTTP ${r.status}`)
    } finally {
      await req.dispose()
      fs.rmSync(LIVE_STATE, { force: true })
    }
  })
})
