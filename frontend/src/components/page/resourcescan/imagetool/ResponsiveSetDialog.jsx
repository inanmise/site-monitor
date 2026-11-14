import { useEffect, useMemo, useRef, useState } from 'react'
import { CircleAlert, Copy, FileArchive, MonitorSmartphone, Play, Square } from 'lucide-react'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import { clientFailure } from '../../../../utils/errorMessages.js'
import { downloadBlob } from '../../../../utils/csvExport.js'
import { copyText } from '../../../../utils/copyText.js'
import { useToast } from '../../../ui/Toast.jsx'
import ModalShell from '../../../ui/ModalShell.jsx'
import Field from '../../../ui/Field.jsx'
import SegmentedControl from '../../../ui/SegmentedControl.jsx'
import { Spinner } from '../../../ui/Progress.jsx'
import ToneBadge from '../../../admin/ToneBadge.jsx'
import { formatNumber, formatSize } from '../resourceScanModel.js'
import { CHIP, TOUCH, WRAP } from '../ResourceScanParts.jsx'
import * as engine from './engineClient.js'
import { useTopic } from './ImageToolContext.jsx'
import { bytesOfBlob, createZipWriter } from './imageZip.js'
import {
  buildOptions, dimsText, engineProblemText, extOfType, pctText, pictureSnippet, phaseText, resizeSettings, responsiveWidths, resultSummary, savingPctOf,
  slugFileBase, typeLabel, uniqueName, zipFileName,
} from './imageToolModel.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'

/** Sette "en küçük biçim" yok: her genişlik aynı biçimde olmalı (`<source type>` tek tür söyler). */
const SET_FORMATS = ['keep', 'webp', 'jpeg', 'png']
const SNIPPET_FILE = 'snippet.html'

/**
 * DUYARLI SET (2026-11-14, kullanıcı kararı 8): tek görselden 480 / 768 / 1280 / 1920 genişliklerinden doğal genişliği
 * aşmayanlar (hepsi büyükse yalnız doğal genişlik) üretilir, her biri seçilen biçimde optimize edilir, tek ZIP olarak
 * iner; yanında kopyalanmaya hazır `<img srcset sizes>` / `<picture>` kodu (değerler kaçışlı, dosya adları ZIP'tekiyle
 * AYNI; `alt` ve `sizes` için küçük form). İşlem tarayıcıda, genişlikler SIRAYLA; pencere kapanınca süren iş iptal edilir.
 * Bir genişliğin hatası ötekileri durdurmaz — ZIP ve kod yalnız HAZIR olanları içerir.
 */
export default function ResponsiveSetDialog({ name, base, blob, info, format = 'keep', store, onClose }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const support = useTopic(store, 'support', () => store.support())
  const widths = useMemo(() => responsiveWidths(info?.width), [info?.width])
  const slug = useMemo(() => slugFileBase(base || name), [base, name])
  const [fmt, setFmt] = useState(SET_FORMATS.includes(format) ? format : 'keep')
  const [alt, setAlt] = useState('')
  const [sizes, setSizes] = useState('100vw')
  const [prefix, setPrefix] = useState('')
  const [kind, setKind] = useState('img')
  const [rows, setRows] = useState(() => widths.map((w) => ({ width: w, state: 'idle', phase: null, summary: null, name: null, problem: null })))
  const [running, setRunning] = useState(false)
  const [zipping, setZipping] = useState(false)
  const [copied, setCopied] = useState(false)
  const ctl = useRef(null)
  const files = useRef(new Map())
  const alive = useRef(true)
  const seq = useRef(0)
  useEffect(() => {
    alive.current = true
    store.ensureSupport()
    const held = files.current
    return () => { alive.current = false; ctl.current?.abort(); held.clear() }
  }, [store])

  const patch = (width, p) => setRows((rs) => rs.map((r) => (r.width === width ? { ...r, ...p } : r)))

  async function generate() {
    if (running) return
    const mine = ++seq.current
    const c = new AbortController()
    ctl.current = c
    files.current.clear()
    setRunning(true)
    setRows(widths.map((w) => ({ width: w, state: 'waiting', phase: null, summary: null, name: null, problem: null })))
    const live = () => alive.current && seq.current === mine && !c.signal.aborted
    const used = new Set([SNIPPET_FILE])
    try {
      // Sınırlı döngü: en çok dört genişlik, sırayla
      for (const w of widths) {
        if (!live()) return
        const options = buildOptions(resizeSettings(w, { format: fmt }), support.limits)
        if (!options) { patch(w, { state: 'failed', problem: { code: 'INVALID_OPTIONS', params: {} } }); continue }
        patch(w, { state: 'processing' })
        try {
          const res = await engine.processImage(blob, options, { signal: c.signal, onProgress: (phase) => { if (live()) patch(w, { phase }) } })
          if (!live()) return
          const summary = resultSummary(res)
          const fileName = uniqueName(`${slug}-${summary.width ?? w}w.${extOfType(summary.type)}`, used)
          files.current.set(w, res.blob)
          patch(w, { state: 'done', summary, name: fileName, phase: null })
        } catch (e) {
          if (!live() || e?.code === 'CANCELLED') return
          patch(w, { state: 'failed', phase: null, problem: engine.isEngineError(e) ? { code: e.code, params: e.params } : { code: 'other', params: {} } })
        }
      }
    } finally {
      if (ctl.current === c) ctl.current = null
      if (alive.current && seq.current === mine) setRunning(false)
    }
  }
  function stop() {
    ctl.current?.abort()
    seq.current += 1
    setRunning(false)
    setRows((rs) => rs.map((r) => (r.state === 'waiting' || r.state === 'processing' ? { ...r, state: 'idle', phase: null } : r)))
  }

  const ready = rows.filter((r) => r.state === 'done' && r.summary && r.name)
  // Kodda her genişlik bir kez: motor büyütmediği için iki satır aynı genişliğe düşebilir (ör. doğal 500 → 480 ve 500 ayrı)
  const snippetFiles = useMemo(() => {
    const seen = new Set()
    const out = []
    for (const r of ready) {
      const w = r.summary.width ?? r.width
      if (seen.has(w)) continue
      seen.add(w)
      out.push({ name: r.name, width: w, height: r.summary.height, type: r.summary.type })
    }
    return out
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows])
  const snippet = useMemo(() => pictureSnippet({ files: snippetFiles, alt, sizes, kind, prefix, fallbackName: name }),
    [snippetFiles, alt, sizes, kind, prefix, name])

  async function downloadZip() {
    if (zipping || !ready.length) return
    setZipping(true)
    let writer = null
    try {
      writer = await createZipWriter()
      for (const r of ready) {
        const b = files.current.get(r.width)
        if (b) writer.add(r.name, await bytesOfBlob(b))
      }
      if (snippet) writer.addText(SNIPPET_FILE, `${snippet}\n`)
      const out = writer.finish()
      writer = null
      if (!alive.current) return
      if (downloadBlob(zipFileName(slug, t('prs.it.rs.zipSuffix')), out) === false) toast.error(t('prs.it.srv.download'))
      else toast.success(t('prs.it.rs.zipDone', formatNumber(ready.length, locale)))
    } catch (e) {
      if (!alive.current) return
      const f = clientFailure(e, { fallback: t('prs.it.zip.unexpected'), chunk: t('prs.export.pageUpdated') })
      toast.error(f.message, { details: f.details })
    } finally {
      writer?.abort()
      if (alive.current) setZipping(false)
    }
  }
  async function copy() {
    if (!snippet) return
    if (await copyText(snippet)) {
      setCopied(true)
      setTimeout(() => { if (alive.current) setCopied(false) }, 2000)
    } else toast.info(t('prs.it.rs.copyManual'))
  }

  const webp = support.webp === true
  const supportKnown = support.state === 'ready'
  const tone = { idle: 'muted', waiting: 'muted', processing: 'info', done: 'success', failed: 'danger' }
  return (
    <ModalShell open onClose={onClose} title={t('prs.it.rs.title')} icon={MonitorSmartphone} size="lg" scrollBody closeLabel={t('app.dismiss')}
      dismissOnBackdrop={!running}
      footer={(
        <>
          <Button type="button" variant="outline" className={TOUCH} onClick={onClose}>{t('app.dismiss')}</Button>
          <Button type="button" variant="outline" className={TOUCH} data-slot="prs-it-rs-zip" disabled={!ready.length || running || zipping}
            aria-busy={zipping || undefined} onClick={downloadZip}>
            {zipping ? <Spinner size={14} inline decorative /> : <FileArchive aria-hidden="true" />}{t('prs.it.rs.zip')}
          </Button>
          {running ? (
            <Button type="button" variant="outline" className={TOUCH} data-slot="prs-it-rs-stop" onClick={stop}><Square aria-hidden="true" />{t('prs.it.cancel')}</Button>
          ) : (
            <Button type="button" className={TOUCH} data-slot="prs-it-rs-go" disabled={!widths.length} onClick={generate}>
              <Play aria-hidden="true" />{t(ready.length ? 'prs.it.rs.regenerate' : 'prs.it.rs.generate')}
            </Button>
          )}
        </>
      )}>
      <div data-slot="prs-it-rs" className="flex min-w-0 flex-col gap-3">
        <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
          {name} · {dimsText(info?.width, info?.height) || t('prs.img.dimsUnknown')}
        </p>
        {widths.length === 0 ? (
          <p data-slot="prs-it-rs-nowidth" className="m-0 flex items-start gap-2 text-sm text-muted-foreground">
            <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />{t('prs.it.rs.noWidth')}
          </p>
        ) : (
          <>
            <p className={cn('m-0 text-sm text-foreground', WRAP)}>{t('prs.it.rs.intro', widths.map((w) => formatNumber(w, locale)).join(' · '))}</p>
            <Field label={t('prs.it.set.format')} name="rsFormat" hint={supportKnown && !webp ? t('prs.it.set.webpOff') : t('prs.it.rs.formatHint')}>
              {({ id, describedBy }) => (
                <NativeSelect id={id} aria-describedby={describedBy} value={fmt} disabled={running} data-slot="prs-it-rs-format"
                  className="pointer-coarse:h-10" onChange={(e) => setFmt(e.target.value)}>
                  {SET_FORMATS.map((f) => (
                    <NativeSelectOption key={f} value={f} disabled={f === 'webp' && supportKnown && !webp}>{t(`prs.it.set.format.${f}`)}</NativeSelectOption>
                  ))}
                </NativeSelect>
              )}
            </Field>
            <ul data-slot="prs-it-rs-rows" className="m-0 flex list-none flex-col gap-1.5 p-0">
              {rows.map((r) => {
                const pct = savingPctOf(r.summary)
                return (
                  <li key={r.width} data-slot="prs-it-rs-row" data-width={r.width} data-state={r.state}
                    className="flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1 rounded-md border px-3 py-2 text-xs">
                    <span className="flex min-w-0 flex-col gap-0.5">
                      <span className="text-sm font-semibold tabular-nums">{t('prs.it.rs.widthPx', formatNumber(r.width, locale))}</span>
                      {r.state === 'done' && (
                        <span className={cn('text-muted-foreground tabular-nums', WRAP)}>
                          {[r.name, dimsText(r.summary.width, r.summary.height), typeLabel(r.summary.type), formatSize(r.summary.bytes, { locale }),
                            pct !== null && pct > 0 ? t('prs.it.rs.vsOriginal', pctText(pct, locale)) : null].filter(Boolean).join(' · ')}
                        </span>
                      )}
                      {r.state === 'failed' && <span className={cn('text-destructive', WRAP)}>{engineProblemText(r.problem?.code, r.problem?.params, t, locale)}</span>}
                    </span>
                    <ToneBadge tone={tone[r.state] || 'muted'} className={cn(CHIP, 'gap-1')}>
                      {r.state === 'processing' && <Spinner size={11} inline decorative />}
                      {r.state === 'processing' && r.phase ? phaseText(r.phase, t) : t(`prs.it.rs.state.${r.state === 'failed' ? 'problem' : r.state}`)}
                    </ToneBadge>
                  </li>
                )
              })}
            </ul>

            <section data-slot="prs-it-rs-code" className="flex min-w-0 flex-col gap-2">
              <h4 className="m-0 text-sm font-semibold">{t('prs.it.rs.codeTitle')}</h4>
              <div data-slot="prs-it-rs-kind">
                <SegmentedControl value={kind} onChange={setKind} ariaLabel={t('prs.it.rs.kind')}
                  options={[{ value: 'img', label: t('prs.it.rs.kindImg') }, { value: 'picture', label: t('prs.it.rs.kindPicture') }]} />
              </div>
              <div className="grid min-w-0 grid-cols-1 gap-x-3 sm:grid-cols-2">
                <Field label={t('prs.it.rs.alt')} name="rsAlt" hint={t('prs.it.rs.altHint')}>
                  {({ id, describedBy }) => <Input id={id} aria-describedby={describedBy} value={alt} maxLength={300} className="pointer-coarse:h-10"
                    data-slot="prs-it-rs-alt" onChange={(e) => setAlt(e.target.value)} />}
                </Field>
                <Field label={t('prs.it.rs.sizes')} name="rsSizes" hint={t('prs.it.rs.sizesHint')}>
                  {({ id, describedBy }) => <Input id={id} aria-describedby={describedBy} value={sizes} maxLength={300} spellCheck={false}
                    className="font-mono pointer-coarse:h-10" data-slot="prs-it-rs-sizes" onChange={(e) => setSizes(e.target.value)} />}
                </Field>
                <Field label={t('prs.it.rs.prefix')} name="rsPrefix" hint={t('prs.it.rs.prefixHint')}>
                  {({ id, describedBy }) => <Input id={id} aria-describedby={describedBy} value={prefix} maxLength={300} spellCheck={false}
                    className="font-mono pointer-coarse:h-10" data-slot="prs-it-rs-prefix" placeholder="/img/" onChange={(e) => setPrefix(e.target.value)} />}
                </Field>
              </div>
              {snippet ? (
                <>
                  <pre data-slot="prs-it-rs-snippet" tabIndex={0}
                    className="m-0 max-h-56 overflow-auto rounded-md border bg-muted/40 p-3 text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]"><code>{snippet}</code></pre>
                  <div className="flex flex-wrap items-center gap-2">
                    <Button type="button" variant="outline" size="sm" className={TOUCH} data-slot="prs-it-rs-copy" onClick={copy}>
                      <Copy aria-hidden="true" />{t(copied ? 'prs.it.rs.copied' : 'prs.it.rs.copy')}
                    </Button>
                    <span className={cn('text-xs text-muted-foreground', WRAP)}>{t(kind === 'picture' ? 'prs.it.rs.pictureNote' : 'prs.it.rs.imgNote')}</span>
                  </div>
                </>
              ) : (
                <p data-slot="prs-it-rs-nocode" className="m-0 text-xs text-muted-foreground">{t('prs.it.rs.noCode')}</p>
              )}
            </section>
          </>
        )}
      </div>
    </ModalShell>
  )
}
