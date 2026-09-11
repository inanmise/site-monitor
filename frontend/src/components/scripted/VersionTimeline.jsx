import { FilePlus2, Pencil, Globe, Users, Trash2, Undo2, RotateCcw, Sprout, ChevronRight } from 'lucide-react'
import { formatDateSec } from '../../api/client'
import UserBadge from '../ui/UserBadge.jsx'

/**
 * Sürüm geçmişi zaman çizelgesi — şablon modalı ve monitör sekmesinin ORTAK gövdesi.
 *
 * <p><b>Neden tablo değil.</b> Önceden ikisi de `health-dbtable`'ı ödünç alıyordu: o tablo Sistem
 * Sağlığı'nın veritabanı listesi için yazılmış, {@code table-layout: fixed} ve orada tanımlı
 * genişlik sınıflarıyla çalışıyor. Buradaki beş sütun o sınıfları HİÇ kullanmadığı için tarayıcı
 * beşini de eşit bölüyordu — "Sürüm" ile "Not" aynı genişlikte. Üstelik hücreler dolgulu/kenarlıklı
 * sınıfları (`dbtcol-*-cell`) taşımadığından tamamen dolgusuz kalıyor, `dbtcol-th` ise sıralanabilir
 * sütun izlenimi veren el imleci + mavi hover taşıyordu (sıralama YOK). Sonuç: sıkışık, çerçevesiz
 * ve yanıltıcı bir ızgara.
 *
 * <p>Veri zaten bir olay akışı ("kim, ne zaman, ne yaptı"), tablo değil: her satır bir olay, sırası
 * anlamlı ve alanların uzunlukları çok farklı. Zaman çizelgesi bu şekli doğrudan karşılıyor, notu
 * kendi satırına alıyor ve dar modal genişliğinde kırpılmadan okunuyor.
 *
 * <p>Satır düğme DEĞİL: içinde {@link UserBadge} var ve buton içinde etkileşimli/karmaşık içerik
 * yuvalamak geçersiz HTML olurdu. Bunun yerine `role="button"` + `tabIndex` + Enter/Space — böylece
 * ModalShell'in odak tuzağı da satırları görüyor (`[tabindex]` seçicisi).
 */

/** Olay → ikon + ton. Bilinmeyen olay nötr noktayla çizilir (sessiz boşluk olmaz). */
const EVENT_STYLE = {
  CREATE:   { icon: FilePlus2, tone: 'new' },
  SEED:     { icon: Sprout,    tone: 'new' },
  EDIT:     { icon: Pencil,    tone: 'edit' },
  RESTORE:  { icon: RotateCcw, tone: 'edit' },
  PROMOTE:  { icon: Globe,     tone: 'up' },
  DEMOTE:   { icon: Users,     tone: 'down' },
  DELETE:   { icon: Trash2,    tone: 'danger' },
  UNDELETE: { icon: Undo2,     tone: 'up' },
}
const DEFAULT_STYLE = { icon: Pencil, tone: 'edit' }

// Saf sunum: metinlerin tamamı prop olarak gelir (bileşen i18n'e bağlı değil).
// 2026-09-11 genişleme (sürüm & dağıtım geçmişi için, mevcut 3 çağıran DEĞİŞMEZ):
//   eventStyles — EVENT_STYLE üstüne birleşir (yeni olay türleri: UPGRADE/ROLLBACK/RELEASE…)
//   renderMeta(v) — verilince tarih+UserBadge satırının YERİNE çizilir (dağıtımda kullanıcı yok)
//   versionPrefix — "v" varsayılan
//   caret(v) — sağdaki ok yerine özel işaret (aç/kapa)
//   renderBelow(v) — satırın altına genişletilmiş içerik (değişiklik listesi)
export default function VersionTimeline({
  rows, selId, onPick, eventLabel, currentLabel, renderExtra,
  eventStyles, renderMeta, versionPrefix = 'v', caret, renderBelow,
}) {
  const styles = eventStyles ? { ...EVENT_STYLE, ...eventStyles } : EVENT_STYLE
  return (
    <ol className="sc-vt">
      {rows.map(v => {
        const style = styles[v.event_type] || DEFAULT_STYLE
        const Icon = style.icon
        const selected = selId === v.id
        const pick = () => onPick(v)
        return (
          <li key={v.id} className={`sc-vt-item${selected ? ' is-sel' : ''}`}>
            <div className="sc-vt-row" role="button" tabIndex={0} aria-pressed={selected}
              onClick={pick}
              onKeyDown={e => {
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick() }
              }}>
              <span className={`sc-vt-dot sc-vt-dot--${style.tone}`} aria-hidden="true">
                <Icon size={13} />
              </span>

              <span className="sc-vt-body">
                <span className="sc-vt-head">
                  <span className="sc-ver-chip sc-ver-chip--cell">{versionPrefix}{v.version}</span>
                  <span className="sc-vt-event">{eventLabel(v.event_type)}</span>
                  {v.current && <span className="sc-ver-current">{currentLabel}</span>}
                  {renderExtra?.(v)}
                </span>
                {renderMeta ? renderMeta(v) : (
                <span className="sc-vt-meta">
                  <span className="sys-mono">{formatDateSec(v.created_at)}</span>
                  <span className="sc-vt-sep" aria-hidden="true">·</span>
                  <UserBadge username={v.created_by} size="sm" inline nameOnly />
                </span>
                )}
                {/* Not KENDİ satırında: tabloda "Not" sütunu diğerleriyle eşit genişlikteydi ve
                    uzun notlar okunmaz hâle geliyordu. Yoksa satır hiç çizilmez — boş "—" yerine. */}
                {v.note && <span className="sc-vt-note">{v.note}</span>}
              </span>

              {caret ? caret(v) : <ChevronRight size={15} className="sc-vt-caret" aria-hidden="true" />}
            </div>
            {renderBelow?.(v) && <div className="sc-vt-below">{renderBelow(v)}</div>}
          </li>
        )
      })}
    </ol>
  )
}
