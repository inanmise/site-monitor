import { ChevronRight, CheckCircle2, AlertTriangle, Lock } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import HelpTip from '../../ui/HelpTip.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { cn } from '@/lib/utils'

/** Hızlı seçim çipleri — en sık kullanılan saklama pencereleri. */
const QUICK_DAYS = [30, 90, 180, 365, 730]

export const fmtBytes = (n) => {
  if (n == null) return '—'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = Number(n), i = 0
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v < 10 && i > 0 ? v.toFixed(1) : Math.round(v)} ${u[i]}`
}
export const fmtNum = (n) => {
  if (n == null) return '—'
  const x = Number(n)
  if (x >= 1_000_000) return (x / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M'
  if (x >= 1_000) return (x / 1_000).toFixed(1).replace(/\.0$/, '') + 'K'
  return String(x)
}
export const fmtDay = (iso) =>
  (iso ? String(iso).slice(0, 10).split('-').reverse().join('.') : '—')

/** Saklama modu rozet tonu (AGE mavi, EXTERNAL amber, ORPHAN_ONLY yeşil). */
const MODE_TONE = { AGE: 'info', EXTERNAL: 'warning', ORPHAN_ONLY: 'success' }

/**
 * Tek saklama politikası satırı: solda kimlik, ortada süre kontrolü, sağda ölçüm kümesi.
 * Satır açılınca silme kuralı, taban/varsayılan, son temizlik ve uyum onayı görünür.
 *
 * Tablo yerine CSS-grid: 8 kolonlu sabit tablo dar ekranda sıkışıyordu ve başlıkları
 * "sıralanabilir" gibi görünüyordu (yanlış affordance). Mobil-önce: telefonda kimlik / kontrol /
 * ölçümler alt alta, geniş ekranda (xl) tek satır ızgara. Değişen satır TÜM çerçevesiyle amber
 * vurgulanır (sol renk şeridi YOK — kullanıcı kararı 2026-09-26); `data-changed` test kancası.
 */
export default function PolicyRow({
  policy, value, original, maxRows, expanded, onToggle, onChange, approval, onApprove, disabled,
}) {
  const t = useT()
  const p = policy
  const changed = p.configurable && String(value) !== String(original)
  const shortened = changed && Number(value) < Number(original)
  const pct = maxRows > 0 ? Math.max(1, Math.round((Number(p.rows) || 0) / maxRows * 100)) : 0
  const needsApproval = p.data_class === 'PERSONAL' || p.data_class === 'SECURITY_AUDIT'

  return (
    <div data-slot="policy-row" data-changed={changed ? 'true' : undefined} data-open={expanded ? 'true' : undefined}
      className={cn('mb-2 overflow-hidden rounded-[10px] border bg-card transition-[border-color,box-shadow] hover:border-primary/40',
        expanded && 'shadow-md',
        changed && 'border-amber-500/70 bg-amber-500/5 hover:border-amber-500/80')}>
      <div className="grid grid-cols-[28px_minmax(0,1fr)] items-center gap-x-3 gap-y-2.5 px-3 py-2.5 sm:px-3.5 xl:grid-cols-[28px_minmax(200px,1.5fr)_minmax(230px,auto)_minmax(280px,1.4fr)] xl:gap-3.5">
        <Button type="button" variant="ghost" size="icon-sm" onClick={onToggle} aria-expanded={expanded} aria-label={p.table}
          className="text-muted-foreground hover:text-primary">
          <ChevronRight aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', expanded && 'rotate-90')} />
        </Button>

        {/* Kimlik */}
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-1.5 font-mono text-[13px] font-bold break-all">
            {p.table}
            {/* Ayarı olmayan (ORPHAN/EXTERNAL) politikada helpKey boştur → HelpTip hiç çizilmez. */}
            <HelpTip helpKey={p.setting_key ? 'help.set.' + p.setting_key : ''} label={p.table} />
            {changed && (
              <ToneBadge tone={shortened ? 'danger' : 'success'} data-delta={shortened ? 'down' : 'up'}
                className="rounded-[5px] px-1.5 font-sans text-[11px] font-extrabold">
                {original} → {value}
              </ToneBadge>
            )}
          </div>
          <div className="mt-0.5 line-clamp-2 text-[11.5px] leading-snug text-muted-foreground" title={p.rationale}>{p.rationale}</div>
        </div>

        {/* Süre kontrolü */}
        <div className="col-span-2 flex flex-wrap items-center gap-2.5 xl:col-span-1">
          {p.configurable ? (
            <>
              <div role="group" aria-label={t('ret.quickDays', p.table)} className="inline-flex flex-wrap gap-0.5 rounded-lg border bg-muted/50 p-0.5">
                {QUICK_DAYS.map(d => {
                  const active = String(value) === String(d)
                  const blocked = d < p.min_days
                  const chip = (
                    <Button key={d} type="button" size="xs" variant={active ? 'default' : 'ghost'} aria-pressed={active}
                      disabled={disabled || blocked} onClick={() => onChange(String(d))}
                      className={cn('min-w-9 tabular-nums', !active && 'text-muted-foreground')}>{d}</Button>
                  )
                  // Devre dışı düğme ipucu almaz (olay üretmez) → span tetik
                  return blocked
                    ? <SimpleTooltip key={d} content={t('ret.minHint', p.min_days)}><span className="inline-flex">{chip}</span></SimpleTooltip>
                    : chip
                })}
              </div>
              <div className="inline-flex items-center gap-1.5">
                <Input type="number" className="h-8 w-20 tabular-nums" min={p.zero_means_never ? 0 : p.min_days}
                  value={value} disabled={disabled} aria-label={t('ret.daysFor', p.table)}
                  onChange={e => onChange(e.target.value)} />
                <span className="text-[11.5px] text-muted-foreground">{t('ret.daysShort')}</span>
              </div>
            </>
          ) : (
            <ToneBadge tone={MODE_TONE[p.mode] || 'muted'} data-mode={p.mode} className="rounded-full px-2.5 font-bold">
              {p.mode === 'EXTERNAL' && <Lock aria-hidden="true" />}
              {t(`ret.mode.${p.mode}`)}
            </ToneBadge>
          )}
        </div>

        {/* Ölçüm kümesi */}
        <div className="relative col-span-2 grid grid-cols-2 gap-x-4 gap-y-1.5 pb-2 sm:grid-cols-[repeat(4,auto)] sm:justify-start xl:col-span-1 xl:justify-end">
          <Metric value={fmtNum(p.rows)} label={t('ret.colRows')} />
          <Metric value={fmtBytes(p.bytes)} label={t('ret.colSize')} />
          <Metric value={`${fmtDay(p.oldest_at)} → ${fmtDay(p.newest_at)}`} label={t('ret.rangeLbl')} mono />
          <Metric value={p.deletes ? fmtNum(p.purgeable) : '—'} label={t('ret.colPurgeable')} danger={p.purgeable > 0} />
          {/* decorative: aynı oran zaten satır/boyut metinleri olarak görünüyor. */}
          <div className="absolute inset-x-0 bottom-0">
            <ProgressBar value={pct} max={100} size="sm" decorative className="h-[3px]" />
          </div>
        </div>
      </div>

      {expanded && (
        <div className="border-t border-dashed bg-muted/40 px-4 pt-3 pb-3.5 sm:pl-[50px]">
          <dl className="grid grid-cols-1 gap-x-3.5 gap-y-1.5 text-xs sm:grid-cols-[130px_1fr]">
            <dt className="font-bold text-muted-foreground">{t('ret.detailRule')}</dt>
            <dd className="font-mono text-[11.5px] break-words">{p.rule || t(`ret.mode.${p.mode}`)}</dd>
            <dt className="font-bold text-muted-foreground">{t('ret.detailColumn')}</dt>
            <dd className="break-words">{p.time_column || '—'}</dd>
            <dt className="font-bold text-muted-foreground">{t('ret.detailFloor')}</dt>
            <dd>{p.configurable ? t('ret.detailFloorVal', p.min_days, p.default_days) : '—'}</dd>
            <dt className="font-bold text-muted-foreground">{t('ret.detailKey')}</dt>
            <dd className="font-mono text-[11.5px] break-all">{p.setting_key || '—'}</dd>
          </dl>

          {needsApproval && (
            <Button type="button" variant="outline" size="sm" onClick={onApprove} disabled={disabled} data-approved={approval ? 'true' : 'false'}
              className={cn('mt-3 h-auto min-h-8 border-dashed py-1 text-left text-[11.5px] font-semibold whitespace-normal hover:border-solid',
                approval
                  ? 'border-success text-success hover:text-success'
                  : 'border-amber-500 text-amber-700 hover:text-amber-800 dark:text-amber-300 dark:hover:text-amber-200')}>
              {approval
                ? <><CheckCircle2 aria-hidden="true" />{t('ret.approvedBy', approval.by, fmtDay(approval.at))}
                    {approval.note ? <span className="font-normal text-muted-foreground">· {approval.note}</span> : null}</>
                : <><AlertTriangle aria-hidden="true" />{t('ret.approvePending')}</>}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}

/** Ölçüm kümesinin tek hücresi: değer + küçük büyük harf etiket. */
function Metric({ value, label, mono = false, danger = false }) {
  return (
    <div className="flex min-w-[52px] flex-col gap-px sm:items-end">
      <span className={cn('text-[13px] font-bold whitespace-nowrap tabular-nums',
        mono && 'font-mono text-[11.5px] font-semibold text-muted-foreground', danger && 'text-destructive')}>{value}</span>
      <span className="text-[9.5px] font-bold tracking-wider whitespace-nowrap text-muted-foreground/80 uppercase">{label}</span>
    </div>
  )
}
