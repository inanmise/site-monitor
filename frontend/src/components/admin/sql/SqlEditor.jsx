import { forwardRef, useCallback, useEffect, useId, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useT } from '../../../i18n/index.jsx'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
import { highlightSql } from './sqlHighlight.jsx'
import { completionContext, completionItems, parseAliases } from './sqlComplete.js'
import { formatSql } from './sqlFormat.js'
import { caretInfo, offsetToLineCol } from './sqlUtils.js'

/** Satır yüksekliği (px) — `leading-6` ile AYNI olmak zorunda (cetvel + etkin satır bandı buna göre çizilir). */
const LINE_H = 24
/** Metin alanı iç boşluğu (px) — `px-3 py-3` ile AYNI (öneri listesinin imleç konumu). */
const PAD = 12
const OPTION_H = 32
const LIST_MAX_ITEMS = 8

/**
 * SQL düzenleyici — shadcn Textarea + sözdizimi boyası + satır cetveli. Bağımlılık yok.
 *
 * <p><b>Katman sözleşmesi</b> (react-simple-code-editor'ın kalıbı, SARMASIZ): metni şeffaf bir `textarea`
 * (gerçek düzenleme, imleç, seçim, geri alma, ekran okuyucu) boyalı bir `pre`'nin ÜSTÜNE biner. İkisi aynı
 * yazı tipi/boyut/satır yüksekliği/dolgu ile `white-space: pre` (sarma KAPALI) çizer; kaydırma, `pre`'nin
 * içindeki `code`'a ve cetvele `transform` ile aktarılır (React yeniden çizimi yok). Sarma kapalı olduğu için
 * her satır tam `LINE_H` yüksekliğindedir → cetvel ölçümsüz, tanım gereği hizalı. Kalın/italik boya YOK
 * (glif genişliği oynamasın). Yerleşim jsdom'da doğrulanamaz — gerçek tarayıcıda (Playwright) denendi.
 *
 * <p>Kısayol: Ctrl/⌘+Enter → `onRun(seçiliMetin | null)` (seçim varsa yalnız seçim koşar).
 * Programatik yazım `document.execCommand('insertText')` ile yapılır → tarayıcının GERİ ALMA yığını korunur
 * (şema gezgininden tablo sorgusu yazmak kullanıcının sorgusunu geri dönülmez biçimde silmesin); komut yoksa
 * (jsdom) `onChange` ile düşer.
 *
 * Ref API: `focus()`, `insert(text)`, `replaceAll(text)`, `select(start, end)`, `selection()`, `format()`.
 *
 * <p><b>Otomatik tamamlama</b> (2026-11-13, B1): `completion` verilirse ({ tables, columnsMap, onNeedColumns }) yazarken
 * tablo / kolon / anahtar sözcük önerileri imlecin altında bir `listbox` olarak açılır (dize / yorum içinde açılmaz;
 * `takma_ad.` sonrası o tablonun kolonları). Metin alanı `textbox` rolünde KALIR (`aria-autocomplete="list"`,
 * `aria-controls`, `aria-expanded`, `aria-activedescendant`); ↑ / ↓ gezinir, Enter / Tab kabul, Esc kapatır,
 * Ctrl+Boşluk zorla açar. Programatik yazım (şema gezgini, örnek) öneri AÇMAZ. Liste katman sözleşmesini bozmaz:
 * `pre` + `textarea` aynen, liste ikisinin ÜSTÜNDE ayrı bir mutlak öğe.
 * <p><b>Biçimlendir</b> (B2): Ctrl/⌘+Shift+F ya da `format()` — `formatSql` (doğrusal; dize / yorum / dolar bloğu
 * korunur), geri alma yığını korunarak yazılır.
 */
const SqlEditor = forwardRef(function SqlEditor({
  value, onChange, onRun, onCaret, placeholder, label, id, className, statusExtra, completion = null, onFormatted,
}, ref) {
  const t = useT()
  const taRef = useRef(null)
  const codeRef = useRef(null)
  const gutterRef = useRef(null)
  const boxRef = useRef(null)
  const measureRef = useRef(null)
  const programmatic = useRef(false)
  const wantCols = useRef(false)
  const listId = useId()
  const [comp, setComp] = useState(null)   // { items, active, start, top, left, maxH, width }
  const compRef = useRef(comp)
  compRef.current = comp
  const [caret, setCaret] = useState({ line: 1, col: 1, selected: 0 })
  const text = value ?? ''
  const lines = useMemo(() => (text.match(/\n/g)?.length ?? 0) + 1, [text])
  const painted = useMemo(() => highlightSql(text), [text])

  const syncScroll = useCallback(() => {
    const ta = taRef.current
    if (!ta) return
    if (codeRef.current) codeRef.current.style.transform = `translate(${-ta.scrollLeft}px, ${-ta.scrollTop}px)`
    if (gutterRef.current) gutterRef.current.style.transform = `translateY(${-ta.scrollTop}px)`
  }, [])

  /** Öneri listesi: bağlam → öneriler → imleç konumu (eş aralıklı yazı: sütun × karakter genişliği). */
  const openCompletion = useCallback((val, caret, { force = false } = {}) => {
    if (!completion) return
    const ctx = completionContext(val, caret, { force })
    if (!ctx) { setComp(null); return }
    const { items, needColumns } = completionItems(ctx, {
      tables: completion.tables || [], columnsMap: completion.columnsMap || {}, aliases: parseAliases(val),
    })
    if (needColumns.length) { wantCols.current = true; completion.onNeedColumns?.(needColumns) }
    if (!items.length) { setComp(null); return }
    const ta = taRef.current
    const box = boxRef.current
    const { line, col } = offsetToLineCol(val, ctx.start)
    const chW = (measureRef.current?.getBoundingClientRect?.().width || 0) / 10
    const sl = ta?.scrollLeft || 0
    const st = ta?.scrollTop || 0
    const w = box?.clientWidth || 0
    const h = box?.clientHeight || 0
    const listW = w ? Math.min(320, w - 8) : 320
    let left = PAD + (col - 1) * chW - sl
    if (w) left = Math.max(4, Math.min(left, w - listW - 4))
    const want = Math.min(items.length, LIST_MAX_ITEMS) * OPTION_H + 8
    const below = PAD + line * LINE_H - st
    const above = PAD + (line - 1) * LINE_H - st
    let top = below
    let maxH = want
    if (h) {
      const spaceBelow = h - below - 4
      const spaceAbove = above - 4
      if (spaceBelow < want && spaceAbove > spaceBelow) {
        maxH = Math.max(OPTION_H * 2, Math.min(want, spaceAbove))
        top = above - maxH
      } else maxH = Math.max(OPTION_H * 2, Math.min(want, spaceBelow))
    }
    setComp((c) => ({
      items, active: c && c.start === ctx.start ? Math.min(c.active, items.length - 1) : 0, start: ctx.start,
      top, left: Math.max(0, left), maxH, width: listW,
    }))
  }, [completion])

  // Kolonlar yüklenince (öneri onları beklerken) liste tazelenir — yalnız düzenleyici odaktaysa
  useEffect(() => {
    if (!wantCols.current) return
    const ta = taRef.current
    if (!ta || document.activeElement !== ta) return
    wantCols.current = false
    openCompletion(ta.value, ta.selectionStart ?? 0)
  }, [completion?.columnsMap, openCompletion])
  useLayoutEffect(syncScroll, [text, syncScroll])

  const updateCaret = useCallback(() => {
    const ta = taRef.current
    if (!ta) return
    const cc = compRef.current
    // İmleç öneri sözcüğünün dışına çıktı ya da seçim yapıldı → liste kapanır
    if (cc && ((ta.selectionStart ?? 0) < cc.start || ta.selectionStart !== ta.selectionEnd)) setComp(null)
    const info = caretInfo(ta.value, ta.selectionStart ?? 0, ta.selectionEnd ?? 0)
    setCaret((c) => (c.line === info.line && c.col === info.col && c.selected === info.selected ? c : info))
    onCaret?.({ ...info, text: ta.value.slice(ta.selectionStart ?? 0, ta.selectionEnd ?? 0) })
  }, [onCaret])

  /** Tarayıcının geri alma yığınını koruyarak [a, b) aralığını değiştirir. */
  const writeRange = useCallback((a, b, insert) => {
    const ta = taRef.current
    if (!ta) return false
    programmatic.current = true
    setComp(null)
    ta.focus()
    let ok = false
    // Düzenleyici gizliyse (telefonda başka sekme) odak alamaz: komut o an odaktaki BAŞKA alana yazardı → durum yolu.
    if (document.activeElement === ta) {
      ta.setSelectionRange(a, b)
      try { ok = document.execCommand?.('insertText', false, insert) === true } catch { ok = false }
    }
    if (!ok) {
      const cur = ta.value
      onChange?.(cur.slice(0, a) + insert + cur.slice(b))
      const pos = a + insert.length
      requestAnimationFrame(() => { ta.setSelectionRange(pos, pos); updateCaret() })
    } else {
      updateCaret()
    }
    programmatic.current = false
    return true
  }, [onChange, updateCaret])

  /** Biçimlendir (B2): metin değişmezse false. */
  const formatAll = useCallback(() => {
    const ta = taRef.current
    if (!ta) return false
    const before = ta.value
    const after = formatSql(before)
    if (after === before) { onFormatted?.(false); return false }
    writeRange(0, before.length, after)
    onFormatted?.(true)
    return true
  }, [writeRange, onFormatted])

  const accept = useCallback((item) => {
    const ta = taRef.current
    const cc = compRef.current
    if (!ta || !cc || !item) return
    writeRange(cc.start, ta.selectionStart ?? cc.start, item.insert)
  }, [writeRange])

  const revealOffset = useCallback((offset) => {
    const ta = taRef.current
    if (!ta) return
    const { line } = offsetToLineCol(ta.value, offset)
    const top = (line - 1) * LINE_H
    if (top < ta.scrollTop || top > ta.scrollTop + ta.clientHeight - LINE_H * 2) {
      ta.scrollTop = Math.max(0, top - ta.clientHeight / 3)
    }
    syncScroll()
  }, [syncScroll])

  useImperativeHandle(ref, () => ({
    focus: () => taRef.current?.focus(),
    insert: (s) => {
      const ta = taRef.current
      if (!ta) return false
      return writeRange(ta.selectionStart ?? ta.value.length, ta.selectionEnd ?? ta.value.length, s)
    },
    replaceAll: (s) => {
      const ta = taRef.current
      if (!ta) return false
      const ok = writeRange(0, ta.value.length, s)
      ta.scrollTop = 0
      ta.scrollLeft = 0
      syncScroll()
      return ok
    },
    select: (a, b) => {
      const ta = taRef.current
      if (!ta) return false
      ta.focus()
      ta.setSelectionRange(a, b)
      revealOffset(a)
      updateCaret()
      return true
    },
    selection: () => {
      const ta = taRef.current
      if (!ta) return { start: 0, end: 0, text: '' }
      return { start: ta.selectionStart, end: ta.selectionEnd, text: ta.value.slice(ta.selectionStart, ta.selectionEnd) }
    },
    format: formatAll,
  }), [writeRange, revealOffset, updateCaret, syncScroll, formatAll])

  const onKeyDown = (e) => {
    const cc = compRef.current
    if (cc && !e.ctrlKey && !e.metaKey && !e.altKey) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        const d = e.key === 'ArrowDown' ? 1 : -1
        setComp((x) => (x ? { ...x, active: (x.active + d + x.items.length) % x.items.length } : x))
        return
      }
      if ((e.key === 'Enter' || e.key === 'Tab') && !e.shiftKey) {
        e.preventDefault()
        accept(cc.items[cc.active])
        return
      }
      if (e.key === 'Escape') {
        e.preventDefault()
        e.stopPropagation()
        setComp(null)
        return
      }
      if (['ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageUp', 'PageDown'].includes(e.key)) setComp(null)
    }
    if ((e.ctrlKey || e.metaKey) && (e.key === ' ' || e.code === 'Space') && completion) {
      e.preventDefault()
      const ta = e.currentTarget
      openCompletion(ta.value, ta.selectionStart ?? 0, { force: true })
      return
    }
    if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'f' || e.key === 'F')) {
      e.preventDefault()
      formatAll()
      return
    }
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      setComp(null)
      e.preventDefault()
      const ta = e.currentTarget
      const sel = ta.value.slice(ta.selectionStart, ta.selectionEnd)
      onRun?.(sel.trim() ? sel : null)
    }
  }

  const TYPE = 'font-mono text-base leading-6 md:text-sm md:leading-6 [font-variant-ligatures:none] [tab-size:4]'

  return (
    <div data-slot="sql-editor" className={cn('flex min-h-0 flex-col bg-background', className)}>
      <div className="flex min-h-0 flex-1 overflow-hidden focus-within:ring-2 focus-within:ring-ring/40 focus-within:ring-inset">
        {/* Satır cetveli: dekoratif (numaralar ekran okuyucuya ikinci kez okunmasın) */}
        <div aria-hidden="true" data-slot="sql-editor-gutter"
          className="shrink-0 overflow-hidden border-r bg-muted/40 text-right text-muted-foreground/80 select-none"
          style={{ width: `${Math.max(2, String(lines).length) + 2.5}ch` }}>
          <div ref={gutterRef} className={cn(TYPE, 'py-3 pr-2 tabular-nums')}>
            {Array.from({ length: lines }, (_, i) => (
              <div key={i} data-active={i + 1 === caret.line ? 'true' : undefined}
                className="h-6 data-[active]:font-semibold data-[active]:text-foreground">{i + 1}</div>
            ))}
          </div>
        </div>
        <div ref={boxRef} className="relative min-w-0 flex-1 overflow-hidden">
          {/* Karakter genişliği ölçüsü (öneri listesinin yatay konumu) — görünmez, 10 karakter */}
          <span ref={measureRef} aria-hidden="true" className={cn(TYPE, 'pointer-events-none invisible absolute top-0 left-0 whitespace-pre')}>0000000000</span>
          <pre aria-hidden="true" data-slot="sql-editor-paint"
            className={cn(TYPE, 'pointer-events-none absolute inset-0 m-0 overflow-hidden px-3 py-3 whitespace-pre text-foreground')}>
            <code ref={codeRef} className="relative block w-max min-w-full">
              {text && (
                <span aria-hidden="true" className="absolute inset-x-0 h-6 rounded-sm bg-muted/60"
                  style={{ top: (caret.line - 1) * LINE_H }} />
              )}
              <span className="relative">{painted}{'\n'}</span>
            </code>
          </pre>
          <Textarea
            ref={taRef}
            id={id}
            aria-label={label}
            value={text}
            placeholder={placeholder}
            wrap="off"
            spellCheck={false}
            autoCapitalize="off"
            autoCorrect="off"
            autoComplete="off"
            aria-autocomplete={completion ? 'list' : undefined}
            aria-controls={comp ? listId : undefined}
            aria-expanded={completion ? Boolean(comp) : undefined}
            aria-activedescendant={comp ? `${listId}-o${comp.active}` : undefined}
            onChange={(e) => {
              const v = e.target.value
              onChange?.(v)
              if (!completion || programmatic.current) return
              const kind = e.nativeEvent?.inputType || ''
              const caret = e.target.selectionStart ?? v.length
              // Yazarken (ekleme) açılır; silerken yalnız açıksa tazelenir
              if (!kind || kind.startsWith('insert')) openCompletion(v, caret)
              else if (compRef.current) openCompletion(v, caret)
            }}
            onKeyDown={onKeyDown}
            onScroll={() => { syncScroll(); if (compRef.current) setComp(null) }}
            onSelect={updateCaret}
            onKeyUp={updateCaret}
            onClick={updateCaret}
            onBlur={() => { if (compRef.current) setComp(null) }}
            className={cn(TYPE,
              'absolute inset-0 size-full min-h-0 resize-none overflow-auto rounded-none border-0 bg-transparent px-3 py-3 whitespace-pre text-transparent caret-foreground shadow-none field-sizing-fixed',
              'selection:bg-primary/25 selection:text-transparent focus-visible:ring-0 dark:bg-transparent')}
          />
          {comp && (
            <div id={listId} role="listbox" aria-label={t('sql.ac.label')} data-slot="sql-completion"
              style={{ top: comp.top, left: comp.left, maxHeight: comp.maxH, width: comp.width }}
              className="absolute z-20 max-w-[calc(100%-0.5rem)] overflow-y-auto overscroll-contain rounded-md border bg-popover p-1 text-popover-foreground shadow-md">
              {comp.items.map((it, i) => (
                <div role="option" key={`${it.kind}:${it.label}`} id={`${listId}-o${i}`} aria-selected={i === comp.active}
                  data-kind={it.kind}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => accept(it)}
                  onMouseMove={() => { if (i !== comp.active) setComp((x) => (x ? { ...x, active: i } : x)) }}
                  className="flex min-h-8 cursor-pointer items-center gap-2 rounded-sm px-2 py-1 text-sm aria-selected:bg-accent aria-selected:text-accent-foreground pointer-coarse:min-h-10">
                  <span className="min-w-0 flex-1 font-mono text-[13px] [overflow-wrap:anywhere]">{it.label}</span>
                  <span className="shrink-0 text-[10px] tracking-wide text-muted-foreground uppercase">{t(`sql.ac.kind.${it.kind}`)}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
      <div data-slot="sql-editor-status"
        className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-0.5 border-t bg-muted/30 px-3 py-1.5 text-xs text-muted-foreground">
        <span className="tabular-nums" aria-live="off">{t('sql.ed.caret', caret.line, caret.col)}</span>
        {caret.selected > 0 && <span className="tabular-nums text-foreground">{t('sql.ed.selected', caret.selected)}</span>}
        <span className="tabular-nums">{t('sql.ed.chars', text.length.toLocaleString())}</span>
        {statusExtra && <span className="min-w-0 sm:ml-auto">{statusExtra}</span>}
      </div>
    </div>
  )
})

export default SqlEditor
