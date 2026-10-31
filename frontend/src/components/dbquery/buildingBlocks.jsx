// Veritabanı Sorgu İzleme (11. tür, 2026-10-31) — form / sayfa YAPI TAŞLARI.
//
// Bu dosya İSKELETTİR: her bileşenin PROPS SÖZLEŞMESİ burada sabittir; gövdeleri ayrı dosyalarda yazılır ve bu dosya
// onları yeniden dışa aktaran bir dizin olur (ad / props değişmez). Sayfa (DbQueryMonitorPage) yalnız buradan içe aktarır.
//
// RuleBuilder        { rules, logic, onChange(rules), onLogicChange(logic), columns?: [{name,type}], fieldErrors?: {[alan]: ileti}, disabled? }
//                    — alan anahtarları sunucuyla aynı: 'rules', 'rules[N].<alt>' (N 0 tabanlı)
// ScheduleEditor     { value: {schedule_mode, interval_seconds, cron_expression, windows, blackouts, skip_holidays, skip_dates},
//                      onChange(patch), fieldErrors?, disabled? } — sonraki çalışmaları kendisi önizler (schedule-preview)
// SqlEditor          { value, onChange(sql), dbType, fieldError?, violations?: [Violation], disabled?, onGuardChange?(result) }
//                    — canlı güvenlik denetimi (sql-check, 400 ms), satır numarası, ihlal listesi; data-field="sql_text"
// ConnectionPicker   { teamId, value: profileId, onChange(profileId, profile), disabled?, error?, canManage?, teams? }
//                    — takımın bağlantıları + "Bağlantıları yönet" (ConnectionsManager); data-field="profile_id"
// ConnectionsManager { open, onOpenChange(open), teamId?, teams: [{id,name}], canEdit, onChanged?() }
// TemplatePicker     { teamId, dbType?, onPick(template), disabled? }
// SaveTemplateDialog { open, onOpenChange(open), teamId, draft: {sql_text, purpose, rules, rule_logic, db_type}, onSaved?(template) }
// TemplatesManager   { open, onOpenChange(open), teamId?, teams, canEdit }
// RuleResultsList    { results: [RuleResult], compact? } — kural · beklenen · gelen · seviye (satır verisi değil)
// DbQueryAlertRows   { alertId, alertType } — alarm anındaki sorgu sonucu (paylaşımla okuyana yalnız satır sayısı)

import React from 'react'

const Stub = (name) => {
  const C = (props) => <div data-slot={`dbq-stub-${name}`} data-props={Object.keys(props || {}).join(',')} />
  C.displayName = name
  return C
}

export const RuleBuilder = Stub('RuleBuilder')
export const ScheduleEditor = Stub('ScheduleEditor')
export const SqlEditor = Stub('SqlEditor')
export const ConnectionPicker = Stub('ConnectionPicker')
export const ConnectionsManager = Stub('ConnectionsManager')
export const TemplatePicker = Stub('TemplatePicker')
export const SaveTemplateDialog = Stub('SaveTemplateDialog')
export const TemplatesManager = Stub('TemplatesManager')
export const RuleResultsList = Stub('RuleResultsList')
export const DbQueryAlertRows = Stub('DbQueryAlertRows')
