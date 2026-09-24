import { createPortal } from 'react-dom'
import { ShieldAlert, ArrowRight } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { fmtNum } from './PolicyRow.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Kaydetmeden ÖNCE "ne değişecek" özeti. Kullanıcı hangi tabloda hangi değerden hangi değere
 * geçtiğini ve bunun SONUCUNU (uzatma mı, kalıcı silme mi) görmeden kaydetmez.
 *
 * Neden kendi penceresi: showConfirm'in kutusu 420px sabit ve mesajı bir <p> içinde render
 * ediliyor — tablo geçersiz DOM üretirdi.
 */
export default function RetentionReviewModal({ changes, onCancel, onConfirm, saving }) {
  const t = useT()
  const shortened = changes.filter(c => c.to < c.from)
  const danger = shortened.length > 0

  return createPortal(
    <div className="modal-overlay" onClick={saving ? undefined : onCancel}>
      <div className="modal-box modal-wide ret-review" onClick={e => e.stopPropagation()}>
        <div className={`modal-icon-hdr ret-review-hdr${danger ? ' ret-review-hdr--danger' : ''}`}>
          <div className="modal-icon-hdr-badge"><ShieldAlert size={20} /></div>
          <h3>{t('ret.reviewTitle', changes.length)}</h3>
        </div>

        <div className="ret-review-body">
          {danger && (
            <div className="ret-review-warn" role="alert">
              {t('ret.reviewWarn', shortened.length)}
            </div>
          )}

          <table className="audit-diff-table ret-review-table">
            <thead>
              <tr>
                <th>{t('ret.colTable')}</th>
                <th>{t('audit.diffFrom')}</th>
                <th />
                <th>{t('audit.diffTo')}</th>
                <th>{t('ret.reviewEffect')}</th>
              </tr>
            </thead>
            <tbody>
              {changes.map(c => {
                const down = c.to < c.from
                const delta = c.to - c.from
                return (
                  <tr key={c.id} className={down ? 'ret-review-row--down' : ''}>
                    <td className="audit-diff-field">
                      {c.table}
                      <span className="ret-review-cls">{t(`ret.class.${c.dataClass}`)}</span>
                    </td>
                    <td className="audit-diff-from">{c.from} {t('ret.daysShort')}</td>
                    <td className="ret-review-arrow"><ArrowRight size={13} /></td>
                    <td className="audit-diff-to">{c.to} {t('ret.daysShort')}</td>
                    <td className="ret-review-effect">
                      {down
                        ? <span className="ret-review-effect--down">
                            {t('ret.effectShorten', Math.abs(delta), fmtNum(c.purgeable ?? 0))}
                          </span>
                        : <span className="ret-review-effect--up">
                            {t('ret.effectExtend', delta)}
                          </span>}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>

          <p className="field-hint ret-review-hint">{t('ret.reviewHint')}</p>
        </div>

        <div className="modal-actions">
          <Button variant="secondary" onClick={onCancel} disabled={saving}>
            {t('app.cancel')}
          </Button>
          <Button variant={danger ? 'destructive' : 'default'}
            onClick={onConfirm} disabled={saving}>
            {saving ? <Spinner size={15} inline decorative /> : null}
            {danger ? t('ret.reviewConfirmDanger') : t('ret.reviewConfirm')}
          </Button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
