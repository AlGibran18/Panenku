# Implementasi F1 — Katalog dan Produk

Catatan implementasi tambahan; `backend/README.md` tetap menjadi pegangan proyek dan tidak diubah.
Basis integrasi: backend Geisar pada commit `5a9ea40`.

## Perilaku API

Katalog tetap mendukung pencarian nama produk/komoditas/toko, filter komoditas, urut harga Rp/kg, dan paginasi. Produk aktif hanya tampil dari petani terverifikasi yang akunnya aktif dan komoditasnya aktif. Produk habis tetap terlihat tetapi `availableForPurchase=false`. Tanggal siap di masa depan tidak menyembunyikan produk: jadwal checkout tetap mengikuti tanggal tersebut.

Respons produk menambahkan:

| Field | Makna |
|---|---|
| `farmerVerified` | KTP demo petani disetujui admin; bukan jaminan kualitas panen. |
| `availableForPurchase` | Produk aktif, petani aktif/terverifikasi, komoditas aktif, dan stok tersedia positif. Checkout tetap memvalidasi ulang. |
| `photoUrl` | Object key privat, dipertahankan untuk kompatibilitas field lama. Bukan URL yang dibuka Android. |
| `photoAccessUrl` | URL GET bertanda tangan selama 15 menit; dapat null jika foto belum dikelola/konfigurasi belum tersedia. |
| `photoExpiresAt` | Waktu kedaluwarsa URL UTC. Muat ulang detail untuk memperoleh URL baru. |

Detail menambahkan `farmerRating: {"average": null, "reviewCount": 0}` jika belum ada ulasan valid. Nilai berasal dari ulasan pesanan berstatus `selesai`, dengan pembeli/petani sesuai pesanan; rata-rata dibulatkan dua desimal. Tidak ada rating dummy. Migrasi V5 menyediakan tabel `reviews` sesuai rancangan README; endpoint mengirim ulasan belum dibuat dalam F1 ini. Modul ulasan berikutnya wajib menjaga kepemilikan, status selesai, dan satu ulasan per pesanan.

## Alur produk dan foto

1. Petani membuat produk melalui `POST /api/v1/petani/products`, `publicationStatus="draft"`, tanpa `photoUrl`.
2. Unggah `POST /api/v1/petani/products/{id}/photo`, header `Authorization: Bearer <token>`, multipart field **file**. Di Postman pilih Body → form-data → key `file` bertipe File. Biarkan klien membuat boundary Content-Type.
3. Respons 201 berisi `productId`, object key `photoUrl`, `photoAccessUrl`, `photoExpiresAt`, `contentType`, dan `sizeBytes`.
4. Publikasikan melalui `PUT /api/v1/petani/products/{id}` dengan data produk lengkap dan `publicationStatus="aktif"`. `photoUrl` boleh dihilangkan: foto tersimpan dipertahankan. Jika dikirim, hanya object key foto produk yang saat ini tersimpan yang diterima.
5. Endpoint unggah yang sama mengganti satu foto aktif produk. `DELETE /api/v1/petani/products/{id}` hanya menonaktifkan produk, sehingga snapshot pesanan lama tetap tersimpan.

JPEG dan PNG maksimal **5 MiB (5.242.880 byte)** diterima. Server memeriksa isi gambar, kecocokan MIME, kemampuan decode, dan dimensi maksimal 25 megapiksel untuk membatasi memori. Nama berkas klien tidak dipakai sebagai object key. SVG, berkas kosong/rusak, MIME palsu, dan path foto buatan klien ditolak. Hanya petani pemilik dengan akun aktif yang dapat mengunggah/mengubah produk. Draft boleh dibuat sebelum verifikasi; publikasi memerlukan KTP disetujui, lokasi, metode pembayaran lengkap, stok positif, komoditas aktif, dan foto yang telah diunggah untuk produk itu.

Foto produk memakai bucket khusus tanpa bucket policy. Akses tanpa tanda tangan ditolak MinIO. Unggah yang gagal menyimpan perubahan database menghapus objek baru setelah rollback; objek sebelumnya tetap menjadi foto produk. Objek foto lama setelah penggantian sukses masih privat dan belum dihapus otomatis; housekeeping/lifecycle storage perlu disepakati terpisah. URL lama yang sudah diterbitkan tetap berlaku sampai kedaluwarsa.

Data lama dengan path foto dummy tetap dapat dibaca agar integrasi tidak merusak pesanan/katalog lama, tetapi tidak diberi URL foto palsu. Sebelum produk lama dipublikasikan/diubah sebagai aktif, unggah foto yang sebenarnya. Jika storage nonaktif/tidak terjangkau, unggah mengembalikan 503; server tidak melaporkan unggah sukses palsu. Adapter foto F1 terpisah dari `common/storage/FileStorage`; unggah KTP/bukti pembayaran/refund bukan bagian implementasi ini.

## Menjalankan lokal di Windows / Android Studio

Gunakan JDK 17 sesuai toolchain proyek dan Docker Desktop dengan Linux engine aktif. Dari folder `backend`, pastikan `docker version` menampilkan Client dan Server. Pertahankan `.env` lokal; jika belum ada, salin `.env.example` dan ganti password/secret.

Atur di `.env`:

```dotenv
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=panenku-local
MINIO_SECRET_KEY=change-this-local-storage-password
PRODUCT_PHOTOS_ENABLED=true
PRODUCT_PHOTOS_BUCKET=panenku-products
MINIO_PUBLIC_ENDPOINT=http://localhost:9000
```

Untuk Android emulator gunakan `MINIO_PUBLIC_ENDPOINT=http://10.0.2.2:9000`; endpoint internal backend tetap localhost. Host pada URL bertanda tangan harus dapat dijangkau klien dan tidak boleh diganti setelah URL ditandatangani. Perangkat fisik memerlukan alamat host yang dapat dijangkau dan pengaturan jaringan tersendiri; Compose membatasi port ke loopback secara default.

```powershell
docker compose --profile product-photos up -d --build
```

Profile ini menambahkan MinIO untuk foto tanpa mengubah layanan PostgreSQL. API storage ada di port 9000 dan console di 9001. Volume bernama mempertahankan foto setelah restart container. Jangan menggunakan `down -v` jika ingin menyimpan data.

**Distribusi MinIO:** image/biner komunitas resmi tidak lagi tersedia pada pengujian ini; repository komunitas telah diarsipkan. Dockerfile membangun sumber resmi pada commit tetap `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a` (tag `RELEASE.2025-10-15T17-29-55Z`), bukan mengambil image pihak ketiga. Build pertama memerlukan internet, ruang disk beberapa GB, dan memori Docker yang cukup (disarankan 6 GB). Ini lingkungan demo pengembangan, bukan klaim bahwa server arsip tersebut menerima pembaruan keamanan. Untuk deployment gunakan layanan storage yang dipelihara dan disepakati tim; adapter saat ini diuji terhadap MinIO, bukan semua penyedia S3.

Spring Boot tidak otomatis membaca `.env`. Ekspor variabel ke Terminal tempat `bootRun` dijalankan, atau isi Environment variables pada konfigurasi Run Android Studio. Untuk file `.env` sederhana KEY=VALUE dari contoh:

```powershell
Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and !$line.StartsWith('#')) {
        $pair = $line -split '=', 2
        if ($pair.Length -eq 2) { [Environment]::SetEnvironmentVariable($pair[0], $pair[1], 'Process') }
    }
}
.\gradlew.bat bootRun
```

Jangan commit `.env`, password, token, atau URL bertanda tangan. `MINIO_BUCKET` lama tetap terpisah dari `PRODUCT_PHOTOS_BUCKET`.

## Pengujian

```powershell
.\gradlew.bat test bootJar
```

Tes integrasi menggunakan PostgreSQL dan MinIO sungguhan melalui Testcontainers; Docker wajib hidup. Jika image lokal `panenku-product-minio:9e49d5e` belum ada, tes F1 membangunnya melalui Dockerfile di atas. Log build otomatis ada di `backend/build/product-photos-image-build.log`. Pada jaringan proxy ber-CA khusus, build dapat menerima secret BuildKit `proxy_ca`; untuk build otomatis tes, set `PRODUCT_STORAGE_BUILD_CA_FILE` ke bundle CA tepercaya lokal. CA tersebut tidak dimasukkan ke image hasil.

Cakupan F1 meliputi unggah JPEG/PNG, bytes unduhan yang sama, penolakan akses anonim ke objek, URL 15 menit, auth/pemilik, validasi berkas, storage nonaktif/tidak terjangkau, kompensasi rollback database, publikasi dan deaktivasi, stok cadangan, snapshot pesanan, availability, serta agregasi rating. Tes fitur yang sudah ada tetap dijalankan untuk memeriksa regresi integrasi. Pengujian API ini tidak menggantikan UAT antarmuka Android.

Verifikasi pada 16 November 2026 dengan JDK 17: `gradle test bootJar` berhasil; **82 tes, 0 gagal/error, 0 dilewati**, terdiri dari 69 tes sebelumnya dan 13 tes tambahan F1. PostgreSQL 16 serta MinIO hasil Dockerfile di atas dipakai pada tes integrasi. Tes mencakup akses foto lewat HTTP sebenarnya ke MinIO dan endpoint aplikasi melalui MockMvc. UAT aplikasi Android dan deployment kelompok belum dijalankan dalam verifikasi ini.
