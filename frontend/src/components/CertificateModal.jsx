import { useEffect, useState, useRef, lazy, Suspense } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { useToast } from './ui/Toast.jsx'
import UserBadge from './ui/UserBadge.jsx'
import { Trash2, Globe, X, Pencil, Clock, User, History, Undo2, Stethoscope } from 'lucide-react'
import AlertHistory from './admin/AlertHistory'
import SslCheckerPanel from './SslCheckerPanel.jsx'
import DiagnosticsModal from './admin/DiagnosticsModal.jsx'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import { isInsecure, securityTitle } from '../utils/certSecurity.js'
import { InventoryTab } from './inventory/InventoryDetails.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'

// Grafik recharts çekiyor; diğer izleme sayfalarındaki gibi (PingMonitorPage) tembel yüklenir.
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const CertHealthPanel = lazy(() => import('./CertHealthPanel.jsx'))

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

  if (loading) return <LoadingBlock label={t('modal.loading')} fullWidth />

  const visibleNotes = (notes ?? []).filter(n =>
    filter === 'ALL' ? true : (n.category || 'NOTE') === filter
  )

  function renderAuthorLine(authorName, authorUsername) {
    if (!authorName && !authorUsername) {
      return <span className="cert-note-meta-item"><User size={12} /> {t('notes.authorUnknown')}</span>
    }
    return (
      <span className="cert-note-meta-item">
        <UserBadge username={authorUsername || authorName} displayName={authorName || undefined} inline size="sm" />
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
                        <LoadingBlock label={t('notes.historyLoading')} fullWidth />
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
                                <span> · {(r.edited_by || r.edited_by_name)
                                  ? <UserBadge username={r.edited_by || r.edited_by_name} displayName={r.edited_by_name || undefined} inline size="sm" />
                                  : <strong>{t('notes.authorUnknown')}</strong>}</span>
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
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [certData, setCertData]       = useState(null)
  // O3/D12: modal kalıcı mount'lu, yalnız domain prop'u değişiyor — uçuşan yanıt guard'ları
  // "istek anındaki domain hâlâ ekranda mı" sorusunu bu ref'ten okur (her render'da tazelenir).
  const domainRef = useRef(null)
  domainRef.current = domain
  // Canlı SSL probe'unun tur sayacı. domainRef TEK BAŞINA yetmiyordu: uçuşan yanıt "artık
  // ekranda değilim" deyip sslLoading'i TEMİZLEMEDEN dönüyor, bayrak true kalıyordu. Modal
  // kalıcı mount'lu olduğu için o bayrak bir sonraki domain'e taşınıyor ve SSL sekmesi
  // sonsuza kadar yükleniyor görünüyordu (bkz. sıfırlama: domain effect'i).
  const sslSeq = useRef(0)
  const [sslData, setSslData]         = useState(null)
  const [sslLoading, setSslLoading]   = useState(false)
  const [activeTab, setActiveTab]     = useState('ssl')
  const [showDiag, setShowDiag]       = useState(false)
  const isAdmin = currentUserRole === 'ADMIN' || currentUserRole === 'TEAM_ADMIN'
  const canViewInventory = usePermissions().canView('inventory.list')
  // Silme yetkisi backend'deki kapinin AYNISI: inventory.crud/edit (uc ayrica takim kapsami arar).
  // Yetkisi olmayana dugme HIC cizilmez — gorunup 403 vermek kullaniciyi bosuna umutlandirir.
  const canDeleteCert = usePermissions().canEdit('inventory.crud')
  const [deleting, setDeleting] = useState(false)

  useEffect(() => {
    // Domain değişimi (modalı KAPATMAK dahil) uçuşan probe'u geçersizler ve yükleme bayrağını
    // sıfırlar — `!domain` dalından ÖNCE, çünkü kapanış tam da bayrağın sızdığı yoldu.
    sslSeq.current++
    setSslLoading(false)
    if (!domain) return
    setCertData(null)
    setSslData(null)
    setActiveTab('ssl')

    if (initialData) {
      setCertData(initialData)
      setSslData(initialData)
      return
    }

    // Details / Alerts / Notes için geçmiş veri. Guard (D12): A'nın geç dönen yanıtı B'nin
    // modalını doldurmasın.
    const reqDomain = domain
    api.getHistory(domain).then((res) => {
      if (reqDomain !== domainRef.current) return
      if (res?.success && Array.isArray(res.data) && res.data.length > 0) setCertData(res.data[0])
    })

  }, [domain])

  /**
   * SSL sekmesindeki CANLI kontrol yalnız o sekme açıldığında koşar (K2).
   *
   * <p>Önceden her modal açılışında koşuyordu: kullanıcı yalnız "Detaylar"a bakacak olsa bile
   * izlenen sunucuyla TLS el sıkışması yapılıyor, modal onu bekliyordu. Sağlık sekmesi zaten
   * kalıcı son kontrolle anında çiziliyor; canlı probe artık istemli.
   */
  // O3: canlı TLS probe'u yavaştır; kullanıcı A'yı kapatıp B'yi açtığında A'nın geç dönen
  // probe'u B modalında A'nın SSL verisini gösterebiliyordu. Yanıt yalnız İSTEK ANINDAKİ tur
  // hâlâ güncelse yazılır (sslSeq) — ve hangi dalda dönerse dönsün yükleme bayrağı, turu
  // geçersizleyen domain effect'i tarafından sıfırlanır.
  useEffect(() => {
    if (!domain || activeTab !== 'ssl' || sslData || sslLoading) return
    setSslLoading(true)
    const mySeq = ++sslSeq.current
    api.checkDomainPreview(domain).then((res) => {
      if (mySeq !== sslSeq.current) return          // daha yeni bir tur var → bu yanıtı AT
      setSslData(res?.data ?? null)
      setSslLoading(false)
    }).catch(() => { if (mySeq === sslSeq.current) setSslLoading(false) })
  }, [domain, activeTab, sslData, sslLoading])

  function switchTab(tab) { setActiveTab(tab) }

  if (!domain) return null

  // Sertifikayi envanterden sil — YENI uc YOK, mevcut DELETE /admin/inventory/{id} cagrilir:
  // denetim kaydi, soft-delete ve acik alarmlarin kapatilmasi kendiliginden miras kalir.
  async function deleteCertificate() {
    const ok = await showConfirm({
      title: t('inv.deleteTitle'),
      message: t('inv.deleteMsg', domain),
      confirmText: t('inv.deleteConfirm'),
      cancelText: t('inv.deleteCancel'),
      variant: 'danger',
    })
    if (!ok) return
    setDeleting(true)
    const found = await api.admin.getInventoryByDomain(domain)
    const id = found?.success ? found.data?.id : null
    if (!id) { setDeleting(false); toast.error(t('inv.deleteNotFound')); return }
    const res = await api.admin.deleteInventory(id)
    setDeleting(false)
    if (res?.success) { toast.success(t('inv.deleted')); onClose?.({ deleted: true, domain }) }
    else toast.error(res?.error || t('inv.deleteError'))
  }

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
            {d && isInsecure(d) && (
              <span className="modal-status-pill modal-status-error" title={securityTitle(d, t)}>
                {t('cert.sec.insecure')}
              </span>
            )}
            {!previewMode && isAdmin && (
              <button className="modal-header-diag-btn" onClick={() => setShowDiag(true)} title={t('inv.diagnose')}>
                <Stethoscope size={13} /> {t('inv.diagnose')}
              </button>
            )}
            {!previewMode && canDeleteCert && (
              <button className="modal-header-diag-btn" onClick={deleteCertificate}
                disabled={deleting} title={t('inv.delete')}>
                <Trash2 size={13} /> {t('inv.delete')}
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
          {/* Sağlık, SSL'in hemen yanında: ikisi kardeş yüzey — SSL canlı el sıkışmasını ham
              hâliyle gösterir, Sağlık aynı sertifika için HÜKÜM ve AKSİYON üretir. Sekme
              çubuğunun sonunda durursa kullanıcı ikisini ilişkilendiremiyor. Önizleme modunda
              gizli: envanterde olmayan bir domainin kalıcı sağlık kaydı yoktur. */}
          {!previewMode && (
            <button
              className={`modal-tab${activeTab === 'health' ? ' active' : ''}`}
              onClick={() => switchTab('health')}
            >
              {t('hlth.tab')}
            </button>
          )}
          <button
            className={`modal-tab${activeTab === 'details' ? ' active' : ''}`}
            onClick={() => switchTab('details')}
          >
            {t('modal.detailsTab')}
          </button>
          {/* Kontrol Geçmişi + Grafik: diğer sekiz izleme türüyle aynı paylaşılan bileşenler.
              previewMode (SSL Checker önizlemesi) ikisini de gizler — önizlenen domain envanterde
              olmayabilir, geçmiş/seri uçları o durumda 404 döner ve kullanıcı yanıltıcı biçimde
              "kayıt yok" görürdü (alerts/inventory/notes tam bu nedenle zaten gizli). */}
          {!previewMode && (
            <button
              className={`modal-tab${activeTab === 'history' ? ' active' : ''}`}
              onClick={() => switchTab('history')}
            >
              {t('hist.tab')}
            </button>
          )}
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
              className={`modal-tab${activeTab === 'chart' ? ' active' : ''}`}
              onClick={() => switchTab('chart')}
            >
              {t('modal.chartTab')}
            </button>
          )}
          {!previewMode && canViewInventory && (
            <button
              className={`modal-tab${activeTab === 'inventory' ? ' active' : ''}`}
              onClick={() => switchTab('inventory')}
            >
              {t('modal.inventoryTab')}
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

        {!previewMode && activeTab === 'health' && (
          <div className="modal-body">
            <Suspense fallback={<LoadingBlock label={t('modal.loading')} fullWidth />}>
              <CertHealthPanel domain={domain} />
            </Suspense>
          </div>
        )}

        {activeTab === 'ssl' && (
          (sslLoading || !sslData) ? (
            <LoadingBlock label={t('modal.loading')} fullWidth />
          ) : (
            <div className="modal-body ssl-tab-body">
              <SslCheckerPanel data={sslData} />
            </div>
          )
        )}

        {activeTab === 'details' && (
          !d ? (
            <LoadingBlock label={t('modal.loading')} fullWidth />
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
                  label2={d.trust_status ? t('modal.trustStatus') : ''}
                  value2={d.trust_status || ''}
                />
              )}
              {!d.deployment_status && d.trust_status && (
                <Row
                  label={t('modal.trustStatus')} value={d.trust_status}
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

        {!previewMode && activeTab === 'history' && (
          <div className="modal-body">
            {/* Paylaşılan Kontrol Geçmişi v2 — kind "uptime-ssl" certificate_checks üstünde çalışır
                ve diğer türlerle birebir aynı zarfı döndürür. monitorId = DOMAIN (cert domain-anahtarlı).
                Uptime'ın kontrollü aralık modu (range/onRangeChange) alınmaz: orası tek picker'la iki
                kolonu sürüyor, burada tek kolon var → bileşen kendi aralığını sürsün. */}
            <CheckHistoryTab
              kind="uptime-ssl"
              monitorId={domain}
              listKey="cert-ssl-history"
              presets={[1, 7, 30, 90]}
              defaultPreset={7}
              gridClass="upt-uptime-rt-grid"
              columns={[t('uptime.dateFrom'), t('dns.status'), t('modal.daysRemain'), '']}
              renderRow={(c) => (<>
                <span className="upt-rt-time">{formatDate(c.checked_at)}</span>
                <span className={c.status !== 'error' ? 'upt-rt-up' : 'upt-rt-down'}>
                  {c.status !== 'error' ? t('uptime.statusUp') : t('uptime.statusDown')}
                </span>
                <span className="upt-rt-ms">
                  {c.days_remaining != null ? t('uptime.sslDays').replace('{0}', c.days_remaining) : '—'}
                </span>
                {c.error ? <span className="upt-rt-error" title={c.error}>{c.error}</span> : <span />}
              </>)} />
          </div>
        )}

        {!previewMode && activeTab === 'alerts' && (
          <AlertHistory domain={domain} />
        )}

        {!previewMode && activeTab === 'chart' && (
          <div className="modal-body">
            <Suspense fallback={<LoadingBlock label={t('modal.loading')} fullWidth />}>
              <ResponseTimeChart monitorId={domain} kind="ssl" />
            </Suspense>
          </div>
        )}

        {!previewMode && canViewInventory && activeTab === 'inventory' && (
          <InventoryTab domain={domain} />
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
