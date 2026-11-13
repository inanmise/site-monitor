import { useEffect, useState } from 'react'
import { AlertTriangle, ExternalLink, Scissors, ShieldAlert } from 'lucide-react'
import { api } from '../../../api/client'
import { navigateTo } from '../../../utils/navigate.js'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { KvList } from '../health/HealthParts.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import {
  MONITOR_TABS, findingAction, findingArgs, formatBytes, formatNumber, needsCompliance, severityTone,
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

export default function DbGrowthFindings({ findings, t, canApply = false, canOpenRetention = false, onApplied, slot = 'dbg-findings' }) {
  const [apply, setApply] = useState(null)
  const list = Array.isArray(findings) ? findings : []
  if (!list.length) {
    return <p data-slot={`${slot}-empty`} className="m-0 rounded-md bg-muted/40 px-3 py-2.5 text-sm text-muted-foreground">{t('dbg.findings.none')}</p>
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
                {t(`dbg.adv.${f.code}.body`, ...findingArgs(f, t))}
              </p>
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
        onApplied={() => { setApply(null); onApplied?.() }} />}
    </>
  )
}

/**
 * Saklama kısaltma onayı: mevcut → önerilen gün, taban, veri sınıfı (kişisel / güvenlik denetiminde uyum uyarısı) ve
 * ilk gece temizliğinin sileceği satır tahmini (zaman aşımlı sayım ya da histogram — sunucu söyler). Kayıt Ayarlar →
 * Veri Saklama'nın AYNI ucundan geçer (aynı doğrulama, taban, denetim kaydı).
 */
export function ApplyRetentionDialog({ finding, t, onClose, onApplied }) {
  const [est, setEst] = useState(null)
  const [estError, setEstError] = useState(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const days = finding.suggested_days

  useEffect(() => {
    let alive = true   // geç yanıt (pencere kapandı / başka öneri açıldı) yok sayılır
    setEst(null); setEstError(null)
    api.admin.dbGrowth.whatIf(finding.policy_id, days).then((res) => {
      if (!alive) return
      if (res?.success) setEst(res.data)
      else setEstError(res?.error || t('dbg.apply.estFailed'))
    }).catch((e) => { if (alive) setEstError(e?.message || t('dbg.apply.estFailed')) })
    return () => { alive = false }
  }, [finding.policy_id, days, t])

  async function confirm() {
    setSaving(true); setError(null)
    try {
      const res = await api.admin.saveRetentionSettings({ [finding.setting_key]: String(days) })
      if (res?.success) onApplied()
      else setError(res?.error || t('settings.saveError'))
    } catch (e) {
      setError(e?.message || t('settings.saveError'))
    } finally {
      setSaving(false)
    }
  }

  const compliance = needsCompliance(est?.data_class || finding.data_class)
  const rows = est?.rows
  return (
    <ModalShell open onClose={saving ? () => {} : onClose} size="md" icon={Scissors} busy={saving} title={t('dbg.apply.title', finding.table || '')}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={saving}>{t('dbg.apply.cancel')}</Button>
          <Button type="button" data-slot="dbg-apply-confirm" onClick={confirm} disabled={saving} aria-busy={saving || undefined}>
            {saving ? t('dbg.apply.saving') : t('dbg.apply.confirm', formatNumber(days))}
          </Button>
        </>
      )}>
      <div data-slot="dbg-apply-dialog" className="flex flex-col gap-3 text-sm">
        <KvList>
          <dt>{t('dbg.apply.table')}</dt><dd className="font-mono text-xs [overflow-wrap:anywhere]">{finding.table}</dd>
          <dt>{t('dbg.apply.change')}</dt>
          <dd data-slot="dbg-apply-change" className="font-semibold tabular-nums">
            {t('dbg.apply.daysChange', formatNumber(est?.current_days ?? finding.current_days), formatNumber(days))}
          </dd>
          <dt>{t('dbg.apply.floor')}</dt><dd className="tabular-nums">{t('dbg.daysCount', formatNumber(est?.min_days ?? finding.min_days))}</dd>
          <dt>{t('dbg.apply.dataClass')}</dt><dd>{t(`ret.class.${est?.data_class || finding.data_class}`)}</dd>
          <dt>{t('dbg.apply.estimate')}</dt>
          <dd data-slot="dbg-apply-estimate">
            {est == null && !estError && <span className="text-muted-foreground">{t('dbg.apply.estimating')}</span>}
            {estError && <span className="text-muted-foreground">{t('dbg.apply.estUnavailable')}</span>}
            {est && (rows == null
              ? <span className="text-muted-foreground">{t('dbg.apply.estUnknown')}</span>
              : <span className="tabular-nums">{t('dbg.apply.estRows', formatNumber(rows),
                est.bytes_estimate != null ? formatBytes(est.bytes_estimate) : '—', t(`dbg.estMethod.${est.rows_method || 'uniform'}`))}</span>)}
          </dd>
        </KvList>
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
