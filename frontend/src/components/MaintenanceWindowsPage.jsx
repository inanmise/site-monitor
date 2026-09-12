import { LoadingBlock } from './ui/Progress.jsx'
import { useState, useEffect, useCallback, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { usePagination } from '../hooks/usePagination.js'
import PaginationBar from './ui/PaginationBar.jsx'
import MultiTeamSelect from './ui/MultiTeamSelect.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import DateTimeField from './ui/DateTimeField.jsx'
import MonthCalendar from './ui/MonthCalendar.jsx'
import ModalShell from './ui/ModalShell.jsx'
import { Wrench, Plus, Play, Pencil, Trash2, Pause, RefreshCw, History, CalendarDays } from 'lucide-react'

const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

const RECURRENCES = ['NONE', 'DAILY', 'WEEKLY', 'MONTHLY']
const DOW = [1, 2, 3, 4, 5, 6, 7]   // Pzt..Paz (ISO)
const TZ_LIST = (() => {
  try { return Intl.supportedValuesOf('timeZone') } catch { return ['Europe/Istanbul', 'UTC', 'Europe/London', 'America/New_York'] }
})()
const MON_TYPES = [
  ['http', 'getHttpMonitors', m => m.url],
  ['port', 'getPortMonitors', m => m.host],
  ['keyword', 'getKeywordMonitors', m => m.url],
  ['ping', 'getPingMonitors', m => m.host],
  ['page', 'getPageMonitors', m => m.url],
  ['pagespeed', 'getPageSpeedMonitors', m => m.url],
  ['dns', 'getDnsMonitors', m => m.domain],
  ['domain', 'getDomainMonitors', m => m.domain],
  ['cert', 'getUptimeOverview', m => m.domain],
  // Sentetik: iki farklılık var — liste ucu {monitors:[…]} ile sarmalıyor ve alarm anahtarı
  // monitörün ADI (SweepItem.domain = m.getName()), diğer türlerdeki url/host/domain değil.
  // Motor tarafı zaten hazırdı (isUnderMaintenance sentetik alarmın da geçtiği yolda); eksik
  // olan yalnız bu listeydi, bu yüzden planlı kesintide sentetik monitörler susturulamıyor,
  // tek çare "tüm monitörler" bayrağıyla her şeyi birden susturmaktı.
  ['scripted', 'getScriptedMonitors', m => m.name, d => d?.monitors || []],
]
const emptyForm = {
  name: '', description: '', allMonitors: false, targets: [], timezone: 'Europe/Istanbul',
  startAt: '', durationMinutes: 60, recurrence: 'NONE', daysOfWeek: [], dayOfMonth: 1,
}

function todInTz(utcIso, tz) {
  if (!utcIso) return ''
  try {
    return new Date(utcIso.endsWith('Z') ? utcIso : utcIso + 'Z')
      .toLocaleTimeString([], { timeZone: tz, hour: '2-digit', minute: '2-digit', hour12: false })
  } catch { return '' }
}

export default function MaintenanceWindowsPage({ systemRole }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const toast = useToast()
  const canManage = systemRole === 'ADMIN' || systemRole === 'TEAM_ADMIN'

  const [rows, setRows] = useState([])
  const [calOpen, setCalOpen] = useState(() => { try { return localStorage.getItem('mw-cal-open') === 'true' } catch { return false } })
  // Takvim olayları (2026-09-12, #19): sıradaki oluşum (next_occurrence) ya da tek seferlik başlangıç; aynı güne 2+ pencere = çakışma adayı
  const mwCalEvents = rows.filter(w => w.next_occurrence || w.start_at).map(w => ({
    date: w.next_occurrence || w.start_at, label: w.name, title: `${w.name} · ${w.duration_minutes ?? 60} ${t('chg.unitMin')}`,
    tone: w.status === 'ACTIVE' ? 'warn' : 'info', onClick: () => openEdit(w),
  }))
  const pager = usePagination(rows, { listKey: 'maintenance-windows' })
  const [loading, setLoading] = useState(true)
  const [modal, setModal] = useState(null)          // 'new' | window | 'quick'
  const [historyItem, setHistoryItem] = useState(null)   // değişiklik geçmişi penceresi
  const [form, setForm] = useState(emptyForm)
  const [quickForm, setQuickForm] = useState({ allMonitors: false, targets: [], minutes: 30, name: '' })
  const [saving, setSaving] = useState(false)
  const [monitorOptions, setMonitorOptions] = useState([])
  const [optsLoaded, setOptsLoaded] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await api.monitoring.maintenance.list()
      if (res?.success) setRows(res.data ?? [])
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => { load() }, [load])

  async function loadMonitorOptions() {
    if (optsLoaded) return
    const out = []
    await Promise.all(MON_TYPES.map(async ([type, fn, key, pick]) => {
      try {
        const res = await api.monitoring[fn]?.()
        // `pick`: yanıtı düz diziye indirger — sentetik uç {monitors:[…]} ile sarmalıyor.
        const list = res?.success ? (pick ? pick(res.data) : (res.data || [])) : []
        for (const m of list) {
          const target = key(m)
          if (!target) continue
          out.push({ value: target, target, type, name: m.name || target, label: `${m.name || target} · ${t('mw.type.' + type)}` })
        }
      } catch { /* atla */ }
    }))
    const seen = new Set()
    setMonitorOptions(out.filter(o => seen.has(o.value) ? false : (seen.add(o.value), true)))
    setOptsLoaded(true)
  }

  function openNew() { setForm(emptyForm); loadMonitorOptions(); setModal('new') }
  function openEdit(w) {
    loadMonitorOptions()
    setForm({
      name: w.name || '', description: w.description || '', allMonitors: !!w.all_monitors,
      targets: (w.targets || []).map(x => x.target).filter(Boolean),
      timezone: w.timezone || 'Europe/Istanbul', startAt: w.start_at || '', durationMinutes: w.duration_minutes ?? 60,
      recurrence: w.recurrence || 'NONE',
      daysOfWeek: w.days_of_week ? w.days_of_week.split(',').map(Number) : [],
      dayOfMonth: w.day_of_month ?? 1,
    })
    setModal(w)
  }
  function openQuick() { setQuickForm({ allMonitors: false, targets: [], minutes: 30, name: '' }); loadMonitorOptions(); setModal('quick') }
  function close() { setModal(null) }

  function targetObjs(vals) {
    return vals.map(tg => { const o = monitorOptions.find(x => x.value === tg); return o ? { type: o.type, target: o.value, name: o.name } : { type: '?', target: tg, name: tg } })
  }

  async function save() {
    if (!form.name.trim()) { toast.error(t('mw.nameRequired')); return }
    if (!form.startAt) { toast.error(t('mw.startRequired')); return }
    if (!form.allMonitors && form.targets.length === 0) { toast.error(t('mw.targetsRequired')); return }
    // Sessizce inert (hiç tetiklenmeyen) pencereyi önle (M9)
    if (!(Number(form.durationMinutes) >= 1)) { toast.error(t('mw.durationRequired')); return }
    if (form.recurrence === 'WEEKLY' && form.daysOfWeek.length === 0) { toast.error(t('mw.weekdayRequired')); return }
    if (form.recurrence === 'MONTHLY' && !(Number(form.dayOfMonth) >= 1 && Number(form.dayOfMonth) <= 31)) { toast.error(t('mw.dayOfMonthRequired')); return }
    setSaving(true)
    try {
      const payload = {
        name: form.name.trim(), description: form.description?.trim() || null, allMonitors: form.allMonitors,
        targets: form.allMonitors ? [] : targetObjs(form.targets),
        timezone: form.timezone, startAt: form.startAt, durationMinutes: Number(form.durationMinutes), recurrence: form.recurrence,
        daysOfWeek: form.recurrence === 'WEEKLY' ? form.daysOfWeek.join(',') : null,
        dayOfMonth: form.recurrence === 'MONTHLY' ? Number(form.dayOfMonth) : null,
      }
      const res = modal === 'new' ? await api.monitoring.maintenance.create(payload) : await api.monitoring.maintenance.update(modal.id, payload)
      if (!res?.success) { toast.error(res?.error || t('mw.saveError')); return }
      toast.success(t('mw.saved')); close(); load()
    } finally {
      setSaving(false)
    }
  }

  async function saveQuick() {
    if (!quickForm.allMonitors && quickForm.targets.length === 0) { toast.error(t('mw.targetsRequired')); return }
    setSaving(true)
    try {
      const res = await api.monitoring.maintenance.quick({
        name: quickForm.name?.trim() || null, allMonitors: quickForm.allMonitors,
        targets: quickForm.allMonitors ? [] : targetObjs(quickForm.targets), minutes: Number(quickForm.minutes),
      })
      if (!res?.success) { toast.error(res?.error || t('mw.saveError')); return }
      toast.success(t('mw.started')); close(); load()
    } finally {
      setSaving(false)
    }
  }

  async function togglePause(w) {
    const res = w.status === 'paused' ? await api.monitoring.maintenance.resume(w.id) : await api.monitoring.maintenance.pause(w.id)
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    load()
  }
  async function del(w) {
    if (!await showConfirm({
      title: t('mw.delete'), message: t('mw.deleteConfirm'),
      confirmText: t('mw.delete'), variant: 'danger',
    })) return
    const res = await api.monitoring.maintenance.remove(w.id)
    if (!res?.success) { toast.error(res?.error || t('mw.deleteError')); return }
    toast.success(t('mw.deleted')); load()
  }

  function scheduleSummary(w) {
    const tod = todInTz(w.start_at, w.timezone)
    const dur = w.duration_minutes
    if (w.recurrence === 'DAILY')  return `${t('mw.daily')} · ${tod} · ${dur}${t('mw.minShort')}`
    if (w.recurrence === 'WEEKLY') return `${t('mw.weekly')} · ${(w.days_of_week || '').split(',').filter(Boolean).map(d => t('mw.dow.' + d)).join(', ')} · ${tod}`
    if (w.recurrence === 'MONTHLY') return `${t('mw.monthly')} · ${t('mw.dayOfMonthShort')} ${w.day_of_month} · ${tod}`
    return `${t('mw.once')} · ${tod}`
  }
  function statusBadge(s) {
    return <span className={`mw-status mw-status--${s}`}>{t('mw.st.' + s)}</span>
  }

  return (
    <div className="mw-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title"><Wrench size={20} style={{ verticalAlign: '-4px', marginRight: 6 }} />{t('mw.title')}</h2>
          <p className="upt-subtitle">{t('mw.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <button className="btn btn-sm upt-refresh-btn" onClick={() => setCalOpen(v => { try { localStorage.setItem('mw-cal-open', String(!v)) } catch { /* yoksay */ } return !v })} aria-pressed={calOpen}><CalendarDays size={14} />{t('mw.calendar')}</button>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}><RefreshCw size={14} />{t('mw.refresh')}</button>
          {canManage && <button className="btn btn-sm btn-secondary" onClick={openQuick}><Play size={14} />{t('mw.startNow')}</button>}
          {canManage && <button className="btn btn-sm btn-primary" onClick={openNew}><Plus size={14} />{t('mw.create')}</button>}
        </div>
      </div>

      {loading ? (
        <LoadingBlock label={t('tbl.loading')} fullWidth />
      ) : rows.length === 0 ? (
        <div className="mw-empty">
          <div className="mw-empty-art"><Wrench size={54} /></div>
          <h3 className="mw-empty-title">{t('mw.emptyTitle')}</h3>
          <p className="mw-empty-text">{t('mw.emptyText')}</p>
          {canManage && <button className="btn btn-primary" onClick={openNew}><Plus size={15} />{t('mw.create')}</button>}
        </div>
      ) : (<>
        {/* Takvim görünümü (2026-09-12, #19): sıradaki oluşumlar ay ızgarasında; aynı güne çakışan pencereler yığılır */}
        {calOpen && <MonthCalendar events={mwCalEvents} ariaLabel={t('mw.calendar')} />}
        <div className="admin-table-wrap">
          <table className="admin-table mw-table">
            <thead>
              <tr>
                <th>{t('mw.colName')}</th><th>{t('mw.colMonitors')}</th><th>{t('mw.colSchedule')}</th>
                <th>{t('mw.colNext')}</th><th>{t('mw.colStatus')}</th><th className="mw-th-actions">{t('mw.colActions')}</th>
              </tr>
            </thead>
            <tbody>
              {pager.pageItems.map(w => (
                <tr key={w.id} className={w.status === 'active' ? 'mw-row-active' : ''}>
                  <td><div className="mw-name">{w.name}</div>{w.description && <div className="mw-desc">{w.description}</div>}</td>
                  <td>{w.all_monitors ? <span className="mw-all">{t('mw.allMonitors')}</span> : (w.target_count + ' ' + t('mw.monitors'))}</td>
                  <td className="mw-sched">{scheduleSummary(w)}</td>
                  <td className="mw-next">{w.next_occurrence ? todInTz(w.next_occurrence, w.timezone) + ' · ' + (w.next_occurrence.slice(0, 10)) : '—'}</td>
                  <td>{statusBadge(w.status)}</td>
                  <td className="mw-th-actions">
                    {canManage && <>
                      <button className="mw-act" title={w.status === 'paused' ? t('mw.resume') : t('mw.pause')} onClick={() => togglePause(w)}>
                        {w.status === 'paused' ? <Play size={13} /> : <Pause size={13} />}
                      </button>
                      <button className="mw-act" title={t('mw.edit')} onClick={() => openEdit(w)}><Pencil size={13} /></button>
                      {/* Pencerenin GEÇMİŞİ: planlı kesinti alarmları susturur, dolayısıyla
                          "bu pencereyi kim genişletti" sorusunun izlenebilir olması gerekir. */}
                      <button className="mw-act" title={t('chg.tab')} onClick={() => setHistoryItem(w)}><History size={13} /></button>
                      <button className="mw-act mw-act-danger" title={t('mw.delete')} onClick={() => del(w)}><Trash2 size={13} /></button>
                    </>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <PaginationBar {...pager} />
        </div>
      </>)}

      {/* Create / Edit modal */}
      {modal && modal !== 'quick' && createPortal(
        <div className="modal-overlay">
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 640, width: '92vw', maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="modal-icon-hdr modal-icon-hdr--port">
              <div className="modal-icon-hdr-badge"><Wrench size={20} /></div>
              <h3>{modal === 'new' ? t('mw.modalNew') : t('mw.modalEdit')}</h3>
            </div>
            <div className="form-grid form-grid--top">
              <label className="full-width"><span>{t('mw.name')} <span className="req-star">*</span></span>
                <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label className="full-width"><span>{t('mw.description')}</span>
                <textarea rows={2} value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} /></label>

              {/* Monitör seçimi */}
              <div className="full-width mw-block">
                <div className="mw-block-title">{t('mw.monitorsTitle')}</div>
                <label className="checkbox-label">
                  <input type="checkbox" checked={form.allMonitors} onChange={e => setForm(f => ({ ...f, allMonitors: e.target.checked }))} />{t('mw.allMonitorsOpt')}</label>
                {!form.allMonitors && (
                  <div style={{ marginTop: 6 }}>
                    <MultiTeamSelect value={form.targets} onChange={v => setForm(f => ({ ...f, targets: v }))}
                      options={monitorOptions} placeholder={t('mw.selectMonitors')} searchThreshold={2} />
                    <button type="button" className="mw-selectall" onClick={() => setForm(f => ({ ...f, targets: monitorOptions.map(o => o.value) }))}>{t('mw.selectAll')}</button>
                  </div>
                )}
              </div>

              {/* Zamanlama */}
              <label><span>{t('mw.start')} <span className="req-star">*</span></span>
                <DateTimeField value={form.startAt} onChange={v => setForm(f => ({ ...f, startAt: v }))} placeholder={t('mw.start')} /></label>
              <label><span>{t('mw.timezone')}</span>
                <SearchableSelect value={form.timezone} onChange={v => setForm(f => ({ ...f, timezone: v }))}
                  options={TZ_LIST.map(z => ({ value: z, label: z }))} searchThreshold={2} /></label>
              <label><span>{t('mw.duration')}</span>
                <input type="number" min="1" value={form.durationMinutes} onChange={e => setForm(f => ({ ...f, durationMinutes: Number(e.target.value) }))} /></label>
              <label><span>{t('mw.recurrence')}</span>
                <SearchableSelect value={form.recurrence} onChange={v => setForm(f => ({ ...f, recurrence: v }))}
                  options={RECURRENCES.map(r => ({ value: r, label: t('mw.rec.' + r) }))} /></label>

              {form.recurrence === 'WEEKLY' && (
                <div className="full-width mw-dow">
                  {DOW.map(d => (
                    <button type="button" key={d} className={`mw-dow-btn${form.daysOfWeek.includes(d) ? ' active' : ''}`}
                      onClick={() => setForm(f => ({ ...f, daysOfWeek: f.daysOfWeek.includes(d) ? f.daysOfWeek.filter(x => x !== d) : [...f.daysOfWeek, d] }))}>
                      {t('mw.dow.' + d)}
                    </button>
                  ))}
                </div>
              )}
              {form.recurrence === 'MONTHLY' && (
                <label><span>{t('mw.dayOfMonth')}</span>
                  <input type="number" min="1" max="31" value={form.dayOfMonth} onChange={e => setForm(f => ({ ...f, dayOfMonth: Number(e.target.value) }))} /></label>
              )}

              <div className="full-width mw-summary">
                <span>{t('mw.summaryLabel')}:</span> {scheduleSummary({ recurrence: form.recurrence, start_at: form.startAt, timezone: form.timezone, duration_minutes: form.durationMinutes, days_of_week: form.daysOfWeek.join(','), day_of_month: form.dayOfMonth })} · {form.timezone}
              </div>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={close}>{t('mw.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? '…' : t('mw.save')}</button>
            </div>
          </div>
        </div>, document.body)}

      {/* Quick (ad-hoc) modal */}
      {modal === 'quick' && createPortal(
        <div className="modal-overlay">
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 520, width: '92vw' }}>
            <div className="modal-icon-hdr modal-icon-hdr--port">
              <div className="modal-icon-hdr-badge"><Play size={20} /></div>
              <h3>{t('mw.quickTitle')}</h3>
            </div>
            <div className="form-grid form-grid--top">
              <label className="full-width"><span>{t('mw.name')}</span>
                <input value={quickForm.name} placeholder={t('mw.quickNamePh')} onChange={e => setQuickForm(f => ({ ...f, name: e.target.value }))} /></label>
              <div className="full-width mw-block">
                <label className="checkbox-label">
                  <input type="checkbox" checked={quickForm.allMonitors} onChange={e => setQuickForm(f => ({ ...f, allMonitors: e.target.checked }))} />{t('mw.allMonitorsOpt')}</label>
                {!quickForm.allMonitors && (
                  <div style={{ marginTop: 6 }}>
                    <MultiTeamSelect value={quickForm.targets} onChange={v => setQuickForm(f => ({ ...f, targets: v }))}
                      options={monitorOptions} placeholder={t('mw.selectMonitors')} searchThreshold={2} />
                  </div>
                )}
              </div>
              <label><span>{t('mw.durationMin')}</span>
                <input type="number" min="1" value={quickForm.minutes} onChange={e => setQuickForm(f => ({ ...f, minutes: Number(e.target.value) }))} /></label>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={close}>{t('mw.cancel')}</button>
              <button className="btn btn-primary" onClick={saveQuick} disabled={saving}><Play size={14} />{saving ? '…' : t('mw.startNow')}</button>
            </div>
          </div>
        </div>, document.body)}

      {/* Değişiklik geçmişi — ayrı ve SALT-OKUNUR bir kabuk. Düzenleme formunun içine sekme
          olarak konsaydı geçmişi okumak için formu açmak gerekirdi ve yanlışlıkla kayıt
          riski doğardı. */}
      <ModalShell open={!!historyItem} onClose={() => setHistoryItem(null)}
        title={historyItem ? historyItem.name : ''} icon={History} size="lg" scrollBody>
        {historyItem && (
          <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
            <ChangeHistoryTab t={t} kind="maintenance" monitorId={historyItem.id} />
          </Suspense>
        )}
      </ModalShell>
    </div>
  )
}
