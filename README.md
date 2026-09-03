# Viavis Live

Viavis için özel Android müşteri davranış izleme uygulaması.

## Hedef
- Viavis REST API üzerinden dashboard
- Canlı hareket akışı
- Terk edilmiş sepetler
- Ürün performansı
- Ziyaretçi timeline
- Firebase Cloud Messaging ile checkout/sipariş push bildirimleri

## Android
- Package: `com.viavis.live`
- Min SDK: 26 (Android 8.0)
- Target/Compile SDK: 35
- Kotlin + Jetpack Compose

## Build
GitHub Actions, `main` dalına yapılan pushlarda Debug APK üretir.

Firebase yapılandırması ayrı aşamada eklenecektir.
