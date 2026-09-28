import { useT } from '../../i18n/index.jsx'
import { formatDate, formatDateOnly, formatDateSec } from '../../api/client'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'

/**
 * Durum yoğunluk şeridi — seçili aralığın kovaları (dakika/saat/gün) tek satır hücre dizisi.
 * Hücre rengi hata oranına göre success→danger arasında (color-mix, token'lı — hex yok);
 * tıklanınca o kovanın alt-aralığına iner (onZoom). Hatalı bölgeyi scroll'suz bulmanın yolu.
 * Grafik kütüphanesi yok; hücreler shadcn Button (renk satır içi — hata oranından hesaplanır),
 * ipucu shadcn Tooltip. Boş kovalar (hiç kontrol yok) backend'den gelmez, atlanır.
 * Test kancaları: hücre `data-cell` (+ hatalıysa `data-fail`), sıfırlama düğmesi adıyla.
 *
 * <p>DOKUNMATİK (2026-09-28, RESPONSIVE.md §4 — dokunma hedefi ≥ 40 px): hücreler ETKİLEŞİMLİ (odaklanır, tıklanınca
 * aralığa iner) ama 3–20 px genişliğinde — kova sayısı ≤ 6 sa'te dakika (360'a dek), ≤ 7 g'de saat, üstünde gün. Hücre
 * başına 40 px verilemez: genişletilmiş alanlar komşularla çakışır ve yanlış kovaya iner; ipucu dokunmatikte hiç açılmaz.
 * Bu yüzden `pointer: coarse`'da şeridin üstünü 40 px yüksekliğinde TEK bir katman kaplar (`ui/HintPopover` etkileşimli
 * kipi: shadcn Button + Popover): dokununca HATALI dilimler listelenir (zamanda bitişik hatalı kovalar tek satır), her
 * satır 40 px'lik bir düğme ve o aralığa iner. Fareli ekranda katman `display:none` (odaklanmaz, erişilebilirlik
 * ağacında yok) — masaüstü görünümü ve davranışı AYNI. Katman kancası `data-slot="density-strip-touch"`, liste
 * `data-slot="density-strip-list"`. Hücreler dokunmatikte `pointer-events-none`: hedef adayı bile olmazlar (Chromium dokunma
 * ayarlaması katmanın dışında açıkta kalan en ufak parçayı seçebiliyor — kesinti çizelgesinde yaşandı).
 */

/** Kova anahtarını [fromIso, toIso] aralığına açar — uzunluk kovanın genişliğini söyler. */
export function bucketBounds(key) {
  if (!key) return null
  if (key.length === 16) return [key + ':00', key + ':59']            // dakika
  if (key.length === 13) return [key + ':00:00', key + ':59:59']      // saat
  if (key.length === 10) return [key + 'T00:00:00', key + 'T23:59:59'] // gün
  return [key, key]
}

const utcMs = (iso) => Date.parse(`${iso}Z`)

/**
 * Hatalı dilim koşuları (dokunmatik liste): ZAMANDA bitişik hatalı kovalar tek koşu. Arada kontrol olmayan bir kova
 * (sunucu boş kovayı göndermez) ya da hatasız kova varsa koşu kırılır — boş dilim "hatalı" sayılmaz.
 * @returns [{ from, to, fail, total, buckets, day }] — `from`/`to` kova sınırları (UTC, `Z`siz), `day` gün kovası.
 */
export function failRuns(buckets = []) {
  const out = []
  let cur = null
  for (const b of buckets || []) {
    const fail = Number(b?.fail) || 0
    const bounds = bucketBounds(String(b?.key ?? ''))
    if (!(fail > 0) || !bounds) { cur = null; continue }
    const total = Number(b.total) || 0
    if (cur && utcMs(bounds[0]) - utcMs(cur.to) === 1000) {
      cur.to = bounds[1]
      cur.fail += fail
      cur.total += total
      cur.buckets += 1
    } else {
      cur = { from: bounds[0], to: bounds[1], fail, total, buckets: 1, day: String(b.key).length === 10 }
      out.push(cur)
    }
  }
  return out
}

/** Koşu etiketi (yerel saat): "07.08.2026 13:00 – 15:59" (aynı gün: bitişte yalnız saat); gün kovası "07.08.2026". */
function runLabel(r) {
  if (r.day) {
    const a = formatDateOnly(r.from), b = formatDateOnly(r.to)
    return a === b ? a : `${a} – ${b}`
  }
  const a = formatDate(r.from), b = formatDate(r.to)
  const endDay = formatDateOnly(r.to)
  const endTime = formatDateOnly(r.from) === endDay && b.startsWith(endDay) ? b.slice(endDay.length).replace(/^[^0-9]+/, '') : ''
  return `${a} – ${endTime || b}`
}

/** Dokunmatik liste — Popover içeriği (yalnız AÇIKKEN çizilir: biçimlendirme kapalı şeritte koşmaz). */
function StripTouchList({ buckets, onZoom, close, t }) {
  const runs = failRuns(buckets)
  return (
    <div data-slot="density-strip-list" className="flex min-w-0 flex-col gap-1.5">
      <p className="m-0 text-sm font-semibold text-foreground">{t('hist.stripTouchTitle')}</p>
      <p className="m-0 text-muted-foreground">{runs.length ? t('hist.stripTouchHint') : t('hist.stripTouchNone')}</p>
      {runs.length > 0 && (
        <ul className="m-0 flex max-h-64 list-none flex-col gap-1 overflow-y-auto p-0">
          {runs.map((r) => (
            <li key={r.from}>
              <Button type="button" variant="ghost" data-slot="density-strip-run"
                className="h-auto min-h-10 w-full justify-between gap-2 px-2 py-1.5 text-left text-xs font-normal whitespace-normal"
                onClick={() => { onZoom?.(r.from, r.to); close() }}>
                <span className="min-w-0 tabular-nums">{runLabel(r)}</span>
                <Badge variant="outline" className="shrink-0 border-destructive/40 bg-destructive/10 font-semibold text-destructive tabular-nums">
                  {t('hist.stripTouchCount', r.fail, r.total)}
                </Badge>
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default function DensityStrip({ buckets, onZoom, zoomed = false, onReset }) {
  const t = useT()
  if (!buckets || buckets.length === 0) return null
  return (
    <div className="mt-0.5 mb-2.5 flex items-center gap-2">
      {/* Konum kabı: dokunmatik katman şeridin DIŞINA taşar (-10 px üst/alt → 40 px) — şeridin kendi `overflow-hidden`'ı
          onu kırpardı, bu yüzden katman şeridin kardeşi. */}
      <div className="relative min-w-0 flex-auto">
        <div className="flex h-5 gap-px overflow-hidden rounded" role="group" aria-label={t('hist.stripLabel')}>
          {buckets.map(b => {
            const total = Number(b.total) || 0
            const fail = Number(b.fail) || 0
            const ratio = total > 0 ? fail / total : 0
            const pct = Math.round(ratio * 100)
            const bg = fail === 0
              ? 'color-mix(in srgb, var(--success) 30%, transparent)'
              : `color-mix(in srgb, var(--danger) ${Math.max(35, pct)}%, var(--success))`
            const bounds = bucketBounds(String(b.key))
            const label = `${formatDateSec(bounds[0])} — ${total} / ${fail} ${t('hist.stripFail')}`
            return (
              <SimpleTooltip key={b.key} content={label}>
                <Button type="button" variant="ghost" data-cell="true" data-fail={fail > 0 ? 'true' : undefined}
                  className="h-full min-w-[3px] flex-1 basis-0 rounded-none p-0 transition-[filter] duration-100 hover:brightness-[.82] focus-visible:ring-0 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-primary pointer-coarse:pointer-events-none"
                  style={{ background: bg }} aria-label={label}
                  onClick={() => bounds && onZoom?.(bounds[0], bounds[1])} />
              </SimpleTooltip>
            )
          })}
        </div>
        <HintPopover interactive side="bottom" align="start" data-slot="density-strip-touch"
          aria-label={t('hist.stripTouch')} contentLabel={t('hist.stripTouchTitle')}
          triggerClassName="absolute inset-x-0 -inset-y-2.5 hidden rounded-md pointer-coarse:block"
          className="w-[min(20rem,calc(100vw-1rem))] p-2"
          content={({ close }) => <StripTouchList buckets={buckets} onZoom={onZoom} close={close} t={t} />} />
      </div>
      {zoomed && (
        <Button type="button" variant="outline" size="xs" onClick={onReset}
          className="shrink-0 rounded-full border-primary text-[11px] font-semibold text-primary hover:bg-primary/10 hover:text-primary pointer-coarse:h-10">
          {t('hist.resetZoom')}
        </Button>
      )}
    </div>
  )
}
