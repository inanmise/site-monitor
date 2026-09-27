import { Globe, Search, ShieldCheck } from 'lucide-react'
import { useState, useMemo } from 'react'
import { useT } from '../i18n/index.jsx'
import ModalShell from './ui/ModalShell.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'

/**
 * Sertifika otoritesi (CA) çeşitliliği — hangi CA kaç alan adına sertifika vermiş. Çizim ui/ModalShell
 * (shadcn Dialog: Escape / odak tuzağı / odak iadesi) + arama için InputGroup + her CA için Card (sayı rozeti +
 * alan adı listesi). Uzun liste pencerenin gövdesinde kayar; telefonda pencere ekrana sığar.
 */
export default function CaDiversityModal({ certs, onClose }) {
  const t = useT()
  const [search, setSearch] = useState('')

  const grouped = useMemo(() => {
    const map = {}
    certs
      .filter(c => c.status !== 'error')
      .forEach(c => {
        const issuer = c.issuer_cn || c.issuer || 'Unknown'
        if (!map[issuer]) map[issuer] = []
        map[issuer].push(c.domain)
      })
    return Object.entries(map).sort((a, b) => b[1].length - a[1].length)
  }, [certs])

  const filtered = useMemo(() => {
    if (!search.trim()) return grouped
    const q = search.toLowerCase()
    return grouped
      .map(([issuer, domains]) => [
        issuer,
        domains.filter(d => d.toLowerCase().includes(q) || issuer.toLowerCase().includes(q))
      ])
      .filter(([, domains]) => domains.length > 0)
  }, [grouped, search])

  const totalDomains = grouped.reduce((s, [, d]) => s + d.length, 0)

  return (
    <ModalShell open onClose={onClose} title={t('stat.caDiv')} icon={ShieldCheck} size="md" scrollBody
      closeLabel={t('app.close')}>
      <Badge variant="secondary" data-slot="cadiv-summary"
        className="mb-3 bg-success/15 text-success dark:bg-success/20">
        {t('cadiv.summary', grouped.length, totalDomains)}
      </Badge>
      <InputGroup className="mb-4">
        <InputGroupInput placeholder={t('cadiv.search')} aria-label={t('cadiv.search')} value={search}
          onChange={e => setSearch(e.target.value)} />
        <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
      </InputGroup>

      <div className="flex flex-col gap-3">
        {filtered.map(([issuer, domains]) => (
          <Card key={issuer} data-slot="cadiv-ca" className="gap-0 overflow-hidden py-0 shadow-none">
            <div className="flex items-center gap-3 border-b bg-muted/50 px-4 py-2.5">
              <span className="flex h-9 min-w-9 shrink-0 items-center justify-center rounded-lg bg-primary px-1.5 text-[1.1em] font-extrabold text-primary-foreground">
                {domains.length}
              </span>
              <span className="min-w-0 text-[.9em] font-bold [overflow-wrap:anywhere]">{issuer}</span>
            </div>
            <ul className="py-1">
              {domains.map(domain => (
                <li key={domain} className="flex items-center gap-2.5 border-b px-4 py-[7px] last:border-b-0 hover:bg-muted/50">
                  <Globe size={13} aria-hidden="true" className="shrink-0 text-muted-foreground" />
                  <span className="min-w-0 font-mono text-[.88em] font-medium break-all">{domain}</span>
                </li>
              ))}
            </ul>
          </Card>
        ))}
      </div>

      {filtered.length === 0 && (
        <StatusBlock tone="neutral" icon={Search} title={t('cadiv.noResults')} />
      )}
    </ModalShell>
  )
}
