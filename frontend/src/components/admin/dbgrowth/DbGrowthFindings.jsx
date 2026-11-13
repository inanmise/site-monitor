import { useEffect, useState } from 'react'
import { AlertTriangle, ExternalLink, Lock, RotateCcw, Scissors, ShieldAlert } from 'lucide-react'
import { api } from '../../../api/client'
import { navigateTo } from '../../../utils/navigate.js'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { KvList } from '../health/HealthParts.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import {
  MONITOR_TABS, affectedText, applyGate, findingAction, findingArgs, findingBodyKey, formatBytes, formatNumber, needsCompliance,
  severityTone,
} from './dbGrowthModel.js'

/**
 * Veritabanı büyümesi önerileri (2026-11-13). Her satır: önem rozeti + başlık + tablo + açıklama + eylem.
 * "Uygula" yalnız GLOBAL yöneticide ve yalnız kısaltma adayında; diğer herkes Ayarlar → Veri Saklama'ya bağlantı görür
 * (`ret_focus` politikayı açıp vurgular). DBA / kod işi olan öneriler yalnız etiket taşır — uygulama hiçbir zaman
 * VACUUM FULL / REINDEX / pg_repack çalıştırmaz.
 */
export function openRetention(policyId) {
  navigateTo('settings', { sec: 'retention', ret_focus: policyId })
}

export default function DbGrowthFindings({
  findings, t, canApply = false, canOpenRetention = false, onApplied, slot = 'dbg-findings', emptyText,
}) {
  const [apply, setApply] = useState(null)
  const list = Array.isArray(findings) ? findings : []
  if (!list.length) {
    return (
      <p data-slot={`${slot}-empty`} className="m-0 rounded-md bg-muted/40 px-3 py-2.5 text-sm text-muted-foreground">
        {emptyText || t('dbg.findings.none')}
      </p>
    )
  }
  return (
    <>
      <ul data-slot={slot} className="m-0 flex list-none flex-col gap-2 p-0">
        {list.map((f, i) => {
          const action = findingAction(f, { canApply, canOpenRetention })
          return (
            <li key={`${f.code}-${f.table || ''}-${f.params?.index || ''}-${i}`} data-slot="dbg-finding" data-code={f.code}
              data-severity={f.severity} className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5 shadow-xs">
              <div className="flex min-w-0 flex-wrap items-center gap-2">
                <ToneBadge tone={severityTone(f.severity)} data-slot="dbg-finding-sev" className="rounded-md font-semibold">
                  {t(`dbg.sev.${f.severity}`)}
                </ToneBadge>
                <span className="min-w-0 text-sm font-semibold break-words text-foreground">{t(`dbg.adv.${f.code}.title`)}</span>
                {f.table && <code className="min-w-0 font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">{f.table}</code>}
              </div>
              <p className="m-0 text-[13px] leading-snug text-muted-foreground [overflow-wrap:anywhere]">
                {t(findingBodyKey(f), ...findingArgs(f, t))}
              </p>
              {affectedText(f, t) && (
                <p data-slot="dbg-finding-affects" className="m-0 text-[13px] leading-snug font-medium text-foreground [overflow-wrap:anywhere]">
                  {affectedText(f, t)}
                </p>
              )}
              <div className="flex flex-wrap items-center gap-2">
                {action === 'apply' && (
                  <Button type="button" size="sm" data-slot="dbg-apply" onClick={() => setApply(f)} className="pointer-coarse:h-10">
                    <Scissors aria-hidden="true" />{t('dbg.action.apply_retention')}
                  </Button>
                )}
                {(action === 'apply' || action === 'open') && (
                  <Button type="button" size="sm" variant="outline" data-slot="dbg-open-retention" onClick={() => openRetention(f.policy_id)}
                    className="pointer-coarse:h-10">
                    <ExternalLink aria-hidden="true" />{t('dbg.action.open_retention')}
                  </Button>
                )}
                {action === 'monitor' && (
                  <Button type="button" size="sm" variant="outline" data-slot="dbg-open-monitor" className="pointer-coarse:h-10"
                    onClick={() => navigateTo(MONITOR_TABS[f.params.type], { monitor: String(f.params.monitor_id) })}>
                    <ExternalLink aria-hidden="true" />{t('dbg.openMonitor')}
                  </Button>
                )}
                {!action && ['dba', 'code'].includes(f.action) && (
                  <ToneBadge tone="muted" data-slot="dbg-action-label" className="rounded-md">{t(`dbg.action.${f.action}`)}</ToneBadge>
                )}
                {!action && f.action === 'apply_retention' && !canOpenRetention && (
                  <span className="text-xs text-muted-foreground">{t('dbg.applyAdminOnly')}</span>
                )}
              </div>
            </li>
          )
        })}
      </ul>
      {apply && <ApplyRetentionDialog finding={apply} t={t} onClose={() => setApply(null)}
        onApplied={(info) => { setApply(null); onApplied?.(info) }} />}
    </>
  )
}

/**
 * Saklama kısaltma onayı (bağımsız inceleme 2026-11-13 ile genişletildi).
 *
 * <p>Ayar anahtarı birden çok tabloyu yönetebilir (ör. bildirim günlükleri): pencere ETKİLENEN TÜM tabloları — veri
 * sınıfı ve ilk gece silinecek satır tahminiyle — listeler; taban, anahtarı paylaşan kuralların en büyüğüdür; uyum uyarısı
 * herhangi biri kişisel / güvenlik denetimi verisiyse çıkar. Güncel süre canlı okunur: okunamazsa ya da önerilenden kısa /
 * eşitse (eski analiz satırı) onay KAPALIDIR — uygulamak süreyi uzatırdı. Yasal tutma açıkken hiçbir satır silinmez ve
 * pencere bunu söyler. Kayıt Ayarlar → Veri Saklama'nın AYNI ucundan geçer (aynı doğrulama, taban, denetim kaydı).
 */
export function ApplyRetentionDialog({ finding, t, onClose, onApplied }) {
  const [est, setEst] = useState(null)
  const [estError, setEstError] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const days = finding.suggested_days

  useEffect(() => {
    let alive = true   // geç yanıt (pencere kapandı / başka öneri açıldı / yeniden denendi) yok sayılır
    setEst(null); setEstError(null)
    api.admin.dbGrowth.whatIf(finding.policy_id, days).then((res) => {
      if (!alive) return
      if (res?.success) setEst(res.data)
      else setEstError(res?.error || t('dbg.apply.estFailed'))
    }).catch((e) => { if (alive) setEstError(e?.message || t('dbg.apply.estFailed')) })
    return () => { alive = false }
  }, [finding.policy_id, days, t, attempt])

  const gate = applyGate(est, estError, days)
  const hold = est?.hold_active === true || (est == null && finding.params?.hold === true)

  async function confirm() {
    if (gate !== 'ok') return
    setSaving(true); setError(null)
    try {
      const res = await api.admin.saveRetentionSettings({ [est?.setting_key || finding.setting_key]: String(days) })
      if (res?.success) onApplied({ hold })
      else setError(res?.error || t('settings.saveError'))
    } catch (e) {
      setError(e?.message || t('settings.saveError'))
    } finally {
      setSaving(false)
    }
  }

  const policies = Array.isArray(est?.policies) ? est.policies : []
  const affected = Number(est?.affected_count ?? finding.params?.affected_count ?? 1)
  const compliance = est
    ? est.compliance === true || policies.some((p) => p.compliance === true) || needsCompliance(est.data_class)
    : finding.params?.compliance === true || needsCompliance(finding.data_class)
  const floor = est?.min_days ?? finding.min_days
  const estimateLabel = hold ? t('dbg.apply.estimateHold') : t('dbg.apply.estimate')
  const estimateText = (m) => {
    if (m?.rows == null) return <span className="text-muted-foreground">{t('dbg.apply.estUnknown')}</span>
    return (
      <span className="tabular-nums">
        {t('dbg.apply.estRows', formatNumber(m.rows), m.bytes_estimate != null ? formatBytes(m.bytes_estimate) : '—',
          t(`dbg.estMethod.${m.rows_method || 'uniform'}`))}
      </span>
    )
  }

  return (
    <ModalShell open onClose={saving ? () => {} : onClose} size="md" icon={Scissors} busy={saving} scrollBody
      title={t('dbg.apply.title', finding.table || '')}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={saving}>{t('dbg.apply.cancel')}</Button>
          <Button type="button" variant="destructive" data-slot="dbg-apply-confirm" data-gate={gate} onClick={confirm}
            disabled={saving || gate !== 'ok'} aria-busy={saving || undefined}>
            {saving ? t('dbg.apply.saving') : t('dbg.apply.confirm', formatNumber(days))}
          </Button>
        </>
      )}>
      <div data-slot="dbg-apply-dialog" data-gate={gate} data-hold={hold ? 'true' : undefined} className="flex flex-col gap-3 text-sm">
        {gate === 'stale' && (
          <div data-slot="dbg-apply-stale">
            <AlertBanner tone="warning" icon={AlertTriangle} className="mb-0" title={t('dbg.apply.notValidTitle')}>
              {t('dbg.apply.notValid', formatNumber(est.current_days), formatNumber(days))}
            </AlertBanner>
          </div>
        )}
        {gate === 'unknown' && (
          <div data-slot="dbg-apply-unknown">
            <AlertBanner tone="warning" icon={AlertTriangle} className="mb-0"
              actions={(
                <Button type="button" size="sm" variant="secondary" data-slot="dbg-apply-retry" className="pointer-coarse:h-10"
                  onClick={() => setAttempt((n) => n + 1)}>
                  <RotateCcw aria-hidden="true" />{t('db.retry')}
                </Button>
              )}>
              {t('dbg.apply.currentUnknown')}
              {estError && <span className="mt-1 block text-xs [overflow-wrap:anywhere]">{estError}</span>}
            </AlertBanner>
          </div>
        )}
        <KvList>
          <dt>{t('dbg.apply.table')}</dt><dd className="font-mono text-xs [overflow-wrap:anywhere]">{finding.table}</dd>
          <dt>{t('dbg.apply.setting')}</dt>
          <dd data-slot="dbg-apply-setting" className="font-mono text-xs [overflow-wrap:anywhere]">{est?.setting_key || finding.setting_key}</dd>
          <dt>{t('dbg.apply.change')}</dt>
          <dd data-slot="dbg-apply-change" className="font-semibold tabular-nums">
            {t('dbg.apply.daysChange', est ? formatNumber(est.current_days) : '…', formatNumber(days))}
          </dd>
          <dt>{t('dbg.apply.floor')}</dt><dd className="tabular-nums">{t('dbg.daysCount', formatNumber(floor))}</dd>
          {affected <= 1 && (
            <>
              <dt>{t('dbg.apply.dataClass')}</dt><dd>{t(`ret.class.${est?.data_class || finding.data_class}`)}</dd>
            </>
          )}
          <dt>{estimateLabel}</dt>
          <dd data-slot="dbg-apply-estimate">
            {est == null && !estError && <span className="text-muted-foreground">{t('dbg.apply.estimating')}</span>}
            {estError && <span className="text-muted-foreground">{t('dbg.apply.estUnavailable')}</span>}
            {est && (affected > 1
              ? (est.total_rows == null
                ? <span className="text-muted-foreground">{t('dbg.apply.estUnknown')}</span>
                : <span className="tabular-nums">{t('dbg.apply.estTotal', formatNumber(est.total_rows),
                  est.total_bytes_estimate != null ? formatBytes(est.total_bytes_estimate) : '—')}</span>)
              : estimateText(est))}
          </dd>
        </KvList>

        {hold && (
          <div data-slot="dbg-apply-hold">
            <AlertBanner tone="info" icon={Lock} className="mb-0" title={t('dbg.apply.holdTitle')}>
              {t('dbg.apply.holdBody')}
            </AlertBanner>
          </div>
        )}

        {affected > 1 && (
          <section data-slot="dbg-apply-affected" className="flex min-w-0 flex-col gap-1.5">
            <h3 className="m-0 text-sm font-semibold text-foreground">{t('dbg.apply.affectedTitle', formatNumber(affected))}</h3>
            <p className="m-0 text-xs text-muted-foreground">{t('dbg.apply.affectedHint')}</p>
            {policies.length > 0 && (
              <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
                {policies.map((m) => (
                  <li key={m.policy_id} data-slot="dbg-apply-policy" data-table={m.table} data-compliance={m.compliance ? 'true' : 'false'}
                    className="flex min-w-0 flex-col gap-1 rounded-md border bg-card px-2.5 py-2">
                    <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                      <code className="min-w-0 font-mono text-xs font-semibold text-foreground [overflow-wrap:anywhere]">{m.table}</code>
                      <ToneBadge tone={m.compliance ? 'warning' : 'muted'} className="h-auto rounded-md whitespace-normal">
                        {m.compliance && <ShieldAlert aria-hidden="true" />}{t(`ret.class.${m.data_class}`)}
                      </ToneBadge>
                    </div>
                    <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
                      {estimateLabel}: {estimateText(m)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}

        {compliance && (
          <AlertBanner tone="warning" icon={ShieldAlert} title={t('dbg.apply.complianceTitle')} className="mb-0">
            {t('dbg.apply.complianceBody')}
          </AlertBanner>
        )}
        <AlertBanner tone="info" className="mb-0">{t('dbg.vacuumNote')}</AlertBanner>
        {error && <AlertBanner tone="danger" role="alert" icon={AlertTriangle} className="mb-0">{error}</AlertBanner>}
      </div>
    </ModalShell>
  )
}
