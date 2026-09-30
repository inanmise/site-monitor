import { useT } from '../../../i18n/index.jsx'
import { dateLocale } from '../../../i18n/dateLocale.js'
import { DEPLOY_KIND_STYLE, deployKindTone, fmtDuration } from '../../../utils/releaseUi.js'
import { relParts } from './releaseModel.js'
import CopyButton from '../../ui/CopyButton.jsx'
import { TONE_CLASS } from '../ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/**
 * Sürüm & Dağıtım rozetleri (2026-09-27 yeniden tasarım) — tür, kaynak, ortam, "şu an" ve kopyalanabilir kısa
 * referans. Tonlar `utils/releaseUi.js` DEPLOY_KIND_TONE'dan (tek sözlük); ikonlar DEPLOY_KIND_STYLE'dan.
 * Test kancaları: `data-kind`, `data-source`, `data-env`, `data-slot="deploy-current-badge"`, `data-slot="sha-ref"`.
 */

/** Göreli zaman ("2 gün önce" / "2 days ago") — tarayıcı Intl'i, uygulama dili. Geçersiz → ''. */
export function fmtAgo(iso, now = Date.now()) {
  const p = relParts(iso, now)
  if (!p) return ''
  try {
    return new Intl.RelativeTimeFormat(dateLocale(), { numeric: 'auto' }).format(p.value, p.unit)
  } catch {
    return ''
  }
}

/** Kısa süre ("3g 19sa" / "3d 19h"; 60 sn altı "az önce"). Boş/geçersiz → ''. */
export function fmtDur(seconds, t) {
  return fmtDuration(seconds, { d: t('version.dur.d'), h: t('version.dur.h'), m: t('version.dur.m') }, t('version.justNow'))
}

/** Tür noktası/ikon kabı tonları (zaman çizelgesi rayı) — koyu karşılıklar aynı satırda. */
export const DOT_TONE = {
  success: 'bg-success/15 text-success ring-success/30',
  danger: 'bg-destructive/15 text-destructive ring-destructive/30',
  warning: 'bg-amber-500/15 text-amber-700 ring-amber-500/30 dark:text-amber-300',
  info: 'bg-primary/10 text-primary ring-primary/25',
  muted: 'bg-muted text-muted-foreground ring-border',
}

export function kindIcon(kind) {
  return (DEPLOY_KIND_STYLE[kind] || DEPLOY_KIND_STYLE.UNKNOWN).icon
}

/** Dağıtım türü rozeti: ikon + ad; bilinmeyen sürüm kesikli. */
export function KindBadge({ kind, className }) {
  const t = useT()
  const k = kind || 'UNKNOWN'
  const Icon = kindIcon(k)
  return (
    <Badge variant="outline" data-kind={k}
      className={cn('gap-1 rounded-md px-1.5 font-semibold whitespace-nowrap', TONE_CLASS[deployKindTone(k)],
        k === 'UNKNOWN' && 'border-dashed border-border', className)}>
      <Icon aria-hidden="true" className="size-3" />{t('version.kind.' + k)}
    </Badge>
  )
}

/** Kayıt kaynağı rozeti — elle mavi, geri doldurma kesikli, açılış sessiz. */
export function SourceBadge({ source, className }) {
  const t = useT()
  if (!source) return null
  return (
    <Badge variant="outline" data-source={source}
      className={cn('rounded-md px-1.5 font-medium whitespace-nowrap',
        source === 'MANUAL' ? TONE_CLASS.info : source === 'BACKFILL' ? 'border-dashed text-muted-foreground' : TONE_CLASS.muted,
        className)}>
      {t('version.source.' + source)}
    </Badge>
  )
}

/**
 * Ortam rozeti (Sürüm & Dağıtım, sürüm penceresi, Sistem Sağlığı "Uygulama" kartı, Genel Ayarlar → Ortam adı — hepsi bu
 * bileşen). Sunucu ortam adını önce Genel Ayarlar'daki "Ortam adı" ayarından (2026-09-29), yoksa Helm
 * `config.environmentName` (APP_ENVIRONMENT) değerinden okur; ikisi de boşsa pod'da
 * "unknown", pod dışında "local" döner (BuildInfo). Ham "unknown" kullanıcıya bir şey söylemiyordu (2026-09-27,
 * kullanıcı: "neden unknown yazıyor?") → çevrilmiş etiket + nedenini ve çözümünü anlatan ipucu.
 */
export function EnvBadge({ env, className }) {
  const t = useT()
  if (!env) return null
  const key = String(env).toLowerCase()
  const unknown = key === 'unknown'
  const label = unknown ? t('version.envUnknown') : key === 'local' ? t('version.envLocal') : env
  return (
    <Badge variant="outline" data-env={env} title={unknown ? t('version.envUnknownTip') : undefined}
      className={cn('rounded-md px-1.5 font-semibold tracking-wide', !unknown && key !== 'local' && 'uppercase',
        unknown ? TONE_CLASS.warning : TONE_CLASS.info, className)}>
      {label}
    </Badge>
  )
}

/** "Şu an" rozeti — koşan kayıt. */
export function CurrentBadge({ className }) {
  const t = useT()
  return (
    <Badge variant="outline" data-slot="deploy-current-badge"
      className={cn('gap-1 rounded-full px-2 font-bold tracking-wide uppercase', TONE_CLASS.success, className)}>
      <span aria-hidden="true" className="size-1.5 rounded-full bg-success motion-safe:animate-pulse" />
      {t('releases.current')}
    </Badge>
  )
}

/**
 * Kısa gösterilen, TAM kopyalanan referans (commit SHA, imaj). Metin tek düğümde (sorgular bölünmez); tam değer
 * `title`da. Kopyala düğmesi dar kapta (dokunmatik) 40 px, geniş kapta 32 px (`@container/deploy`).
 */
export function ShaRef({ value, short, wrap = false, className }) {
  const t = useT()
  if (!value) return <span className="text-muted-foreground">—</span>
  const shown = short || value
  return (
    <span data-slot="sha-ref" className={cn('inline-flex min-w-0 max-w-full items-center gap-0.5 align-middle', className)}>
      <code title={value}
        className={cn('min-w-0 rounded-sm bg-muted px-1.5 py-px font-mono text-[0.92em] text-foreground',
          wrap ? 'break-all' : 'truncate')}>
        {shown}
      </code>
      <CopyButton value={value} label={t('a11y.rowAction', shown, t('version.copy'))} copiedLabel={t('version.copied')}
        variant="ghost" buttonSize="icon-sm"
        className="size-10 shrink-0 text-muted-foreground hover:text-primary @2xl/deploy:size-7" />
    </span>
  )
}
