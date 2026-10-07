import os from 'node:os'
import path from 'node:path'

/**
 * Canlı suite'in paylaşılan oturum dosyası (Playwright storageState: çerezler + localStorage). Depo DIŞINDA, işletim
 * sisteminin geçici dizininde durur; `live-teardown` çıkış yapıp siler. İçinde parola YOK, yalnız oturum çerezi.
 */
export const LIVE_STATE = path.join(os.tmpdir(), 'site-monitor-e2e-live', 'state.json')
export const LIVE_BASE_URL = 'http://127.0.0.1:5174'
