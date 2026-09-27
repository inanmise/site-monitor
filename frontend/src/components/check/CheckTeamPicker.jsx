import { useId, useMemo, useState } from 'react'
import { Users, Play } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Field, FieldLabel } from '@/components/shadcn/field'
import { cn } from '@/lib/utils'

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
  // Kovalar açılıştan SONRA gelirse (liste henüz yüklenirken açıldı) varsayılan "tüm takımlar" boş kalıyor, Başlat pasif
  // görünüyordu (2026-09-27 regresyon B5). Kullanıcı dokunmadıkça seçim takım listesini İZLER — değer karşılaştırmalı,
  // render sırasında (React'in "önceki prop'a göre state" deseni): çağıranlar kovaları her çizimde yeni dizi olarak verir.
  const [touched, setTouched] = useState(false)
  const allSig = JSON.stringify(allKeys)
  const [seenSig, setSeenSig] = useState(allSig)
  if (!touched && seenSig !== allSig) {
    setSeenSig(allSig)
    setSelected(allKeys)
  }

  const total =buckets.filter(b => selected.includes(b.key)).reduce((s, b) => s + b.count, 0)
  const allSelected = selected.length === allKeys.length && allKeys.length > 0

  function toggle(key) {
    setTouched(true)
    setSelected(cur => cur.includes(key) ? cur.filter(k => k !== key) : [...cur, key])
  }
  function toggleAll() {
    setTouched(true)
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
    <ModalShell open onClose={onClose} title={t('app.checkTeamTitle')} icon={Users} size="sm"
      footer={<>
        <span data-slot="check-team-total" className="mr-auto self-center text-[13px] font-bold text-muted-foreground">
          {totalText ? totalText(total) : t('app.checkTeamTotal', total)}
        </span>
        <Button variant="secondary" onClick={onClose}>{t('app.cancel')}</Button>
        <Button onClick={start} disabled={total === 0}>
          <Play size={14} aria-hidden="true" />{t('app.checkTeamStart')}
        </Button>
      </>}>
      <p className="mb-3 text-[13px] text-muted-foreground">{descText || t('app.checkTeamDesc')}</p>

      {buckets.length === 0 ? (
        <StatusBlock tone="neutral" description={emptyText || t('app.checkTeamEmpty')} className="py-4 md:py-4" />
      ) : (
        <>
          <TeamRow checked={allSelected} onToggle={toggleAll} label={t('app.checkTeamAll')} count={allKeys.length} strong />
          <div className="max-h-[46vh] overflow-auto rounded-lg border [&>*+*]:border-t">
            {buckets.map(b => (
              <TeamRow key={b.key} checked={selected.includes(b.key)} onToggle={() => toggle(b.key)}
                label={b.label || t('app.checkTeamNone')} count={b.count} />
            ))}
          </div>
        </>
      )}
    </ModalShell>
  )
}

/**
 * Tek takım satırı — shadcn Field (yatay) + Checkbox + FieldLabel: satırın herhangi bir yerine
 * (etikete) tıklamak kutuyu değiştirir. Ad kutunun etiketidir, sayaç ayrı bir sayı.
 */
function TeamRow({ checked, onToggle, label, count, strong = false }) {
  const id = useId()
  return (
    <Field orientation="horizontal" role={undefined} className="gap-2.5 px-3 py-2 hover:bg-muted/50">
      <Checkbox id={id} checked={checked} onCheckedChange={() => onToggle()} />
      <FieldLabel htmlFor={id} className={cn('min-w-0 flex-1 cursor-pointer truncate text-sm', strong ? 'font-bold' : 'font-normal')}>
        {label}
      </FieldLabel>
      <span className="text-[13px] font-bold tabular-nums text-muted-foreground">{count}</span>
    </Field>
  )
}
