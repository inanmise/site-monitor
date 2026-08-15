/**
 * Sınırlı eşzamanlılıkla kuyruk tüketimi.
 *
 * "Şimdi Kontrol Et" akışı için yazıldı: erişilemeyen bir host'ta tek sertifika kontrolü
 * timeout'a (~6 sn) kadar sürüyor ve sıralı `for (… of …) await` döngüsünde bu, ARKASINDAKİ
 * tüm domainleri bekletiyordu. Burada iş parçaları TAMAMLANMA sırasında biter; yavaş olan
 * kendi süresini yine harcar ama kimseyi bloklamaz.
 *
 * Sınırsız fan-out (hepsini birden başlatmak) bilinçli olarak yapılmıyor: her kontrol sunucuda
 * bir istek iş parçacığı tutar ve tek pod aynı anda başka kullanıcılara da hizmet eder.
 *
 * @param {Array} items        işlenecek öğeler (kopyalanır; çağıranın dizisi değişmez)
 * @param {Function} worker    async (item) => void — hata YAKALAMASI çağırana aittir
 * @param {Object} [opts]
 * @param {number} [opts.limit=6]        aynı anda en fazla kaç iş
 * @param {Function} [opts.shouldStop]   () => boolean — true dönerse YENİ iş başlatılmaz
 *                                       (uçuştakiler tamamlanır; "Durdur" düğmesi bunu kullanır)
 * @returns {Promise<void>} tüm işler bittiğinde (veya durdurulduğunda) çözülür
 */
export async function runWithConcurrency(items, worker, { limit = 6, shouldStop } = {}) {
  const queue = [...items]
  const stopped = () => (typeof shouldStop === 'function' ? !!shouldStop() : false)
  const workerCount = Math.max(1, Math.min(limit, queue.length))
  if (!queue.length) return

  const drain = async () => {
    while (!stopped()) {
      const item = queue.shift()
      if (item === undefined) return
      await worker(item)
    }
  }
  await Promise.all(Array.from({ length: workerCount }, drain))
}

export default runWithConcurrency
