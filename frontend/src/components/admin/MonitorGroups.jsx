import { useState, useEffect, useMemo } from 'react'
import { Pencil, FolderTree } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import ToneBadge from './ToneBadge.jsx'
import { ToolbarSearch } from './ListToolbar.jsx'
import { SETTINGS_STACK, SettingsHeader, SettingsSection } from './SettingsControls.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'

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

  // Satır eylemlerinin adı SATIRI içerir (aynı ad iki türde olabilir → tür + takım da adda).
  const rowName = (g) => `${g.name} · ${t(TYPE_LABEL[g.type]) || g.type} · ${g.teamName || t('grp.noTeam')}`

  return (
    <div className={SETTINGS_STACK} data-testid="monitor-groups">
    <SettingsHeader icon={FolderTree} title={t('grp.title')} description={t('grp.desc')}
      meta={!loading && groups.length > 0 ? <Badge variant="outline" className="font-normal text-muted-foreground tabular-nums">{groups.length}</Badge> : null} />
    <SettingsSection contentClassName="flex flex-col gap-3">
      {!loading && groups.length > 0 && (
        <ToolbarSearch value={search} onChange={setSearch} placeholder={t('grp.search')} ariaLabel={t('grp.search')}
          clearLabel={t('app.clear')} className="h-9 w-full sm:max-w-xs" />
      )}

      {loading ? (
        <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-4" />
      ) : groups.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('grp.empty')}</p>
      ) : (
        <div className="overflow-hidden rounded-lg border">
          <Table data-testid="grp-table">
            <TableHeader className="bg-muted/50">
              <TableRow>
                <TableHead className="hidden sm:table-cell">{t('grp.colTeam')}</TableHead>
                <TableHead>{t('grp.colType')}</TableHead>
                <TableHead>{t('grp.colGroup')}</TableHead>
                <TableHead className="text-right">{t('grp.colTotal')}</TableHead>
                <TableHead><span className="sr-only">{t('grp.rename')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((g) => (
                <TableRow key={g.id}>
                  <TableCell className="hidden text-muted-foreground sm:table-cell">{g.teamName || t('grp.noTeam')}</TableCell>
                  <TableCell><ToneBadge tone="muted" data-type={g.type} className="font-semibold">{t(TYPE_LABEL[g.type]) || g.type}</ToneBadge></TableCell>
                  {editing?.id === g.id ? (
                    <>
                      <TableCell colSpan={2} className="min-w-[180px]">
                        <Input autoFocus type="text" value={editing.value} data-testid="grp-rename-input"
                          aria-label={t('a11y.rowAction', t('grp.newNamePlaceholder'), rowName(g))}
                          placeholder={t('grp.newNamePlaceholder')}
                          onChange={(e) => setEditing({ ...editing, value: e.target.value })}
                          onKeyDown={(e) => { if (e.key === 'Enter') save(g); if (e.key === 'Escape') setEditing(null) }} />
                      </TableCell>
                      <TableCell>
                        <div className="flex justify-end gap-2">
                          <Button variant="secondary" size="sm" onClick={() => setEditing(null)}>{t('grp.cancel')}</Button>
                          <Button size="sm" onClick={() => save(g)} disabled={saving} aria-busy={saving || undefined}>
                            {saving ? t('settings.saving') : t('grp.save')}
                          </Button>
                        </div>
                      </TableCell>
                    </>
                  ) : (
                    <>
                      <TableCell className="font-medium break-all whitespace-normal">{g.name}</TableCell>
                      <TableCell className="text-right tabular-nums">{g.count}</TableCell>
                      <TableCell className="text-right">
                        <Button variant="secondary" size="sm" onClick={() => startEdit(g)}
                          aria-label={t('a11y.rowAction', t('grp.rename'), rowName(g))}>
                          <Pencil size={13} aria-hidden="true" />{t('grp.rename')}
                        </Button>
                      </TableCell>
                    </>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </SettingsSection>
    </div>
  )
}
