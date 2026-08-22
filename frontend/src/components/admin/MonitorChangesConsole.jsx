import { useCallback, useEffect, useMemo, useState } from 'react'
import { Search, ChevronRight, Copy } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import ChangeDiffChips from '../history/ChangeDiffChips.jsx'
import { shortUserAgent } from '../history/changeFields.js'
import { copyText } from '../../utils/copyText.js'

/**
 * Yönetici konsolu — TÜM izlemelerdeki yapılandırma değişiklikleri tek listede.
 *
 * <p>"Kim, nerede, neyi ekledi/değiştirdi, ne zaman" sorusunu tek noktadan cevaplar. İzlemenin
 * kendi "Değişiklikler" sekmesiyle AYNI veriyi ve AYNI sunum parçalarını kullanır
 * ({@code ChangeDiffChips}, {@code changeFields}); fark yalnız kapsam (tümü ↔ tek kaynak) ve
 * süzgeç zenginliğidir. Denetim konsolunun (`AuditLogViewer`) `audit-*` CSS ailesi yeniden
 * kullanılır — yeni bir görsel dil icat edilmez.
 *
 * <p>Kapsam sunucuda: global admin/AUDIT her şeyi görür, diğerleri {@code viewTeamIds} kesişimini.
 * Bu ekran yalnız yöneticiye gösteriliyor ama uç kapsamı doğru uyguladığı için ileride takıma
 * açmak yalnız bir görünürlük kararı olur.
 */

const KINDS = ['port', 'dns', 'keyword', 'http', 'page', 'scripted', 'domain', 'ping',
  'inventory', 'group', 'maintenance']
const EVENTS = ['CREATE', 'UPDATE', 'DELETE', 'RESTORE', 'GROUP_RENAME']

/** İzleme türü → uygulama sekmesi (satırdan izlemenin kendi geçmişine gitmek için). */
const TAB_BY_KIND = {
  port: 'port', dns: 'dns', keyword: 'keyword', http: 'http', page: 'page',
  scripted: 'scripted', domain: 'domain', ping: 'ping',
}

export default function MonitorChangesConsole() {
  const t = useT()
  const [rows, setRows] = useState(null)
  const [counts, setCounts] = useState({})
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)
  const [kind, setKind] = useState('')
  const [eventType, setEventType] = useState('')
  const [actor, setActor] = useState('')
  const [q, setQ] = useState('')
  const [from, setFrom] = useState('')
  const [open, setOpen] = useState(null)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    setRows(null)
    try {
      const res = await api.monitoring.getRecentChanges({ page, size, kind, eventType, actor, q, from })
      if (res?.success) {
        setRows(res.data?.changes || [])
        setTotal(res.data?.total || 0)
        setCounts(res.data?.event_counts || {})
        setError(null)
      } else {
        setRows([])
        setError(res?.error || t('chg.loadError'))
      }
    } catch (e) {
      setRows([])
      setError(e?.message || String(e))
    }
  }, [page, size, kind, eventType, actor, q, from, t])

  useEffect(() => { load() }, [load])

  /** Aktör seçenekleri görünen satırlardan türetilir — ayrı bir uç açmaya değmez. */
  const actorOptions = useMemo(() => {
    const seen = new Map()
    ;(rows || []).forEach(r => { if (r.actor) seen.set(r.actor, r.actor_name || r.actor) })
    return [{ value: '', label: t('chg.allActors') },
      ...[...seen.entries()].map(([v, label]) => ({ value: v, label }))]
  }, [rows, t])

  const kindOptions = useMemo(() => [
    { value: '', label: t('chg.allKinds') },
    ...KINDS.map(k => ({ value: k, label: t('chg.kind.' + k) })),
  ], [t])

  const eventLabel = (ev) => {
    const key = `chg.event${ev}`
    const label = t(key)
    return label === key ? ev : label
  }

  /** Hazır süzgeçler: en sık sorulan üç soru tek tık. */
  function applyPreset(preset) {
    setPage(0)
    const today = new Date()
    const iso = (d) => d.toISOString().slice(0, 19)
    if (preset === 'today') {
      setFrom(iso(new Date(today.getFullYear(), today.getMonth(), today.getDate())))
      setEventType('')
    } else if (preset === 'week') {
      setFrom(iso(new Date(Date.now() - 7 * 864e5)))
      setEventType('')
    } else if (preset === 'deletes') {
      setFrom(''); setEventType('DELETE')
    } else {
      setFrom(''); setEventType(''); setActor(''); setKind(''); setQ('')
    }
  }

  const totalPages = Math.max(1, Math.ceil(total / size))

  return (
    <div className="audit-viewer chg-console">
      {/* Özet şeridi — "bugün ne oldu" sorusunun tek bakışta cevabı. */}
      <div className="audit-stats-row">
        <Stat label={t('chg.statTotal')} value={total} />
        <Stat label={t('chg.eventCREATE')} value={counts.CREATE ?? 0} />
        <Stat label={t('chg.eventUPDATE')} value={counts.UPDATE ?? 0} />
        <Stat label={t('chg.eventDELETE')} value={counts.DELETE ?? 0} />
      </div>

      <div className="audit-toolbar">
        <div className="audit-presets">
          <button className="audit-filter-btn" onClick={() => applyPreset('today')}>{t('chg.presetToday')}</button>
          <button className="audit-filter-btn" onClick={() => applyPreset('week')}>{t('chg.presetWeek')}</button>
          <button className="audit-filter-btn" onClick={() => applyPreset('deletes')}>{t('chg.presetDeletes')}</button>
          <button className="audit-filter-btn" onClick={() => applyPreset('clear')}>{t('chg.presetClear')}</button>
        </div>
        <div className="audit-toolbar-actions chg-filters">
          <SearchableSelect value={kind} onChange={(v) => { setKind(v); setPage(0) }}
            options={kindOptions} searchThreshold={6} />
          <SearchableSelect value={actor} onChange={(v) => { setActor(v); setPage(0) }}
            options={actorOptions} searchThreshold={6} />
          <input className="upt-search" type="text" value={q} aria-label={t('chg.searchPlaceholder')}
            placeholder={t('chg.searchPlaceholder')}
            onChange={(e) => { setQ(e.target.value); setPage(0) }} />
        </div>
      </div>

      <div className="chg-console-events">
        <SegmentedControl value={eventType} onChange={(v) => { setEventType(v); setPage(0) }}
          ariaLabel={t('chg.eventFilter')}
          options={[{ value: '', label: t('chg.allEvents') },
            ...EVENTS.map(e => ({ value: e, label: eventLabel(e) }))]} />
      </div>

      {error && <AlertBanner tone="danger" title={t('chg.loadError')}>{error}</AlertBanner>}

      {rows === null ? <LoadingBlock label={t('modal.loading')} />
        : rows.length === 0 ? (
          <StatusBlock tone="neutral" icon={Search} title={t('chg.emptyTitle')}
            description={t('chg.consoleEmptyText')} />
        ) : (<>
          <div className="chg-rows">
            {rows.map(r => {
              const id = `${r.kind}-${r.resource_id}-${r.seq}`
              const isOpen = open === id
              const tab = TAB_BY_KIND[String(r.kind).toLowerCase()]
              return (
                <div key={id} className={`chg-row${isOpen ? ' is-open' : ''}`}>
                  <button type="button" className="chg-row-head" aria-expanded={isOpen}
                    onClick={() => setOpen(isOpen ? null : id)}>
                    <ChevronRight size={15} className="chg-row-caret" aria-hidden="true" />
                    <span className={`chg-ev chg-ev--${String(r.event_type).toLowerCase()}`}>
                      {eventLabel(r.event_type)}
                    </span>
                    <span className="chg-row-main">
                      <span className="chg-row-name">{r.resource_name || `#${r.resource_id}`}</span>
                      <span className="chg-row-meta">
                        {t('chg.kind.' + String(r.kind).toLowerCase()) }
                        {r.team_name ? ` · ${r.team_name}` : ''}
                      </span>
                    </span>
                    <span className="chg-row-who">
                      <UserBadge username={r.actor} displayName={r.actor_name} size="sm" inline nameOnly />
                      <span className="chg-row-when sys-mono">{formatDateSec(r.at)}</span>
                    </span>
                  </button>

                  {/* Kapalı satırda bile ilk birkaç alan görünür: liste taranırken açmadan okunsun. */}
                  {!isOpen && <ChangeDiffChips t={t} changes={r.changes} limit={3} className="chg-row-chips" />}

                  {isOpen && (
                    <div className="chg-row-detail">
                      {r.note && <p className="chg-note">{r.note}</p>}
                      <ChangeDiffChips t={t} changes={r.changes} />
                      <div className="chg-row-facts">
                        {r.ip_address && (
                          <span className="chg-ip" title={t('chg.ipTitle')}
                            onClick={() => copyText(r.ip_address)}>
                            {r.ip_address}<Copy size={10} aria-hidden="true" />
                          </span>
                        )}
                        {r.user_agent && <span className="chg-ua" title={r.user_agent}>{shortUserAgent(r.user_agent)}</span>}
                        {tab && (
                          <a className="chg-goto" href={`?tab=${tab}&monitor=${r.resource_id}&mtab=changes`}>
                            {t('chg.openMonitor')}
                          </a>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              )
            })}
          </div>

          <PaginationBar
            page={page} totalPages={totalPages} totalItems={total}
            rangeStart={total === 0 ? 0 : page * size + 1}
            rangeEnd={Math.min(total, (page + 1) * size)}
            pageSize={size}
            onPageChange={setPage}
            onPageSizeChange={(s) => { setSize(s); setPage(0) }} />
        </>)}
    </div>
  )
}

function Stat({ label, value }) {
  return (
    <div className="audit-stat">
      <div className="audit-stat-value">{value ?? '—'}</div>
      <div className="audit-stat-label">{label}</div>
    </div>
  )
}
