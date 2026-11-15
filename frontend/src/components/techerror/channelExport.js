import { downloadBlob } from '../../utils/csvExport.js'
import { buildXlsx, XLSX_MIME } from '../../utils/xlsxWriter.js'
import { buildSheets, exportFileName, pivotExport } from './channelExportModel.js'

/**
 * Kanalın raporlarını Excel'e aktarma (C14, 2026-10-18). `ChannelXlsxButton` bu modülü TEMBEL yükler: Excel yazıcısı
 * (fflate) yalnız düğmeye basılınca iner (lazyChunks kapısı). İki sayfa: "Değerler" (alanlar satırda, aylar sütunda) ve
 * "Raporlar" (ay başına durum, gönderim tarihi, onay durumu). İndirilen dosyanın adını ve `ok` döner — tarayıcı indirmeyi
 * başlatamadıysa `ok: false` (çağıran başarı bildirimi göstermez, 2026-11-15).
 */
export function exportChannelXlsx(data, { channel, year, t, fmtDate }) {
  const pivot = pivotExport(data, channel?.id)
  const bytes = buildXlsx(buildSheets(pivot, { t, fmtDate }))
  const name = exportFileName(channel?.name, year, pivot.months)
  // utils/csvExport.downloadBlob (inceleme 2026-10-18): bağlantı DOM'da tıklanır, adres 60 sn tutulur — DOM dışı bağlantı ve
  // tıklamanın hemen ardından iptal edilen adres Safari / iPad'de indirmeyi başlatmıyordu (başarı bildirimi yine görünürdü)
  const ok = downloadBlob(name, new Blob([bytes], { type: XLSX_MIME }))
  return { ok, name, fields: pivot.fields.length, months: pivot.months.length }
}
