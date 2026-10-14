import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { Bold, Eye, IndentDecrease, IndentIncrease, List } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import HelpTip from '../ui/HelpTip.jsx'
import { Button } from '@/components/shadcn/button'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
import { parseRichText } from './techErrorModel.js'
import { indentLines, toggleBold, toggleBullets } from './richTextEdit.js'

const LEVEL_PAD = ['ps-0', 'ps-5', 'ps-10']
const LEVEL_MARK = ['•', '–', '·']

/**
 * Düz blok listesini iç içe listelere çevirir (madde düzeyi = iç içelik). Paragraf ve aralık blokları olduğu gibi
 * kalır. Blok sayısı TechErrorRichText.MAX_BLOCKS, düzey 3 ile sınırlı — özyineleme derinliği en çok 3.
 */
export function richTree(blocks) {
  const out = []
  let i = 0
  while (i < blocks.length) {
    const b = blocks[i]
    if (b.kind !== 'li') { out.push({ type: 'block', block: b }); i++; continue }
    const [list, next] = readList(blocks, i, b.level)
    out.push(list)
    i = next
  }
  return out
}

function readList(blocks, start, level) {
  const items = []
  let i = start
  while (i < blocks.length && blocks[i].kind === 'li' && blocks[i].level >= level) {
    const b = blocks[i]
    if (b.level > level) {
      const [sub, next] = readList(blocks, i, b.level)
      if (items.length) items[items.length - 1].children.push(sub)
      else items.push({ block: null, children: [sub] })
      i = next
    } else {
      items.push({ block: b, children: [] })
      i++
    }
  }
  return [{ type: 'list', level, numbered: !!items[0]?.block?.marker, items }, i]
}

function Runs({ runs }) {
  return runs.map((r, j) => (r.bold
    ? <strong key={j} className="font-semibold text-foreground">{r.text}</strong>
    : <Fragment key={j}>{r.text}</Fragment>))
}

/**
 * `indent`: listenin kendi girintisi (düzey farkı). Üst düzey liste kendi düzeyi kadar, alt liste üstüyle arasındaki
 * düzey farkı kadar içeriden başlar — PDF ve e-posta da düzey × sabit girinti çizer (inceleme 2026-10-14: düz satırdan
 * sonra gelen alt madde ekranda girintisiz, PDF'te girintiliydi; 0 → 2 sıçrayışı tek düzey girinti alıyordu).
 */
function ListNode({ node, indent = 0 }) {
  const Tag = node.numbered ? 'ol' : 'ul'
  return (
    <Tag role="list" className={cn('m-0 flex list-none flex-col gap-1 p-0', LEVEL_PAD[indent] || LEVEL_PAD[LEVEL_PAD.length - 1],
      node.level > 0 && 'mt-1')} data-level={node.level}>
      {node.items.map((it, k) => (
        <li key={k} className="flex min-w-0 flex-col" data-slot="te-rich-item" data-level={node.level}>
          {it.block && (
            <span className="flex gap-2">
              {/* Numara okunur kalır (adım sırası anlam taşır); madde işareti süstür */}
              {it.block.marker
                ? <span className="shrink-0 tabular-nums text-muted-foreground">{it.block.marker}</span>
                : <span aria-hidden="true" className="shrink-0 text-muted-foreground">{LEVEL_MARK[node.level] || LEVEL_MARK[0]}</span>}
              <span className="min-w-0"><Runs runs={it.block.runs} /></span>
            </span>
          )}
          {it.children.map((c, ci) => <ListNode key={ci} node={c} indent={c.level - node.level} />)}
        </li>
      ))}
    </Tag>
  )
}

/**
 * Zengin metnin GÖRÜNÜMÜ (2026-10-14): paragraf satırları, madde / alt madde (gerçek iç içe liste), numaralı madde,
 * kalın. PDF ve e-posta aynı ayrıştırıcıyla çizer (TechErrorRichText) — ekranda görülen, gönderilenle aynıdır. HTML
 * yoktur: metin React düğümü olarak basılır (kaçış gerekmez).
 */
export function RichText({ text, className, slot = 'te-rich' }) {
  const tree = useMemo(() => richTree(parseRichText(text)), [text])
  return (
    <div className={cn('flex min-w-0 flex-col gap-1 leading-relaxed [overflow-wrap:anywhere]', className)} data-slot={slot}>
      {tree.map((n, i) => {
        if (n.type === 'list') return <ListNode key={i} node={n} indent={n.level} />
        const b = n.block
        if (b.kind === 'gap') return <div key={i} aria-hidden="true" className="h-2" />
        return <p key={i} className={cn('m-0', LEVEL_PAD[b.level] || LEVEL_PAD[0])}><Runs runs={b.runs} /></p>
      })}
    </div>
  )
}

/** İki metnin ortak başı ve ortak sonu (karakter sayısı; örtüşmez) — yalnız aradaki parça değişmiştir. */
export function changedSpan(a, b) {
  const max = Math.min(a.length, b.length)
  let p = 0
  while (p < max && a[p] === b[p]) p++
  let q = 0
  while (q < max - p && a[a.length - 1 - q] === b[b.length - 1 - q]) q++
  return [p, q]
}

function isMac() {
  try {
    return /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '')
  } catch {
    return false
  }
}

/**
 * Biçimli açıklama girişi: araç çubuğu (Kalın, Madde, Alt madde, Girintiyi azalt) + Önizle + biçim yardımı. İşaretleme
 * düz metindir (`**kalın**`, `- madde`, iki boşluk + `- ` alt madde) — kullanıcı elle de yazabilir; düğmeler yalnız
 * kolaylıktır. Ctrl/⌘+B seçili metni kalın yapar.
 *
 * <p>İnceleme 2026-10-14: önizlemede metin alanı DOM'da kalır (gizli) — etiketler, `aria-controls` ve hata bağları
 * kopmaz; araç çubuğu tek sekme durağıdır (← → ile gezilir); düğme işlemleri tarayıcının geri al geçmişine girer
 * (`insertText`, desteklenmezse doğrudan değer); salt okunurda işaretleme değil işlenmiş metin gösterilir.
 */
export function RichTextarea({ id, value, onChange, disabled, rows = 4, maxLength, describedBy, invalid, placeholder, label, autoFocus }) {
  const t = useT()
  const ref = useRef(null)
  const [preview, setPreview] = useState(false)
  const [notice, setNotice] = useState('')
  const [active, setActive] = useState(0)
  const text = value ?? ''

  useEffect(() => {
    if (autoFocus && !disabled) ref.current?.focus()
    // yalnız ilk çizimde
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  // Değer dışarıdan değişince (kayıt yanıtı, taslak geri yükleme) "biçim uygulanmadı" notu eskir
  useEffect(() => { setNotice('') }, [value])
  // Alana hata geldiyse önizlemeden çıkılır: gizli metin kutusuna odaklanılamaz, hata görülemezdi
  useEffect(() => { if (invalid) setPreview(false) }, [invalid])

  if (disabled) {
    // Salt okunur (yetkisi olmayan izleyici): ham işaretleme değil, PDF'teki gibi işlenmiş metin
    return (
      <div id={id} role="group" aria-label={label || undefined} aria-describedby={describedBy}
        className="min-h-10 rounded-md border bg-muted/30 px-3 py-2 text-sm" data-slot="te-rich-readonly">
        {text.trim() ? <RichText text={text} /> : <p className="m-0 text-muted-foreground">—</p>}
      </div>
    )
  }

  const apply = (fn) => {
    const el = ref.current
    const start = el ? el.selectionStart : text.length
    const end = el ? el.selectionEnd : text.length
    const next = fn(text, start, end)
    if (maxLength && next.value.length > maxLength) {
      setNotice(t('te.rich.tooLong'))
      return
    }
    setNotice('')
    if (next.value === text) return
    let viaUndo = false
    if (el && typeof document !== 'undefined' && typeof document.execCommand === 'function') {
      try {
        // Tarayıcının geri al (Ctrl+Z) geçmişine girsin: değeri yazarak değiştir; React input olayından onChange alır.
        // Yalnız DEĞİŞEN parça seçilip yazılır (ortak baş / son korunur): bütün metin seçilip yazılınca geri al tüm
        // metni seçili bırakıyor, sonraki tuş açıklamanın tamamını siliyordu (inceleme 2026-10-14)
        const [a, b] = changedSpan(text, next.value)
        el.focus()
        el.setSelectionRange(a, text.length - b)
        const middle = next.value.slice(a, next.value.length - b)
        const ok = middle === '' ? document.execCommand('delete', false) : document.execCommand('insertText', false, middle)
        viaUndo = ok === true && el.value === next.value
      } catch {
        viaUndo = false
      }
    }
    if (!viaUndo) onChange(next.value)
    requestAnimationFrame(() => {
      const node = ref.current
      if (!node) return
      node.focus()
      try { node.setSelectionRange(next.start, next.end) } catch { /* jsdom / gizli alan */ }
    })
  }

  const shortcut = isMac() ? '⌘B' : 'Ctrl+B'
  const tools = [
    { key: 'bold', icon: Bold, label: t('te.rich.bold', shortcut), run: () => apply(toggleBold) },
    { key: 'bullet', icon: List, label: t('te.rich.bullet'), run: () => apply(toggleBullets) },
    { key: 'indent', icon: IndentIncrease, label: t('te.rich.indent'), run: () => apply((v, s, e) => indentLines(v, s, e, 1)) },
    { key: 'outdent', icon: IndentDecrease, label: t('te.rich.outdent'), run: () => apply((v, s, e) => indentLines(v, s, e, -1)) },
  ]
  const count = tools.length + 1     // + Önizle
  // Önizlemede biçim düğmeleri kapalı: tek sekme durağı Önizle düğmesidir
  const stop = preview ? count - 1 : active
  const focusTool = (i) => {
    const n = preview ? count - 1 : ((i % count) + count) % count
    setActive(n)
    const bar = ref.current?.closest('[data-slot="te-rich-input"]')?.querySelectorAll('[data-tool]')
    bar?.[n]?.focus()
  }
  const onToolKey = (e) => {
    if (e.key === 'ArrowRight') { e.preventDefault(); focusTool(active + 1) }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); focusTool(active - 1) }
    else if (e.key === 'Home') { e.preventDefault(); focusTool(0) }
    else if (e.key === 'End') { e.preventDefault(); focusTool(count - 1) }
  }

  return (
    <div className="flex min-w-0 flex-col gap-1.5" data-slot="te-rich-input">
      <div className="flex flex-wrap items-center gap-1">
        <div role="toolbar" aria-label={t('te.rich.toolbar', label || '')} aria-controls={id} onKeyDown={onToolKey}
          className="flex flex-wrap items-center gap-1">
          {tools.map(({ key, icon: Icon, label: l, run }, i) => (
            <Button key={key} type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10"
              aria-label={l} title={l} disabled={preview} onClick={() => { setActive(i); run() }} data-tool={key}
              tabIndex={stop === i ? 0 : -1}>
              <Icon aria-hidden="true" />
            </Button>
          ))}
          <span className="mx-1 h-5 w-px bg-border" aria-hidden="true" />
          <Button type="button" size="sm" variant={preview ? 'secondary' : 'ghost'} className="h-8 px-2 pointer-coarse:min-h-10"
            aria-pressed={preview} onClick={() => { setActive(count - 1); setPreview((p) => !p) }} data-tool="preview"
            tabIndex={stop === count - 1 ? 0 : -1}>
            <Eye aria-hidden="true" /> {t('te.rich.preview')}
          </Button>
        </div>
        <HelpTip helpKey="te.rich.hint" label={t('te.rich.helpTitle')} />
      </div>
      {preview && (
        <div className="min-h-16 rounded-md border bg-muted/30 px-3 py-2 text-sm" data-slot="te-rich-preview" aria-live="polite"
          aria-label={t('te.rich.previewOf', label || '')} role="region">
          {text.trim() ? <RichText text={text} /> : <p className="m-0 text-muted-foreground">{t('te.rich.previewEmpty')}</p>}
        </div>
      )}
      {/* Önizlemede de DOM'da kalır (gizli): etiket, aria-controls ve hata bağları kopmasın */}
      <Textarea ref={ref} id={id} rows={rows} value={text} maxLength={maxLength} placeholder={placeholder}
        className={preview ? 'hidden' : undefined}
        aria-describedby={describedBy} aria-invalid={invalid}
        onKeyDown={(e) => {
          // Fiziksel tuş (e.code) YALNIZ tuş Latin harf üretmiyorsa (ör. Rusça düzen) sayılır: Dvorak'ta o tuş "x"tir ve
          // Ctrl+X (kes) kalına dönmemeli
          const latin = typeof e.key === 'string' && /^[a-z]$/i.test(e.key)
          if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.nativeEvent?.isComposing
            && (e.key === 'b' || e.key === 'B' || (!latin && e.code === 'KeyB'))) {
            e.preventDefault()
            apply(toggleBold)
          }
        }}
        onChange={(e) => { if (notice) setNotice(''); onChange(e.target.value) }} />
      <p className="sr-only" role="status" aria-live="polite">{notice}</p>
      {notice && <p className="text-xs text-amber-800 dark:text-amber-300" aria-hidden="true">{notice}</p>}
    </div>
  )
}
