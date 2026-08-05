package com.sitemonitor.repository;

/**
 * Görsel meta projeksiyonu — byte[] data ALANINI İÇERMEZ. Liste/detay yollarında
 * (imagesMeta) görsel baytlarının heap'e materialize edilmesini önler; kapalı
 * (closed) projection olduğu için Hibernate yalnız bu kolonları SELECT eder.
 */
public interface WeeklyReportImageMetaView {
    Long getId();
    Long getTeamId();
    String getCaption();
    String getContentType();
    Long getSizeBytes();
}
