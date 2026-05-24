import { useEffect, useState, useRef } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { Trash2, Globe, X } from 'lucide-react'
import AlertHistory from './admin/AlertHistory'

function NotesTab({ domain, t }) {
  const [notes, setNotes]       = useState(null)
  const [loading, setLoading]   = useState(false)
  const [newNote, setNewNote]   = useState('')
  const [saving, setSaving]     = useState(false)
  const textRef = useRef(null)

  useEffect(() => { loadNotes() }, [domain])

  async function loadNotes() {
    setLoading(true)
    const res = await api.admin.getNotes(domain)
    setNotes(res?.data ?? [])
    setLoading(false)
  }

  async function addNote() {
    if (!newNote.trim()) return
    setSaving(true)
    const res = await api.admin.addNote(domain, newNote.trim())
    setSaving(false)
    if (res?.success) { setNewNote(''); loadNotes() }
  }

  async function deleteNote(noteId) {
    await api.admin.deleteNote(domain, noteId)
    loadNotes()
  }

  if (loading) return <div className="loading">{t('modal.loading')}</div>

  return (
    <div className="modal-body">
      <div className="note-add-row">
        <textarea
          ref={textRef}
          className="note-textarea"
          rows={3}
          value={newNote}
          onChange={(e) => setNewNote(e.target.value)}
          placeholder={t('note.placeholder')}
          onKeyDown={(e) => { if (e.ctrlKey && e.key === 'Enter') addNote() }}
        />
        <button className="btn btn-primary note-add-btn" onClick={addNote} disabled={saving || !newNote.trim()}>
          {saving ? t('note.saving') : t('note.add')}
        </button>
      </div>
      {notes && notes.length === 0 ? (
        <div className="alh-notif-empty">{t('note.empty')}</div>
      ) : (
        <div className="note-list">
          {(notes ?? []).map((n) => (
            <div key={n.id} className="note-card">
              <div className="note-header">
                <span className="note-author">{n.authorName || n.authorUsername || '—'}</span>
                <span className="note-date">{formatDate(n.createdAt)}</span>
                <button className="note-del-btn" onClick={() => deleteNote(n.id)} title={t('note.delete')}>
                  <Trash2 size={13} />
                </button>
              </div>
              <div className="note-body">{n.note}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default function CertificateModal({ domain, alertLevel, onClose }) {
  const t = useT()
  const [certData, setCertData]   = useState(null)
  const [activeTab, setActiveTab] = useState('details')

  useEffect(() => {
    if (!domain) return
    setCertData(null)
    setActiveTab('details')
    api.getHistory(domain).then((res) => {
      if (res?.success && res.data.length > 0) setCertData(res.data[0])
    })
  }, [domain])

  function switchTab(tab) { setActiveTab(tab) }

  if (!domain) return null

  const d = certData

  return (
    <div className="modal show" onClick={(e) => e.target.classList.contains('modal') && onClose()}>
      <div className="modal-content modal-wide">
        <div className="modal-header-row">
          <div className="modal-header-domain">
            <span className="modal-header-icon"><Globe size={16} /></span>
            <h2 className="modal-title">{domain}</h2>
            {d && <span className={`modal-status-pill modal-status-${alertLevel ?? statusKey(d)}`}>{statusLabel(alertLevel ?? statusKey(d), t)}</span>}
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <div className="modal-tabs">
          <button
            className={`modal-tab${activeTab === 'details' ? ' active' : ''}`}
            onClick={() => switchTab('details')}
          >
            {t('modal.detailsTab')}
          </button>
          <button
            className={`modal-tab${activeTab === 'alerts' ? ' active' : ''}`}
            onClick={() => switchTab('alerts')}
          >
            {t('modal.alertsTab')}
          </button>
          <button
            className={`modal-tab${activeTab === 'notes' ? ' active' : ''}`}
            onClick={() => switchTab('notes')}
          >
            {t('modal.notesTab')}
          </button>
        </div>

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

        {activeTab === 'alerts' && (
          <AlertHistory domain={domain} />
        )}

        {activeTab === 'notes' && (
          <NotesTab domain={domain} t={t} />
        )}
      </div>
    </div>
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
