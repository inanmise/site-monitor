package com.sitemonitor.service;

/**
 * Bir olay kaydı (Olay ve Hata Geçmişi) GÜNCELLENDİ (2026-11-20). {@link IncidentService} işlemin içinde yayımlar; dinleyiciler
 * (teknik hata raporu konularının canlı bağı) işlem İŞLENDİKTEN sonra çalışır ({@code @TransactionalEventListener}
 * AFTER_COMMIT) — geri alınan güncelleme hiçbir şey tetiklemez.
 */
public record IncidentRecordChangedEvent(Long incidentId) { }
