import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import MDEditor, { commands as mdCommands } from '@uiw/react-md-editor'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  Plus, Save, Send, CheckCircle, Undo2, Eye, Trash2, RefreshCcw, ArrowLeft,
} from 'lucide-react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useTheme } from '../i18n/theme.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { clipboardToMarkdownTable } from '../utils/pasteTable'
import { downscaleImage } from '../utils/imageDownscale'

const STATUS_COLOR = {
  DRAFT: '#6b7280',
  PENDING_APPROVAL: '#d97706',
  APPROVED: '#16a34a',
  REJECTED: '#dc2626',
}

/** ISO hafta no (yerel tarih) — yeni rapor varsayılanı. */
function isoWeekInfo(d = new Date()) {
  const date = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const dayNum = date.getUTCDay() || 7
  date.setUTCDate(date.getUTCDate() + 4 - dayNum)
  const yearStart = new Date(Date.UTC(date.getUTCFullYear(), 0, 1))
  const week = Math.ceil(((date - yearStart) / 86400000 + 1) / 7)
  return { year: date.getUTCFullYear(), week }
}

/** Rapor, içinde bulunulan veya bir önceki ISO haftasında mı? USER düzenleme
 *  penceresi — backend'deki inEditWindow ile aynı kural (önceki hafta: PO
 *  iadesi hafta sınırını aşabilsin diye dahil). */
function isEditableWeek(r) {
  const cur = isoWeekInfo()
  const prev = isoWeekInfo(new Date(Date.now() - 7 * 86400000))
  return [cur, prev].some((w) => w.year === r.report_year && w.week === r.week_no)
}

/** Toolbar'daki özel "görsel yükle" komutu ikonu (varsayılan image komutu
 *  yalnız şablon metni eklediği için kaldırıldı — bu, dosya seçiciyi açar). */
const IMAGE_UPLOAD_ICON = (
  <svg width="13" height="13" viewBox="0 0 20 20">
    <path fill="currentColor"
      d="M15 9c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm4-7H1c-.55 0-1 .45-1 1v14c0 .55.45 1 1 1h18c.55 0 1-.45 1-1V3c0-.55-.45-1-1-1zm-1 13l-6-5-2 2-4-5-4 8V4h16v11z" />
  </svg>
)

/**
 * Markdown alanı: geniş tek yazma alanı (sağ üst ikonlarla kaynak/önizleme
 * geçişi), Excel yapıştırma (TSV/HTML→markdown tablo) ve toolbar üzerinden
 * açıklamalı görsel yükleme.
 * highlightEnable kapalı — şeffaf textarea + arkadaki highlight katmanı
 * kombinasyonu kayma/hayalet-metin sorunları üretiyordu.
 */
function MdField({ value, onChange, editable, reportId, height = 220 }) {
  const t = useT()
  const { theme } = useTheme()
  const toast = useToast()
  const fileRef = useRef(null)
  const [pendingFile, setPendingFile] = useState(null) // { file } → açıklama modalı
  const [caption, setCaption] = useState('')
  const [uploading, setUploading] = useState(false)

  const editorCommands = useMemo(() => [
    mdCommands.bold, mdCommands.italic, mdCommands.strikethrough,
    mdCommands.divider,
    mdCommands.link, mdCommands.quote,
    mdCommands.divider,
    mdCommands.unorderedListCommand, mdCommands.orderedListCommand,
    mdCommands.divider,
    {
      name: 'image-upload',
      keyCommand: 'image-upload',
      buttonProps: { 'aria-label': t('wr.uploadImage'), title: t('wr.uploadImage') },
      icon: IMAGE_UPLOAD_ICON,
      execute: () => fileRef.current?.click(),
    },
  ], [t])

  function handlePasteCapture(e) {
    if (e.target?.tagName !== 'TEXTAREA') return
    const md = clipboardToMarkdownTable(e.clipboardData)
    if (!md) return
    e.preventDefault()
    e.stopPropagation()
    const ta = e.target
    const start = ta.selectionStart ?? (value?.length ?? 0)
    const end = ta.selectionEnd ?? start
    onChange((value ?? '').slice(0, start) + md + (value ?? '').slice(end))
    toast.success(t('wr.pasteTableDone'))
  }

  function handleFileChosen(e) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setCaption('')
    setPendingFile({ file })
  }

  async function doUpload() {
    if (!pendingFile) return
    setUploading(true)
    try {
      const processed = await downscaleImage(pendingFile.file)
      const cap = caption.trim()
      const res = await api.weeklyReports.uploadImage(reportId, processed, cap || null)
      if (res?.success) {
        const alt = cap || processed.name
        const captionLine = cap ? `\n**${cap}**\n` : '\n'
        onChange((value ?? '') + `${captionLine}\n![${alt}](/api/weekly-reports/images/${res.data.id})\n`)
        setPendingFile(null)
        setCaption('')
      } else {
        toast.error(res?.error || t('wr.uploadFailed'))
      }
    } catch {
      toast.error(t('wr.uploadFailed'))
    } finally {
      setUploading(false)
    }
  }

  if (!editable) {
    return (
      <div className="show-markdown" data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{value || '—'}</ReactMarkdown>
      </div>
    )
  }

  return (
    <div onPasteCapture={handlePasteCapture}>
      <div data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
        <MDEditor
          value={value ?? ''}
          onChange={(v) => onChange(v ?? '')}
          preview="edit"
          height={height}
          visibleDragbar={false}
          highlightEnable={false}
          commands={editorCommands}
          extraCommands={[mdCommands.codeEdit, mdCommands.codePreview]}
        />
      </div>
      <div style={{ fontSize: '.75em', color: 'var(--text-light)', marginTop: 4 }}>
        {t('wr.pasteHint')}
      </div>
      <input ref={fileRef} type="file" accept="image/*"
        style={{ display: 'none' }} onChange={handleFileChosen} />

      {pendingFile && (
        <div className="modal-overlay" onClick={() => !uploading && setPendingFile(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('wr.uploadImage')}</h3>
            <p style={{ fontSize: '.85em', color: 'var(--text-light)', marginBottom: 10 }}>
              {pendingFile.file.name} · {(pendingFile.file.size / 1024 / 1024).toFixed(1)} MB
            </p>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: '.9em' }}>
              {t('wr.imageCaption')}
              <input autoFocus value={caption} onChange={(e) => setCaption(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') doUpload() }} />
            </label>
            <div className="modal-actions">
              <button className="btn btn-secondary" disabled={uploading}
                onClick={() => setPendingFile(null)}>{t('wr.cancel')}</button>
              <button className="btn btn-primary" disabled={uploading} onClick={doUpload}>
                {uploading ? t('wr.uploading') : t('wr.insertImage')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

/** Serbest elle yazılabilen sayı alanı — yazarken boş bırakılabilir,
 *  yalnız rakam kabul eder; state'e anlık sayı yazılır. */
function NumInput({ label, value, onChange, editable }) {
  const [text, setText] = useState(value != null ? String(value) : '0')

  useEffect(() => {
    const parsed = text === '' ? 0 : parseInt(text, 10)
    if (value !== parsed) setText(value != null ? String(value) : '')
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps

  function handleChange(e) {
    const cleaned = e.target.value.replace(/[^0-9]/g, '')
    setText(cleaned)
    onChange(cleaned === '' ? 0 : parseInt(cleaned, 10))
  }

  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: '.85em' }}>
      {label}
      <input type="text" inputMode="numeric" pattern="[0-9]*" value={text} disabled={!editable}
        onChange={handleChange}
        onBlur={() => { if (text === '') setText('0') }}
        style={{ width: 110 }} />
    </label>
  )
}

export default function WeeklyReportsPage({ systemRole, teamId, teamName }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isAudit = systemRole === 'AUDIT'

  const [teams, setTeams] = useState([])
  const [selTeamId, setSelTeamId] = useState(teamId ? String(teamId) : '')
  const [year, setYear] = useState(isoWeekInfo().year)
  const [reports, setReports] = useState([])
  const [selectedId, setSelectedId] = useState(null)
  const [report, setReport] = useState(null)      // full report (GET /{id})
  const [content, setContent] = useState(null)    // parsed content_json
  const [managerMissing, setManagerMissing] = useState(false)
  const [channelTab, setChannelTab] = useState(0)
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [newModal, setNewModal] = useState(null)  // { year, week }
  const [rejectModal, setRejectModal] = useState(null) // { note }
  const [previewHtml, setPreviewHtml] = useState(null)

  const effTeamId = isAdmin ? (selTeamId ? Number(selTeamId) : null) : teamId
  // Düzenleme/silme: ADMIN her durum + her hafta; diğerleri kendi takımının
  // DRAFT/REJECTED raporunu yalnız mevcut + önceki ISO haftasında (backend kuralıyla aynı)
  const canModifyRow = (r) => isAdmin || (!isAudit
    && r.team_id === teamId
    && (r.status === 'DRAFT' || r.status === 'REJECTED')
    && isEditableWeek(r))
  const editable = !!report && canModifyRow(report)
  const weekLocked = !!report && !isAdmin && !isAudit && report.team_id === teamId
    && (report.status === 'DRAFT' || report.status === 'REJECTED') && !isEditableWeek(report)
  const canSubmit = editable && report.status === 'DRAFT'
  const showApproval = !!report && report.status === 'PENDING_APPROVAL' && !isAudit

  useEffect(() => {
    if (isAdmin) api.admin.getTeams().then((res) => { if (res?.success) setTeams(res.data ?? []) })
  }, [isAdmin])

  const loadList = useCallback(async () => {
    const res = await api.weeklyReports.list({ teamId: effTeamId ?? undefined, year })
    if (res?.success) {
      setReports(res.data ?? [])
      // Açık rapor (filtre değişimiyle) listeden kaybolduysa listeye dön
      if (selectedId && !res.data?.some((r) => r.id === selectedId)) {
        setSelectedId(null)
      }
    }
  }, [effTeamId, year, selectedId])

  useEffect(() => { loadList() }, [effTeamId, year]) // eslint-disable-line react-hooks/exhaustive-deps

  const loadReport = useCallback(async (id) => {
    if (!id) { setReport(null); setContent(null); return }
    const res = await api.weeklyReports.get(id)
    if (res?.success) {
      const r = res.data.report
      setReport({ ...r, images: res.data.images })
      setManagerMissing(!!res.data.manager_contact_missing)
      try { setContent(JSON.parse(r.content_json)) } catch { setContent(null) }
      setDirty(false)
      setChannelTab(0)
    }
  }, [])

  useEffect(() => { loadReport(selectedId) }, [selectedId, loadReport])

  function patch(path, value) {
    setContent((prev) => {
      const next = structuredClone(prev)
      let obj = next
      for (let i = 0; i < path.length - 1; i++) obj = obj[path[i]]
      obj[path[path.length - 1]] = value
      return next
    })
    setDirty(true)
  }

  async function save(showToast = true) {
    if (!report || !content) return false
    setBusy(true)
    const res = await api.weeklyReports.save(report.id, JSON.stringify(content))
    setBusy(false)
    if (res?.success) {
      if (showToast) toast.success(t('wr.saved'))
      setDirty(false)
      setReport((p) => ({ ...p, status: res.data.status }))
      loadList()
      return true
    }
    toast.error(res?.error || t('wr.saveFailed'))
    return false
  }

  async function submit() {
    if (dirty && !(await save(false))) return
    setBusy(true)
    const res = await api.weeklyReports.submit(report.id)
    setBusy(false)
    if (res?.success) {
      toast.success(res.po_mail === 'SKIPPED_NO_CONTACT' ? t('wr.poMailSkipped') : t('wr.submitOk'))
      loadReport(report.id); loadList()
    } else {
      toast.error(res?.error || t('wr.actionFailed'))
    }
  }

  async function approve() {
    const ok = await showConfirm({
      title: t('wr.approveConfirmTitle'),
      message: t('wr.approveConfirmMsg'),
      confirmText: t('wr.approve'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    setBusy(true)
    const res = await api.weeklyReports.approve(report.id)
    setBusy(false)
    if (res?.success) {
      toast.success(t('wr.approveOk'))
      loadReport(report.id); loadList()
    } else {
      toast.error(res?.error || t('wr.actionFailed'))
    }
  }

  async function doReject() {
    if (!rejectModal?.note?.trim()) { toast.error(t('wr.rejectNoteRequired')); return }
    setBusy(true)
    const res = await api.weeklyReports.reject(report.id, rejectModal.note.trim())
    setBusy(false)
    if (res?.success) {
      setRejectModal(null)
      toast.success(t('wr.rejectOk'))
      loadReport(report.id); loadList()
    } else {
      toast.error(res?.error || t('wr.actionFailed'))
    }
  }

  async function openPreview() {
    if (dirty && !(await save(false))) return
    const res = await api.weeklyReports.preview(report.id)
    if (res?.success) setPreviewHtml(res.html)
    else toast.error(res?.error || t('wr.actionFailed'))
  }

  async function createReport() {
    const res = await api.weeklyReports.create({
      team_id: effTeamId ?? undefined,
      year: newModal.year,
      week_no: newModal.week,
    })
    if (res?.success) {
      setNewModal(null)
      toast.success(t('wr.created'))
      setYear(newModal.year)
      await loadList()
      setSelectedId(res.data.id)
    } else {
      toast.error(res?.error?.includes('DUPLICATE_WEEK') ? t('wr.duplicateWeek') : (res?.error || t('wr.actionFailed')))
    }
  }

  async function deleteReport(r) {
    const ok = await showConfirm({
      title: t('wr.deleteReport'),
      message: t('wr.confirmDeleteReport', r.week_label),
      variant: 'danger',
      confirmText: t('wr.deleteReport'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    setBusy(true)
    const res = await api.weeklyReports.remove(r.id)
    setBusy(false)
    if (res?.success) {
      toast.success(t('wr.deleted'))
      if (selectedId === r.id) setSelectedId(null)
      loadList()
    } else {
      toast.error(res?.error || t('wr.actionFailed'))
    }
  }

  async function backToList() {
    if (dirty) {
      const ok = await showConfirm({
        title: t('wr.unsavedLeaveTitle'),
        message: t('wr.unsavedLeaveMsg'),
        confirmText: t('wr.backToList'),
        cancelText: t('wr.cancel'),
      })
      if (!ok) return
    }
    setSelectedId(null)
    loadList()
  }

  function addChannel() {
    const channels = content?.item4?.channels ?? []
    patch(['item4', 'channels'],
      [...channels, { id: 'c-' + Date.now(), name: t('wr.newChannelName'), notes_md: '' }])
    setChannelTab(channels.length)
  }

  async function removeChannel(idx) {
    const ch = content.item4.channels[idx]
    const ok = await showConfirm({
      title: t('wr.deleteChannel'),
      message: t('wr.confirmDeleteChannel', ch.name),
      variant: 'danger',
      confirmText: t('wr.deleteChannel'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    const channels = content.item4.channels.filter((_, i) => i !== idx)
    patch(['item4', 'channels'], channels)
    setChannelTab(Math.max(0, channelTab - (idx <= channelTab ? 1 : 0)))
  }

  const statusBadge = (status) => (
    <span style={{
      background: STATUS_COLOR[status] || '#6b7280', color: '#fff',
      padding: '3px 10px', borderRadius: 12, fontSize: '.78em', fontWeight: 700,
    }}>
      {t(`wr.status${status === 'PENDING_APPROVAL' ? 'Pending' : status.charAt(0) + status.slice(1).toLowerCase()}`)}
    </span>
  )

  const i1 = content?.item1 ?? {}
  const i2 = content?.item2 ?? {}
  const channels = content?.item4?.channels ?? []

  return (
    <div className="admin-section wr-editor">
      {/* ── Üst bar — listede filtreler, editörde Listeye Dön ── */}
      <div className="admin-section-header">
        <h3>{t('wr.title')}</h3>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          {!selectedId ? (
            <>
              {isAdmin && (
                <select value={selTeamId} onChange={(e) => setSelTeamId(e.target.value)}>
                  <option value="">{t('wr.allTeams')}</option>
                  {teams.map((tm) => <option key={tm.id} value={tm.id}>{tm.name}</option>)}
                </select>
              )}
              <select value={year} onChange={(e) => setYear(Number(e.target.value))}>
                {[year - 1, year, year + 1].filter((v, i, a) => a.indexOf(v) === i)
                  .map((y) => <option key={y} value={y}>{y}</option>)}
              </select>
              <button className="btn btn-secondary btn-sm-p" onClick={loadList}>
                <RefreshCcw size={13} />
              </button>
              {!isAudit && (
                <button className="btn btn-success" onClick={() => setNewModal(isoWeekInfo())}>
                  <Plus size={14} /> {t('wr.newReport')}
                </button>
              )}
            </>
          ) : (
            <button className="btn btn-secondary" onClick={backToList}>
              <ArrowLeft size={14} /> {t('wr.backToList')}
            </button>
          )}
        </div>
      </div>

      {/* ── Liste görünümü — hafta bazlı sıralı (backend hafta desc döner) ── */}
      {!selectedId && (
        reports.length ? (
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>{t('wr.colWeek')}</th>
                  {isAdmin && !selTeamId && <th>{t('wr.team')}</th>}
                  <th>{t('wr.statusCol')}</th>
                  <th>{t('wr.colUpdated')}</th>
                  <th>{t('wr.colSent')}</th>
                  <th>{t('wr.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {reports.map((r) => (
                  <tr key={r.id} onClick={() => setSelectedId(r.id)} style={{ cursor: 'pointer' }}>
                    <td><strong>{r.week_label}</strong></td>
                    {isAdmin && !selTeamId && (
                      <td>{teams.find((tm) => tm.id === r.team_id)?.name ?? r.team_id}</td>
                    )}
                    <td>{statusBadge(r.status)}</td>
                    <td>{r.updated_by} · {formatDate(r.updated_at)}</td>
                    <td>{r.sent_at ? formatDate(r.sent_at) : '—'}</td>
                    <td onClick={(e) => e.stopPropagation()}>
                      <button className="btn-sm btn-edit" onClick={() => setSelectedId(r.id)}>
                        {t('wr.open')}
                      </button>
                      {canModifyRow(r) && (
                        <button className="btn-sm btn-del" onClick={() => deleteReport(r)}>
                          {t('wr.deleteReport')}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="empty-state">{t('wr.noReportSelected')}</div>
        )
      )}

      {report && content && (
        <>
          {/* ── Durum satırı ── */}
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap', marginBottom: 14 }}>
            <strong>{report.week_label}</strong>
            {statusBadge(report.status)}
            {report.sent_at && <span style={{ fontSize: '.8em', color: 'var(--text-light)' }}>
              {t('wr.sentAt')} {formatDate(report.sent_at)}
            </span>}
            <span style={{ fontSize: '.8em', color: 'var(--text-light)' }}>
              {t('wr.lastEdit')} {report.updated_by} · {formatDate(report.updated_at)}
            </span>
          </div>

          {/* ── İade notu / müdür uyarısı ── */}
          {report.reject_note && report.status !== 'APPROVED' && (
            <div className="alert-msg" style={{ marginBottom: 12 }}>
              <strong>{t('wr.rejectNoteBanner')}</strong> {report.reject_note}
            </div>
          )}
          {managerMissing && (
            <div className="alert-msg" style={{ marginBottom: 12 }}>
              {t('wr.managerContactMissing')}
            </div>
          )}
          {weekLocked && (
            <div className="alert-msg" style={{ marginBottom: 12 }}>
              {t('wr.weekLockedBanner')}
            </div>
          )}

          {/* ── Madde 1 ── */}
          <div className="show-section-header">{t('wr.item1Title')}</div>
          <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', margin: '10px 0' }}>
            <NumInput label={t('wr.total')}  value={i1.total}  editable={editable} onChange={(v) => patch(['item1', 'total'], v)} />
            <NumInput label={t('wr.urgent')} value={i1.urgent} editable={editable} onChange={(v) => patch(['item1', 'urgent'], v)} />
            <NumInput label={t('wr.high')}   value={i1.high}   editable={editable} onChange={(v) => patch(['item1', 'high'], v)} />
            <NumInput label={t('wr.medium')} value={i1.medium} editable={editable} onChange={(v) => patch(['item1', 'medium'], v)} />
            <NumInput label={t('wr.low')}    value={i1.low}    editable={editable} onChange={(v) => patch(['item1', 'low'], v)} />
          </div>
          <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', marginBottom: 10 }}>
            <label style={{ flex: '1 1 220px', display: 'flex', flexDirection: 'column', gap: 4, fontSize: '.85em' }}>
              {t('wr.statusText')}
              <input value={i1.status_text ?? ''} disabled={!editable}
                onChange={(e) => patch(['item1', 'status_text'], e.target.value)} />
            </label>
            <label style={{ flex: '2 1 320px', display: 'flex', flexDirection: 'column', gap: 4, fontSize: '.85em' }}>
              {t('wr.trackingUrl')}
              <input value={i1.tracking_url ?? ''} disabled={!editable} placeholder="https://..."
                onChange={(e) => patch(['item1', 'tracking_url'], e.target.value)} />
            </label>
          </div>
          <MdField value={i1.notes_md} editable={editable} reportId={report.id}
            onChange={(v) => patch(['item1', 'notes_md'], v)} height={180} />

          {/* ── Madde 2 ── */}
          <div className="show-section-header" style={{ marginTop: 22 }}>{t('wr.item2Title')}</div>
          <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', margin: '10px 0' }}>
            <NumInput label={t('wr.openIncidents')}  value={i2.open_incidents}  editable={editable} onChange={(v) => patch(['item2', 'open_incidents'], v)} />
            <NumInput label={t('wr.problemRecords')} value={i2.problem_records} editable={editable} onChange={(v) => patch(['item2', 'problem_records'], v)} />
            <NumInput label={t('wr.postmortems')}    value={i2.postmortems}     editable={editable} onChange={(v) => patch(['item2', 'postmortems'], v)} />
            <label style={{ flex: '2 1 320px', display: 'flex', flexDirection: 'column', gap: 4, fontSize: '.85em' }}>
              {t('wr.trackingUrl')}
              <input value={i2.tracking_url ?? ''} disabled={!editable} placeholder="https://..."
                onChange={(e) => patch(['item2', 'tracking_url'], e.target.value)} />
            </label>
          </div>
          <MdField value={i2.notes_md} editable={editable} reportId={report.id}
            onChange={(v) => patch(['item2', 'notes_md'], v)} height={180} />

          {/* ── Madde 3 ── */}
          <div className="show-section-header" style={{ marginTop: 22 }}>{t('wr.item3Title')}</div>
          <div style={{ marginTop: 10 }}>
            <MdField value={content?.item3?.notes_md} editable={editable} reportId={report.id}
              onChange={(v) => patch(['item3', 'notes_md'], v)} height={240} />
          </div>

          {/* ── Madde 4 — kanallar ── */}
          <div className="show-section-header" style={{ marginTop: 22 }}>{t('wr.item4Title')}</div>
          <div className="admin-tabs" style={{ marginTop: 10 }}>
            {channels.map((ch, idx) => (
              <button key={ch.id} type="button"
                className={`admin-tab-btn${channelTab === idx ? ' active' : ''}`}
                onClick={() => setChannelTab(idx)}>
                {ch.name}
              </button>
            ))}
            {editable && (
              <button type="button" className="admin-tab-btn" onClick={addChannel}>
                <Plus size={12} /> {t('wr.addChannel')}
              </button>
            )}
          </div>
          {channels[channelTab] && (
            <div style={{ marginTop: 10 }}>
              {editable && (
                <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                  <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '.85em' }}>
                    {t('wr.channelName')}
                    <input value={channels[channelTab].name}
                      onChange={(e) => patch(['item4', 'channels', channelTab, 'name'], e.target.value)} />
                  </label>
                  <button type="button" className="btn-sm btn-del" onClick={() => removeChannel(channelTab)}>
                    <Trash2 size={12} /> {t('wr.deleteChannel')}
                  </button>
                </div>
              )}
              <MdField value={channels[channelTab].notes_md} editable={editable} reportId={report.id}
                onChange={(v) => patch(['item4', 'channels', channelTab, 'notes_md'], v)} height={220} />
            </div>
          )}

          {/* ── Aksiyonlar ── */}
          <div className="modal-actions" style={{ marginTop: 24 }}>
            {canModifyRow(report) && (
              <button className="btn btn-danger" onClick={() => deleteReport(report)} disabled={busy}>
                <Trash2 size={14} /> {t('wr.deleteReport')}
              </button>
            )}
            <button className="btn btn-secondary" onClick={openPreview} disabled={busy}>
              <Eye size={14} /> {t('wr.preview')}
            </button>
            {editable && (
              <button className="btn btn-primary" onClick={() => save()} disabled={busy || !dirty}>
                <Save size={14} /> {busy ? t('wr.saving') : t('wr.save')}
              </button>
            )}
            {canSubmit && (
              <button className="btn btn-success" onClick={submit} disabled={busy}>
                <Send size={14} /> {t('wr.submit')}
              </button>
            )}
            {showApproval && (
              <>
                <button className="btn btn-success" onClick={approve} disabled={busy || managerMissing}>
                  <CheckCircle size={14} /> {t('wr.approve')}
                </button>
                <button className="btn btn-secondary" onClick={() => setRejectModal({ note: '' })} disabled={busy}>
                  <Undo2 size={14} /> {t('wr.reject')}
                </button>
              </>
            )}
          </div>
        </>
      )}

      {/* ── Yeni rapor modalı ── */}
      {newModal && (
        <div className="modal-overlay" onClick={() => setNewModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('wr.newReport')}</h3>
            <div className="form-grid">
              <label>
                {t('wr.year')}
                <input type="number" value={newModal.year}
                  onChange={(e) => setNewModal({ ...newModal, year: parseInt(e.target.value || '0', 10) })} />
              </label>
              <label>
                {t('wr.week')}
                <input type="number" min="1" max="53" value={newModal.week}
                  onChange={(e) => setNewModal({ ...newModal, week: parseInt(e.target.value || '0', 10) })} />
              </label>
            </div>
            <p style={{ fontSize: '.82em', color: 'var(--text-light)', marginTop: 8 }}>{t('wr.templateHint')}</p>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setNewModal(null)}>{t('wr.cancel')}</button>
              <button className="btn btn-primary" onClick={createReport}>{t('wr.create')}</button>
            </div>
          </div>
        </div>
      )}

      {/* ── İade modalı ── */}
      {rejectModal && (
        <div className="modal-overlay" onClick={() => setRejectModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('wr.rejectModalTitle')}</h3>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: '.9em' }}>
              {t('wr.rejectNote')} <span className="req-star">*</span>
              <textarea rows={5} value={rejectModal.note}
                onChange={(e) => setRejectModal({ note: e.target.value })} />
            </label>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setRejectModal(null)}>{t('wr.cancel')}</button>
              <button className="btn btn-primary" onClick={doReject} disabled={busy}>{t('wr.reject')}</button>
            </div>
          </div>
        </div>
      )}

      {/* ── Mail önizleme ── */}
      {previewHtml != null && (
        <div className="modal-overlay" onClick={() => setPreviewHtml(null)}>
          <div className="modal-box modal-wide" onClick={(e) => e.stopPropagation()}
            style={{ maxWidth: 820, height: '85vh', display: 'flex', flexDirection: 'column' }}>
            <h3>{t('wr.previewTitle')}</h3>
            {/* allow-same-origin: görsellerin oturum çerezi ile yüklenebilmesi için; script yok */}
            <iframe title="preview" srcDoc={previewHtml} sandbox="allow-same-origin"
              style={{ flex: 1, border: '1px solid var(--border)', borderRadius: 8, background: '#f1f5f9' }} />
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setPreviewHtml(null)}>{t('wr.close')}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
