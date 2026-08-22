import { Globe, CalendarDays, Network, Search, Target, Radio, ScanSearch, FlaskConical,
  ShieldCheck, Folder, Wrench, Layers, FilePlus2, Pencil, Trash2 } from 'lucide-react'

/**
 * Tür kartları — "hangi izlemede ne kadar oluşturma / değişiklik / silme oldu" tek bakışta.
 *
 * <p>Önceki hâli dört düz sayı kutusuydu (toplam + üç olay). Toplamlar "bugün 40 değişiklik
 * olmuş" der ama YÖNETİCİNİN sorduğu soruyu cevaplamaz: hangi izleme türü hareketli, nerede
 * silme var, hangi tür hiç dokunulmamış. Kart başına tür + olay kırılımı bunu doğrudan gösterir.
 *
 * <p>İkonlar Nav'daki izleme türü ikonlarının AYNISI: kullanıcı aynı türü menüde hangi şekille
 * tanıyorsa burada da onu görür, yeni bir görsel dil öğrenmesi gerekmez.
 *
 * <p>Kartlar aynı zamanda SÜZGEÇ: tıklanınca liste o türe daralır, tekrar tıklanınca açılır.
 * Bu yüzden sayılar tür süzgecinden ETKİLENMEZ (sunucu kasten yalnız tarih + kapsam uygular) —
 * aksi halde bir türe tıklandığında diğer kartlar sıfırlanır ve karşılaştırma kaybolurdu.
 */

const ICONS = {
  http: Globe, domain: CalendarDays, port: Network, dns: Search, keyword: Target,
  ping: Radio, page: ScanSearch, scripted: FlaskConical,
  inventory: ShieldCheck, group: Folder, maintenance: Wrench,
}

/** Kart üstündeki kırılım: ikon + ton + i18n anahtarı. RESTORE/GROUP_RENAME "diğer"e toplanır. */
const BREAKDOWN = [
  { key: 'CREATE', Icon: FilePlus2, tone: 'new' },
  { key: 'UPDATE', Icon: Pencil, tone: 'edit' },
  { key: 'DELETE', Icon: Trash2, tone: 'danger' },
]

const sum = (obj) => Object.values(obj || {}).reduce((a, b) => a + Number(b || 0), 0)

export default function ChangeKindCards({ t, kindCounts = {}, selected = '', onSelect }) {
  // Sunucu yalnız KAYDI OLAN türleri döndürür; hiç değişiklik görmemiş tür kart üretmez
  // (11 boş kutu göstermek sinyali gürültüye çevirirdi).
  const kinds = Object.keys(kindCounts)
    .map(k => ({ kind: String(k).toLowerCase(), events: kindCounts[k], total: sum(kindCounts[k]) }))
    .filter(k => k.total > 0)
    .sort((a, b) => b.total - a.total)

  if (kinds.length === 0) return null

  const grandTotal = kinds.reduce((a, k) => a + k.total, 0)
  const busiest = kinds[0]

  return (
    <div className="chg-kpi-grid">
      {/* "Tümü" kartı: hem genel toplam hem süzgeci temizleme yolu. */}
      <button type="button"
        className={`chg-kpi chg-kpi--all${selected === '' ? ' is-sel' : ''}`}
        aria-pressed={selected === ''}
        onClick={() => onSelect('')}>
        <span className="chg-kpi-head">
          <span className="chg-kpi-icon"><Layers size={16} /></span>
          <span className="chg-kpi-name">{t('chg.allKinds')}</span>
        </span>
        <span className="chg-kpi-total">{grandTotal}</span>
        <span className="chg-kpi-sub">{t('chg.kpiBusiest', t('chg.kind.' + busiest.kind))}</span>
      </button>

      {kinds.map(({ kind, events, total }) => {
        const Icon = ICONS[kind] || Layers
        const isSel = selected === kind
        return (
          <button type="button" key={kind}
            className={`chg-kpi${isSel ? ' is-sel' : ''}`}
            aria-pressed={isSel}
            title={t('chg.kpiFilterHint')}
            onClick={() => onSelect(isSel ? '' : kind)}>
            <span className="chg-kpi-head">
              <span className="chg-kpi-icon"><Icon size={16} /></span>
              <span className="chg-kpi-name">{t('chg.kind.' + kind)}</span>
            </span>
            <span className="chg-kpi-total">{total}</span>

            {/* Kırılım: sıfır olan olay türü GÖSTERİLMEZ — "0 silme" bilgisi kartı doldurup
                asıl sayıları bastırıyordu. */}
            <span className="chg-kpi-breakdown">
              {BREAKDOWN.filter(b => Number(events?.[b.key] || 0) > 0).map(b => (
                <span key={b.key} className={`chg-kpi-chip chg-kpi-chip--${b.tone}`}
                  title={t('chg.event' + b.key)}>
                  <b.Icon size={11} aria-hidden="true" />{events[b.key]}
                </span>
              ))}
            </span>

            {/* Oran şeridi: sayıları okumadan da "burada çok silme var" görülebilsin.
                Genişlik YÜZDEYLE değil flex oranıyla veriliyor — bu bir doluluk göstergesi değil,
                üç parçalı bir DAĞILIM. Flex hem aritmetiği kaldırıyor hem de elle yazılmış yüzde
                çubuklarını yasaklayan bekçiye (progress-guard) takılmıyor; o kural tek değerli
                ilerleme çubukları için var ve ProgressBar burada yanlış bileşen olurdu. */}
            <span className="chg-kpi-bar" aria-hidden="true">
              {BREAKDOWN.map(b => {
                const n = Number(events?.[b.key] || 0)
                return n > 0 ? (
                  <i key={b.key} className={`chg-kpi-bar-seg chg-kpi-bar-seg--${b.tone}`}
                    style={{ flexGrow: n }} />
                ) : null
              })}
            </span>
          </button>
        )
      })}
    </div>
  )
}
