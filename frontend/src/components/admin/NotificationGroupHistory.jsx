import { formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import DiffTable from './audit/DiffTable.jsx'
import { EventBadge } from './ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * "Kim, ne zaman, neyi değiştirdi" — bildirim gruplarının değişiklik geçmişi.
 *
 * <p>Sunum bileşeni: veriyi çağıran ekran getirir. Kaynak denetim kaydı olduğu için satırlar
 * sonradan düzenlenemez ve SİLİNMİŞ gruplar da listede kalır — kullanıcının en çok aradığı
 * kayıt çoğu zaman tam olarak "bu grubu kim sildi" olur.
 *
 * <p>Biçim, eylemin türüne göre değişir çünkü kaydın içeriği de değişir: oluşturma ve silme
 * ANLIK GÖRÜNTÜ taşır (o an ne vardı), düzenleme ise FARK taşır (neyden neye). İkisini tek
 * şablona sıkıştırmak, oluşturmada "boştan şuna" gibi yanlış bir okuma üretirdi.
 */

const BADGE = {
  CREATE:   'ev-create',
  UPDATE:   'ev-edit',
  DEFAULT:  'ev-edit',
  REASSIGN: 'ev-other',
  DELETE:   'ev-delete',
}

/** Denetim alanı adı → ekran etiketi; bilinmeyen alan ham adıyla gösterilir (kaybolmaz). */
function fieldLabel(t, key, prefix = 'ng.f') {
  const s = t(`${prefix}.${key}`)
  return s === `${prefix}.${key}` ? key : s
}

/** Eylem etiketi: önekli anahtar yoksa ham eylem adı (yeni bir olay türü sessizce kaybolmasın). */
function actionLabel(t, action, prefix = 'ng.act') {
  const s = t(`${prefix}.${action}`)
  return s === `${prefix}.${action}` ? String(action || '').toLowerCase().replace(/_/g, ' ') : s
}

function formatValue(t, v) {
  if (v === true) return t('ng.histYes')
  if (v === false) return t('ng.histNo')
  if (v === null || v === undefined || v === '') return t('ng.histEmptyValue')
  return String(v)
}

/** Fark biçimi mi ({"alan":{"from":…,"to":…}}) yoksa anlık görüntü mü — kayıt türünden değil
 *  İÇERİKTEN karar verilir; eski kayıtların biçimi de böylece doğru okunur. */
function isDiffShape(obj) {
  const vals = Object.values(obj)
  return vals.length > 0 && vals.every(v => v && typeof v === 'object' && !Array.isArray(v)
    && ('from' in v || 'to' in v))
}

function parseChanges(raw) {
  if (!raw) return null
  try {
    const o = JSON.parse(raw)
    return o && typeof o === 'object' && !Array.isArray(o) ? o : null
  } catch {
    // Eski kayıtlar JSON olmayabilir; satırın kendisi (kim/ne zaman/ne yaptı) yine gösterilir.
    return null
  }
}

function Detail({ row, fieldPrefix }) {
  const t = useT()
  const parsed = parseChanges(row.changes)
  if (!parsed) return null

  if (row.action === 'REASSIGN') {
    const to = parsed.to
    return (
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-[0.85em]">
        <span className="font-semibold text-success">{t('ng.histMoved').replace('{n}', parsed.moved ?? 0)}</span>
        <span className="text-muted-foreground">
          {to == null ? t('ng.histMovedDefault') : t('ng.histMovedTo').replace('{id}', to)}
        </span>
      </div>
    )
  }

  if (isDiffShape(parsed)) {
    // Ortak fark tablosu (Denetim Kaydı / Değişiklik Geçmişi ile aynı): alan · eski (üstü çizili) → yeni.
    return (
      <DiffTable fieldLabel={t('audit.diffField')} fromLabel={t('audit.diffFrom')} toLabel={t('audit.diffTo')}
        rows={Object.entries(parsed).map(([field, c]) => [field, fieldLabel(t, field, fieldPrefix), formatValue(t, c.from), formatValue(t, c.to)])} />
    )
  }

  return (
    <div className="flex flex-col gap-1 text-[0.85em]">
      <div className="text-muted-foreground">
        {row.action === 'DELETE' ? t('ng.histSnapshotOld') : t('ng.histSnapshotNew')}
      </div>
      <ul data-slot="ng-hist-fields" className="ml-1 flex list-none flex-col gap-0.5 p-0">
        {Object.entries(parsed).map(([field, v]) => (
          <li key={field} className="flex min-w-0 flex-wrap gap-x-2">
            <span className="font-semibold whitespace-nowrap">{fieldLabel(t, field, fieldPrefix)}</span>
            <span className="min-w-0 font-mono break-all">{formatValue(t, v)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

export default function NotificationGroupHistory({
  rows, truncated, hidden, loading, error, filterName, onClearFilter,
  // Yönetim Paneli sekmeleri aynı sunumu başka kaynaklar için kullanır (2026-09-20).
  fieldPrefix = 'ng.f', actPrefix = 'ng.act', nameOf = (r) => r.group_name || `#${r.group_id}`,
  // Sayfalama (2026-09-26 standardı): çağıranın `useServerPagination(...).bar` nesnesi — taban dönüşümü kancada.
  // Verilmezse (önizleme: UserDetailPanel son 10 kayıt) çubuk çizilmez.
  pagination = null,
}) {
  const t = useT()

  if (loading) return <LoadingBlock label={t('ng.histLoading')} size={16} />
  if (error) return <AlertBanner tone="danger" title={t('ng.histError')}>{error}</AlertBanner>

  // shadcn (2026-09-26, D2): eski `.ng-hist-*` / `.audit-event-badge` / `.audit-diff-table` ailesi yerine
  // EventBadge (Badge) + ortak DiffTable (Table); satırlar kenarlıklı liste (sol renk şeridi YOK), telefonda
  // sarar. Test kancaları: `data-slot="ng-hist-list|ng-hist-row"`, fark hücrelerinde `data-diff`.
  const hint = 'text-xs text-muted-foreground [overflow-wrap:anywhere]'
  return (
    <div data-slot="ng-hist" className="flex min-w-0 flex-col gap-2.5">
      {filterName && (
        <div className="flex flex-wrap items-center gap-2">
          <span className={hint}>{t('ng.histFilterOn').replace('{name}', filterName)}</span>
          <Button type="button" variant="secondary" size="sm" onClick={onClearFilter}>
            {t('ng.histFilterClear')}
          </Button>
        </div>
      )}

      {rows.length === 0 ? (
        <p className={hint}>{filterName ? t('ng.histEmptyGroup') : t('ng.histEmpty')}</p>
      ) : (
        <ul data-slot="ng-hist-list" className="flex list-none flex-col divide-y overflow-hidden rounded-lg border bg-card p-0">
          {rows.map(r => (
            <li key={r.id} data-slot="ng-hist-row" className="flex min-w-0 flex-col gap-2 px-3 py-2.5">
              <div className="flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1 text-sm">
                <span className="font-mono text-xs whitespace-nowrap text-muted-foreground tabular-nums">{formatDateSec(r.at)}</span>
                <EventBadge kind={BADGE[r.action] || 'ev-other'}>{actionLabel(t, r.action, actPrefix)}</EventBadge>
                <span className="font-semibold break-all">{r.actor || '—'}</span>
                <span className="inline-flex min-w-0 flex-wrap items-center gap-x-1.5 [overflow-wrap:anywhere]">
                  “{nameOf(r)}”
                  {r.team_name && <span className="inline-flex items-center gap-1 text-muted-foreground">· <TeamBadge teamId={r.team_id} teamName={r.team_name} size={11} /></span>}
                </span>
                {r.ip && <span className="font-mono text-xs text-muted-foreground sm:ml-auto">{r.ip}</span>}
              </div>
              <Detail row={r} fieldPrefix={fieldPrefix} />
            </li>
          ))}
        </ul>
      )}

      {pagination && <PaginationBar {...pagination} />}

      {/* Sessiz kesme YOK: eksik bir geçmişi tam sanmak, geçmişin kendisinden daha kötüdür. */}
      {truncated && <p className={hint}>{t('ng.histTruncated').replace('{n}', pagination?.totalItems || rows.length)}</p>}
      {hidden > 0 && <p className={hint}>{t('ng.histHidden').replace('{n}', hidden)}</p>}
      <p className={hint}>{t('ng.histRetentionNote')}</p>
    </div>
  )
}
