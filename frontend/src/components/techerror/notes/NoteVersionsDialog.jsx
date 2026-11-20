import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowLeft, CircleAlert, History, Upload, X } from 'lucide-react'
import { api } from '../../../api/client'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import { cn } from '@/lib/utils'
import { fmtDateTime } from '../TechErrorParts.jsx'
import { LazyNoteView } from './lazyParts.jsx'
import { NOTE_LIMITS, isOldServerAnswer, versionCountKeys } from './noteModel.js'

/**
 * KANAL NOTU SÜRÜMLERİ (2026-11-20): her kayıt bir sürümdür; en yeni 20'si saklanır. Liste (en yeni önce) → seçilen
 * sürümün önizlemesi (notun KİLİTLİ görüntüleyicisiyle) → "Düzenleyiciye yükle": sürüm metni ŞİMDİKİ sürümün üzerine
 * kaydedilmemiş taslak olarak gelir (sunucuya bir şey yazılmaz; arada başkası kaydettiyse kayıt yine 409 alır).
 *
 * Önizlemede görseller notun ŞİMDİKİ görsel listesinden çizilir — eski sürümün başvurduğu görsel silindiyse yerinde
 * "görsel artık yok" kutusu görünür. Okunamayan liste / sürüm "yok" sayılmaz: hata + "Tekrar dene".
 */
export default function NoteVersionsDialog({ channelId, open, onClose, canLoad = false, currentVersion = null, images, onLoad, returnFocusRef }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const [list, setList] = useState({ loading: false, error: null, unsupported: false, data: null })
  const [selected, setSelected] = useState(null)
  const [details, setDetails] = useState({})            // sürüm → { loading, error, data }
  const [pane, setPane] = useState('list')              // dar ekran: list | detail
  const seq = useRef(0)
  const detailsRef = useRef(details)
  detailsRef.current = details
  const tRef = useRef(t)
  tRef.current = t
  // Dar ekranda liste ↔ ayrıntı geçişinde odak gizlenen bölmede kalmasın
  const backRef = useRef(null)
  const itemRefs = useRef({})
  const paneMove = useRef(null)
  useEffect(() => {
    const to = paneMove.current
    paneMove.current = null
    if (to === 'detail' && backRef.current?.offsetParent) backRef.current.focus()
    else if (to === 'list') itemRefs.current[selected]?.focus()
  }, [pane, selected])

  // Liste bu açılışta okundu mu: yeniden açılışta önceki seçimin ayrıntısı (gösterilmeyecek) istenmesin
  const listReady = useRef(false)
  const loadList = useCallback(async () => {
    const my = ++seq.current
    listReady.current = false
    setList({ loading: true, error: null, unsupported: false, data: null })
    detailsRef.current = {}
    setDetails({})
    try {
      const res = await api.techErrors.channelNoteVersions(channelId)
      if (my !== seq.current) return
      if (res?.success && res.data && Array.isArray(res.data.versions)) {
        listReady.current = true
        setList({ loading: false, error: null, unsupported: false, data: res.data })
        setSelected(res.data.versions[0]?.version ?? null)
      } else {
        setList({ loading: false, error: res?.error || tRef.current('te.note.ver.listUnread'), unsupported: isOldServerAnswer(res), data: null })
      }
    } catch (e) {
      if (my === seq.current) setList({ loading: false, error: e?.message || tRef.current('te.note.ver.listUnread'), unsupported: false, data: null })
    }
  }, [channelId])

  useEffect(() => {
    if (open) { setPane('list'); loadList() } else seq.current++
  }, [open, loadList])
  useEffect(() => () => { seq.current++ }, [])

  const fetchDetail = useCallback(async (version) => {
    if (version == null) return
    const cur = detailsRef.current[version]
    if (cur && (cur.loading || cur.data)) return
    const my = seq.current
    setDetails((d) => ({ ...d, [version]: { loading: true, error: null, data: null } }))
    try {
      const res = await api.techErrors.channelNoteVersion(channelId, version)
      if (my !== seq.current) return
      setDetails((d) => ({ ...d, [version]: res?.success && res.data && typeof res.data.body === 'string'
        ? { loading: false, error: null, data: res.data }
        : { loading: false, error: res?.error || tRef.current('te.note.ver.oneUnread'), data: null } }))
    } catch (e) {
      if (my === seq.current) setDetails((d) => ({ ...d, [version]: { loading: false, error: e?.message || tRef.current('te.note.ver.oneUnread'), data: null } }))
    }
  }, [channelId])

  // Ayrıntı yalnız BU açılışın listesi geldikten sonra istenir (liste değişince de — en yeni sürüm öncekiyle aynıysa)
  const listData = list.data
  useEffect(() => {
    if (open && listReady.current && listData && selected != null) fetchDetail(selected)
  }, [open, selected, fetchDetail, listData])

  const versions = list.data?.versions || []
  const sel = versions.find((v) => v.version === selected) || null
  const maxVersions = list.data?.max_versions || NOTE_LIMITS.max_versions
  // Sınıra ulaşıldıysa ve en eski satır sürüm 1 değilse daha eskileri budanmıştır
  const pruned = versions.length >= maxVersions && versions[versions.length - 1]?.version > 1
  const detail = sel ? details[sel.version] : null
  // "21 karakter · 1 görsel" — sayı 1 ise tekil biçim (EN "1 image")
  const countsText = (v) => {
    const [charKey, imgKey] = versionCountKeys(v.char_count, v.image_count)
    return `${t(charKey, v.char_count ?? '—')} · ${t(imgKey, v.image_count ?? '—')}`
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="flex max-h-[92dvh] w-[calc(100vw-1.5rem)] max-w-4xl flex-col gap-0 overflow-hidden p-0 sm:max-w-4xl"
        data-slot="te-note-versions" showCloseButton={false}
        onCloseAutoFocus={(e) => { if (returnFocusRef?.current) { e.preventDefault(); returnFocusRef.current.focus() } }}>
        <DialogClose asChild>
          <Button type="button" variant="ghost" size="icon" className="absolute top-2.5 right-2.5 size-8 pointer-coarse:size-10"
            aria-label={t('app.close')}>
            <X aria-hidden="true" />
          </Button>
        </DialogClose>
        <DialogHeader className="border-b p-4 pe-14 text-left">
          <DialogTitle className="flex items-center gap-2"><History aria-hidden="true" className="size-4" /> {t('te.note.ver.title')}</DialogTitle>
          <DialogDescription>{t('te.note.ver.desc', maxVersions)}</DialogDescription>
        </DialogHeader>
        {list.loading && (
          <div className="flex flex-col gap-3 p-4" role="status" aria-live="polite" data-slot="te-note-versions-loading">
            <span className="sr-only">{t('te.loading')}</span>
            <Skeleton className="h-16 w-full rounded-lg motion-reduce:animate-none" />
            <Skeleton className="h-40 w-full rounded-lg motion-reduce:animate-none" />
          </div>
        )}
        {!list.loading && list.error && (
          <div className="p-4" data-slot="te-note-versions-failed" data-state={list.unsupported ? 'unsupported' : 'failed'}>
            {list.unsupported ? (
              <StatusBlock tone="neutral" icon={CircleAlert} title={t('te.note.unavailableTitle')} description={t('te.note.unavailableBody')}
                actions={<Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={loadList}>{t('te.retry')}</Button>} />
            ) : (
              <StatusBlock tone="danger" icon={CircleAlert} title={t('te.note.ver.listUnreadTitle')} description={list.error}
                actions={<Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={loadList}>{t('te.retry')}</Button>} />
            )}
          </div>
        )}
        {list.data && versions.length === 0 && (
          <div className="p-4" data-slot="te-note-versions-empty">
            <StatusBlock tone="neutral" icon={History} title={t('te.note.ver.noneTitle')} description={t('te.note.ver.noneBody')} />
          </div>
        )}
        {list.data && versions.length > 0 && (
          <div className="grid min-h-0 flex-1 md:grid-cols-[17rem_minmax(0,1fr)]">
            <div className={cn('min-h-0 flex-col overflow-y-auto border-b p-3 md:flex md:border-e md:border-b-0', pane === 'detail' ? 'hidden' : 'flex')}
              data-slot="te-note-versions-list">
              {pruned && <p className="mb-2 text-xs text-muted-foreground" data-slot="te-note-versions-pruned">{t('te.ver.pruned', maxVersions)}</p>}
              <ul className="flex list-none flex-col gap-1.5">
                {versions.map((v) => (
                  <li key={v.version}>
                    <Button type="button" variant={v.version === selected ? 'secondary' : 'ghost'} aria-current={v.version === selected ? 'true' : undefined}
                      className="h-auto w-full flex-col items-start gap-0.5 px-3 py-2 text-left whitespace-normal pointer-coarse:min-h-10"
                      data-slot="te-note-version-item" data-version={v.version}
                      ref={(el) => { if (el) itemRefs.current[v.version] = el; else delete itemRefs.current[v.version] }}
                      onClick={() => { setSelected(v.version); paneMove.current = 'detail'; setPane('detail') }}>
                      <span className="flex flex-wrap items-center gap-1.5 font-semibold">
                        {t('te.ver.versionN', v.version)}
                        {currentVersion != null && v.version === currentVersion && (
                          <Badge className="overflow-visible rounded-md bg-primary/10 text-primary">{t('te.ver.current')}</Badge>
                        )}
                      </span>
                      <span className="text-xs font-normal text-muted-foreground [overflow-wrap:anywhere]">
                        {fmtDateTime(v.saved_at, uiLang)} · {v.saved_by_name || '—'}
                      </span>
                      <span className="text-xs font-normal text-muted-foreground" data-slot="te-note-version-counts">
                        {countsText(v)}
                      </span>
                    </Button>
                  </li>
                ))}
              </ul>
            </div>
            <div className={cn('min-h-0 min-w-0 flex-col overflow-y-auto p-4 md:flex', pane === 'list' ? 'hidden' : 'flex')} data-slot="te-note-version-detail">
              <div className="sticky top-0 z-10 -mx-4 -mt-4 mb-2 border-b bg-background px-4 py-1.5 md:hidden">
                <Button ref={backRef} type="button" variant="ghost" size="sm" className="w-fit pointer-coarse:min-h-10"
                  data-slot="te-note-versions-back" onClick={() => { paneMove.current = 'list'; setPane('list') }}>
                  <ArrowLeft aria-hidden="true" /> {t('te.ver.backToList')}
                </Button>
              </div>
              {sel && (!detail || detail.loading) && (
                <div className="flex flex-col gap-3" role="status" aria-live="polite">
                  <span className="sr-only">{t('te.loading')}</span>
                  <Skeleton className="h-24 w-full rounded-lg motion-reduce:animate-none" />
                </div>
              )}
              {sel && detail?.error && (
                <StatusBlock tone="danger" icon={CircleAlert} title={t('te.note.ver.oneUnreadTitle')} description={detail.error}
                  actions={(
                    <Button type="button" variant="outline" className="pointer-coarse:min-h-10"
                      onClick={() => { detailsRef.current = { ...detailsRef.current, [sel.version]: undefined }; setDetails((d) => ({ ...d, [sel.version]: undefined })); fetchDetail(sel.version) }}>
                      {t('te.retry')}
                    </Button>
                  )} />
              )}
              {sel && detail?.data && (
                <div className="flex min-w-0 flex-col gap-4">
                  <div className="flex flex-col gap-1">
                    <h3 className="flex flex-wrap items-center gap-2 text-lg font-semibold">
                      {t('te.ver.versionN', sel.version)}
                      {currentVersion != null && sel.version === currentVersion && (
                        <Badge className="overflow-visible rounded-md bg-primary/10 text-primary">{t('te.ver.current')}</Badge>
                      )}
                    </h3>
                    <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]">
                      {t('te.ver.savedBy', fmtDateTime(detail.data.saved_at || sel.saved_at, uiLang), detail.data.saved_by_name || sel.saved_by_name || '—')}
                    </p>
                  </div>
                  {canLoad && (
                    <div className="flex flex-col gap-1.5 rounded-lg border bg-muted/30 p-3">
                      <p className="text-sm text-muted-foreground">{t('te.note.ver.loadHint')}</p>
                      <Button type="button" className="w-fit pointer-coarse:min-h-10" data-slot="te-note-version-load"
                        onClick={async () => { if (await onLoad?.({ version: sel.version, body: detail.data.body })) onClose() }}>
                        <Upload aria-hidden="true" /> {t('te.note.ver.load')}
                      </Button>
                    </div>
                  )}
                  <div className="min-w-0 rounded-lg border p-3" data-slot="te-note-version-preview">
                    {detail.data.body.trim() === ''
                      ? <p className="text-sm text-muted-foreground">{t('te.note.ver.emptyBody')}</p>
                      : <LazyNoteView body={detail.data.body} images={images} headingBase={4} />}
                  </div>
                </div>
              )}
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
