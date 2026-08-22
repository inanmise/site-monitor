import { MoveRight, Lock } from 'lucide-react'
import { fieldLabel, formatValue, parseChanges, MASK } from './changeFields.js'

/**
 * Bir değişikliğin alan-bazlı "eski → yeni" özeti.
 *
 * <p>Hem izleme detayındaki Değişiklikler sekmesi hem yönetici konsolu bunu kullanır — diff
 * sunumu TEK yerde tanımlıdır. İkinci bir kopya, iki ekranın aynı değişikliği farklı göstermesi
 * demek olurdu.
 *
 * <p>Maskeli değer (`***`) kilit ikonuyla gösterilir: kullanıcı "alan değişti ama değerini
 * göremiyorum" ile "değer boş" arasındaki farkı görmeli.
 *
 * @param limit  gösterilecek en fazla çip; kalanı "+N alan" olarak özetlenir (konsol satırı için)
 */
export default function ChangeDiffChips({ t, changes, teamNames = {}, limit = 0, className = '' }) {
  const rows = parseChanges(changes)
  if (rows.length === 0) return null

  const shown = limit > 0 ? rows.slice(0, limit) : rows
  const hidden = rows.length - shown.length
  const ctx = { t, teamNames }

  return (
    <span className={`chg-chips ${className}`.trim()}>
      {shown.map(r => {
        const from = formatValue(r.key, r.from, ctx)
        const to = formatValue(r.key, r.to, ctx)
        const masked = r.from === MASK || r.to === MASK
        return (
          <span key={r.key} className="chg-chip" title={`${fieldLabel(t, r.key)}: ${from} → ${to}`}>
            <span className="chg-chip-field">{fieldLabel(t, r.key)}</span>
            {masked && <Lock size={11} className="chg-chip-lock" aria-hidden="true" />}
            <span className="chg-chip-from">{from}</span>
            <MoveRight size={12} className="chg-chip-arrow" aria-hidden="true" />
            <span className="chg-chip-to">{to}</span>
          </span>
        )
      })}
      {hidden > 0 && <span className="chg-chip chg-chip--more">{t('chg.moreFields', hidden)}</span>}
    </span>
  )
}
