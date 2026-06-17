import { useEffect, useState, useRef } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { Trash2, Globe, X, Pencil, Clock, User, History, Undo2, Stethoscope } from 'lucide-react'
import AlertHistory from './admin/AlertHistory'
import SslCheckerPanel from './SslCheckerPanel.jsx'
import DiagnosticsModal from './admin/DiagnosticsModal.jsx'

const NOTE_CATEGORIES   = ['NOTE', 'DEPLOYMENT', 'INCIDENT', 'RENEWAL']
const NOTE_EDIT_WINDOW_MS = 24 * 60 * 60 * 1000
const NOTE_MAX_LENGTH   = 5000

function categoryStripe(cat) {
  switch (cat) {
    case 'DEPLOYMENT': return '#2563eb'
    case 'INCIDENT':   return '#dc2626'
    case 'RENEWAL':    return '#10b981'
    default:           return '#94a3b8'
  }
}

function isWithinEditWindow(createdAt) {
  if (!createdAt) return false
  const ts = Date.parse(createdAt.endsWith('Z') ? createdAt : createdAt + 'Z')
  if (Number.isNaN(ts)) return false
  return Date.now() - ts < NOTE_EDIT_WINDOW_MS
}

function fmtTs(ts, fallback) {
  if (!ts) return fallback
  const out = formatDate(ts)
  return (!out || out === 'N/A') ? fallback : out
}

function NotesTab({ domain, t, currentUser, isAdmin }) {
  const [notes, setNotes]       = useState(null)
  const [loading, setLoading]   = useState(false)
  const [newNote, setNewNote]   = useState('')
  const [newCategory, setNewCategory] = useState('NOTE')
  const [filter, setFilter]     = useState('ALL')
  const [saving, setSaving]     = useState(false)
  const [editingId, setEditingId] = useState(null)
  const [editBody, setEditBody] = useState('')
  const [editSaving, setEditSaving] = useState(false)
  const [error, setError]       = useState(null)
  const [historyOpen, setHistoryOpen] = useState({})
  const [revisions, setRevisions] = useState({})
  const [historyLoading, setHistoryLoading] = useState({})
  const { showConfirm } = useDialog()
  const textRef = useRef(null)

  useEffect(() => { loadNotes() }, [domain])

  async function loadNotes() {
    setLoading(true)
    const res = await api.admin.getNotes(domain)
    setNotes(res?.data ?? [])
    setLoading(false)
  }

  async function addNote() {
    setError(null)
    if (!newNote.trim()) return
    if (newNote.length > NOTE_MAX_LENGTH) {
      setError(t('notes.tooLong', NOTE_MAX_LENGTH))
      return
    }
    setSaving(true)
    const res = await api.admin.addNote(domain, newNote.trim(), newCategory)
    setSaving(false)
    if (res?.success) {
      setNewNote('')
      setNewCategory('NOTE')
      loadNotes()
    } else {
      setError(res?.error || t('notes.saveFailed'))
    }
  }

  function startEdit(n) {
    setEditingId(n.id)
    setEditBody(n.note)
    setError(null)
  }
  function cancelEdit() {
    setEditingId(null)
    setEditBody('')
  }
  async function saveEdit() {
    setError(null)
    if (!editBody.trim()) return
    if (editBody.length > NOTE_MAX_LENGTH) {
      setError(t('notes.tooLong', NOTE_MAX_LENGTH))
      return
    }
    setEditSaving(true)
    const res = await api.admin.updateNote(domain, editingId, editBody.trim())
    setEditSaving(false)
    if (res?.success) {
      // Invalidate cached revisions so accordion reloads with new EDIT entry
      setRevisions(prev => { const c = { ...prev }; delete c[editingId]; return c })
      cancelEdit()
      loadNotes()
    } else {
      setError(res?.error || t('notes.saveFailed'))
    }
  }

  async function deleteNote(n) {
    const ok = await showConfirm({
      title: t('notes.deleteTitle'),
      message: t('notes.deleteConfirm'),
      variant: 'danger',
      confirmText: t('notes.delete'),
      cancelText: t('notes.cancel'),
    })
    if (!ok) return
    const res = await api.admin.deleteNote(domain, n.id)
    if (res?.success) {
      setRevisions(prev => { const c = { ...prev }; delete c[n.id]; return c })
      if (editingId === n.id) cancelEdit()
      loadNotes()
    }
  }

  async function restoreNote(n) {
    const ok = await showConfirm({
      title: t('notes.restoreTitle'),
      message: t('notes.restoreConfirm'),
      variant: 'default',
      confirmText: t('notes.restoreBtn'),
      cancelText: t('notes.cancel'),
    })
    if (!ok) return
    const res = await api.admin.restoreNote(domain, n.id)
    if (res?.success) {
      setRevisions(prev => { const c = { ...prev }; delete c[n.id]; return c })
      loadNotes()
    }
  }

  async function toggleHistory(noteId) {
    const willOpen = !historyOpen[noteId]
    setHistoryOpen(prev => ({ ...prev, [noteId]: willOpen }))
    if (willOpen && !revisions[noteId]) {
      setHistoryLoading(prev => ({ ...prev, [noteId]: true }))
      const res = await api.admin.getNoteRevisions(domain, noteId)
      setRevisions(prev => ({ ...prev, [noteId]: res?.data ?? [] }))
      setHistoryLoading(prev => ({ ...prev, [noteId]: false }))
    }
  }

  if (loading) return <div className="loading">{t('modal.loading')}</div>

  const visibleNotes = (notes ?? []).filter(n =>
    filter === 'ALL' ? true : (n.category || 'NOTE') === filter
  )

  function renderAuthorLine(authorName, authorUsername) {
    const name = authorName || authorUsername || t('notes.authorUnknown')
    return (
      <span className="cert-note-meta-item">
        <User size={12} />
        <span className="cert-note-author-strong">{name}</span>
        {authorUsername && authorUsername !== name && (
          <span className="cert-note-author-username">({authorUsername})</span>
        )}
      </span>
    )
  }

  return (
    <div className="modal-body">
      {isAdmin && (
        <div className="cert-note-form">
          <div className="cert-note-form-row">
            <label className="cert-note-cat-label">{t('notes.categoryLabel')}</label>
            <select className="cert-note-cat-select" value={newCategory} onChange={(e) => setNewCategory(e.target.value)}>
              {NOTE_CATEGORIES.map(c => (
                <option key={c} value={c}>{t(`notes.cat.${c}`)}</option>
              ))}
            </select>
          </div>
          <textarea
            ref={textRef}
            className="note-textarea"
            rows={3}
            value={newNote}
            maxLength={NOTE_MAX_LENGTH}
            onChange={(e) => setNewNote(e.target.value)}
            placeholder={t('notes.bodyPlaceholder')}
            onKeyDown={(e) => { if (e.ctrlKey && e.key === 'Enter') addNote() }}
          />
          <div className="cert-note-form-footer">
            <span className="cert-note-charcount">{newNote.length} / {NOTE_MAX_LENGTH}</span>
            <button className="btn btn-primary" onClick={addNote} disabled={saving || !newNote.trim()}>
              {saving ? t('notes.saving') : t('notes.add')}
            </button>
          </div>
          {error && <div className="cert-note-error">{error}</div>}
        </div>
      )}

      {notes && notes.length > 0 && (
        <div className="cert-note-filter-row">
          {['ALL', ...NOTE_CATEGORIES].map(f => (
            <button
              key={f}
              className={`cert-note-filter-btn${filter === f ? ' active' : ''}`}
              onClick={() => setFilter(f)}>
              {f === 'ALL' ? t('notes.filterAll') : t(`notes.cat.${f}`)}
            </button>
          ))}
        </div>
      )}

      {notes && notes.length === 0 ? (
        <div className="alh-notif-empty">{t('notes.empty')}</div>
      ) : visibleNotes.length === 0 ? (
        <div className="alh-notif-empty">{t('notes.emptyForFilter')}</div>
      ) : (
        <div className="alert-history-cards">
          {visibleNotes.map((n) => {
            const cat        = n.category || 'NOTE'
            const createdAt  = n.created_at
            const updatedAt  = n.updated_at
            const updatedBy  = n.updated_by
            const deletedAt  = n.deleted_at
            const deletedBy  = n.deleted_by
            const authorName = n.author_name
            const authorUser = n.author_username
            const isDeleted  = !!deletedAt
            const isAuthor   = currentUser && authorUser === currentUser
            const canEdit    = !isDeleted && isAuthor && isWithinEditWindow(createdAt)
            const canDelete  = !isDeleted && (isAuthor || isAdmin)
            const canRestore = isDeleted && isAdmin
            const isEditing  = editingId === n.id
            const revs       = revisions[n.id] ?? []
            const editEvents = revs.filter(r => r.event_type === 'EDIT').length
            return (
              <div key={n.id} className={`alert-history-card cert-note-card${isDeleted ? ' cert-note-deleted' : ''}`}>
                <div className="ahc-stripe" style={{ background: isDeleted ? '#9ca3af' : categoryStripe(cat) }} />
                <div className="ahc-body">
                  {isDeleted && (
                    <div className="cert-note-deleted-banner">
                      <Trash2 size={13} />
                      <span>
                        {t('notes.deletedBanner', fmtTs(deletedAt, t('notes.dateUnknown')), deletedBy || t('notes.authorUnknown'))}
                      </span>
                    </div>
                  )}

                  <div className="cert-note-header">
                    <span className={`cert-note-category-badge cn-cat-${cat}`}>
                      {t(`notes.cat.${cat}`)}
                    </span>
                    <div className="cert-note-actions">
                      {canEdit && !isEditing && (
                        <button className="cert-note-icon-btn" title={t('notes.edit')} onClick={() => startEdit(n)}>
                          <Pencil size={13} />
                        </button>
                      )}
                      {canDelete && !isEditing && (
                        <button className="cert-note-icon-btn cert-note-del-btn" title={t('notes.delete')} onClick={() => deleteNote(n)}>
                          <Trash2 size={13} />
                        </button>
                      )}
                      {canRestore && (
                        <button className="cert-note-icon-btn cert-note-restore-btn" title={t('notes.restoreBtn')} onClick={() => restoreNote(n)}>
                          <Undo2 size={13} />
                        </button>
                      )}
                    </div>
                  </div>

                  <div className="cert-note-meta">
                    {renderAuthorLine(authorName, authorUser)}
                    <span className="cert-note-meta-item">
                      <Clock size={12} />
                      {fmtTs(createdAt, t('notes.dateUnknown'))}
                    </span>
                    {updatedAt && (
                      <span className="cert-note-meta-item cert-note-edited-label" title={`${t('notes.editedLabel')}: ${fmtTs(updatedAt, t('notes.dateUnknown'))}${updatedBy ? ' · ' + updatedBy : ''}`}>
                        <Pencil size={11} />
                        {t('notes.editedLabel')}
                      </span>
                    )}
                    <button className="cert-note-history-btn" onClick={() => toggleHistory(n.id)}>
                      <History size={12} />
                      {historyOpen[n.id] ? t('notes.hideHistory') : t('notes.showHistory')}
                    </button>
                  </div>

                  {isEditing ? (
                    <div className="cert-note-edit-form">
                      <textarea
                        className="note-textarea"
                        rows={3}
                        value={editBody}
                        maxLength={NOTE_MAX_LENGTH}
                        onChange={(e) => setEditBody(e.target.value)}
                      />
                      <div className="cert-note-form-footer">
                        <span className="cert-note-charcount">{editBody.length} / {NOTE_MAX_LENGTH}</span>
                        <button className="btn btn-secondary btn-sm-p" onClick={cancelEdit} disabled={editSaving}>
                          {t('notes.cancel')}
                        </button>
                        <button className="btn btn-primary btn-sm-p" onClick={saveEdit} disabled={editSaving || !editBody.trim()}>
                          {editSaving ? t('notes.saving') : t('notes.save')}
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div className="cert-note-body">{n.note}</div>
                  )}

                  {historyOpen[n.id] && (
                    <div className="cert-note-history-panel">
                      <div className="cert-note-history-title">
                        <History size={13} /> {t('notes.historyTitle')}
                      </div>
                      {historyLoading[n.id] ? (
                        <div className="loading">{t('notes.historyLoading')}</div>
                      ) : revs.length === 0 ? (
                        <div className="cert-note-history-empty">{t('notes.historyEmpty')}</div>
                      ) : (
                        [...revs].reverse().map(r => (
                          <div key={r.id} className="cert-note-history-item">
                            <span className="cert-note-history-bullet">●</span>
                            <div style={{ flex: 1 }}>
                              <div>
                                <span className={`cert-note-history-event cert-note-history-event${r.event_type}`}>
                                  {t(`notes.event${r.event_type}`)}
                                </span>
                                <span> · {fmtTs(r.edited_at, t('notes.dateUnknown'))}</span>
                                <span> · <strong>{r.edited_by_name || r.edited_by || t('notes.authorUnknown')}</strong></span>
                                {r.edited_by && r.edited_by_name && r.edited_by_name !== r.edited_by && (
                                  <span className="cert-note-author-username"> ({r.edited_by})</span>
                                )}
                              </div>
                              {r.body && <div className="cert-note-history-body">{r.body}</div>}
                            </div>
                          </div>
                        ))
                      )}
                    </div>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

export default function CertificateModal({ domain, alertLevel, onClose, initialData, previewMode, currentUser, currentUserRole }) {
  const t = useT()
  const [certData, setCertData]       = useState(null)
  const [sslData, setSslData]         = useState(null)
  const [sslLoading, setSslLoading]   = useState(false)
  const [activeTab, setActiveTab]     = useState('ssl')
  const [showDiag, setShowDiag]       = useState(false)
  const isAdmin = currentUserRole === 'ADMIN' || currentUserRole === 'TEAM_ADMIN'

  useEffect(() => {
    if (!domain) return
    setCertData(null)
    setSslData(null)
    setActiveTab('ssl')

    if (initialData) {
      setCertData(initialData)
      setSslData(initialData)
      return
    }

    // Details / Alerts / Notes için geçmiş veri
    api.getHistory(domain).then((res) => {
      if (res?.success && res.data.length > 0) setCertData(res.data[0])
    })

    // SSL tabı için canlı check (SSL Checker ile aynı endpoint)
    setSslLoading(true)
    api.checkDomainPreview(domain).then((res) => {
      setSslData(res?.data ?? null)
      setSslLoading(false)
    })
  }, [domain])

  function switchTab(tab) { setActiveTab(tab) }

  if (!domain) return null

  const d = certData

  return (
    <>
    <div className="modal show" onClick={(e) => e.target.classList.contains('modal') && onClose()}>
      <div className="modal-content modal-wide">
        <div className="modal-header-row">
          <div className="modal-header-domain">
            <span className="modal-header-icon"><Globe size={16} /></span>
            <h2 className="modal-title">{domain}</h2>
            {d && <span className={`modal-status-pill modal-status-${alertLevel ?? statusKey(d)}`}>{statusLabel(alertLevel ?? statusKey(d), t)}</span>}
            {!previewMode && isAdmin && (
              <button className="modal-header-diag-btn" onClick={() => setShowDiag(true)} title={t('inv.diagnose')}>
                <Stethoscope size={13} /> {t('inv.diagnose')}
              </button>
            )}
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <div className="modal-tabs">
          <button
            className={`modal-tab${activeTab === 'ssl' ? ' active' : ''}`}
            onClick={() => switchTab('ssl')}
          >
            {t('ssl.tab')}
          </button>
          <button
            className={`modal-tab${activeTab === 'details' ? ' active' : ''}`}
            onClick={() => switchTab('details')}
          >
            {t('modal.detailsTab')}
          </button>
          {!previewMode && (
            <button
              className={`modal-tab${activeTab === 'alerts' ? ' active' : ''}`}
              onClick={() => switchTab('alerts')}
            >
              {t('modal.alertsTab')}
            </button>
          )}
          {!previewMode && (
            <button
              className={`modal-tab${activeTab === 'notes' ? ' active' : ''}`}
              onClick={() => switchTab('notes')}
            >
              {t('modal.notesTab')}
            </button>
          )}
        </div>

        {activeTab === 'ssl' && (
          (sslLoading || !sslData) ? (
            <div className="loading">{t('modal.loading')}</div>
          ) : (
            <div className="modal-body ssl-tab-body">
              <SslCheckerPanel data={sslData} />
            </div>
          )
        )}

        {activeTab === 'details' && (
          !d ? (
            <div className="loading">{t('modal.loading')}</div>
          ) : (
            <div className="modal-body">

              {/* ── Identity ── */}
              <SectionTitle>{t('modal.secIdentity')}</SectionTitle>
              <Row label={t('modal.domain')}   value={d.domain}
                   label2={t('modal.status')}  value2={statusLabel(alertLevel ?? statusKey(d), t)} />
              <Row label={t('modal.subject')}  value={d.subject}
                   label2={t('modal.issuer')}  value2={d.issuer_cn || d.issuer} />
              {d.subject_dn && <FullRow label={t('modal.subjectDn')} value={d.subject_dn} mono />}
              {d.issuer_dn  && <FullRow label={t('modal.issuerDn')}  value={d.issuer_dn}  mono />}

              {/* ── Validity ── */}
              <SectionTitle>{t('modal.secValidity')}</SectionTitle>
              <Row label={t('modal.notBefore')}  value={formatDate(d.not_before)}
                   label2={t('modal.notAfter')}  value2={formatDate(d.not_after)} />
              <Row label={t('modal.daysRemain')} value={d.days_remaining ?? 'N/A'}
                   label2={t('modal.lastCheck')} value2={formatDate(d.checked_at)} />
              {d.serial_number && (
                <FullRow label={t('modal.serialNumber')} value={d.serial_number} mono />
              )}

              {/* ── Key Information ── */}
              <SectionTitle>{t('modal.secKey')}</SectionTitle>
              <Row
                label={t('modal.pubKeyAlgo')}
                value={d.public_key_algorithm
                  ? `${d.public_key_algorithm}${d.public_key_size ? ' / ' + d.public_key_size + ' bit' : ''}`
                  : 'N/A'}
                label2={t('modal.sigAlgo')}
                value2={d.signature_algorithm || 'N/A'}
              />
              <Row
                label={t('modal.isCA')}
                value={d.is_ca == null ? 'N/A' : d.is_ca ? t('modal.yes') : t('modal.no')}
                label2=""
                value2=""
              />
              {d.key_usage?.length > 0 && (
                <FullRow label={t('modal.keyUsage')} value={d.key_usage.join(' · ')} />
              )}
              {d.ext_key_usage?.length > 0 && (
                <FullRow label={t('modal.extKeyUsage')} value={d.ext_key_usage.join(' · ')} />
              )}

              {/* ── Security ── */}
              <SectionTitle>{t('modal.secSecurity')}</SectionTitle>
              {d.fingerprint && (
                <FullRow label={t('modal.fingerprint')} value={d.fingerprint} mono />
              )}
              {(d.chain_status || d.revocation_status || d.deployment_status) && (
                <Row
                  label={t('modal.chainStatus')}      value={d.chain_status || 'N/A'}
                  label2={t('modal.revocationStatus')} value2={d.revocation_status || 'N/A'}
                />
              )}
              {d.deployment_status && (
                <Row
                  label={t('modal.deploymentStatus')} value={d.deployment_status || 'N/A'}
                  label2="" value2=""
                />
              )}

              {/* ── Infrastructure ── */}
              {(d.ocsp_url || d.crl_url) && (
                <>
                  <SectionTitle>{t('modal.secInfra')}</SectionTitle>
                  {d.ocsp_url && <FullRow label={t('modal.ocspUrl')} value={d.ocsp_url} />}
                  {d.crl_url  && <FullRow label={t('modal.crlUrl')}  value={d.crl_url}  />}
                </>
              )}

              {/* ── SAN ── */}
              {d.san?.length > 0 && (
                <>
                  <SectionTitle>{t('modal.san')}</SectionTitle>
                  <div className="modal-row full">
                    <div className="modal-field">
                      <div className="modal-field-value modal-san-list">
                        {d.san.map((s, i) => <span key={i} className="modal-san-chip">{s}</span>)}
                      </div>
                    </div>
                  </div>
                </>
              )}

              {/* ── Error ── */}
              {d.error && (
                <div className="modal-row full">
                  <div className="modal-field" style={{ background: '#ffe8e8', borderLeft: '4px solid var(--danger-color)' }}>
                    <div className="modal-field-label" style={{ color: 'var(--danger-color)' }}>{t('modal.errorMsg')}</div>
                    <div className="modal-field-value">{d.error}</div>
                  </div>
                </div>
              )}
            </div>
          )
        )}

        {!previewMode && activeTab === 'alerts' && (
          <AlertHistory domain={domain} />
        )}

        {!previewMode && activeTab === 'notes' && (
          <NotesTab domain={domain} t={t} currentUser={currentUser} isAdmin={currentUserRole === 'ADMIN' || currentUserRole === 'TEAM_ADMIN'} />
        )}
      </div>
    </div>
    {showDiag && (
      <DiagnosticsModal domain={domain} port={d?.port || 443} onClose={() => setShowDiag(false)} />
    )}
    </>
  )
}

function SectionTitle({ children }) {
  return (
    <div className="modal-section-title">{children}</div>
  )
}

function Row({ label, value, label2, value2 }) {
  return (
    <div className="modal-row">
      <div className="modal-field">
        <div className="modal-field-label">{label}</div>
        <div className="modal-field-value">{value || (value === 0 ? 0 : 'N/A')}</div>
      </div>
      {label2 ? (
        <div className="modal-field">
          <div className="modal-field-label">{label2}</div>
          <div className="modal-field-value">{value2 || (value2 === 0 ? 0 : 'N/A')}</div>
        </div>
      ) : <div className="modal-field" />}
    </div>
  )
}

function FullRow({ label, value, mono = false }) {
  return (
    <div className="modal-row full">
      <div className="modal-field">
        <div className="modal-field-label">{label}</div>
        <div className={`modal-field-value${mono ? ' modal-mono' : ''}`}>{value || 'N/A'}</div>
      </div>
    </div>
  )
}

function statusKey(cert) {
  if (cert.alert_level) return cert.alert_level
  if (cert.status === 'error') return 'error'
  if (cert.days_remaining != null && cert.days_remaining < 0) return 'expired'
  if (cert.days_remaining != null && cert.days_remaining <= 7) return 'critical'
  if (cert.days_remaining != null && cert.days_remaining <= 15) return 'high'
  if (cert.warning) return 'warning'
  return 'valid'
}

function statusLabel(key, t) {
  const map = {
    valid:    () => t('card.valid'),
    warning:  () => t('card.warning'),
    high:     () => t('card.high'),
    critical: () => t('card.critical'),
    error:    () => t('card.error'),
    expired:  () => t('modal.statusError'),
  }
  return (map[key] ?? map.valid)()
}
