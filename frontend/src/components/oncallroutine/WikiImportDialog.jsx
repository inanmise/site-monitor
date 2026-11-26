import { useEffect, useMemo, useRef, useState } from 'react'
import { ClipboardPaste, FileInput, Import } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import { WIKI_MAX_INPUT, parseWikiImport } from './wikiImport.js'
import {
  FREQUENCIES, WEEKDAYS, applyImport, collectIds, countTasks, frequencyLabel, importLimitProblems, resolveLimits, withFrequency,
} from './templateEditorModel.js'

/**
 * WİKİDEN İÇE AKTARMA penceresi (2026-11-26). Kullanıcı wiki sayfasını (Confluence / SharePoint / markdown) kopyalayıp
 * yapıştırır; tarayıcı önce `text/html`, yoksa `text/plain` okunur ve `wikiImport.parseWikiImport` canlı (300 ms sonra)
 * önizleme üretir: başlıklar = bölümler, madde işaretleri = görevler, girintili bağlantılar = görev bağlantıları. Önizlemede
 * bölüm adları düzenlenir, bölüm / görev seçimi kaldırılabilir, görevin sıklığı değiştirilir. Mevcut taslakta görev varsa
 * "Ekle" (aynı adlı bölüme ekler) ya da "Değiştir" (taslağı siler — silinecek görev sayısıyla sorulur). Birleşik şablon
 * sınırları aşarsa içe aktarma kilitlenir ve neden yazılır. Hiçbir şey sunucuya gitmez; sonuç düzenleyicide KAYDEDİLMEMİŞ
 * taslaktır.
 *
 * @param {{open: boolean, onClose: () => void, draftSections?: object[], lang?: 'tr'|'en', limits?: object,
 *          onImport: (r: {mode: 'append'|'replace', sections: object[]}) => void}} props
 */
const PARSE_DELAY_MS = 300

function rowsFrom(sections) {
  return (sections || []).map((s) => ({
    id: s.id,
    title: s.title,
    include: true,
    tasks: (s.tasks || []).map((tk) => ({ ...tk, include: tk.active !== false })),
  }))
}

/** Seçili bölüm / görevlerden içe aktarılacak bölümler (seçimsiz görevli bölüm düşer). */
export function selectedSections(rows) {
  const out = []
  for (const r of rows || []) {
    if (!r.include) continue
    const tasks = r.tasks.filter((tk) => tk.include).map((tk) => {
      const out = { ...tk, active: true }
      delete out.include
      return out
    })
    if (!tasks.length) continue
    out.push({ id: r.id, title: r.title.trim(), window_from: null, window_to: null, tasks })
  }
  return out
}

export default function WikiImportDialog({ open, onClose, draftSections = [], lang = 'tr', limits, onImport }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [input, setInput] = useState({ html: '', text: '' })
  const [pasteNote, setPasteNote] = useState(null)   // ikinci yapıştırmanın sonucu: 'appended' | 'plain' | null
  const [parsed, setParsed] = useState(null)
  const [rows, setRows] = useState([])
  const [mode, setMode] = useState('append')
  const [busy, setBusy] = useState(false)
  const draftRef = useRef(draftSections)
  draftRef.current = draftSections
  const L = useMemo(() => resolveLimits(limits), [limits])

  useEffect(() => {
    if (!open) return
    setInput({ html: '', text: '' })
    setPasteNote(null)
    setParsed(null)
    setRows([])
    setMode('append')
    setBusy(false)
  }, [open])

  useEffect(() => {
    if (!open) return undefined
    if (!input.html.trim() && !input.text.trim()) { setParsed(null); setRows([]); return undefined }
    const h = setTimeout(() => {
      const taken = collectIds({ sections: draftRef.current || [] }, new Set())
      const r = parseWikiImport(input, { lang: lang === 'en' ? 'en' : 'tr', taken })
      setParsed(r)
      setRows(rowsFrom(r.sections))
    }, PARSE_DELAY_MS)
    return () => clearTimeout(h)
  }, [input, open, lang])

  const draftTasks = countTasks({ sections: draftSections || [] })
  const effectiveMode = draftTasks > 0 ? mode : 'append'
  const chosen = useMemo(() => selectedSections(rows), [rows])
  const chosenTasks = chosen.reduce((a, s) => a + s.tasks.length, 0)
  const blankTitle = rows.some((r) => r.include && r.tasks.some((tk) => tk.include) && !r.title.trim())
  const longTitle = rows.some((r) => r.include && r.title.trim().length > L.section_title)
  const problems = useMemo(() => {
    if (!chosen.length) return []
    const merged = applyImport({ v: 1, sections: draftSections || [] }, chosen, effectiveMode, new Set())
    return importLimitProblems(merged.template, L)
  }, [chosen, draftSections, effectiveMode, L])
  const tooLarge = parsed?.error === 'TOO_LARGE' || input.html.length > WIKI_MAX_INPUT || input.text.length > WIKI_MAX_INPUT
  const canImport = !tooLarge && chosen.length > 0 && !blankTitle && !longTitle && problems.length === 0 && !busy

  // İkinci yapıştırma ilkini SİLMEZ: metin imlecin olduğu yere girer. Biçimli (HTML) içerik yalnız boş kutuya ya da yine
  // biçimli bir içeriğin SONUNA eklenince korunur; aksi hâlde düz metinden okunur ve bu söylenir.
  const onPaste = (e) => {
    const cd = e.clipboardData
    if (!cd) return
    const html = cd.getData('text/html') || ''
    const text = cd.getData('text/plain') || ''
    if (!html.trim() && !text) return
    e.preventDefault()
    const el = e.currentTarget
    const prev = input
    if (!prev.text.trim() && !prev.html.trim()) {
      setInput({ html, text })
      setPasteNote(null)
      return
    }
    const start = Number.isInteger(el?.selectionStart) ? el.selectionStart : prev.text.length
    const end = Number.isInteger(el?.selectionEnd) ? el.selectionEnd : prev.text.length
    const atEnd = start === prev.text.length && end === prev.text.length
    const sep = atEnd && prev.text && !prev.text.endsWith('\n') ? '\n' : ''
    const nextText = prev.text.slice(0, start) + sep + text + prev.text.slice(end)
    const keepHtml = atEnd && !!prev.html.trim() && !!html.trim()
    setInput({ html: keepHtml ? `${prev.html}\n${html}` : '', text: nextText })
    setPasteNote(keepHtml || (!prev.html.trim() && !html.trim()) ? 'appended' : 'plain')
  }

  const patchRow = (rid, patch) => setRows((rs) => rs.map((r) => (r.id === rid ? { ...r, ...patch } : r)))
  const patchTask = (rid, tid, fn) => setRows((rs) => rs.map((r) => (r.id !== rid ? r : {
    ...r, tasks: r.tasks.map((tk) => (tk.id === tid ? fn(tk) : tk)),
  })))

  const doImport = async () => {
    if (!canImport) return
    if (effectiveMode === 'replace' && draftTasks > 0) {
      setBusy(true)
      const ok = await showConfirm({
        title: t('or.imp.replaceTitle'),
        message: t(draftTasks === 1 ? 'or.imp.replaceBody.one' : 'or.imp.replaceBody', draftTasks),
        confirmText: t('or.imp.replaceConfirm'),
        variant: 'danger',
      })
      setBusy(false)
      if (!ok) return
    }
    onImport({ mode: effectiveMode, sections: chosen })
  }

  const st = parsed?.stats
  const statParts = st ? [
    t('or.imp.statSections', st.sections),
    t('or.imp.statTasks', st.tasks),
    t('or.imp.statLinks', st.links),
    st.description_lines ? t('or.imp.statDescription', st.description_lines) : null,
  ].filter(Boolean) : []
  const noteParts = st ? [
    st.dropped_links ? t('or.imp.noteDroppedLinks', st.dropped_links) : null,
    st.skipped_lines ? t('or.imp.noteSkipped', st.skipped_lines) : null,
    st.truncated_titles ? t('or.imp.noteTruncated', st.truncated_titles) : null,
    st.capped?.sections || st.capped?.tasks || st.capped?.links || st.capped?.description ? t('or.imp.noteCapped') : null,
    st.capped?.active ? t('or.imp.noteInactive', st.capped.active) : null,
    st.node_limit ? t('or.imp.noteNodeLimit') : null,
  ].filter(Boolean) : []

  const hasInput = !!(input.html.trim() || input.text.trim())
  const format = input.html.trim() ? 'html' : 'text'

  return (
    <ModalShell open={open} onClose={onClose} title={t('or.imp.title')} icon={FileInput} size="lg" scrollBody busy={busy}
      dismissOnBackdrop={false} className={PHONE_FULLSCREEN}
      footer={(
        <>
          <Button type="button" variant="outline" onClick={onClose} className="pointer-coarse:h-10">{t('dlg.cancel')}</Button>
          <Button type="button" data-slot="or-imp-confirm" disabled={!canImport} onClick={doImport} className="pointer-coarse:h-10">
            <Import aria-hidden="true" />
            {chosenTasks > 0
              ? t(chosenTasks === 1 ? 'or.imp.importN.one' : 'or.imp.importN', chosenTasks)
              : t('or.imp.import')}
          </Button>
        </>
      )}>
      <div data-slot="or-imp-dialog" className="@container flex min-w-0 flex-col gap-4">
        <p className="text-sm text-muted-foreground">{t('or.imp.intro')}</p>
        <div className="flex flex-col gap-1.5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <Label htmlFor="or-imp-input" className="text-sm font-semibold">{t('or.imp.inputLabel')}</Label>
            {hasInput && (
              <Badge variant="outline" data-slot="or-imp-format" data-format={format}
                className="h-auto whitespace-normal rounded-md overflow-visible">
                {format === 'html' ? t('or.imp.formatHtml') : t('or.imp.formatText')}
              </Badge>
            )}
          </div>
          <Textarea id="or-imp-input" data-slot="or-imp-input" rows={6} value={input.text} spellCheck={false}
            placeholder={t('or.imp.inputPh')} onPaste={onPaste}
            onChange={(e) => { setInput({ html: '', text: e.target.value }); setPasteNote(null) }}
            className="font-mono text-xs" />
          <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
            <ClipboardPaste aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
            <span>{t('or.imp.inputHint')}</span>
          </p>
          {pasteNote && (
            <p className="m-0 text-xs text-muted-foreground" role="status" data-slot="or-imp-paste-note" data-note={pasteNote}>
              {pasteNote === 'plain' ? t('or.imp.pastePlain') : t('or.imp.pasteAppended')}
            </p>
          )}
        </div>

        {tooLarge && (
          <div data-slot="or-imp-too-large">
            <AlertBanner tone="danger" className="mb-0">{t('or.imp.tooLargeError')}</AlertBanner>
          </div>
        )}

        {!tooLarge && parsed && !rows.length && hasInput && (
          <div data-slot="or-imp-empty">
            <AlertBanner tone="info" className="mb-0" title={t('or.imp.nothingTitle')}>{t('or.imp.nothingBody')}</AlertBanner>
          </div>
        )}

        {!tooLarge && rows.length > 0 && (
          <>
            <div data-slot="or-imp-stats" className="flex flex-col gap-1">
              <p className="text-sm font-medium tabular-nums">{statParts.join(' · ')}</p>
              {noteParts.length > 0 && <p className="text-xs text-muted-foreground">{noteParts.join(' · ')}</p>}
            </div>

            {draftTasks > 0 && (
              <div data-slot="or-imp-mode" data-mode={effectiveMode} className="flex flex-col gap-1.5">
                <span className="text-sm font-semibold">{t('or.imp.modeLabel')}</span>
                <SegmentedControl ariaLabel={t('or.imp.modeLabel')} value={effectiveMode} onChange={setMode} options={[
                  { value: 'append', label: t('or.imp.modeAppend') },
                  { value: 'replace', label: t('or.imp.modeReplace') },
                ]} />
                <p className="text-xs text-muted-foreground">
                  {effectiveMode === 'replace'
                    ? t(draftTasks === 1 ? 'or.imp.modeReplaceHint.one' : 'or.imp.modeReplaceHint', draftTasks)
                    : t('or.imp.modeAppendHint')}
                </p>
              </div>
            )}

            {(problems.length > 0 || blankTitle || longTitle) && (
              <div data-slot="or-imp-limit">
                <AlertBanner tone="warning" className="mb-0" title={t('or.imp.limitTitle')}>
                  <ul className="list-disc ps-4">
                    {blankTitle && <li>{t('or.imp.sectionTitleRequired')}</li>}
                    {longTitle && <li>{t('or.imp.sectionTitleTooLong', L.section_title)}</li>}
                    {problems.map((p, i) => <li key={i}>{t(p.k, ...(p.a || []))}</li>)}
                  </ul>
                </AlertBanner>
              </div>
            )}

            <div data-slot="or-imp-preview" className="flex flex-col gap-3">
              <h3 className="text-sm font-semibold">{t('or.imp.previewTitle')}</h3>
              {rows.map((r) => {
                const on = r.tasks.filter((tk) => tk.include).length
                return (
                  <div key={r.id} data-slot="or-imp-section" data-include={r.include ? 'true' : 'false'}
                    className={cn('flex flex-col gap-2 rounded-xl border border-border bg-card p-3', !r.include && 'opacity-70')}>
                    <div className="flex items-start gap-2">
                      <Checkbox checked={r.include} aria-label={t('or.imp.includeSection', r.title || t('or.tpl.untitledSection'))}
                        className="mt-2.5" onCheckedChange={(v) => patchRow(r.id, { include: v === true })} />
                      <div className="flex min-w-0 flex-1 flex-col gap-1">
                        <Input value={r.title} aria-label={t('or.imp.sectionTitle')} disabled={!r.include}
                          aria-invalid={r.include && !r.title.trim() ? true : undefined}
                          onChange={(e) => patchRow(r.id, { title: e.target.value })} className="font-semibold" />
                        <span className="text-xs tabular-nums text-muted-foreground">{t('or.imp.sectionTasks', on, r.tasks.length)}</span>
                      </div>
                    </div>
                    {r.include && (
                      <ul className="flex flex-col divide-y divide-border">
                        {r.tasks.map((tk) => (
                          <li key={tk.id} data-slot="or-imp-task" data-include={tk.include ? 'true' : 'false'}
                            className="flex flex-col gap-1.5 py-2 @md:flex-row @md:items-start @md:gap-3">
                            <div className="flex min-w-0 flex-1 items-start gap-2">
                              <Checkbox checked={tk.include} aria-label={t('or.imp.includeTask', tk.title)} className="mt-0.5"
                                onCheckedChange={(v) => patchTask(r.id, tk.id, (x) => ({ ...x, include: v === true }))} />
                              <div className="min-w-0 flex-1">
                                <p className={cn('text-sm [overflow-wrap:anywhere]', !tk.include && 'text-muted-foreground line-through')}>{tk.title}</p>
                                <p className="text-xs text-muted-foreground">
                                  {[
                                    tk.links?.length ? t(tk.links.length === 1 ? 'or.tpl.sumLinks.one' : 'or.tpl.sumLinks', tk.links.length) : null,
                                    tk.description ? t('or.tpl.sumDescription') : null,
                                  ].filter(Boolean).join(' · ') || frequencyLabel(t, tk)}
                                </p>
                              </div>
                            </div>
                            {tk.include && (
                              <div className="flex flex-wrap items-center gap-2 ps-7 @md:ps-0">
                                <NativeSelect value={tk.frequency} aria-label={t('or.imp.taskFrequency', tk.title)}
                                  onChange={(e) => patchTask(r.id, tk.id, (x) => withFrequency(x, e.target.value))}>
                                  {FREQUENCIES.map((f) => <NativeSelectOption key={f} value={f}>{t(`or.freq.${f}`)}</NativeSelectOption>)}
                                </NativeSelect>
                                {tk.frequency === 'WEEKLY' && (
                                  <NativeSelect value={tk.weekday ?? 5} aria-label={t('or.imp.taskWeekday', tk.title)}
                                    onChange={(e) => patchTask(r.id, tk.id, (x) => ({ ...x, weekday: Number(e.target.value) }))}>
                                    {WEEKDAYS.map((d) => <NativeSelectOption key={d} value={d}>{t(`or.weekday.${d}`)}</NativeSelectOption>)}
                                  </NativeSelect>
                                )}
                                {tk.frequency === 'MONTHLY' && (
                                  <span className="text-xs text-muted-foreground">{t(`or.monthly.${tk.monthly_rule || 'FIRST_WORKDAY'}`)}</span>
                                )}
                              </div>
                            )}
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )
              })}
            </div>
          </>
        )}
      </div>
    </ModalShell>
  )
}
