import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, FileClock, History, RotateCcw, Upload } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { diffTemplates } from './routineTemplateDiff.js'
import { SAVE_SOURCES, frequencyLabel, istanbulStamp, windowLabel } from './templateEditorModel.js'
import { thrownResult } from './routineModel.js'

/** Bilinen kayıt kaynağı mı? Bilinmeyen kod ham anahtar olarak gösterilmesin. */
const knownSource = (s) => SAVE_SOURCES.includes(s)

/**
 * Nöbet rutini ŞABLON SÜRÜMLERİ penceresi (2026-11-26). Liste en yeniden eskiye; bir sürüm seçilince o sürüm ve bir
 * önceki okunur, FARK gösterilir (bölüm / görev eklendi, silindi, adı değişti, taşındı, sıklık, zorunlu, etkin, açıklama,
 * saat aralığı, bağlantılar — yalnız ETİKET + ANA MAKİNE —, canlı sayaçlar). "Taslağa yükle" sürümü düzenleyiciye
 * KAYDEDİLMEMİŞ taslak olarak koyar (taban = şu anki sürüm; eşzamanlı kayıt yine 409 alır). Kap ≥ 640 px iki bölmeli,
 * dar ekranda liste → ayrıntı.
 *
 * @param {{open: boolean, onClose: () => void, teamId: number|string, currentVersion: number|null,
 *          onLoadVersion: (template: object, version: number) => void, canLoad?: boolean}} props
 */
const TWO_PANE_MIN_PX = 640
const LIST_LIMIT = 200

function stampText(iso) {
  const s = istanbulStamp(iso)
  return s ? `${s.date} ${s.hm}` : ''
}

function DiffLine({ kind, children }) {
  const t = useT()
  const tone = kind === 'added' ? 'secondary' : kind === 'removed' ? 'destructive' : 'outline'
  return (
    <li data-slot="or-tpl-ver-change" data-kind={kind} className="flex flex-wrap items-start gap-2 py-1.5">
      <Badge variant={tone} className="h-auto shrink-0 whitespace-normal rounded-md overflow-visible">{t(`or.tpl.ver.kind.${kind}`)}</Badge>
      <span className="min-w-0 flex-1 text-sm [overflow-wrap:anywhere]">{children}</span>
    </li>
  )
}

/** Farkı okunur satırlara çevirir. */
function DiffView({ diff }) {
  const t = useT()
  const w = (x) => windowLabel(t, x?.from, x?.to) || t('or.tpl.noWindow')
  const sec = diff.sections
  const tk = diff.tasks
  const sectionRows = [
    ...sec.added.map((x) => <DiffLine key={`sa-${x.id}`} kind="added">{t('or.tpl.ver.sectionAdded', x.title)}</DiffLine>),
    ...sec.removed.map((x) => <DiffLine key={`sr-${x.id}`} kind="removed">{t('or.tpl.ver.sectionRemoved', x.title)}</DiffLine>),
    ...sec.renamed.map((x) => <DiffLine key={`sn-${x.id}`} kind="changed">{t('or.tpl.ver.sectionRenamed', x.from, x.to)}</DiffLine>),
    ...sec.moved.map((x) => <DiffLine key={`sm-${x.id}`} kind="moved">{t('or.tpl.ver.sectionMoved', x.title)}</DiffLine>),
    ...sec.window.map((x) => <DiffLine key={`sw-${x.id}`} kind="changed">{t('or.tpl.ver.sectionWindow', x.title, w(x.from), w(x.to))}</DiffLine>),
  ]
  const taskRows = [
    ...tk.added.map((x) => <DiffLine key={`ta-${x.id}`} kind="added">{t('or.tpl.ver.taskAdded', x.title, x.section)}</DiffLine>),
    ...tk.removed.map((x) => <DiffLine key={`tr-${x.id}`} kind="removed">{t('or.tpl.ver.taskRemoved', x.title, x.section)}</DiffLine>),
    ...tk.renamed.map((x) => <DiffLine key={`tn-${x.id}`} kind="changed">{t('or.tpl.ver.taskRenamed', x.from, x.to)}</DiffLine>),
    ...tk.moved.map((x) => (
      <DiffLine key={`tm-${x.id}`} kind="moved">
        {x.from_section === x.to_section ? t('or.tpl.ver.taskReordered', x.title, x.to_section) : t('or.tpl.ver.taskMoved', x.title, x.from_section, x.to_section)}
      </DiffLine>
    )),
    ...tk.frequency.map((x) => (
      <DiffLine key={`tf-${x.id}`} kind="changed">{t('or.tpl.ver.taskFrequency', x.title, frequencyLabel(t, x.from), frequencyLabel(t, x.to))}</DiffLine>
    )),
    ...tk.required.map((x) => (
      <DiffLine key={`tq-${x.id}`} kind="changed">{t(x.to ? 'or.tpl.ver.taskRequiredOn' : 'or.tpl.ver.taskRequiredOff', x.title)}</DiffLine>
    )),
    ...tk.active.map((x) => (
      <DiffLine key={`tc-${x.id}`} kind="changed">{t(x.to ? 'or.tpl.ver.taskActiveOn' : 'or.tpl.ver.taskActiveOff', x.title)}</DiffLine>
    )),
    ...tk.description.map((x) => <DiffLine key={`td-${x.id}`} kind="changed">{t('or.tpl.ver.taskDescription', x.title)}</DiffLine>),
    ...tk.window.map((x) => <DiffLine key={`tw-${x.id}`} kind="changed">{t('or.tpl.ver.taskWindow', x.title, w(x.from), w(x.to))}</DiffLine>),
    ...tk.links.map((x) => {
      const link = (l) => (l.host ? t('or.tpl.ver.linkWithHost', l.label || l.host, l.host) : (l.label || t('or.tpl.ver.linkUnnamed')))
      const parts = [
        ...x.added.map((l) => `+ ${link(l)}`),
        ...x.removed.map((l) => `− ${link(l)}`),
        ...x.relabeled.map((l) => t('or.tpl.ver.linkRelabeled', l.from || l.host, l.to || l.host)),
      ]
      return <DiffLine key={`tl-${x.id}`} kind="changed">{t('or.tpl.ver.taskLinks', x.title, parts.join(' · '))}</DiffLine>
    }),
    ...tk.signals.map((x) => {
      const parts = [
        ...x.added.map((k) => `+ ${t(`or.sig.${k}.label`)}`),
        ...x.removed.map((k) => `− ${t(`or.sig.${k}.label`)}`),
      ]
      return <DiffLine key={`ts-${x.id}`} kind="changed">{t('or.tpl.ver.taskSignals', x.title, parts.join(' · '))}</DiffLine>
    }),
  ]
  if (!sectionRows.length && !taskRows.length) {
    return <p data-slot="or-tpl-ver-nodiff" className="text-sm text-muted-foreground">{t('or.tpl.ver.noDiff')}</p>
  }
  return (
    <div data-slot="or-tpl-ver-diff" className="flex flex-col gap-3">
      {sectionRows.length > 0 && (
        <div>
          <h4 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t('or.tpl.ver.sections')}</h4>
          <ul className="divide-y divide-border">{sectionRows}</ul>
        </div>
      )}
      {taskRows.length > 0 && (
        <div>
          <h4 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t('or.tpl.ver.tasks')}</h4>
          <ul className="divide-y divide-border">{taskRows}</ul>
        </div>
      )}
    </div>
  )
}

export default function TemplateVersionsDialog({ open, onClose, teamId, currentVersion, onLoadVersion, canLoad = true }) {
  const t = useT()
  const [list, setList] = useState({ loading: false, error: null, info: null, items: null })
  const [selected, setSelected] = useState(null)
  const [details, setDetails] = useState(() => new Map())
  const [mode, setMode] = useState('list')
  const listSeq = useRef(0)
  const listCtrl = useRef(null)
  const pending = useRef(new Map())
  const [paneRef, width] = useElementWidth()
  const wide = width === 0 || width >= TWO_PANE_MIN_PX
  const backRef = useRef(null)
  const itemRefs = useRef(new Map())

  const loadList = useCallback(async () => {
    const my = ++listSeq.current
    listCtrl.current?.abort()
    const c = typeof AbortController !== 'undefined' ? new AbortController() : null
    listCtrl.current = c
    setList((l) => ({ ...l, loading: true, error: null, info: null }))
    let res
    try {
      res = await api.oncallRoutines.templateVersions(teamId, c ? { signal: c.signal } : undefined)
    } catch (e) {
      if (my !== listSeq.current) return
      setList({ loading: false, error: thrownResult(e).error || t('or.tpl.ver.loadFailed'), info: errorInfoOf(e), items: null })
      return
    }
    if (my !== listSeq.current || res === null) return
    if (!res?.success) {
      setList({ loading: false, error: res?.error || t('or.tpl.ver.loadFailed'), info: errorInfoOf(res), items: null })
      return
    }
    const d = res.data
    const raw = Array.isArray(d) ? d : Array.isArray(d?.versions) ? d.versions : Array.isArray(d?.items) ? d.items : []
    const items = raw.filter((x) => x && Number.isInteger(x.version)).sort((a, b) => b.version - a.version)
    setList({ loading: false, error: null, info: null, items })
    setSelected((s) => (s != null && items.some((x) => x.version === s) ? s : items[0]?.version ?? null))
  }, [teamId, t])

  useEffect(() => {
    if (!open) return undefined
    setDetails(new Map())
    pending.current = new Map()
    setMode('list')
    setSelected(null)
    loadList()
    return () => { listSeq.current += 1; listCtrl.current?.abort(); for (const c of pending.current.values()) c.abort?.() }
  }, [open, loadList])

  const fetchDetail = useCallback(async (version) => {
    if (version == null || pending.current.has(version)) return
    const c = typeof AbortController !== 'undefined' ? new AbortController() : { abort() {} }
    pending.current.set(version, c)
    setDetails((m) => new Map(m).set(version, { status: 'loading' }))
    let res
    try {
      res = await api.oncallRoutines.templateVersion(teamId, version, c.signal ? { signal: c.signal } : undefined)
    } catch (e) {
      if (pending.current.get(version) !== c) return
      pending.current.delete(version)
      setDetails((m) => new Map(m).set(version, { status: 'error', error: thrownResult(e).error || t('or.tpl.ver.detailFailed') }))
      return
    }
    if (pending.current.get(version) !== c) return
    pending.current.delete(version)
    if (res === null) return
    if (res?.success && res.data?.template) {
      setDetails((m) => new Map(m).set(version, { status: 'ok', data: res.data }))
    } else {
      const notFound = errorInfoOf(res)?.status === 404
      setDetails((m) => new Map(m).set(version, {
        status: 'error', notFound, error: res?.error || t(notFound ? 'or.tpl.ver.notFound' : 'or.tpl.ver.detailFailed'),
      }))
    }
  }, [teamId, t])

  const items = useMemo(() => list.items || [], [list.items])
  const index = items.findIndex((x) => x.version === selected)
  const prevItem = index >= 0 ? items[index + 1] || null : null
  const prevUnknown = index >= 0 && !prevItem && items.length >= LIST_LIMIT
  const prevVersion = prevItem?.version ?? null

  useEffect(() => {
    if (!open || selected == null) return
    if (!details.has(selected)) fetchDetail(selected)
    if (prevVersion != null && !details.has(prevVersion)) fetchDetail(prevVersion)
  }, [open, selected, prevVersion, details, fetchDetail])

  const sel = selected != null ? details.get(selected) : null
  const prev = prevVersion != null ? details.get(prevVersion) : null
  const diff = useMemo(() => {
    if (sel?.status !== 'ok') return null
    if (prevVersion != null && prev?.status !== 'ok') return null
    if (prevUnknown) return null
    return diffTemplates(prevVersion != null ? prev.data.template : null, sel.data.template)
  }, [sel, prev, prevVersion, prevUnknown])

  const choose = (v) => {
    setSelected(v)
    if (!wide) {
      setMode('detail')
      setTimeout(() => backRef.current?.focus?.(), 0)
    }
  }
  const backToList = () => {
    setMode('list')
    setTimeout(() => itemRefs.current.get(selected)?.focus?.(), 0)
  }
  const retryDetail = () => {
    setDetails((m) => {
      const n = new Map(m)
      if (n.get(selected)?.status === 'error') n.delete(selected)
      if (prevVersion != null && n.get(prevVersion)?.status === 'error') n.delete(prevVersion)
      return n
    })
  }

  const selItem = index >= 0 ? items[index] : null
  const isCurrent = selItem && currentVersion != null && selItem.version === currentVersion

  const listPane = (
    <div data-slot="or-tpl-ver-list" className={cn('flex min-w-0 flex-col gap-1', wide && 'max-h-[60vh] overflow-y-auto pe-1')}>
      {items.map((v) => {
        const on = v.version === selected
        const sum = v.summary || {}
        const by = v.saved_by_name || t('or.tpl.ver.unknownUser')
        return (
          <Button key={v.version} type="button" variant="ghost" aria-pressed={on} data-slot="or-tpl-ver-item" data-version={v.version}
            ref={(el) => { if (el) itemRefs.current.set(v.version, el); else itemRefs.current.delete(v.version) }}
            onClick={() => choose(v.version)}
            className={cn('h-auto min-h-10 w-full flex-col items-start gap-0.5 whitespace-normal rounded-lg border px-3 py-2 text-left',
              on ? 'border-primary bg-primary/5' : 'border-transparent')}>
            <span className="flex w-full flex-wrap items-center gap-1.5 text-sm font-semibold">
              {t('or.tpl.versionN', v.version)}
              {currentVersion != null && v.version === currentVersion && (
                <Badge variant="secondary" className="h-auto whitespace-normal rounded-md overflow-visible">{t('or.tpl.ver.current')}</Badge>
              )}
              {knownSource(v.source) && v.source !== 'EDITOR' && (
                <Badge variant="outline" className="h-auto whitespace-normal rounded-md overflow-visible">{t(`or.tpl.src.${v.source}`)}</Badge>
              )}
            </span>
            <span className="text-xs font-normal text-muted-foreground [overflow-wrap:anywhere]">
              {[stampText(v.saved_at), by].filter(Boolean).join(' · ')}
            </span>
            {Number.isInteger(sum.tasks) && (
              <span className="text-xs font-normal tabular-nums text-muted-foreground">
                {t('or.tpl.ver.summaryLine', sum.sections ?? 0, sum.tasks ?? 0, sum.added ?? 0, sum.removed ?? 0, sum.changed ?? 0)}
              </span>
            )}
          </Button>
        )
      })}
    </div>
  )

  const detailPane = (
    <div data-slot="or-tpl-ver-detail" className="flex min-w-0 flex-col gap-3">
      {!wide && (
        <div>
          <Button ref={backRef} type="button" variant="ghost" size="sm" className="-ms-2 pointer-coarse:h-10" data-slot="or-tpl-ver-back"
            onClick={backToList}>
            <ArrowLeft aria-hidden="true" /> {t('or.tpl.ver.backToList')}
          </Button>
        </div>
      )}
      {selItem ? (
        <>
          <div className="flex flex-col gap-1">
            <h3 className="text-sm font-semibold">
              {prevItem ? t('or.tpl.ver.compareTitle', selItem.version, prevItem.version) : t('or.tpl.versionN', selItem.version)}
            </h3>
            <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
              {[stampText(selItem.saved_at), selItem.saved_by_name || t('or.tpl.ver.unknownUser'), knownSource(selItem.source) ? t(`or.tpl.src.${selItem.source}`) : null]
                .filter(Boolean).join(' · ')}
            </p>
            {!prevItem && !prevUnknown && <p className="text-xs text-muted-foreground">{t('or.tpl.ver.firstVersion')}</p>}
          </div>
          {(sel?.status === 'error' || prev?.status === 'error') ? (
            <StatusBlock tone="danger" title={t('or.tpl.ver.detailFailedTitle')}
              description={sel?.status === 'error' ? sel.error : prev.error}
              actions={<Button type="button" onClick={retryDetail} className="pointer-coarse:h-10"><RotateCcw aria-hidden="true" /> {t('or.tpl.retry')}</Button>} />
          ) : prevUnknown ? (
            <p data-slot="or-tpl-ver-prev-unknown" className="text-sm text-muted-foreground">{t('or.tpl.ver.prevNotListed')}</p>
          ) : diff ? (
            <DiffView diff={diff} />
          ) : (
            <div className="flex flex-col gap-2" aria-busy="true" data-slot="or-tpl-ver-detail-loading">
              <Skeleton className="h-5 w-3/4" />
              <Skeleton className="h-5 w-2/3" />
              <Skeleton className="h-5 w-1/2" />
            </div>
          )}
          {canLoad && (
            <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3">
              <Button type="button" data-slot="or-tpl-ver-load" className="pointer-coarse:h-10"
                disabled={isCurrent || sel?.status !== 'ok'}
                onClick={() => sel?.status === 'ok' && onLoadVersion(sel.data.template, selItem.version)}>
                <Upload aria-hidden="true" /> {t('or.tpl.ver.load')}
              </Button>
              <span className="text-xs text-muted-foreground">{isCurrent ? t('or.tpl.ver.isCurrent') : t('or.tpl.ver.loadHint')}</span>
            </div>
          )}
        </>
      ) : null}
    </div>
  )

  let body
  if (list.loading && !list.items) {
    body = (
      <div className="flex flex-col gap-2" aria-busy="true" data-slot="or-tpl-ver-loading">
        <Skeleton className="h-14 w-full" />
        <Skeleton className="h-14 w-full" />
        <Skeleton className="h-14 w-full" />
      </div>
    )
  } else if (list.error) {
    body = (
      <StatusBlock tone="danger" title={t('or.tpl.ver.loadFailedTitle')} description={list.error} details={list.info || undefined}
        actions={<Button type="button" onClick={loadList} className="pointer-coarse:h-10"><RotateCcw aria-hidden="true" /> {t('or.tpl.retry')}</Button>} />
    )
  } else if (!items.length) {
    body = <StatusBlock icon={FileClock} title={t('or.tpl.ver.emptyTitle')} description={t('or.tpl.ver.emptyBody')} />
  } else if (wide) {
    body = (
      <div className="grid grid-cols-[minmax(0,15rem)_minmax(0,1fr)] gap-4">
        {listPane}
        {detailPane}
      </div>
    )
  } else {
    body = mode === 'detail' ? detailPane : listPane
  }

  return (
    <ModalShell open={open} onClose={onClose} title={t('or.tpl.ver.title')} icon={History} size="xl" scrollBody
      className={PHONE_FULLSCREEN}
      footer={<Button type="button" variant="outline" onClick={onClose} className="pointer-coarse:h-10">{t('app.close')}</Button>}>
      <div ref={paneRef} data-slot="or-tpl-ver-dialog" data-layout={wide ? 'split' : mode} className="min-w-0">
        {body}
      </div>
    </ModalShell>
  )
}
