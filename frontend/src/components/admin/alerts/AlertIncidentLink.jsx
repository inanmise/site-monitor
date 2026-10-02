import { useCallback, useEffect, useState } from 'react'
import { ClipboardPlus, ListChecks } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { usePermissions } from '../../../contexts/PermissionsProvider.jsx'
import { navigateTo } from '../../../utils/navigate.js'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { Button } from '@/components/shadcn/button'
import IncidentFormModal from '../../incidenthistory/IncidentFormModal.jsx'
import { EMPTY_FORM } from '../../incidenthistory/incidentHistoryModel.js'
import { incidentPrefillFromAlert } from './alertIncidentModel.js'

/** Olay formunun yönetilen seçim listeleri: form prop anahtarı ↔ sunucu seçenek tipi. */
const OPTION_TYPES = [
  ['channel', 'CHANNEL'], ['domain', 'DOMAIN'], ['errorCode', 'ERROR_CODE'],
  ['functionCode', 'FUNCTION_CODE'], ['channelCode', 'CHANNEL_CODE'],
]
const EMPTY_OPTIONS = Object.freeze({ channel: [], domain: [], errorCode: [], functionCode: [], channelCode: [] })
const ACTION = 'h-9 pointer-coarse:h-10'

/** Olay kaydı → bağlantı özeti (oluşturma yanıtı ya da /by-alert satırı). */
const summaryOf = (r) => ({ id: r.id, title: r.title, status: r.status })

/**
 * Alarm detayında "Olay kaydı aç" / "Olay kaydı #N" (2026-10-01, ürün sahibi taahhüdü: "Yeni bir düğme eklenir").
 *
 * <p><b>Görünürlük.</b> Düğme yalnız olay kaydı OLUŞTURABİLENE görünür — Olay & Hata Geçmişi'nin "Yeni kayıt" düğmesiyle
 * ve `POST /api/incidents` kapısıyla AYNI izin: `incidents.manage` / edit. Bağlı kayıtlar yalnız `incidents.view` sahibine
 * çekilir (tek istek, yalnız detay açıkken — liste satırı başına istek yok); sunucu olay kaydı takım kapsamını uygular.
 *
 * <p><b>Akış.</b> Düğme Olay & Hata Geçmişi'nin KENDİ formunu (IncidentFormModal, ikinci form yok) alarmdan ön doldurulmuş
 * açar; kullanıcı her alanı değiştirebilir, "Kaydet" demeden kayıt oluşmaz. Kayıt `alert_event_id`'yi taşır.
 *
 * <p><b>Zaten bağlı kayıt varsa</b> düğme yerine bağlantı(lar) görünür: "Olay kaydı #N" → Olay & Hata Geçmişi'nde o kaydın
 * ayrıntısı. İkinci bir kayıt gerekiyorsa Olay & Hata Geçmişi'nden açılır (tek, tutarlı yol; aynı alarm için yanlışlıkla
 * mükerrer kayıt açılmaz).
 */
export default function AlertIncidentLink({ alert: a, teamName }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { canView, canEdit } = usePermissions()
  const canCreate = canEdit('incidents.manage')
  const canSee = canView('incidents.view')
  const alertId = a?.id
  const [linked, setLinked] = useState([])
  const [form, setForm] = useState(null)          // açık form: { initial }
  const [teams, setTeams] = useState([])
  const [options, setOptions] = useState(EMPTY_OPTIONS)

  useEffect(() => {
    setLinked([])
    if (alertId == null || !canSee) return undefined
    let alive = true
    Promise.resolve().then(() => api.incidents.byAlert(alertId)).catch(() => null).then((res) => {
      if (alive && res?.success && Array.isArray(res.data)) setLinked(res.data.map(summaryOf))
    })
    return () => { alive = false }
  }, [alertId, canSee])

  const loadOptions = useCallback(async () => {
    const res = await Promise.all(OPTION_TYPES.map(([, type]) =>
      Promise.resolve().then(() => api.incidents.options(type)).catch(() => null)))
    const next = {}
    OPTION_TYPES.forEach(([key], i) => { next[key] = res[i]?.success && Array.isArray(res[i].data) ? res[i].data : [] })
    setOptions(next)
  }, [])

  // Form açılınca takım listesi + yönetilen seçenekler (Olay & Hata Geçmişi ile aynı uçlar); form beklemeden açılır.
  useEffect(() => {
    if (!form) return undefined
    let alive = true
    Promise.resolve().then(() => api.admin.getTeams()).catch(() => null).then((res) => {
      if (alive && res?.success && Array.isArray(res.data)) setTeams(res.data)
    })
    loadOptions()
    return () => { alive = false }
  }, [form, loadOptions])

  if (!a || alertId == null) return null
  if (!canCreate && linked.length === 0) return null

  const openForm = () => setForm({ initial: { ...EMPTY_FORM, ...incidentPrefillFromAlert(a, t, { teamName }) } })

  async function submit(payload) {
    try {
      const res = await api.incidents.create(payload)
      if (!res?.success) return { ok: false, error: res?.error || t('inc.saveError') }
      toast.success(t('alh.incident.created', res.data?.id ?? ''))
      if (res.data?.id != null) setLinked((l) => [summaryOf(res.data), ...l.filter((x) => x.id !== res.data.id)])
      setForm(null)
      return { ok: true, data: res.data }
    } catch {
      return { ok: false, error: t('inc.saveError') }
    }
  }

  const addOption = async (type, value) => {
    let res = null
    try { res = await api.incidents.addOption(type, value) } catch { res = null }
    if (res?.success) await loadOptions()
    else toast.error(res?.error || t('inc.saveError'))
  }
  const deleteOption = async (type, value) => {
    const ok = await showConfirm({
      title: t('inc.optDeleteTitle'), message: t('inc.optDeleteConfirm', value),
      variant: 'danger', confirmText: t('inc.delete'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    let res = null
    try { res = await api.incidents.deleteOption(type, value) } catch { res = null }
    if (res?.success) await loadOptions()
    else toast.error(res?.error || t('inc.saveError'))
  }

  return (
    <>
      {linked.map((r) => (
        <Button key={r.id} type="button" variant="outline" size="sm" className={ACTION} data-slot="alert-incident-link"
          data-incident-id={r.id} title={r.title || undefined}
          onClick={() => navigateTo('incident-history', { ih_id: String(r.id) })}>
          <ListChecks aria-hidden="true" />{t('alh.incident.link', r.id)}
        </Button>
      ))}
      {canCreate && linked.length === 0 && (
        <Button type="button" variant="outline" size="sm" className={ACTION} data-slot="alert-incident-open"
          title={t('alh.incident.openHint')} onClick={openForm}>
          <ClipboardPlus aria-hidden="true" />{t('alh.incident.open')}
        </Button>
      )}
      {form && (
        <IncidentFormModal key={`alert:${alertId}`} mode="create" initial={form.initial} teams={teams} options={options}
          onAddOption={addOption} onDeleteOption={deleteOption} onSubmit={submit} onClose={() => setForm(null)} />
      )}
    </>
  )
}
