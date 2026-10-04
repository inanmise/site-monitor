import { lazy, Suspense, useCallback, useEffect, useState } from 'react'
import { History, PencilLine, UserPlus } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { LoadingBlock } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { TimeAgo } from '../inventory/InventoryDetailParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'

const ChangeHistoryTab = lazy(() => import('../history/ChangeHistoryTab.jsx'))

/**
 * Sertifika penceresi → **Değişiklik geçmişi** (2026-10-05, kullanıcı isteği: "sertifika izlemeyi kim ekledi, ne
 * değiştirdi, ne zaman güncellendi — dashboard kartına tıklayınca geçmiş göremiyorum").
 *
 * <p>Geçmiş YENİ bir kaynak değildir: envanterdeki her ekleme / düzenleme / silme / geri yükleme zaten izleme değişiklik
 * günlüğüne ({@code MonitorHistoryService.INVENTORY}) yazılıyor ve Envanter çekmecesinin "Değişiklikler" sekmesi aynı
 * bileşenle ({@link ChangeHistoryTab}) gösteriyordu. Burada sertifikanın envanter kaydı alan adından bulunur
 * ({@code GET /admin/inventory/by-domain}), üstte "Ekleyen / Son güncelleyen" özeti, altında alan alan önce → sonra
 * değişiklikler. Takım adları kurum geneli takım rehberinden (ad yoksa kimlik). Geri alma düğmesi yok (çekmecedeki gibi).
 *
 * <p>Durumlar: yükleniyor (iskelet), envanterde kayıt yok (açıklamalı boş durum), hata (yeniden dene).
 */
export default function CertChangesTab({ t, domain }) {
  const [state, setState] = useState({ status: 'loading', record: null })
  const [teamNames, setTeamNames] = useState({})

  const load = useCallback(async () => {
    setState({ status: 'loading', record: null })
    try {
      const res = await api.admin.getInventoryByDomain(domain)
      if (!res?.success) { setState({ status: 'error', record: null }); return }
      setState(res.data ? { status: 'ready', record: res.data } : { status: 'missing', record: null })
    } catch {
      setState({ status: 'error', record: null })
    }
  }, [domain])

  useEffect(() => { load() }, [load])

  // Takım adları (diff'te takım alanı kimlik yerine adla görünsün) — rehber yoksa kimlik gösterilir.
  useEffect(() => {
    let alive = true
    Promise.resolve(api.teams?.directory ? api.teams.directory() : null)
      .then((res) => {
        if (!alive) return
        const map = {}
        for (const tm of (Array.isArray(res?.data) ? res.data : [])) if (tm?.id != null) map[tm.id] = tm.name
        setTeamNames(map)
      })
      .catch(() => {})
    return () => { alive = false }
  }, [])

  if (state.status === 'loading') return <LoadingBlock label={t('modal.loading')} />
  if (state.status === 'error') {
    return (
      <StatusBlock tone="danger" icon={History} title={t('certchg.loadError')}
        actions={<Button type="button" variant="outline" className="h-10 sm:h-9" onClick={load}>{t('certchg.retry')}</Button>} />
    )
  }
  if (state.status === 'missing') {
    return <StatusBlock icon={History} title={t('certchg.missingTitle')} description={t('certchg.missingBody')} />
  }

  const r = state.record
  return (
    <div className="flex min-w-0 flex-col gap-3" data-slot="cert-changes">
      <Card className="gap-0 py-3" data-slot="cert-changes-meta">
        <CardContent className="px-3 sm:px-4">
          <dl className="m-0 grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2">
            <MetaItem Icon={UserPlus} label={t('certchg.created')} at={r.created_at} by={r.created_by_name || r.created_by}
              t={t} slot="created" />
            <MetaItem Icon={PencilLine} label={t('certchg.updated')} at={r.updated_at} by={r.updated_by_name || r.updated_by}
              t={t} slot="updated" />
          </dl>
          <p className="m-0 mt-3 text-xs text-muted-foreground">{t('certchg.legacyNote')}</p>
        </CardContent>
      </Card>
      <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
        <ChangeHistoryTab t={t} kind="inventory" monitorId={r.id} teamNames={teamNames} />
      </Suspense>
    </div>
  )
}

/** "Ekleyen" / "Son güncelleyen" — kim + ne zaman (göreli + tam tarih); bilgi yoksa "—". */
function MetaItem({ Icon, label, at, by, t, slot }) {
  return (
    <div className="flex min-w-0 items-start gap-2.5" data-slot={`cert-changes-${slot}`}>
      <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground">
        <Icon aria-hidden="true" className="size-4" />
      </span>
      <div className="min-w-0">
        <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
        <dd className="m-0 min-w-0 text-sm break-words [overflow-wrap:anywhere]">
          {at || by ? (
            <>
              <span className="font-medium">{by || t('certchg.unknownUser')}</span>
              {at && (
                <span className="block text-xs text-muted-foreground tabular-nums">
                  <TimeAgo iso={at} /> · {formatDate(at)}
                </span>
              )}
            </>
          ) : '—'}
        </dd>
      </div>
    </div>
  )
}
