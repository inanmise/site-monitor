import { lazy, Suspense, useCallback, useEffect, useState } from 'react'
import { Copy, Download, Eye, FileStack, FileUp, KeyRound, Layers, RefreshCw, Trash2 } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
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
// "Görüntüle" (2026-10-07): sürümün tarayıcı gibi sertifika hiyerarşisi — iç içe pencere, yalnız açılınca yüklenir
const VersionHierarchyDialog = lazy(() => import('./VersionHierarchyDialog.jsx'))

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

/**
 * Tek sürüm kartı — güncel olan vurgulu; önceki (kalan) sürüme göre fark (anahtar, SAN), "aynı sertifika yeniden
 * yüklendi" notu, PEM indirme ve — yalnız ESKİ sürümde, yönetebilen kullanıcıya — kalıcı "Sil" (`onDelete`).
 */
function VersionCard({ v, inventoryId, current, onDelete, onView, deleting = false }) {
  const t = useT()
  const added = Array.isArray(v.san_added) ? v.san_added : []
  const removed = Array.isArray(v.san_removed) ? v.san_removed : []
  const d = current ? daysFrom(v.not_after) : null
  const label = t('mcert.versionShort', v.version)
  return (
    <Card data-slot="mcert-version" data-version={v.version} data-current={v.current ? 'true' : 'false'}
      className={cn('min-w-0 gap-3 rounded-[10px] px-3 py-3 shadow-none sm:px-4', v.current && 'border-primary bg-primary/5 ring-1 ring-primary/30')}>
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1.5">
        <Badge variant={v.current ? 'default' : 'secondary'} className="h-6 rounded-md px-2 text-xs font-bold tabular-nums">{label}</Badge>
        {v.current
          ? <Badge variant="outline" data-slot="mcert-version-current" className={cn(CHIP, 'border-success/40 bg-success/10 text-success dark:bg-success/20')}>{t('mcert.ver.current')}</Badge>
          : <span className="text-xs text-muted-foreground">{t('mcert.ver.superseded', v.superseded_at ? formatDate(v.superseded_at) : '—', v.superseded_by || '—')}</span>}
        {v.same_as_previous === true && (
          <Badge variant="outline" data-slot="mcert-same-reupload" className={cn(CHIP, 'border-sky-500/35 bg-sky-500/10 text-sky-800 dark:bg-sky-500/20 dark:text-sky-300')}>
            <Copy aria-hidden="true" className="size-3" />{t('mcert.ver.sameAsPrevious')}
          </Badge>
        )}
        {v.key_changed === true && <Badge variant="outline" data-slot="mcert-key-changed" className={cn(CHIP, 'border-violet-500/35 bg-violet-500/10 text-violet-800 dark:bg-violet-500/20 dark:text-violet-300')}><KeyRound aria-hidden="true" className="size-3" />{t('mcert.ver.keyChanged')}</Badge>}
        {v.key_changed === false && v.same_as_previous !== true && <Badge variant="outline" data-slot="mcert-key-same" className={cn(CHIP, 'border-amber-500/35 bg-amber-500/10 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300')}><KeyRound aria-hidden="true" className="size-3" />{t('mcert.ver.keySame')}</Badge>}
        <span className="ml-auto flex flex-wrap items-center gap-1.5">
          {onView && v.id != null && (
            <Button type="button" variant="outline" size="sm" data-slot="mcert-version-view" className="h-9 pointer-coarse:h-10"
              aria-label={t('a11y.rowAction', label, t('chier.viewVersion'))} onClick={() => onView(v)}>
              <Eye aria-hidden="true" />{t('chier.viewAction')}
            </Button>
          )}
          <Button asChild variant="outline" size="sm" className="h-9 pointer-coarse:h-10">
            <a href={api.manualCerts.pemUrl(inventoryId, v.id)} download data-slot="mcert-pem"
              aria-label={t('a11y.rowAction', label, t('mcert.act.pem'))}
              onClick={(e) => { e.preventDefault(); downloadFromUrl(api.manualCerts.pemUrl(inventoryId, v.id)) }}>
              <Download aria-hidden="true" />{t('mcert.act.pemShort')}
            </a>
          </Button>
          {onDelete && !v.current && (
            <Button type="button" variant="outline" size="sm" data-slot="mcert-version-delete" disabled={deleting} aria-busy={deleting || undefined}
              className="h-9 text-destructive hover:bg-destructive/10 hover:text-destructive pointer-coarse:h-10"
              aria-label={t('a11y.rowAction', label, t('mcert.ver.delete'))} onClick={() => onDelete(v)}>
              {deleting ? <Spinner size={12} inline decorative /> : <Trash2 aria-hidden="true" />}{t('mcert.ver.delete')}
            </Button>
          )}
        </span>
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
 * kipinde açar (salt okunurda yok). Her an tek sertifika (güncel) izlenir.
 *
 * <p>Eski sürümü kalıcı silme (2026-10-07, kullanıcı isteği): yalnız GÜNCEL OLMAYAN sürümde ve "Yeni sürüm yükle" ile aynı
 * koşulda (yazma izni + kaydın takım kapsamı) "Sil" → tehlike onayı → `DELETE /manual-certs/{id}/versions/{vid}` →
 * bildirim + liste SESSİZ tazelenir (bileşen sökülmez, açık hiçbir şey sıfırlanmaz). 409 CURRENT_VERSION açıklanır.
 * "Yine de yükle" ile aynı sertifikanın yeniden yüklendiği sürüm "Aynı sertifika yeniden yüklendi" notunu taşır.
 *
 * <p>"Görüntüle" (2026-10-07, kullanıcı isteği): her sürüm kartında — sürümün sertifika hiyerarşisini tarayıcı gibi
 * (kök → ara → yaprak, seçili sertifikanın ayrıntısı, tek sertifika PEM indirme) iç içe pencerede açar
 * (VersionHierarchyDialog → certmodal/ManualCertHierarchy; okuma izniyle, salt okunurda da).
 *
 * <p>Test kancaları: `data-slot="mcert-versions"`, kart `mcert-version` (+ `data-current`, `data-version`), `mcert-pem`,
 * `mcert-version-delete`, `mcert-same-reupload`, `mcert-version-view`.
 */
export default function ManualCertVersions({ domain, readOnly = false, onRenewed, onOpenCert }) {
  const t = useT()
  const canEditInventory = usePermissions().canEdit('inventory.crud')
  const { showConfirm } = useDialog()
  const toast = useToast()
  const [state, setState] = useState({ status: 'loading' })
  const [wizard, setWizard] = useState(false)
  const [deletingId, setDeletingId] = useState(null)
  // "Görüntüle" ile açılan sürüm (tarayıcı gibi hiyerarşi penceresi) — null = kapalı
  const [viewing, setViewing] = useState(null)

  /**
   * `silent` (yeni sürüm kaydedildikten sonra): mevcut liste ekranda kalır, "yükleniyor" durumuna GEÇİLMEZ ve hata
   * eldeki veriyi ezmez. Eskiden tazeleme önce `loading` durumuna geçiyordu → bileşen erken dönüşle yükleme sihirbazını
   * SÖKÜYOR, `wizard` hâlâ açık olduğu için sihirbaz SIFIRDAN (1. adım "Dosya") yeniden açılıyordu: kullanıcı sonuç
   * adımını hiç görmüyor, boş bir yükleme penceresiyle karşılaşıyordu (canlı e2e, 2026-10-07).
   */
  const load = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setState({ status: 'loading' })
    const fail = (message) => setState((s) => (silent && s.status === 'ready' ? s : { status: 'error', message }))
    try {
      const inv = await api.admin.getInventoryByDomain(domain)
      if (!inv?.success) { fail(inv?.error); return }
      if (!inv.data?.id) { if (!silent) setState({ status: 'missing' }); return }
      const res = await api.manualCerts.get(inv.data.id)
      if (!res?.success) { fail(res?.error); return }
      setState({ status: 'ready', id: inv.data.id, detail: res.data || {}, canManage: (res.data?.can_manage ?? inv.data.can_manage) !== false })
    } catch (e) {
      fail(e?.message)
    }
  }, [domain])

  useEffect(() => { load() }, [load])

  /** Eski sürümü kalıcı sil — onay, istek, bildirim, sessiz tazeleme. */
  async function deleteVersion(v) {
    if (!v || v.current || deletingId != null || state.status !== 'ready') return
    const ok = await showConfirm({
      title: t('mcert.ver.deleteTitle', v.version),
      message: t('mcert.ver.deleteMessage', v.version),
      confirmText: t('mcert.ver.deleteConfirm'),
      variant: 'danger',
    })
    if (!ok) return
    setDeletingId(v.id)
    let res = null
    try {
      res = await api.manualCerts.deleteVersion(state.id, v.id)
    } catch (e) {
      res = { success: false, error: e?.message }
    } finally {
      setDeletingId(null)
    }
    if (res?.success) {
      toast.success(t('mcert.ver.deleted', v.version))
      load({ silent: true })
      return
    }
    if (res == null) return   // oturum düştü / bakım — istemci kendi akışını yürütür
    toast.error(res.code === 'CURRENT_VERSION' ? t('mcert.ver.deleteCurrent') : (res.error || t('mcert.ver.deleteFailed')))
    if (res.code === 'CURRENT_VERSION' || res.status === 404) load({ silent: true })
  }

  if (state.status === 'loading') return <LoadingBlock label={t('modal.loading')} fullWidth />
  if (state.status === 'error') {
    return (
      <StatusBlock tone="danger" icon={FileStack} role="alert" title={t('mcert.ver.loadError')} description={state.message || undefined}
        actions={<Button type="button" variant="outline" className="h-10" onClick={() => load()}><RefreshCw aria-hidden="true" />{t('mcert.retry')}</Button>} />
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
        ? <VersionCard v={current} inventoryId={state.id} current onView={setViewing} />
        : <StatusBlock tone="neutral" icon={FileStack} title={t('mcert.ver.none')} />}
      {older.length > 0 && (
        <section className="flex min-w-0 flex-col gap-2" aria-label={t('mcert.ver.history')}>
          <h4 className="m-0 text-sm font-semibold text-muted-foreground">{t('mcert.ver.history')}</h4>
          <ol className="m-0 flex list-none flex-col gap-2 p-0">
            {older.map((v) => (
              <li key={v.id ?? v.version}>
                <VersionCard v={v} inventoryId={state.id} current={false} onView={setViewing}
                  onDelete={canRenew ? deleteVersion : undefined} deleting={deletingId != null && deletingId === v.id} />
              </li>
            ))}
          </ol>
        </section>
      )}
      {wizard && (
        <Suspense fallback={null}>
          <UploadWizard renewTarget={{ inventory_id: state.id, domain }} onClose={() => setWizard(false)}
            onDone={() => { load({ silent: true }); onRenewed?.() }} onOpenCert={onOpenCert} />
        </Suspense>
      )}
      {viewing && (
        <Suspense fallback={null}>
          <VersionHierarchyDialog inventoryId={state.id} version={viewing} onClose={() => setViewing(null)} />
        </Suspense>
      )}
    </div>
  )
}
