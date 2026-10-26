# Kayıp Bulucu

Nereye düşerse düşsün (yer, halı, masa, araba koltuğu, fayans, çim, kum…) kaybolan küçük şeyi (küpe, şişe kapağı, hap, vida, boncuk, yüzük…) kamerayla bulan Android uygulaması.

- Uygulamayı aç, telefonu aradığın yere doğru tut ve yavaşça gezdir.
- Yüzeyden farklı görünen küçük bir şey görünce **yeşil kutu** içine alır ve telefon kısaca titrer.
- Yüzeyin ne olduğunu bilmesi gerekmez: her karede yüzeyin kendi renklerini ve dokusunu öğrenir, ona uymayan tek şeyi arar.
- Kutuya **alınmayanlar**:
  - Ayakkabı, ayak, mobilya ayağı gibi büyük ya da resmin kenarından giren şeyler. Kayıp bir şey, her yanı yüzeyle çevrili küçük bir "ada"dır.
  - Halı deseni, ahşap damarı, dikişler, derzler, çatlaklar, gölgeler.
  - Desende çok sayıda tekrar eden şeyler (puantiye, çakıl taşları, terrazzo parçaları). Sadece "tek olan" gösterilir.
- Kamera aşağıya bakmıyorsa (duvar, tavan) tarama yapmaz.
- **Hassasiyet** çubuğu: çok desenli yüzeyde düşür, düz yüzeyde çok küçük şeyler için artır.
- **Fener**: loş ortamda (araba içi, koltuk altı) ya da halı tüyleri arasındaki şeyleri belirginleştirmek için.

Sınırlar: Yüzeyle aynı renkte olan bir şey (siyah lastik üzerinde siyah toka, karda beyaz hap) ve çok yoğun, çok renkli desenler (kilim) zor; bunlarda yaklaşmak ve feneri açmak yardımcı olur.

Her şey telefonda çalışır; internet ya da hesap gerekmez.

APK, her push'ta GitHub Actions ile derlenir ve **Releases** sayfasına `KayipBulucu.apk` olarak yüklenir.
