import { useState, useEffect } from 'react'
import { Database, RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import Field from '../ui/Field.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { FIELD_GRID_3, SETTINGS_STACK, SettingsHeader, SettingsSection } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'

/** Salt-okunur etiket/değer alanı (ui/Field + shadcn Input readOnly) — boş değer "—", odaklanınca seçilir. */
function InfoField({ label, value }) {
  const v = (value === null || value === undefined || value === '') ? '—' : String(value)
  return (
    <Field label={label}>
      {({ id }) => (
        <Input id={id} type="text" readOnly value={v} className="font-mono" onFocus={(e) => e.target.select()} />
      )}
    </Field>
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
    <div className={SETTINGS_STACK} data-testid="database-info">
      <SettingsHeader icon={Database} title={t('db.title')} description={t('db.desc')}
        actions={(
          <Button variant="outline" onClick={load} disabled={loading} aria-busy={loading || undefined}>
            {loading ? <Spinner size={14} inline decorative /> : <RefreshCw size={14} />} {loading ? t('db.loading') : t('db.refresh')}
          </Button>
        )} />

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <SettingsSection title={t('db.secConnection')}>
          <div className={FIELD_GRID_3}>
            <InfoField label={t('db.database')} value={d.database} />
            <InfoField label={t('db.user')} value={d.user} />
            <InfoField label={t('db.host')} value={hostPort} />
            <InfoField label={t('db.version')} value={d.version} />
          </div>
        </SettingsSection>

        <SettingsSection title={t('db.secServer')}>
          <div className={FIELD_GRID_3}>
            <InfoField label={t('db.size')} value={d.size} />
            <InfoField label={t('db.uptime')} value={d.uptime} />
            <InfoField label={t('db.startTime')} value={d.start_time} />
            <InfoField label={t('db.encoding')} value={d.encoding} />
            <InfoField label={t('db.maxConnections')} value={d.max_connections} />
            <InfoField label={t('db.activeConnections')} value={d.active_connections} />
          </div>
        </SettingsSection>

        <SettingsSection title={t('db.secPool')}>
          <div className={FIELD_GRID_3}>
            <InfoField label={t('db.poolName')} value={p.name} />
            <InfoField label={t('db.poolActive')} value={p.active} />
            <InfoField label={t('db.poolIdle')} value={p.idle} />
            <InfoField label={t('db.poolTotal')} value={p.total} />
            <InfoField label={t('db.poolWaiting')} value={p.waiting} />
            <InfoField label={t('db.poolMax')} value={p.max_size} />
            <InfoField label={t('db.poolMin')} value={p.min_idle} />
          </div>
        </SettingsSection>

        <SettingsSection title={t('db.secJdbc')}>
          <div className="grid grid-cols-1 gap-x-4">
            <InfoField label={t('db.jdbcUrl')} value={d.jdbc_url} />
            <InfoField label={t('db.driver')} value={driver} />
          </div>
        </SettingsSection>
      </div>
    </div>
  )
}
