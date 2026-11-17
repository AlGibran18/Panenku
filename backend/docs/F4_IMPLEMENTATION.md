# Implementasi F4 — Pratinjau dan perhitungan checkout

Basis: commit backend `0b1e18c`, termasuk F1–F3 dan integrasi Geisar. Dokumen ini melengkapi aturan perhitungan, snapshot, dan limit dalam PRD serta README. `backend/README.md` tidak diubah. Tidak diperlukan migrasi baru; skema sampai V22 tetap dipakai.

## Aturan perhitungan

- Jumlah kg memakai desimal eksak, kelipatan 0,01 kg, minimum 0,01 dan maksimum 99.999.999,99 kg sesuai kolom NUMERIC(10,2).
- Harga satuan adalah rupiah bilangan bulat positif.
- Subtotal setiap item = kg × harga per kg, dibulatkan **half-up per item** ke rupiah. Hasil pembulatan item kemudian dijumlahkan.
- Total pembayaran = subtotal produk + ongkir. Ambil sendiri memakai ongkir 0; antar memakai ongkir tetap per pesanan dari profil petani.
- Komisi platform, biaya pemesanan, dan biaya kemasan terpisah tetap **0**.
- `farmerProductRevenue` sama dengan subtotal produk: ini pendapatan produk tanpa potongan komisi, bukan laba bersih usaha dan tidak memasukkan ongkir.
- Total harus <= limit yang berlaku. Nilai tepat pada limit diterima. Default operasional tetap Rp10 juta jika setting tersebut dikonfigurasi demikian; setting hilang/rusak tidak ditebak atau diganti diam-diam oleh backend.

`CheckoutMath` menyediakan perhitungan bersama untuk pratinjau dan checkout. Penjumlahan menggunakan BigDecimal sebelum konversi eksak ke Long, sehingga nominal terlalu besar ditolak, tidak berputar menjadi angka negatif.

Contoh: Tomat 2,5 kg × Rp12.000 = Rp30.000; Sawi 1,25 kg × Rp8.000 = Rp10.000. Subtotal Rp40.000, antar Rp10.000, total Rp50.000. Ambil sendiri menghasilkan total Rp40.000.

Pembulatan sangat kecil mengikuti aturan yang sama tanpa minimum belanja baru: 0,01 kg × Rp50 dibulatkan menjadi Rp1 per item. Dua item tersebut menghasilkan Rp2. Nilai 0,01 kg × Rp1 dibulatkan menjadi Rp0; tidak ditambahkan biaya/minimum rupiah buatan untuk menaikkannya. Alur verifikasi pembayaran yang sudah ada tidak diubah menjadi pembayaran/penyelesaian otomatis untuk nominal tersebut.

## Membaca pratinjau sebelum membuat pesanan

`POST /api/v1/buyer/checkout/preview`, token pembeli aktif, JSON:

```json
{
  "items": [
    {"productId": "UUID-tomat", "quantityKg": 2.5},
    {"productId": "UUID-sawi", "quantityKg": 1.25}
  ],
  "fulfillmentMethod": "delivery",
  "scheduledDate": "TANGGAL-ISO-dari-pilihan-F3",
  "scheduledTimeSlot": "08:00-10:00",
  "deliveryAddress": "Alamat pembeli",
  "deliveryDistrict": "kepanjen",
  "buyerNotes": "Sayur segar"
}
```

UUID dan tanggal di atas adalah placeholder. Gunakan nilai sebenarnya dari katalog/pilihan jadwal F3. Pilihan hanya berisi 1–100 produk unik dari satu petani. Harga dan ongkir **tidak dikirim untuk menentukan hasil pratinjau**; server membacanya dari database. Alamat/kecamatan wajib untuk delivery. Untuk pickup, alamat/kecamatan pengantaran diabaikan pada ringkasan efektif.

Respons 200 memakai envelope `success/data` yang sudah berlaku dan `Cache-Control: no-store`. `data` berisi:

| Field | Kegunaan |
|---|---|
| `farmerId`, `storeName` | Identitas toko pilihan. |
| `items` | Product ID, nama, komoditas, kg, harga/kg terkini, subtotal yang sudah dibulatkan, dan tanggal siap. Cocokkan item melalui `productId`. |
| `subtotalItems`, `deliveryFee`, `totalPayment` | Rincian rupiah bilangan bulat. |
| `platformFee`, `orderFee`, `packagingFee` | Selalu 0 pada MVP ini. |
| `farmerProductRevenue` | Subtotal produk, bukan laba bersih. |
| `currency` | `IDR`. |
| `transactionLimitApplied` | Limit yang dibaca untuk ringkasan ini. |
| `canCheckout`, `validationCode` | Kelayakan pada pembacaan ini; melebihi limit memberikan false dan `TRANSACTION_LIMIT_EXCEEDED`. |
| `fulfillmentMethod`, `scheduledDate`, `scheduledTimeSlot`, `timeZone` | Ringkasan pemenuhan, zona `Asia/Jakarta`. |
| `pickupLocation`, `deliveryAddress`, `deliveryDistrict`, `buyerNotes` | Lokasi/alamat dan catatan yang diringkas untuk konfirmasi. |
| `expectedFulfillmentToken` | Token validasi konteks pemenuhan F3. |
| `quotedAt` | Waktu pembacaan server (Instant UTC), bukan jaminan harga berlaku sampai waktu tertentu. |
| `quoteToken` | Token bertanda tangan untuk konfirmasi seluruh ringkasan. |

Pratinjau yang melampaui limit tetap mengembalikan 200 dengan rincian dan `canCheckout=false`, agar UI dapat menjelaskan kelebihan nominal. Server tetap menolak pembuatan pesanan tersebut. Kondisi lain yang tidak valid (stok, akun, produk, jadwal, layanan, atau konfigurasi) mengembalikan 400/403/404/409 sesuai jenisnya.

Pratinjau hanya membaca dalam satu snapshot transaksi PostgreSQL REPEATABLE READ. Tidak ada order, cadangan stok, item keranjang, snapshot pembayaran, atau catatan riwayat yang dibuat. Backend hanya memeriksa apakah petani mempunyai metode pembayaran yang dapat digunakan; **rincian rekening/QRIS tidak dikirim dalam respons pratinjau**.

## Konfirmasi checkout

Tampilkan rincian kepada pembeli. Jika disetujui dan `canCheckout=true`, rakit request ke endpoint lama `POST /api/v1/buyer/checkout` dengan header `Idempotency-Key`:

```json
{
  "items": [
    {"productId": "UUID-tomat", "quantityKg": 2.5, "expectedPricePerKg": 12000},
    {"productId": "UUID-sawi", "quantityKg": 1.25, "expectedPricePerKg": 8000}
  ],
  "fulfillmentMethod": "delivery",
  "scheduledDate": "TANGGAL-ISO-yang-dirangkum",
  "scheduledTimeSlot": "08:00-10:00",
  "expectedDeliveryFee": 10000,
  "deliveryAddress": "Alamat pembeli",
  "deliveryDistrict": "kepanjen",
  "buyerNotes": "Sayur segar",
  "expectedFulfillmentToken": "TOKEN-F3-dari-pratinjau",
  "expectedCheckoutToken": "quoteToken-dari-pratinjau"
}
```

Ambil `expectedPricePerKg` dari harga setiap item pada pratinjau, `expectedDeliveryFee` dari deliveryFee, serta kedua token dari respons yang sama. Jangan mengirim seluruh respons sebagai request; field hasil seperti totalPayment, subtotalItems, currency, dan biaya tambahan bukan input checkout. Parser JSON yang sudah ada tetap ketat dan menolak field tak dikenal.

Di bawah lock checkout yang sudah tersedia, server memeriksa ulang akun, profil, produk, stok, tanggal, slot, layanan, harga, ongkir, metode pembayaran yang tersedia, dan limit. Ringkasan dihitung ulang dengan kode perhitungan yang sama. Nilai dari klien tidak dipakai sebagai pengganti harga atau total authoritative dari server.

`quoteToken` memakai HMAC-SHA256 dan diikat pada pembeli serta seluruh rincian yang diringkas. Waktu `quotedAt` dan token itu sendiri dikecualikan dari signature. Item diurutkan serta kg dinormalisasi, sehingga urutan request atau penulisan 2.500 vs 2.50 tidak mengubah ringkasan. Token tidak menyimpan harga tetap, tidak mencadangkan stok, dan tidak menjamin kelayakan sampai kapan pun.

Perubahan rincian menghasilkan 409 `CHECKOUT_PREVIEW_CHANGED`. Perubahan harga/ongkir dapat lebih dahulu ditolak oleh pemeriksaan lama (`CONFLICT`), dan perubahan konteks F3 oleh `FULFILLMENT_CHANGED`. Jadwal/layanan yang sudah tidak valid dapat menghasilkan 400. Muat ulang pratinjau dan minta konfirmasi pembeli kembali; jangan mengganti nilai konfirmasi diam-diam. Perubahan limit juga memerlukan pratinjau baru, walaupun total masih di bawah limit baru, karena nilai limit yang ditampilkan berubah.

Field baru `expectedCheckoutToken` bersifat opsional untuk kompatibilitas. Klien baru sebaiknya menggunakannya. Jika null/tidak dikirim, field tersebut tidak ikut serialisasi hash request historis. Request lama tetap wajib membawa harga konfirmasi per item dan ongkir konfirmasi, dengan seluruh validasi server yang sudah ada.

## Retry, snapshot, dan integrasi

- Retry key dan payload yang sama mengembalikan order tersimpan sebelum memeriksa ulang quote terkini; snapshot pesanan lama tidak berubah ketika harga, profil, atau limit berubah.
- Key yang sama dengan payload berbeda tetap ditolak. Jika respons jaringan tidak diketahui, ulangi payload persis dan key semula; jangan memakai key baru sampai hasil request lama dipastikan.
- Pratinjau tidak mencadangkan stok. Dua pembeli dapat memperoleh ringkasan stok terakhir, tetapi hanya checkout yang berhasil memperoleh reservasi yang akan membuat pesanan.
- Pembuatan order, opsi pembayaran snapshot, item, dan reservasi tetap satu transaksi. Kegagalan salah satu penulisan menggulung balik semuanya.
- Pilihan rekening/QRIS tetap **setelah petani menerima pesanan**. `paymentMethodId` dan `paymentMethodVersion` tetap tidak diterima pada checkout baru. Kontrak F6/F7 Geisar tidak diganti.
- Jadwal tetap mengikuti F3. Ringkasan checkout dan respons checkout adalah snapshot awal; penjadwalan ulang yang disetujui kemudian tetap ditampilkan sebagai `effectiveSchedule` pada detail pesanan.
- Waktu pengurangan stok fisik pada F5 masih mengikuti implementasi Geisar (`menunggu_penerimaan`); perbedaan PRD tentang awal pengantaran tidak diubah oleh F4.

## Validasi dan pengujian

Bug `items: [null]` pada checkout sekarang menghasilkan 400, bukan 500. Pratinjau juga menolak elemen null, item kosong/duplikat, jumlah nol/negatif atau presisi berlebih, lebih dari 100 produk, teks terlalu panjang, serta token konfirmasi yang formatnya salah. Harga/ongkir konfirmasi memakai integer rupiah; nilai pecahan rupiah ditolak.

Dari folder `backend`, JDK 17 dan Docker aktif:

```powershell
.\gradlew.bat test --tests '*CheckoutPreviewIntegrationTest' --tests '*CheckoutPricingRulesTest'
.\gradlew.bat test bootJar
```

Suite penuh tetap mencakup F1–F3 dan integrasi Geisar. Tes seeder Geisar memerlukan python3, psql, dan htpasswd pada PATH; tes storage POSIX memerlukan lingkungan Linux/macOS yang sesuai. Kebutuhan tersebut tidak diubah oleh F4.

Cakupan F4 meliputi contoh PRD Rp50.000, pickup gratis, pembulatan per item, nilai sangat kecil, batas Long dan limit, perubahan harga/ongkir/limit/alamat, pengikatan token ke pembeli, manipulasi nominal, request null, konsistensi subtotal per product ID, transaksi gagal, stok terakhir, dan retry bersamaan. UAT UI Android belum dilakukan dalam pengerjaan backend ini.

Verifikasi 17 November 2026 (WIB), JDK 17 di Linux: **260 tes lulus, 0 gagal/error, 0 dilewati**, terdiri dari 226 tes sebelumnya dan 34 tes F4. `test bootJar` berhasil. Suite memakai PostgreSQL 16 dan MinIO F1 sungguhan melalui Testcontainers. README dan skema migrasi sampai V22 tidak berubah pada implementasi F4.
