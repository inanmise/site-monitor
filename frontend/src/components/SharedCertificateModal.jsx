import { useEffect, useState } from 'react'
import { Link2, ShieldCheck, ExternalLink, Layers } from 'lucide-react'
import { api, formatDate, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import ModalShell from './ui/ModalShell.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import CopyableRef from './ui/CopyableRef.jsx'
import { navigateTo } from '../utils/navigate.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/** Kalan gün tonu (eski .shc-crit / .shc-warn); hücrede `data-tone` (crit|warn) test kancası. */
const DAYS_TONE = { crit: 'font-bold text-destructive', warn: 'font-semibold text-amber-700 dark:text-amber-400' }
const DIM = 'text-muted-foreground'

/**
 * Paylaşılan sertifika penceresi (2026-09-22, kullanıcı isteği): kart üzerindeki "N alan aynı sertifikayı paylaşıyor"
 * çipi tıklanınca açılır. Cevapladığı soru: "bu sertifikayı yenilersem BAŞKA nereleri etkiler, kimi haberdar etmeliyim,
 * nereye kurulacak?" — bu yüzden her eş için takım, platform, tier, port, durum/kalan gün, son kontrol gösterilir.
 * Alan adına tıklanınca o alanın kartına/envanterine gidilir. Kapsam dışı eşler sayıyla belirtilir (varlık gizlenmez).
 */
export default function SharedCertificateModal({ domain, onClose, onSelectDomain }) {
  const t = useT()
  const [state, setState] = useState({ loading: true, data: null, error: null })

  useEffect(() => {
    let alive = true
    setState({ loading: true, data: null, error: null })
    api.getSharedCertificate(domain)
      .then((r) => { if (alive) setState({ loading: false, data: r?.success ? r.data : null, error: r?.success ? null : (r?.error || t('shc.error')) }) })
      .catch((e) => { if (alive) setState({ loading: false, data: null, error: e?.message || t('shc.error') }) })
    return () => { alive = false }
  }, [domain]) // eslint-disable-line react-hooks/exhaustive-deps

  const d = state.data
  const peers = d?.peers || []
  const others = peers.filter((p) => !p.self)
  const tone = (p) => p.days_remaining == null ? undefined : p.days_remaining <= 7 ? 'crit' : p.days_remaining <= 30 ? 'warn' : undefined

  return (
    <ModalShell open onClose={onClose} title={t('shc.title')} icon={Link2} size="lg" scrollBody>
      {state.loading ? <LoadingBlock label={t('modal.loading')} fullWidth /> : state.error ? (
        <AlertBanner tone="danger" role="alert">{state.error}</AlertBanner>
      ) : (<>
        <p className="mb-3">{t('shc.lead', others.length, domain)}</p>

        {/* Sertifikanın kendisi — hangi sertifikadan söz ediyoruz (telefonda etiket üstte, geniş ekranda iki sütun) */}
        <dl data-slot="shc-cert" className="mb-4 grid grid-cols-1 gap-x-3 gap-y-1 rounded-lg border bg-muted/40 px-3 py-2.5 sm:grid-cols-[150px_minmax(0,1fr)]">
          <dt className="text-[.88em] text-muted-foreground">{t('shc.subject')}</dt><dd className="m-0 [overflow-wrap:anywhere]">{d?.subject || '—'}</dd>
          <dt className="text-[.88em] text-muted-foreground">{t('shc.issuer')}</dt>
          <dd className="m-0 flex flex-wrap items-center gap-1 [overflow-wrap:anywhere]"><ShieldCheck size={12} aria-hidden="true" /> {d?.issuer || '—'}</dd>
          <dt className="text-[.88em] text-muted-foreground">{t('shc.expiry')}</dt>
          <dd className="m-0">{d?.not_after ? formatDate(d.not_after) : '—'}{d?.days_remaining != null && <span className={DIM}> · {t('shc.daysLeft', d.days_remaining)}</span>}</dd>
          <dt className="text-[.88em] text-muted-foreground">{t('shc.fingerprint')}</dt>
          <dd className="m-0 min-w-0">{d?.fingerprint ? <CopyableRef value={d.fingerprint} copyLabel={t('shc.copyFp')} copiedLabel={t('err.copied')} /> : '—'}</dd>
          {Array.isArray(d?.san) && d.san.length > 0 && (<>
            <dt className="text-[.88em] text-muted-foreground">{t('shc.san', d.san.length)}</dt>
            <dd className="m-0 flex flex-wrap gap-1">{d.san.map((s) => <Badge key={s} variant="outline" className="font-normal">{s}</Badge>)}</dd>
          </>)}
        </dl>

        <div className="mb-1.5 text-[.92em] font-bold">{t('shc.peersTitle', peers.length)}{d?.hidden > 0 && <span className={cn(DIM, 'font-normal')}> · {t('shc.hidden', d.hidden)}</span>}</div>
        {peers.length === 0 ? <StatusBlock tone="neutral" icon={Link2} title={t('shc.none')} /> : (
          <div className="rounded-lg border">
            <Table data-testid="shc-table" className="text-[.9em]">
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="bg-muted/60 text-muted-foreground">{t('shc.colDomain')}</TableHead>
                  <TableHead className="bg-muted/60 text-muted-foreground">{t('shc.colTeam')}</TableHead>
                  <TableHead className="bg-muted/60 text-muted-foreground">{t('shc.colPlatform')}</TableHead>
                  <TableHead className="bg-muted/60 text-muted-foreground">{t('shc.colTier')}</TableHead>
                  <TableHead className="bg-muted/60 text-muted-foreground">{t('shc.colStatus')}</TableHead>
                  <TableHead className="bg-muted/60 text-muted-foreground">{t('shc.colChecked')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {peers.map((p) => (
                  <TableRow key={p.domain} data-self={p.self ? 'true' : undefined} data-state={p.self ? 'selected' : undefined}>
                    <TableCell>
                      <span className="inline-flex flex-wrap items-center gap-1.5">
                        <Button type="button" variant="link" data-shc-domain="true" className="h-auto gap-1 p-0 font-bold text-foreground hover:text-primary"
                          onClick={() => { onSelectDomain?.(p.domain); onClose?.() }} title={t('shc.openDomain')}>
                          {p.domain} <ExternalLink size={11} aria-hidden="true" />
                        </Button>
                        {p.self && <Badge variant="secondary" className="bg-primary/10 text-primary dark:bg-primary/20">{t('shc.thisDomain')}</Badge>}
                        {!p.in_inventory && <Badge variant="outline" title={t('shc.notInInventoryTip')}>{t('shc.notInInventory')}</Badge>}
                        {p.port && p.port !== 443 && <span className={DIM}> :{p.port}</span>}
                      </span>
                    </TableCell>
                    <TableCell>{p.team_name ? <TeamBadge teamId={p.team_id} teamName={p.team_name} /> : <span className={DIM}>—</span>}</TableCell>
                    <TableCell>{p.platform ? <span className="inline-flex items-center gap-1 whitespace-nowrap" title={p.platform_detail || ''}><Layers size={11} aria-hidden="true" /> {p.platform}</span> : <span className={DIM}>—</span>}</TableCell>
                    <TableCell>{p.tier ? `T${p.tier}` : <span className={DIM}>—</span>}</TableCell>
                    <TableCell data-tone={tone(p)} className={DAYS_TONE[tone(p)]}>
                      {p.days_remaining == null ? '—' : p.days_remaining < 0 ? t('shc.expiredAgo', Math.abs(p.days_remaining)) : t('shc.daysLeft', p.days_remaining)}
                      {p.not_after && <span className={cn(DIM, 'font-normal')}> · {formatDate(p.not_after)}</span>}
                    </TableCell>
                    <TableCell className={DIM}>{p.checked_at ? formatDateSec(p.checked_at) : '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        <p className="mt-3 text-[.85em] text-muted-foreground">{t('shc.foot')}</p>
        <div className="mt-2.5 flex justify-end">
          <Button type="button" variant="secondary" size="sm" onClick={() => { navigateTo('renewal'); onClose?.() }}>{t('shc.toRenewal')}</Button>
        </div>
      </>)}
    </ModalShell>
  )
}
