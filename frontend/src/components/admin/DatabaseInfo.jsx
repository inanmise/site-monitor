import { useState, useEffect } from 'react'
import { Database, RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'

/** Salt-okunur etiket/değer alanı — boş değer "—" gösterir, tıklayınca seçilir. */
function Field({ label, value }) {
  const v = (value === null || value === undefined || value === '') ? '—' : String(value)
  return (
    <div className="threshold-field">
      <label>{label}</label>
      <input type="text" readOnly value={v} title={v}
        style={{ fontFamily: 'monospace' }} onFocus={(e) => e.target.select()} />
    </div>
  )
}

/**
 * Settings → Veritabanı Bilgileri (yalnız admin). Bağlı PostgreSQL örneğine ait
 * salt-okunur temel meta verileri: db adı, kullanıcı, host/port, sürüm, boyut,
 * çalışma süresi + HikariCP havuz istatistikleri ve JDBC sürücü bilgisi.
 */
export default function DatabaseInfo() {
  const t = useT()
  const toast = useToast()
  const [info, setInfo] = useState(null)
  const [loading, setLoading] = useState(false)

  async function load() {
    setLoading(true)
    try {
      try {
        const res = await api.admin.getDatabaseInfo()
        if (res?.success) setInfo(res.data || {})
        else toast.error(res?.error || t('db.loadError'))
      } catch {
        toast.error(t('db.loadError'))
      }
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  const d = info || {}
  const p = d.pool || {}
  const hostPort = d.server_addr
    ? `${d.server_addr}${d.server_port ? ':' + d.server_port : ''}`
    : (d.server_port ? String(d.server_port) : null)
  const driver = d.driver_name
    ? (d.driver_version ? `${d.driver_name} ${d.driver_version}` : d.driver_name)
    : null

  return (
    <div className="ldap-settings">
      <div className="admin-section">
        <h3><Database size={16} style={{ verticalAlign: '-3px', marginRight: 6 }} />{t('db.title')}</h3>
        <p className="section-desc">{t('db.desc')}</p>
        <div className="ldap-actions">
          <button className="btn btn-secondary" onClick={load} disabled={loading}>
            <RefreshCw size={14} className={loading ? 'spin' : ''} /> {loading ? t('db.loading') : t('db.refresh')}
          </button>
        </div>
      </div>

      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('db.secConnection')}</h4>
        <div className="threshold-grid">
          <Field label={t('db.database')} value={d.database} />
          <Field label={t('db.user')} value={d.user} />
          <Field label={t('db.host')} value={hostPort} />
          <Field label={t('db.version')} value={d.version} />
        </div>
      </div>

      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('db.secServer')}</h4>
        <div className="threshold-grid">
          <Field label={t('db.size')} value={d.size} />
          <Field label={t('db.uptime')} value={d.uptime} />
          <Field label={t('db.startTime')} value={d.start_time} />
          <Field label={t('db.encoding')} value={d.encoding} />
          <Field label={t('db.maxConnections')} value={d.max_connections} />
          <Field label={t('db.activeConnections')} value={d.active_connections} />
        </div>
      </div>

      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('db.secPool')}</h4>
        <div className="threshold-grid">
          <Field label={t('db.poolName')} value={p.name} />
          <Field label={t('db.poolActive')} value={p.active} />
          <Field label={t('db.poolIdle')} value={p.idle} />
          <Field label={t('db.poolTotal')} value={p.total} />
          <Field label={t('db.poolWaiting')} value={p.waiting} />
          <Field label={t('db.poolMax')} value={p.max_size} />
          <Field label={t('db.poolMin')} value={p.min_idle} />
        </div>
      </div>

      <div className="admin-section">
        <h4 className="ldap-subhdr">{t('db.secJdbc')}</h4>
        <div className="threshold-grid">
          <Field label={t('db.jdbcUrl')} value={d.jdbc_url} />
          <Field label={t('db.driver')} value={driver} />
        </div>
      </div>
    </div>
  )
}
