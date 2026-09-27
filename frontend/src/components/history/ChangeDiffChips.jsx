import ChangeChipList from './ChangeChipList.jsx'
import { fieldLabel, formatValue, parseChanges, MASK } from './changeFields.js'

/**
 * İzleme değişikliğinin ham `changes` JSON'u → çip modeli (`ChangeChipList` girdisi). Değerler insancıllaştırılır
 * (`changeFields.formatValue`: süre birimleri, açık/kapalı, takım kimliği → ad); bozuk JSON boş liste döner.
 */
export function diffEntries(t, changes, teamNames = {}) {
  const ctx = { t, teamNames }
  return parseChanges(changes).map(r => {
    const from = formatValue(r.key, r.from, ctx)
    const to = formatValue(r.key, r.to, ctx)
    const label = fieldLabel(t, r.key)
    return {
      key: r.key, label, diff: true, from, to,
      full: `${label}: ${from} → ${to}`,
      masked: r.from === MASK || r.to === MASK,
    }
  })
}

/**
 * Bir izleme değişikliğinin alan-bazlı "eski → yeni" özeti — shadcn Badge çipleri (`ChangeChipList`).
 *
 * <p>Hem izleme detayındaki Değişiklikler sekmesi hem İzleme Değişiklikleri konsolu bunu kullanır — diff sunumu
 * TEK yerde tanımlıdır. Eski `.chg-chip*` App.css ailesinin yerini aldı (2026-09-26).
 *
 * @param limit  gösterilecek en fazla çip; kalanı "+N alan" olarak özetlenir (konsol satırı için)
 * @param wrap   true (varsayılan) → çipler sarar; false → masaüstünde tek satır (tablo hücresi)
 */
export default function ChangeDiffChips({ t, changes, teamNames = {}, limit = 0, wrap = true, className = '' }) {
  const all = diffEntries(t, changes, teamNames)
  if (all.length === 0) return null
  const shown = limit > 0 ? all.slice(0, limit) : all
  const hidden = all.length - shown.length
  return (
    <ChangeChipList entries={shown} more={hidden} moreLabel={t('chg.moreFields', hidden)}
      wrap={wrap} className={className} />
  )
}
