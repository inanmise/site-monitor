import { useId, useRef, useState } from 'react'
import { FileSpreadsheet, RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Button } from '@/components/shadcn/button'
import { ApiError } from '../../utils/errorMessages.js'
import { fmtDateTime } from './TechErrorParts.jsx'
import { exportParams } from './channelExportModel.js'

/**
 * Tarayıcı tarafı hatanın künyesi (Teknik ayrıntı → "Hata kodu"): sabit kod + tarayıcının kendi metni (kısaltılmış).
 * Tarayıcı metni ("Failed to fetch dynamically imported module …", "x is not a function") YALNIZ burada görünür;
 * kullanıcıya giden ileti çevrilmiş metindir.
 */
export function clientErrorCode(code, e) {
  const name = typeof e?.name === 'string' && e.name ? e.name : ''
  const msg = typeof e?.message === 'string' ? e.message.trim().slice(0, 160) : ''
  const raw = name || msg ? `${name}${name && msg ? ': ' : ''}${msg}` : ''
  return raw ? `${code} (${raw})` : code
}

/**
 * "Excel'e aktar" (C14, 2026-10-18): kanalın raporlarındaki değerleri (aylar sütunlarda, alanlar satırlarda) `.xlsx`
 * olarak indirir. Veri sunucunun değer dışa aktarımından (E23) gelir — tek kanal, tüm durumlar (taslaklar dahil); yıl
 * süzgeci seçiliyse o yıl, "Tüm yıllar"da kanalın bütün yılları. Listenin durum / arama süzgeçleri UYGULANMAZ (düğmenin
 * açıklaması bunu söyler). Excel yazıcısı (fflate) yalnız basılınca yüklenir. Okuma kapısı sunucuda (kanalı okuyabilen
 * herkes; paylaşımla okuyan da — dışa aktarımda kişi verisi yok).
 *
 * Hatalar iki ayrı yerden gelir: değerleri OKUMA (sunucu / ağ — sunucunun ya da istemcinin açıklayıcı metni, künyesiyle)
 * ve dosyayı tarayıcıda KURMA (tembel modül yüklenemedi, yazıcı hatası — yalnız çevrilmiş metin; tarayıcı metni künyede).
 */
export default function ChannelXlsxButton({ channel, year }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const hintId = useId()
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const y = Number(year)
  const hint = year !== 'all' && Number.isInteger(y) && y > 0 ? t('te.xlsx.hintYear', y) : t('te.xlsx.hint')

  async function run() {
    if (busyRef.current || !channel?.id) return
    busyRef.current = true
    setBusy(true)
    try {
      let res
      try {
        res = await api.techErrors.exportValues(exportParams(channel, year))
      } catch (e) {
        // İstemcinin ağ / zaman aşımı hatası (ApiError) zaten açıklayıcıdır; başka bir şey fırladıysa ham metni gösterme
        const known = e instanceof ApiError && typeof e.message === 'string' && e.message.trim()
        toast.error(known ? e.message : t('te.xlsx.loadError'),
          { details: known ? e : { code: clientErrorCode('XLSX_LOAD_FAILED', e) } })
        return
      }
      if (!res?.success || !res.data) {
        toast.error(res?.error || t('te.xlsx.loadError'), { details: res?.errorInfo ?? res })
        return
      }
      const hasData = (res.data.rows?.length || 0) > 0 || (res.data.reports?.length || 0) > 0
      if (!hasData) {
        toast.info(t('te.xlsx.empty'))
        return
      }
      let out
      try {
        const mod = await import('./channelExport.js')
        out = mod.exportChannelXlsx(res.data, { channel, year, t, fmtDate: (iso) => fmtDateTime(iso, lang) })
      } catch (e) {
        toast.error(t('te.xlsx.buildFailedText'), { details: { code: clientErrorCode('XLSX_BUILD_FAILED', e) } })
        return
      }
      toast.success(t('te.xlsx.done', out.name))
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  return (
    <>
      <Button type="button" variant="outline" onClick={run} disabled={busy} aria-busy={busy || undefined}
        title={hint} aria-describedby={hintId} data-slot="te-channel-xlsx" className="pointer-coarse:min-h-10 sm:ml-auto">
        {busy
          ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" />
          : <FileSpreadsheet aria-hidden="true" />}
        {busy ? t('te.xlsx.busy') : t('te.xlsx.button')}
      </Button>
      <span id={hintId} className="sr-only" data-slot="te-channel-xlsx-hint">{hint}</span>
    </>
  )
}
