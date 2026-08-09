import { Table } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'

/**
 * SQL Playground — tablo şema detayları (salt-okunur):
 *  • Kolonlar: tip + tip SINIRLARI (taşma/uzunluk farkındalığı) + NULL/NOT NULL + default
 *  • Data integrity: constraint'ler (PRIMARY KEY / FOREIGN KEY / UNIQUE / CHECK)
 *  • Index'ler (unique işaretli)
 *  • Trigger'lar
 * Veri backend'in /admin/sql/tables/{name}/details ucundan gelir (Postgres katalogu).
 */
export default function TableDetailsModal({ table, details, loading, onClose }) {
  const t = useT()
  const columns     = details?.columns ?? []
  const constraints = details?.constraints ?? []
  const indexes     = details?.indexes ?? []
  const triggers    = details?.triggers ?? []

  const ctClass = (type) => {
    const k = String(type || '').split(' ')[0].toLowerCase()
    return `sqlpg-td-ct sqlpg-td-ct-${k}`
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box modal-wide sqlpg-tdmodal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--user">
          <div className="modal-icon-hdr-badge"><Table size={20} /></div>
          <h3>{t('sql.td.title', table)}</h3>
        </div>

        {loading ? (
          <LoadingBlock label={t('sql.td.loading')} className="sqlpg-td-loading" size={18} />
        ) : (
          <div className="sqlpg-td-body">
            {/* ── Kolonlar + tip sınırları ── */}
            <section className="sqlpg-td-sec">
              <h4>{t('sql.td.columns')} <span className="sqlpg-td-count">{columns.length}</span></h4>
              <div className="sqlpg-td-table-wrap">
                <table className="sqlpg-td-table">
                  <thead>
                    <tr>
                      <th>{t('sql.td.colName')}</th>
                      <th>{t('sql.td.colType')}</th>
                      <th>{t('sql.td.colBounds')}</th>
                      <th>{t('sql.td.colNull')}</th>
                      <th>{t('sql.td.colDefault')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {columns.map((c) => (
                      <tr key={c.column_name}>
                        <td className="sqlpg-td-mono">{c.column_name}</td>
                        <td className="sqlpg-td-mono sqlpg-td-type">{c.udt_name || c.data_type}</td>
                        <td className="sqlpg-td-bounds">{c.bounds || '—'}</td>
                        <td>
                          {c.is_nullable === 'NO'
                            ? <span className="sqlpg-td-badge sqlpg-td-nn">NOT NULL</span>
                            : <span className="sqlpg-td-muted">NULL</span>}
                        </td>
                        <td className="sqlpg-td-mono sqlpg-td-default" title={c.column_default || ''}>
                          {c.column_default ?? '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>

            {/* ── Data integrity (constraint'ler) ── */}
            <section className="sqlpg-td-sec">
              <h4>{t('sql.td.integrity')} <span className="sqlpg-td-count">{constraints.length}</span></h4>
              {constraints.length === 0 ? (
                <div className="sqlpg-td-empty">{t('sql.td.none')}</div>
              ) : (
                <ul className="sqlpg-td-list">
                  {constraints.map((c) => (
                    <li key={c.name}>
                      <span className={ctClass(c.type)}>{c.type}</span>
                      <span className="sqlpg-td-cname">{c.name}</span>
                      <code className="sqlpg-td-cdef">{c.definition}</code>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            {/* ── Index'ler ── */}
            <section className="sqlpg-td-sec">
              <h4>{t('sql.td.indexes')} <span className="sqlpg-td-count">{indexes.length}</span></h4>
              {indexes.length === 0 ? (
                <div className="sqlpg-td-empty">{t('sql.td.none')}</div>
              ) : (
                <ul className="sqlpg-td-list">
                  {indexes.map((ix) => (
                    <li key={ix.name}>
                      <span className={`sqlpg-td-ct ${ix.is_unique ? 'sqlpg-td-ct-unique' : 'sqlpg-td-ct-index'}`}>
                        {ix.is_unique ? 'UNIQUE' : 'INDEX'}
                      </span>
                      <span className="sqlpg-td-cname">{ix.name}</span>
                      <code className="sqlpg-td-cdef">{ix.definition}</code>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            {/* ── Trigger'lar ── */}
            <section className="sqlpg-td-sec">
              <h4>{t('sql.td.triggers')} <span className="sqlpg-td-count">{triggers.length}</span></h4>
              {triggers.length === 0 ? (
                <div className="sqlpg-td-empty">{t('sql.td.none')}</div>
              ) : (
                <ul className="sqlpg-td-list">
                  {triggers.map((tg, i) => (
                    <li key={i}>
                      <span className="sqlpg-td-ct sqlpg-td-ct-trigger">{tg.timing} {tg.event}</span>
                      <span className="sqlpg-td-cname">{tg.name}</span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        )}

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>{t('sql.closeRowDetails')}</button>
        </div>
      </div>
    </div>
  )
}
