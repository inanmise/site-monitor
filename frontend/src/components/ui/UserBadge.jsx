import { useUserDirectory } from './UserDirectory.jsx'
import { Avatar, AvatarImage, AvatarFallback } from '@/components/shadcn/avatar'
import { cn } from '@/lib/utils'

// Deterministik avatar tonu + baş harfler (resim yoksa fallback). Diğer bileşenler de kullanabilir.
export function avatarBg(s) {
  let h = 0
  for (let i = 0; i < (s || '').length; i++) h = (h * 31 + s.charCodeAt(i)) % 360
  return `hsl(${h} 45% 52%)`
}
export function initialsOf(name, username) {
  const base = (name || username || '?').trim()
  const p = base.split(/\s+/)
  return (((p[0]?.[0] || '') + (p[1]?.[0] || '')).toUpperCase()) || base[0]?.toUpperCase() || '?'
}

/**
 * Proje geneli kullanıcı rozeti: avatar (AD foto + baş-harf fallback) + ad-soyad (+ kimlik alt satırı).
 * `username` ve/veya `email` verilir; `userId`/`displayName` verilmezse UserDirectory'den (username→
 * sonra email) çözülür. Böylece username YA DA e-posta görünen her yer ad-soyad+resme dönüşür.
 *
 * Avatar shadcn Avatar (Radix): foto yüklenene kadar ve yüklenemezse (404/403) baş-harf yedeği
 * görünür — eskiden bunu elle tutulan bir onError durumu yapıyordu.
 *
 * props:
 *  - username | email (en az biri) — dizinden çözüm anahtarı + fallback metin
 *  - userId, displayName (opsiyonel) — verilirse dizine bakılmaz
 *  - inline: tek satır (yalnız ad); showUsername: ad altında kimlik (default true, inline'da yok)
 *  - size: 'sm'|'md'; count: sağda sayı; systemLabel: 'system'/boş actor metni
 */
export default function UserBadge({
  username, email, userId, displayName, count,
  size = 'md', showUsername = true, inline = false, systemLabel, nameOnly = false,
}) {
  const dir = useUserDirectory()

  // Sistem/boş actor → düz metin (avatar yok).
  if (username === 'system') return <span className="text-muted-foreground">{systemLabel || 'Sistem'}</span>
  if (!username && !email && !displayName) return <span className="text-muted-foreground">{systemLabel || '—'}</span>

  const needLookup = (userId == null || displayName == null)
  const resolved = needLookup ? (dir.lookup(username) || dir.lookupByEmail(email)) : null
  const id = userId != null ? userId : resolved?.id
  const ident = username || email
  const rawName = displayName || resolved?.display_name || ident
  // nameOnly: sondaki parantezli eki ("(… Bölümü)" gibi departman/başlık) çıkar → yalnız ad-soyad.
  const name = nameOnly && typeof rawName === 'string' ? rawName.replace(/\s*\([^)]*\)\s*$/, '') : rawName
  const sm = size === 'sm'

  return (
    <span data-slot="user-badge" className="inline-flex min-w-0 max-w-full items-center gap-[7px]">
      <Avatar className={sm ? 'size-5' : 'size-[26px]'}>
        {id != null && <AvatarImage src={`/api/users/${id}/photo`} alt="" className="object-cover" />}
        <AvatarFallback className={cn('font-bold text-white', sm ? 'text-[9px]' : 'text-[11px]')}
          style={{ background: avatarBg(ident || name) }}>
          {initialsOf(name, ident)}
        </AvatarFallback>
      </Avatar>
      {inline ? (
        <span className="min-w-0 overflow-hidden text-ellipsis font-semibold">{name}</span>
      ) : (
        <span className="min-w-0 leading-[1.2]">
          <span className="font-semibold">{name}</span>
          {showUsername && ident && name !== ident && (
            <span className="block font-mono text-[.78em] opacity-60">{ident}</span>
          )}
        </span>
      )}
      {count != null && <span className="ml-auto shrink-0 font-bold">{count}</span>}
    </span>
  )
}
