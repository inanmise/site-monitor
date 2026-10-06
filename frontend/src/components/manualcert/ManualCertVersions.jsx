import { lazy, Suspense, useCallback, useEffect, useState } from 'react'
import { Download, FileStack, FileUp, KeyRound, Layers, RefreshCw } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import { dateOnly } from '../certcard/certCardModel.js'
import { toUtc } from '../../utils/localDay.js'
import { daysText, downloadFromUrl } from './manualCertModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

const UploadWizard = lazy(() => import('./UploadWizard.jsx'))

const CHIP = 'h-auto min-h-5 rounded-md px-1.5 py-0.5 text-[11px] font-semibold whitespace-normal'

function Fact({ label, children, mono = false, full = false }) {
  return (
    <div className={cn('min-w-0', full && 'sm:col-span-2')}>
      <dt className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
      <dd className={cn('m-0 min-w-0 text-[13px] break-words [overflow-wrap:anywhere]', mono && 'font-mono text-xs')}>{children || '—'}</dd>
    </div>
  )
}

/** Gün kalan (güncel sürüm) — bitiş tarihinden; dolmuşsa kırmızı. */
function daysFrom(iso) {
  if (!iso) return null
  const end = Date.parse(toUtc(String(iso)))
  return Number.isFinite(end) ? Math.floor((end - Date.now()) / 86400000) : null
}

/** Tek sürüm kartı — güncel olan vurgulu; önceki sürüme göre fark (anahtar, SAN) ve PEM indirme. */
function VersionCard({ v, inventoryId, current }) {
  const t = useT()
  const added = Array.isArray(v.san_added) ? v.san_added : []
  const removed = Array.isArray(v.san_removed) ? v.san_removed : []
  const d = current ? daysFrom(v.not_after) : null
  return (
    <Card data-slot="mcert-version" data-version={v.version} data-current={v.current ? 'true' : 'false'}
      className={cn('min-w-0 gap-3 rounded-[10px] px-3 py-3 shadow-none sm:px-4', v.current && 'border-primary bg-primary/5 ring-1 ring-primary/30')}>
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1.5">
        <Badge variant={v.current ? 'default' : 'secondary'} className="h-6 rounded-md px-2 text-xs font-bold tabular-nums">{t('mcert.versionShort', v.version)}</Badge>
        {v.current
          ? <Badge variant="outline" data-slot="mcert-version-current" className={cn(CHIP, 'border-success/40 bg-success/10 text-success dark:bg-success/20')}>{t('mcert.ver.current')}</Badge>
          : <span className="text-xs text-muted-foreground">{t('mcert.ver.superseded', v.superseded_at ? formatDate(v.superseded_at) : '—', v.superseded_by || '—')}</span>}
        {v.key_changed === true && <Badge variant="outline" data-slot="mcert-key-changed" className={cn(CHIP, 'border-violet-500/35 bg-violet-500/10 text-violet-800 dark:bg-violet-500/20 dark:text-violet-300')}><KeyRound aria-hidden="true" className="size-3" />{t('mcert.ver.keyChanged')}</Badge>}
        {v.key_changed === false && <Badge variant="outline" data-slot="mcert-key-same" className={cn(CHIP, 'border-amber-500/35 bg-amber-500/10 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300')}><KeyRound aria-hidden="true" className="size-3" />{t('mcert.ver.keySame')}</Badge>}
        <Button asChild variant="outline" size="sm" className="ml-auto h-9 pointer-coarse:h-10">
          <a href={api.manualCerts.pemUrl(inventoryId, v.id)} download data-slot="mcert-pem"
            aria-label={t('a11y.rowAction', t('mcert.versionShort', v.version), t('mcert.act.pem'))}
            onClick={(e) => { e.preventDefault(); downloadFromUrl(api.manualCerts.pemUrl(inventoryId, v.id)) }}>
            <Download aria-hidden="true" />{t('mcert.act.pemShort')}
          </a>
        </Button>
      </div>
      <dl className="m-0 grid min-w-0 grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2">
        <Fact label={t('mcert.entry.subject')} full>{v.subject_dn || v.subject}</Fact>
        <Fact label={t('mcert.entry.issuer')} full>{v.issuer_dn || v.issuer}</Fact>
        <Fact label={t('mcert.ver.validity')}>
          <span className="tabular-nums">{dateOnly(v.not_before)} → {dateOnly(v.not_after)}</span>
          {d != null && <span className={cn('ml-1.5 text-xs font-semibold', d < 0 ? 'text-destructive' : d <= 30 ? 'text-amber-700 dark:text-amber-400' : 'text-muted-foreground')}>({daysText(t, d)})</span>}
        </Fact>
        <Fact label={t('mcert.entry.key')}>{[v.key_alg, v.key_size].filter(Boolean).join(' ')}{v.signature_algorithm ? ` · ${v.signature_algorithm}` : ''}</Fact>
        <Fact label={t('mcert.ver.fingerprint')} mono full>
          <span className="flex min-w-0 items-start gap-1">
            <span className="min-w-0 self-center break-all">{v.fingerprint}</span>
            {v.fingerprint && <CopyButton value={v.fingerprint} label={t('mcert.ver.copyFp')} copiedLabel={t('err.copied')} variant="ghost" buttonSize="icon-sm"
              className="-my-1 shrink-0 text-muted-foreground hover:text-primary pointer-coarse:size-10" />}
          </span>
        </Fact>
        <Fact label={t('mcert.ver.uploaded')}>{[v.uploaded_at ? formatDate(v.uploaded_at) : null, v.uploaded_by_name].filter(Boolean).join(' · ')}</Fact>
        <Fact label={t('mcert.ver.file')}>
          {v.file_name ? <span className="font-mono text-xs">{v.file_name}{v.file_format ? ` (${v.file_format})` : ''}{v.source_alias ? ` · ${v.source_alias}` : ''}</span> : null}
          {v.chain_count ? <span className="block text-xs text-muted-foreground">{t('mcert.ver.chainCount', v.chain_count)}</span> : null}
        </Fact>
        {v.note && <Fact label={t('mcert.ver.note')} full><span className="whitespace-pre-line">{v.note}</span></Fact>}
        {(added.length > 0 || removed.length > 0) && (
          <Fact label={t('mcert.ver.sanDiff')} full>
            <span className="flex flex-wrap gap-1">
              {added.map((s) => <Badge key={`+${s}`} variant="outline" className={cn(CHIP, 'border-success/40 bg-success/10 font-mono font-normal text-success [overflow-wrap:anywhere]')}>+ {s}</Badge>)}
              {removed.map((s) => <Badge key={`-${s}`} variant="outline" className={cn(CHIP, 'border-destructive/40 bg-destructive/10 font-mono font-normal text-destructive [overflow-wrap:anywhere]')}>− {s}</Badge>)}
            </span>
          </Fact>
        )}
      </dl>
    </Card>
  )
}

/**
 * Sertifika penceresi → "Sürümler" sekmesi (yalnız manuel kayıtlar, 2026-10-06). Kayıt alan adından bulunur
 * (`GET /admin/inventory/by-domain`), ayrıntı `GET /manual-certs/{id}`: güncel sürüm kartı (vurgulu) + önceki sürümler
 * (yeniden eskiye) — parmak izi (kopyala), geçerlilik, veren, yükleyen/zaman, dosya/biçim, not, kim/ne zaman yerini aldı,
 * önceki sürüme göre anahtar değişimi ve SAN farkı, her sürüm için PEM indir. "Yeni sürüm yükle" sihirbazı yenileme
 * kipinde açar (salt okunurda yok). Eski sürümler hiçbir zaman silinmez; her an tek sertifika (güncel) izlenir.
 *
 * <p>Test kancaları: `data-slot="mcert-versions"`, kart `mcert-version` (+ `data-current`, `data-version`), `mcert-pem`.
 */
export default function ManualCertVersions({ domain, readOnly = false, onRenewed, onOpenCert }) {
  const t = useT()
  const canEditInventory = usePermissions().canEdit('inventory.crud')
  const [state, setState] = useState({ status: 'loading' })
  const [wizard, setWizard] = useState(false)

  const load = useCallback(async () => {
    setState({ status: 'loading' })
    try {
      const inv = await api.admin.getInventoryByDomain(domain)
      if (!inv?.success) { setState({ status: 'error', message: inv?.error }); return }
      if (!inv.data?.id) { setState({ status: 'missing' }); return }
      const res = await api.manualCerts.get(inv.data.id)
      if (!res?.success) { setState({ status: 'error', message: res?.error }); return }
      setState({ status: 'ready', id: inv.data.id, detail: res.data || {}, canManage: (res.data?.can_manage ?? inv.data.can_manage) !== false })
    } catch (e) {
      setState({ status: 'error', message: e?.message })
    }
  }, [domain])

  useEffect(() => { load() }, [load])

  if (state.status === 'loading') return <LoadingBlock label={t('modal.loading')} fullWidth />
  if (state.status === 'error') {
    return (
      <StatusBlock tone="danger" icon={FileStack} role="alert" title={t('mcert.ver.loadError')} description={state.message || undefined}
        actions={<Button type="button" variant="outline" className="h-10" onClick={load}><RefreshCw aria-hidden="true" />{t('mcert.retry')}</Button>} />
    )
  }
  if (state.status === 'missing') return <StatusBlock tone="neutral" icon={FileStack} title={t('mcert.ver.missing')} />

  const versions = Array.isArray(state.detail.versions) ? state.detail.versions : []
  const sorted = [...versions].sort((a, b) => (Number(b.version) || 0) - (Number(a.version) || 0))
  const current = sorted.find((v) => v.current) || sorted[0] || null
  const older = sorted.filter((v) => v !== current)
  // Yeni sürüm = dosya çözümleme + yazma: envanter ekleme izni (inventory.crud/edit) + kaydın takım kapsamı (can_manage)
  const canRenew = !readOnly && state.canManage && canEditInventory

  return (
    <div data-slot="mcert-versions" className="flex min-w-0 flex-col gap-3 pt-1 pb-2">
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <h3 className="m-0 flex items-center gap-2 text-base font-semibold">
            <Layers aria-hidden="true" className="size-4 text-primary" />{t('mcert.ver.title', versions.length)}
          </h3>
          <p className="m-0 text-xs text-muted-foreground">{t('mcert.ver.desc')}</p>
        </div>
        {canRenew && (
          <Button type="button" data-slot="mcert-renew-btn" className="h-10 sm:h-9 pointer-coarse:h-10" onClick={() => setWizard(true)}>
            <FileUp aria-hidden="true" />{t('mcert.act.renew')}
          </Button>
        )}
      </div>
      {current
        ? <VersionCard v={current} inventoryId={state.id} current />
        : <StatusBlock tone="neutral" icon={FileStack} title={t('mcert.ver.none')} />}
      {older.length > 0 && (
        <section className="flex min-w-0 flex-col gap-2" aria-label={t('mcert.ver.history')}>
          <h4 className="m-0 text-sm font-semibold text-muted-foreground">{t('mcert.ver.history')}</h4>
          <ol className="m-0 flex list-none flex-col gap-2 p-0">
            {older.map((v) => <li key={v.id ?? v.version}><VersionCard v={v} inventoryId={state.id} current={false} /></li>)}
          </ol>
        </section>
      )}
      {wizard && (
        <Suspense fallback={null}>
          <UploadWizard renewTarget={{ inventory_id: state.id, domain }} onClose={() => setWizard(false)}
            onDone={() => { load(); onRenewed?.() }} onOpenCert={onOpenCert} />
        </Suspense>
      )}
    </div>
  )
}
