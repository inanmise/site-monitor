import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { STATUS_OPTIONS, TIER_OPTIONS } from './certTableModel.js'

/**
 * Tüm Sertifikalar tablosu — başlığın altındaki KOLON SÜZGEÇ SATIRI (2026-09-22, kullanıcı isteği: envanterdekiyle aynı).
 * Bu tablo SUNUCU sayfalı: süzgeçler mevcut sunucu parametrelerine eşlenir (filter_domain/issuer/status/team/window/tier/
 * port/insecure/fp) — yeni sunucu süzgeci uydurulmaz; sunucunun süzemediği kolonlar (SAN, imza, anahtar, TLS, seri…)
 * boş hücre olarak kalır. Metin alanları tablonun 300 ms debounce'undan geçer (filters → queryFilters).
 * Hücre sırası thead ile birebir: [seçim] + cols + işlem.
 */
export default function CertFilterRow({ filters, onFilter, cols, facets, teamNames = {}, showSelect, pageRows = [] }) {
  const t = useT()
  const set = (k, v) => onFilter({ ...filters, [k]: v })
  const any = { value: '', label: t('inv.filterAny') }
  const sel = (key, options) => <SearchableSelect value={filters[key] || ''} onChange={(v) => set(key, v)} options={[any, ...options]} searchThreshold={6} />
  const text = (key, ph, aria) => (
    <span className="inv-fr-text">
      <input type="search" className="input input-sm" value={filters[key] || ''} onChange={(e) => set(key, e.target.value)} placeholder={ph} aria-label={aria} />
    </span>
  )
  const teamOpts = [
    ...(facets?.teams || []).map((tm) => ({ value: String(tm.id), label: `${tm.name}${tm.count != null ? ` (${tm.count})` : ''}` })),
    ...(facets && facets.no_team > 0 ? [{ value: '__none__', label: `${t('app.noTeam')} (${facets.no_team})` }] : []),
  ]
  if (filters.team && !teamOpts.some((o) => o.value === filters.team)) teamOpts.push({ value: filters.team, label: teamNames[filters.team] || filters.team })
  // Port: sunucu tam port ya da 'nonstd' süzer; seçenekler sayfadaki satırlardan (facet yok)
  const ports = [...new Set(pageRows.map((c) => String(c.port ?? 443)))].sort((a, b) => Number(a) - Number(b))
  const portOpts = [{ value: 'nonstd', label: t('tbl.portNonstd') }, ...ports.map((p) => ({ value: p, label: p }))]
  if (filters.port && !portOpts.some((o) => o.value === filters.port)) portOpts.push({ value: filters.port, label: filters.port })

  const cellFor = (key) => {
    switch (key) {
      case 'domain': return text('domain', t('inv.colFilterDomainPh'), t('inv.colFilterDomain'))
      case 'issuer': return text('issuer', t('tbl.issuerPh'), t('tbl.colIssuer'))
      case 'team': return sel('team', teamOpts)
      case 'days': return sel('window', [
        { value: 'expired', label: t('tbl.winExpired') }, ...['7', '30', '60', '90'].map((d) => ({ value: d, label: t('tbl.winDays', d) })),
      ])
      case 'status': return sel('status', STATUS_OPTIONS.filter((o) => o.value).map((o) => ({ value: o.value, label: `${o.icon} ${t(o.labelKey)}` })))
      case 'tier': return sel('tier', TIER_OPTIONS.filter(Boolean).map((x) => ({ value: x, label: `T${x}${facets?.tiers?.[x] != null ? ` (${facets.tiers[x]})` : ''}` })))
      case 'port': return sel('port', portOpts)
      case 'trust': return (
        <SearchableSelect value={filters.insecure ? 'insecure' : ''} onChange={(v) => set('insecure', v === 'insecure')}
          options={[any, { value: 'insecure', label: `${t('tbl.onlyInsecure')}${facets ? ` (${facets.insecure ?? 0})` : ''}` }]} />
      )
      case 'fingerprint': return text('fp', t('tbl.colFingerprint'), t('tbl.colFingerprint'))
      default: return null
    }
  }

  return (
    <tr className="inv-filter-row ct-filter-row" data-testid="ct-filter-row">
      {showSelect && <td className="inv-fr-cell" />}
      {cols.map((key) => <td key={key} className="inv-fr-cell" data-col={key}>{cellFor(key)}</td>)}
      <td className="inv-fr-cell" />
    </tr>
  )
}
