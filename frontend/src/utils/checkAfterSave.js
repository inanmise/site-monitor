/**
 * KAYIT SONRASI KONTROL (2026-09-28, kullanıcı bildirimi: "İzleme kartını Test et → başarılı → Kaydet. Sonrasında açılan
 * kartta veriler yansımıyor, boş bir görünüm oluyor. İlk koşumun verilerinin hemen karta yansımasını beklerim.").
 *
 * <p><b>Kusur:</b> dokuz türün hiçbirinde oluşturma ucu kontrol koşmuyor (kayıt tek iş yapar). Yeni kart zamanlayıcının
 * ilk turuna (ilk görüşte hemen: HTTP/Port/Ping/Anahtar Kelime ≤ 30 sn, Sayfa/Sayfa Hızı/Sentetik ≤ 60 sn, DNS ≤ 5 dk,
 * Alan Adı ≤ 1 sa) VE listenin 60 sn'lik yenilemesine kadar boş kalıyordu. Formdaki "Test" sonucu kalıcı değildir ve
 * istemciden gelen bir sonucu kaydetmek güvenilmez veri yazmak olurdu → kaydedilmez.
 *
 * <p><b>Çözüm:</b> kaydın hemen ardından sayfa kartın KENDİ "Şimdi kontrol et" yolunu ({@code checkNow} →
 * {@code POST /monitoring/<tür>/{id}/check}) çağırır. Sonuç sunucuda normal bir kontrol olarak kalıcı yazılır; geçmiş,
 * alarm değerlendirmesi (evaluate*Now) ve trend elle kontrolle birebir aynı işler. Kart bu sırada dönen göstergeyle
 * "İlk kontrol yapılıyor…" der (useRunningChecks → kartın `running`'i) ve yanıt gelince sonuçla dolar.
 * TEK mekanizma: sunucu tarafında ayrıca "oluşturunca asenkron kontrol" YOK — ikisi birlikte aynı hedefe iki istek ve
 * geçmişe iki satır yazardı; arayüz dışı oluşturmaları (API, envanterden türeyen DNS/Port) zamanlayıcının ilk-görüş
 * kuralı zaten ilk turda koşar.
 *
 * <p><b>Düzenlemede</b> yalnız KONTROLÜ ETKİLEYEN bir alan değiştiyse (hedef, yöntem, beklenen değer/kod, zaman aşımı,
 * vekil …) taze kontrol koşulur — kart eski hedefin sonucunu göstermesin. Ad / etiket / grup / not / bildirim ayarları
 * gibi meta düzenlemeleri koşum BAŞLATMAZ. Duraklatılmış izleme hiç koşulmaz (duraklatmak = kontrol yok; elle
 * tetikleme alarm değerlendirmesini de başlatır).
 *
 * <p>Karşılaştırma sunucunun kayıt ÖNCESİ (düzenlenen satır) ve SONRASI (kaydetme yanıtı) satırı üzerinden yapılır:
 * ikisi de aynı enrich* çıktısıdır, normalleştirmeyi (URL'e şema ekleme, küçük harf host …) sunucu yapmış olur.
 * Geri okunamayan gizli alanlar (başlık, parola) satırda yoktur — çağıran onları `extraChanged` ile bildirir.
 */

/** Tür → kontrolün SONUCUNU etkileyen satır alanları (snake_case, enrich* çıktısı). */
export const CHECK_FIELDS = Object.freeze({
  http: ['url', 'method', 'expected_status', 'follow_redirects', 'verify_ssl', 'use_proxy', 'timeout_ms'],
  ping: ['host', 'ip_version', 'packet_count', 'timeout_ms'],
  keyword: ['url', 'keyword', 'condition', 'operator', 'match_count', 'case_sensitive', 'use_proxy', 'timeout_ms'],
  port: ['host', 'port', 'protocol', 'expect', 'send_data', 'use_proxy', 'ip_version', 'timeout_ms'],
  dns: ['domain', 'record_type', 'expected_value'],
  page: ['url', 'mode', 'crawl_depth', 'crawl_max_pages', 'exclude_patterns', 'slow_resource_ms', 'alert_third_party',
    'alert_mixed_content', 'alert_timeout', 'resource_concurrency', 'use_proxy', 'timeout_ms'],
  pagespeed: ['url', 'timeout_ms', 'max_load_ms', 'max_ttfb_ms', 'max_page_kb', 'max_requests', 'user_agent', 'send_dnt',
    'exclude_trackers', 'tracker_patterns', 'resource_concurrency', 'basic_auth_user', 'use_proxy'],
  // script + env değişince sunucu YENİ SÜRÜM yazar (script_version) — içerik değişikliğinin kanonik işareti.
  scripted: ['script_version', 'timeout_seconds', 'use_proxy'],
  // Durum (OK/WARNING/CRITICAL) kontrol ANINDA uyarı/kritik günlerinden hesaplanır → onlar da sonucu etkiler.
  domain: ['domain', 'check_timeout_ms', 'blacklist_enabled', 'warning_days', 'critical_days'],
})

/** Karşılaştırma için tek biçim: boş = null, metin kırpılır, dizi/nesne JSON. */
function norm(v) {
  if (v === undefined || v === null) return null
  if (typeof v === 'string') { const s = v.trim(); return s === '' ? null : s }
  if (typeof v === 'object') return JSON.stringify(v)
  return v
}

/**
 * Kaydetmeden sonra kontrol koşulsun mu?
 *
 * @param {keyof CHECK_FIELDS} kind  izleme türü
 * @param {object} opts
 * @param {boolean} opts.isNew        oluşturma mı
 * @param {object} [opts.before]      düzenlenen satır (kayıt öncesi; oluşturmada yok)
 * @param {object} opts.after         kaydetme yanıtındaki satır (`res.data`)
 * @param {boolean} [opts.extraChanged] satırda görünmeyen, kontrolü etkileyen bir alan yazıldı (gizli başlık / parola)
 * @returns {boolean}
 */
export function shouldCheckAfterSave(kind, { isNew = false, before = null, after = null, extraChanged = false } = {}) {
  if (!after || after.id == null) return false
  if (after.active === false) return false
  if (isNew) return true
  if (extraChanged) return true
  const fields = CHECK_FIELDS[kind]
  if (!fields) return false
  return fields.some((f) => norm(before?.[f]) !== norm(after?.[f]))
}

/**
 * Kontrolü ATEŞLE-UNUT başlatır: sonuç kartta görünür (sayfanın checkNow'u track + işlevsel setMonitors ile işler);
 * kayıt akışı sonucu BEKLEMEZ (pencere hemen kapanır, kart dönen göstergeyle bekler). Ret YUTULUR: ağ hatasında
 * request() fırlatır — yakalanmazsa işlenmemiş ret olur (CI'da "unhandled rejection" = kırmızı). Kart o durumda
 * "İlk kontrol bekleniyor"da kalır; zamanlayıcı ilk turunda yine koşar.
 *
 * @param {Function} checkNow  sayfanın tekil kontrol fonksiyonu — (monitor, opts?) => Promise
 * @param {object} monitor     kaydedilen satır (en az `id`)
 * @param {object} [opts]      checkNow'a geçen seçenekler (ör. `{ silent: true }`)
 */
export function startCheckAfterSave(checkNow, monitor, opts) {
  try {
    const p = opts === undefined ? checkNow(monitor) : checkNow(monitor, opts)
    if (p && typeof p.catch === 'function') p.catch(() => {})
  } catch { /* senkron hata da kaydı bozmasın */ }
}
