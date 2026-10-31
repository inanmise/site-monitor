import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { ShieldCheck, ShieldAlert, ShieldQuestion, Table2, Info, AlertOctagon, RotateCw } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Textarea } from '@/components/shadcn/textarea'
import { Label } from '@/components/shadcn/label'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import AlertBanner from '../ui/AlertBanner.jsx'
import { FieldError } from '../ui/Field.jsx'
import { Spinner } from '../ui/Progress.jsx'
import {
  MAX_SQL_CHARS, indent, isGuardTimeout, isKnownGuardCode, lineCount, newlineWithIndent, offsetOf,
} from './sqlEditorModel.js'

/** Canlı denetim bekleme süresi (ms) — yazarken her tuşta istek gitmesin. */
export const SQL_CHECK_DEBOUNCE_MS = 400

/** Satır cetveli ve metin AYNI yazı / satır yüksekliğini taşır (hizalama tanım gereği). Telefonda 16 px (iOS yakınlaştırmasın). */
const MONO = 'font-mono text-base leading-6 md:text-[13px] md:leading-5'

const MSSQL_EXAMPLE = [
  'SELECT o.id, o.status, o.created_at',
  'FROM dbo.Orders o WITH (NOLOCK)',
  'JOIN dbo.Customers c WITH (NOLOCK) ON c.id = o.customer_id',
  "WHERE o.status = 'FAILED'",
].join('\n')

/** İhlal listesinin içerik anahtarı (aynı ihlaller yeni dizide gelirse durum değişmesin). */
const vKey = (list) => (list || []).map((v) => `${v?.code}:${v?.line}:${v?.column}:${v?.token}`).join('|')

/** Sözlükte yoksa yedek anahtarın metni (bilinmeyen ihlal kodu → genel metin; ham anahtar basılmaz). */
function tOr(t, key, fallback) {
  const s = t(key)
  return s === key ? t(fallback) : s
}

/**
 * SQL düzenleyicisi (Veritabanı Sorgu İzleme, 2026-10-31) — satır numaralı, monospace, textarea tabanlı (ağır editör
 * bağımlılığı yok). Canlı güvenlik denetimi: değer / DB türü değişince 400 ms sonra `POST /dbquery/sql-check`; eski istek
 * iptal edilir (AbortController) ve geç yanıt sıra numarasıyla düşürülür. Durumlar:
 *   • empty     — sorgu boş (nötr)
 *   • checking  — denetleniyor (nötr)
 *   • ok        — YALNIZ sunucu `ok: true` derse yeşil "güvenlik denetiminden geçti"
 *   • rejected  — ihlaller (satır:sütun; tıklayınca o satıra odak)
 *   • unknown   — ağ / sunucu hatası ya da sunucunun ayrıştırma zaman sınırı (`SQL_UNPARSABLE` + belirteç `TIMEOUT`,
 *                 `isGuardTimeout`): "denetlenemedi" (nötr — asla "güvenli", asla "reddedildi" değil) + "Yeniden denetle"
 *   • idle      — devre dışı (salt okunur) görünüm: denetim yapılmaz, durum satırı çizilmez
 * Sunucunun kayıtta döndürdüğü ihlaller (`violations` prop, 400 DBQUERY_SQL_REJECTED) değer DEĞİŞENE dek önceliklidir.
 *
 * Klavye: Tab iki boşluk girintiler (çok satırlı seçimde her satırı), Shift+Tab azaltır, Enter bulunulan satırın
 * girintisini korur. Alandan Tab ile çıkmak için önce Esc (sonraki Tab odağı taşır) — ekranda yazılı.
 *
 * `onGuardChange(result)`: `{ status, ok (true | false | null), violations, tables, message, timeout }` — yalnız durum
 * değişince; `timeout: true` = sunucu ayrıştırma süresi doldu (status 'unknown', ok null, violations boş).
 */
export default function SqlEditor({ value, onChange, dbType, fieldError, violations, disabled = false, onGuardChange }) {
  const t = useT()
  const { lang } = useLanguage()
  const id = useId()
  const taRef = useRef(null)
  const gutterRef = useRef(null)
  const pendingSel = useRef(null)
  const escTab = useRef(false)
  const seqRef = useRef(0)
  const [recheck, setRecheck] = useState(0)
  const text = value ?? ''
  const [guard, setGuard] = useState({ status: disabled ? 'idle' : (text.trim() ? 'checking' : 'empty'), violations: [], tables: [], message: null })

  // Sunucunun kayıt yanıtındaki ihlaller: geldikleri andaki değere bağlı (değer değişince canlı denetim devralır)
  const [served, setServed] = useState({ list: [], for: null })
  useEffect(() => {
    // Aynı içerikte yeni dizi (çağıranın `violations={x || []}` yazması) durum DEĞİŞTİRMEZ — yoksa her çizimde döngü
    const has = Array.isArray(violations) && violations.length > 0
    setServed((s) => {
      if (!has) return s.list.length ? { list: [], for: null } : s
      if (s.list === violations || vKey(s.list) === vKey(violations)) return s
      return { list: violations, for: text }
    })
    // yalnız prop değişince — değer bağımlılığı bilinçli dışarıda
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [violations])
  const servedActive = served.list.length > 0 && served.for === text

  // ── Canlı denetim ────────────────────────────────────────────────────────────────────────────────────────────────
  useEffect(() => {
    const seq = ++seqRef.current
    // Salt okunur görünüm: canlı denetim yok, durum da söylenmez (idle) — sunucu kayıtta zaten denetler
    if (disabled) { setGuard((g) => (g.status === 'idle' ? g : { status: 'idle', violations: [], tables: [], message: null })); return undefined }
    if (!text.trim()) { setGuard({ status: 'empty', violations: [], tables: [], message: null }); return undefined }
    setGuard((g) => ({ ...g, status: 'checking' }))
    const ctrl = new AbortController()
    const timer = setTimeout(async () => {
      let next
      try {
        const res = await api.monitoring.dbQuerySqlCheck(text, dbType || null, { signal: ctrl.signal })
        if (seq !== seqRef.current || ctrl.signal.aborted) return
        const d = res?.success ? res.data : null
        if (d && typeof d === 'object') {
          const list = Array.isArray(d.violations) ? d.violations : []
          if (isGuardTimeout(list)) {
            // Ayrıştırma zaman sınırı: sorgu kötü değil, şimdilik denetlenemedi — ileti sunucudan
            next = { status: 'unknown', violations: [], tables: [], message: list[0].message || null, timeout: true }
          } else {
            const status = d.ok === true && list.length === 0 ? 'ok' : (d.ok === false || list.length > 0 ? 'rejected' : 'unknown')
            next = { status, violations: list, tables: Array.isArray(d.tables) ? d.tables : [], message: null }
          }
        } else {
          next = { status: 'unknown', violations: [], tables: [], message: res?.error || res?.message || null }
        }
      } catch (e) {
        if (seq !== seqRef.current || ctrl.signal.aborted) return
        next = { status: 'unknown', violations: [], tables: [], message: e?.message || null }
      }
      setGuard(next)
    }, SQL_CHECK_DEBOUNCE_MS)
    return () => { clearTimeout(timer); ctrl.abort() }
  }, [text, dbType, disabled, recheck])

  const shown = servedActive
    ? (isGuardTimeout(served.list)
        ? { status: 'unknown', violations: [], tables: [], message: served.list[0].message || null, timeout: true }
        : { status: 'rejected', violations: served.list, tables: guard.tables, message: null })
    : guard

  // Üst bileşene durum değişimi (aynı durum tekrar bildirilmez — kayıt düğmesini gereksiz yere yeniden çizdirmez)
  const cbRef = useRef(onGuardChange)
  cbRef.current = onGuardChange
  const lastKey = useRef('')
  useEffect(() => {
    const key = `${shown.status}|${shown.timeout ? 'T' : ''}|${(shown.violations || []).map((v) => `${v.code}:${v.line}:${v.column}`).join(',')}|${(shown.tables || []).join(',')}`
    if (key === lastKey.current) return
    lastKey.current = key
    cbRef.current?.({
      status: shown.status,
      ok: shown.status === 'ok' ? true : (shown.status === 'rejected' ? false : null),
      violations: shown.violations || [],
      tables: shown.tables || [],
      message: shown.message || null,
      timeout: shown.timeout === true,
    })
  }, [shown.status, shown.violations, shown.tables, shown.message, shown.timeout])

  // ── Düzenleme ───────────────────────────────────────────────────────────────────────────────────────────────────
  const lines = useMemo(() => lineCount(text), [text])
  const badLines = useMemo(() => new Set((shown.violations || []).map((v) => Number(v.line)).filter((n) => n > 0)), [shown.violations])

  useLayoutEffect(() => {
    const sel = pendingSel.current
    const ta = taRef.current
    if (!sel || !ta) return
    pendingSel.current = null
    ta.setSelectionRange(sel.start, sel.end)
  }, [text])

  const apply = useCallback((res) => {
    pendingSel.current = { start: res.start, end: res.end }
    onChange?.(res.value)
  }, [onChange])

  const onKeyDown = (e) => {
    if (disabled) return
    if (e.key === 'Escape') { escTab.current = true; return }
    if (e.key !== 'Tab') escTab.current = false
    const ta = e.currentTarget
    if (e.key === 'Tab' && !e.ctrlKey && !e.altKey && !e.metaKey) {
      if (escTab.current) { escTab.current = false; return }   // Esc'ten sonra Tab odağı taşır
      e.preventDefault()
      apply(indent(ta.value, ta.selectionStart, ta.selectionEnd, e.shiftKey))
      return
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey && !e.nativeEvent?.isComposing) {
      e.preventDefault()
      apply(newlineWithIndent(ta.value, ta.selectionStart, ta.selectionEnd))
    }
  }

  const syncScroll = () => {
    const ta = taRef.current
    if (ta && gutterRef.current) gutterRef.current.scrollTop = ta.scrollTop
  }

  /** İhlal satırına git: imleç satır:sütuna, satır görünür alanın üçte birine. */
  const goTo = (v) => {
    const ta = taRef.current
    if (!ta) return
    const pos = offsetOf(text, v.line || 1, v.column || 1)
    ta.focus()
    ta.setSelectionRange(pos, pos)
    const lh = parseFloat(getComputedStyle(ta).lineHeight) || 20
    ta.scrollTop = Math.max(0, ((Number(v.line) || 1) - 1) * lh - ta.clientHeight / 3)
    syncScroll()
  }

  const over = text.length > MAX_SQL_CHARS
  const counterId = `${id}-count`
  const hintId = `${id}-hint`
  const errId = fieldError ? `${id}-err` : null
  const statusId = `${id}-status`
  const fmt = (n) => new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR').format(n)

  return (
    <div data-slot="dbq-sql-editor" data-field="sql_text" data-guard={shown.status} className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-end justify-between gap-x-3 gap-y-1">
        <Label htmlFor={id} className="gap-1 font-semibold">
          {t('dbq.sql.label')} <span data-slot="field-required" className="text-destructive">*</span>
        </Label>
        <span id={counterId} data-slot="dbq-sql-count" data-over={over ? 'true' : undefined}
          className={cn('text-xs tabular-nums', over ? 'font-semibold text-destructive' : 'text-muted-foreground')}>
          {t('dbq.sql.count', fmt(text.length), fmt(MAX_SQL_CHARS))}
        </span>
      </div>

      {/* Kural özeti — kullanıcı neyin kabul edildiğini yazmadan önce görsün */}
      <ul data-slot="dbq-sql-rules" aria-label={t('dbq.sql.rulesLabel')} className="m-0 flex list-none flex-wrap gap-1.5 p-0">
        {['selectOnly', 'noDdl', 'noLock', 'builtinOnly', 'limits'].map((k) => (
          <li key={k}><Badge variant="outline" className="h-auto rounded-md font-normal whitespace-normal text-muted-foreground">{t(`dbq.sql.rule.${k}`)}</Badge></li>
        ))}
      </ul>

      <div className={cn('flex min-w-0 overflow-hidden rounded-md border bg-background shadow-xs',
        (fieldError || over || shown.status === 'rejected') && 'border-destructive', disabled && 'opacity-80')}>
        {/* Satır cetveli — metin kaydıkça aynı miktar kayar; ekran okuyucuya okunmaz */}
        <div ref={gutterRef} aria-hidden="true" data-slot="dbq-sql-gutter"
          className={cn(MONO, 'max-h-[60vh] shrink-0 overflow-hidden border-r bg-muted/50 py-2 text-right text-muted-foreground select-none')}>
          {Array.from({ length: lines }, (_, i) => (
            <div key={i} data-line={i + 1} data-bad={badLines.has(i + 1) ? 'true' : undefined}
              className={cn('px-2 tabular-nums', badLines.has(i + 1) && 'bg-destructive/10 font-bold text-destructive')}>
              {i + 1}
            </div>
          ))}
        </div>
        <Textarea ref={taRef} id={id} value={text} disabled={disabled} wrap="off" spellCheck={false} autoCapitalize="off"
          autoComplete="off" autoCorrect="off" data-gramm="false"
          placeholder={t('dbq.sql.placeholder')}
          aria-invalid={fieldError || over || shown.status === 'rejected' ? true : undefined}
          aria-describedby={[counterId, hintId, statusId, errId].filter(Boolean).join(' ')}
          onChange={(e) => onChange?.(e.target.value)} onKeyDown={onKeyDown} onScroll={syncScroll}
          className={cn(MONO, 'field-sizing-fixed h-64 max-h-[60vh] min-h-40 resize-y overflow-auto rounded-none border-0 py-2 whitespace-pre shadow-none focus-visible:ring-0')} />
      </div>
      <p id={hintId} className="text-xs text-muted-foreground">{t('dbq.sql.keyboardHint')}</p>

      {over && <p className="text-xs font-semibold text-destructive">{t('dbq.sql.tooLong', fmt(MAX_SQL_CHARS))}</p>}

      <GuardStatus id={statusId} guard={shown} t={t} onGo={goTo}
        onRecheck={servedActive || disabled ? null : () => setRecheck((n) => n + 1)} />

      {dbType === 'MSSQL' && (
        <AlertBanner tone="info" icon={Info} title={t('dbq.sql.nolockTitle')} className="mb-0">
          <p className="m-0">{t('dbq.sql.nolockBody')}</p>
          <pre data-slot="dbq-sql-nolock-example"
            className="mt-2 mb-0 overflow-x-auto rounded-md border bg-muted/50 px-3 py-2 font-mono text-xs leading-5 whitespace-pre text-foreground">
            {MSSQL_EXAMPLE}
          </pre>
        </AlertBanner>
      )}

      {fieldError && <FieldError id={errId}>{fieldError}</FieldError>}
    </div>
  )
}

/** Denetim sonucu satırı + ihlal listesi + okunan tablolar. */
function GuardStatus({ id, guard, t, onGo, onRecheck }) {
  const { status } = guard
  const list = guard.violations || []
  const tables = guard.tables || []
  return (
    <div id={id} data-slot="dbq-sql-guard" data-status={status} className="flex min-w-0 flex-col gap-2" aria-live="polite">
      {status === 'checking' && (
        <p className="m-0 inline-flex items-center gap-1.5 text-xs text-muted-foreground">
          <Spinner size={14} inline decorative />{t('dbq.sql.checking')}
        </p>
      )}
      {status === 'empty' && <p className="m-0 text-xs text-muted-foreground">{t('dbq.sql.emptyHint')}</p>}
      {status === 'ok' && (
        <p data-slot="dbq-sql-ok" className="m-0 inline-flex items-center gap-1.5 text-xs font-semibold text-success">
          <ShieldCheck aria-hidden="true" className="size-4" />{t('dbq.sql.ok')}
        </p>
      )}
      {status === 'unknown' && (
        <div data-slot="dbq-sql-unknown" data-timeout={guard.timeout ? 'true' : undefined}
          className="flex min-w-0 flex-wrap items-start gap-x-3 gap-y-1.5">
          <p className="m-0 inline-flex min-w-0 flex-1 basis-64 items-start gap-1.5 text-xs text-muted-foreground">
            <ShieldQuestion aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            <span className="[overflow-wrap:anywhere]">
              {guard.timeout
                ? (guard.message || t('dbq.sql.timeout'))
                : <>{t('dbq.sql.unchecked')}{guard.message ? ` — ${guard.message}` : ''}</>}
            </span>
          </p>
          {onRecheck && (
            <Button type="button" variant="outline" size="sm" onClick={onRecheck} data-slot="dbq-sql-recheck"
              className="shrink-0 pointer-coarse:h-10">
              <RotateCw aria-hidden="true" />{t('dbq.sql.recheck')}
            </Button>
          )}
        </div>
      )}
      {status === 'rejected' && (
        <div data-slot="dbq-sql-violations" className="rounded-md border border-destructive/40 bg-destructive/5 p-2.5">
          <p className="m-0 mb-1.5 inline-flex items-center gap-1.5 text-sm font-semibold text-destructive">
            <ShieldAlert aria-hidden="true" className="size-4" />
            {list.length === 1 ? t('dbq.sql.violationsOne') : t('dbq.sql.violations', list.length || 1)}
          </p>
          <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
            {(list.length ? list : [{ code: 'SQL_UNPARSABLE' }]).map((v, i) => {
              const code = isKnownGuardCode(v.code) ? v.code : 'unknown'
              const title = tOr(t, `dbq.guard.${code}.title`, 'dbq.guard.unknown.title')
              const fix = tOr(t, `dbq.guard.${code}.fix`, 'dbq.guard.unknown.fix')
              const where = v.line ? (v.column ? t('dbq.sql.atLineCol', v.line, v.column) : t('dbq.sql.atLine', v.line)) : null
              return (
                <li key={`${v.code}-${v.line}-${v.column}-${i}`} data-slot="dbq-sql-violation" data-code={v.code}>
                  <Button type="button" variant="ghost" onClick={() => onGo(v)} disabled={!v.line}
                    className="h-auto w-full items-start justify-start gap-2 px-2 py-1.5 text-left whitespace-normal hover:bg-destructive/10 pointer-coarse:min-h-10 disabled:opacity-100">
                    <AlertOctagon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-destructive" />
                    <span className="flex min-w-0 flex-col gap-0.5">
                      <span className="text-sm font-semibold [overflow-wrap:anywhere]">
                        {title}{v.token ? <> · <code className="rounded bg-muted px-1 font-mono text-xs">{v.token}</code></> : null}
                      </span>
                      {v.message && <span className="text-xs text-foreground [overflow-wrap:anywhere]">{v.message}</span>}
                      <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{fix}</span>
                      {where && <span className="text-xs font-medium text-destructive">{where}</span>}
                    </span>
                  </Button>
                </li>
              )
            })}
          </ul>
        </div>
      )}
      {(status === 'ok' || status === 'rejected') && tables.length > 0 && (
        <div data-slot="dbq-sql-tables" className="flex min-w-0 flex-wrap items-center gap-1.5">
          <span className="inline-flex items-center gap-1 text-xs font-semibold text-muted-foreground">
            <Table2 aria-hidden="true" className="size-3.5" />{t('dbq.sql.tables')}
          </span>
          {tables.map((tb) => (
            <Badge key={tb} variant="outline" className="h-auto rounded-md font-mono font-normal whitespace-normal [overflow-wrap:anywhere]">{tb}</Badge>
          ))}
        </div>
      )}
    </div>
  )
}
