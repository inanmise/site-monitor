import { useEffect, useRef, useState } from 'react'
import { dateLocale } from '../../i18n/dateLocale.js'
import { RefreshCw, Check, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ProgressBar, Spinner } from '../ui/Progress.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

// Date → "HH:mm:ss.SSS"
export const fmtClock = (d) => (d instanceof Date
  ? d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0')
  : '')
// Geçen süre — <1 sn ise ms, değilse saniye (3 hane ms hassasiyeti).
export const fmtDur = (ms) => (ms == null ? '' : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(3)} s`)
// ISO/tarih → "GG.AA.YYYY" (saat gürültüsü tabloyu şişirmesin)
export const fmtDay = (iso) => {
  if (!iso) return '—'
  const d = new Date(iso)
  return isNaN(d) ? '—' : d.toLocaleDateString(dateLocale(), { day: '2-digit', month: '2-digit', year: 'numeric' })
}

/**
 * Kolon hücre görünümleri (eski `.chk-mono` / `.chk-td-target` / `.chk-td-team` / `.chk-days`) —
 * Tailwind + jeton; çağıranlar `tdClassName`/`thClassName` olarak bunları verir. shadcn TableCell'e
 * legacy sınıf konmaz (App.css katmansız, Tailwind'i ezer).
 */
export const CELL = {
  mono: 'font-mono tabular-nums text-muted-foreground [&_b]:font-bold [&_b]:text-foreground',
  left: 'text-left',
  target: 'max-w-[260px] truncate text-left font-medium text-muted-foreground',
  team: 'max-w-[170px] truncate text-left font-medium text-muted-foreground',
  days: 'font-bold',
}

/** Kalan gün → renk (kart/rozet token'larıyla aynı eşikler: <0 dolmuş, <7 kritik, <15 yüksek, <30 uyarı). */
export function daysClass(days) {
  if (days == null) return 'text-muted-foreground'
  if (days < 0) return 'text-destructive line-through'
  if (days < 7) return 'text-destructive'
  if (days < 15) return 'text-orange-600 dark:text-orange-400'
  if (days < 30) return 'text-amber-700 dark:text-amber-400'
  return 'text-success'
}

/** HTTP durum kodu → renk (2xx/3xx iyi, 4xx uyarı, 5xx hata). */
export function httpClass(code) {
  if (code == null) return 'text-muted-foreground'
  if (code < 400) return 'text-success'
  if (code < 500) return 'text-amber-700 dark:text-amber-400'
  return 'text-destructive'
}

/**
 * Kolonun hücre sınıfı. Varsayılan tek aralıklı sayı görünümü (`CELL.mono`); boş dize verilirse
 * ek sınıf YAZILMAZ (düz metin hücresi — ör. tier rozeti).
 */
function tdClassOf(c, row) {
  const cls = typeof c.tdClassName === 'function' ? c.tdClassName(row) : c.tdClassName
  if (cls === undefined) return CELL.mono
  return cls || undefined
}

// Tablo başlığı (eski .chk-table th): küçük, büyük harfli, yapışkan; hücreler sağa yaslı.
const TH = 'sticky top-0 z-[1] h-auto bg-card px-3 py-1.5 text-right text-[10.5px] font-bold tracking-[.04em] text-muted-foreground uppercase'
const TD = 'px-3 py-1.5 text-right'

/**
 * "Şimdi Kontrol Et" akan ilerleme tablosunun İSKELETİ: ilerleme çubuğu, özet şeridi, akış
 * kaydırması, duvar saati ve Durdur/Kapat eylemleri. Çizim shadcn: pencere ui/ModalShell (Dialog),
 * tablo Table, ilerleme ui/Progress, sayaç Badge.
 *
 * <p><b>Neden ayrı bileşen:</b> aynı koşum yüzeyi iki yerde gerekiyor — sertifika panosunda
 * (alan adı · tier · kalan gün) ve dokuz izleme sayfasında (izleme · hedef · yanıt süresi).
 * Ortak olan tabloların KOLONLARI değil, çevresindeki her şey: satırlar geldikçe en alta kayan
 * liste, paralel koşumda donmayan saniye sayacı, kaç/kaç ilerleme ve "uçuştakiler bitsin"
 * anlamındaki Durdur. Bunları ikinci kez yazmak, iki kopyanın zamanla ayrışması demekti.
 *
 * <p>Kolonlar çağırandan gelir; iskelet yalnız durum tik'ini, ad hücresini, başlangıç saatini
 * ve süreyi kendisi çizer — çünkü satır sözleşmesinin ({@code ok/error/start/ms}) sahibi odur.
 *
 * <p>Pencere örtüye tıklamakla KAPANMAZ (eskisi gibi; koşum sürerken yanlışlıkla kaybolmasın);
 * Kapat düğmesi, X ve Escape kapatır — koşumu durdurmaz (Durdur ayrı eylem).
 * Test kancaları: `role="dialog"`, durum hücresi `data-col="status"`, hatalı satır `data-error`,
 * hata iletisi `data-slot="check-row-msg"`, tik `data-ok`.
 *
 * @param {Object|null} run  {rows, total, done, teamLabel, startedAt, finishedAt}; null → hiçbir şey çizilmez
 * @param {string} nameHeader  ilk veri kolonunun başlığı
 * @param {Function} nameOf  (row) => string — ilk veri kolonunun metni
 * @param {Function} [errorOf]  (row) => string|null — varsayılan: row.error || row.data?.error
 * @param {Array} columns  [{key, label, thClassName?, tdClassName?: string|(row)=>string, render: (row)=>node}]
 * @param {import('react').ReactNode} [notice]  özet şeridinin altında koşum geneli bildirim (ör. "havuz dolu → N atlandı")
 */
export default function CheckRunShell({ run, nameHeader, nameOf, errorOf, columns = [], title, notice, onClose, onCancel }) {
  const t = useT()
  const listRef = useRef(null)
  const [now, setNow] = useState(() => Date.now())

  // Yeni satır eklendikçe listeyi en alta kaydır (akış efekti).
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [run?.rows.length])

  // Geçen süre saniyede bir ilerlesin: koşum paralel olduğu için son yavaş kontrol beklenirken
  // yeni satır gelmiyor ve yalnız render'a bağlı bir sayaç donmuş görünürdü.
  const running = !!run && !run.done
  useEffect(() => {
    if (!running) return
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [running])

  if (!run) return null

  const rows = run.rows
  const okCount = rows.filter(r => r.ok).length
  const failCount = rows.length - okCount
  // DUVAR SAATİ süresi. Satır sürelerinin toplamı DEĞİL: kontroller paralel koştuğu için o toplam
  // (ör. 8 × 6 sn) gerçekte geçen sürenin çok üstünde çıkar ve kullanıcıya yanlış bilgi verirdi.
  const elapsedMs = run.startedAt ? Math.max(0, (run.finishedAt ?? now) - run.startedAt) : null
  const rowError = errorOf || ((r) => r.error || r.data?.error)

  return (
    <ModalShell
      open
      onClose={onClose}
      title={title || t('app.checkProgressTitle')}
      icon={RefreshCw}
      size="lg"
      dismissOnBackdrop={false}
      className="sm:w-fit sm:min-w-[460px] sm:max-w-[min(92vw,calc(100%-2rem))]"
      headerExtra={
        <Badge variant="secondary" data-slot="check-count" className="ml-auto font-bold tabular-nums">
          {rows.length}/{run.total}
        </Badge>
      }
      footer={<>
        {!run.done && (
          <Button variant="secondary" onClick={onCancel}>{t('app.checkCancel')}</Button>
        )}
        <Button variant="secondary" onClick={onClose}>{t('app.close')}</Button>
      </>}
    >
      {/* Belirli ilerleme: kaç kontrolün bittiği zaten biliniyor → yüzde gösterilebilir.
          Rol ve değer shadcn Progress'ten; başlıktaki x/y ile aynı sayıdan gelir. */}
      <div className="mb-1">
        <ProgressBar value={rows.length} max={run.total} size="sm"
          label={t('app.checkProgressLabel', rows.length, run.total)} showValue />
      </div>

      <div className="flex flex-wrap items-center gap-x-3.5 gap-y-2 px-0.5 pt-2 pb-2.5 text-xs font-bold tabular-nums text-muted-foreground">
        <span className="inline-flex items-center gap-1.5 text-success"><Check size={13} aria-hidden="true" />{t('app.checkSummaryOk', okCount)}</span>
        <span className={cn('inline-flex items-center gap-1.5', failCount && 'text-destructive')}><X size={13} aria-hidden="true" />{t('app.checkSummaryFail', failCount)}</span>
        <span className="inline-flex items-center gap-1.5">{t('app.checkSummaryTime', fmtDur(elapsedMs))}</span>
        {run.teamLabel && <span className="ml-auto max-w-[42%] truncate font-semibold">{run.teamLabel}</span>}
      </div>
      {notice}

      {/* Kaydırma bu kapta (ref: akış); shadcn Table'ın kendi kabı taşmayı ona bırakır ki başlık yapışsın. */}
      <div ref={listRef}
        className="my-1 mb-2.5 max-h-[50vh] overflow-auto rounded-lg border [&>[data-slot=table-container]]:overflow-visible">
        <Table className="text-[13px]">
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className={cn(TH, 'w-[34px] pr-1 pl-2.5 text-center')}>
                <span className="sr-only">{t('app.checkColStatus')}</span>
              </TableHead>
              <TableHead className={cn(TH, 'text-left')}>{nameHeader}</TableHead>
              {columns.map(c => <TableHead key={c.key} className={cn(TH, c.thClassName)}>{c.label}</TableHead>)}
              <TableHead className={TH}>{t('app.checkColStart')}</TableHead>
              <TableHead className={TH}>{t('app.checkColDur')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((r, i) => {
              const msg = r.ok ? null : rowError(r)
              return (
                <TableRow key={i} data-error={r.ok ? undefined : 'true'} className={r.ok ? undefined : 'bg-destructive/5'}>
                  <TableCell data-col="status" className={cn(TD, 'w-[34px] pr-1 pl-2.5 text-center')}>
                    <span data-ok={r.ok ? 'true' : 'false'}
                      className={cn('inline-flex size-[17px] items-center justify-center rounded-full align-middle text-[11px] font-extrabold text-white',
                        r.ok ? 'bg-success' : 'bg-destructive')}>
                      {r.ok ? '✓' : '✕'}
                    </span>
                  </TableCell>
                  <TableCell data-col="name" className={cn(TD, 'text-left font-semibold')}>
                    <span className="block">{nameOf(r)}</span>
                    {msg && <span data-slot="check-row-msg" className="mt-0.5 block max-w-[320px] text-[.88em] font-normal whitespace-normal text-destructive">{msg}</span>}
                  </TableCell>
                  {columns.map(c => (
                    <TableCell key={c.key} className={cn(TD, tdClassOf(c, r))}>{c.render(r)}</TableCell>
                  ))}
                  <TableCell className={cn(TD, CELL.mono)}>{fmtClock(r.start)}</TableCell>
                  <TableCell className={cn(TD, CELL.mono)}><b>{fmtDur(r.ms)}</b></TableCell>
                </TableRow>
              )
            })}
            {!run.done && (
              <TableRow className="hover:bg-transparent">
                <TableCell className={cn(TD, 'text-left text-muted-foreground italic')} colSpan={columns.length + 4}>
                  <Spinner size={14} inline decorative /> {t('app.checking')}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>
    </ModalShell>
  )
}
