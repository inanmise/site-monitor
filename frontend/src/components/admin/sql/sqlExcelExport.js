import { downloadBlob } from '../../../utils/csvExport.js'
import { XLSX_MIME, buildXlsx } from '../../../utils/xlsxWriter.js'
import { excelSheet } from './sqlLibraryModel.js'
import { stampedFile } from './sqlUtils.js'

/**
 * SQL Playground Excel dışa aktarımı (2026-11-13, A3 / C3) — TEMBEL modül: Excel yazıcısını (fflate) yalnız bu dosya
 * içe aktarır; SqlPlayground ve sonuç paneli onu YALNIZ tıklamada dinamik içe aktarmayla alır
 * (`manualCertExtractLazy` / `lazyChunks` kapıları). İndirme `utils/csvExport.downloadBlob` ile (Safari / iPad).
 *
 * @param rows    satır nesneleri
 * @param cols    kolon sırası (başlık)
 * @param types   kolon → tür (`number` kolonları sayı hücresi)
 * @param prefix  dosya adı öneki (`query` ekran sonucu, `sql` sunucuda tam dışa aktarım)
 * @returns indirme başladıysa true
 */
export function downloadSqlExcel(rows, cols, types, prefix = 'query') {
  const bytes = buildXlsx([excelSheet(rows, cols, types)])
  return downloadBlob(stampedFile(prefix, 'xlsx'), new Blob([bytes], { type: XLSX_MIME }))
}
