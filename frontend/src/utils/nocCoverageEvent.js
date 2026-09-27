/**
 * 7/24 (NOC) kapsamı DEĞİŞTİ olayı (2026-09-27).
 *
 * <p>Neden var: Pano şeridi (NocCoverageBanner) ve izleme formlarının grup/tür seçenekleri (useNocFormOptions) modül
 * düzeyinde önbellek tutar (sekme gezintisi yeniden istek atmasın). Kapsamı değiştiren bir yazmadan sonra — Kapsam
 * sayfasında aç/kapa ya da toplu, izleme/envanter formunda "7/24'e bildir", Ayarlar'da grup/yapılandırma — önbellek
 * 5 dk'ya (şerit) / 60 sn'ye (form seçenekleri) dek BAYAT kalıyordu.
 *
 * <p>Tek kaynak: `api/client.js` `request()` başarılı her yazmada {@link isNocCoverageWrite} ile bakar ve olayı yayar —
 * yeni bir form ya da uç eklendiğinde çağrı yerine tek tek hatırlamak gerekmez (sınıf kapanır, örnek değil).
 * Dinleyenler önbelleğini düşürür; açık olan yüzey yeniden çeker.
 */
export const NOC_COVERAGE_EVENT = 'sm:noc-coverage-changed'

/** 7/24 kapsamını değiştirebilen uçlar: aç/kapa + toplu, grup yönetimi, yapılandırma (test e-postası HARİÇ). */
const NOC_WRITE_PATH = /^\/(?:admin\/)?noc\/(?:monitors|groups|config)(?:[/?]|$)/
const NOC_TEST_PATH = /^\/(?:admin\/)?noc\/groups\/[^/?]+\/test(?:[/?]|$)/
/** İzleme / envanter kaydı gövdesindeki 7/24 anahtarları (izleme uçları camelCase, envanter snake_case). */
const NOC_BODY_KEY = /"(?:nocNotify|nocGroupIds|noc_notify|noc_group_ids)"\s*:/

/** Bu istek (başarılı olursa) 7/24 kapsamını değiştirebilir mi? GET ve arama kaydı/arama listesi uçları değiştirmez. */
export function isNocCoverageWrite(path, options = {}) {
  const method = String(options.method || 'GET').toUpperCase()
  if (method === 'GET' || method === 'HEAD') return false
  const p = String(path || '')
  if (NOC_WRITE_PATH.test(p)) return !NOC_TEST_PATH.test(p)
  return typeof options.body === 'string' && NOC_BODY_KEY.test(options.body)
}

/** Olayı yayar (tarayıcı dışı ortamda sessiz). */
export function announceNocCoverageChange() {
  try { window.dispatchEvent(new CustomEvent(NOC_COVERAGE_EVENT)) } catch { /* window yok */ }
}
