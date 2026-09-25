import { useMemo, useState } from 'react'
import { Users, Play } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'

export const TEAMS_KEY = 'sm.checkRun.teams'
/** Takımsız sertifikalar için sanal anahtar (gerçek team_id null). */
export const NO_TEAM = '__none__'

export function readSavedTeams(key = TEAMS_KEY) {
  try {
    const raw = localStorage.getItem(key)
    const arr = raw ? JSON.parse(raw) : null
    return Array.isArray(arr) ? arr.map(String) : null
  } catch { return null }
}
function writeSavedTeams(key, keys) {
  try { localStorage.setItem(key, JSON.stringify(keys)) } catch { /* yoksay */ }
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
  return sortBuckets([...map.values()])
}

/**
 * İzleme listesi → takım kovaları. Sertifika ikizinden AYRI bir fonksiyon çünkü ANAHTAR farklı:
 * burada takım kimliği {@code team_name}'dir, {@code team_id} değil.
 *
 * <p><b>Neden:</b> Port ve DNS izlemeleri çift kaynaklı — envanterden türeyen satırlarda
 * {@code team_id} NULL kalır ama {@code team_name} domain→takım haritasından DOLU gelir
 * (MonitoringController.enrichPort/enrichDns). {@code team_id} ile kovalamak bu satırların
 * hepsini "Takımsız"a düşürürdü; oysa sayfanın kendi takım filtresi (monitorFilters
 * matchesTeamAndGroup) ve {@code useTeamOptions} zaten {@code team_name} karşılaştırıyor.
 * Aynı ekranda iki farklı takım tanımı olamaz — anahtar tek olmalı.
 */
export function monitorTeamBuckets(monitors) {
  const map = new Map()
  for (const m of monitors || []) {
    if (!m) continue
    const key = m.team_name || NO_TEAM
    const cur = map.get(key)
    if (cur) cur.count += 1
    else map.set(key, { key, label: m.team_name || null, count: 1 })
  }
  return sortBuckets([...map.values()])
}

function sortBuckets(buckets) {
  return buckets.sort((a, b) => {
    if (a.key === NO_TEAM) return 1
    if (b.key === NO_TEAM) return -1
    return (a.label || '').localeCompare(b.label || '', 'tr')
  })
}

/**
 * Kontrol öncesi takım seçimi. Kullanıcı bir, birkaç veya tüm takımları seçer;
 * seçim localStorage'da saklanır ve bir sonraki açılışta ön-seçili gelir.
 * Takım listesi çağıranın elindeki kayıtlardan türetilir — kullanıcı zaten göremediği
 * takımı seçemez, ek uç nokta/izin gerekmez.
 *
 * <p>Sertifika panosu {@code certs} geçer (kovalar burada türetilir); izleme sayfaları hazır
 * {@code buckets} ve kendi {@code storageKey}'ini geçer. Anahtarın tür başına ayrılması ŞART:
 * tek anahtar paylaşılsaydı HTTP sayfasında yapılan takım seçimi panonun seçimini ezerdi.
 */
export default function CheckTeamPicker({
  certs, buckets: bucketsProp, storageKey = TEAMS_KEY,
  descText, totalText, emptyText, onStart, onClose,
}) {
  const t = useT()
  const buckets = useMemo(() => bucketsProp ?? teamBuckets(certs), [bucketsProp, certs])
  const allKeys = useMemo(() => buckets.map(b => b.key), [buckets])

  // Varsayılan HER ZAMAN tüm takımlar (kullanıcı kararı 2026-09-22): önceki seçim geri yüklenmez — "Şimdi Kontrol Et"
  // rutini tüm envanteri taramaktır; daraltma o koşum için bilinçli bir seçimdir. Son seçim yine yazılır (readSavedTeams
  // başka yüzeyler için kalır).
  const [selected, setSelected] = useState(() => allKeys)

  const total = buckets.filter(b => selected.includes(b.key)).reduce((s, b) => s + b.count, 0)
  const allSelected = selected.length === allKeys.length && allKeys.length > 0

  function toggle(key) {
    setSelected(cur => cur.includes(key) ? cur.filter(k => k !== key) : [...cur, key])
  }
  function toggleAll() {
    setSelected(allSelected ? [] : allKeys)
  }
  function start() {
    writeSavedTeams(storageKey, selected)
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
        <p className="chk-team-desc">{descText || t('app.checkTeamDesc')}</p>

        {buckets.length === 0 ? (
          <p className="chk-team-empty">{emptyText || t('app.checkTeamEmpty')}</p>
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
          <span className="chk-team-total">{totalText ? totalText(total) : t('app.checkTeamTotal', total)}</span>
          <Button variant="secondary" onClick={onClose}>{t('app.cancel')}</Button>
          <Button onClick={start} disabled={total === 0}>
            <Play size={14} />{t('app.checkTeamStart')}
          </Button>
        </div>
      </div>
    </div>
  )
}
