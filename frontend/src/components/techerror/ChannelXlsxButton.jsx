import { useRef, useState } from 'react'
import { FileSpreadsheet, RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Button } from '@/components/shadcn/button'
import { fmtDateTime } from './TechErrorParts.jsx'
import { exportParams } from './channelExportModel.js'

/**
 * "Excel'e aktar" (C14, 2026-10-18): kanalın raporlarındaki değerleri (aylar sütunlarda, alanlar satırlarda) `.xlsx`
 * olarak indirir. Veri sunucunun değer dışa aktarımından (E23) gelir — tek kanal, tüm durumlar (taslaklar dahil); yıl
 * süzgeci seçiliyse o yıl, "Tüm yıllar"da kanalın bütün yılları. Excel yazıcısı (fflate) yalnız basılınca yüklenir.
 * Okuma kapısı sunucuda (kanalı okuyabilen herkes; paylaşımla okuyan da — dışa aktarımda kişi verisi yok).
 */
export default function ChannelXlsxButton({ channel, year }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)

  async function run() {
    if (busyRef.current || !channel?.id) return
    busyRef.current = true
    setBusy(true)
    try {
      const res = await api.techErrors.exportValues(exportParams(channel, year))
      if (!res?.success || !res.data) {
        toast.error(res?.error || t('te.xlsx.loadError'), { details: res?.errorInfo })
        return
      }
      const hasData = (res.data.rows?.length || 0) > 0 || (res.data.reports?.length || 0) > 0
      if (!hasData) {
        toast.info(t('te.xlsx.empty'))
        return
      }
      const mod = await import('./channelExport.js')
      const out = mod.exportChannelXlsx(res.data, { channel, year, t, fmtDate: (iso) => fmtDateTime(iso, lang) })
      toast.success(t('te.xlsx.done', out.name))
    } catch (e) {
      toast.error(e?.message || t('te.xlsx.loadError'))
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  return (
    <Button type="button" variant="outline" onClick={run} disabled={busy} aria-busy={busy || undefined}
      title={t('te.xlsx.hint')} data-slot="te-channel-xlsx" className="pointer-coarse:min-h-10 sm:ml-auto">
      {busy
        ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" />
        : <FileSpreadsheet aria-hidden="true" />}
      {busy ? t('te.xlsx.busy') : t('te.xlsx.button')}
    </Button>
  )
}
