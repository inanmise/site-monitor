import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * KAPI (2026-10-08): tarayıcıdaki sertifika ayıklayıcısı ve kütüphaneleri (node-forge, fflate) AÇILIŞ PAKETİNE GİRMEZ.
 * Yalnız yükleme sihirbazının (React.lazy) yolundan, o da yalnız "Analiz et"te `import()` / Web Worker ile yüklenir.
 * Kaynak düzeyinde denetlenir (jsdom paket üretmez): hangi dosya neyi STATİK içe aktarıyor.
 *
 *  - `node-forge` / `fflate` üretim kaynağında YALNIZ `components/manualcert/extract/` altında içe aktarılır;
 *  - `extract/index.js` hiçbir şeyi statik içe aktarmaz — çekirdek `import('./core.js')` ya da Worker ile gelir;
 *  - `extract/` modüllerini yalnız `UploadWizard.jsx` (index) kullanır; sihirbaz her yerde `lazy(() => import(...))`;
 *  - açılış paketindeki `manualCertModel.js` (App.jsx statik alır) `extract/`'ı içe aktarmaz.
 */
const SRC = path.resolve(__dirname, '..')
const EXTRACT = path.join(SRC, 'components', 'manualcert', 'extract')
const rel = (f) => path.relative(SRC, f).replace(/\\/g, '/')

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) { if (e.name !== 'test' && e.name !== 'assets') walk(path.join(dir, e.name), acc) } else if (/\.(jsx?|mjs)$/.test(e.name)) acc.push(path.join(dir, e.name))
  }
  return acc
}
const FILES = walk(SRC).map((f) => ({ f, rel: rel(f), src: fs.readFileSync(f, 'utf8') }))
const staticImports = (src) => [...src.matchAll(/^\s*import\s+(?:[^'"]*?\s+from\s+)?['"]([^'"]+)['"]/gm)].map((m) => m[1])
const anyImports = (src) => [...staticImports(src), ...[...src.matchAll(/import\(\s*['"]([^'"]+)['"]\s*\)/g)].map((m) => m[1])]

describe('manuel sertifika ayıklayıcısı — tembel parça kapısı', () => {
  it('node-forge ve fflate yalnız extract/ altında içe aktarılır (tek istisna: tembel Excel yazıcısı)', () => {
    // İstisna (2026-10-08): `utils/xlsxWriter.js` fflate ile .xlsx paketler. O da açılış paketine GİRMEZ — aşağıdaki
    // kapı yalnız tembel yüklenen dışa aktarma modülünün onu içe aktardığını denetler. node-forge için istisna YOK.
    const XLSX_WRITER = 'utils/xlsxWriter.js'
    // İkinci istisna (2026-10-17, C12): kanalın yıl arşivi (`techerror/yearArchive.js`) akışlı ZIP'i fflate ile yazar —
    // YALNIZ dinamik `import('fflate')` ile (arşiv düğmesine basılınca); statik içe aktarma açılış paketine sokardı.
    const YEAR_ARCHIVE = 'components/techerror/yearArchive.js'
    // Üçüncü istisna (2026-11-14, kaynak analizi görsel aracı): görsel motorunun PNG yazıcısı (`utils/imageEngine/pngWriter.js`)
    // fflate'i STATİK alır, ama kendisine yalnız motorun ağır çekirdeğinden ulaşılır — Worker paketi (`engine.worker.js`) ya
    // da ana iş parçacığı yedeği `import('./core.js')`; motorun kamu arayüzü (`index.js`) de yalnız `import()` ile yüklenir
    // (lazyChunks.test.js). Yani fflate açılış paketine bu yoldan da GİRMEZ.
    const PNG_WRITER = 'utils/imageEngine/pngWriter.js'
    // Dördüncü istisna (2026-11-14): görsel aracının akışlı ZIP yazıcısı (`imagetool/imageZip.js`) fflate'i YALNIZ dinamik
    // `import('fflate')` ile yükler (ZIP istenince) — yıl arşivi gibi.
    const IMAGE_ZIP = 'components/page/resourcescan/imagetool/imageZip.js'
    const FFLATE_OK = new Set([XLSX_WRITER, YEAR_ARCHIVE, PNG_WRITER, IMAGE_ZIP])
    const offenders = FILES.filter(({ f, rel: r, src }) => !f.startsWith(EXTRACT)
      && anyImports(src).some((s) => s === 'node-forge' || s.startsWith('node-forge/') || (s === 'fflate' && !FFLATE_OK.has(r))))
      .map((x) => x.rel)
    expect(offenders).toEqual([])
    const archive = FILES.find((x) => x.rel === YEAR_ARCHIVE)
    expect(staticImports(archive.src).filter((s) => s === 'fflate'), 'yıl arşivi fflate\'i statik içe aktarmaz').toEqual([])
    const imageZip = FILES.find((x) => x.rel === IMAGE_ZIP)
    expect(staticImports(imageZip.src).filter((s) => s === 'fflate'), 'görsel ZIP yazıcısı fflate\'i statik içe aktarmaz').toEqual([])
    // PNG yazıcısını motorun içinden yalnız ağır çekirdek / Worker içe aktarır; kamu arayüzü (index.js) almaz
    const pngUsers = FILES.filter(({ src }) => anyImports(src).some((s) => /(^|\/)pngWriter(\.js)?$/.test(s))).map((x) => x.rel).sort()
    expect(pngUsers.every((r) => r.startsWith('utils/imageEngine/') && r !== 'utils/imageEngine/index.js'), pngUsers.join(', ')).toBe(true)
    const users = FILES.filter(({ src }) => anyImports(src).some((s) => s.startsWith('node-forge') || s === 'fflate')).map((x) => x.rel).sort()
    // 2026-10-09: ZIP açma core.js'ten extract/zip.js'e taşındı (akışlı Inflate + kayıt sınırı) — yine extract/ altında.
    expect(users).toEqual(['components/manualcert/extract/crypto.js', 'components/manualcert/extract/zip.js', YEAR_ARCHIVE, XLSX_WRITER,
      PNG_WRITER, IMAGE_ZIP].sort())
  })

  it('Excel yazıcısı (fflate) yalnız tembel dışa aktarma modüllerinden gelir; onlar her yerde import() ile yüklenir', () => {
    const writerUsers = FILES.filter(({ src }) => anyImports(src).some((s) => /(^|\/)xlsxWriter(\.js)?$/.test(s))).map((x) => x.rel).sort()
    // 2026-10-10: kripto envanteri dışa aktarımı ikinci tembel kullanıcı (Excel / PDF / CSV).
    // 2026-10-17 (E22): Ayarlar → Teknik hata raporu kanal özeti Excel'i yazıcıyı YALNIZ tıklamada import() ile alır.
    const SETTINGS_EXPORT = 'components/admin/TechErrorReportSettings.jsx'
    // 2026-10-18 (C14): kanal görünümünün "Excel'e aktar" modülü (ChannelXlsxButton onu yalnız import() ile alır — lazyChunks kapısı)
    const CHANNEL_EXPORT = 'components/techerror/channelExport.js'
    // 2026-10-31: Sayfa Bütünlüğü kaynak analizi dışa aktarımı (Excel / PDF) — ResourceScanTab onu YALNIZ tıklamada import() ile alır
    const RESOURCE_SCAN_EXPORT = 'components/page/resourcescan/resourceScanExport.js'
    // 2026-11-05 (act E): aktivite dışa aktarım menüsü Excel'i yazıcıyı YALNIZ tıklamada import() ile alır
    const ACTIVITY_EXPORT = 'components/activity/ActivityExportMenu.jsx'
    // 2026-11-13: İstatistikler Excel / PDF dışa aktarımı — StatsView onu YALNIZ tıklamada import() ile alır
    const STATS_EXPORT = 'components/stats/statsExport.js'
    // 2026-11-13: Olaylar dışa aktarımı — IncidentsPage Excel yazıcısını YALNIZ tıklamada import() ile alır
    const INCIDENTS_EXPORT = 'components/IncidentsPage.jsx'
    // 2026-11-13: SQL Playground Excel'i (ekran sonucu + sunucuda tam dışa aktarım) — tek tembel modül; SqlPlayground ve
    // sonuç paneli onu YALNIZ tıklamada import() ile alır (aşağıda)
    const SQL_EXPORT = 'components/admin/sql/sqlExcelExport.js'
    expect(writerUsers).toEqual(['components/cryptoinv/cryptoInventoryExport.js', 'components/sharedcert/sharedCertExport.js', SETTINGS_EXPORT, CHANNEL_EXPORT, RESOURCE_SCAN_EXPORT, ACTIVITY_EXPORT, STATS_EXPORT, INCIDENTS_EXPORT, SQL_EXPORT].sort())
    expect(FILES.filter(({ src }) => staticImports(src).some((s) => /(^|\/)sqlExcelExport(\.js)?$/.test(s))).map((x) => x.rel),
      'SQL dışa aktarım modülü statik içe aktarılmaz').toEqual([])
    expect(FILES.filter(({ src }) => /import\(\s*'\.\/(sql\/)?sqlExcelExport\.js'\s*\)/.test(src)).map((x) => x.rel).sort())
      .toEqual(['components/admin/SqlPlayground.jsx', 'components/admin/sql/ResultsPanel.jsx'])
    const incidents = FILES.find((x) => x.rel === INCIDENTS_EXPORT)
    expect(staticImports(incidents.src).filter((s) => /(^|\/)xlsxWriter(\.js)?$/.test(s)), 'Olaylar Excel yazıcısını statik içe aktarmaz').toEqual([])
    const stats = FILES.find((x) => x.rel === STATS_EXPORT)
    expect(FILES.filter(({ src }) => staticImports(src).some((s) => /(^|\/)statsExport(\.js)?$/.test(s))).map((x) => x.rel), 'İstatistikler dışa aktarımı statik içe aktarılmaz').toEqual([])
    expect(stats, 'istatistik dışa aktarım modülü bulunur').toBeTruthy()
    const activity = FILES.find((x) => x.rel === ACTIVITY_EXPORT)
    expect(staticImports(activity.src).filter((s) => /(^|\/)xlsxWriter(\.js)?$/.test(s)), 'aktivite menüsü Excel yazıcısını statik içe aktarmaz').toEqual([])
    expect(FILES.filter(({ src }) => staticImports(src).some((s) => /resourceScanExport(\.js)?$/.test(s))).map((x) => x.rel),
      'kaynak analizi dışa aktarımı statik içe aktarılmaz').toEqual([])
    expect(FILES.filter(({ src }) => /import\(\s*'\.\/resourceScanExport\.js'\s*\)/.test(src)).map((x) => x.rel))
      .toEqual(['components/page/resourcescan/ResourceScanTab.jsx'])
    const settings = FILES.find((x) => x.rel === SETTINGS_EXPORT)
    expect(staticImports(settings.src).filter((s) => /(^|\/)xlsxWriter(\.js)?$/.test(s)), 'Ayarlar Excel yazıcısını statik içe aktarmaz').toEqual([])
    const staticExportUsers = FILES.filter(({ src }) => staticImports(src).some((s) => /(sharedCertExport|cryptoInventoryExport)(\.js)?$/.test(s))).map((x) => x.rel)
    expect(staticExportUsers).toEqual([])
    const lazyExportUsers = FILES.filter(({ src }) => /import\(\s*'\.\/sharedcert\/sharedCertExport\.js'\s*\)/.test(src)).map((x) => x.rel)
    expect(lazyExportUsers).toEqual(['components/SharedCertificateModal.jsx'])
    const lazyCryptoUsers = FILES.filter(({ src }) => /import\(\s*'\.\/cryptoInventoryExport\.js'\s*\)/.test(src)).map((x) => x.rel)
    expect(lazyCryptoUsers).toEqual(['components/cryptoinv/CryptoInventoryView.jsx'])
  })

  it('extract/index.js statik içe aktarma YAPMAZ; çekirdek dinamik import ya da Worker ile gelir', () => {
    const index = FILES.find((x) => x.rel === 'components/manualcert/extract/index.js')
    expect(staticImports(index.src)).toEqual([])
    expect(index.src).toMatch(/import\(\s*'\.\/core\.js'\s*\)/)
    expect(index.src).toMatch(/new Worker\(new URL\('\.\/extract\.worker\.js', import\.meta\.url\), \{ type: 'module' \}\)/)
    const worker = FILES.find((x) => x.rel === 'components/manualcert/extract/extract.worker.js')
    expect(staticImports(worker.src)).toEqual(['./core.js'])
  })

  it('extract/ modüllerini dışarıdan yalnız UploadWizard (index.js üzerinden) kullanır; sihirbaz her yerde lazy', () => {
    const outside = FILES.filter(({ f, src }) => !f.startsWith(EXTRACT) && anyImports(src).some((s) => /(^|\/)extract\//.test(s) && /manualcert|^\.\/extract/.test(s)))
    expect(outside.map((x) => x.rel)).toEqual(['components/manualcert/UploadWizard.jsx'])
    expect(anyImports(outside[0].src).filter((s) => /extract\//.test(s))).toEqual(['./extract/index.js'])
    const wizardStatic = FILES.filter(({ src }) => staticImports(src).some((s) => /UploadWizard(\.jsx)?$/.test(s))).map((x) => x.rel)
    expect(wizardStatic).toEqual([])
    const lazyUsers = FILES.filter(({ src }) => /lazy\(\(\) => import\('\.\/UploadWizard\.jsx'\)\)/.test(src)).map((x) => x.rel).sort()
    expect(lazyUsers.length).toBeGreaterThan(0)
  })

  it('açılış paketindeki manualCertModel.js ve App.jsx extract/ ya da kripto kütüphanesi içe aktarmaz', () => {
    for (const r of ['components/manualcert/manualCertModel.js', 'App.jsx', 'main.jsx']) {
      const x = FILES.find((y) => y.rel === r)
      expect(x, r).toBeTruthy()
      expect(anyImports(x.src).filter((s) => /extract\/|node-forge|fflate/.test(s)), r).toEqual([])
    }
  })
})
