import { useEffect, useRef, useState } from 'react'
import { BookMarked, ChevronLeft, ChevronRight, Link2, Pencil, Plus, Search, Trash2, Users, X } from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import { useDateLocale, useT } from '../../../i18n/index.jsx'
import { copyText } from '../../../utils/copyText.js'
import { useDialog } from '../../ui/Dialog.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import ChoiceToggle from './ChoiceToggle.jsx'
import { SAVED_PAGE_SIZE, savedLink } from './sqlLibraryModel.js'
import { relativeFrom } from './sqlUtils.js'

/** Sayfa sorgu dizesi. */
export function savedQuery({ scope = 'mine', q = '', tag = '', page = 0, size = SAVED_PAGE_SIZE } = {}) {
  const p = new URLSearchParams()
  p.set('scope', scope)
  if (q.trim()) p.set('q', q.trim().slice(0, 200))
  if (tag.trim()) p.set('tag', tag.trim().slice(0, 30))
  p.set('page', String(Math.max(0, page | 0)))
  p.set('size', String(size))
  return p.toString()
}

/**
 * Kayıtlı sorgular (2026-11-13, D1) — sağdan açılan Sheet: kapsam (benim / paylaşılan / tümü), arama, etiket süzgeci,
 * sunucu sayfalaması. Satır: başlık, açıklama, etiketler, sahip (+ "paylaşılan"), son güncelleme; eylemler: yeni sekmede aç,
 * bağlantıyı kopyala (`?tab=sqlplayground&sq=<id>`), düzenle / sil (yalnız sahip — `can_edit`). Eski sunucu (uç yok, 404 /
 * 405) → "bu sürümde yok" (`onUnsupported` ile özellik gizlenir). Okunamayan liste asla "kayıt yok" değil.
 */
export default function SavedQueriesPanel({ open, onOpenChange, onOpen, onEdit, onUnsupported, reloadKey = 0 }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [scope, setScope] = useState('mine')
  const [q, setQ] = useState('')
  const [qd, setQd] = useState('')
  const [tag, setTag] = useState('')
  const [tagD, setTagD] = useState('')
  const [page, setPage] = useState(0)
  const [state, setState] = useState({ loading: false, error: null, rows: [], total: 0, unsupported: false })
  const [reload, setReload] = useState(0)
  const seq = useRef(0)
  const onUnsupportedRef = useRef(onUnsupported)
  onUnsupportedRef.current = onUnsupported

  useEffect(() => { const h = setTimeout(() => { setQd(q); setTagD(tag) }, 300); return () => clearTimeout(h) }, [q, tag])
  useEffect(() => { setPage(0) }, [scope, qd, tagD])

  useEffect(() => {
    if (!open) return
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true, error: null }))
    api.admin.sqlSavedList(savedQuery({ scope, q: qd, tag: tagD, page })).then((r) => {
      if (my !== seq.current) return
      if (r?.success && Array.isArray(r.data)) {
        setState({ loading: false, error: null, rows: r.data, total: Number.isFinite(Number(r.total)) ? Number(r.total) : r.data.length, unsupported: false })
      } else if (r?.status === 404 || r?.status === 405) {
        setState({ loading: false, error: null, rows: [], total: 0, unsupported: true })
        onUnsupportedRef.current?.()
      } else {
        setState((s) => ({ ...s, loading: false, error: r?.error || t('sql.saved.loadError') }))
      }
    }).catch((e) => {
      if (my !== seq.current) return
      setState((s) => ({ ...s, loading: false, error: e?.message || t('sql.saved.loadError') }))
    })
  }, [open, scope, qd, tagD, page, reload, reloadKey, t])

  const remove = async (row) => {
    const ok = await showConfirm({ title: t('sql.saved.deleteTitle'), message: t('sql.saved.deleteMsg', row.title), confirmText: t('sql.saved.delete') })
    if (!ok) return
    const r = await api.admin.sqlSavedDelete(row.id)
    if (r?.success) { toast.success(t('sql.saved.deleted')); setReload((n) => n + 1) } else toast.error(r?.error || t('sql.saved.deleteError'))
  }
  const copyLink = async (row) => {
    if (await copyText(savedLink(row.id))) toast.success(t('sql.saved.linkCopied'))
    else toast.error(t('sql.res.copyFailed'))
  }

  const pageCount = Math.max(1, Math.ceil(state.total / SAVED_PAGE_SIZE))
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full gap-0 p-0 sm:max-w-xl" data-slot="sql-saved-panel">
        <SheetHeader className="border-b pr-12">
          <SheetTitle className="flex items-center gap-2"><BookMarked aria-hidden="true" className="size-4" /> {t('sql.saved.title')}</SheetTitle>
          <SheetDescription className="text-xs">{t('sql.saved.desc')}</SheetDescription>
        </SheetHeader>
        {!state.unsupported && (
          <div className="flex flex-col gap-2 border-b p-3">
            <ChoiceToggle ariaLabel={t('sql.saved.scope')} value={scope} onChange={setScope} options={[
              { value: 'mine', label: t('sql.saved.scopeMine') }, { value: 'shared', label: t('sql.saved.scopeShared') },
              { value: 'all', label: t('sql.saved.scopeAll') },
            ]} />
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,1fr)_10rem]">
              <InputGroup className="h-10 sm:h-9 pointer-coarse:h-10">
                <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('sql.saved.search')} aria-label={t('sql.saved.search')} />
                <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
                {q && (
                  <InputGroupAddon align="inline-end">
                    <InputGroupButton size="icon-xs" aria-label={t('sql.ex.clearSearch')} onClick={() => setQ('')}><X /></InputGroupButton>
                  </InputGroupAddon>
                )}
              </InputGroup>
              <Input value={tag} onChange={(e) => setTag(e.target.value)} placeholder={t('sql.saved.tagFilter')} aria-label={t('sql.saved.tagFilter')} maxLength={30} />
            </div>
          </div>
        )}
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-3">
          {state.unsupported ? (
            <div data-slot="sql-saved-unsupported"><StatusBlock icon={BookMarked} title={t('sql.saved.unsupported')} className="py-6" /></div>
          ) : state.loading && !state.rows.length ? (
            <LoadingBlock label={t('sql.saved.loading')} size={18} />
          ) : state.error ? (
            <StatusBlock tone="danger" title={t('sql.saved.loadError')} description={state.error} className="py-6"
              actions={<Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => setReload((n) => n + 1)}>{t('sql.retry')}</Button>} />
          ) : !state.rows.length ? (
            <StatusBlock icon={BookMarked} title={scope === 'mine' && !qd && !tagD ? t('sql.saved.emptyMine') : t('sql.saved.empty')} className="py-6" />
          ) : (
            <ul data-slot="sql-saved-list" aria-label={t('sql.saved.listLabel')} className="flex flex-col gap-2" aria-busy={state.loading || undefined}>
              {state.rows.map((row) => {
                const when = row.updated_at ?? row.created_at
                return (
                  <li key={row.id} data-slot="sql-saved-item" data-id={row.id} className="flex min-w-0 flex-col gap-1.5 rounded-lg border p-2.5">
                    <div className="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-1">
                      <span className="min-w-0 font-semibold [overflow-wrap:anywhere]">{row.title}</span>
                      {row.shared && (
                        <Badge variant="outline" className="gap-1 font-normal"><Users aria-hidden="true" />{t('sql.saved.sharedBadge')}</Badge>
                      )}
                    </div>
                    {row.description && <p className="m-0 text-sm break-words whitespace-pre-wrap text-muted-foreground">{row.description}</p>}
                    {Array.isArray(row.tags) && row.tags.length > 0 && (
                      <span className="flex flex-wrap gap-1">
                        {row.tags.map((tg) => (
                          <Badge key={tg} variant="secondary" className="h-auto font-normal whitespace-normal [overflow-wrap:anywhere]">{tg}</Badge>
                        ))}
                      </span>
                    )}
                    <pre className="m-0 max-h-24 overflow-auto rounded-md bg-muted/40 p-2 font-mono text-[11px] break-words whitespace-pre-wrap">{row.sql_text}</pre>
                    <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
                      {row.mine ? t('sql.saved.byMe') : t('sql.saved.by', row.owner_name || '—')}
                      {when && <> · <span title={formatDateSec(when)}>{relativeFrom(when, locale) || formatDateSec(when)}</span></>}
                    </span>
                    <div className="flex flex-wrap gap-2">
                      <Button type="button" size="sm" className="pointer-coarse:h-10" onClick={() => onOpen?.(row)}><Plus /> {t('sql.saved.open')}</Button>
                      <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => copyLink(row)}><Link2 /> {t('sql.saved.copyLink')}</Button>
                      {row.can_edit && (
                        <>
                          <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => onEdit?.(row)}><Pencil /> {t('sql.saved.edit')}</Button>
                          <Button type="button" variant="outline" size="sm" className="text-destructive pointer-coarse:h-10" onClick={() => remove(row)}><Trash2 /> {t('sql.saved.delete')}</Button>
                        </>
                      )}
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </div>
        {!state.unsupported && state.total > SAVED_PAGE_SIZE && (
          <div className="flex items-center justify-between gap-2 border-t px-3 py-2 text-xs text-muted-foreground">
            <span className="tabular-nums" aria-live="polite">{t('sql.hist.range', (page * SAVED_PAGE_SIZE + 1).toLocaleString(locale), Math.min(state.total, (page + 1) * SAVED_PAGE_SIZE).toLocaleString(locale), state.total.toLocaleString(locale))}</span>
            <span className="flex gap-1">
              <Button type="button" variant="outline" size="icon" disabled={page <= 0 || state.loading} onClick={() => setPage((p) => p - 1)} aria-label={t('sql.hist.prev')}><ChevronLeft /></Button>
              <Button type="button" variant="outline" size="icon" disabled={page + 1 >= pageCount || state.loading} onClick={() => setPage((p) => p + 1)} aria-label={t('sql.hist.next')}><ChevronRight /></Button>
            </span>
          </div>
        )}
      </SheetContent>
    </Sheet>
  )
}
