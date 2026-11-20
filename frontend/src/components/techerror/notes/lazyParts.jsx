import { Component, useCallback, useEffect, useState } from 'react'
import { useT } from '../../../i18n/index.jsx'
import { importWithDeadline, isImportTimeout } from '../../../utils/importWithDeadline.js'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import AlertBanner from '../../ui/AlertBanner.jsx'

/**
 * KANAL NOTU TEMBEL PARÇALARI (2026-11-20): görüntüleyici (`react-markdown`) ve düzenleyici (markdown editörü, görsel
 * hazırlama, ekran yakalama) YALNIZ buradan, ihtiyaç anında yüklenir — kanal sayfası, rapor düzenleyicisi ve görsel
 * paneli onları ön-yükleme grafiğinde taşımaz (kapı: lazyChunks.test.js).
 *
 * React.lazy yerine süre sınırlı `importWithDeadline` + durum: parça inemezse (dağıtım sonrası bayat paket, ağ) çevreleyen
 * ekran ÇÖKMEZ — yerinde "yüklenemedi" + "Tekrar dene" görünür (rapor düzenleyicisindeki kaydedilmemiş iş kaybolmaz).
 * Başarısız yükleme önbelleğe alınmaz; başarılı olan bir kez iner, sonraki açılışlar eşzamanlı çizilir.
 */
const LOADERS = {
  view: () => importWithDeadline(() => import('./ChannelNoteView.jsx')),
  editor: () => importWithDeadline(() => import('./ChannelNoteEditor.jsx')),
}
const loaded = new Map()
const inflight = new Map()

/** Parçayı yükler (süren isteğe katılır). Söz bileşenle çözülür; hata önbelleğe alınmaz. */
export function loadNotePart(kind) {
  if (loaded.has(kind)) return Promise.resolve(loaded.get(kind))
  if (!inflight.has(kind)) {
    const p = LOADERS[kind]().then((m) => {
      const Comp = m?.default
      if (!Comp) throw new Error('note part has no default export')
      loaded.set(kind, Comp)
      return Comp
    }).finally(() => { inflight.delete(kind) })
    inflight.set(kind, p)
  }
  return inflight.get(kind)
}

/** `{ Comp, failed, timedOut, retry }` — `enabled` yanlışken hiçbir şey yüklenmez. */
export function useNotePart(kind, enabled = true) {
  const [state, setState] = useState({ failed: false, timedOut: false, attempt: 0, tick: 0 })
  const Comp = loaded.get(kind) || null
  const { attempt } = state
  useEffect(() => {
    if (!enabled || loaded.has(kind)) return undefined
    let alive = true
    loadNotePart(kind).then(
      () => { if (alive) setState((s) => ({ ...s, failed: false, timedOut: false, tick: s.tick + 1 })) },
      (e) => { if (alive) setState((s) => ({ ...s, failed: true, timedOut: isImportTimeout(e) })) },
    )
    return () => { alive = false }
  }, [kind, enabled, attempt])
  const retry = useCallback(() => setState((s) => ({ ...s, failed: false, timedOut: false, attempt: s.attempt + 1 })), [])
  return { Comp, failed: state.failed && !Comp, timedOut: state.timedOut, retry }
}

function PartFallback({ part, rows = 2, slot }) {
  const t = useT()
  if (part.failed) {
    return (
      <div data-slot={`${slot}-part-failed`}>
        <AlertBanner tone="danger" title={t('te.note.partTitle')}
          actions={<Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" onClick={part.retry}>{t('te.retry')}</Button>}>
          {part.timedOut ? t('te.note.partSlow') : t('te.pageUpdated')}
        </AlertBanner>
      </div>
    )
  }
  return (
    <div role="status" aria-live="polite" className="flex flex-col gap-2" data-slot={`${slot}-part-loading`}>
      <span className="sr-only">{t('te.loading')}</span>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} aria-hidden="true" className={i === 0 ? 'h-4 w-3/4 max-w-full motion-reduce:animate-none' : 'h-4 w-1/2 max-w-full motion-reduce:animate-none'} />
      ))}
    </div>
  )
}

/** Çizilemeyen notun yedeği: kısa açıklama + notun HAM metni (sarar, uzun metin kendi kabında kayar). */
function NoteRenderFallback({ body }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-2" data-slot="te-note-view-broken">
      <AlertBanner tone="warning" title={t('te.note.view.brokenTitle')}>{t('te.note.view.brokenBody')}</AlertBanner>
      <pre tabIndex={0} data-slot="te-note-view-raw"
        className="max-h-[32rem] max-w-full overflow-y-auto rounded-md border bg-muted/40 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]">
        {typeof body === 'string' ? body : ''}
      </pre>
    </div>
  )
}

/**
 * Görüntüleyicinin hata sınırı: aşırı iç içe bir not (binlerce `>` / liste girintisi — 60 000 karakter içinde mümkün)
 * markdown ağacını kurarken yığını taşırabilir. Çökme sekmeyi / rapor kartını / görsel panelini DÜŞÜRMEZ — yerinde
 * "not çizilemedi" + ham metin görünür. Metin değişince (önizlemede yazarken) yeniden denenir.
 */
class NoteViewBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { broken: false }
  }

  static getDerivedStateFromError() {
    return { broken: true }
  }

  componentDidUpdate(prev) {
    if (this.state.broken && prev.body !== this.props.body) this.setState({ broken: false })
  }

  render() {
    return this.state.broken ? <NoteRenderFallback body={this.props.body} /> : this.props.children
  }
}

/** Notun kilitli görüntüleyicisi — ilk çizimde yüklenir. Prop'lar `ChannelNoteView`'unkiler. */
export function LazyNoteView(props) {
  const part = useNotePart('view')
  if (!part.Comp) return <PartFallback part={part} rows={3} slot="te-note-view" />
  const View = part.Comp
  return <NoteViewBoundary body={props.body}><View {...props} /></NoteViewBoundary>
}

/** Not düzenleyicisi — düzenleme kipine geçilince yüklenir. Prop'lar `ChannelNoteEditor`'ünkiler. */
export function LazyNoteEditor(props) {
  const part = useNotePart('editor')
  if (!part.Comp) return <PartFallback part={part} rows={4} slot="te-note-editor" />
  const Editor = part.Comp
  return <Editor {...props} />
}
