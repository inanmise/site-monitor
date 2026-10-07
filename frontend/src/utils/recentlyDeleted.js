import { useMemo, useSyncExternalStore } from 'react'

/**
 * Yakın zamanda silinenler — iyimser, KALICI kaldırma (2026-10-07, kullanıcı isteği: "silme butonuna tıkladığımda kart
 * aniden yok olmuyor, refresh olmayı bekliyor").
 *
 * <p><b>Sorun.</b> Silme başarılı dönünce sayfalar kartı ancak tam liste yeniden yüklenince kaldırıyordu (bekleme). Daha
 * kötüsü: sertifika listesi önbellekleri ({@code cert-latest} …) POD BAŞINADIR; silme yalnız isteği işleyen pod'un
 * önbelleğini boşaltır, sonraki GET başka pod'a düşerse silinen kayıt önbellek süresi boyunca GERİ GELİR.
 *
 * <p><b>Çözüm.</b> Başarılı silmede sayfa öğeyi hemen yerel durumdan çıkarır ve burada işaretler ({@link markDeleted});
 * sunucudan gelen HER liste {@link filterDeleted} ile süzülür — işaret {@link DELETED_TTL_MS} (10 dk, önbellek ömrünün
 * üstünde) boyunca bayat yanıtın kaydı geri getirmesini engeller. Aynı ad yeniden eklenince / adlandırılınca
 * {@link unmarkDeleted} işareti hemen kaldırır (yeni kayıt beklemeden görünür).
 *
 * <p>Saf modül düzeyi depo (React'siz); bileşenler değişiklikleri {@link useDeletedMarksVersion} ile izler (başka bir
 * sekmede yapılan silme — ör. Envanter → Genel Bakış kartları — anında yansısın). Tarayıcı deposu KULLANILMAZ: işaret
 * yalnız bu sekmenin oturum içi bilgisidir; yeniden yüklemede sunucu zaten doğruyu söyler (TTL içinde başka pod bayat
 * dönerse en kötü ihtimalle o kayıt bir kez daha görünür — eski davranış).
 *
 * Türler: {@code 'cert'} (anahtar: alan adı / takip adı, harf duyarsız) ve izleme türleri ({@code 'http'}, {@code 'port'} …
 * anahtar: izleme kimliği) — {@link monitorKind}.
 */

/** İşaret ömrü: sunucu önbelleklerinin (≤ 5 dk) üstünde. */
export const DELETED_TTL_MS = 10 * 60 * 1000

const marks = new Map()   // "kind::key" → bitiş (epoch ms)
const listeners = new Set()
let version = 0

function k(kind, key) {
  return `${String(kind)}::${String(key ?? '').trim().toLowerCase()}`
}

function emit() {
  version++
  for (const fn of listeners) {
    try { fn() } catch { /* dinleyici hatası depoyu bozmasın */ }
  }
}

function prune(now) {
  let changed = false
  for (const [key, exp] of marks) {
    if (exp <= now) { marks.delete(key); changed = true }
  }
  return changed
}

/** Başarılı silmeden HEMEN sonra çağrılır. Boş anahtar yok sayılır. */
export function markDeleted(kind, key, ttlMs = DELETED_TTL_MS, now = Date.now()) {
  if (key == null || String(key).trim() === '') return
  marks.set(k(kind, key), now + Math.max(1, ttlMs))
  emit()
}

/** Birden çok kaydı tek seferde işaretler (toplu silme) — dinleyiciler bir kez uyarılır. */
export function markManyDeleted(kind, keys, ttlMs = DELETED_TTL_MS, now = Date.now()) {
  let any = false
  for (const key of keys || []) {
    if (key == null || String(key).trim() === '') continue
    marks.set(k(kind, key), now + Math.max(1, ttlMs))
    any = true
  }
  if (any) emit()
}

/** Aynı ad / kimlik yeniden eklendi ya da adlandırıldı → işaret kalkar, yeni kayıt hemen görünür. */
export function unmarkDeleted(kind, key) {
  if (marks.delete(k(kind, key))) emit()
}

/** İşaretli ve süresi dolmamış mı? */
export function isRecentlyDeleted(kind, key, now = Date.now()) {
  const exp = marks.get(k(kind, key))
  if (exp == null) return false
  if (exp <= now) { marks.delete(k(kind, key)); return false }
  return true
}

/**
 * Listeden yakın zamanda silinenleri çıkarır. Hiçbir şey çıkmazsa AYNI dizi döner (memo kimliği korunur); dizi değilse
 * olduğu gibi döner. {@code keyOf} öğenin anahtarını verir (ör. {@code c => c.domain}).
 */
export function filterDeleted(kind, list, keyOf, now = Date.now()) {
  if (!Array.isArray(list) || marks.size === 0) return list
  if (prune(now) && marks.size === 0) return list
  const prefix = `${String(kind)}::`
  let hasKind = false
  for (const key of marks.keys()) { if (key.startsWith(prefix)) { hasKind = true; break } }
  if (!hasKind) return list
  const out = list.filter((item) => !marks.has(k(kind, keyOf(item))))
  return out.length === list.length ? list : out
}

/** Sunucu sayfasındaki çıkarılan öğe sayısı — sayfalı listelerde gösterilen toplamı düzeltmek için. */
export function countDeleted(kind, list, keyOf, now = Date.now()) {
  if (!Array.isArray(list)) return 0
  return list.length - filterDeleted(kind, list, keyOf, now).length
}

/** İzleme türü anahtarı — sayfalar arasında aynı ad (ör. Port sayfası ve İzleme Genel Bakış'ı). */
export function monitorKind(type) {
  return `mon-${String(type || '').toLowerCase()}`
}

export function subscribeDeleted(fn) {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

export function getDeletedVersion() {
  return version
}

/** Bileşen kancası: işaret eklenip kalktıkça artan sürüm — memo/etki bağımlılığına konur. */
export function useDeletedMarksVersion() {
  return useSyncExternalStore(subscribeDeleted, getDeletedVersion, getDeletedVersion)
}

/**
 * Sayfa kancası: sunucudan gelen listeyi yakın zamanda silinenlerden süzer; başka bir yüzeyde yapılan silme de (işaret
 * değişince) anında yansır. Hiçbir şey düşmezse AYNI dizi döner. {@code keyOf} varsayılanı izleme kimliği.
 */
export function useWithoutDeleted(kind, list, keyOf = byId) {
  const version = useDeletedMarksVersion()
  // keyOf bağımlılık değil: sayfalar satır içi ok fonksiyonu verir; tür + liste + sürüm yeterli.
  return useMemo(() => filterDeleted(kind, list, keyOf), [kind, list, version]) // eslint-disable-line react-hooks/exhaustive-deps
}

const byId = (m) => m?.id

/**
 * İzleme silme yanıtını işaretler — KALICI silindiyse true. Port/DNS envanter TÜREVİ satırda silme duraklatmadır
 * (sunucu {@code permanent:false} döner): satır listede kalır, işaretlenmez. Yanıtta alan yoksa (diğer yedi tür) kalıcıdır.
 */
export function markMonitorDeleted(type, id, res) {
  if (res?.data?.permanent === false || res?.permanent === false) return false
  markDeleted(monitorKind(type), id)
  return true
}

/** Yalnız testler: depoyu sıfırlar. */
export function __resetDeletedMarks() {
  marks.clear()
  emit()
}
