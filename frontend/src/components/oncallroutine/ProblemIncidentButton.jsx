import { useCallback, useEffect, useRef, useState } from 'react'
import { ClipboardPlus, EyeOff, ListChecks } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { serverFieldErrors } from '../../utils/formErrors.js'
import { isUnsureResult } from '../../utils/unsureResult.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import IncidentFormModal from '../incidenthistory/IncidentFormModal.jsx'
import { EMPTY_FORM } from '../incidenthistory/incidentHistoryModel.js'
import { codeOf, shortDate } from './routineModel.js'

/** Olay formunun yönetilen seçim listeleri: form prop anahtarı ↔ sunucu seçenek tipi (AlertIncidentLink ile aynı). */
const OPTION_TYPES = [
  ['channel', 'CHANNEL'], ['domain', 'DOMAIN'], ['errorCode', 'ERROR_CODE'],
  ['functionCode', 'FUNCTION_CODE'], ['channelCode', 'CHANNEL_CODE'],
]
const EMPTY_OPTIONS = Object.freeze({ channel: [], domain: [], errorCode: [], functionCode: [], channelCode: [] })
const ACTION = 'pointer-coarse:min-h-10'
const INCIDENT_STATUSES = new Set(['OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED'])
const SEVERITY_MAP = Object.freeze({ LOW: 'LOW', MEDIUM: 'MEDIUM', HIGH: 'HIGH' })
const TITLE_MAX = 120

/** Sunucu zaman damgası → olay formunun UTC ISO biçimi (`yyyy-MM-ddTHH:mm:ss`, ek yok); ayrıştırılamazsa ''. */
export function toFormIso(s) {
  if (!s) return ''
  const raw = String(s).trim()
  const zoned = /[zZ]$|[+-]\d\d:?\d\d$/.test(raw)
  if (zoned) {
    const ms = Date.parse(raw)
    return Number.isFinite(ms) ? new Date(ms).toISOString().slice(0, 19) : ''
  }
  const v = raw.replace(/\.\d+$/, '')
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(v)) return v
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(v)) return `${v}:00`
  return ''
}

/** Notun ilk dolu satırı, en çok `max` karakter (kesilirse "…" ile). */
export function firstLine(text, max = TITLE_MAX) {
  const line = String(text ?? '').split('\n').map((s) => s.trim()).find(Boolean) || ''
  return line.length > max ? `${line.slice(0, max - 1).trimEnd()}…` : line
}

/**
 * Sorundan olay kaydı formunun ÖN DOLGUSU (IncidentFormModal `initial`). Başlık = "görev: notun ilk satırı"; açıklama = not +
 * nereden aktarıldığı; önem 1:1; durum Açık; kategori Diğer; takım = rutinin takımı; oluş / tespit = sorunun açılışı.
 */
export function incidentPrefillFromProblem(problem, { teamId, teamName, t }) {
  const note = String(problem?.note ?? '')
  const head = problem?.task_title ? `${problem.task_title}: ` : ''
  const at = toFormIso(problem?.created_at)
  return {
    ...EMPTY_FORM,
    title: `${head}${firstLine(note, TITLE_MAX)}`.slice(0, 255),
    description: `${note}\n\n${t('or.prob.incidentFooter', teamName || '', shortDate(problem?.day, true))}`,
    severity: SEVERITY_MAP[problem?.severity] || 'MEDIUM',
    status: 'OPEN',
    category: 'OTHER',
    team_id: teamId != null ? String(teamId) : '',
    team_name: teamName || '',
    detected_at: at,
    occurred_at: at,
  }
}

/** Bağlı olay kaydı çipi — "Olay #812 · Açık" (Olay & Hata Geçmişi'nde o kaydı açar) ya da görme yetkisi yoksa düz metin. */
export function IncidentChip({ incident }) {
  const t = useT()
  if (!incident || incident.id == null) return null
  if (incident.hidden) {
    return (
      <Badge variant="outline" data-slot="or-problem-incident-hidden" data-incident-id={incident.id}
        className="h-auto gap-1 rounded-md py-1 whitespace-normal text-muted-foreground overflow-visible">
        <EyeOff aria-hidden="true" />{t('or.prob.incidentHidden', incident.id)}
      </Badge>
    )
  }
  const status = INCIDENT_STATUSES.has(incident.status) ? t(`inc.st${incident.status}`) : null
  return (
    <Button type="button" variant="outline" size="sm" className={`h-auto min-h-8 whitespace-normal text-left ${ACTION}`}
      data-slot="or-problem-incident-link" data-incident-id={incident.id} title={incident.title || undefined}
      onClick={() => navigateTo('incident-history', { ih_id: String(incident.id) })}>
      <ListChecks aria-hidden="true" />
      <span className="[overflow-wrap:anywhere]">
        {status ? t('or.prob.incidentChip', incident.id, status) : t('or.prob.incidentChipPlain', incident.id)}
      </span>
    </Button>
  )
}

/** Bağlama yanıtından sorun (ya da null). */
function problemOf(res) {
  const d = res?.data?.problem ?? res?.data
  return d && typeof d === 'object' && d.id != null ? d : null
}

/**
 * "Olay kaydı oluştur" (2026-11-26): sorundan Olay & Hata Geçmişi kaydı açar ve soruna bağlar. Yalnız olay kaydı
 * OLUŞTURABİLENE görünür (`incidents.manage` / edit — Olay & Hata Geçmişi'nin "Yeni kayıt" kapısı) ve sorun henüz bağlı
 * değilken. Form Olay & Hata Geçmişi'nin KENDİ formudur (IncidentFormModal; AlertIncidentLink deseni) — sorundan ön doldurulur,
 * "Kaydet" demeden kayıt oluşmaz.
 *
 * Kayıt oluşunca soruna bağlanır (`PUT /problems/{id}/incident`, sürüm denetimli). Sürüm çakışması (409) yanıttaki güncel
 * sorunla BİR kez yeniden denenir; yine olmazsa "Olay kaydı #N oluştu ama bağlanamadı" + kaydı açan bağlantı. Kayıt
 * oluştuktan sonraki hiçbir hata yeni kayıt açtırmaz (form kapanır).
 *
 * Props: `{ teamId, teamName, problem, onChange, disabled }` — `onChange(problem)` bağlanan sorunla.
 */
export default function ProblemIncidentButton({ teamId, teamName, problem, onChange = () => {}, disabled = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { canEdit } = usePermissions()
  const canCreate = canEdit('incidents.manage')
  const [form, setForm] = useState(null)          // açık form: { initial }
  const [teams, setTeams] = useState([])
  const [options, setOptions] = useState(EMPTY_OPTIONS)
  const problemRef = useRef(problem)
  problemRef.current = problem
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

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

  if (!canCreate || !problem || problem.id == null || problem.incident) return null

  const openForm = () => setForm({ initial: incidentPrefillFromProblem(problem, { teamId, teamName, t }) })

  /** Oluşan kaydı soruna bağlar — sürüm çakışmasında yanıttaki güncel sürümle bir kez daha dener. */
  async function link(created) {
    const incidentId = created?.id
    if (incidentId == null) return
    const summary = { id: incidentId, title: created.title ?? null, status: created.status ?? 'OPEN' }
    const call = async (version) => {
      try {
        return await api.oncallRoutines.linkIncident(problemRef.current.id, { version, incident_id: incidentId })
      } catch {
        return { success: false, status: 0, code: 'NETWORK_ERROR' }
      }
    }
    let res = await call(problemRef.current.version)
    if (res === null) return
    if (!res?.success && codeOf(res) === 'CONCURRENT_MODIFICATION') {
      const current = res.current && typeof res.current === 'object' ? res.current : null
      if (current?.version != null) {
        // Başka biri arada değiştirdi: güncel hâl hemen yansır; aynı olay kaydıyla güncel sürüme bir kez daha bağlanır
        onChangeRef.current(current)
        problemRef.current = current
        res = await call(current.version)
        if (res === null) return
      }
    }
    if (res?.success) {
      const linked = problemOf(res)
      onChangeRef.current(linked ? (linked.incident ? linked : { ...linked, incident: summary })
        : { ...problemRef.current, incident: summary })
      return
    }
    const open = () => navigateTo('incident-history', { ih_id: String(incidentId) })
    toast.action(isUnsureResult(res) ? t('or.prob.linkUnsure', incidentId) : t('or.prob.linkRefused', incidentId), {
      label: t('or.prob.openIncident'), onAction: open, duration: 15000,
    })
  }

  async function submit(payload) {
    let res
    try {
      res = await api.incidents.create(payload)
    } catch {
      return { ok: false, error: t('inc.saveError') }
    }
    // fields: sunucunun alan hataları (400 VALIDATION_FAILED) — form iletiyi ilgili alanın altında gösterir
    if (!res?.success) return { ok: false, error: res?.error || t('inc.saveError'), fields: serverFieldErrors(res) }
    setForm(null)
    toast.success(t('or.prob.incidentCreated', res.data?.id ?? ''))
    // Bağlama formu BEKLETMEZ: form kaydı tamamladı (taslağı silinir); bağlantı arkada kurulur, sonucu bildirilir
    void link(res.data)
    return { ok: true, data: res.data }
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
      <Button type="button" variant="outline" size="sm" className={ACTION} data-slot="or-problem-incident"
        disabled={disabled} title={t('or.prob.incidentHint')} onClick={openForm}>
        <ClipboardPlus aria-hidden="true" />{t('or.prob.incidentCreate')}
      </Button>
      {form && (
        <IncidentFormModal key={`or-problem:${problem.id}`} mode="create" initial={form.initial} teams={teams} options={options}
          onAddOption={addOption} onDeleteOption={deleteOption} onSubmit={submit} onClose={() => setForm(null)} />
      )}
    </>
  )
}
