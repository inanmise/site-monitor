import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/**
 * Tonlu durum rozeti — shadcn Badge, yumuşak zeminli ton (yönetim ekranlarının ortak dili).
 * Eski `.badge-ok/-err`, `.userpush-badge--*`, `.audit-badge*` benzeri elle tonlanmış etiketlerin yerine.
 * Renkler proje jetonlarından (`success`, `destructive`, `primary`, `muted`); amber tonu shadcn
 * Badge `warning` varyantıyla aynı mürekkep. Test kancası: `data-slot="badge"` + `data-tone`.
 */
export const TONE_CLASS = {
  success: 'border-transparent bg-success/15 text-success',
  danger:  'border-transparent bg-destructive/15 text-destructive',
  info:    'border-transparent bg-primary/10 text-primary',
  warning: 'border-transparent bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  muted:   'border-transparent bg-muted text-muted-foreground',
  neutral: 'text-foreground',
}

/** Sistem rolü rozeti (eski `.role-badge` / `.role-admin`): ADMIN dolgulu birincil, diğerleri yumuşak birincil. */
export function SystemRoleBadge({ role, className = '', children, ...rest }) {
  return role === 'ADMIN'
    ? <Badge data-role={role} className={cn('font-semibold', className)} {...rest}>{children ?? role}</Badge>
    : <Badge variant="outline" data-role={role} className={cn(TONE_CLASS.info, 'font-semibold', className)} {...rest}>{children ?? role}</Badge>
}

/** Org rolü dolgu renkleri (eski `.badge-role-*`) — her rol kalıcı bir renk kimliği taşır. */
const ORG_ROLE_CLASS = {
  PO: 'bg-blue-600', MANAGER: 'bg-amber-600', CLEVEL: 'bg-red-600', TECH: 'bg-green-600', BOLUM_BASKANI: 'bg-violet-600',
}

/** Organizasyonel rol rozeti — dolgulu, beyaz mürekkep; metin çağırandan (i18n). */
export function OrgRoleBadge({ role, className = '', children, ...rest }) {
  return (
    <Badge data-role={role}
      className={cn('rounded-sm px-1.5 font-bold tracking-wide text-white', ORG_ROLE_CLASS[role] || 'bg-muted-foreground', className)} {...rest}>
      {children}
    </Badge>
  )
}

/**
 * Denetim olay rozeti tonları — `auditFormat.eventClass()` çıktısı (`ev-*`) → shadcn Badge tonu.
 * Eski `.audit-event-badge.ev-*` ailesinin karşılığı (koyu tema `dark:` ile aynı satırda).
 */
const EVENT_TONE = {
  'ev-login':  TONE_CLASS.success,
  'ev-failed': TONE_CLASS.danger,
  'ev-logout': TONE_CLASS.muted,
  'ev-delete': TONE_CLASS.danger,
  'ev-create': TONE_CLASS.info,
  'ev-edit':   TONE_CLASS.warning,
  'ev-other':  TONE_CLASS.muted,
  'ev-denied': 'border-transparent bg-red-900 text-white dark:bg-red-800 dark:text-red-50',
  'ev-test':   'border-transparent bg-sky-500/15 text-sky-800 dark:text-sky-200',
  'ev-system': 'border-transparent bg-violet-500/15 text-violet-800 dark:text-violet-200',
  'ev-export': 'border-transparent bg-orange-500/15 text-orange-800 dark:text-orange-200',
}
/** Zaman çizelgesi noktası (dolgulu) — aynı `ev-*` sınıflaması. */
export const EVENT_DOT = {
  'ev-login': 'bg-green-600', 'ev-failed': 'bg-red-600', 'ev-logout': 'bg-zinc-400', 'ev-delete': 'bg-red-600',
  'ev-create': 'bg-blue-600', 'ev-edit': 'bg-amber-500', 'ev-other': 'bg-zinc-400', 'ev-denied': 'bg-red-900',
  'ev-test': 'bg-sky-600', 'ev-system': 'bg-violet-600', 'ev-export': 'bg-orange-600',
}

/** Denetim olay rozeti; `kind` = eventClass() sonucu. Test kancası: `data-event`. */
export function EventBadge({ kind, className = '', children, ...rest }) {
  return (
    <Badge variant="outline" data-event={kind}
      className={cn('rounded-sm font-semibold tracking-wide', EVENT_TONE[kind] || EVENT_TONE['ev-other'], className)} {...rest}>
      {children}
    </Badge>
  )
}

/** Denetim sonucu rozeti (SUCCESS / FAILURE / BLOCKED). */
const OUTCOME_TONE = { SUCCESS: 'success', FAILURE: 'danger', BLOCKED: 'warning' }
export function OutcomeBadge({ outcome, className = '', children, ...rest }) {
  const tone = OUTCOME_TONE[outcome] || 'muted'
  return (
    <Badge variant="outline" data-outcome={outcome || undefined}
      className={cn('rounded-sm font-semibold', TONE_CLASS[tone], className)} {...rest}>
      {children}
    </Badge>
  )
}

/** Push alıcı kararı ("Kim alır?") rozet tonları — kenarlıklı, zeminsiz. */
const DECISION_TONE = {
  RECIPIENT: 'border-success/40 text-success',
  SKIPPED_USER_OPT_OUT: 'border-destructive/40 text-destructive',
  INACTIVE: 'border-destructive/40 text-destructive',
  NO_GROUP: 'text-amber-700 dark:text-amber-300',
  BELOW_MIN_LEVEL: 'text-amber-700 dark:text-amber-300',
  GROUP_DISABLED: 'text-amber-700 dark:text-amber-300',
  MISSING_MEMBERSHIP: 'border-dashed border-destructive/40 text-destructive',
}
/** Push alıcı kararı rozeti (UserPushSettings "Kim alır?", kullanıcı ayrıntısı). Test kancası: `data-decision`. */
export function DecisionBadge({ decision, className = '', children, ...rest }) {
  return (
    <Badge variant="outline" data-decision={decision}
      className={cn('font-bold text-muted-foreground', DECISION_TONE[decision], className)} {...rest}>
      {children}
    </Badge>
  )
}

export default function ToneBadge({ tone = 'muted', className = '', children, ...rest }) {
  const key = TONE_CLASS[tone] ? tone : 'muted'
  return (
    <Badge variant="outline" data-tone={key} className={cn(TONE_CLASS[key], className)} {...rest}>
      {children}
    </Badge>
  )
}
