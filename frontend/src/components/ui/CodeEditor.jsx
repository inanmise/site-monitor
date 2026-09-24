import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Editor from 'react-simple-code-editor'
import { highlight, languages } from 'prismjs/components/prism-core'
import 'prismjs/components/prism-clike'
import 'prismjs/components/prism-javascript'
import 'prismjs/themes/prism.css'

/** Kod yüzeyi ölçüleri — CSS'teki `--code-*` değişkenleriyle AYNI olmak zorunda. */
const PAD = 12
const GUTTER_W = 44

/**
 * Hafif JavaScript kod editörü (react-simple-code-editor + prismjs) — k6 scriptleri için syntax
 * highlight. Kendi (açık) kod arka planına sahiptir; uygulama teması ne olursa olsun okunaklı kalır.
 * Monaco/CodeMirror gibi ağır bağımlılık YOK.
 *
 * `textareaId` parametrik: eskiden sabitti ve aynı ekranda ikinci bir editör (sürüm önizlemesi)
 * render edilince DOM'da id çakışması oluyordu.
 *
 * ── SATIR CETVELİ ────────────────────────────────────────────────────────────────────────────
 * k6 hataları satır/sütun ile gelir ("Unexpected token (46:29)", "script:34:12"); cetvel olmadan
 * kullanıcı o satırı elle sayıyordu.
 *
 * KÜTÜPHANE SÖZLEŞMESİ: şeffaf metinli bir `textarea`, boyalı bir `pre`'nin ÜSTÜNE bindirilir;
 * ikisinin sarma ve genişlik davranışı birebir aynı olmak zorundadır (README: "changing anything
 * that affects the layout can misalign it"). İlk denemede sarmayı `white-space: pre` ile
 * kapatmıştım: `pre` container'ın `overflow:hidden`'ı tarafından kırpıldı, textarea ise imleci
 * görünür tutmak için KENDİ İÇİNDE kaydı — kullanıcının gördüğü metin (`pre`) hiç değişmedi,
 * yani editör "yazmıyor" gibi davrandı.
 *
 * BU KURGUDA HİÇBİR YERLEŞİM STİLİ EZİLMEZ. `!important` sayısı: 0. Cetvel boşluğu kütüphanenin
 * KENDİ API'siyle açılır: `padding` prop'u NESNE kabul ediyor ve dört değeri hem `pre`'ye hem
 * `textarea`'ya aynı satır-içi stille yazıyor (lib/index.js:74-78, :397, :400). Yani iki katman
 * atomik olarak birlikte sola boşluk alır; ayrışmaları yapısal olarak imkânsız.
 *
 * HİZALAMA ÖLÇÜMLE DEĞİL, TANIM GEREĞİ doğrudur: cetvelin her satırı, o satırın metninin
 * `visibility:hidden` bir kopyasını ("hayalet") taşır. Hayalet, `pre` ile aynı genişlikte ve aynı
 * `white-space:pre-wrap` kurallarıyla aktığı için aynı yerlerden sarar → satır kutusunun yüksekliği
 * o satırın gerçek sarılmış yüksekliğine eşittir. Sarma AÇIK kalır (şablonlarda 80+ karakterlik
 * 118 satır var; yatay kaydırma okumayı bozardı), ResizeObserver ve ölçüm döngüsü yoktur.
 *
 * Yerleşim jsdom'da doğrulanamaz (düzen hesaplanmaz) → gerçek Chromium testleri:
 * `frontend/e2e/code-editor.spec.js`.
 *
 * @param markers `[{line, type, message}]` — cetvelde işaretlenecek satırlar (k6 hata satırı).
 * @param revealMarkers ilk hata satırını görünür alana kaydır.
 * @param showLineNumbers GERİ ALMA ANAHTARI: false ⇒ bileşen bugünküyle BİREBİR aynı DOM'u üretir.
 */
export default function CodeEditor({ value, onChange, placeholder, readOnly = false, minHeight = 280,
                                     textareaId = 'k6-script-editor', showLineNumbers = true,
                                     markers = [], revealMarkers = false }) {
  const wrapRef = useRef(null)
  const [active, setActive] = useState([0, 0])   // 1-tabanlı [ilk, son] vurgulu satır

  const code = value || ''
  const lines = useMemo(() => code.split('\n'), [code])

  const textarea = useCallback(() => wrapRef.current?.querySelector('textarea'), [])

  const syncSelection = useCallback(() => {
    const ta = textarea()
    if (!ta) return
    const a = ta.selectionStart ?? 0
    const b = ta.selectionEnd ?? a
    const start = Math.min(a, b), end = Math.max(a, b)   // ters (aşağıdan yukarı) seçim
    const lineOf = (idx) => {
      let n = 1
      for (let i = 0; i < idx && i < ta.value.length; i++) if (ta.value[i] === '\n') n++
      return n
    }
    setActive([lineOf(start), lineOf(end)])
  }, [textarea])

  useEffect(() => {
    if (!showLineNumbers) return
    // Sürükleyerek seçimde mouseup textarea DIŞINDA olabiliyor; belge düzeyinde yakalanmazsa
    // vurgu yarım kalır. `activeElement` kontrolü ŞART: sayfada iki editör var (sürüm önizlemesi
    // + düzenleme) ve biri diğerinin vurgusunu bozmamalı.
    const onSel = () => { if (document.activeElement === textarea()) syncSelection() }
    document.addEventListener('selectionchange', onSel)
    return () => document.removeEventListener('selectionchange', onSel)
  }, [showLineNumbers, syncSelection, textarea])

  /** Numaraya tıklama: o satırın tamamını seçer (IDE davranışı). */
  const selectLine = useCallback((n) => {
    const ta = textarea()
    if (!ta) return
    let start = 0
    for (let i = 0; i < n - 1; i++) start += lines[i].length + 1
    ta.focus()
    ta.setSelectionRange(start, start + (lines[n - 1]?.length ?? 0))
    syncSelection()
  }, [lines, syncSelection, textarea])

  const markerByLine = useMemo(() => {
    const m = new Map()
    for (const k of markers || []) if (k?.line > 0 && !m.has(k.line)) m.set(k.line, k)
    return m
  }, [markers])

  // İşaretli satıra kaydır — hata görünür alanın dışındaysa kullanıcı onu aramasın.
  // Yalnız marker kümesi DEĞİŞTİĞİNDE çalışır; her render'da kaydırma çalmaz.
  const firstMarker = markers?.[0]?.line || 0
  useEffect(() => {
    if (!showLineNumbers || !revealMarkers || !firstMarker) return
    const wrap = wrapRef.current
    const row = wrap?.querySelector(`.cg-row[data-line="${firstMarker}"]`)
    if (!wrap || !row) return
    wrap.scrollTop = Math.max(0, row.offsetTop - wrap.clientHeight / 3)
  }, [showLineNumbers, revealMarkers, firstMarker])

  const [first, last] = active

  return (
    <div className={`code-editor-wrap${showLineNumbers ? '' : ' code-editor-wrap--plain'}`}
         ref={wrapRef}>
      {showLineNumbers && (
        // aria-hidden: hayalet metin ekran okuyucuya script'i İKİNCİ kez okutmasın.
        // DİKKAT: bu düğüme ASLA `z-index` verme — verilirse `.cg-num` kendi yığın bağlamına
        // hapsolur, textarea'nın altında kalır ve numaraya tıklama çalışmaz.
        <div className="cg" aria-hidden="true">
          {lines.map((text, i) => {
            const n = i + 1
            const on = first > 0 && n >= first && n <= last
            const mk = markerByLine.get(n)
            return (
              <div key={n} data-line={n}
                   className={`cg-row${on ? ' cg-row--active' : ''}${mk ? ' cg-row--error' : ''}`}>
                <span className="cg-num" data-line={n} title={mk?.message}
                      onClick={() => selectLine(n)}>{n}</span>
                {/* Hayalet: yer kaplar, görünmez. Boş satır da bir satır kutusu üretmeli. */}
                <span className="cg-ghost">{text === '' ? ' ' : text}</span>
              </div>
            )
          })}
        </div>
      )}

      <Editor
        value={code}
        onValueChange={(c) => onChange && onChange(c)}
        highlight={(c) => highlight(c, languages.javascript, 'javascript')}
        // NESNE padding: cetvel boşluğunu pre VE textarea'ya atomik olarak açar (bkz. sınıf yorumu).
        padding={showLineNumbers ? { top: PAD, right: PAD, bottom: PAD, left: PAD + GUTTER_W } : PAD}
        readOnly={readOnly}
        placeholder={placeholder}
        textareaId={textareaId}
        spellCheck={false}
        onClick={showLineNumbers ? syncSelection : undefined}
        onKeyUp={showLineNumbers ? syncSelection : undefined}
        onFocus={showLineNumbers ? syncSelection : undefined}
        onBlur={showLineNumbers ? () => setActive([0, 0]) : undefined}
        // FONT BURADA YOK — tek kaynak `.code-editor-wrap` (App.css). pre/textarea kütüphanede
        // `inherit`; hayalet de aynı zincirden miras alır. Buraya font yazılırsa iki zincir
        // ayrışır ve sarma noktaları kayar.
        style={{ minHeight, color: '#27272a', caretColor: '#111' }}
      />
    </div>
  )
}
