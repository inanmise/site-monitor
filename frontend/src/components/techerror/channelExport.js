import { triggerDownload } from '../../utils/exportInventory.js'
import { buildXlsx, XLSX_MIME } from '../../utils/xlsxWriter.js'
import { buildSheets, exportFileName, pivotExport } from './channelExportModel.js'

/**
 * Kanalın raporlarını Excel'e aktarma (C14, 2026-10-18). `ChannelXlsxButton` bu modülü TEMBEL yükler: Excel yazıcısı
 * (fflate) yalnız düğmeye basılınca iner (lazyChunks kapısı). İki sayfa: "Değerler" (alanlar satırda, aylar sütunda) ve
 * "Raporlar" (ay başına durum, gönderim tarihi, onay durumu). İndirilen dosyanın adını döner.
 */
export function exportChannelXlsx(data, { channel, year, t, fmtDate }) {
  const pivot = pivotExport(data, channel?.id)
  const bytes = buildXlsx(buildSheets(pivot, { t, fmtDate }))
  const name = exportFileName(channel?.name, year, pivot.months)
  triggerDownload(new Blob([bytes], { type: XLSX_MIME }), name)
  return { name, fields: pivot.fields.length, months: pivot.months.length }
}
