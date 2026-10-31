import { Database, ShieldCheck, ShieldAlert, CircleHelp, CircleDashed, Unplug } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import ToneBadge from '../admin/ToneBadge.jsx'
import { HEALTH_TONE, healthState } from './connectionModel.js'

/**
 * Veritabanı Sorgu İzleme yapı taşlarının ORTAK küçük parçaları (2026-10-31): DB türü rozeti, bağlantı sağlık rozeti.
 * Renk yalnız durum için kullanılır; DB türleri yeşil / kırmızı / amber DEĞİL (durumla karışmasın) — nötr çerçeve + harf.
 */

const TYPE_INK = {
  POSTGRESQL: 'border-sky-500/40 text-sky-800 dark:text-sky-200',
  MSSQL: 'border-violet-500/40 text-violet-800 dark:text-violet-200',
  ORACLE: 'border-indigo-500/40 text-indigo-800 dark:text-indigo-200',
}

/** DB türü rozeti — simge + türün adı (TR/EN); bilinmeyen tür nötr "?". Test kancası `data-slot="dbq-type"` + `data-type`. */
export function DbTypeBadge({ type, className = '' }) {
  const t = useT()
  const known = Object.prototype.hasOwnProperty.call(TYPE_INK, type)
  return (
    <Badge variant="outline" data-slot="dbq-type" data-type={known ? type : 'unknown'}
      className={cn('h-auto gap-1 rounded-md font-medium whitespace-normal', known ? TYPE_INK[type] : 'text-muted-foreground', className)}>
      <Database aria-hidden="true" />
      {known ? t(`dbq.conn.type.${type}`) : t('dbq.conn.type.unknown')}
    </Badge>
  )
}

const HEALTH_ICON = { ok: ShieldCheck, failing: ShieldAlert, untested: CircleDashed, unused: Unplug, unknown: CircleHelp }

/**
 * Bağlantı sağlık rozeti — yalnız sunucunun "ok" dediği yeşil; "denenmedi" amber, "kullanılmıyor" nötr, bilinmeyen
 * (alan yok / okunamadı) nötr "bilinmiyor". `data-slot="dbq-health"` + `data-state`.
 */
export function HealthBadge({ profile, state: stateProp, className = '' }) {
  const t = useT()
  const state = stateProp || healthState(profile)
  const Icon = HEALTH_ICON[state] || CircleHelp
  return (
    <ToneBadge tone={HEALTH_TONE[state] || 'muted'} data-slot="dbq-health" data-state={state}
      className={cn('h-auto gap-1 rounded-md whitespace-normal', className)}>
      <Icon aria-hidden="true" />{t(`dbq.conn.health.${state}`)}
    </ToneBadge>
  )
}
