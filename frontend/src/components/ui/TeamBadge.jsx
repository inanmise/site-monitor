import { useState } from 'react'
import { Users } from 'lucide-react'
import { useTeamDirectory } from './TeamDirectory.jsx'
import TeamMembersModal from './TeamMembersModal.jsx'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/*
 * Görünüm: shadcn Badge `ghost` varyantı. Rozet çoğu yerde ZATEN tonlu bir çipin, tablo hücresinin
 * ya da başlığın içinde çiziliyor (.cc-team-chip, .ahc-chip-team, <strong>) — dolu/kenarlı bir hap
 * orada "çip içinde çip" olurdu. Yazı boyutu bağlamdan gelir; dar kapta ad kırpılır (shrink + truncate,
 * eski `.cc-team-chip .team-badge` kuralının yaptığı iş). Tıklanabilir olan üzerine gelince vurgulanır.
 * Test kancası: `data-slot="team-badge"`.
 */
const BADGE_BASE = 'min-w-0 max-w-full shrink px-1 py-0 align-middle text-[length:inherit]'
const BADGE_INTERACTIVE = 'cursor-pointer hover:bg-accent hover:text-primary focus-visible:text-primary outline-none'

/**
 * Tıklanabilir takım rozeti: adın geçtiği her yerde aynı görünüm, tıklanınca üye modalı.
 * id yoksa TeamDirectory ada göre çözer; çözülemezse düz metin (tıklanmaz) çizer — hiçbir
 * yüzeyde takım adı KAYBOLMAZ. {@code onOpen} verilirse modal yerine o çağrılır (TeamManager).
 */
/**
 * {@code as="span"}: rozet zaten bir <button> İÇİNDE çiziliyorsa (satır başlığı, kart düğmesi)
 * button-içinde-button geçersiz HTML'dir (React validateDOMNesting uyarısı, QA ISSUE-001
 * 2026-09-10). Bu modda <span role="button" tabIndex=0> çizilir; tıklama/Enter/Space aynı
 * modalı açar ve dış düğmeye SIZMAZ.
 */
export default function TeamBadge({ teamId, teamName, size = 12, className = '', onOpen, static: forceStatic = false, title, as = 'button' }) {
  const t = useT()
  const dir = useTeamDirectory()
  const [open, setOpen] = useState(false)
  const byName = teamName ? dir.byName.get(String(teamName).trim().toLowerCase()) : null
  const id = teamId != null ? Number(teamId) : (byName?.id ?? null)
  const entry = id != null ? (dir.byId.get(id) || byName) : byName
  const name = teamName || entry?.name || null
  if (!name) return null
  const clickable = !forceStatic && id != null
  // İkon boyutu prop'tan (eski API, px) — Badge'in [&>svg]:size-3 kuralını satır içi stil ezer;
  // size={0} (TeamStatsSection) ikonu hiç çizdirmez.
  const icon = size > 0 ? <Users aria-hidden="true" style={{ width: size, height: size }} /> : null
  const body = <>{icon}<span className="truncate">{name}</span></>
  if (!clickable) {
    return (
      <Badge variant="ghost" data-slot="team-badge" title={title}
        className={cn(BADGE_BASE, 'cursor-default', className)}>
        {body}
      </Badge>
    )
  }
  const openIt = (e) => {
    e?.stopPropagation?.()
    if (onOpen) onOpen(id, name)
    else setOpen(true)
  }
  const label = t('team.openMembers', name)
  const cls = cn(BADGE_BASE, BADGE_INTERACTIVE, className)
  return (
    <>
      {as === 'span' ? (
        <Badge asChild variant="ghost" data-slot="team-badge" className={cls}>
          <span role="button" tabIndex={0} title={title || label} aria-label={label}
            onClick={openIt}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openIt(e) } }}>
            {body}
          </span>
        </Badge>
      ) : (
        <Badge asChild variant="ghost" data-slot="team-badge" className={cls}>
          <button type="button" onClick={openIt} title={title || label} aria-label={label}>
            {body}
          </button>
        </Badge>
      )}
      {open && (
        <TeamMembersModal open={open} onClose={() => setOpen(false)}
          team={{ id, name, email: entry?.email, leader_id: entry?.leader_id, leader_display_name: entry?.leader_display_name }} />
      )}
    </>
  )
}
