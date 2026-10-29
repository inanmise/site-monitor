import { useId } from 'react'
import { ArrowRight, Cpu, RefreshCw, RotateCcw, ShieldAlert, Undo2, Zap } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import Field from '../../ui/Field.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { SettingsSaveBar } from '../SettingsControls.jsx'
import { GODEBUG_KEYS, engineState, fmtUtc, godebugOn, buildGodebug, podList } from './k6EngineModel.js'
import {
  AlertDialog, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '@/components/shadcn/alert-dialog'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { cn } from '@/lib/utils'

export const TOUCH = 'pointer-coarse:min-h-10'
/** Ad / yol / sürüm rozeti — sarar, kırpılmaz (CLAUDE.md "names never clipped"). */
export const CHIP = 'h-auto rounded-md overflow-visible whitespace-normal [overflow-wrap:anywhere]'

/** Kart başlığı + isteğe bağlı eylemler — telefonda alt alta, geniş ekranda yan yana. */
export function SectionCard({ slot, icon: Icon, title, description, actions, children, className = '', contentClassName = '' }) {
  const id = useId()
  return (
    <Card data-slot={slot} aria-labelledby={id} role="region" className={cn('gap-4', className)}>
      <CardHeader className="border-b border-border px-4 [.border-b]:pb-4 sm:px-6">
        <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
          <div className="flex min-w-0 flex-col gap-1">
            <CardTitle id={id} role="heading" aria-level={4} className="flex min-w-0 items-center gap-2 leading-snug">
              {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
              <span className="min-w-0 [overflow-wrap:anywhere]">{title}</span>
            </CardTitle>
            {description && <CardDescription className="max-w-[78ch]">{description}</CardDescription>}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-2 sm:shrink-0 sm:justify-end">{actions}</div>}
        </div>
      </CardHeader>
      <CardContent className={cn('flex min-w-0 flex-col gap-4 px-4 sm:px-6', contentClassName)}>{children}</CardContent>
    </Card>
  )
}

// ── Motorlar ────────────────────────────────────────────────────────────────────────────────────

const ROLES = ['active', 'rollback', 'candidate']
const STATE_TONE = { running: 'success', missing: 'danger', unset: 'muted' }

/** Tek ikili satırı: rol, yol (tek aralıklı, sarar), sürüm, durum, yoklama zamanı, sözdizimi notu. */
function EngineRow({ role, profile, lang }) {
  const t = useT()
  const state = engineState(profile)
  return (
    <li data-slot="k6e-engine" data-role={role} data-state={state}
      className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card px-3 py-3 sm:px-4">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <span className="font-semibold">{t(`k6e.role.${role}`)}</span>
        <ToneBadge tone={STATE_TONE[state]} className={CHIP} data-slot="k6e-engine-state" data-state={state}>
          {t(`k6e.engineState.${state}`)}
        </ToneBadge>
        {profile && (profile.version
          ? <Badge variant="outline" className={cn(CHIP, 'font-mono')} data-slot="k6e-engine-version">{profile.version}</Badge>
          : <ToneBadge tone="muted" className={CHIP} data-slot="k6e-engine-version" data-state="unknown">{t('k6e.versionUnknown')}</ToneBadge>)}
      </div>
      <p className="m-0 text-xs text-muted-foreground">{t(`k6e.roleHint.${role}`)}</p>
      {profile ? (
        <code data-slot="k6e-engine-bin" className="block rounded-md bg-muted px-2 py-1 font-mono text-xs [overflow-wrap:anywhere] whitespace-normal">
          {profile.bin}
        </code>
      ) : (
        <p className="m-0 text-sm text-muted-foreground" data-slot="k6e-engine-unset">{t('k6e.engineUnsetHint')}</p>
      )}
      {profile && (
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
          <span data-slot="k6e-engine-probed">{t('k6e.probedAt', fmtUtc(profile.probed_at, lang))}</span>
          {profile.modern_syntax === true && <span data-slot="k6e-engine-syntax" data-syntax="modern">{t('k6e.syntax.modern')}</span>}
          {profile.modern_syntax === false && <span data-slot="k6e-engine-syntax" data-syntax="legacy">{t('k6e.syntax.legacy')}</span>}
        </div>
      )}
    </li>
  )
}

/** Etkin / Yedek / Aday kartı + Yeniden yokla, Adayı etkin yap, Yedeğe geri dön. */
export function EnginesCard({ data, lang, busy, scanRunning, onReprobe, onSwitch, onRollback }) {
  const t = useT()
  const engines = data?.engines || {}
  const locked = !!busy
  // Başka sürüm koşan pod (dağıtım sürüyor) ya da pod sürümleri okunamadı: sunucu geçişi reddeder (inceleme M2)
  const others = data?.other_version_pods
  const mixed = Array.isArray(others) && others.length > 0
  const podsUnknown = others === null
  return (
    <SectionCard slot="k6e-engines" icon={Cpu} title={t('k6e.engines.title')} description={t('k6e.engines.desc')}
      actions={(
        <Button type="button" variant="outline" onClick={onReprobe} disabled={locked} aria-busy={busy === 'reprobe' || undefined}
          className={TOUCH} data-slot="k6e-reprobe">
          {busy === 'reprobe' ? <Spinner size={15} inline decorative /> : <RefreshCw aria-hidden="true" />} {t('k6e.reprobe')}
        </Button>
      )}>
      {data?.scripted_enabled === false && (
        <div data-slot="k6e-scripted-off">
          <AlertBanner tone="info" className="mb-0">{t('k6e.scriptedOff')}</AlertBanner>
        </div>
      )}
      <ul className="m-0 grid list-none grid-cols-1 gap-3 p-0 @3xl:grid-cols-3">
        {ROLES.map((r) => <EngineRow key={r} role={r} profile={engines[r] || null} lang={lang} />)}
      </ul>
      <p className="m-0 text-sm text-muted-foreground" data-slot="k6e-syntax-version">
        {data?.syntax_version ? t('k6e.syntaxVersion', data.syntax_version) : t('k6e.syntaxVersionUnknown')}
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" onClick={onSwitch} disabled={locked || !data?.can_switch || scanRunning || mixed || podsUnknown}
          className={cn(TOUCH, 'whitespace-normal')} data-slot="k6e-switch">
          <Zap aria-hidden="true" /> {t('k6e.switch')}
        </Button>
        <Button type="button" variant="outline" onClick={onRollback} disabled={locked || !data?.can_rollback}
          className={cn(TOUCH, 'whitespace-normal')} data-slot="k6e-rollback">
          <Undo2 aria-hidden="true" /> {t('k6e.rollback')}
        </Button>
      </div>
      {!data?.can_switch && (
        <p className="m-0 text-xs text-muted-foreground" data-slot="k6e-switch-why">{t('k6e.switchUnavailable')}</p>
      )}
      {data?.can_switch && scanRunning && (
        <p className="m-0 text-xs text-muted-foreground" data-slot="k6e-switch-why">{t('k6e.switchWaitScan')}</p>
      )}
      {data?.can_switch && !scanRunning && mixed && (
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="k6e-switch-why" data-why="mixed">
          {t('k6e.switchMixed', podList(others))}
        </p>
      )}
      {data?.can_switch && !scanRunning && podsUnknown && (
        <p className="m-0 text-xs text-muted-foreground" data-slot="k6e-switch-why" data-why="podsUnknown">{t('k6e.switchPodsUnknown')}</p>
      )}
    </SectionCard>
  )
}

// ── Ayarlar ─────────────────────────────────────────────────────────────────────────────────────

/** Yedek / aday yolu, User-Agent, TLS kaçışları (GODEBUG anahtarları) + kayıt çubuğu. */
export function SettingsCard({ data, form, dirty, saving, fe, onChange, onSave, onDiscard }) {
  const t = useT()
  const on = godebugOn(form.godebug)
  const serverKeys = Array.isArray(data?.godebug_keys) ? data.godebug_keys : []
  const descOf = (key) => {
    const own = t(`k6e.gd.${key}`)
    if (own !== `k6e.gd.${key}`) return own
    return serverKeys.find((k) => k.key === key)?.description || ''
  }
  const setKey = (key, v) => onChange('godebug', buildGodebug({ ...on, [key]: v }))
  const anyOn = GODEBUG_KEYS.some((g) => on[g.key])
  return (
    <SectionCard slot="k6e-settings" icon={ShieldAlert} title={t('k6e.settings.title')} description={t('k6e.settings.desc')}>
      <div className="grid min-w-0 grid-cols-1 gap-x-4 @2xl:grid-cols-2">
        <Field label={t('k6e.f.rollbackBin')} hint={t('k6e.f.rollbackBinHint')} {...fe.fieldProps('rollback_bin')}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.rollback_bin} disabled={saving}
              spellCheck={false} autoComplete="off" placeholder="/usr/local/bin/k6-0.49" onChange={(e) => onChange('rollback_bin', e.target.value)}
              data-slot="k6e-f-rollback" className="h-10 font-mono" />
          )}
        </Field>
        <Field label={t('k6e.f.candidateBin')} hint={t('k6e.f.candidateBinHint')} {...fe.fieldProps('candidate_bin')}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.candidate_bin} disabled={saving}
              spellCheck={false} autoComplete="off" placeholder="/usr/local/bin/k6-2.3" onChange={(e) => onChange('candidate_bin', e.target.value)}
              data-slot="k6e-f-candidate" className="h-10 font-mono" />
          )}
        </Field>
        <div className="@2xl:col-span-2">
          <Field label={t('k6e.f.userAgent')} hint={t('k6e.f.userAgentHint')} {...fe.fieldProps('user_agent')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.user_agent} disabled={saving}
                spellCheck={false} autoComplete="off" maxLength={260} placeholder="Grafana k6/2.3.0"
                onChange={(e) => onChange('user_agent', e.target.value)} data-slot="k6e-f-ua" className="h-10 font-mono" />
            )}
          </Field>
        </div>
      </div>

      <fieldset className="m-0 flex min-w-0 flex-col gap-3 border-0 p-0" data-slot="k6e-godebug" data-field="godebug">
        <legend className="mb-1 p-0 text-sm font-semibold">{t('k6e.f.godebug')}</legend>
        <AlertBanner tone="warning" className="mb-0">{t('k6e.f.godebugWarn')}</AlertBanner>
        <ul className="m-0 grid list-none grid-cols-1 gap-2 p-0 @3xl:grid-cols-2">
          {GODEBUG_KEYS.map((g) => (
            <GodebugRow key={g.key} gkey={g.key} restore={g.restore} checked={!!on[g.key]} disabled={saving}
              description={descOf(g.key)} onChange={(v) => setKey(g.key, v)} />
          ))}
        </ul>
        <p className="m-0 text-xs text-muted-foreground" data-slot="k6e-godebug-value">
          {anyOn ? t('k6e.f.godebugValue', form.godebug) : t('k6e.f.godebugNone')}
        </p>
        {fe.errors.godebug && <p className="m-0 text-xs text-destructive" data-slot="k6e-godebug-error">{fe.errors.godebug}</p>}
      </fieldset>

      <SettingsSaveBar dirty={dirty} saving={saving} onSave={onSave} onDiscard={onDiscard} />
    </SectionCard>
  )
}

function GodebugRow({ gkey, restore, checked, disabled, description, onChange }) {
  const t = useT()
  const id = useId()
  const descId = `${id}-d`
  return (
    <li data-slot="k6e-gd" data-key={gkey} data-on={checked ? 'true' : 'false'}
      className="flex min-w-0 items-start gap-3 rounded-lg border px-3 py-2.5">
      <Switch id={id} checked={checked} onCheckedChange={(v) => onChange(v === true)} disabled={disabled} aria-describedby={descId}
        className="mt-0.5" />
      <div className="flex min-w-0 flex-col gap-0.5">
        <Label htmlFor={id} className="flex min-h-5 cursor-pointer flex-wrap items-center gap-1.5 font-normal pointer-coarse:min-h-10">
          <span className="font-mono text-sm font-semibold">{gkey}</span>
          <span className="text-sm">{t('k6e.f.godebugAllow')}</span>
        </Label>
        <span id={descId} className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
          {description}{' '}<span className="font-mono">({gkey}={restore})</span>
        </span>
      </div>
    </li>
  )
}

// ── Geçiş / geri dönüş onayı ────────────────────────────────────────────────────────────────────

const READY_TONE = { danger: 'danger', warning: 'warning', success: 'success', info: 'info' }

/**
 * Geçiş ya da geri dönüş onayı — shadcn AlertDialog. Başlıkta sürüm → sürüm; geçişte SON uyumluluk taramasından hazırlık
 * özeti (tarama yok / başka aday sürümü / tarama sürüyor / gerileme / temiz). Karar kullanıcıda; tarama sürerken sunucu
 * reddettiği için onay düğmesi kapalıdır.
 */
export function EngineActionDialog({ kind, data, readiness, busy, onConfirm, onCancel }) {
  const t = useT()
  if (!kind) return null
  const engines = data?.engines || {}
  const from = engines.active?.version || t('k6e.versionUnknownShort')
  const target = kind === 'switch' ? engines.candidate : engines.rollback
  const to = target?.version || t('k6e.versionUnknownShort')
  const blocked = kind === 'switch' && readiness?.blocked
  const running = busy === kind
  return (
    <AlertDialog open onOpenChange={(open) => { if (!open && !running) onCancel() }}>
      <AlertDialogContent data-slot="k6e-action-dialog" data-kind={kind} data-level={kind === 'switch' ? readiness?.level : undefined}
        className="max-h-[calc(100dvh-2rem)] overflow-y-auto" overlayProps={{ onClick: () => { if (!running) onCancel() } }}>
        <AlertDialogHeader>
          <AlertDialogTitle>{t(kind === 'switch' ? 'k6e.dlg.switchTitle' : 'k6e.dlg.rollbackTitle')}</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="flex flex-col gap-2 text-sm text-muted-foreground">
              <p className="m-0 flex flex-wrap items-center justify-center gap-2 sm:justify-start" data-slot="k6e-dlg-versions">
                <Badge variant="outline" className={cn(CHIP, 'font-mono')}>{from}</Badge>
                <ArrowRight aria-hidden="true" className="size-4" />
                <span className="sr-only">{t('k6e.dlg.to')}</span>
                <Badge variant="outline" className={cn(CHIP, 'font-mono')}>{to}</Badge>
              </p>
              <p className="m-0">{t(kind === 'switch' ? 'k6e.dlg.switchBody' : 'k6e.dlg.rollbackBody')}</p>
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        {kind === 'switch' && readiness && (
          <ul className="m-0 flex list-none flex-col gap-2 p-0" data-slot="k6e-readiness" data-level={readiness.level}>
            {readiness.items.map((it) => (
              <li key={it.key} data-slot="k6e-ready-item" data-key={it.key} data-tone={it.tone}>
                <AlertBanner tone={READY_TONE[it.tone] || 'info'} className="mb-0" role="status">
                  {readinessText(it, t)}
                </AlertBanner>
              </li>
            ))}
          </ul>
        )}
        <AlertDialogFooter>
          <Button type="button" variant="outline" onClick={onCancel} disabled={running} className={TOUCH} data-slot="k6e-dlg-cancel">
            {t('k6e.cancel')}
          </Button>
          <Button type="button" variant={kind === 'switch' && readiness?.level === 'danger' ? 'destructive' : 'default'}
            onClick={onConfirm} disabled={running || blocked} aria-busy={running || undefined} className={cn(TOUCH, 'whitespace-normal')}
            data-slot="k6e-dlg-confirm">
            {running ? <Spinner size={15} inline decorative /> : (kind === 'switch' ? <Zap aria-hidden="true" /> : <RotateCcw aria-hidden="true" />)}
            {' '}{t(kind === 'switch' ? 'k6e.dlg.switchConfirm' : 'k6e.dlg.rollbackConfirm')}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/** Hazırlık maddesi → cümle. */
export function readinessText(it, t) {
  switch (it.key) {
    case 'running': return t('k6e.ready.running')
    case 'noScan': return t('k6e.ready.noScan')
    case 'scansUnknown': return t('k6e.ready.scansUnknown')
    case 'mixedVersions': return t('k6e.ready.mixedVersions', it.params[0])
    case 'podsUnknown': return t('k6e.ready.podsUnknown')
    case 'notRun': return it.params[0] === 1 ? t('k6e.ready.notRun.one') : t('k6e.ready.notRun', it.params[0])
    case 'newFail': return it.params[0] === 1 ? t('k6e.ready.newFail.one') : t('k6e.ready.newFail', it.params[0])
    case 'changed': return it.params[0] === 1 ? t('k6e.ready.changed.one') : t('k6e.ready.changed', it.params[0])
    case 'incomplete': return t('k6e.ready.incomplete', t(`k6e.scanState.${it.params[0]}`))
    case 'versionUnknown': return t('k6e.ready.versionUnknown')
    case 'otherVersion': return t('k6e.ready.otherVersion', it.params[0], it.params[1])
    case 'countsUnknown': return t('k6e.ready.countsUnknown')
    case 'regressions': return it.params[0] === 1 ? t('k6e.ready.regressions.one') : t('k6e.ready.regressions', it.params[0])
    case 'rescanAfter': return t('k6e.ready.rescanAfter')
    case 'clean': return t('k6e.ready.clean', it.params[0])
    default: return it.key
  }
}
