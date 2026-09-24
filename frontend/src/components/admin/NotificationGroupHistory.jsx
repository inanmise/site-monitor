import { formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
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
      <div className="ng-hist-detail">
        <span className="audit-diff-to">{t('ng.histMoved').replace('{n}', parsed.moved ?? 0)}</span>
        <span className="audit-sub">
          {to == null ? t('ng.histMovedDefault') : t('ng.histMovedTo').replace('{id}', to)}
        </span>
      </div>
    )
  }

  if (isDiffShape(parsed)) {
    return (
      <table className="audit-diff-table ng-hist-diff">
        <thead>
          <tr>
            <th>{t('audit.diffField')}</th>
            <th>{t('audit.diffFrom')}</th>
            <th>{t('audit.diffTo')}</th>
          </tr>
        </thead>
        <tbody>
          {Object.entries(parsed).map(([field, c]) => (
            <tr key={field}>
              <td className="audit-diff-field">{fieldLabel(t, field, fieldPrefix)}</td>
              <td className="audit-diff-from">{formatValue(t, c.from)}</td>
              <td className="audit-diff-to">{formatValue(t, c.to)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    )
  }

  return (
    <div className="ng-hist-detail">
      <div className="audit-sub">
        {row.action === 'DELETE' ? t('ng.histSnapshotOld') : t('ng.histSnapshotNew')}
      </div>
      <ul className="ng-hist-fields">
        {Object.entries(parsed).map(([field, v]) => (
          <li key={field}>
            <span className="audit-diff-field">{fieldLabel(t, field, fieldPrefix)}</span>
            <span className="audit-mono">{formatValue(t, v)}</span>
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
  // Sayfalama (2026-09-20): sunucu 0 tabanlı; PaginationBar 1 tabanlı. Verilmezse (eski çağıran) çubuk çizilmez.
  page = 0, size = 25, total = null, onPageChange, onPageSizeChange,
}) {
  const t = useT()

  if (loading) return <LoadingBlock label={t('ng.histLoading')} size={16} />
  if (error) return <AlertBanner tone="danger" title={t('ng.histError')}>{error}</AlertBanner>

  return (
    <div className="ng-hist">
      {filterName && (
        <div className="ng-hist-filter">
          <span className="field-hint">{t('ng.histFilterOn').replace('{name}', filterName)}</span>
          <Button type="button" variant="secondary" onClick={onClearFilter}>
            {t('ng.histFilterClear')}
          </Button>
        </div>
      )}

      {rows.length === 0 ? (
        <p className="field-hint">{filterName ? t('ng.histEmptyGroup') : t('ng.histEmpty')}</p>
      ) : (
        <ul className="ng-hist-list">
          {rows.map(r => (
            <li key={r.id} className="ng-hist-row">
              <div className="ng-hist-main">
                <span className="audit-cell-time audit-mono">{formatDateSec(r.at)}</span>
                <span className={`audit-event-badge ${BADGE[r.action] || 'ev-other'}`}>
                  {actionLabel(t, r.action, actPrefix)}
                </span>
                <span className="ng-hist-who">{r.actor || '—'}</span>
                <span className="ng-hist-what">
                  “{nameOf(r)}”
                  {r.team_name && <span className="audit-sub"> · <TeamBadge teamId={r.team_id} teamName={r.team_name} size={11} /></span>}
                </span>
                {r.ip && <span className="ng-hist-ip audit-mono">{r.ip}</span>}
              </div>
              <Detail row={r} fieldPrefix={fieldPrefix} />
            </li>
          ))}
        </ul>
      )}

      {total != null && total > 0 && onPageChange && (
        <PaginationBar page={page + 1} totalPages={Math.max(1, Math.ceil(total / size))} totalItems={total}
          rangeStart={page * size + 1} rangeEnd={Math.min((page + 1) * size, total)}
          pageSize={size} onPageChange={(p) => onPageChange(p - 1)} onPageSizeChange={(s) => onPageSizeChange?.(s)} />
      )}

      {/* Sessiz kesme YOK: eksik bir geçmişi tam sanmak, geçmişin kendisinden daha kötüdür. */}
      {truncated && <p className="field-hint">{t('ng.histTruncated').replace('{n}', total ?? rows.length)}</p>}
      {hidden > 0 && <p className="field-hint">{t('ng.histHidden').replace('{n}', hidden)}</p>}
      <p className="field-hint">{t('ng.histRetentionNote')}</p>
    </div>
  )
}
