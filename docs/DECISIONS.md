# Roadmaxxing — Karar Günlüğü

Yeni bir karar alındığında en üste eklenir. Belgeler arasında çelişki olursa bu dosya geçerlidir.

| # | Tarih | Karar | Neden | Elenen seçenekler |
| --- | --- | --- | --- | --- |
| 17 | 2026-11-16 | Raporlar Türkçe; kod, yorumlar ve commit mesajları İngilizce | Kullanıcı tercihi | — |
| 16 | 2026-11-16 | iOS workflow kullanıcının çalışan BrainScroll workflow'undan uyarlandı; build job'ında shared testleri iOS simülatöründe çalışır | iosMain yerelde derlenemiyor; testler zaten açılan macOS job'ında ekstra commit gerektirmeden çalışır | Sıfırdan workflow yazmak; CI'da iOS testi olmaması |
| 15 | 2026-11-16 | Android testleri görünür emülatörde (Pixel, Pixel Tablet, Pixel Fold); emülatör kullanıcı söylemeden kapatılmaz; GPX ile sahte sürüş testi | Kullanıcı izlemek ve kendisi denemek istiyor; araba sürmeden kayıt testi gerekiyor | Headless emülatör |
| 14 | 2026-11-16 | Commit ve push'u yalnızca kullanıcı yapar; Claude yalnızca anlamlı işlerden sonra en fazla 10 kelimelik İngilizce mesaj önerir | Kullanıcı tercihi | Her küçük işten sonra commit |
| 13 | 2026-11-16 | Alan adı ve sosyal medya hesapları şimdilik alınmayacak | Kullanıcı kararı | Hemen satın almak |
| 12 | 2026-11-16 | Mağaza adı: "Roadmaxxing: Drive Map Tracker" (30 karakter) | Kategorinin en çok aranan üç kelimesi (drive, map, tracker) tek başlıkta; 30 karakter sınırı | "…Drive Map & Tracker" (31 karakter, sınırı aşıyor) |
| 11 | 2026-11-16 | Marka: Roadmaxxing | Hedef kitlenin internet dilini konuşuyor, ürünle anlamı örtüşüyor, tescil ve alan adı açısından temiz görünüyor | Paved, Charted, Geared, Blacktop ve diğerleri (bkz. BRAND.md) |
| 10 | 2026-11-16 | Türkçe isim kullanılmayacak | Kullanıcı tercihi | Viraj, Turla, Volta |
| 9 | 2026-11-16 | Türkiye fiyatları: haftalık ₺59,99, aylık ₺119,99, yıllık ₺699,99 (7 gün deneme) | Kullanıcı önceki fiyatları (₺119,99 / ₺229,99 / ₺1.499,99) yüksek buldu, sonra ₺49,99 / ₺99,99 / ₺599,99 önerisini "bir tık artır" dedi | İlk iki fiyat önerisi |
| 8 | 2026-11-16 | Sunucusuz mimari: eşleştirme ve yüzde cihazda; OpenFreeMap, Cloudflare R2, Firestore Spark | Kullanıcının sunucusu yok, sunucu maliyeti ve kurulumu istemiyor | Ktor backend + Cloud Run + Valhalla + PostGIS |
| 7 | 2026-11-16 | Ana renk Volt yeşili `#C8FF3D`; yalnızca koyu tema | Kategoride kullanılmıyor, "yeşil = keşfedildi" fikri, siyahta en yüksek kontrast | Sinyal turuncusu `#FFA126`, Nane `#3DF5B4` |
| 6 | 2026-11-16 | Giriş: Kullanıcı önce sürer, hesap ilk paylaşımda veya 3. sürüşten sonra istenir | Bağlanmadan duvara çarpmasın | İlk açılışta zorunlu giriş |
| 5 | 2026-11-16 | Lansmanda abonelik var; paywall açık ama cömert | Kullanıcı kararı; rakiplerin sert paywall'ları kötü yorum alıyor | Önce tamamen ücretsiz |
| 4 | 2026-11-16 | Servisler: Firebase Auth, RevenueCat, PostHog (resmî KMP SDK'ları; Firebase için GitLive) | Kullanıcı tercihi | — |
| 3 | 2026-11-16 | UI: Compose Multiplatform (UI dahil tek Kotlin kod tabanı) | Kullanıcının Mac'i yok, iOS'u GitHub Actions ile derliyor, KMP tecrübesi var ve memnun; MapLibre Compose ile harita da ortak yazılabiliyor | SwiftUI + ortak iş mantığı; React Native / Expo |
| 2 | 2026-11-16 | MVP dar tutulacak: widget, CarPlay, Android Auto, AI, liderlik tablosu sonraki sürümlerde | Önce ana döngüyü yayınlamak | Hepsini MVP'ye almak |
| 1 | 2026-11-16 | Ürün: sürüşleri harita tamamlama oyununa çeviren uygulama ("sürüş için Wandrer"); referans UI Wheelz | Harita tamamlama başka alanlarda kanıtlı, sürüşte niş boş, rakiplerin zaafı sıkıcılaşma | Klasik hız/istatistik takipçisi |
