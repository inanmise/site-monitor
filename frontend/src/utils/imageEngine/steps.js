// Görsel motoru — adım adım (bölünebilir) hesap sürücüsü. Ağır döngüler parçalara bölünür ve parçalar arasında bir
// üreteç (generator) `yield` eder; sıcak döngünün kendisi DÜZ bir fonksiyonda kalır (motor onu iyi derler — üreteç
// gövdesindeki döngüler iyi iyileştirilmez). Aynı adımlar:
//  • eşzamanlı (`runSteps`): Worker'da ya da testte — hiç beklemeden, tek seferde;
//  • eşzamansız (`runStepsAsync`): ana iş parçacığı yedeğinde — her adımda `tick()` beklenir (iptal denetimi + sayfaya
//    nefes; `tick` zamana bakarak yalnız gerektiğinde gerçekten bekler).
// Üreteçler sınırlı sayıda `yield` eder (girdi boyu / parça boyu): döngü sonsuz olamaz.

/** Üreteci sonuna kadar eşzamanlı koşar; dönüş değeri sonuçtur. */
export function runSteps(gen) {
  let r = gen.next()
  while (!r.done) r = gen.next()
  return r.value
}

/** Üreteci her adımda `tick()` bekleyerek koşar (tick atarsa — iptal — hata yayılır). */
export async function runStepsAsync(gen, tick) {
  let r = gen.next()
  while (!r.done) {
    await tick()
    r = gen.next()
  }
  return r.value
}

/** `tick` varsa adım adım, yoksa eşzamanlı (sonuç her durumda söz olarak döner). */
export function drive(gen, tick) {
  return typeof tick === 'function' ? runStepsAsync(gen, tick) : Promise.resolve(runSteps(gen))
}
