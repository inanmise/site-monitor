import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { FileDown, Printer } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useUserDirectory } from '../ui/UserDirectory.jsx'
import MarkdownEditor from '../ui/MarkdownEditor.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Table, TableBody, TableCell, TableHead, TableRow } from '@/components/shadcn/table'
import { downloadBlob } from '../../utils/csvExport.js'
import { SUMMARY_SECTIONS, incidentMarkdown, istanbulStamp, summaryFacts, summaryFileName } from './incidentSummary.js'

/** Özet / yazdırma için kaydın taze okuması bu süreyi aşamaz. */
export const SUMMARY_READ_TIMEOUT_MS = 20_000
/** Yazdırma görünümünün işaretlendiği body özniteliği (App.css `@media print` kuralı). */
export const PRINT_ATTR = 'data-ih-print'
/** Yazdırmadan önce görsellerin yüklenmesi en çok bu kadar beklenir (inceleme 2026-11-26) — sonra görsel ne olursa olsun yazdırılır. */
export const PRINT_IMAGE_WAIT_MS = 5_000

/**
 * Kök içindeki görsellerin yüklenmesini (load / error) bekler — en çok `maxMs` (inceleme 2026-11-26: yazdırma 50 ms sonra
 * tetikleniyordu, markdown görselleri henüz inmemişse baskıda boş kutu çıkıyordu). Tembel (`loading="lazy"`) görsel hemen
 * yüklensin diye `eager` yapılır (yazdırma kökü ekranda görünmez; tembel görsel orada hiç inmezdi). Yüklenmiş / görseli
 * olmayan kök hemen çözülür. `{ promise, cancel }`: iptal dinleyicileri ve zamanlayıcıyı kaldırır (söz çözülmez).
 */
export function waitForImages(root, maxMs = PRINT_IMAGE_WAIT_MS) {
  const imgs = root ? Array.from(root.querySelectorAll('img')) : []
  let cancel = () => {}
  const promise = new Promise((resolve) => {
    const cleanups = []
    let timer = null
    let settled = false
    let left = 0
    const stop = () => {
      settled = true
      if (timer != null) clearTimeout(timer)
      for (const c of cleanups) c()
    }
    const done = () => { if (!settled) { stop(); resolve() } }
    for (const img of imgs) {
      if (img.getAttribute('loading') === 'lazy') img.setAttribute('loading', 'eager')
      if (img.complete) continue
      left += 1
      let counted = false
      const one = () => {
        if (counted) return
        counted = true
        left -= 1
        if (left <= 0) done()
      }
      img.addEventListener('load', one)
      img.addEventListener('error', one)
      cleanups.push(() => { img.removeEventListener('load', one); img.removeEventListener('error', one) })
    }
    if (left === 0) { done(); return }
    timer = setTimeout(done, Math.max(0, Number(maxMs) || 0))
    cancel = () => { if (!settled) stop() }
  })
  return { promise, cancel: () => cancel() }
}

/**
 * Yazdırma görünümü (2026-11-26) — body'nin DOĞRUDAN çocuğu (portal); ekranda görünmez, yalnız yazdırırken `body[data-ih-print]`
 * ile tek görünen öğe olur (App.css). İçerik özetle aynı: başlık, olgu tablosu, dört bölüm (işlenmiş Markdown).
 */
function PrintView({ record, t, nameOf, locale, rootRef }) {
  const facts = summaryFacts(record, t, { nameOf, locale })
  return createPortal(
    <div ref={rootRef} data-slot="ih-print-root" aria-hidden="true" className="ih-print-root">
      <h1 className="mb-1 text-xl font-bold">{record.title || t('inc.sum.untitled')}</h1>
      <p className="mb-3 text-xs">{t('inc.sum.recordNo', record.id)} · {t('inc.sum.generated', istanbulStamp(new Date().toISOString().slice(0, 19)))}</p>
      {facts.length > 0 && (
        <div className="mb-4">
          <Table className="table-fixed">
            <TableBody>
              {facts.map(([k, v]) => (
                <TableRow key={k} className="hover:bg-transparent">
                  <TableHead scope="row" className="h-auto w-1/3 border px-2 py-1 align-top font-semibold whitespace-normal [overflow-wrap:anywhere]">{k}</TableHead>
                  <TableCell className="border px-2 py-1 align-top whitespace-normal [overflow-wrap:anywhere]">{v}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {SUMMARY_SECTIONS.map(([titleKey, field]) => {
        const text = String(record[field] ?? '').trim()
        return (
          <section key={field} className="mb-3 break-inside-avoid-page">
            <h2 className="mb-1 text-base font-bold">{t(titleKey)}</h2>
            {text ? <div className="text-sm"><MarkdownEditor value={text} editable={false} /></div>
              : <p className="text-sm italic">{t('inc.notEntered')}</p>}
          </section>
        )
      })}
    </div>,
    document.body,
  )
}

/**
 * "Özeti indir" + "Yazdır" (2026-11-26, "Bul ve Raporla"). İkisi de önce kaydın TAZE hâlini okur (en çok
 * {@link SUMMARY_READ_TIMEOUT_MS}; liste satırı özet olabilir ve dört metni taşımaz) — okunamazsa hata bildirimi, hiçbir şey
 * indirilmez / yazdırılmaz. Özet Markdown dosyasıdır (`downloadBlob` — Safari / iPad); yazdırma tarayıcının yazdırma
 * penceresini açar (PDF olarak kaydetme de oradan). Pencere kapanınca yoldaki okuma iptal edilir.
 */
export default function IncidentReportActions({ record }) {
  const t = useT()
  const toast = useToast()
  const locale = useDateLocale()
  const dir = useUserDirectory()
  const [busy, setBusy] = useState(null)          // null | 'md' | 'print'
  const [printRec, setPrintRec] = useState(null)
  // Yazdırma görünümü bağlandı, görseller bekleniyor (en çok PRINT_IMAGE_WAIT_MS) — düğme meşgul görünür
  const [printWaiting, setPrintWaiting] = useState(false)
  const abortRef = useRef(null)
  const printRootRef = useRef(null)
  const aliveRef = useRef(true)
  useEffect(() => {
    aliveRef.current = true
    return () => { aliveRef.current = false; abortRef.current?.abort() }
  }, [])
  const nameOf = (u) => dir?.lookup?.(u)?.display_name || null

  // Yazdırma: görünüm çizildikten sonra (bir sonraki kare) görsellerin yüklenmesi beklenir (en çok PRINT_IMAGE_WAIT_MS), sonra
  // yazdırılır; pencere kapanınca (afterprint) ya da print() döndüğünde temizlenir. Pencere kapanırsa bekleme iptal edilir.
  useEffect(() => {
    if (!printRec) return undefined
    let done = false
    let cancelled = false
    let waiter = null
    const finish = () => {
      if (done) return
      done = true
      document.body.removeAttribute(PRINT_ATTR)
      window.removeEventListener('afterprint', finish)
      if (aliveRef.current) setPrintRec(null)
    }
    document.body.setAttribute(PRINT_ATTR, '1')
    window.addEventListener('afterprint', finish)
    setPrintWaiting(true)
    let safety = null
    const id = window.setTimeout(() => {
      waiter = waitForImages(printRootRef.current, PRINT_IMAGE_WAIT_MS)
      waiter.promise.then(() => {
        if (cancelled) return
        if (aliveRef.current) setPrintWaiting(false)
        try { window.print() } catch { /* yazdırma yok (gömülü görünüm) */ }
        // Çoğu tarayıcıda print() pencere kapanana dek bekler; beklemeyenlerde afterprint temizler. Güvenlik payı: 60 sn.
        safety = window.setTimeout(finish, 60_000)
      })
    }, 50)
    return () => {
      cancelled = true
      window.clearTimeout(id)
      waiter?.cancel()
      if (aliveRef.current) setPrintWaiting(false)
      if (safety != null) window.clearTimeout(safety)
      if (!done) { done = true; document.body.removeAttribute(PRINT_ATTR); window.removeEventListener('afterprint', finish) }
    }
  }, [printRec])

  async function readFresh() {
    abortRef.current?.abort()
    const ctl = typeof AbortController === 'function' ? new AbortController() : null
    abortRef.current = ctl
    let res = null
    try { res = await api.incidents.get(record.id, { timeoutMs: SUMMARY_READ_TIMEOUT_MS, signal: ctl?.signal }) } catch { res = null }
    if (!aliveRef.current || ctl?.signal?.aborted) return { rec: null, aborted: true }
    const d = res?.success ? res.data : null
    if (d && typeof d === 'object' && !Array.isArray(d) && String(d.id) === String(record.id)) return { rec: d }
    return { rec: null, res }
  }

  async function download() {
    if (busy) return
    setBusy('md')
    try {
      const { rec, res, aborted } = await readFresh()
      if (aborted) return
      if (!rec) { toast.error(res?.error || t('inc.sum.readFailed'), { details: res || undefined }); return }
      let origin = ''
      try { origin = window.location.origin } catch { origin = '' }
      const md = incidentMarkdown(rec, t, { nameOf, locale, origin })
      const name = summaryFileName(rec)
      if (!downloadBlob(name, new Blob([md], { type: 'text/markdown;charset=utf-8' }))) { toast.error(t('inc.sum.downloadFailed')); return }
      toast.success(t('inc.sum.downloaded', name))
    } finally {
      if (aliveRef.current) setBusy(null)
    }
  }

  async function print() {
    if (busy) return
    setBusy('print')
    try {
      const { rec, res, aborted } = await readFresh()
      if (aborted) return
      if (!rec) { toast.error(res?.error || t('inc.sum.readFailed'), { details: res || undefined }); return }
      setPrintRec(rec)
    } finally {
      if (aliveRef.current) setBusy(null)
    }
  }

  return (
    <>
      <Button type="button" variant="outline" size="sm" className="h-9 pointer-coarse:h-10" data-slot="ih-summary-download"
        disabled={busy != null || printWaiting} aria-busy={busy === 'md' || undefined} onClick={download}>
        {busy === 'md' ? <Spinner size={14} label={t('inc.loading')} /> : <FileDown aria-hidden="true" />}{t('inc.sum.download')}
      </Button>
      <Button type="button" variant="outline" size="sm" className="h-9 pointer-coarse:h-10" data-slot="ih-print"
        disabled={busy != null || printWaiting} aria-busy={busy === 'print' || printWaiting || undefined} onClick={print}>
        {busy === 'print' || printWaiting ? <Spinner size={14} label={t('inc.loading')} /> : <Printer aria-hidden="true" />}{t('inc.sum.print')}
      </Button>
      {printRec && <PrintView record={printRec} t={t} nameOf={nameOf} locale={locale} rootRef={printRootRef} />}
    </>
  )
}
