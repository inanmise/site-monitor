import { useState, useEffect, useMemo } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Button } from '@/components/shadcn/button'

// İzleme türü etiketleri — mevcut İzleme Göstergeleri anahtarlarını yeniden kullan (yeni i18n gerekmez).
export const TYPE_LABEL = {
  cert: 'wr.monTypeCert', http: 'wr.monTypeHttp', ping: 'wr.monTypePing',
  port: 'wr.monTypePort', dns: 'wr.monTypeDns', keyword: 'wr.monTypeKeyword', domain: 'wr.monTypeDomain',
  page: 'wr.monTypePage', scripted: 'wr.monTypeScripted', pagespeed: 'wr.monTypePageSpeed',
}

/**
 * İzleme Grupları (Ayarlar) — grup adları TAKIM + İZLEME TÜRÜ bazlı: her satır (takım, tür, grup) üçlüsüdür.
 * Yeniden adlandırma yalnız o türün monitörlerini + o türün alarm geçmişini etkiler. Admin tüm takımları görür
 * (server-side scope); takım üyesi yalnız kendi takımını. Aynı takım+türde çakışan ada 409.
 */
export default function MonitorGroups() {
  const t = useT()
  const toast = useToast()
  const [groups, setGroups] = useState([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState(null)   // { id, value }
  const [saving, setSaving] = useState(false)
  const [search, setSearch] = useState('')

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    try {
      const res = await api.monitoring.listGroups()   // admin → tüm takımlar (server-side scope)
      if (res?.success) {
        // Backend snake_case → normalize; camelCase fallback.
        setGroups((res.data || []).map((g) => ({
          id: g.id,
          teamId: g.team_id ?? g.teamId ?? null,
          teamName: g.team_name ?? g.teamName ?? null,
          type: g.type,
          name: g.name,
          count: g.count ?? 0,
        })))
      } else toast.error(res?.error || t('settings.loadError'))
    } finally {
      setLoading(false)
    }
  }

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return groups
    return groups.filter((g) =>
      g.name.toLowerCase().includes(q) ||
      (g.teamName || '').toLowerCase().includes(q) ||
      (t(TYPE_LABEL[g.type]) || g.type).toLowerCase().includes(q))
  }, [groups, search, t])

  function startEdit(g) { setEditing({ id: g.id, value: g.name }) }

  async function save(g) {
    const newName = editing.value.trim()
    if (!newName) { toast.error(t('grp.emptyNameError')); return }
    if (newName === g.name) { setEditing(null); return }
    setSaving(true)
    try {
      const res = await api.monitoring.renameGroup(g.id, newName)
      if (res?.success) { setEditing(null); toast.success(t('grp.renamed', res.data?.affected ?? 0)); load() }
      else toast.error(res?.error || t('grp.renameError'))   // 409 çakışma mesajı backend'den gelir
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="admin-section">
      <h3>{t('grp.title')}</h3>
      <p className="section-desc">{t('grp.desc')}</p>

      {!loading && groups.length > 0 && (
        <input
          className="grp-search"
          type="text"
          placeholder={t('grp.search')}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      )}

      {loading ? (
        <p className="section-desc">{t('settings.loading')}</p>
      ) : groups.length === 0 ? (
        <p className="section-desc">{t('grp.empty')}</p>
      ) : (
        <table className="admin-table grp-table">
          <thead>
            <tr>
              <th>{t('grp.colTeam')}</th>
              <th>{t('grp.colType')}</th>
              <th>{t('grp.colGroup')}</th>
              <th>{t('grp.colTotal')}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((g) => (
              <tr key={g.id}>
                <td className="grp-team">{g.teamName || t('grp.noTeam')}</td>
                <td><span className="grp-type-badge">{t(TYPE_LABEL[g.type]) || g.type}</span></td>
                {editing?.id === g.id ? (
                  <>
                    <td colSpan={2}>
                      <input
                        className="grp-input"
                        autoFocus
                        type="text"
                        value={editing.value}
                        placeholder={t('grp.newNamePlaceholder')}
                        onChange={(e) => setEditing({ ...editing, value: e.target.value })}
                        onKeyDown={(e) => { if (e.key === 'Enter') save(g); if (e.key === 'Escape') setEditing(null) }}
                      />
                    </td>
                    <td className="grp-actions">
                      <Button variant="secondary" onClick={() => setEditing(null)}>{t('grp.cancel')}</Button>
                      <Button onClick={() => save(g)} disabled={saving}>{saving ? t('settings.saving') : t('grp.save')}</Button>
                    </td>
                  </>
                ) : (
                  <>
                    <td className="grp-name">{g.name}</td>
                    <td>{g.count}</td>
                    <td className="grp-actions">
                      <Button variant="secondary" onClick={() => startEdit(g)}>{t('grp.rename')}</Button>
                    </td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
