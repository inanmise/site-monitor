/**
 * Ham bir ölçümden ÖNERİLEN alarm eşikleri.
 *
 * <p>Neden ayrı bir dosya: bu kural ürünün en kolay yanlış kullanılan yeri. Eşikleri ölçülen
 * değerin kendisine koymak her normal dalgalanmada alarm üretir; çok yükseğe koymak ise alarmı
 * hiç çaldırmaz. Kural tek yerde durursa hem test edilebilir hem de arayüzde gerekçesiyle
 * anlatılabilir.
 *
 * <p><b>Metrikler aynı oynaklıkta DEĞİLDİR</b> — payların farklı olmasının sebebi budur:
 * <ul>
 *   <li><b>Boyut ve istek sayısı</b> deploy'a bağlıdır; kendiliğinden değişmezler. Biri sayfaya
 *       bir video eklediğinde hemen zıplarlar → DAR pay (%15). Asıl değerli alarm bunlardır.</li>
 *   <li><b>Yükleme süresi</b> ağa bağlıdır; aynı sayfa gün içinde iki katına çıkabilir →
 *       GENİŞ pay (×2). Dar tutulursa haftada birkaç kez boşuna alarm gelir.</li>
 *   <li><b>TTFB</b> sunucunun düşünme süresidir, ağdan çok daha kararlıdır → ×3, ama çok küçük
 *       değerlerde oransal pay anlamsız kaldığı için en az +200 ms mutlak pay eklenir
 *       (90 ms'lik bir TTFB'de ×3 = 270 ms, tek bir GC duraklaması bile onu aşabilirdi).</li>
 * </ul>
 *
 * <p>Yuvarlama HEP YUKARI: aşağı yuvarlamak eşiği ölçülen değere yaklaştırır ve yanlış alarm
 * üretir. Okunur sayılara yuvarlanır çünkü bu değerler ekranda kullanıcıya gösteriliyor.
 */

/** Yukarı, verilen adıma yuvarlar. Adım <= 0 ise değeri olduğu gibi (yukarı tam sayıya) döndürür. */
export function roundUpTo(value, step) {
  const n = Number(value)
  if (!Number.isFinite(n) || n <= 0) return 0
  if (!Number.isFinite(step) || step <= 0) return Math.ceil(n)
  return Math.ceil(n / step) * step
}

export const LOAD_FACTOR = 2
export const TTFB_FACTOR = 3
export const TTFB_MIN_HEADROOM_MS = 200
export const SIZE_HEADROOM = 1.15
export const REQUESTS_HEADROOM = 1.15

/**
 * Ölçüm sonucundan eşik önerisi. Ölçülemeyen metrik için `null` döner (uydurma eşik konmaz).
 *
 * @param r `testPageSpeed` / ölçüm yanıtı — {response_ms, ttfb_ms, total_bytes, request_count}
 * @returns {{maxLoadMs:number|null, maxTtfbMs:number|null, maxPageKb:number|null, maxRequests:number|null}}
 */
export function suggestThresholds(r) {
  const num = (v) => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : null)
  const load = num(r?.response_ms)
  const ttfb = num(r?.ttfb_ms)
  const bytes = num(r?.total_bytes)
  const reqs = num(r?.request_count)

  return {
    maxLoadMs: load == null ? null : roundUpTo(load * LOAD_FACTOR, 500),
    maxTtfbMs: ttfb == null ? null
      : roundUpTo(Math.max(ttfb * TTFB_FACTOR, ttfb + TTFB_MIN_HEADROOM_MS), 50),
    // Boyut eşiği KB girilir; ölçüm bayt. Çevrim 1 KB = 1024 bayt (arayüzdeki gösterimle aynı taban).
    maxPageKb: bytes == null ? null : roundUpTo((bytes / 1024) * SIZE_HEADROOM, 1000),
    maxRequests: reqs == null ? null : roundUpTo(reqs * REQUESTS_HEADROOM, 10),
  }
}

/**
 * Öneri güvenilir mi? Ölçüm KISMİ ise (kaynak sayısı ya da bayt tavanına takıldıysa) gerçek
 * değerler daha yüksektir; o toplamdan türetilen eşik ilk tam ölçümde hemen ihlal verir.
 * Arayüz bu durumda öneriyi gizlemez ama uyarır — karar kullanıcınındır.
 */
export function suggestionIsPartial(r) {
  return Boolean(r?.capped) || Boolean(r?.bytes_truncated)
}

/** Tür bütçeleri + asgari boyut alanları (2026-11-14) — sunucu kuralının istemci aynası (MonitoringController.requirePageSpeedBudgets). */
export const KB_FIELDS = ['maxJsKb', 'maxImgKb', 'maxFontKb', 'maxThirdPartyKb', 'minPageKb']
export const KB_MAX = 10_000_000

/**
 * Bütçe alanlarının istemci denetimi: boş geçerli (eşik yok); dolu ise 0 ile {@link KB_MAX} arası TAM sayı; asgari boyut
 * azami boyuttan büyük olamaz (ikisi de > 0 iken). Hata haritası `fe.check`'e eklenir — sunucu da aynı kuralı uygular.
 * `base` (düzenlemede kayıtlı değerlerin form hâli) verilirse DEĞİŞMEYEN alan yeniden denetlenmez (eski kayıt
 * düzenlenebilir kalır — sunucu kuralıyla aynı).
 */
export function kbFieldErrors(form, t, base = null) {
  const out = {}
  const same = (key) => base != null && String(base[key] ?? '') === String(form?.[key] ?? '')
  for (const key of KB_FIELDS) {
    const raw = form?.[key]
    if (raw === '' || raw == null || same(key)) continue
    const n = Number(raw)
    if (!Number.isFinite(n) || !Number.isInteger(n) || n < 0 || n > KB_MAX) out[key] = t('pspd.err.kbRange')
  }
  const filled = (v) => v !== '' && v != null
  const min = Number(form?.minPageKb), max = Number(form?.maxPageKb)
  const changed = !same('minPageKb') || !same('maxPageKb')
  if (!out.minPageKb && changed && filled(form?.minPageKb) && filled(form?.maxPageKb)
      && min > 0 && max > 0 && min > max) out.minPageKb = t('pspd.err.minAboveMax')
  return out
}
