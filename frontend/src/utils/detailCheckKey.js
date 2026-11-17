/**
 * Ayrıntı penceresi sekme verisinin "hangi ölçüm için okundu" anahtarı (performans 2026-11-17).
 *
 * Sayfa Hızı (`/resources`) ve Sayfa Bütünlüğü (`/issues`) pencereleri eskiden 30 sn'de bir sekme açık olsun olmasın ve
 * yeni ölçüm olsun olmasın bu uçları yeniden okuyordu. Artık yalnız o sekme GÖRÜNÜRKEN ve izlemenin son ölçüm zamanı
 * değiştiyse (ya da son sessiz okuma başarısız olduysa) okunur; aksi hâlde eldeki veri kalır.
 *
 * Anahtar = izleme kimliği + son ölçüm zamanı (`last_check`, yoksa `checked_at`). Zaman bilinmiyorsa boş — bir sonraki
 * gerçek değerde bir kez fazla okuma olur (güvenli yön: hiç kaçırılmaz).
 *
 * @param {object|null|undefined} row izleme satırı
 * @param {number|string|null} [id] satır yoksa kullanılacak kimlik
 * @returns {string|null}
 */
export function detailCheckKey(row, id = null) {
  const key = row?.id ?? id
  if (key == null) return null
  return `${key}|${row?.last_check ?? row?.checked_at ?? ''}`
}
