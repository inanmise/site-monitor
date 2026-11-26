import { useEffect } from 'react'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import { channelReportsVisibility } from '../utils/landingTabs.js'

/**
 * App gövdesi PermissionsProvider'ın DIŞINDA çizilir; açılış sekmesi kararı Kanal Teknik Hata Raporları görünürlüğünü
 * (`scope.channel_reports`) sağlayıcının ALTINDAKİ bu boş bileşenden alır (AlarmsPermissionProbe deseni). Değer
 * true / false / null (okunmadı ya da okunamadı — bilinmiyor) — yalnız DEĞİŞİNCE bildirilir (ilkel, yeniden çizim yok).
 */
export default function ChannelReportsScopeProbe({ onChange }) {
  const { scope, loaded } = usePermissions()
  const visible = channelReportsVisibility(scope, loaded)
  useEffect(() => { onChange?.(visible) }, [visible, onChange])
  return null
}
