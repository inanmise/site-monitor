/**
 * App düzeyindeki testler için: App.jsx'te LAZY olan ama İLK render'da bağlanan modülü önceden ısıtır.
 *
 * Neden (2026-10-02, performans önerisi 22): CertificateModal açılış paketinden çıkarıldı — App onu her zaman
 * bağlar, chunk ilk çizimle arka planda iner. Tarayıcıda bu birkaç on ms; vitest'te ise modülün SOĞUK dönüşümü
 * (Markdown editörü + sözdizimi renklendirici zinciri, ~8 sn) testin İÇİNDE koşup işçiyi meşgul eder ve 1–5 sn'lik
 * waitFor'ları sahte kırmızıya çevirir. Eskiden bu maliyet App'in statik import'uyla dosya yüklenirken ödeniyordu;
 * burada da yükleme anında ödenir — testlerin davranışı ve beklentileri aynı kalır.
 *
 * Kullanım: `import App from '../App.jsx'` satırının hemen altına `import './appLazyWarmup.js'`.
 * (vi.mock'lar hoist edildiği için ısıtılan modül de dosyanın mock'larını görür.)
 */
await import('../components/CertificateModal.jsx')
