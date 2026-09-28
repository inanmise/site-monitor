import { Fragment, useId } from 'react'
import { CircleCheck, CircleHelp, CircleX, Info, Lock, ShieldCheck, TriangleAlert, Link2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemMedia, ItemSeparator, ItemTitle } from '@/components/shadcn/item'
import { cn } from '@/lib/utils'
import { ST } from './sslModel.js'

/**
 * SSL Kontrol sekmesi — GRUPLU kontrol listesi (shadcn Item ailesi). Her satır: durum simgesi (+ ekran okuyucu için
 * durum metni) · ne denetlendi · düz dille sonuç · ölçülen değer rozeti. Durum YALNIZ simge + rozetle taşınır — sol renk
 * şeridi YOK (kullanıcı kuralı). Test kancası: satır `data-slot="ssl-check"` + `data-check` + `data-status`.
 */

const STATUS_ICON = { ok: CircleCheck, warn: TriangleAlert, fail: CircleX, unknown: CircleHelp, info: Info }
const STATUS_INK = {
  ok: 'text-success',
  warn: 'text-amber-600 dark:text-amber-400',
  fail: 'text-destructive',
  unknown: 'text-muted-foreground',
  info: 'text-primary',
}

/** Durum simgesi — renk + şekil (renk körlüğü: onay / ünlem / çarpı / soru ayrı biçimler). */
export function SslStatusIcon({ status, className }) {
  const Icon = STATUS_ICON[status] ?? CircleHelp
  return <Icon aria-hidden="true" className={cn('size-5 shrink-0', STATUS_INK[status] ?? STATUS_INK.unknown, className)} />
}

const GROUP_ICON = { cert: ShieldCheck, trust: Link2, conn: Lock }

/** Değer rozeti metni: kalan gün ve zincir uzunluğu çevrilir, diğerleri ölçülen ham değer (TLS 1.3, şifre adı…). */
function valueText(r, t) {
  if (r.key === 'expiry') return r.value < 0 ? t('sslv.expiredBadge') : t('sslv.daysValue', r.value)
  if (r.key === 'chain') return t('sslv.certCount', r.value)
  return String(r.value)
}

export default function SslCheckGroups({ groups }) {
  const t = useT()
  const baseId = useId()
  return (
    <section data-slot="ssl-checks" aria-label={t('sslv.checksTitle')} className="flex min-w-0 flex-col gap-4">
      {groups.map((g) => {
        const Icon = GROUP_ICON[g.key] ?? ShieldCheck
        const passed = g.rows.filter((r) => r.status === ST.OK).length
        const headId = `${baseId}-${g.key}`
        return (
          <div key={g.key} data-slot="ssl-check-group" data-group={g.key} className="flex min-w-0 flex-col gap-2">
            <h4 id={headId} className="flex items-center gap-1.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
              <Icon aria-hidden="true" className="size-3.5" />
              {t(`sslv.group.${g.key}`)}
              <span className="ml-auto font-medium tracking-normal normal-case tabular-nums">{t('sslv.groupScore', passed, g.rows.length)}</span>
            </h4>
            <ItemGroup aria-labelledby={headId} className="rounded-lg border bg-card">
              {g.rows.map((r, i) => (
                <Fragment key={r.key}>
                  {i > 0 && <ItemSeparator />}
                  <Item role="listitem" size="sm" data-slot="ssl-check" data-check={r.key} data-status={r.status}
                    className="flex-nowrap items-start gap-3 px-3 py-2.5 max-sm:flex-wrap">
                    <ItemMedia className="mt-0.5 self-start">
                      <SslStatusIcon status={r.status} />
                    </ItemMedia>
                    <ItemContent className="min-w-0 gap-0.5">
                      <ItemTitle className="w-full flex-wrap gap-x-2 gap-y-0.5">
                        {t(`sslv.check.${r.key}`)}
                        <span className="sr-only">: {t(`sslv.status.${r.status}`)}</span>
                        {r.advice && r.status !== ST.OK && (
                          <Badge variant="outline" className="font-normal text-muted-foreground">{t('sslv.adviceTag')}</Badge>
                        )}
                      </ItemTitle>
                      <ItemDescription className="line-clamp-none text-[13px] text-pretty [overflow-wrap:anywhere]">
                        {t(r.textKey, ...r.args)}
                      </ItemDescription>
                    </ItemContent>
                    {r.value != null && r.value !== '' && (
                      <ItemActions className="max-w-[45%] shrink-0 self-start max-sm:max-w-full max-sm:basis-full max-sm:pl-8">
                        <Badge variant="secondary" data-slot="ssl-check-value"
                          className={cn('h-auto max-w-full py-0.5 text-left whitespace-normal [overflow-wrap:anywhere]', r.mono && 'font-mono font-normal')}>
                          {valueText(r, t)}
                        </Badge>
                      </ItemActions>
                    )}
                  </Item>
                </Fragment>
              ))}
            </ItemGroup>
          </div>
        )
      })}
    </section>
  )
}
