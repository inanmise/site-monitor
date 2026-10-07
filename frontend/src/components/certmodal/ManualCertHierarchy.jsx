import { useEffect, useState } from 'react'
import { ListTree, RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import CertHierarchyView from './CertHierarchyView.jsx'

/**
 * Elle yüklenen sertifika sürümünün hiyerarşisini YÜKLER ve CertHierarchyView ile çizer (2026-10-07).
 * `GET /api/manual-certs/{id}/versions/{vid}/chain` — çevrim-dışı, yazmaz, alarm açmaz.
 *
 * <p>Hedef: `inventoryId` + `versionId` verilirse doğrudan (Sürümler sekmesi "Görüntüle", SSL sekmesinde önizlemenin
 * `inventory_id` / `manual_version_id`'si). Eksikse (eski sunucu) kayıt alan adından bulunur (`/admin/inventory/by-domain`)
 * ve GÜNCEL sürüm ayrıntıdan (`/manual-certs/{id}` → `current_version.id`) okunur.
 *
 * <p>Yükleniyor → LoadingBlock; hata → "Yeniden dene"li StatusBlock (ilk iletinin yerine sunucunun iletisi).
 */
async function resolveTarget({ inventoryId, versionId, domain }) {
  let inv = inventoryId ?? null
  let ver = versionId ?? null
  if (inv == null && domain) {
    const r = await api.admin.getInventoryByDomain(domain)
    inv = r?.success ? (r.data?.id ?? null) : null
  }
  if (inv != null && ver == null) {
    const d = await api.manualCerts.get(inv)
    ver = d?.success ? (d.data?.current_version?.id ?? null) : null
  }
  return inv != null && ver != null ? { inventoryId: inv, versionId: ver } : null
}

export default function ManualCertHierarchy({ inventoryId = null, versionId = null, domain = null, initialSelected = 'leaf' }) {
  const t = useT()
  const [state, setState] = useState({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let alive = true
    setState({ status: 'loading' })
    ;(async () => {
      try {
        const target = await resolveTarget({ inventoryId, versionId, domain })
        if (!alive) return
        if (!target) { setState({ status: 'error', message: null }); return }
        const res = await api.manualCerts.versionChain(target.inventoryId, target.versionId)
        if (!alive) return
        if (res?.success && Array.isArray(res.data?.nodes)) setState({ status: 'ready', data: res.data })
        else setState({ status: 'error', message: res?.error || null })
      } catch (e) {
        if (alive) setState({ status: 'error', message: e?.message || null })
      }
    })()
    return () => { alive = false }
  }, [inventoryId, versionId, domain, attempt])

  if (state.status === 'loading') return <LoadingBlock label={t('chier.loading')} fullWidth />
  if (state.status === 'error') {
    return (
      <StatusBlock tone="danger" icon={ListTree} role="alert" title={t('chier.loadFailed')} description={state.message || t('chier.loadFailedHint')}
        actions={(
          <Button type="button" variant="outline" className="h-10 gap-1.5" onClick={() => setAttempt((n) => n + 1)}>
            <RefreshCw aria-hidden="true" />{t('sslv.retry')}
          </Button>
        )} />
    )
  }
  return <CertHierarchyView nodes={state.data.nodes} initialSelected={initialSelected} />
}
