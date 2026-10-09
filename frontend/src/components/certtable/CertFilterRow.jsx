import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { COLUMN_BY_KEY, STATUS_OPTIONS, TIER_OPTIONS, trustFilterOptions } from './certTableModel.js'
import { Input } from '@/components/shadcn/input'
import { TableCell, TableRow } from '@/components/shadcn/table'

/**
 * Tüm Sertifikalar tablosu — başlığın altındaki KOLON SÜZGEÇ SATIRI (2026-09-22, kullanıcı isteği: envanterdekiyle aynı).
 * Bu tablo SUNUCU sayfalı: süzgeçler mevcut sunucu parametrelerine eşlenir (filter_domain/issuer/status/team/window/tier/
 * port/fp/trust) — yeni sunucu süzgeci uydurulmaz; sunucunun süzemediği kolonlar (SAN, imza, anahtar, TLS, seri…)
 * boş hücre olarak kalır. Metin alanları tablonun 300 ms debounce'undan geçer (filters → queryFilters).
 * Hücre sırası thead ile birebir: [seçim] + cols + işlem. Çizim shadcn: TableRow/TableCell + Input + ui/SearchableSelect.
 */
const CELL = 'bg-muted/40 px-1.5 py-1 align-middle border-b-2 border-border'

export default function CertFilterRow({ filters, onFilter, cols, facets, teamNames = {}, showSelect, pageRows = [] }) {
  const t = useT()
  const set = (k, v) => onFilter({ ...filters, [k]: v })
  const any = { value: '', label: t('inv.filterAny') }
  // Ad = KOLON: seçicinin tetiği role="combobox" ve içerikten ad almaz; hücrede görünür etiket de yok
  // (başlık üstte). Adsızken 6 seçicinin hepsi "combobox" olarak duyuluyordu (2026-09-25, R17).
  const colAria = (col) => t('flt.column', t(COLUMN_BY_KEY[col]?.labelKey ?? col))
  const sel = (key, options, col) => <SearchableSelect value={filters[key] || ''} onChange={(v) => set(key, v)} options={[any, ...options]} searchThreshold={6} ariaLabel={colAria(col)} />
  const text = (key, ph, aria) => (
    <Input type="search" className="h-8 min-w-[120px] md:text-[.82em]" value={filters[key] || ''}
      onChange={(e) => set(key, e.target.value)} placeholder={ph} aria-label={aria} />
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
      case 'team': return sel('team', teamOpts, 'team')
      case 'days': return sel('window', [
        { value: 'expired', label: t('tbl.winExpired') }, ...['7', '30', '60', '90'].map((d) => ({ value: d, label: t('tbl.winDays', d) })),
      ], 'days')
      // Seçici etiketi düz metin (aranır, tetikte yazılır) — durum ikonları sütun başlığı menüsünde (lucide), emoji yok.
      case 'status': return sel('status', STATUS_OPTIONS.filter((o) => o.value).map((o) => ({ value: o.value, label: t(o.labelKey) })), 'status')
      case 'tier': return sel('tier', TIER_OPTIONS.filter(Boolean).map((x) => ({ value: x, label: `T${x}${facets?.tiers?.[x] != null ? ` (${facets.tiers[x]})` : ''}` })), 'tier')
      case 'port': return sel('port', portOpts, 'port')
      // Güven (2026-10-09): sütunun gösterdiği değerlerle AYNI seçenekler (eskiden yalnız "Yalnız güvensiz" vardı —
      // sütunda "Kısmen doğrulandı" görünürken süzülemiyordu). "Yalnız güvensiz" süzgeç çubuğunda ayrı düğme olarak kalır.
      case 'trust': return sel('trust', trustFilterOptions(t, facets), 'trust')
      case 'fingerprint': return text('fp', t('tbl.colFingerprint'), t('tbl.colFingerprint'))
      default: return null
    }
  }

  return (
    <TableRow data-testid="ct-filter-row" className="hover:bg-transparent">
      {showSelect && <TableCell className={CELL} />}
      {cols.map((key) => <TableCell key={key} className={CELL} data-col={key}>{cellFor(key)}</TableCell>)}
      <TableCell className={CELL} />
    </TableRow>
  )
}
