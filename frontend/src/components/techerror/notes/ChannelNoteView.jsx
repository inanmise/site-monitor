import { createContext, memo, useCallback, useContext, useMemo, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ImageOff, ShieldAlert } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { safeHref } from '../../../utils/safeHref.js'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { ImageLightbox } from '../ImageLightbox.jsx'
import { NOTE_REF_PREFIX, imagesById, noteImageIdOf } from './noteModel.js'

/**
 * KANAL NOTU GÖRÜNTÜLEYİCİSİ — notun TEK çizicisi (okuma kipi, düzenleyicinin önizlemesi, sürüm önizlemesi, rapor ve
 * görsel paneli kartı). Tembel parça: `react-markdown` yalnız bu dosyadan girer (bkz. lazyChunks.test.js).
 *
 * KİLİTLİ ÇİZİM (not başka takımlarla paylaşılabilen, kullanıcı yazısı bir belgedir):
 *  - `rehype` eklentisi YOK: ham HTML çalışmaz, düz metin olarak görünür.
 *  - GÖRSEL: yalnız `te-note-img:<kimlik>` ve kimlik notun `images` listesindeyse çizilir (aynı kökenli adres). Başka
 *    her adres (https://…, data:, göreli) için <img> HİÇ çizilmez — uzak sunucuya istek gitmez; yerine satır içi bir
 *    bilgi kutusu gelir. Listede olmayan kimlik "görsel artık yok" kutusudur.
 *  - BAĞLANTI: `utils/safeHref` (http / https / mailto + göreli); yeni sekme, `rel="noopener noreferrer"`. Güvensiz
 *    şema düz metin kalır.
 *  - Başlıklar bir düzey iner (h1 → h3 …): sayfanın / kartın başlık sırasını bozmaz.
 * Uzun sözcük ve adresler sarar; tablolar kendi kabında yatay kayar; görsel boyutu önceden ayrılır (zıplama yok).
 */

const REMARK_PLUGINS = [remarkGfm]

const NoteViewContext = createContext({ images: new Map(), headingBase: 3, onZoom: () => {} })
// Bağlantının İÇİNDE mi çiziliyor: bağlantı içindeki görsel düğme olamaz (etkileşimli öğe iç içe geçmez) — düz <img> çizilir,
// tıklayınca bağlantı açılır
const InLinkContext = createContext(false)

/** URL öznitelikleri: görselde yalnız kanonik not başvurusu, bağlantıda güvenli şema; ötekiler boş. */
export function noteUrlTransform(url, key, node) {
  const tag = node?.tagName
  if (tag === 'img') {
    if (key !== 'src') return ''
    const id = noteImageIdOf(typeof url === 'string' ? url.trim() : '')
    return id ? `${NOTE_REF_PREFIX}${id}` : ''
  }
  if (tag === 'a' && key === 'href') return safeHref(url) ?? ''
  return ''
}

const PLACEHOLDER = 'my-1 inline-flex max-w-full items-start gap-1.5 rounded-md border border-dashed bg-muted/40 px-2 py-1 align-middle text-xs text-muted-foreground'

function ImagePlaceholder({ slot, icon: Icon, text, alt, children }) {
  return (
    <span className={PLACEHOLDER} data-slot={slot}>
      <Icon aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
      <span className="min-w-0 [overflow-wrap:anywhere]">{alt ? `${text} — ${alt}` : text}</span>
      {children}
    </span>
  )
}

function NoteImage({ src, alt }) {
  const t = useT()
  const ctx = useContext(NoteViewContext)
  const inLink = useContext(InLinkContext)
  const btnRef = useRef(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const id = noteImageIdOf(typeof src === 'string' ? src : '')
  // Not görseli olmayan her adres: <img> çizilmez (uzak sunucuya istek YOK)
  if (!id) return <ImagePlaceholder slot="te-note-img-blocked" icon={ShieldAlert} text={t('te.note.view.imageBlocked')} alt={alt} />
  const meta = ctx.images.get(id)
  if (!meta) return <ImagePlaceholder slot="te-note-img-missing" icon={ImageOff} text={t('te.note.view.imageMissing')} alt={alt} />
  const label = alt || meta.file_name || t('te.note.view.imageUnnamed')
  if (failed) {
    return (
      <ImagePlaceholder slot="te-note-img-failed" icon={ImageOff} text={t('te.note.view.imageNotLoaded')} alt={label}>
        {!inLink && (
          <Button type="button" size="sm" variant="outline" className="h-7 shrink-0 px-2 text-xs pointer-coarse:h-10"
            data-slot="te-note-img-retry" onClick={() => { setFailed(false); setAttempt((n) => n + 1) }}>
            {t('te.retry')}
          </Button>
        )}
      </ImagePlaceholder>
    )
  }
  const url = api.techErrors.channelNoteImageUrl(meta.id)
  if (inLink) {
    return (
      <img key={attempt} src={url} alt={label} width={meta.width || undefined} height={meta.height || undefined}
        loading="lazy" decoding="async" data-slot="te-note-img" data-image-id={id} data-in-link="true"
        className="my-1 block h-auto max-w-full rounded-md border bg-muted/30" onError={() => setFailed(true)} />
    )
  }
  return (
    <Button ref={btnRef} type="button" variant="ghost" data-slot="te-note-img-open" aria-label={t('te.note.view.zoom', label)}
      className="my-1 block h-auto max-w-full cursor-zoom-in rounded-md p-0 whitespace-normal hover:bg-transparent pointer-coarse:min-h-10 pointer-coarse:min-w-10"
      onClick={() => ctx.onZoom({ src: url, label, width: meta.width, height: meta.height }, btnRef)}>
      <img key={attempt} src={url} alt={label} width={meta.width || undefined} height={meta.height || undefined}
        loading="lazy" decoding="async" data-slot="te-note-img" data-image-id={id}
        className="block h-auto max-w-full rounded-md border bg-muted/30" onError={() => setFailed(true)} />
    </Button>
  )
}

function NoteLink({ href, title, id, children, ...rest }) {
  // Güvensiz şema (javascript:, data: …): tıklanamaz, düz metin
  if (!href) return <span id={id} data-slot="te-note-link-plain">{children}</span>
  const inPage = href.startsWith('#')
  // Dipnot bağlantıları (remark-gfm): `id` (geri dönüş hedefi) ve erişilebilirlik öznitelikleri korunur
  const a11y = {}
  if (typeof rest['aria-label'] === 'string') a11y['aria-label'] = rest['aria-label']
  if (typeof rest['aria-describedby'] === 'string') a11y['aria-describedby'] = rest['aria-describedby']
  return (
    <a href={href} id={id} title={title || undefined} {...a11y} {...(inPage ? {} : { target: '_blank', rel: 'noopener noreferrer' })}
      className="text-primary underline underline-offset-2 [overflow-wrap:anywhere]">
      <InLinkContext.Provider value={true}>{children}</InLinkContext.Provider>
    </a>
  )
}

const HEADING_CLASS = ['', '', '', 'text-base font-semibold leading-snug', 'text-sm font-semibold leading-snug',
  'text-sm font-semibold leading-snug text-muted-foreground', 'text-xs font-semibold uppercase leading-snug tracking-wide text-muted-foreground']

function NoteHeading({ node, children, id }) {
  const { headingBase } = useContext(NoteViewContext)
  const depth = Number(String(node?.tagName || 'h1').slice(1)) || 1
  const level = Math.min(6, Math.max(headingBase, depth + headingBase - 1))
  const Tag = `h${level}`
  // Dipnot bölümünün gizli başlığı (remark-gfm) gizli kalır
  const hidden = [].concat(node?.properties?.className || []).includes('sr-only')
  return <Tag id={id} className={hidden ? 'sr-only' : cn('mt-1 first:mt-0 [overflow-wrap:anywhere]', HEADING_CLASS[level])}>{children}</Tag>
}

function NoteList({ node, className, children }) {
  const ordered = node?.tagName === 'ol'
  const tasks = typeof className === 'string' && className.includes('contains-task-list')
  const Tag = ordered ? 'ol' : 'ul'
  return (
    <Tag start={ordered ? node?.properties?.start : undefined}
      className={cn('flex flex-col gap-1', tasks ? 'list-none ps-0' : ordered ? 'list-decimal ps-6' : 'list-disc ps-6')}>
      {children}
    </Tag>
  )
}

function NoteTaskBox({ type, checked }) {
  const t = useT()
  if (type !== 'checkbox') return null
  return <Checkbox checked={!!checked} disabled aria-label={checked ? t('te.note.view.taskDone') : t('te.note.view.taskOpen')}
    className="me-2 inline-flex align-[-2px]" />
}

// Kimliği DEĞİŞMEYEN bileşen haritası (her çizimde yeni nesne olsa markdown ağacı baştan kurulurdu)
const COMPONENTS = {
  h1: NoteHeading, h2: NoteHeading, h3: NoteHeading, h4: NoteHeading, h5: NoteHeading, h6: NoteHeading,
  p: ({ children }) => <p className="leading-relaxed">{children}</p>,
  a: NoteLink,
  img: NoteImage,
  ul: NoteList,
  ol: NoteList,
  li: ({ children, id }) => <li id={id} className="leading-relaxed [&>ol]:mt-1 [&>p+p]:mt-2 [&>ul]:mt-1">{children}</li>,
  input: NoteTaskBox,
  blockquote: ({ children }) => (
    <blockquote className="flex flex-col gap-2 border-s-4 border-border ps-3 text-muted-foreground">{children}</blockquote>
  ),
  hr: () => <hr className="h-px border-0 bg-border" />,
  pre: ({ children }) => (
    <pre className="max-w-full overflow-x-auto rounded-md border bg-muted/50 p-3 font-mono text-xs leading-relaxed [&>code]:bg-transparent [&>code]:p-0 [&>code]:text-[1em]"
      tabIndex={0}>
      {children}
    </pre>
  ),
  code: ({ children }) => <code className="rounded bg-muted px-1 py-0.5 font-mono text-[0.85em]">{children}</code>,
  table: ({ children }) => <Table data-slot="te-note-table" className="w-max min-w-full">{children}</Table>,
  thead: ({ children }) => <TableHeader>{children}</TableHeader>,
  tbody: ({ children }) => <TableBody>{children}</TableBody>,
  tr: ({ children }) => <TableRow className="hover:bg-transparent">{children}</TableRow>,
  th: ({ children, style }) => (
    <TableHead style={style} className="h-auto max-w-[24rem] min-w-24 border bg-muted/50 px-2 py-1.5 align-top whitespace-normal">{children}</TableHead>
  ),
  td: ({ children, style }) => (
    <TableCell style={style} className="max-w-[24rem] min-w-24 border px-2 py-1.5 align-top whitespace-normal">{children}</TableCell>
  ),
}

/**
 * @param {string} body        markdown (GFM)
 * @param {Array}  images      notun görsel listesi ({id, file_name, width, height, …}) — yalnız bunlar çizilir
 * @param {number} headingBase en üst başlığın düzeyi (varsayılan 3; kart içinde 4)
 */
function ChannelNoteView({ body, images, headingBase = 3, className }) {
  const [zoom, setZoom] = useState(null)
  const returnFocusRef = useRef(null)
  const byId = useMemo(() => imagesById(images), [images])
  const onZoom = useCallback((img, ref) => {
    returnFocusRef.current = ref?.current || null
    setZoom(img)
  }, [])
  const ctx = useMemo(() => ({ images: byId, headingBase, onZoom }), [byId, headingBase, onZoom])
  return (
    <div data-slot="te-note-view"
      className={cn('flex min-w-0 flex-col gap-3 text-sm text-foreground [overflow-wrap:anywhere]', className)}>
      <NoteViewContext.Provider value={ctx}>
        <ReactMarkdown remarkPlugins={REMARK_PLUGINS} components={COMPONENTS} urlTransform={noteUrlTransform}>
          {typeof body === 'string' ? body : ''}
        </ReactMarkdown>
      </NoteViewContext.Provider>
      <ImageLightbox open={!!zoom} onOpenChange={(o) => { if (!o) setZoom(null) }} src={zoom?.src} label={zoom?.label || ''}
        width={zoom?.width} height={zoom?.height} returnFocusRef={returnFocusRef} />
    </div>
  )
}

export default memo(ChannelNoteView)
