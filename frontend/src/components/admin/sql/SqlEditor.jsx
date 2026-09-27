import { forwardRef, useCallback, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useT } from '../../../i18n/index.jsx'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
import { highlightSql } from './sqlHighlight.jsx'
import { caretInfo, offsetToLineCol } from './sqlUtils.js'

/** Satır yüksekliği (px) — `leading-6` ile AYNI olmak zorunda (cetvel + etkin satır bandı buna göre çizilir). */
const LINE_H = 24

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
 * Ref API: `focus()`, `insert(text)`, `replaceAll(text)`, `select(start, end)`, `selection()`.
 */
const SqlEditor = forwardRef(function SqlEditor({
  value, onChange, onRun, onCaret, placeholder, label, id, className, statusExtra,
}, ref) {
  const t = useT()
  const taRef = useRef(null)
  const codeRef = useRef(null)
  const gutterRef = useRef(null)
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
  useLayoutEffect(syncScroll, [text, syncScroll])

  const updateCaret = useCallback(() => {
    const ta = taRef.current
    if (!ta) return
    const info = caretInfo(ta.value, ta.selectionStart ?? 0, ta.selectionEnd ?? 0)
    setCaret((c) => (c.line === info.line && c.col === info.col && c.selected === info.selected ? c : info))
    onCaret?.({ ...info, text: ta.value.slice(ta.selectionStart ?? 0, ta.selectionEnd ?? 0) })
  }, [onCaret])

  /** Tarayıcının geri alma yığınını koruyarak [a, b) aralığını değiştirir. */
  const writeRange = useCallback((a, b, insert) => {
    const ta = taRef.current
    if (!ta) return false
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
    return true
  }, [onChange, updateCaret])

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
  }), [writeRange, revealOffset, updateCaret, syncScroll])

  const onKeyDown = (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
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
        <div className="relative min-w-0 flex-1 overflow-hidden">
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
            onChange={(e) => onChange?.(e.target.value)}
            onKeyDown={onKeyDown}
            onScroll={syncScroll}
            onSelect={updateCaret}
            onKeyUp={updateCaret}
            onClick={updateCaret}
            className={cn(TYPE,
              'absolute inset-0 size-full min-h-0 resize-none overflow-auto rounded-none border-0 bg-transparent px-3 py-3 whitespace-pre text-transparent caret-foreground shadow-none field-sizing-fixed',
              'selection:bg-primary/25 selection:text-transparent focus-visible:ring-0 dark:bg-transparent')}
          />
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
