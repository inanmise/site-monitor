import { useState, useEffect } from 'react'
import { History } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import NotificationGroupHistory from './NotificationGroupHistory.jsx'

/**
 * Yönetim Paneli "Değişiklik Geçmişi" bölümü (2026-09-20) — eşik / eskalasyon kişisi / takım / kullanıcı.
 *
 * <p>Bildirim Grupları'ndaki desenin genellenmiş hâli: KAPALI başlar (her açılışta denetim sorgusu atmak
 * ekranı asıl işi için açan kullanıcıya bedava yük bindirir), "Geçmişi göster" ile yüklenir; kaynak
 * denetim kaydıdır (audit_log) — sonradan düzenlenemez, silinmiş kayıtlar da listede kalır.
 *
 * <p>Sunum {@link NotificationGroupHistory} ile paylaşılır (fark tablosu / anlık görüntü / rozetler);
 * yalnız etiket önekleri ve ad çözümü farklıdır. Ekran tek satır süzgeç verebilir ({@code filter}:
 * {id, name}) — ör. kullanıcı satırındaki "Geçmiş" eylemi.
 */
export default function AdminChangeHistory({ resource, filter = null, onClearFilter, canView = true }) {
  const t = useT()
  const [open, setOpen] = useState(!!filter)
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  // Dışarıdan süzgeç gelirse (satır eylemi) bölüm kendiliğinden açılır.
  useEffect(() => { if (filter) setOpen(true) }, [filter?.id])   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!open || !canView) return
    let cancelled = false
    setLoading(true); setError(null)
    api.admin.history(resource, filter?.id ?? null)
      .then(res => {
        if (cancelled) return
        if (res?.success) setData(res)
        else setError(res?.error ?? t('ng.histError'))
      })
      .catch(e => { if (!cancelled) setError(e?.message ?? String(e)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [open, resource, filter?.id, canView])   // eslint-disable-line react-hooks/exhaustive-deps

  if (!canView) return null

  return (
    <div className="admin-section" data-testid="admin-history">
      <div className="admin-section-header ng-hist-header">
        <div>
          <h3>{t('hist.title')}</h3>
          <p className="section-desc">{t(`hist.desc.${resource}`)}</p>
        </div>
        <button
          className="btn btn-secondary"
          onClick={() => { if (open) { setOpen(false); onClearFilter?.() } else setOpen(true) }}
        >
          <History size={15} aria-hidden="true" /> {open ? t('ng.histHide') : t('ng.histShow')}
        </button>
      </div>

      {open && (
        <NotificationGroupHistory
          rows={data?.items ?? []}
          truncated={!!data?.truncated}
          hidden={data?.hidden ?? 0}
          loading={loading}
          error={error}
          filterName={filter?.name}
          onClearFilter={onClearFilter}
          fieldPrefix="hist.f"
          actPrefix="hist.act"
          nameOf={(r) => r.name || r.team_name || `#${r.resource_id}`}
        />
      )}
    </div>
  )
}
