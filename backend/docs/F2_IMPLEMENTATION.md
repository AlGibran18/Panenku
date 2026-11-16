# Implementasi F2 — Keranjang dan Reservasi Stok

Catatan tambahan implementasi Gibran berdasarkan PRD CART01/CART02 dan aturan README. `backend/README.md` tidak diubah. Basis: commit backend `7e5f8ef` yang sudah memuat integrasi Geisar dan F1.

## Keranjang dan konsistensi

Endpoint lama tetap tersedia di `/api/v1/buyer/cart`: GET untuk membaca, POST `/items` untuk menambah jumlah, PUT `/items/{id}` untuk menetapkan jumlah, dan DELETE `/items/{id}` untuk menghapus item. Auth wajib; role dan status akun diperiksa kembali pada database.

Semua operasi keranjang memakai lock baris akun pembeli yang sama dengan checkout. Lock ini sudah tersedia walaupun keranjang kosong. Dua penambahan bersamaan tidak saling menimpa, dan dua produk petani berbeda tidak dapat masuk bersamaan ke keranjang kosong. Pembeli lain tetap memakai lock akunnya sendiri. Penguncian keranjang tidak mengunci atau mencadangkan stok produk.

Jumlah menggunakan kelipatan 0,01 kg, minimal 0,01 kg; jumlah nol/negatif, presisi berlebih, dan jumlah melebihi stok ditolak. Maksimal 100 produk unik. Menghapus item yang kemudian nonaktif/habis tetap diperbolehkan agar pembeli bisa memperbaiki keranjangnya.

POST `/items` bersifat **menambah**, sedangkan PUT `/items/{id}` menetapkan jumlah absolut. Jangan mengirim ulang POST penambahan secara otomatis setelah respons jaringan tidak diketahui; muat ulang keranjang terlebih dahulu. Perilaku idempotency checkout yang sudah ada tetap memakai `Idempotency-Key`.

Respons `CartDTO` mempertahankan field lama dan menambahkan:

| Field | Makna |
|---|---|
| `cartVersion` | Token isi keranjang yang dihitung server; kirim kembali untuk konfirmasi penggantian. |
| `farmerId`, `storeName` | Petani/toko keranjang; null jika kosong. |
| `hasUnavailableItems` | Ada item yang saat ini tidak dapat dibeli dengan jumlah tersimpan. |

Setiap item menambahkan `farmerId`, `storeName`, `readyDate`, `photoAccessUrl`, `photoExpiresAt`, dan `unavailableReason`. Foto memakai adapter F1, dapat null saat foto/storage belum tersedia. Harga, stok, dan tanggal dibaca dari data produk terkini. Jumlah tersimpan tidak otomatis dikurangi ketika stok turun. Subtotal mencakup seluruh item tersimpan, termasuk item tidak tersedia; gunakan flag dan alasan tersebut untuk meminta pembeli memperbaiki pilihan sebelum checkout.

| `unavailableReason` | Penanganan UI yang disarankan |
|---|---|
| `INSUFFICIENT_STOCK` | Tampilkan stok terbaru; minta pembeli mengurangi jumlah atau menghapus item. |
| `OUT_OF_STOCK` | Produk habis; tidak bisa checkout. |
| `PRODUCT_INACTIVE` | Produk dinonaktifkan. |
| `COMMODITY_INACTIVE` | Komoditas tidak aktif. |
| `FARMER_UNVERIFIED` | Verifikasi petani belum disetujui. |
| `FARMER_SUSPENDED` | Petani disuspend; transaksi baru diblokir. |
| `FARMER_UNAVAILABLE` | Data/role petani tidak tersedia. |
| null | Item tersedia untuk jumlah tersebut pada pembacaan ini; checkout tetap memvalidasi ulang. |

Pembacaan data produk/profil/akun/komoditas dilakukan per kelompok, bukan satu query untuk setiap item. Tidak ada state keranjang dalam memori proses; item tetap tersimpan di PostgreSQL dan token dapat dihitung ulang setelah restart.

## Pergantian petani atau penggantian seluruh keranjang

Jika POST `/items` mencoba memasukkan petani berbeda, server mengembalikan 409 `CART_FARMER_CONFLICT` dan mempertahankan isi lama. UI menawarkan Pertahankan atau Ganti. Memilih Pertahankan tidak memerlukan request baru.

Setelah pembeli memilih Ganti, ambil `cartVersion` dari GET `/api/v1/buyer/cart` dan kirim **PUT `/api/v1/buyer/cart`**:

```json
{
  "items": [
    {
      "productId": "UUID-produk-petani-yang-dipilih",
      "quantityKg": 2.5,
      "expectedPricePerKg": 12000
    }
  ],
  "expectedCartVersion": "TOKEN-dari-GET-cart",
  "confirmed": true
}
```

UUID/token pada contoh adalah placeholder, ganti dengan nilai respons sebenarnya. Endpoint mengganti **seluruh isi**, bukan menggabungkan. `items: []` boleh untuk mengosongkan seluruh keranjang setelah konfirmasi. Respons sukses 200 berisi keranjang terbaru.

Server memeriksa konfirmasi, token, kepemilikan akun, produk unik dari satu petani, jumlah, kondisi produk, stok, serta harga yang diharapkan sebelum menghapus item lama. Semua delete/insert berada dalam satu transaksi. Kegagalan penyimpanan mengembalikan isi lama melalui rollback.

`cartVersion` adalah SHA-256 atas identitas pembeli dan isi item termasuk ID serta jumlah yang dinormalisasi. Token bukan nomor versi berurutan, bukan otorisasi, dan tidak mencadangkan stok/harga. URL foto sementara tidak memengaruhinya. Jika isi berbeda dari saat konfirmasi, server mengembalikan 409 `CART_CHANGED`; muat ulang dan minta konfirmasi baru. Harga yang berbeda dari `expectedPricePerKg` menghasilkan 409 `PRODUCT_PRICE_CHANGED`.

## Beli Lagi

1. GET **`/api/v1/buyer/orders/{id}/reorder-preview`**, dengan token pembeli pemilik pesanan.
2. Tampilkan daftar `items`, perubahan harga/tanggal, item yang dikecualikan, serta jumlah yang disarankan. Jelaskan bahwa konfirmasi akan mengganti seluruh keranjang sekarang.
3. Jika pembeli setuju, POST **`/api/v1/buyer/orders/{id}/reorder`** dengan:

```json
{
  "expectedCartVersion": "cartVersion-dari-pratinjau",
  "previewToken": "previewToken-dari-pratinjau",
  "confirmed": true
}
```

Pratinjau tidak mengubah keranjang dan tidak membuat pesanan, pembayaran, atau reservasi. Hanya pesanan milik akun pembeli aktif yang dapat dipakai. Tidak ditambahkan pembatasan status selesai pada Beli Lagi karena PRD menyebut pesanan lama tanpa syarat tersebut.

Field penting pada setiap item pratinjau:

- `previousQuantityKg` dan `previousPricePerKg`: snapshot pesanan sumber.
- `quantityKg`: jumlah yang disarankan, maksimum jumlah lama dan stok tersedia terkini yang lebih kecil. Nilai ini nol untuk item yang tidak tersedia.
- `pricePerKg`, `priceChanged`, `readyDate`, `stockAvailable`: keadaan produk terbaru.
- `available`: item akan dimasukkan jika dikonfirmasi.
- `adjustmentReason`: `QUANTITY_REDUCED` jika stok lebih kecil, atau kode ketersediaan pada tabel di atas. `PRODUCT_NOT_FOUND` dipakai jika produk sumber tidak ditemukan.

Konfirmasi menghitung ulang pratinjau. Perubahan harga, stok, tanggal, nama/toko, atau kondisi item menghasilkan 409 `REORDER_CHANGED`, sehingga penyesuaian baru tidak diterapkan diam-diam. Perubahan isi keranjang dari perangkat lain menghasilkan 409 `CART_CHANGED`. Jika seluruh item tidak tersedia, 409 `NO_REORDERABLE_ITEMS` diberikan dan keranjang lama dipertahankan. Item tidak tersedia hanya dikecualikan dari hasil Beli Lagi setelah pembeli mengonfirmasi pratinjau.

Setelah sukses, pengiriman ulang konfirmasi dengan token isi lama ditolak `CART_CHANGED` sehingga tidak menambah jumlah dua kali. Jika respons jaringan hilang, GET keranjang untuk melihat hasil; jangan langsung mengonfirmasi ulang dengan pratinjau baru tanpa keputusan pembeli.

Snapshot pesanan sumber tidak diubah. Harga, stok, dan tanggal pada keranjang baru tetap dapat berubah sebelum checkout; validasi ulang checkout tetap menjadi gerbang akhir.

## Reservasi stok dan batas integrasi

F2 mempertahankan checkout atomik yang sudah ada: satu transaksi membuat order, item snapshot, reservasi, dan kenaikan `stock_reserved`. Stok tersedia adalah `stock_physical - stock_reserved`. Checkout mengunci produk dan memvalidasi ulang sehingga dua keranjang dapat berisi produk terakhir, tetapi hanya checkout yang memperoleh stok yang berhasil. Kegagalan validasi tidak meninggalkan pesanan atau cadangan parsial.

Pelepasan saat pembatalan/penolakan/kedaluwarsa dan perlindungan pelepasan tepat satu kali tetap memakai implementasi F5/scheduler Geisar. Checkout tidak otomatis mengosongkan keranjang; perilaku sebelumnya dipertahankan karena tidak ada ketentuan pengosongan otomatis yang disepakati dalam scope ini. Refund tidak otomatis merestok.

**Ketentuan yang masih perlu diselaraskan lintas F2/F5:** PRD menyebut pengurangan stok fisik ketika mulai diantar, sedangkan catatan F5 README dan kode saat ini memenuhinya pada `menunggu_penerimaan`. Implementasi F2 ini tidak mengubah state machine F5, endpoint pembayaran/refund, atau jadwal/perhitungan F3/F4. Jangan menganggap perbedaan waktu pemenuhan tersebut sudah diperbaiki oleh perubahan keranjang ini.

## Verifikasi

Dari folder `backend`, gunakan JDK 17 dan Docker aktif seperti konfigurasi proyek:

```powershell
.\gradlew.bat test --tests '*CartIntegrationTest'
.\gradlew.bat test bootJar
```

Tes F2 menggunakan PostgreSQL 16 sungguhan melalui Testcontainers. Cakupan: penambahan bersamaan pada keranjang kosong/berisi, larangan campur petani, dua konfirmasi bersamaan, token lama, rollback setelah delete saat insert gagal, alasan item tidak tersedia, otorisasi API, Beli Lagi dengan data terkini, preservasi snapshot, dua keranjang memperebutkan stok terakhir, kegagalan checkout tanpa reservasi parsial, dan pembatalan bersamaan dengan kedaluwarsa. Tes F1 serta tes Geisar juga tetap dijalankan pada suite penuh. UAT antarmuka Android belum dijalankan dalam pengerjaan backend ini.

Hasil verifikasi 17 November 2026 (WIB), JDK 17: **107 tes lulus, 0 gagal/error, 0 dilewati**, terdiri dari 82 tes sebelumnya dan 25 tes F2; `test bootJar` berhasil. Tes tambahan memastikan lock keranjang tetap kompatibel dengan foreign key riwayat pembeli pada F5. State machine F5 dan README tetap utuh.
