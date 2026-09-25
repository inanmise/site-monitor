import { LoadingBlock } from './ui/Progress.jsx'
import { useState, useEffect, useRef } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import MarkdownEditor from './ui/MarkdownEditor.jsx'
import { BookOpen, Plus, Pencil, Trash2, Save, ChevronRight, ChevronDown } from 'lucide-react'
import { Button } from '@/components/shadcn/button'

/**
 * Hedef-bazlı (type = KEYWORD|PING, target = url/host) "Rehber & Notlar":
 *  - üstte tek REHBER bloğu (alarm gelince ne yapılır) — markdown, düzenlenebilir
 *  - altında yapılandırılmış NOT günlüğü (Sorun / Yapılan işlem / Kök neden / Bakılacak yerler)
 * Keyword/Ping detay modalına lazy yüklenir.
 */
const EMPTY = { problem: '', action_taken: '', root_cause: '', refs: '' }

export default function MonitorNotes({ type, target }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const toast = useToast()
  const [guide, setGuide] = useState(null)
  const [notes, setNotes] = useState([])
  const [loading, setLoading] = useState(true)

  const [editingGuide, setEditingGuide] = useState(false)
  const [guideDraft, setGuideDraft] = useState('')
  const [savingGuide, setSavingGuide] = useState(false)

  const [form, setForm] = useState(EMPTY)
  const [adding, setAdding] = useState(false)
  const [editId, setEditId] = useState(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => { load()   }, [type, target])

  // D12: type/target hızla değişirse eskinin geç yanıtı yeni hedefin notlarını ezmesin.
  const loadSeq = useRef(0)
  async function load() {
    if (!target) { setLoading(false); return }
    const seq = ++loadSeq.current
    setLoading(true)
    try {
      const res = await api.monitoring.getMonitorNotes(type, target)
      if (seq !== loadSeq.current) return
      if (res?.success) { setGuide(res.data.guide || null); setNotes(res.data.notes || []) }
      else toast.error(res?.error || 'Error')
    } finally {
      setLoading(false)
    }
  }

  async function saveGuide() {
    setSavingGuide(true)
    try {
      const res = await api.monitoring.saveMonitorGuide(type, target, guideDraft)
      if (res?.success) { setGuide(res.data); setEditingGuide(false); toast.success(t('mnote.guideSaved')) }
      else toast.error(res?.error || 'Error')
    } finally {
      setSavingGuide(false)
    }
  }

  function startAdd() { setForm(EMPTY); setEditId(null); setAdding(true) }
  function startEdit(n) {
    setForm({ problem: n.problem || '', action_taken: n.action_taken || '', root_cause: n.root_cause || '', refs: n.refs || '' })
    setEditId(n.id); setAdding(true)
  }
  function cancelForm() { setAdding(false); setEditId(null); setForm(EMPTY) }

  async function saveNote() {
    if (!form.problem.trim()) { toast.error(t('mnote.problemRequired')); return }
    setSaving(true)
    try {
      const res = editId
        ? await api.monitoring.updateMonitorNote(editId, form)
        : await api.monitoring.addMonitorNote({ type, target, ...form })
      if (res?.success) { toast.success(editId ? t('mnote.saved') : t('mnote.added')); cancelForm(); load() }
      else toast.error(res?.error || 'Error')
    } finally {
      setSaving(false)
    }
  }

  async function del(n) {
    if (!await showConfirm({
      title: t('mnote.delete'), message: t('mnote.deleteConfirm'),
      confirmText: t('mnote.delete'), variant: 'danger',
    })) return
    const res = await api.monitoring.deleteMonitorNote(n.id)
    if (res?.success) { toast.success(t('mnote.deleted')); load() }
    else toast.error(res?.error || 'Error')
  }

  if (loading) return <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />

  return (
    <div className="mnote-wrap">
      {/* ── Rehber ── */}
      <div className="mnote-guide">
        <div className="mnote-sec-hdr">
          <span className="mnote-sec-title"><BookOpen size={15} /> {t('mnote.guideTitle')}</span>
          {!editingGuide && (
            <Button variant="secondary" size="sm"
              onClick={() => { setGuideDraft(guide?.guide || ''); setEditingGuide(true) }}>
              <Pencil size={12} /> {t('mnote.edit')}
            </Button>
          )}
        </div>
        {editingGuide ? (
          <>
            <MarkdownEditor value={guideDraft} onChange={setGuideDraft} editable height={240} />
            <div className="mnote-form-actions">
              <Button variant="secondary" size="sm" onClick={() => setEditingGuide(false)}>{t('mnote.cancel')}</Button>
              <Button size="sm" onClick={saveGuide} disabled={savingGuide}>
                <Save size={12} /> {savingGuide ? t('mnote.saving') : t('mnote.save')}
              </Button>
            </div>
          </>
        ) : guide?.guide ? (
          <>
            <div className="mnote-md"><MarkdownEditor value={guide.guide} editable={false} /></div>
            {guide.updated_by && (
              <div className="mnote-meta">{t('mnote.updatedBy')}: {guide.updated_by} · {fmt(guide.updated_at)}</div>
            )}
          </>
        ) : (
          <div className="mnote-empty">{t('mnote.guideEmpty')}</div>
        )}
      </div>

      {/* ── Notlar ── */}
      <div className="mnote-notes">
        <div className="mnote-sec-hdr">
          <span className="mnote-sec-title">{t('mnote.notesTitle')} ({notes.length})</span>
          {!adding && (
            <Button size="sm" onClick={startAdd}>
              <Plus size={12} /> {t('mnote.addNote')}
            </Button>
          )}
        </div>

        {adding && (
          <div className="mnote-form">
            <NoteField label={t('mnote.fProblem')} required value={form.problem}
              onChange={v => setForm(f => ({ ...f, problem: v }))} />
            <NoteField label={t('mnote.fAction')} value={form.action_taken}
              onChange={v => setForm(f => ({ ...f, action_taken: v }))} />
            <NoteField label={t('mnote.fRoot')} value={form.root_cause}
              onChange={v => setForm(f => ({ ...f, root_cause: v }))} />
            <NoteField label={t('mnote.fRefs')} value={form.refs}
              onChange={v => setForm(f => ({ ...f, refs: v }))} />
            <div className="mnote-md-hint">{t('mnote.mdHint')}</div>
            <div className="mnote-form-actions">
              <Button variant="secondary" size="sm" onClick={cancelForm}>{t('mnote.cancel')}</Button>
              <Button size="sm" onClick={saveNote} disabled={saving || !form.problem.trim()}>
                {saving ? t('mnote.saving') : (editId ? t('mnote.save') : t('mnote.addNote'))}
              </Button>
            </div>
          </div>
        )}

        {notes.length === 0 && !adding && <div className="mnote-empty">{t('mnote.notesEmpty')}</div>}

        {notes.map(n => (
          <NoteCard key={n.id} n={n} onEdit={startEdit} onDelete={del} />
        ))}
      </div>
    </div>
  )
}

function NoteField({ label, value, onChange, required }) {
  return (
    <label className="mnote-field">
      <span>{label}{required && <span className="req-star"> *</span>}</span>
      <textarea rows={3} value={value} spellCheck={false} onChange={e => onChange(e.target.value)} />
    </label>
  )
}

function NoteRow({ label, value }) {
  if (!value) return null
  return (
    <div className="mnote-row">
      <div className="mnote-row-label">{label}</div>
      <div className="mnote-md"><MarkdownEditor value={value} editable={false} /></div>
    </div>
  )
}

// Tek not — akordiyon: varsayılan kapalı; başlıkta problem özeti + yazar·tarih, tıklayınca alanlar açılır.
// Düzenle/Sil başlıkta hep görünür (stopPropagation → toggle'ı tetiklemez).
function NoteCard({ n, onEdit, onDelete }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const toggle = () => setOpen(o => !o)
  return (
    <div className={`mnote-card${open ? ' open' : ''}`}>
      <div className="mnote-card-hdr" role="button" tabIndex={0} aria-expanded={open}
        onClick={toggle}
        onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle() } }}>
        <span className="mnote-card-toggle">{open ? <ChevronDown size={15} /> : <ChevronRight size={15} />}</span>
        <span className="mnote-card-summary">
          <span className="mnote-card-title">{n.problem || t('mnote.fProblem')}</span>
          <span className="mnote-meta">
            {n.author_name || n.author_username} · {fmt(n.created_at)}
            {n.updated_at ? ` · ${t('mnote.edited')}` : ''}
          </span>
        </span>
        <span className="mnote-card-actions" onClick={e => e.stopPropagation()}>
          <Button variant="secondary" size="sm" title={t('mnote.edit')} onClick={() => onEdit(n)}><Pencil size={12} /></Button>
          <Button variant="destructive" size="sm" title={t('mnote.delete')} onClick={() => onDelete(n)}><Trash2 size={12} /></Button>
        </span>
      </div>
      {open && (
        <div className="mnote-card-body">
          <NoteRow label={t('mnote.fProblem')} value={n.problem} />
          <NoteRow label={t('mnote.fAction')} value={n.action_taken} />
          <NoteRow label={t('mnote.fRoot')} value={n.root_cause} />
          <NoteRow label={t('mnote.fRefs')} value={n.refs} />
        </div>
      )}
    </div>
  )
}

function fmt(iso) {
  if (!iso) return ''
  try { return formatDate(iso) } catch { return iso }
}
