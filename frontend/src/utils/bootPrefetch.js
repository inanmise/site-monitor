import { api } from '../api/client'

/**
 * Açılış ön isteği (2026-11-06, performans — PF4 Y3a). Saklı dil İngilizce ise uygulama, İngilizce sözlük parçası inene kadar
 * açılış ekranında bekler (LangProvider `fallback`); `BrandingProvider` ve `App` o ana dek BAĞLANMAZ, dolayısıyla `/api/branding`
 * ve oturum açılışı `/api/me` da sözlüğü bekliyordu (seri: parça → sonra iki istek). Artık `main.jsx` iki isteği ilk çizimden ÖNCE
 * başlatır (sözlükle paralel); `App` (`bootMe`) ve `BrandingProvider` aynı sözü TEK kez alır — ağda yine birer istek.
 *
 * Kurallar (eskisiyle aynı):
 *  - bilinmeyen yol (markalı 404 sayfası) → `/api/me` BAŞLATILMAZ (App hiç bağlanmaz); marka yine istenir (404 sayfası da markalı);
 *  - bekleyen çıkış (UIM-1) → `/api/me` BAŞLATILMAZ (App önce çıkışı kapatır, sonra kendi isteğini atar); App ayrıca bekleyen
 *    çıkışı görünce varsa ön isteği ATAR ({@link discardBootMe}) — çıkıştan önceki oturumun yanıtı kullanılmaz;
 *  - söz yalnız BİR kez alınır ({@link takeBootMe} / {@link takeBootBranding}); sonraki çağrılar (yenileme, StrictMode'un ikinci
 *    efekti) eskisi gibi kendi isteğini atar;
 *  - {@link BOOT_PREFETCH_MAX_AGE_MS} sonra ön istek bayat sayılır (alınmaz) — açılıştan çok sonra bağlanan bir bileşen eski
 *    yanıtla çalışmasın.
 * Reddedilen söz burada "işlendi" işaretlenir (işlenmemiş ret uyarısı yok); alan taraf reddi yine görür.
 */
export const BOOT_PREFETCH_MAX_AGE_MS = 60_000

let me = null        // { promise, at }
let branding = null  // { promise, at }

function hold(promise) {
  if (promise && typeof promise.catch === 'function') promise.catch(() => {})
  return { promise, at: Date.now() }
}

/** İstekleri başlatır (yalnız bir kez; zaten başlamışsa dokunmaz). `me: false` → oturum isteği başlatılmaz. */
/**
 * Ön istekler dil başlığı (`X-Lang`) TAŞIMAZ: ikisinin başarılı yanıtında dile bağlı metin yok, sözlük de henüz inmedi —
 * İngilizce sözlük inemezse oturum Türkçe'ye zorlanır ve hiçbir istek "en" taşımamalı (`rolling-update-assets.spec.js`).
 */
const NO_LANG = Object.freeze({ headers: Object.freeze({ 'X-Lang': undefined }) })

export function startBootPrefetch({ me: withMe = true, branding: withBranding = true } = {}) {
  try {
    if (withMe && !me) me = hold(api.getMe(NO_LANG))
    if (withBranding && !branding) branding = hold(api.getBranding(false, NO_LANG))
  } catch { /* istemci hazır değil: bileşenler kendi isteğini atar */ }
}

function take(slot) {
  if (!slot) return null
  if (Date.now() - slot.at > BOOT_PREFETCH_MAX_AGE_MS) return null
  return slot.promise
}

/** Ön `/api/me` sözü (bir kez); yoksa / bayatsa null — çağıran kendi isteğini atar. */
export function takeBootMe() {
  const p = take(me)
  me = null
  return p
}

/** Ön `/api/branding` sözü (bir kez); yoksa / bayatsa null. */
export function takeBootBranding() {
  const p = take(branding)
  branding = null
  return p
}

/** Ön `/api/me` sözünü kullanmadan bırakır (bekleyen çıkış: önceki oturumun yanıtı uygulanmamalı). */
export function discardBootMe() {
  me = null
}

/** Testler için: modül durumunu sıfırlar. */
export function resetBootPrefetch() {
  me = null
  branding = null
}
