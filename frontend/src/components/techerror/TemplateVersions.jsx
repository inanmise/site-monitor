import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowLeft, CircleAlert, History, Upload, X } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import { cn } from '@/lib/utils'
import { fmtDateTime, periodLabel } from './TechErrorParts.jsx'
import { allFields, allSlots, formatPercent, hostOf, resolvePlaceholders, templateDiff } from './techErrorModel.js'

/**
 * ŞABLON SÜRÜM GEÇMİŞİ (2026-10-14, Faz B). Her şablon kaydı bir sürüm; seçilen sürümün kaydı (kim / ne zaman), önceki
 * KAYITLI sürüme göre farkı ve bölüm yapısı gösterilir. "Düzenleyiciye yükle" taslağı o sürümle değiştirir — sunucuya
 * hiçbir şey yazılmaz; kaydedince YENİ sürüm olur (geçmiş değişmez). Geniş ekranda liste + ayrıntı yan yana, dar
 * ekranda liste → ayrıntı (geri düğmeli).
 */
export default function TemplateVersionsDialog({ channelId, channelName, period, open, onClose, canEdit, onLoad, returnFocusRef }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const [list, setList] = useState({ loading: false, error: null, data: null })
  const [selected, setSelected] = useState(null)
  const [details, setDetails] = useState({})            // version → { loading, error, data }
  const [pane, setPane] = useState('list')              // dar ekran: list | detail
  const seq = useRef(0)
  const detailsRef = useRef(details)
  detailsRef.current = details
  // Dar ekranda liste ↔ ayrıntı geçişinde odak gizlenen bölmede kalmasın (inceleme 2026-10-14)
  const backRef = useRef(null)
  const itemRefs = useRef({})
  const paneMove = useRef(null)
  useEffect(() => {
    const to = paneMove.current
    paneMove.current = null
    if (to === 'detail' && backRef.current?.offsetParent) backRef.current.focus()
    else if (to === 'list') itemRefs.current[selected]?.focus()
  }, [pane, selected])

  const loadList = useCallback(async () => {
    const my = ++seq.current
    setList({ loading: true, error: null, data: null })
    detailsRef.current = {}
    setDetails({})
    try {
      const res = await api.techErrors.templateVersions(channelId)
      if (my !== seq.current) return
      if (res?.success && res.data) {
        setList({ loading: false, error: null, data: res.data })
        setSelected(res.data.versions?.[0]?.version ?? null)
      } else setList({ loading: false, error: res?.error || t('te.ver.loadError'), data: null })
    } catch (e) {
      if (my === seq.current) setList({ loading: false, error: e?.message || t('te.ver.loadError'), data: null })
    }
  }, [channelId, t])

  useEffect(() => {
    if (open) { setPane('list'); loadList() } else seq.current++
  }, [open, loadList])

  const fetchDetail = useCallback(async (version) => {
    if (version == null) return
    const cur = detailsRef.current[version]
    if (cur && (cur.loading || cur.data)) return
    const my = seq.current
    setDetails((d) => ({ ...d, [version]: { loading: true, error: null, data: null } }))
    try {
      const res = await api.techErrors.templateVersion(channelId, version)
      if (my !== seq.current) return
      setDetails((d) => ({ ...d, [version]: res?.success && res.data
        ? { loading: false, error: null, data: res.data }
        : { loading: false, error: res?.error || t('te.ver.loadError'), data: null } }))
    } catch (e) {
      if (my === seq.current) setDetails((d) => ({ ...d, [version]: { loading: false, error: e?.message || t('te.ver.loadError'), data: null } }))
    }
  }, [channelId, t])

  const versions = list.data?.versions || []
  // Sürümü bilinmeyen raporlar (D19) — "0 rapor" yazan satır ve ayrıntı bunu bilerek konuşur
  const unknownReports = Number(list.data?.unknown_version_reports) > 0 ? Number(list.data.unknown_version_reports) : 0
  const idx = versions.findIndex((v) => v.version === selected)
  const sel = idx >= 0 ? versions[idx] : null
  // Fark, bir önceki KAYITLI sürüme göre (aradaki sürümler budanmış olabilir)
  const prev = idx >= 0 ? versions.slice(idx + 1).find((v) => v.recorded !== false) : null
  useEffect(() => {
    if (!open || selected == null) return
    fetchDetail(selected)
    if (prev) fetchDetail(prev.version)
  }, [open, selected, prev, fetchDetail])

  // Sınıra ulaşıldıysa eski sürümler BUDANDI — "geçmiş bu özellikle başladı" denmez
  const maxVersions = list.data?.max_versions
  // Budama sınırın ÜSTÜNDE çalışır: tam sınırda ve listede sürüm 1 varsa hiçbir şey silinmemiştir (düzeltme incelemesi)
  const recordedVersions = versions.filter((v) => v.recorded !== false)
  const pruned = !!maxVersions && recordedVersions.length >= maxVersions
    && recordedVersions[recordedVersions.length - 1].version > 1
  const partial = !pruned && (versions.some((v) => v.recorded === false) || (versions.length > 0 && versions[versions.length - 1].version > 1))

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="flex max-h-[92dvh] w-[calc(100vw-1.5rem)] max-w-4xl flex-col gap-0 overflow-hidden p-0 sm:max-w-4xl"
        data-slot="te-versions" showCloseButton={false}
        onCloseAutoFocus={(e) => { if (returnFocusRef?.current) { e.preventDefault(); returnFocusRef.current.focus() } }}>
        {/* Yerleşik kapat düğmesi dokunmatikte 26 px: bu pencerede 40 px'lik shadcn düğmesi (proje kuralı ≥ 40 px) */}
        <DialogClose asChild>
          <Button type="button" variant="ghost" size="icon" className="absolute top-2.5 right-2.5 size-8 pointer-coarse:size-10"
            aria-label={t('app.close')}>
            <X aria-hidden="true" />
          </Button>
        </DialogClose>
        <DialogHeader className="border-b p-4 pe-14 text-left">
          <DialogTitle className="flex items-center gap-2"><History aria-hidden="true" className="size-4" /> {t('te.ver.title')}</DialogTitle>
          <DialogDescription>{t('te.ver.desc')}</DialogDescription>
        </DialogHeader>
        {list.loading && (
          <div className="flex flex-col gap-3 p-4" role="status" aria-live="polite">
            <span className="sr-only">{t('te.loading')}</span>
            <Skeleton className="h-16 w-full rounded-lg motion-reduce:animate-none" />
            <Skeleton className="h-40 w-full rounded-lg motion-reduce:animate-none" />
          </div>
        )}
        {list.error && (
          <div className="p-4">
            <StatusBlock tone="danger" icon={CircleAlert} title={t('te.ver.loadErrorTitle')} description={list.error}
              actions={<Button type="button" variant="outline" onClick={loadList}>{t('te.retry')}</Button>} />
          </div>
        )}
        {list.data && (
          <div className="grid min-h-0 flex-1 md:grid-cols-[17rem_minmax(0,1fr)]">
            <div className={cn('min-h-0 flex-col overflow-y-auto border-b p-3 md:flex md:border-e md:border-b-0', pane === 'detail' ? 'hidden' : 'flex')}
              data-slot="te-versions-list">
              {pruned && <p className="m-0 mb-2 text-xs text-muted-foreground" data-slot="te-versions-partial" data-pruned="true">
                {t('te.ver.pruned', maxVersions)}</p>}
              {partial && <p className="m-0 mb-2 text-xs text-muted-foreground" data-slot="te-versions-partial">{t('te.ver.partial')}</p>}
              {/* Raporların şablon sürümü (D19): sürümü bilinmeyen (bu özellikten önce açılmış) ve listede olmayan bir sürümle
                  açılmış raporlar ayrı söylenir — hiçbiri sessizce düşmez */}
              {list.data.unknown_version_reports > 0 && (
                <p className="m-0 mb-2 text-xs text-muted-foreground" data-slot="te-versions-unknown">
                  {t('te.ver.unknownReports', list.data.unknown_version_reports)}</p>
              )}
              {list.data.unlisted_version_reports > 0 && (
                <p className="m-0 mb-2 text-xs text-muted-foreground" data-slot="te-versions-unlisted">
                  {t('te.ver.unlistedReports', list.data.unlisted_version_reports)}</p>
              )}
              <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
                {versions.map((v) => (
                  <li key={v.version}>
                    <Button type="button" variant={v.version === selected ? 'secondary' : 'ghost'} aria-current={v.version === selected ? 'true' : undefined}
                      className="h-auto w-full flex-col items-start gap-0.5 whitespace-normal px-3 py-2 text-left pointer-coarse:min-h-10"
                      data-slot="te-version-item" data-version={v.version}
                      ref={(el) => { if (el) itemRefs.current[v.version] = el; else delete itemRefs.current[v.version] }}
                      onClick={() => { setSelected(v.version); paneMove.current = 'detail'; setPane('detail') }}>
                      <span className="flex flex-wrap items-center gap-1.5 font-semibold">
                        {t('te.ver.versionN', v.version)}
                        {v.current && <Badge className="rounded-md overflow-visible bg-primary/10 text-primary">{t('te.ver.current')}</Badge>}
                      </span>
                      <span className="text-xs font-normal text-muted-foreground [overflow-wrap:anywhere]">
                        {fmtDateTime(v.saved_at, uiLang)} · {v.saved_by_name || '—'}
                      </span>
                      {reportCountText(v, t, unknownReports) && (
                        <span className="text-xs font-normal text-muted-foreground" data-slot="te-version-report-count" data-count={v.report_count}>
                          {reportCountText(v, t, unknownReports)}
                        </span>
                      )}
                    </Button>
                  </li>
                ))}
              </ul>
            </div>
            <div className={cn('min-h-0 flex-col overflow-y-auto p-4 md:flex', pane === 'list' ? 'hidden' : 'flex')} data-slot="te-version-detail">
              {/* Dar ekranda geri düğmesi bölme kaydırılsa da görünür kalır (yapışkan) */}
              <div className="sticky top-0 z-10 -mx-4 -mt-4 mb-2 border-b bg-background px-4 py-1.5 md:hidden">
                <Button ref={backRef} type="button" variant="ghost" size="sm" className="w-fit pointer-coarse:min-h-10"
                  data-slot="te-versions-back" onClick={() => { paneMove.current = 'list'; setPane('list') }}>
                  <ArrowLeft aria-hidden="true" /> {t('te.ver.backToList')}
                </Button>
              </div>
              {sel && (
                <VersionDetail sel={sel} prev={prev} detail={details[sel.version]} prevDetail={prev ? details[prev.version] : null}
                  unknownReports={unknownReports}
                  channelName={channelName} period={period} canEdit={canEdit} uiLang={uiLang}
                  onRetry={() => { setDetails((d) => ({ ...d, [sel.version]: undefined })); fetchDetail(sel.version) }}
                  onLoad={async (tpl) => { if (await onLoad(tpl, sel.version)) onClose() }} />
              )}
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

function VersionDetail({ sel, prev, detail, prevDetail, channelName, period, canEdit, uiLang, onRetry, onLoad, unknownReports = 0 }) {
  const t = useT()
  if (!detail || detail.loading) {
    return (
      <div className="flex flex-col gap-3" role="status" aria-live="polite">
        <span className="sr-only">{t('te.loading')}</span>
        <Skeleton className="h-24 w-full rounded-lg motion-reduce:animate-none" />
      </div>
    )
  }
  if (detail.error) {
    return <StatusBlock tone="danger" icon={CircleAlert} title={t('te.ver.loadErrorTitle')} description={detail.error}
      actions={<Button type="button" variant="outline" onClick={onRetry}>{t('te.retry')}</Button>} />
  }
  const tpl = detail.data.template
  const resolve = (s) => resolvePlaceholders(s || '', channelName, period?.year, period?.month, tpl?.lang)
  const diff = prevDetail?.data ? templateDiff(prevDetail.data.template, tpl) : null
  const diffUnknown = !!prevDetail?.data && diff == null
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h3 className="m-0 flex flex-wrap items-center gap-2 text-lg font-semibold">
          {t('te.ver.versionN', sel.version)}
          {sel.current && <Badge className="rounded-md overflow-visible bg-primary/10 text-primary">{t('te.ver.current')}</Badge>}
        </h3>
        <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">
          {t('te.ver.savedBy', fmtDateTime(sel.saved_at, uiLang), sel.saved_by_name || '—')}
        </p>
        <p className="m-0 text-sm text-muted-foreground">
          {t('te.ver.counts', sel.section_count, sel.field_count, sel.slot_count)}
        </p>
        {sourceText(detail.data, sel, t) && (
          <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-version-source">
            {sourceText(detail.data, sel, t)}
          </p>
        )}
      </div>

      {/* Bu sürümle açılmış / güncellenmiş raporlar (D19) — sayı tek gruplu sorgudan, dönemler en çok 24 (en yeni önce) */}
      {(detail.data.report_count != null || sel.report_count != null) && (
        <section className="flex flex-col gap-1.5" aria-labelledby="te-ver-reports" data-slot="te-version-reports">
          <h4 id="te-ver-reports" className="m-0 text-sm font-semibold">
            {reportCountText({ report_count: detail.data.report_count ?? sel.report_count }, t, unknownReports)}
          </h4>
          {/* Sürümü bilinmeyen raporlar varken "rapor yok" kesin değildir — söylenir */}
          {unknownReports > 0 && (
            <p className="m-0 text-xs text-muted-foreground" data-slot="te-version-reports-unknown">
              {t('te.ver.unknownReports', unknownReports)}</p>
          )}
          {Array.isArray(detail.data.reports) && detail.data.reports.length > 0 && (
            <ul className="m-0 flex list-none flex-col gap-1 p-0">
              {detail.data.reports.map((r) => (
                <li key={r.id} className="rounded-md bg-muted/40 px-2.5 py-1.5 text-sm [overflow-wrap:anywhere]" data-slot="te-version-report">
                  <span className="font-medium">{periodLabel(r, t)}</span>
                  {r.title ? <span className="text-muted-foreground"> · {r.title}</span> : null}
                </li>
              ))}
            </ul>
          )}
          {Array.isArray(detail.data.reports) && (detail.data.report_count ?? 0) > detail.data.reports.length && (
            <p className="m-0 text-xs text-muted-foreground" data-slot="te-version-reports-more">
              {t('te.ver.reportsMore', detail.data.reports.length)}</p>
          )}
        </section>
      )}

      {canEdit && !sel.current && (
        <div className="flex flex-col gap-1.5 rounded-lg border bg-muted/30 p-3">
          <p className="m-0 text-sm">{t('te.ver.loadHint')}</p>
          <Button type="button" className="w-fit pointer-coarse:min-h-10" onClick={() => onLoad(tpl)} data-slot="te-version-load">
            <Upload aria-hidden="true" /> {t('te.ver.load')}
          </Button>
        </div>
      )}

      <section className="flex flex-col gap-2" aria-labelledby="te-ver-diff">
        <h4 id="te-ver-diff" className="m-0 text-sm font-semibold">
          {prev ? t('te.ver.diffTitle', prev.version, sel.version) : t('te.ver.diffTitleFirst')}
        </h4>
        {/* Kayıtlı olmayan (kanalın kendisinden okunan) sürüm "ilk kayıtlı sürüm" değildir */}
        {!prev && <p className="m-0 text-sm text-muted-foreground" data-slot="te-version-nodiff">
          {t(sel.recorded === false ? 'te.ver.notRecorded' : 'te.ver.firstRecorded')}</p>}
        {diffUnknown && <p className="m-0 text-sm text-destructive" role="alert">{t('te.ver.diffUnknown')}</p>}
        {prev && prevDetail?.loading && <Skeleton className="h-12 w-full rounded-lg motion-reduce:animate-none" />}
        {prev && prevDetail?.error && <p className="m-0 text-sm text-destructive" role="alert">{t('te.ver.diffUnavailable')}</p>}
        {diff && diff.length === 0 && <p className="m-0 text-sm text-muted-foreground">{t('te.ver.noStructuralChange')}</p>}
        {diff && diff.length > 0 && (
          <ul className="m-0 flex list-none flex-col gap-1.5 p-0" data-slot="te-version-diff">
            {diff.map((d, i) => (
              <li key={i} className="rounded-md bg-muted/40 px-2.5 py-1.5 text-sm [overflow-wrap:anywhere]" data-group={d.group} data-op={d.op}>
                {diffText(d, t, resolve, uiLang)}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="flex flex-col gap-2" aria-labelledby="te-ver-outline">
        <h4 id="te-ver-outline" className="m-0 text-sm font-semibold">{t('te.ver.outline')}</h4>
        <ol className="m-0 flex list-decimal flex-col gap-1 ps-5 text-sm" data-slot="te-version-outline">
          {(tpl?.sections || []).map((s) => (
            <li key={s.id} className="[overflow-wrap:anywhere]">
              <span className="font-medium">{resolve(s.title)}</span>{' '}
              <span className="text-muted-foreground">· {t(`te.kind.${s.kind}`)}{outlineCount(s, t)}{hostOf(tpl, s) ? ` · ${t('te.tpl.hostBadge')}` : ''}</span>
            </li>
          ))}
        </ol>
        <p className="m-0 text-xs text-muted-foreground">
          {t('te.ver.totals', allFields(tpl).length, allSlots(tpl).length)}
        </p>
      </section>
    </div>
  )
}

/**
 * "Bu sürümle N rapor" (D19). Sayı yoksa (eski sunucu) null — çizilmez; 0 → "Bu sürümle rapor yok". Hiçbir zaman "v0" /
 * "vnull" yazılmaz (sürüm numarası zaten satırın başlığında).
 */
export function reportCountText(v, t, unknownReports = 0) {
  const n = v?.report_count
  if (n == null || !Number.isFinite(Number(n))) return null
  // Sürümü bilinmeyen raporlar varken 0 "rapor yok" değil "bilinen rapor yok" (inceleme 2026-10-18: bilinmeyen ≠ 0)
  if (Number(n) === 0) return unknownReports > 0 ? t('te.ver.reportsNoneKnown') : t('te.ver.reportsNone')
  return t('te.ver.reportsN', Number(n))
}

/** Kanalın ilk sürümünün kaynağı (D17): takım kütüphanesi (+ kaydın adı), başka kanalın kopyası ya da sistem varsayılanı. */
export function sourceText(detail, sel, t) {
  const kind = detail?.source_kind ?? sel?.source_kind
  const name = detail?.source_name ?? sel?.source_name
  if (kind === 'library') return t('te.ver.sourceLibrary', name || '—')
  if (kind === 'copy') return t('te.ver.sourceCopy')
  if (kind === 'default') return t('te.ver.sourceDefault')
  return null
}

function outlineCount(s, t) {
  if (s.kind === 'metrics') return ` · ${t('te.ver.nFields', (s.fields || []).length)}`
  if (s.kind === 'images') return ` · ${t('te.ver.nSlots', (s.slots || []).length)}`
  if (s.kind === 'summary') return ` · ${t('te.ver.nLines', (s.lines || []).length)}`
  if (s.kind === 'table') return ` · ${t('te.ver.nTable', (s.columns || []).length, (s.rows || []).length)}`
  if (s.kind === 'trend') return ` · ${t('te.ver.nCharts', (s.series || []).length)}`
  return ''
}

const langName = (l, t) => (l === 'en' ? t('te.tpl.langEn') : t('te.tpl.langTr'))

/** Fark satırının okunur metni (etiketler bu sürümün diliyle çözülür). */
export function diffText(d, t, resolve, lang = 'tr') {
  const r = (s) => resolve(s || '')
  const typeName = (x) => (x ? t(`te.type.${x}`) : '—')
  const pct = (x) => (x == null ? '—' : formatPercent(x, lang))
  const betterName = (x) => (x ? t(`te.tpl.better.${x}`) : t('te.tpl.betterDefault'))
  switch (`${d.group}.${d.op}`) {
    // Hedef, "daha iyi" yönü ve renkli kutucuklar (2026-10-17, 4b)
    case 'field.target': return t('te.ver.d.field.target', r(d.label), pct(d.before), pct(d.after), r(d.section))
    case 'field.better': return t('te.ver.d.field.better', r(d.label), betterName(d.before), betterName(d.after), r(d.section))
    case 'summary.tiles': return t('te.ver.d.summary.tiles', r(d.label), d.before, d.after)
    case 'report.title': return t('te.ver.d.report.title', r(d.before), r(d.after))
    case 'report.lang': return t('te.ver.d.report.lang', langName(d.before, t), langName(d.after, t))
    case 'section.add': return t('te.ver.d.section.add', r(d.label), t(`te.kind.${d.kind}`))
    case 'section.remove': return t('te.ver.d.section.remove', r(d.label), t(`te.kind.${d.kind}`))
    case 'section.rename': return t('te.ver.d.section.rename', r(d.before), r(d.after))
    case 'section.reorder': return t('te.ver.d.section.reorder')
    case 'section.retype': return t('te.ver.d.section.retype', r(d.label), t(`te.kind.${d.before}`), t(`te.kind.${d.after}`))
    case 'section.labels': return t('te.ver.d.section.labels', r(d.label))
    case 'section.host': {
      // Gösterim yeri (2026-10-17): ayrı bölüm (null) ↔ özet kartı (özetin başlığı; okunamıyorsa "—")
      const where = (x) => (x == null ? t('te.tpl.hostSeparate') : t('te.tpl.hostIn', r(x) || '—'))
      return `${r(d.label)} — ${t('te.ver.host')}: ${where(d.before)} → ${where(d.after)}`
    }
    case 'summary.change': return t('te.ver.d.summary.change', r(d.label), d.before, d.after)
    case 'text.change': return t('te.ver.d.text.change', r(d.label))
    case 'trend.change': return t('te.ver.d.trend.change', r(d.label), d.before, d.after)
    case 'field.retype': case 'column.retype': return t(`te.ver.d.${d.group}.retype`, r(d.label), typeName(d.before), typeName(d.after))
    case 'slot.resize': return t('te.ver.d.slot.resize', r(d.label), t(`te.width.${d.before}`), t(`te.width.${d.after}`))
    case 'field.move': case 'slot.move': return t(`te.ver.d.${d.group}.move`, r(d.label), r(d.section), r(d.to))
    case 'field.reorder': case 'slot.reorder': case 'column.reorder': case 'row.reorder':
      return t(`te.ver.d.${d.group}.reorder`, r(d.section))
    default:
      if (d.op === 'rename') return t(`te.ver.d.${d.group}.rename`, r(d.before), r(d.after), r(d.section))
      return t(`te.ver.d.${d.group}.${d.op}`, r(d.label), r(d.section))
  }
}
