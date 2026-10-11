import { ArrowRight, RefreshCw, Users } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { useElementWidthState } from '../../../hooks/useElementWidth.js'
import { cn } from '@/lib/utils'
import { OUTCOMES } from './ldapRoleMappingModel.js'

const TONE_CLS = {
  danger: 'border-destructive/40 bg-destructive/10 text-destructive',
  warning: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300',
  info: 'border-sky-500/40 bg-sky-500/10 text-sky-800 dark:text-sky-300',
  muted: 'border-border bg-muted/40 text-muted-foreground',
}
const toneOf = (key) => OUTCOMES.find((o) => o.key === key)?.tone || 'muted'
const BADGE_TONE = { danger: 'danger', warning: 'warning', info: 'info', muted: 'muted' }

/**
 * "Kim ne olacak?" penceresi (2026-10-11) — sunucu önizlemesi: değerlendirilen AD hesabı sayısı, sonuç sayaçları,
 * kural başına AD'de bulunan grup / üye / eşleşen hesap, etkilenen hesaplar (düşürme önce). Adlar KESİLMEZ.
 */
export default function LdapRoleMappingPreview({ open, onClose, loading, error, data, onRetry }) {
  const t = useT()
  const [width, setEl] = useElementWidthState()
  const narrow = width > 0 && width < 560
  const counts = data?.counts || {}
  const rows = data?.rows || []
  const rules = data?.rules || []

  return (
    <ModalShell open={open} onClose={onClose} size="lg" scrollBody icon={Users} title={t('ldap.rm.previewTitle')}
      footer={(
        <>
          <Button variant="secondary" onClick={onRetry} disabled={loading}><RefreshCw aria-hidden="true" /> {t('ldap.rm.previewAgain')}</Button>
          <Button onClick={onClose}>{t('ldap.rm.close')}</Button>
        </>
      )}>
      <div ref={setEl} data-slot="ldap-rm-preview" className="flex min-w-0 flex-col gap-4">
        <p className="text-sm text-muted-foreground">{t('ldap.rm.previewDesc')}</p>
        {loading ? (
          <LoadingBlock label={t('ldap.rm.previewRunning')} />
        ) : error ? (
          <AlertBanner tone="danger" role="alert" className="mb-0">{error}</AlertBanner>
        ) : !data ? null : (
          <>
            <p className="text-sm font-medium">{t('ldap.rm.ldapUsers', data.ldap_users ?? 0)}</p>
            <ul className="grid grid-cols-2 gap-2 sm:grid-cols-4" aria-label={t('ldap.rm.summary')}>
              {OUTCOMES.map((o) => (
                <li key={o.key} data-slot="ldap-rm-count" data-outcome={o.key}
                  className={cn('flex flex-col gap-0.5 rounded-lg border px-3 py-2', TONE_CLS[o.tone])}>
                  <span className="text-xl font-bold tabular-nums">{counts[o.key] ?? 0}</span>
                  <span className="text-xs leading-tight font-medium">{t('ldap.rm.out.' + o.key)}</span>
                </li>
              ))}
            </ul>

            {rules.length > 0 && (
              <section className="flex flex-col gap-2" aria-labelledby="ldap-rm-rules-h">
                <h4 id="ldap-rm-rules-h" className="text-sm font-semibold">{t('ldap.rm.rulesTitle')}</h4>
                <ol className="flex flex-col gap-2">
                  {rules.map((r) => (
                    <li key={r.index} data-slot="ldap-rm-rule-result" className="flex flex-col gap-1 rounded-lg border p-2.5 text-sm">
                      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                        <span className="text-xs font-semibold text-muted-foreground tabular-nums">{r.index + 1}.</span>
                        <span className="min-w-0 font-medium [overflow-wrap:anywhere]">{r.group}</span>
                        <ArrowRight aria-hidden="true" className="size-3.5 text-muted-foreground" />
                        <ToneBadge tone="info">{r.role}</ToneBadge>
                      </div>
                      <div className="flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-muted-foreground">
                        <span>{t('ldap.rm.groupsFound', r.groups_found ?? 0)}</span>
                        <span>{t('ldap.rm.adMembers', r.ad_members ?? 0)}</span>
                        <span className="font-medium text-foreground">{t('ldap.rm.matched', r.matched_users ?? 0)}</span>
                      </div>
                      {r.groups_found === 0 && <span className="text-xs text-destructive">{t('ldap.rm.noGroupFound')}</span>}
                      {r.ad_members_truncated && <span className="text-xs text-amber-700 dark:text-amber-300">{t('ldap.rm.truncated')}</span>}
                    </li>
                  ))}
                </ol>
              </section>
            )}

            <section className="flex flex-col gap-2" aria-labelledby="ldap-rm-rows-h">
              <h4 id="ldap-rm-rows-h" className="text-sm font-semibold">{t('ldap.rm.changes')}</h4>
              {rows.length === 0 ? (
                <StatusBlock tone="neutral" title={t('ldap.rm.noChanges')} />
              ) : (
                <ul className={cn('grid gap-2', !narrow && 'sm:grid-cols-2')}>
                  {rows.map((r) => {
                    const tone = toneOf(r.outcome)
                    return (
                      <li key={r.username} data-slot="ldap-rm-row" data-outcome={r.outcome}
                        className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card p-2.5 text-sm">
                        <div className="flex min-w-0 flex-col">
                          <span className="font-medium [overflow-wrap:anywhere]">{r.display_name || r.username}</span>
                          <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{r.username}</span>
                        </div>
                        <div className="flex flex-wrap items-center gap-1.5">
                          <ToneBadge tone="muted">{r.role_before || '—'}</ToneBadge>
                          <ArrowRight aria-hidden="true" className="size-3.5 text-muted-foreground" />
                          <ToneBadge tone={BADGE_TONE[tone]}>{r.role_after || '—'}</ToneBadge>
                          <span className="text-xs font-medium">{t('ldap.rm.out.' + r.outcome)}</span>
                        </div>
                        <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
                          {r.rule_group ? t('ldap.rm.reasonRule', r.rule_group) : t('ldap.rm.reasonFallback')}
                        </span>
                      </li>
                    )
                  })}
                </ul>
              )}
              {data.rows_truncated && <p className="text-xs text-muted-foreground">{t('ldap.rm.rowsTruncated', rows.length)}</p>}
            </section>
          </>
        )}
      </div>
    </ModalShell>
  )
}
