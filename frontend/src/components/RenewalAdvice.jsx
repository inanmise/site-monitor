import { useEffect, useMemo, useState } from 'react'
import { api, formatDateOnly, localDayKey } from '../api/client'
import { useT } from '../i18n/index.jsx'
import DiagnosticsModal from './admin/DiagnosticsModal.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import MonthCalendar from './ui/MonthCalendar.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import { buildIcs, downloadIcs } from '../utils/ics.js'
import { csvRows } from '../utils/csv.js'
import { matchesTag, tagNamesOf, tagsOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import { CalendarDays, List, Download, ShieldCheck, Table2, Search, FolderOpen, Copy, FileDown, Stethoscope } from 'lucide-react'

const PRIORITY_COLOR = { critical: '#dc3545', warning: '#fd7e14', info: '#0d6efd' }
const PRIORITY_ORDER = { critical: 0, warning: 1, info: 2 }
const CODES = ['REVOKED', 'DEPLOYMENT_INCOMPLETE', 'CHAIN_BROKEN', 'UNREACHABLE', 'EXPIRED', 'EXPIRING_CRITICAL', 'EXPIRING_WARNING', 'EXPIRING_INFO']

/**
 * Yenileme Önerileri — yeniden tasarım (2026-09-18, kullanıcı isteği: "çok daha kullanışlı").
 *
 * Eski sayfa: sunucu sırasında düz kart listesi, süzgeç yok, sayfalama yok, bağlam yok (takım/kademe/veren).
 * Yeni sayfa:
 *  - Özet şeridi: kritik / uyarı / bilgi + "bu hafta" + "toplu iş" (aynı parmak izini paylaşan alanlar) — tıklayınca süzer.
 *  - Süzgeç çubuğu: arama (alan/veren/grup/etiket), neden (kod), takım, grup, etiket, sıralama; "Filtreleri temizle";
 *    durum URL'de (paylaşılabilir bağlantı).
 *  - Üç görünüm: kart (zengin), tablo (sıkı, sıralanabilir), takvim. Kart ve tablo SAYFALI.
 *  - Satır eylemleri: detay, tanılama (ulaşılamayan), alan adını kopyala. Dışa aktarma: CSV + ICS (süzülmüş liste).
 *  - Aynı sertifika (parmak izi) birden çok alanı kapsıyorsa "N alan bu sertifikayı paylaşıyor" rozeti: tek yenileme
 *    işi olduğunu gösterir — ekipler aynı sertifikayı iki kez yenilemesin.
 */
export default function RenewalAdvice({ onSelectDomain }) {
  const t = useT()
  const [advice, setAdvice] = useState([])
  const [loading, setLoading] = useState(true)
  const [diag, setDiag] = useState(null)   // { domain, port } → DiagnosticsModal
  const [loadError, setLoadError] = useState(null)
  const [view, setView] = useState(() => { try { const v = localStorage.getItem('renewal-view'); return ['list', 'table', 'calendar'].includes(v) ? v : 'list' } catch { return 'list' } })
  const switchView = (v) => { setView(v); try { localStorage.setItem('renewal-view', v) } catch { /* yoksay */ } }
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [priority, setPriority] = useState(() => readUrlParam('r_pri', 'all'))
  const [code, setCode] = useState(() => readUrlParam('r_code', 'all'))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter] = useState(() => readUrlParam('tag', 'all'))
  const [sortKey, setSortKey] = useState(() => readUrlParam('sort', 'priority'))
  const [copied, setCopied] = useState(null)

  // Mesaj/eylem arayüz dilinde (QA 2026-09-12, ISSUE-002): sunucu yalnız TR üretir; `code` + gün sayısı
  // ile çevrilir, bilinmeyen kodda sunucu metni kalır.
  const adviceText = (a) => {
    const n = a.days_remaining == null ? '' : Math.abs(a.days_remaining)
    const msg = t(`renewal.msg.${a.code}`, n), act = t(`renewal.act.${a.code}`, n)
    return { message: msg.startsWith('renewal.msg.') ? a.message : msg, action: act.startsWith('renewal.act.') ? a.action : act }
  }
  const codeLabel = (c) => { const k = `renewal.code.${c}`; const v = t(k); return v === k ? c : v }

  // .catch YOKTU: request() ag hatasinda {success:false} DONDURMEZ, throw eder; promise reject olunca
  // setLoading(false) hic calismiyor, spinner sonsuza kadar donuyordu.
  useEffect(() => {
    api.getRenewalAdvice()
      .then((res) => {
        if (res?.success) { setAdvice(res.data || []); setLoadError(null) }
        else setLoadError(res?.error || 'load failed')
      })
      .catch((e) => setLoadError(e?.message || 'network error'))
      .finally(() => setLoading(false))
  }, [])

  // Aynı parmak izi = aynı sertifika = TEK yenileme işi (alan sayısı rozet için)
  const fpCount = useMemo(() => {
    const m = new Map()
    for (const a of advice) if (a.fingerprint) m.set(a.fingerprint, (m.get(a.fingerprint) || 0) + 1)
    return m
  }, [advice])

  const kpi = useMemo(() => {
    const k = { critical: 0, warning: 0, info: 0, week: 0, batches: 0 }
    for (const a of advice) {
      if (k[a.priority] != null) k[a.priority]++
      if (a.days_remaining != null && a.days_remaining >= 0 && a.days_remaining <= 7) k.week++
    }
    for (const n of fpCount.values()) if (n > 1) k.batches++
    return k
  }, [advice, fpCount])

  const teamOptions = useMemo(() => {
    const names = new Set(); let none = false
    for (const a of advice) { if (a.team_name) names.add(a.team_name); else none = true }
    const o = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((x, y) => x.localeCompare(y)).forEach((n) => o.push({ value: n, label: n }))
    if (none) o.push({ value: '__none__', label: t('app.noTeam') })
    return o
  }, [advice, t])
  const groupOptions = useMemo(() => {
    const names = new Set(); let none = false
    for (const a of advice) { if (a.group_name) names.add(a.group_name); else none = true }
    const o = [{ value: 'all', label: t('app.allGroups') }]
    ;[...names].sort((x, y) => x.localeCompare(y)).forEach((n) => o.push({ value: n, label: n }))
    if (none) o.push({ value: '__none__', label: t('app.noGroup') })
    return o
  }, [advice, t])
  const tagOptions = useMemo(() => {
    const o = [{ value: 'all', label: t('mon.allTags') }]
    tagNamesOf(advice).forEach((n) => o.push({ value: n, label: n }))
    if (advice.some((a) => !(a.tags || '').trim())) o.push({ value: '__none__', label: t('mon.noTags') })
    return o
  }, [advice, t])
  const codeOptions = useMemo(() => {
    const present = new Set(advice.map((a) => a.code))
    return [{ value: 'all', label: t('renewal.allReasons') }, ...CODES.filter((c) => present.has(c)).map((c) => ({ value: c, label: codeLabel(c) }))]
  }, [advice, t])   // eslint-disable-line react-hooks/exhaustive-deps
  const sortOptions = [
    { value: 'priority', label: t('renewal.sortPriority') },
    { value: 'days', label: t('renewal.sortDays') },
    { value: 'domain', label: t('renewal.sortDomain') },
    { value: 'team', label: t('renewal.sortTeam') },
  ]

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    const list = advice.filter((a) => {
      if (priority !== 'all' && a.priority !== priority) return false
      if (code !== 'all' && a.code !== code) return false
      if (teamFilter !== 'all') { if (teamFilter === '__none__') { if (a.team_name) return false } else if (a.team_name !== teamFilter) return false }
      if (groupFilter !== 'all') { if (groupFilter === '__none__') { if (a.group_name) return false } else if (a.group_name !== groupFilter) return false }
      if (!matchesTag(a, tagFilter)) return false
      if (!q) return true
      return (a.domain || '').toLowerCase().includes(q) || (a.issuer_cn || '').toLowerCase().includes(q) || matchesGroupOrTagText(a, q)
    })
    const days = (a) => (a.days_remaining == null ? -99999 : a.days_remaining)
    list.sort((x, y) => {
      if (sortKey === 'domain') return (x.domain || '').localeCompare(y.domain || '')
      if (sortKey === 'team') return (x.team_name || '').localeCompare(y.team_name || '') || days(x) - days(y)
      if (sortKey === 'days') return days(x) - days(y)
      return (PRIORITY_ORDER[x.priority] ?? 9) - (PRIORITY_ORDER[y.priority] ?? 9) || days(x) - days(y)
    })
    return list
  }, [advice, search, priority, code, teamFilter, groupFilter, tagFilter, sortKey])

  const pager = usePagination(filtered, { listKey: 'renewal-advice', defaultSize: 25, resetDeps: [search, priority, code, teamFilter, groupFilter, tagFilter, sortKey, view] })
  const filtersActive = !!search.trim() || priority !== 'all' || code !== 'all' || teamFilter !== 'all' || groupFilter !== 'all' || tagFilter !== 'all' || sortKey !== 'priority'
  const clearFilters = () => { setSearch(''); setPriority('all'); setCode('all'); setTeamFilter('all'); setGroupFilter('all'); setTagFilter('all'); setSortKey('priority') }
  useUrlQuerySync({
    q: search.trim() || null, r_pri: priority !== 'all' ? priority : null, r_code: code !== 'all' ? code : null,
    team: teamFilter !== 'all' ? teamFilter : null, group: groupFilter !== 'all' ? groupFilter : null, tag: tagFilter !== 'all' ? tagFilter : null,
    sort: sortKey !== 'priority' ? sortKey : null, page: pager.page > 1 ? pager.page : null,
  })

  const calEvents = filtered.filter((a) => a.not_after).map((a) => ({
    date: a.not_after, label: a.domain, title: `${a.domain} · ${adviceText(a).message}`,   // takvim yerel güne yerleştirir (ISSUE-004)
    tone: a.priority === 'critical' ? 'bad' : a.priority === 'warning' ? 'warn' : 'info', onClick: () => onSelectDomain?.(a.domain),
  }))
  function exportIcs() {
    downloadIcs('sertifika-yenilemeleri.ics', buildIcs(filtered.filter((a) => a.not_after).map((a) => {
      const tx = adviceText(a)
      return { uid: `cert-${a.domain}-${localDayKey(a.not_after)}`, date: a.not_after, summary: `${t('renewal.icsPrefix')} ${a.domain}`, description: `${tx.message}\n${tx.action || ''}` }
    }), { calName: t('renewal.icsCal') }))
  }
  function exportCsv() {
    const head = ['domain', 'priority', 'reason', 'days_remaining', 'not_after', 'team', 'tier', 'group', 'tags', 'issuer', 'action']
    const rows = filtered.map((a) => [a.domain, a.priority, a.code, a.days_remaining ?? '', a.not_after ? formatDateOnly(a.not_after) : '', a.team_name || '', a.tier ?? '', a.group_name || '', a.tags || '', a.issuer_cn || '', adviceText(a).action || ''])
    const csv = '﻿' + csvRows([head, ...rows])
    try {
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const el = document.createElement('a'); el.href = url; el.download = 'yenileme-onerileri.csv'; document.body.appendChild(el); el.click(); el.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch { /* jsdom */ }
  }
  async function copyDomain(d) {
    try { await navigator.clipboard.writeText(d); setCopied(d); setTimeout(() => setCopied(null), 1500) } catch { /* izin yok */ }
  }

  const PRIORITY_LABEL = { critical: t('renewal.critical'), warning: t('renewal.warning'), info: t('renewal.info') }
  const daysNode = (a) => {
    const d = a.days_remaining
    if (d == null) return <span className="rn-days rn-days--unknown">{t('renewal.unknown')}</span>
    const cls = d < 0 ? 'rn-days--past' : d <= 7 ? 'rn-days--crit' : d <= 30 ? 'rn-days--warn' : ''
    return <span className={`rn-days ${cls}`}><b>{Math.abs(d)}</b> {d < 0 ? t('renewal.daysAgo') : t('renewal.daysLeft')}</span>
  }

  if (loading) return <LoadingBlock label={t('renewal.loading')} fullWidth />
  // Hata bandi "her sey yolunda" bos durumunun ONUNDE: aksi halde yukleme hatasi
  // "yenilenecek sertifika yok" gibi okunurdu.
  if (loadError && advice.length === 0)
    return (
      <AlertBanner tone="danger" title={t('mon.loadError')} role="alert">
        {String(loadError)}
      </AlertBanner>
    )
  if (advice.length === 0)
    return <StatusBlock tone="success" icon={ShieldCheck} title={t('renewal.allGood')} description={t('empty.hintAllGood')} />

  const Kpi = ({ k, label, value, color, onClick, active }) => (
    <button type="button" className={`rn-kpi${active ? ' is-active' : ''}`} style={{ '--kpi': color }} onClick={onClick} title={t('renewal.kpiTip')} data-kpi={k}>
      <span className="rn-kpi-num">{value}</span>
      <span className="rn-kpi-lbl">{label}</span>
    </button>
  )

  return (
    <div className="renewal-container rn">
      {/* ── Özet şeridi ── */}
      <div className="rn-kpis">
        <Kpi k="critical" label={t('renewal.critical')} value={kpi.critical} color={PRIORITY_COLOR.critical} active={priority === 'critical'} onClick={() => setPriority((p) => (p === 'critical' ? 'all' : 'critical'))} />
        <Kpi k="warning" label={t('renewal.warning')} value={kpi.warning} color={PRIORITY_COLOR.warning} active={priority === 'warning'} onClick={() => setPriority((p) => (p === 'warning' ? 'all' : 'warning'))} />
        <Kpi k="info" label={t('renewal.info')} value={kpi.info} color={PRIORITY_COLOR.info} active={priority === 'info'} onClick={() => setPriority((p) => (p === 'info' ? 'all' : 'info'))} />
        <div className="rn-kpi rn-kpi--static" style={{ '--kpi': '#6366f1' }}>
          <span className="rn-kpi-num">{kpi.week}</span>
          <span className="rn-kpi-lbl">{t('renewal.kpiWeek')}</span>
        </div>
        <div className="rn-kpi rn-kpi--static" style={{ '--kpi': '#14b8a6' }} title={t('renewal.batchTip')}>
          <span className="rn-kpi-num">{kpi.batches}</span>
          <span className="rn-kpi-lbl">{t('renewal.kpiBatches')}</span>
        </div>
      </div>

      {/* ── Süzgeç çubuğu ── */}
      <div className="rn-toolbar">
        <div className="rn-search-wrap">
          <Search size={14} aria-hidden="true" />
          <input className="rn-search" type="text" placeholder={t('renewal.searchPlaceholder')} value={search} onChange={(e) => setSearch(e.target.value)} aria-label={t('renewal.searchPlaceholder')} />
        </div>
        <SearchableSelect value={code} onChange={setCode} options={codeOptions} ariaLabel={t('renewal.reason')} />
        {teamOptions.length > 1 && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('inv.colTeam')} />}
        {groupOptions.length > 1 && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupOptions} searchThreshold={2} ariaLabel={t('app.groupLabel')} />}
        {tagOptions.length > 1 && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagOptions} searchThreshold={2} ariaLabel={t('app.tagLabel')} />}
        <SearchableSelect value={sortKey} onChange={setSortKey} options={sortOptions} ariaLabel={t('renewal.sort')} />
        {filtersActive && <button type="button" className="btn btn-secondary btn-sm-p" onClick={clearFilters}>{t('app.clearFilters')}</button>}
        <span className="rn-count">{t('renewal.shown', filtered.length, advice.length)}</span>
        <div className="seg rn-views" role="group" aria-label={t('renewal.viewLabel')}>
          <button type="button" className={`btn btn-sm ${view === 'list' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => switchView('list')} aria-pressed={view === 'list'}><List size={13} /> {t('renewal.viewList')}</button>
          <button type="button" className={`btn btn-sm ${view === 'table' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => switchView('table')} aria-pressed={view === 'table'}><Table2 size={13} /> {t('renewal.viewTable')}</button>
          <button type="button" className={`btn btn-sm ${view === 'calendar' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => switchView('calendar')} aria-pressed={view === 'calendar'}><CalendarDays size={13} /> {t('renewal.viewCalendar')}</button>
        </div>
        <button type="button" className="btn btn-sm btn-secondary" onClick={exportCsv} disabled={!filtered.length} title={t('renewal.csvTip')}><FileDown size={13} /> CSV</button>
        <button type="button" className="btn btn-sm btn-secondary" onClick={exportIcs} disabled={!calEvents.length} title={t('renewal.icsTip')}><Download size={13} /> {t('renewal.ics')}</button>
      </div>

      {filtered.length === 0 && <StatusBlock tone="neutral" title={t('renewal.noneFiltered')} description={t('empty.hintFilter')} />}

      {view === 'calendar' && filtered.length > 0 && <MonthCalendar events={calEvents} ariaLabel={t('renewal.viewCalendar')} />}

      {view === 'table' && filtered.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table rn-table">
            <thead><tr>
              <th>{t('renewal.colPriority')}</th><th>{t('renewal.colDomain')}</th><th>{t('renewal.reason')}</th>
              <th>{t('renewal.colDays')}</th><th>{t('renewal.expiry')}</th><th>{t('inv.colTeam')}</th><th>{t('renewal.colIssuer')}</th><th></th>
            </tr></thead>
            <tbody>{pager.pageItems.map((a) => (
              <tr key={a.domain + a.code} className="rn-row">
                <td><span className="renewal-badge" style={{ background: PRIORITY_COLOR[a.priority] || '#6c757d' }}>{PRIORITY_LABEL[a.priority] || a.priority}</span></td>
                <td>
                  <button type="button" className="inv-domain" onClick={() => onSelectDomain?.(a.domain)}>{a.domain}</button>
                  {a.port && a.port !== 443 ? <span className="inv-dim">:{a.port}</span> : null}
                  {a.tier && <span className={`tier-badge tier-badge-${a.tier}`}> T{a.tier}</span>}
                  {fpCount.get(a.fingerprint) > 1 && <span className="rn-shared" title={t('renewal.batchTip')}>{t('renewal.shared', fpCount.get(a.fingerprint))}</span>}
                </td>
                <td>{codeLabel(a.code)}</td>
                <td>{daysNode(a)}</td>
                <td className="inv-dim">{a.not_after ? formatDateOnly(a.not_after) : '—'}</td>
                <td>{a.team_name ? <TeamBadge teamId={a.team_id} teamName={a.team_name} /> : <span className="inv-dim">—</span>}</td>
                <td className="inv-dim rn-issuer" title={a.issuer_cn || ''}>{a.issuer_cn || '—'}</td>
                <td className="rn-actions">
                  <button type="button" className="btn btn-sm btn-secondary" onClick={() => onSelectDomain?.(a.domain)}>{t('renewal.detail')}</button>
                  {a.code === 'UNREACHABLE' && <button type="button" className="btn btn-sm btn-secondary" onClick={() => setDiag({ domain: a.domain, port: a.port || 443 })}><Stethoscope size={12} /> {t('renewal.diagnoseShort')}</button>}
                </td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}

      {view === 'list' && pager.pageItems.map((item) => {
        const color = PRIORITY_COLOR[item.priority] || '#6c757d'
        const label = PRIORITY_LABEL[item.priority] || item.priority
        const days = item.days_remaining
        const daysAbs = days !== null && days !== undefined ? Math.abs(days) : null
        const daysLabel = days === null || days === undefined ? t('renewal.unknown') : days < 0 ? t('renewal.daysAgo') : t('renewal.daysLeft')
        const shared = fpCount.get(item.fingerprint) > 1 ? fpCount.get(item.fingerprint) : 0
        return (
          <div key={item.domain + item.code} className="renewal-card" style={{ borderLeftColor: color }}>
            <div className="renewal-card-body">
              <div className="renewal-card-header">
                <span className="renewal-badge" style={{ background: color }}>{label}</span>
                <button type="button" className="renewal-domain rn-domain-btn" onClick={() => onSelectDomain?.(item.domain)} title={t('renewal.openDetail')}>{item.domain}{item.port && item.port !== 443 ? `:${item.port}` : ''}</button>
                {item.tier && <span className={`tier-badge tier-badge-${item.tier}`}>T{item.tier}</span>}
                {item.team_name && <TeamBadge teamId={item.team_id} teamName={item.team_name} />}
                <span className="rn-code-chip">{codeLabel(item.code)}</span>
                {shared > 0 && <span className="rn-shared" title={t('renewal.batchTip')}>{t('renewal.shared', shared)}</span>}
              </div>
              <div className="renewal-message">{adviceText(item).message}</div>
              <div className="renewal-action">
                <strong>{t('renewal.action')}</strong> <code>{adviceText(item).action}</code>
              </div>
              <div className="rn-meta">
                {item.issuer_cn && <span className="rn-meta-item" title={t('renewal.colIssuer')}><ShieldCheck size={11} aria-hidden="true" /> {item.issuer_cn}</span>}
                {item.group_name && <button type="button" className="inv-tag upt-group-chip" title={t('card.group')} onClick={() => setGroupFilter(item.group_name)}><FolderOpen size={10} /> {item.group_name}</button>}
                {tagsOf(item).map((tag) => <button key={tag} type="button" className="inv-tag" title={t('card.tag')} onClick={() => setTagFilter(tag)}>{tag}</button>)}
              </div>
              <div className="rn-card-actions">
                <button type="button" className="btn btn-sm btn-secondary" onClick={() => onSelectDomain?.(item.domain)}>{t('renewal.detail')}</button>
                {item.code === 'UNREACHABLE' && (
                  <button type="button" className="btn btn-sm btn-secondary" onClick={() => setDiag({ domain: item.domain, port: item.port || 443 })}><Stethoscope size={12} /> {t('renewal.diagnose')}</button>
                )}
                <button type="button" className="btn btn-sm btn-secondary" onClick={() => copyDomain(item.domain)}><Copy size={12} /> {copied === item.domain ? t('renewal.copied') : t('renewal.copy')}</button>
              </div>
            </div>
            <div className="renewal-expiry-stamp" style={{ background: `linear-gradient(150deg, ${color}18 0%, ${color}38 100%)`, borderLeftColor: `${color}50` }}>
              <span className="expiry-days-num" style={{ color }}>{daysAbs !== null ? daysAbs : '?'}</span>
              <span className="expiry-days-lbl" style={{ color }}>{daysLabel}</span>
              <div className="expiry-divider" style={{ background: `${color}40` }} />
              <span className="expiry-date-caption">{t('renewal.expiry')}</span>
              <span className="expiry-date-val">{item.not_after ? formatDateOnly(item.not_after) : '—'}</span>
            </div>
          </div>
        )
      })}

      {view !== 'calendar' && filtered.length > 0 && <PaginationBar {...pager} />}

      {/* Tanılama modalı (envanter ile ortak) — backend izlenen domainlere açık */}
      {diag && <DiagnosticsModal domain={diag.domain} port={diag.port} onClose={() => setDiag(null)} />}
    </div>
  )
}

