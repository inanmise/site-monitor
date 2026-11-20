import { useCallback, useEffect, useRef, useState } from 'react'
import { CircleAlert, EyeOff, History, NotebookPen, PencilLine, RefreshCw } from 'lucide-react'
import { api } from '../../../api/client'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { fmtDateTime } from '../TechErrorParts.jsx'
import { LazyNoteEditor, LazyNoteView } from './lazyParts.jsx'
import NoteVersionsDialog from './NoteVersionsDialog.jsx'
import { noteState } from './noteModel.js'

/**
 * KANAL SAYFASI "NOTLAR" SEKMESİ (2026-11-20): kanal başına tek zengin metinli belge — "bu kanalın verisini nereden,
 * nasıl alırım". Varsayılan OKUMA kipi (kilitli görüntüleyici); düzenleyebilen için "Düzenle" / boş durumda "Not yaz".
 *
 * Erişim sunucudadır; arayüz yalnız bayrakları okur: kanal satırında `can_read_notes === false` (yalnız görüntüleme
 * paylaşımı) ise uçlar HİÇ çağrılmaz. Bayrak yoksa (eski sunucu) sekme görünür ve uç denenir — eski pod'un kodsuz 404 /
 * 405'i "geçici olarak kullanılamıyor" durumudur. Okunamayan not hiçbir zaman "not yok" sayılmaz (hata + "Tekrar dene").
 * Geç gelen yanıtlar sıra numarasıyla atılır.
 */
export default function ChannelNotesTab({ channelId, channel, onDirtyChange }) {
  const t = useT()
  const toast = useToast()
  const { lang: uiLang } = useLanguage()
  const blocked = channel?.can_read_notes === false
  const [res, setRes] = useState(undefined)            // son okuma yanıtı (undefined = henüz yok)
  const [loading, setLoading] = useState(!blocked)
  const [refreshProblem, setRefreshProblem] = useState(null)
  const [data, setData] = useState(null)
  const [mode, setMode] = useState('read')             // read | edit
  const [editInit, setEditInit] = useState(null)       // sürümden açılan taslak { body, version }
  const [editorGen, setEditorGen] = useState(0)
  const [versionsOpen, setVersionsOpen] = useState(false)
  const versionsBtnRef = useRef(null)
  const editBtnRef = useRef(null)
  const seq = useRef(0)
  const tRef = useRef(t)
  tRef.current = t

  const load = useCallback(async ({ quiet = false } = {}) => {
    const my = ++seq.current
    if (!quiet) setLoading(true)
    setRefreshProblem(null)
    let next
    try {
      next = await api.techErrors.channelNote(channelId)
    } catch (e) {
      next = { success: false, status: 0, error: e?.message || null }
    }
    if (my !== seq.current) return null
    setLoading(false)
    if (next?.success && next.data) {
      setRes(next)
      setData(next.data)
      return next
    }
    // Sessiz yenileme başarısızsa eldeki not kalır (uyarıyla); ilk okuma başarısızsa durum ekranı
    if (quiet) setRefreshProblem(next?.error || tRef.current('te.note.unread'))
    else { setRes(next ?? null); setData(null) }
    return next ?? null
  }, [channelId])

  useEffect(() => {
    if (blocked) return undefined
    load()
    // Sekme kapanınca süren okumanın yanıtı atılır (sayaç bir ref — DOM düğümü değil)
    const counter = seq
    return () => { counter.current++ }
  }, [blocked, load])

  const state = blocked ? 'hidden' : loading && !data ? 'loading' : data ? noteState({ success: true, data }) : noteState(res)
  const canEdit = !!data?.can_edit
  const images = data?.images

  const startEdit = (init = null) => { setEditInit(init); setEditorGen((g) => g + 1); setMode('edit') }
  // Düzenleyici kapanınca odak "Düzenle" / "Not yaz" düğmesine döner (kaybolan düğmede kalmaz)
  const returnFocus = useRef(false)
  const closeEdit = useCallback(() => { returnFocus.current = true; setMode('read'); setEditInit(null) }, [])
  useEffect(() => {
    if (mode !== 'read' || !returnFocus.current) return
    returnFocus.current = false
    editBtnRef.current?.focus()
  }, [mode])
  // Kayıt yanıtı notun yeni hâlidir; o sırada süren okuma (eski hâl) atılır
  const onSaved = useCallback((saved) => {
    seq.current++
    setLoading(false)
    setData(saved)
    setRes({ success: true, data: saved })
    setEditInit(null)
  }, [])
  const onImagesChange = useCallback((list, bytes) => {
    setData((d) => (d ? { ...d, images: list, image_bytes: bytes ?? d.image_bytes } : d))
  }, [])
  const reloadLatest = useCallback(async () => {
    const next = await load({ quiet: true })
    if (next?.success) { setEditInit(null); setEditorGen((g) => g + 1) }
    else if (next !== null) toast.error(next?.error || tRef.current('te.note.unread'))
  }, [load, toast])

  if (state === 'hidden') {
    return (
      <div data-slot="te-notes-tab" data-state="hidden">
        <StatusBlock tone="neutral" icon={EyeOff} title={t('te.note.hiddenTitle')} description={t('te.note.hiddenBody')} />
      </div>
    )
  }
  if (state === 'loading') {
    return (
      <div className="flex flex-col gap-3" role="status" aria-live="polite" data-slot="te-notes-tab" data-state="loading">
        <span className="sr-only">{t('te.loading')}</span>
        <Skeleton className="h-10 w-full rounded-lg motion-reduce:animate-none" />
        <Skeleton className="h-48 w-full rounded-xl motion-reduce:animate-none" />
      </div>
    )
  }
  if (state === 'unsupported') {
    return (
      <div data-slot="te-notes-tab" data-state="unsupported">
        <StatusBlock tone="neutral" icon={CircleAlert} title={t('te.note.unavailableTitle')} description={t('te.note.unavailableBody')}
          actions={<Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={() => load()}><RefreshCw aria-hidden="true" /> {t('te.retry')}</Button>} />
      </div>
    )
  }
  if (state === 'notFound') {
    return (
      <div data-slot="te-notes-tab" data-state="notFound">
        <StatusBlock tone="neutral" icon={CircleAlert} title={t('te.channelGoneTitle')} description={res?.error || undefined} />
      </div>
    )
  }
  if (state === 'failed' || !data) {
    return (
      <div data-slot="te-notes-tab" data-state="failed">
        <StatusBlock tone="danger" icon={CircleAlert} title={t('te.note.unreadTitle')} description={res?.error || t('te.note.unread')}
          details={res?.errorInfo}
          actions={<Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={() => load()}><RefreshCw aria-hidden="true" /> {t('te.retry')}</Button>} />
      </div>
    )
  }

  if (mode === 'edit' && canEdit) {
    return (
      <div className="flex min-w-0 flex-col gap-3" data-slot="te-notes-tab" data-state="edit">
        {editInit?.version != null && (
          <AlertBanner tone="info" title={t('te.note.fromVersionTitle', editInit.version)}>{t('te.note.fromVersionBody')}</AlertBanner>
        )}
        <LazyNoteEditor key={editorGen} channelId={channelId} note={data} initialDraft={editInit} onDirtyChange={onDirtyChange}
          onSaved={onSaved} onImagesChange={onImagesChange} onReloadLatest={reloadLatest} onClose={closeEdit} />
      </div>
    )
  }

  const empty = state === 'empty'
  return (
    <div className="flex min-w-0 flex-col gap-4" data-slot="te-notes-tab" data-state={empty ? 'empty' : 'read'}>
      {refreshProblem && (
        <AlertBanner tone="danger" title={t('te.reloadFailed')}
          actions={<Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" onClick={() => load({ quiet: true })}>{t('te.retry')}</Button>}>
          {refreshProblem}
        </AlertBanner>
      )}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-1 basis-64 flex-col gap-1">
          <h3 className="flex flex-wrap items-center gap-2 text-base font-semibold">
            {t('te.note.title')}
            {data.shared_access && (
              <Badge variant="outline" className="overflow-visible rounded-md whitespace-normal" data-slot="te-note-shared">{t('te.note.sharedBadge')}</Badge>
            )}
          </h3>
          <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-note-meta">
            {empty || !data.updated_at
              ? t('te.note.intro')
              : t('te.note.updatedLine', fmtDateTime(data.updated_at, uiLang), data.updated_by_name || '—', data.version ?? '—')}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {data.exists && (
            <Button ref={versionsBtnRef} type="button" variant="outline" size="sm" className="pointer-coarse:min-h-10" data-slot="te-note-versions-open"
              onClick={() => setVersionsOpen(true)}>
              <History aria-hidden="true" /> {t('te.ver.open')}
            </Button>
          )}
          {canEdit && !empty && (
            <Button ref={editBtnRef} type="button" size="sm" className="pointer-coarse:min-h-10" data-slot="te-note-edit" onClick={() => startEdit()}>
              <PencilLine aria-hidden="true" /> {t('te.note.edit')}
            </Button>
          )}
        </div>
      </div>

      {empty ? (
        <StatusBlock tone="neutral" icon={NotebookPen} title={t('te.note.emptyTitle')}
          description={canEdit ? t('te.note.emptyBodyEdit') : t('te.note.emptyBodyRead')}
          actions={canEdit ? (
            <Button ref={editBtnRef} type="button" className="pointer-coarse:min-h-10" data-slot="te-note-write" onClick={() => startEdit()}>
              <PencilLine aria-hidden="true" /> {t('te.note.write')}
            </Button>
          ) : null}>
          {canEdit && (
            <ul className="mx-auto flex max-w-xl list-disc flex-col gap-1 ps-6 text-left text-sm text-muted-foreground" data-slot="te-note-guide">
              <li>{t('te.note.guide1')}</li>
              <li>{t('te.note.guide2')}</li>
              <li>{t('te.note.guide3')}</li>
            </ul>
          )}
        </StatusBlock>
      ) : (
        <div className="min-w-0 rounded-xl border bg-card p-4" data-slot="te-note-read">
          <LazyNoteView body={data.body} images={images} />
        </div>
      )}

      <NoteVersionsDialog channelId={channelId} open={versionsOpen} onClose={() => setVersionsOpen(false)} canLoad={canEdit}
        currentVersion={data.version ?? null} images={images} returnFocusRef={versionsBtnRef}
        onLoad={({ version, body }) => { startEdit({ body, version }); return true }} />
    </div>
  )
}
