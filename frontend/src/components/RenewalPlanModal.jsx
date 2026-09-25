import { useState } from 'react'
import { CalendarPlus } from 'lucide-react'
import { api, formatDateOnly } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import ModalShell from './ui/ModalShell.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import { isHoliday, isWeekend, lastBusinessDay } from '../pages/forecastModel.js'
import { Button } from '@/components/shadcn/button'

/**
 * Yenileme planı modalı — Vade Takvimi'nden (ExpiryForecastPage) ÇIKARILDI (2026-09-19): Genel Bakış kartının
 * "Planla" kısayolu da aynı modalı kullanır. row: { domain, renewal_planned_at, renewal_planned_note, renew_by_key,
 * expiry_key }. onSaved/onCleared plan JSON'u alır ({ domain, renewal_planned_at, renewal_planned_by, renewal_planned_note }).
 */
/**
 * Alan adı izlemesi de aynı modalı kullanır (2026-09-22, H): `plan(date, note)` / `unplan()` verilirse envanter uçları
 * yerine onlar çağrılır; verilmezse eski davranış (sertifika envanteri).
 */
export default function RenewalPlanModal({ row, onClose, onSaved, onCleared, plan = null, unplan = null, hint = null }) {
  const t = useT(); const toast = useToast()
  const [date, setDate] = useState(row.renewal_planned_at || row.renew_by_key || '')
  const [note, setNote] = useState(row.renewal_planned_note || '')
  const [busy, setBusy] = useState(false)
  async function save() {
    setBusy(true)
    try { const r = plan ? await plan(date, note) : await api.forecastPlan(row.domain, date, note); if (r?.success) { toast.success(t('forecast.planSaved', row.domain)); onSaved(r.data) } else toast.error(r?.error || t('forecast.planError')) }
    catch (e) { toast.error(e?.message || t('forecast.planError')) } finally { setBusy(false) }
  }
  async function clear() {
    setBusy(true)
    try { const r = unplan ? await unplan() : await api.forecastUnplan(row.domain); if (r?.success) { toast.success(t('forecast.planCleared', row.domain)); onCleared(r.data) } else toast.error(r?.error || t('forecast.planError')) }
    catch (e) { toast.error(e?.message || t('forecast.planError')) } finally { setBusy(false) }
  }
  return (
    <ModalShell open onClose={onClose} title={t('forecast.planTitle', row.domain)} icon={CalendarPlus}
      footer={<>
        {row.renewal_planned_at && <Button type="button" variant="destructive" disabled={busy} onClick={clear}>{t('forecast.planClear')}</Button>}
        <Button type="button" variant="secondary" onClick={onClose}>{t('inv.cancel')}</Button>
        <Button type="button" disabled={busy || !date} onClick={save}>{t('forecast.planSave')}</Button>
      </>}>
      {/* hint: çağıran yüzey metni değiştirebilir (alan adında "yeni sertifika"/"en geç" anlamsız — 2026-09-22, H) */}
      <p className="field-hint">{hint || t('forecast.planHint', row.expiry_key ? formatDateOnly(row.expiry_key) : '—', row.renew_by_key ? formatDateOnly(row.renew_by_key) : '—')}</p>
      <label className="full-width"><span>{t('forecast.planDate')}</span><input type="date" className="input" value={date} onChange={(e) => setDate(e.target.value)} /></label>
      {date && (isWeekend(date) || isHoliday(date)) && <AlertBanner tone="warning">{t('forecast.planOffDay', formatDateOnly(lastBusinessDay(date)))}</AlertBanner>}
      <label className="full-width"><span>{t('forecast.planNote')}</span><textarea className="input" rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder={t('forecast.planNotePh')} /></label>
    </ModalShell>
  )
}
