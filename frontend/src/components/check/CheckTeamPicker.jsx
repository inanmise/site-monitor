import { useMemo, useState } from 'react'
import { Users, Play } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'

export const TEAMS_KEY = 'sm.checkRun.teams'
/** Takımsız sertifikalar için sanal anahtar (gerçek team_id null). */
export const NO_TEAM = '__none__'

export function readSavedTeams() {
  try {
    const raw = localStorage.getItem(TEAMS_KEY)
    const arr = raw ? JSON.parse(raw) : null
    return Array.isArray(arr) ? arr.map(String) : null
  } catch { return null }
}
function writeSavedTeams(keys) {
  try { localStorage.setItem(TEAMS_KEY, JSON.stringify(keys)) } catch { /* yoksay */ }
}

/** certs → [{key, label, count}] (takım adına göre sıralı, takımsız en sonda). */
export function teamBuckets(certs) {
  const map = new Map()
  for (const c of certs || []) {
    if (!c?.domain) continue
    const key = c.team_id != null ? String(c.team_id) : NO_TEAM
    const cur = map.get(key)
    if (cur) cur.count += 1
    else map.set(key, { key, label: c.team_name || null, count: 1 })
  }
  return [...map.values()].sort((a, b) => {
    if (a.key === NO_TEAM) return 1
    if (b.key === NO_TEAM) return -1
    return (a.label || '').localeCompare(b.label || '', 'tr')
  })
}

/**
 * Kontrol öncesi takım seçimi. Kullanıcı bir, birkaç veya tüm takımları seçer;
 * seçim localStorage'da saklanır ve bir sonraki açılışta ön-seçili gelir.
 * Takım listesi App state'indeki certs'ten türetilir — kullanıcı zaten göremediği
 * takımı seçemez, ek uç nokta/izin gerekmez.
 */
export default function CheckTeamPicker({ certs, onStart, onClose }) {
  const t = useT()
  const buckets = useMemo(() => teamBuckets(certs), [certs])
  const allKeys = useMemo(() => buckets.map(b => b.key), [buckets])

  const [selected, setSelected] = useState(() => {
    const saved = readSavedTeams()
    const valid = saved ? saved.filter(k => allKeys.includes(k)) : null
    return valid && valid.length ? valid : allKeys
  })

  const total = buckets.filter(b => selected.includes(b.key)).reduce((s, b) => s + b.count, 0)
  const allSelected = selected.length === allKeys.length && allKeys.length > 0

  function toggle(key) {
    setSelected(cur => cur.includes(key) ? cur.filter(k => k !== key) : [...cur, key])
  }
  function toggleAll() {
    setSelected(allSelected ? [] : allKeys)
  }
  function start() {
    writeSavedTeams(selected)
    const label = allSelected
      ? t('app.checkTeamAllLabel')
      : buckets.filter(b => selected.includes(b.key))
          .map(b => b.label || t('app.checkTeamNone')).join(', ')
    onStart(selected, label)
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box chk-team-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--check">
          <div className="modal-icon-hdr-badge"><Users size={20} /></div>
          <h3>{t('app.checkTeamTitle')}</h3>
        </div>
        <p className="chk-team-desc">{t('app.checkTeamDesc')}</p>

        {buckets.length === 0 ? (
          <p className="chk-team-empty">{t('app.checkTeamEmpty')}</p>
        ) : (
          <>
            <label className="chk-team-row chk-team-all">
              <input type="checkbox" checked={allSelected} onChange={toggleAll} />
              <span className="chk-team-name">{t('app.checkTeamAll')}</span>
              <span className="chk-team-count">{allKeys.length}</span>
            </label>
            <div className="chk-team-list">
              {buckets.map(b => (
                <label key={b.key} className="chk-team-row">
                  <input type="checkbox" checked={selected.includes(b.key)} onChange={() => toggle(b.key)} />
                  <span className="chk-team-name">{b.label || t('app.checkTeamNone')}</span>
                  <span className="chk-team-count">{b.count}</span>
                </label>
              ))}
            </div>
          </>
        )}

        <div className="modal-actions">
          <span className="chk-team-total">{t('app.checkTeamTotal', total)}</span>
          <button className="btn btn-secondary" onClick={onClose}>{t('app.cancel')}</button>
          <button className="btn btn-primary" onClick={start} disabled={total === 0}>
            <Play size={14} />{t('app.checkTeamStart')}
          </button>
        </div>
      </div>
    </div>
  )
}
