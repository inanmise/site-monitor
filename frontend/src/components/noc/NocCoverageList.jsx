import { BellOff, BellRing } from 'lucide-react'
import TeamBadge from '../ui/TeamBadge.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { itemKey } from './nocModel.js'
import { NocTypeTag, ReasonBadge } from './nocUi.jsx'

/**
 * Kapsam listesi (2026-09-27): geniş kapta shadcn Table, dar kapta (telefon / kenar çubuklu tablet) kartlar — karar
 * KAP genişliğinden (Uyarılar sayfasıyla aynı). Satır: tür ikonu, ad (izlemeye derin bağlantı) + hedef, takım rozeti,
 * düz sözcüklü durum rozeti, gruplar (geniş ekranda) ve TEK TIK "7/24'e bildir" (yalnız düzenleyebilene). Bildirimi
 * açık satırda "…" menüsünde kapatma (yanlış tıklamanın geri alınması). Toplu seçim yalnız açılabilir satırlarda.
 *
 * Saf sunum: durum ve eylemler sayfada (`h`). Test kancaları: `data-slot="noc-list"` (`data-view`),
 * satır `data-slot="noc-row"` + `data-key` + `data-status`.
 */
export default function NocCoverageList({ items, narrow, t, h, selected, pending }) {
  if (narrow) return <CardsView items={items} t={t} h={h} selected={selected} pending={pending} />
  return <TableView items={items} t={t} h={h} selected={selected} pending={pending} />
}

const selectable = (h, it) => h.canEdit(it) && !it.noc_notify

function NameCell({ it, t, h }) {
  return (
    <div className="flex min-w-0 flex-col items-start">
      <Button type="button" variant="link" data-action="noc-open"
        className="h-auto min-h-0 max-w-full justify-start p-0 text-left font-semibold whitespace-normal [overflow-wrap:anywhere] pointer-coarse:min-h-10"
        title={t('noc.openMonitor', it.name)} onClick={() => h.onOpen(it)}>
        {it.name || it.target}
      </Button>
      {it.target && it.target !== it.name && (
        <span className="max-w-full truncate text-xs text-muted-foreground" title={it.target}>{it.target}</span>
      )}
    </div>
  )
}

/** Satır eylemi: kapalıysa birincil "7/24'e bildir"; açıksa "…" menüsünde "Bildirimi kapat". */
function RowAction({ it, t, h, pending, block = false }) {
  if (!h.canEdit(it)) return null
  const busy = pending.has(itemKey(it))
  if (!it.noc_notify) {
    return (
      <Button type="button" size="sm" data-action="noc-enable" disabled={busy} aria-busy={busy || undefined}
        // Birincil yalnız kullanıcının düzeltebileceği neden (bildirim kapalı); duraklatılmış / tür kapalı / grup yok satırında sakin
        variant={!it.reason || it.reason === 'MONITOR_OFF' ? 'default' : 'outline'}
        className={block ? 'h-10 w-full' : 'h-10 sm:h-8 sm:pointer-coarse:h-10'}
        aria-label={t('a11y.rowAction', t('noc.enable'), it.name)} onClick={() => h.onEnable(it)}>
        {busy ? <Spinner size={14} inline decorative /> : <BellRing aria-hidden="true" />}{t('noc.enable')}
      </Button>
    )
  }
  return (
    <KebabMenu label={t('noc.rowMenu')} rowLabel={it.name}
      items={[{ label: t('noc.disable'), icon: <BellOff aria-hidden="true" />, danger: true, hidden: busy, onClick: () => h.onDisable(it) }]} />
  )
}

/** Bildirimi açık satırda hedef gruplar (yoksa "Varsayılan gruplar") — durum rozetinin altında küçük satır. */
function GroupNames({ it, t }) {
  if (!it.noc_notify) return null
  const names = Array.isArray(it.group_names) ? it.group_names : []
  return (
    <div data-slot="noc-row-groups" className="flex min-w-0 flex-wrap gap-1 text-xs text-muted-foreground">
      {names.length
        ? names.map((n) => <Badge key={n} variant="outline" className="max-w-full font-normal"><span className="truncate">{n}</span></Badge>)
        : <span>{t('noc.groupsDefault')}</span>}
    </div>
  )
}

function TableView({ items, t, h, selected, pending }) {
  const pageSelectable = items.filter((it) => selectable(h, it))
  const allOn = pageSelectable.length > 0 && pageSelectable.every((it) => selected.has(itemKey(it)))
  const someOn = pageSelectable.some((it) => selected.has(itemKey(it)))
  const anyEditable = items.some((it) => h.canEdit(it))
  return (
    <div data-slot="noc-list" data-view="table" className="overflow-hidden rounded-lg border">
      <Table>
        <TableHeader className="bg-muted/50">
          <TableRow>
            {anyEditable && (
              <TableHead className="w-10">
                <Checkbox checked={allOn ? true : someOn ? 'indeterminate' : false} disabled={!pageSelectable.length}
                  aria-label={t('noc.selectPage')} onCheckedChange={(v) => h.onSelectPage(pageSelectable, v === true)} />
              </TableHead>
            )}
            <TableHead>{t('noc.colType')}</TableHead>
            <TableHead>{t('noc.colMonitor')}</TableHead>
            <TableHead>{t('noc.colTeam')}</TableHead>
            <TableHead>{t('noc.colStatus')}</TableHead>
            {anyEditable && <TableHead className="text-right">{t('noc.colAction')}</TableHead>}
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((it) => {
            const key = itemKey(it)
            return (
              <TableRow key={key} data-slot="noc-row" data-key={key} data-status={it.covered ? 'covered' : (it.reason || 'MONITOR_OFF')}
                data-state={selected.has(key) ? 'selected' : undefined}>
                {anyEditable && (
                  <TableCell>
                    {selectable(h, it) && (
                      <Checkbox checked={selected.has(key)} aria-label={t('noc.selectRow', it.name)}
                        onCheckedChange={() => h.onToggle(key)} />
                    )}
                  </TableCell>
                )}
                <TableCell><NocTypeTag type={it.type} t={t} /></TableCell>
                <TableCell className="min-w-[12rem] whitespace-normal"><NameCell it={it} t={t} h={h} /></TableCell>
                <TableCell className="max-w-[12rem]">
                  {it.team_name || it.team_id != null
                    ? <TeamBadge teamId={it.team_id} teamName={it.team_name} />
                    : <span className="text-xs text-muted-foreground">{t('app.noTeam')}</span>}
                </TableCell>
                <TableCell className="w-[14rem] min-w-[12rem] whitespace-normal">
                  <div className="flex min-w-0 flex-col items-start gap-1"><ReasonBadge item={it} t={t} /><GroupNames it={it} t={t} /></div>
                </TableCell>
                {anyEditable && (
                  <TableCell className="text-right"><RowAction it={it} t={t} h={h} pending={pending} /></TableCell>
                )}
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}

function CardsView({ items, t, h, selected, pending }) {
  return (
    // Sütun sayısı KAP genişliğinden (kenar çubuğu açık tablette içerik ~470 px → tek sütun; iki dar sütun adları kırıyordu)
    <ul data-slot="noc-list" data-view="cards" className="grid list-none grid-cols-[repeat(auto-fill,minmax(min(300px,100%),1fr))] gap-3">
      {items.map((it) => {
        const key = itemKey(it)
        const canPick = selectable(h, it)
        return (
          <li key={key} className="min-w-0">
            <Card data-slot="noc-row" data-key={key} data-status={it.covered ? 'covered' : (it.reason || 'MONITOR_OFF')}
              data-state={selected.has(key) ? 'selected' : undefined}
              className="h-full gap-2 py-3 data-[state=selected]:border-primary">
              <CardContent className="flex min-w-0 flex-col gap-2.5 px-3">
                <div className="flex min-w-0 items-start gap-2">
                  {canPick && (
                    // 40 px dokunma hedefi: kutu küçük, çevresindeki etiket alanı tıklanır
                    <label className="-my-2 -ml-2 inline-flex size-10 shrink-0 cursor-pointer items-center justify-center">
                      <Checkbox checked={selected.has(key)} aria-label={t('noc.selectRow', it.name)}
                        onCheckedChange={() => h.onToggle(key)} />
                    </label>
                  )}
                  <div className="flex min-w-0 flex-1 flex-col gap-1">
                    <NocTypeTag type={it.type} t={t} />
                    <NameCell it={it} t={t} h={h} />
                  </div>
                  {it.noc_notify && <RowAction it={it} t={t} h={h} pending={pending} />}
                </div>
                <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
                  <ReasonBadge item={it} t={t} />
                  {(it.team_name || it.team_id != null)
                    ? <TeamBadge teamId={it.team_id} teamName={it.team_name} />
                    : <span className="text-xs text-muted-foreground">{t('app.noTeam')}</span>}
                </div>
                <GroupNames it={it} t={t} />
                {!it.noc_notify && <RowAction it={it} t={t} h={h} pending={pending} block />}
              </CardContent>
            </Card>
          </li>
        )
      })}
    </ul>
  )
}
