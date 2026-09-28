import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Lock, PenLine, RotateCw, Search, SearchX, StickyNote, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import CertNoteComposer from './CertNoteComposer.jsx'
import CertNoteItem from './CertNoteItem.jsx'
import {
  NOTE_CATEGORIES, NOTE_MAX_LENGTH, categoryCounts, liveNoteCount, notePermissions, visibleNotes,
} from './notesModel.js'
import { CAT_ICON } from './notesParts.jsx'

/**
 * Sertifika penceresi → "Notlar" sekmesi (2026-09-28 shadcn yeniden tasarım; eski CertificateModal içi `NotesTab`).
 *
 * <p>Düzen: (1) yazma kartı — yalnız yönetici / takım yöneticisi; salt okunur kayıtta (başka takımın alan adı) ya da
 * yetkisiz kullanıcıda kart yerine NEDENİNİ söyleyen bilgi bandı · (2) araç çubuğu — kategori süzgeci (sayılı çipler),
 * arama, silinmişleri göster anahtarı · (3) zaman çizelgesi (en yeni üstte) — certmodal/CertNoteItem.
 * Boş durum eylem çağrısıyla ("İlk notu yaz" → yazma alanına odak), süzgeçte sonuç yoksa "Süzgeçleri temizle".
 *
 * <p>Davranış ve izinler eskisiyle AYNI (notesModel.notePermissions; sunucu da uygular): ekleme yöneticide, düzenleme
 * yazarda ve 24 saat içinde, silme yazarda ya da yöneticide, geri yükleme yöneticide; salt okunurda hiçbiri. Silme ve geri
 * yükleme onay penceresiyle (useDialog → shadcn AlertDialog). Her meşgul bayrağı `finally` ile iner (busyFlagFinally).
 * Sunucu hataları satır içinde (yazma kartında ya da listenin üstünde), yükleme hatası "Yeniden dene" ile.
 *
 * <p>`onCountChange(n)`: silinmemiş not sayısı değişince (ekle/sil/geri yükle) sekme sayacını tazeler.
 */
export default function CertNotesTab({ domain, currentUser, isAdmin = false, readOnly = false, readOnlyTeam = null, onCountChange }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [notes, setNotes] = useState(null)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState(null)
  const [draft, setDraft] = useState('')
  const [draftCategory, setDraftCategory] = useState('NOTE')
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState(null)
  const [actionError, setActionError] = useState(null)
  const [filter, setFilter] = useState('ALL')
  const [query, setQuery] = useState('')
  const [showDeleted, setShowDeleted] = useState(true)
  const [editingId, setEditingId] = useState(null)
  const [editBody, setEditBody] = useState('')
  const [editSaving, setEditSaving] = useState(false)
  const [pendingId, setPendingId] = useState(null)
  const [historyOpen, setHistoryOpen] = useState({})
  const [revisions, setRevisions] = useState({})
  const [historyLoading, setHistoryLoading] = useState({})
  const composerRef = useRef(null)
  const searchId = useId()
  const deletedSwitchId = useId()
  const domainRef = useRef(domain)
  domainRef.current = domain
  const countRef = useRef(onCountChange)
  countRef.current = onCountChange
  const canWrite = isAdmin && !readOnly

  // Tur sayacı: yalnız EN SON istek sonucu yazar ve yükleme bayrağını indirir. Eskiden bayat yanıt verisini atıp
  // `finally`'de bayrağı yine de indiriyordu — alan adı değişince yeni istek sürerken notes=null + loading=false kalıyor,
  // gövde bir an yalancı "Henüz not yok" çiziyordu (2026-09-28 regresyon taraması).
  const loadSeq = useRef(0)
  const loadNotes = useCallback(async () => {
    const reqDomain = domainRef.current
    const seq = ++loadSeq.current
    setLoading(true)
    setLoadError(null)
    try {
      const res = await api.admin.getNotes(reqDomain)
      if (seq !== loadSeq.current) return
      if (res?.success === false) { setLoadError(res.error || t('settings.loadError')); return }
      const list = Array.isArray(res?.data) ? res.data : []
      setNotes(list)
      countRef.current?.(liveNoteCount(list))
    } catch (e) {
      if (seq === loadSeq.current) setLoadError(e?.message || t('settings.loadError'))
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }, [t])

  // Alan adı değişince (pencere kalıcı mount'lu) önceki kaydın notları/taslağı/süzgeci taşınmaz.
  useEffect(() => {
    setNotes(null); setDraft(''); setDraftCategory('NOTE'); setFormError(null); setActionError(null)
    setFilter('ALL'); setQuery(''); setEditingId(null); setEditBody(''); setHistoryOpen({}); setRevisions({})
    if (domain) loadNotes()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [domain])

  const labelOf = useCallback((c) => t(`notes.cat.${c}`), [t])
  const counts = useMemo(() => categoryCounts(notes, { showDeleted, query, labelOf }), [notes, showDeleted, query, labelOf])
  const shown = useMemo(() => visibleNotes(notes, { category: filter, query, showDeleted, labelOf }), [notes, filter, query, showDeleted, labelOf])
  const deletedCount = useMemo(() => (notes || []).filter((n) => n.deleted_at).length, [notes])

  async function addNote() {
    setFormError(null)
    const body = draft.trim()
    if (!body) return
    if (draft.length > NOTE_MAX_LENGTH) { setFormError(t('notes.tooLong', NOTE_MAX_LENGTH)); return }
    setSaving(true)
    try {
      const res = await api.admin.addNote(domain, body, draftCategory)
      if (res?.success) {
        setDraft('')
        setDraftCategory('NOTE')
        await loadNotes()
      } else {
        setFormError(res?.error || t('notes.saveFailed'))
      }
    } catch (e) {
      setFormError(e?.message || t('notes.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  const startEdit = useCallback((n) => { setEditingId(n.id); setEditBody(n.note); setActionError(null) }, [])
  const cancelEdit = useCallback(() => { setEditingId(null); setEditBody('') }, [])

  async function saveEdit() {
    setActionError(null)
    const body = editBody.trim()
    if (!body) return
    if (editBody.length > NOTE_MAX_LENGTH) { setActionError(t('notes.tooLong', NOTE_MAX_LENGTH)); return }
    const id = editingId
    setEditSaving(true)
    try {
      const res = await api.admin.updateNote(domain, id, body)
      if (res?.success) {
        // Önbellekteki geçmiş geçersiz: bir sonraki açılış yeni EDIT kaydını getirsin
        setRevisions((prev) => { const c = { ...prev }; delete c[id]; return c })
        cancelEdit()
        await loadNotes()
      } else {
        setActionError(res?.error || t('notes.saveFailed'))
      }
    } catch (e) {
      setActionError(e?.message || t('notes.saveFailed'))
    } finally {
      setEditSaving(false)
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
    setActionError(null)
    setPendingId(n.id)
    try {
      const res = await api.admin.deleteNote(domain, n.id)
      if (res?.success) {
        setRevisions((prev) => { const c = { ...prev }; delete c[n.id]; return c })
        if (editingId === n.id) cancelEdit()
        await loadNotes()
      } else {
        setActionError(res?.error || t('cnote.deleteFailed'))
      }
    } catch (e) {
      setActionError(e?.message || t('cnote.deleteFailed'))
    } finally {
      setPendingId(null)
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
    setActionError(null)
    setPendingId(n.id)
    try {
      const res = await api.admin.restoreNote(domain, n.id)
      if (res?.success) {
        setRevisions((prev) => { const c = { ...prev }; delete c[n.id]; return c })
        await loadNotes()
      } else {
        setActionError(res?.error || t('cnote.restoreFailed'))
      }
    } catch (e) {
      setActionError(e?.message || t('cnote.restoreFailed'))
    } finally {
      setPendingId(null)
    }
  }

  async function toggleHistory(noteId) {
    const willOpen = !historyOpen[noteId]
    setHistoryOpen((prev) => ({ ...prev, [noteId]: willOpen }))
    if (!willOpen || revisions[noteId]) return
    setHistoryLoading((prev) => ({ ...prev, [noteId]: true }))
    // try/finally ŞART: request() ağ hatasında THROW eder; yakalanmazsa geçmiş spinner'ı kalıcı dönerdi. Hatada revizyon
    // YAZILMAZ ve panel kapanır ("geçmiş yok" DENMEZ — bilinmiyor ≠ yok); yeniden açmak yeniden dener.
    try {
      const res = await api.admin.getNoteRevisions(domain, noteId)
      if (res?.success === false) throw new Error(res.error || t('settings.loadError'))
      setRevisions((prev) => ({ ...prev, [noteId]: Array.isArray(res?.data) ? res.data : [] }))
    } catch (e) {
      setHistoryOpen((prev) => ({ ...prev, [noteId]: false }))
      setActionError(e?.message || t('settings.loadError'))
    } finally {
      setHistoryLoading((prev) => ({ ...prev, [noteId]: false }))
    }
  }

  function clearFilters() { setFilter('ALL'); setQuery(''); setShowDeleted(true) }
  function focusComposer() { composerRef.current?.focus() }

  // ── Üst bölge: yazma kartı YA DA neden yazılamadığını söyleyen bant ──
  let head
  if (readOnly) {
    head = (
      <AlertBanner tone="info" icon={Lock} title={t('cnote.readOnlyTitle')} className="mb-0">
        {readOnlyTeam?.name ? t('cnote.readOnlyTeam', readOnlyTeam.name) : t('cnote.readOnlyBody')}
      </AlertBanner>
    )
  } else if (!isAdmin) {
    head = (
      <AlertBanner tone="info" icon={Lock} title={t('cnote.noWriteTitle')} className="mb-0">{t('cnote.noWriteBody')}</AlertBanner>
    )
  } else {
    head = (
      <CertNoteComposer ref={composerRef} value={draft} onChange={setDraft} category={draftCategory}
        onCategoryChange={setDraftCategory} onSubmit={addNote} saving={saving} error={formError} />
    )
  }

  let body
  // Liste HENÜZ yoksa (ilk çizim, alan adı değişimi, uçuşan istek) boş durum DEĞİL yükleniyor: "henüz not yok" ancak
  // sunucu boş liste döndüğünde söylenir (bilinmiyor ≠ yok).
  if (notes == null && !loadError) {
    body = <LoadingBlock label={t('modal.loading')} fullWidth />
  } else if (loadError && notes == null) {
    body = (
      <StatusBlock tone="danger" icon={StickyNote} title={t('cnote.loadFailed')} description={loadError}
        actions={<Button type="button" variant="outline" className="gap-1.5 max-sm:h-10" onClick={loadNotes}><RotateCw aria-hidden="true" className="size-4" />{t('cnote.retry')}</Button>} />
    )
  } else if (!notes || notes.length === 0) {
    body = (
      <StatusBlock tone="neutral" icon={StickyNote} title={t('cnote.emptyTitle')}
        description={canWrite ? t('cnote.emptyBody') : t('cnote.emptyReadBody')} className="py-8 md:py-8"
        actions={canWrite ? (
          <Button type="button" variant="outline" className="gap-1.5 max-sm:h-10" onClick={focusComposer}>
            <PenLine aria-hidden="true" className="size-4" />{t('cnote.writeFirst')}
          </Button>
        ) : null} />
    )
  } else {
    body = (
      <>
        <div data-slot="cert-note-toolbar" className="flex min-w-0 flex-col gap-2 lg:flex-row lg:flex-wrap lg:items-center">
          <ToggleGroup type="single" variant="outline" size="sm" spacing={1} value={filter}
            onValueChange={(v) => { if (v) setFilter(v) }} aria-label={t('cnote.filterLabel')} className="flex-wrap">
            {['ALL', ...NOTE_CATEGORIES].map((c) => {
              const Icon = CAT_ICON[c]
              const n = counts[c] ?? 0
              return (
                <ToggleGroupItem key={c} value={c} data-slot="cert-note-filter" data-count={n}
                  disabled={n === 0 && c !== filter && c !== 'ALL'}
                  className="gap-1.5 text-[13px] max-sm:h-10 data-[state=on]:border-primary/50 data-[state=on]:bg-primary/10 data-[state=on]:text-primary">
                  {Icon && <Icon aria-hidden="true" className="size-3.5" />}
                  {c === 'ALL' ? t('cnote.filterAll') : t(`notes.cat.${c}`)}
                  <Badge variant="secondary" className="h-4 min-w-4 px-1 text-[10.5px] tabular-nums">{n}</Badge>
                </ToggleGroupItem>
              )
            })}
          </ToggleGroup>
          <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2 lg:ml-auto">
            <InputGroup className="w-full max-sm:h-10 sm:w-64">
              <InputGroupInput id={searchId} type="text" inputMode="search" enterKeyHint="search" className="max-sm:h-10" value={query} onChange={(e) => setQuery(e.target.value)}
                placeholder={t('cnote.searchPlaceholder')} aria-label={t('cnote.searchLabel')} />
              <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              {query && (
                <InputGroupAddon align="inline-end">
                  <InputGroupButton size="icon-xs" className="max-sm:size-8" aria-label={t('cnote.clearSearch')} onClick={() => setQuery('')}>
                    <X aria-hidden="true" />
                  </InputGroupButton>
                </InputGroupAddon>
              )}
            </InputGroup>
            {deletedCount > 0 && (
              <div className="flex items-center gap-2 max-sm:min-h-10">
                <Switch id={deletedSwitchId} checked={showDeleted} onCheckedChange={setShowDeleted} />
                <Label htmlFor={deletedSwitchId} className="text-[13px] font-normal max-sm:min-h-10">{t('cnote.showDeleted', deletedCount)}</Label>
              </div>
            )}
          </div>
        </div>

        {shown.length === 0 ? (
          <StatusBlock tone="neutral" icon={SearchX} title={t('cnote.noMatchTitle')} description={t('notes.emptyForFilter')} className="py-8 md:py-8"
            actions={<Button type="button" variant="outline" className="max-sm:h-10" onClick={clearFilters}>{t('cnote.clearFilters')}</Button>} />
        ) : (
          <ol data-slot="cert-note-timeline" aria-label={t('cnote.timelineLabel')} className="flex min-w-0 flex-col">
            {shown.map((n, i) => (
              <CertNoteItem key={n.id} note={n} last={i === shown.length - 1}
                perms={notePermissions(n, { currentUser, isAdmin, readOnly })}
                editing={editingId === n.id} editBody={editBody} onEditBody={setEditBody}
                onStartEdit={startEdit} onCancelEdit={cancelEdit} onSaveEdit={saveEdit} editSaving={editSaving}
                onDelete={deleteNote} onRestore={restoreNote} pending={pendingId === n.id}
                historyOpen={!!historyOpen[n.id]} onToggleHistory={toggleHistory}
                revisions={revisions[n.id] ?? []} historyLoading={!!historyLoading[n.id]} />
            ))}
          </ol>
        )}
      </>
    )
  }

  return (
    // aria-busy: liste (yeniden) yükleniyor — yalnız EN SON isteğin bitişiyle iner (bkz. loadSeq).
    <div data-slot="cert-notes" aria-busy={loading || undefined} className={cn('flex min-w-0 flex-col gap-4')}>
      {head}
      {actionError && (
        <AlertBanner tone="danger" role="alert" className="mb-0" onDismiss={() => setActionError(null)} dismissLabel={t('app.close')}>
          {actionError}
        </AlertBanner>
      )}
      {body}
    </div>
  )
}
