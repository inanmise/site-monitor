import { memo, useCallback, useEffect, useRef, useState } from 'react'
import { NotebookText, PencilLine, RefreshCw } from 'lucide-react'
import { api } from '../../../api/client'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { CollapsibleCard, SectionOpenContext, sectionOpenKey, useSectionOpenState, useSectionOpenValue } from '../SectionCollapse.jsx'
import { isSectionOpen } from '../techErrorModel.js'
import { fmtDateTime } from '../TechErrorParts.jsx'
import { LazyNoteView } from './lazyParts.jsx'
import { noteCardState, noteState, sameNoteMeta } from './noteModel.js'

/** Kartın (tek) bölüm kimliği ve açık / kapalı kaydının türü: `sm.te.open.rn.<rapor>` (kişisel kayıt, çıkışta silinir). */
const CARD_ID = 'channel-notes'
const CARD_IDS = Object.freeze([CARD_ID])
const NO_INVALID = new Map()
const IDLE = Object.freeze({ key: null, status: 'idle', data: null, error: null })

/**
 * "KANAL NOTLARI" KARTI (2026-11-20) — rapor düzenleyicisinin başında ve Görsel paneli penceresinin tepesinde SALT OKUNUR,
 * katlanır kart: rapor hazırlayan "bu kanalın verisini nereden / nasıl alırım" notunu rapordan çıkmadan okur.
 *
 * - Yalnız rapor yanıtındaki `channel_notes.readable === true` ise çizilir (anahtar yoksa / okunamaz ise kart HİÇ yok).
 * - Kapalı başlar; açık / kapalı durumu rapor başına hatırlanır. Not, kart İLK açıldığında okunur (kapalı kart istek atmaz).
 * - `unavailable` ya da okunamayan not "not yok" DEĞİLDİR: "okunamadı" + "Tekrar dene".
 * - Not yoksa "henüz not yok"; düzenleyebilen için "Not yaz" bağlantısı (rapor düzenleyicisinin ayrılma kapısından geçer).
 *
 * memo + değerle karşılaştırma: rapor düzenleyicisi her tuşta yeniden çizilirken kart çizilmez (prop'lar kararlı olmalı —
 * `onEdit` için `useLatestCallback`).
 */
function ChannelNotesCard({ reportId, channelId, meta, canEdit = false, onEdit = null, headingBase = 4 }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const kind = noteCardState(meta)
  const openState = useSectionOpenState(sectionOpenKey('rn', reportId), CARD_IDS)
  const openValue = useSectionOpenValue(openState, NO_INVALID)
  const open = isSectionOpen(openState.state, CARD_ID)
  // Okunan not, o anki özetle (kanal + son güncelleme) eşlenir: not başka yerde değiştiyse sonraki açılışta yeniden okunur
  const metaKey = `${channelId}|${meta?.updated_at ?? ''}|${meta?.exists ? 1 : 0}|${meta?.empty ? 1 : 0}`
  const [loaded, setLoaded] = useState(IDLE)
  const load = loaded.key === metaKey ? loaded : IDLE
  const seq = useRef(0)
  useEffect(() => () => { seq.current++ }, [])

  const fetchNote = useCallback(async () => {
    const my = ++seq.current
    setLoaded({ key: metaKey, status: 'loading', data: null, error: null })
    let res
    try {
      res = await api.techErrors.channelNote(channelId)
    } catch (e) {
      res = { success: false, status: 0, error: e?.message || null }
    }
    if (my !== seq.current) return
    setLoaded({ key: metaKey, status: noteState(res), data: res?.success ? res.data : null, error: res?.success ? null : (res?.error || null) })
  }, [channelId, metaKey])

  // Not var ve kart açık: ilk açılışta oku
  const due = open && kind === 'note' && load.status === 'idle'
  useEffect(() => {
    if (due) fetchNote()
  }, [due, fetchNote])

  if (kind === 'none') return null

  const writeLink = canEdit && typeof onEdit === 'function'
  const retry = (
    <Button type="button" size="sm" variant="outline" className="w-fit pointer-coarse:min-h-10" data-slot="te-notes-card-retry" onClick={fetchNote}>
      <RefreshCw aria-hidden="true" /> {t('te.retry')}
    </Button>
  )
  const editButton = (labelKey, slot) => (
    <Button type="button" size="sm" variant="outline" className="w-fit pointer-coarse:min-h-10" data-slot={slot} onClick={() => onEdit()}>
      <PencilLine aria-hidden="true" /> {t(labelKey)}
    </Button>
  )

  let state
  let body
  if (load.status === 'loading') {
    state = 'loading'
    body = (
      <div className="flex flex-col gap-2" role="status" aria-live="polite">
        <span className="sr-only">{t('te.loading')}</span>
        <Skeleton className="h-4 w-3/4 max-w-full motion-reduce:animate-none" />
        <Skeleton className="h-4 w-1/2 max-w-full motion-reduce:animate-none" />
      </div>
    )
  } else if (load.status === 'ok') {
    state = 'note'
    body = (
      <div className="flex min-w-0 flex-col gap-3">
        <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-notes-card-meta">
          {t('te.note.card.updated', fmtDateTime(load.data.updated_at, uiLang), load.data.updated_by_name || '—')}
        </p>
        <LazyNoteView body={load.data.body} images={load.data.images} headingBase={headingBase} />
        {writeLink && editButton('te.note.card.edit', 'te-notes-card-edit')}
      </div>
    )
  } else if (load.status === 'empty' || (kind === 'empty' && load.status === 'idle')) {
    state = 'empty'
    body = (
      <div className="flex min-w-0 flex-col gap-2">
        <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-notes-card-empty">
          {writeLink ? t('te.note.card.emptyEdit') : t('te.note.card.empty')}
        </p>
        {writeLink && editButton('te.note.write', 'te-notes-card-write')}
      </div>
    )
  } else if (load.status === 'unsupported') {
    state = 'unsupported'
    body = (
      <div className="flex min-w-0 flex-col gap-2">
        <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-notes-card-unavailable">{t('te.note.unavailableBody')}</p>
        {retry}
      </div>
    )
  } else if (kind === 'note' && load.status === 'idle') {
    // Kapalı kart: içerik kurulu ama not henüz okunmadı (ilk açılışta okunur)
    state = 'idle'
    body = <p className="text-sm text-muted-foreground">{t('te.loading')}</p>
  } else {
    // `unavailable` özeti ya da okunamayan not (ağ, 5xx, 403, 404): "not yok" denmez
    state = 'unread'
    body = (
      <div className="flex min-w-0 flex-col gap-2">
        <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert" data-slot="te-notes-card-unread">
          {load.error || t('te.note.card.unread')}
        </p>
        {retry}
      </div>
    )
  }

  return (
    <SectionOpenContext.Provider value={openValue}>
      <CollapsibleCard sectionId={CARD_ID} slot="te-notes-card" title={t('te.note.card.title')} copyText=""
        icon={<NotebookText aria-hidden="true" className="mt-[3px] size-4 shrink-0 text-muted-foreground" />}
        hint={<p className="text-sm text-muted-foreground [overflow-wrap:anywhere]">{t('te.note.card.hint')}</p>}
        // `data-state` Radix Collapsible'ındır (open / closed) — kartın not durumu kendi özniteliğinde
        cardProps={{ 'data-note-state': state, 'data-kind': kind }}>
        {body}
      </CollapsibleCard>
    </SectionOpenContext.Provider>
  )
}

export default memo(ChannelNotesCard, (a, b) => a.reportId === b.reportId && a.channelId === b.channelId && a.canEdit === b.canEdit
  && a.onEdit === b.onEdit && a.headingBase === b.headingBase && sameNoteMeta(a.meta, b.meta))
