# Nöbet Rutini

Bu sayfa nöbetçinin her gün yaptığı kontrolleri listeler.

## Gün başlangıcı
- Cluster Monitoring
  - [Grafana küme genel bakış](https://grafana.example.internal/d/cluster-overview)
  - [Grafana düğüm kaynakları](https://grafana.example.internal/d/node-resources)
  - [Prometheus uyarıları](https://prometheus.example.internal/alerts)
  - [Alertmanager](https://alertmanager.example.internal/#/alerts)
  - https://console.example.internal/k8s/cluster/projects
  - Kapasite panosu: https://capacity.example.internal/dashboard
- OCP Watcher Errors Metrics
  - [OCP Watcher](https://grafana.example.internal/d/ocp-watcher)
- Servislerdeki teknik/business ve maplenmemiş hata artışlarının incelenmesi ve anomali tespiti
  - [Teknik hata panosu](https://kibana.example.internal/app/dashboards#/view/tech-errors)
  - [Business hata panosu](https://kibana.example.internal/app/dashboards#/view/business-errors)
  - [Maplenmemiş hatalar](https://kibana.example.internal/app/dashboards#/view/unmapped)
  - [Anomali raporu](https://anomaly.example.internal/report)
- Açık olay ve problem kayıtlarının kontrol edilmesi ve kapatılması
  - [Olay kayıtları](https://itsm.example.internal/incidents?state=open)
  - [Problem kayıtları](https://itsm.example.internal/problems?state=open)
  - İlgili kayıtların mail takibi
- Batch işlerinin gece çalışma sonuçlarının kontrolü
- Yedekleme raporunun kontrol edilmesi
- Long running'e düşen servislerin elastic üzerinden kontrolleri
  - [Long running servisler](https://kibana.example.internal/app/discover#/long-running)
- SiteMonitor üzerinden alertlerin ve monitorlerin kontrol edilmesi
  - [SiteMonitor alarmlar](https://sitemonitor.example.internal/?tab=alerthistory)
  - [SiteMonitor izlemeler](https://sitemonitor.example.internal/?tab=monitoring)

**Gün içi**
- Gün içinde gelen alarmların takibi ve ilgili ekiplere iletilmesi
- Değişiklik takviminin kontrol edilmesi

## Düzenli Gelen Mail İçerik kontrolleri
- Günlük batch raporu maili
- Kapasite raporu maili
  - [Rapor arşivi](mailto:raporlar@example.internal)
- Sertifika süresi uyarı maili
- Lisans kullanım raporu maili
- Yedekleme sonucu maili
- Güvenlik tarama sonucu maili
- Erişim talepleri özeti maili

## Haftalık periyotta
- Kapasite trendlerinin gözden geçirilmesi (Her Cuma)
- Sertifika envanterinin gözden geçirilmesi
- Açık aksiyonların takibi — Pazartesi

## Aylık Geçiş
- Nöbet devir raporunun hazırlanması
  - [Devir şablonu](https://wiki.example.internal/oncall/handover-template)
