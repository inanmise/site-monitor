/**
 * Ayıklama Web Worker'ı (2026-10-08) — PKCS#12 anahtar türetme ve şifre çözme ana iş parçacığını dondurmasın diye.
 * Mesaj: `{ bytes, name, text, password }` → yanıt: ayıklama sonucu (core.js). Parola yalnız bu tarayıcı içinde kalır.
 */
import { extractCore } from './core.js'

self.onmessage = async (e) => {
  let out
  try {
    out = await extractCore(e.data || {})
  } catch {
    out = { worker_error: true }
  }
  self.postMessage(out)
}
