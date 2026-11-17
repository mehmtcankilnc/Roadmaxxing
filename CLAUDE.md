# Roadmaxxing

Sürdüğün her yol haritanda yeşile döner. Roadmaxxing, arabayla gezdikçe şehrini, ülkeni ve dünyayı bir oyundaki gibi (Euro Truck Simulator 2'deki harita tamamlama gibi) tamamladığın, iOS ve Android'de çalışan bir sürüş takip uygulamasıdır. Ana çıktı: "Adana'nın %12,4'ünü keşfettin" gibi bir yüzde ve bunu gösteren paylaşılabilir kart.

Bu dosya projenin giriş noktasıdır. Ayrıntılar `docs/` altında:

| Dosya | İçerik |
| --- | --- |
| `docs/PRD.md` | Ürün gereksinimleri: ne, kimin için, MVP kapsamı, akış, monetizasyon, metrikler, riskler |
| `docs/ARCHITECTURE.md` | Teknik mimari: KMP + Compose, sunucusuz yapı, SDK'lar, veri modeli, sürüş işleme akışı |
| `docs/DESIGN.md` | Tasarım dili: renk token'ları, tipografi, bileşenler, ekranlar, UI referans analizi |
| `docs/BRAND.md` | Marka: ad, mağaza başlığı, alan adı ve kullanıcı adı durumu, elenen adlar |
| `docs/RESEARCH.md` | Pazar doğrulaması, rakipler, kullanıcı şikâyetleri, kaynaklar |
| `docs/DECISIONS.md` | Karar günlüğü (neden böyle yapıyoruz) |
| `docs/ui-references/wheelz/` | Referans uygulama Wheelz'in ekran görüntüleri |
| `docs/ci/` | iOS TestFlight workflow referansı (`ios.yml`), secret listesi ve CI kuralları |

Belgeler arasında çelişki görürsen `docs/DECISIONS.md` en günceldir; çelişkiyi kullanıcıya bildir.

## Değişmez kararlar (özet)

- **Platform:** iOS + Android, tek Kotlin kod tabanı: Kotlin Multiplatform + Compose Multiplatform (UI dahil ortak). React Native, Flutter veya ayrı SwiftUI yok.
- **Geliştirme ortamı:** Geliştiricinin Mac'i yok. iOS derlemesi ve TestFlight yüklemesi yalnızca GitHub Actions macOS runner'ında yapılır. iOS'a özel kod Kotlin/Native interop ile (`expect/actual`) yazılır; Swift gerektiren bir çözüm önermeden önce sor.
- **Sunucu yok, sıfır sabit maliyet:** Yol eşleştirme ve yüzde hesabı cihazda yapılır. Harita karoları OpenFreeMap, yol verisi paketleri Cloudflare R2 (GitHub Actions ile OSM'den üretilir), kullanıcı verisi Firestore Spark (ücretsiz) planı. Kendi sunucumuz, Cloud Functions veya ücretli servis yok.
- **Servisler:** Firebase Auth (GitLive `firebase-kotlin-sdk`), RevenueCat (`purchases-kmp`), PostHog (`posthog-kmp`), MapLibre Compose. Pre-release kütüphaneler tam sürüme sabitlenir.
- **Giriş:** Kullanıcı önce sürer; hesap ilk paylaşımda veya 3. sürüşten sonra istenir. Misafir verisi hesaba kayıpsız taşınır.
- **Monetizasyon:** Lansmanda abonelik var, paywall cömert. Sürülen yollar haritadan asla silinmez. Karanlık desen (sahte çark, kapatılamayan ekran, gece indirim bildirimi) yok.
- **Tasarım:** Sadece koyu tema. Ana renk Volt yeşili `#C8FF3D` = keşfedilen yol. Yeşil üstündeki metin her zaman koyu (`#1A2600`).
- **Yerelleştirme:** İlk günden Türkçe + İngilizce. Kodda sabit metin yok; tüm metinler `composeResources/values-xx/strings.xml` dosyalarından.
- **Güvenlik ve hız:** Hızı teşvik eden metin, rozet veya özellik yok. Uygulama "güvenli sür" mesajını korur.

## Kod kuralları

- Önerilen modül yapısı: `composeApp` (ortak UI + navigasyon), `shared` (domain, data, eşleştirme motoru), `iosApp`, `androidApp`. İlk yapı kurulurken kullanıcıyla teyit et.
- Mimari: MVI (ViewModel + StateFlow), Koin ile DI, SQLDelight yerel veri, Ktor client + kotlinx.serialization.
- Paket adı önerisi: `app.roadmaxxing` (marka alan adı roadmaxxing.app'ten). Henüz kesinleşmedi; kullanmadan önce sor.
- Yol eşleştirme ve yüzde hesabı saf Kotlin, platformdan bağımsız ve birim testli olmalı. Bu, ürünün kalbidir.
- Pil: Sürüş yokken yüksek doğruluklu GPS açık kalmaz; hareket sensörü ile tetiklenir.
- Analitik olaylarına koordinat yazılmaz; yalnızca bölge kimliği.

## Gizli değerler

Firebase yapılandırma dosyaları, RevenueCat ve PostHog anahtarları, imzalama sertifikaları ve App Store Connect anahtarları depoya girmez. Yerelde `local.properties` / `.xcconfig` (gitignore'da), CI'da GitHub Actions secrets kullanılır. Gizli değerleri hiçbir çıktıya, loga veya dokümana yazma.

## Çalışma şekli

Kullanıcı bu projede şu çalışma şeklini istiyor:

1. Ana oturum takım lideridir, kodu kendisi yazmaz. Önce projeyi okur, sorunları kanıtıyla (dosya:satır veya komut çıktısı) listeler.
2. İşi, dosyaları çakışmayan en fazla 4 pakete böler; her paketin kabul ölçütünü yazar. Planı kullanıcıya gösterir ve onay bekler.
3. Onaydan sonra her paket için modeli Sonnet 5.5 (`claude-sonnet-5-5`) olan ayrı bir alt oturum açar (ana oturum, yani lider, Opus 5.5 ile çalışır; `start_session`; araç listede yoksa ToolSearch ile `select:mcp__ccd_session__start_session`). Agent aracı kullanılmaz. Her brief'te paketin işi, dokunabileceği dosyalar, kabul ölçütü ve "bitince typecheck ve test çalıştır" yer alır.
4. Alt oturumları ana oturum yönetir: raporları okur, takılanı yönlendirir, hatalı işi aynı oturuma geri gönderir. Düzeltmeyi kendisi yapmaz.
5. Hepsi bitince işleri birleştirir. Çalışanların raporuna güvenmez: değişiklikleri kendisi okur, testleri kendisi çalıştırır.

**Commit:**
- Commit ve push'u her zaman kullanıcı yapar. Ana oturum ve alt oturumlar hiçbir koşulda commit, push, merge veya rebase yapmaz.
- Commit önerisi yalnızca anlamlı bir iş ya da özellik tamamlandığında verilir (ör. bir paket grubu birleşip testleri geçtiğinde); her küçük işten sonra verilmez.
- Commit mesajı İngilizce, en fazla 10 kelime. Önerinin yanında değişen dosyalar kısaca listelenir.

**iOS ve GitHub Actions:**
- iOS derlemesi ve TestFlight yüklemesi yalnızca GitHub Actions'ta yapılır (kullanıcının Mac'i yok). Referans: `docs/ci/ios.yml` ve `docs/ci/README.md`.
- Workflow dosyası proje iskeleti oluşunca bir kez, referanstan yazılır. Sonra yalnızca kullanıcının onayıyla, gerekçesi söylenerek ve değişiklikler biriktirilip tek seferde değiştirilir (commit şişmesi olmasın).
- `iosMain` kodu kullanıcının bilgisayarında derlenemez (Kotlin/Native iOS hedefleri Mac ister). Bu kodu dikkatli yaz, iOS değişikliklerini biriktir; doğrulama için CI'ı yalnızca gerektiğinde ve kullanıcıya haber vererek kullan.
- CI'daki `build` job'ı shared testlerini iOS simülatöründe çalıştırır ve imzasız simülatör derlemesi yapar. TestFlight yalnızca `v*` etiketiyle veya elle tetiklenince çalışır.
- Kullanıcı iOS'u özellik bitiminde kendi fiziksel cihazında test eder.

**Android test:**
- Testler Android emülatöründe yapılır. Emülatör pencereli (görünür) açılır, headless çalıştırılmaz; kullanıcı ara ara izler.
- İş bitince emülatör kullanıcı söylemeden kapatılmaz; kullanıcı uygulamayı kendisi de kurcalar.
- Sabit emülatör seti: telefon (Pixel), tablet (Pixel Tablet), katlanabilir (Pixel Fold). Arayüz değişiklikleri üçünde de kontrol edilir; aşama sonunda ekran görüntüleri özetle birlikte gösterilir.
- Sürüş kaydı ve eşleştirme, depodaki GPX rotaları (Adana) emülatörde oynatılarak test edilir (`test-data/gpx/`). Faz 0 kabul ölçütü bu rotalarla ölçülür.
- `test-data/gpx/` başta boş; ilk planda bu rotaları üreten bir iş yer alır. Rotalar OSM yol ağına oturur, 1 sn aralıklı zaman damgası ve gerçekçi hız taşır. Senaryolar: ana yollar, mahalle yolları, paralel iki yol, trafikte uzun duraklama, şehirler arası yol parçası ve sürüş sayılmaması gereken bir iz (yürüyüş ya da tren hızında). Her dosyanın senaryosu `test-data/gpx/README.md`'de yazılır.

**Tasarım uyumluluğu:**
- Arayüz iPhone (iPhone Duo dahil), iPad, Android telefon, Android tablet ve katlanabilirlerde düzgün çalışır. Ekran sınıfına göre (kompakt, orta, geniş) uyarlanan düzenler kullanılır; cihaza özel sabit boyut kullanılmaz.

**Metin ve kütüphane disiplini:**
- Arayüze eklenen her metin aynı anda hem Türkçe hem İngilizce çeviri dosyasına yazılır.
- Kullanıcının onayı olmadan yeni kütüphane eklenmez; eklenenler tam sürüme sabitlenir.

**Genel:**
- Raporlar ve özetler Türkçe; kod, kod yorumları ve commit mesajları İngilizce.
- Yeni paket eklemeden önce sor. Gizli değerleri hiçbir çıktıya yazma. Her aşamanın sonunda kullanıcıya 5 satırlık özet ver.

## İlk iş: Faz 0

Ürünün en riskli varsayımını kanıtla: arka planda otomatik sürüş kaydı + cihazda yol eşleştirme + yüzde hesabı, tek bir pilot şehirde (Adana). Ayrıntılar `docs/PRD.md` §13 ve `docs/ARCHITECTURE.md`.
