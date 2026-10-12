import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from './SearchableSelect.jsx'

/**
 * İzlemenin alarmlarının gideceği Bildirim Grubu seçicisi.
 *
 * <p>Boş seçim ("Takım varsayılanı") kasten VARSAYILANDIR ve bir eksiklik değildir: zincirin
 * kalanı (takımın varsayılan grubu → takım adresi) zaten çalışıyor. Bu yüzden alan hiçbir
 * formda zorunlu işaretlenmez.
 *
 * <p><b>Takım değişince liste yeniden yüklenir</b> ve seçim TEMİZLENİR — eski takımın grubu
 * yeni takımda geçersizdir; ekranda bırakılsaydı kullanıcı hâlâ geçerli sanırdı. Backend de
 * aynı kuralı uyguluyor (başka takımın grubu reddedilir), yani ekran sunucuya YALAN söylemez.
 *
 * <p><b>Silinmiş grup:</b> kayıtlı seçim pasif bir gruba işaret ediyorsa liste onu "(silinmiş)"
 * rozetiyle YİNE gösterir. Aksi halde seçici boş görünür ve kullanıcı "takım varsayılanı"
 * sanırdı — oysa alarm hâlâ o silinmiş gruba çözülmeye çalışılıyor.
 */
export default function NotificationGroupSelect({ teamId, value, onChange, disabled = false }) {
  const t = useT()
  const [groups, setGroups] = useState([])
  // Takımın grupları OKUNAMADI (2026-10-12): takım erişim paylaşımıyla düzenlenen izlemede paylaşan takımın bildirim
  // grupları alıcıya açılmaz (yönetim verisi). Bu durumda seçici "Takım varsayılanı" diye YALAN söylemez; kayıtlı
  // seçimi nötr bir etiketle gösterir ve kilitlenir (değer aynen korunur, kaydetme onu değiştirmez).
  const [hidden, setHidden] = useState(false)

  useEffect(() => {
    let alive = true
    setHidden(false)
    if (teamId === '' || teamId == null) { setGroups([]); return () => { alive = false } }
    // includeInactive: kayıtlı seçim silinmiş bir gruba işaret ediyorsa rozetle gösterebilmek için.
    api.notificationGroups.list(teamId, true).then(r => {
      if (!alive) return
      if (r?.success) setGroups(r.data?.groups ?? [])
      else { setGroups([]); setHidden(true) }
    }).catch(() => { if (alive) { setGroups([]); setHidden(true) } })
    return () => { alive = false }
  }, [teamId])

  const noTeam = teamId === '' || teamId == null
  // Takım seçilmemişken BOŞ seçenek de sunulmaz: SearchableSelect boş değeri o seçenekle
  // eşleştirip "Takım varsayılanı" yazardı — oysa henüz hangi takımın varsayılanı olduğu
  // belli değil. Seçenek listesi boş kalınca yer tutucu ("Önce takım seçin") görünür.
  const hasValue = value != null && value !== ''
  const options = noTeam ? [] : hidden ? [
    hasValue ? { value: String(value), label: t('ng.selectorHidden') } : { value: '', label: t('ng.selectorDefault') },
  ] : [
    { value: '', label: t('ng.selectorDefault') },
    ...groups
      // Pasif grup YALNIZ hâlâ seçiliyse listelenir — yeni seçim olarak sunulmaz.
      .filter(g => g.active || String(g.id) === String(value))
      .map(g => ({
        value: String(g.id),
        label: g.active
          ? (g.is_default ? `${g.name} ★` : g.name)
          : `${g.name} ${t('ng.selectorDeleted')}`,
      })),
  ]

  return (
    <label>
      <span>{t('ng.selectorLabel')}</span>
      <SearchableSelect
        value={value == null ? '' : String(value)}
        onChange={onChange}
        options={options}
        disabled={disabled || noTeam || hidden}
        searchThreshold={4}
        placeholder={noTeam ? t('ng.selectorNoTeam') : t('ng.selectorDefault')}
        ariaLabel={t('ng.selectorLabel')}
      />
      <span className="field-hint">{t('ng.selectorHint')}</span>
    </label>
  )
}
