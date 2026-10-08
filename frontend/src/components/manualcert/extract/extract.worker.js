/**
 * Ayıklama Web Worker'ı (2026-10-08) — PKCS#12 anahtar türetme ve şifre çözme ana iş parçacığını dondurmasın diye.
 * Mesaj: `{ bytes, name, text, password }` → yanıt: ayıklama sonucu (core.js). Parola yalnız bu tarayıcı içinde kalır.
 *
 * <p>Yükleme durumu (2026-10-08): iş sürerken `{ type: 'progress', progress }` mesajları da gönderilir (ZIP "n / N dosya",
 * PKCS#12 anahtar türetme / şifre çözme başı, keystore biçimi). İlerleme yalnız sayaç ve biçim adı taşır — parola, anahtar
 * ya da sertifika içeriği ASLA. Son mesaj (tipi olmayan) sonuçtur.
 */
import { extractCore } from './core.js'

self.onmessage = async (e) => {
  let out
  try {
    out = await extractCore(e.data || {}, {
      onProgress: (progress) => { try { self.postMessage({ type: 'progress', progress }) } catch { /* yok say */ } },
    })
  } catch {
    out = { worker_error: true }
  }
  self.postMessage(out)
}
