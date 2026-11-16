# Implementasi F3 — Pilihan dan perubahan jadwal

Basis integrasi: backend Geisar `64f713c`. Catatan ini melengkapi PRD PROD02, ORDER02, SHIP01/SHIP02 serta implementasi profil/pembayaran Geisar. `backend/README.md` dan migrasi V1–V21 tidak diubah.

## Aturan bersama

Zona layanan adalah **Asia/Jakarta (WIB)**, menggunakan waktu server. Semua produk dalam pilihan pemenuhan harus berasal dari satu petani dan memiliki stok yang cukup pada saat dibaca. Tanggal awal mengikuti tanggal siap paling akhir dari produk, tanggal hari ini WIB, dan ketersediaan slot yang belum dimulai.

Pengaturan memakai API Geisar yang sudah tersedia: `GET/PUT /api/v1/petani/profile`. Format request profil tetap sama. F3 menyatukan validasi dengan checkout: maksimal 100 slot, format tepat `HH:mm-HH:mm`, jam valid, mulai sebelum akhir pada hari yang sama, tanpa duplikat setelah trim, dan tanpa tumpang tindih. Slot bersebelahan (`08:00-10:00`, `10:00-12:00`) diperbolehkan. Daftar diurutkan berdasarkan jam mulai. Area memakai identifier kecamatan yang dinormalisasi trim/lowercase dan tanpa duplikat; tidak ditambahkan daftar wilayah nasional yang belum disepakati tim.

Slot profil berlaku setiap hari. Fitur hari libur, kapasitas per slot, kalender mingguan, rute/GPS, dan kurir otomatis tidak ditambahkan. Tanggal/slot tidak mencadangkan kapasitas pengantaran; stok baru dicadangkan saat checkout.

## Pilihan jadwal pembeli

`POST /api/v1/buyer/fulfillment-options`, token pembeli aktif:

```json
{
  "items": [{"productId": "UUID-produk", "quantityKg": 2.5}],
  "fulfillmentMethod": "delivery",
  "deliveryDistrict": "kepanjen",
  "days": 14
}
```

UUID pada contoh adalah placeholder. `fulfillmentMethod` adalah `pickup` atau `delivery`. Untuk pickup, ongkir selalu 0 dan kecamatan tidak diperlukan. Antar memerlukan layanan aktif serta kecamatan yang terdaftar; alamat lengkap tetap wajib saat checkout. Respons memuat:

- `farmerId`, `storeName`, `fulfillmentMethod`, `deliveryDistrict`, `deliveryFee`, `pickupLocation`, `serviceArea`;
- `timeZone="Asia/Jakarta"`, `serverTime` (Instant UTC), `earliestDate`;
- `dates`: daftar `{ "date": "...", "timeSlots": ["08:00-10:00", ...] }`;
- `selectionToken`: token data pemenuhan untuk konfirmasi checkout.

Rentang default 14 hari, maksimum 31 hari per request. `fromDate` opsional untuk meminta rentang berikutnya, tetapi tidak boleh sebelum `earliestDate`. Jika slot terakhir hari ini sudah dimulai, tanggal paling awal bergeser ke hari berikutnya. Tepat pada jam mulai, slot sudah tidak dapat dipilih untuk pesanan baru.

Endpoint ini hanya membaca data; tidak membuat order, reservasi, atau pembayaran. Satu request menerima 1–100 produk unik, jumlah kelipatan 0,01 kg dan positif. Produk nonaktif/habis, komoditas nonaktif, petani belum disetujui/disuspend, atau pilihan lintas petani ditolak.

Saat checkout, kirim field baru `expectedFulfillmentToken` berisi `selectionToken`, di samping `expectedPricePerKg`, `expectedDeliveryFee`, tanggal, dan slot yang sudah berlaku. Contoh tambahan pada payload checkout:

```json
{
  "expectedFulfillmentToken": "TOKEN-dari-fulfillment-options"
}
```

Ini hanya cuplikan field tambahan, bukan request checkout lengkap. Token memeriksa ulang lokasi, ongkir, konfigurasi slot/area, dan tanggal siap produk di bawah lock checkout. Jika pilihan masih valid tetapi datanya berubah, server memberi 409 `FULFILLMENT_CHANGED`; jika tanggal/layanan sudah tidak valid, validasi terkait tetap dapat memberi 400/409. Muat ulang pilihan dan minta konfirmasi pembeli kembali.

Token bersifat opsional untuk kompatibilitas klien lama; klien baru sebaiknya mengirimkannya. Field null tidak dimasukkan ke hash request historis, sehingga retry checkout lama tetap bekerja. Stok, harga, jadwal, layanan, dan limit tetap diperiksa ulang oleh checkout. Token bukan otorisasi atau reservasi, serta tidak memasukkan waktu penerbitan agar tidak berubah hanya karena kalender dibaca ulang.

Pemilihan pembayaran tetap **setelah petani menerima pesanan**. Jangan mengirim `paymentMethodId`/`paymentMethodVersion` pada checkout baru. Salinan opsi pembayaran dan kontrak F6 Geisar dipertahankan.

## Jadwal awal dan jadwal efektif

Kolom `orders.scheduled_date`, `scheduled_time_slot`, dan snapshot item tetap immutable sebagai rekaman checkout. Penjadwalan ulang tidak mengubah harga, ongkir, alamat, metode pemenuhan, snapshot pembayaran, atau snapshot tanggal siap.

Detail/daftar pesanan F5 menambahkan:

```json
{
  "effectiveSchedule": {
    "scheduledDate": "tanggal-jadwal-aktif",
    "scheduledTimeSlot": "13:00-15:00",
    "version": 0,
    "timeZone": "Asia/Jakarta"
  },
  "pendingScheduleChange": null
}
```

Versi 0 berarti jadwal checkout awal; versi berikutnya berasal dari usulan yang disetujui. UI yang menampilkan jadwal berjalan harus membaca `effectiveSchedule`. Field lama `scheduledDate`/`scheduledTimeSlot`, termasuk respons replay checkout, tetap berarti snapshot awal. Jangan mengaktifkan UI penjadwalan ulang pada klien yang belum membaca jadwal efektif.

## Usulan dan keputusan perubahan jadwal

Alur awal F3 mendukung perubahan **sebelum bukti pembayaran pertama**, pada pesanan `menunggu_petani`/`menunggu_pembayaran` dengan `payment_status=belum_dibayar`, belum melewati tenggat. Setelah bukti pertama, termasuk bukti yang ditolak, perubahan tidak didukung oleh alur ini; penanganan dana/jadwal lanjutan perlu dirancang bersama modul pembayaran. Tidak ada perubahan sepihak oleh petani atau admin.

Petani pemilik mengirim `POST /api/v1/petani/orders/{id}/schedule-changes`, header `Idempotency-Key` (1–128 huruf/angka/underscore/hyphen):

```json
{
  "expectedScheduleVersion": 0,
  "scheduledDate": "TANGGAL-baru-ISO",
  "scheduledTimeSlot": "13:00-15:00",
  "reason": "Penyesuaian waktu panen"
}
```

`scheduledDate` harus diganti tanggal ISO sebenarnya, misalnya nilai yang dipilih dari kalender. Alasan wajib maksimal 1000 karakter. Jadwal harus berbeda, berada di masa depan, tidak mendahului tanggal siap snapshot item, dan memakai slot profil yang tersedia. Hanya satu usulan pending per pesanan. Key yang sama dan payload sama mengembalikan usulan yang sama tanpa riwayat ganda; payload berbeda menghasilkan 409.

Pembeli pemilik melihat usulan pada detail pesanan atau `GET /api/v1/buyer/orders/{id}/schedule-changes`. Petani melihat riwayatnya melalui endpoint GET dengan prefix `/petani`. Riwayat memakai `page` mulai 0, `size` 1–100, default 20.

Keputusan pembeli dikirim melalui `PUT /api/v1/buyer/orders/{orderId}/schedule-changes/{changeId}/decision`:

```json
{
  "expectedScheduleVersion": 0,
  "decision": "terima",
  "reason": "Waktu sesuai"
}
```

Keputusan `terima` atau `tolak`; alasan pembeli opsional maksimal 1000 karakter. Saat menerima, server memeriksa ulang kondisi pesanan, versi, tenggat, slot terkini, dan tanggal siap snapshot. Persetujuan menghasilkan versi efektif berikutnya; penolakan mempertahankan jadwal sebelumnya. Pengulangan keputusan yang sama bersifat idempotent; keputusan berbeda setelah diputuskan ditolak. Jika konfigurasi berubah sebelum persetujuan, usulan masih dapat ditolak lalu dibuat ulang.

Proposal/keputusan dicatat di tabel baru V22 `order_schedule_changes` dan riwayat pesanan. Baris usulan tidak dapat diubah kecuali satu keputusan final; riwayat final dan snapshot awal dilindungi dari update/delete. Gagal menulis riwayat menggulung balik keputusan serta perubahan jadwal efektif dalam transaksi yang sama.

Usulan pending menahan penerimaan pesanan oleh petani serta pemilihan/pengiriman bukti pembayaran. **Usulan tidak memperpanjang atau menghentikan tenggat:** scheduler tetap boleh mengakhiri pesanan yang memenuhi syarat dan melepas cadangan tepat satu kali. Usulan pada order kedaluwarsa tidak bisa disetujui dan tidak membuka kembali stok. Riwayatnya dapat tetap berstatus pending secara historis; UI hanya menawarkan persetujuan jika order masih memenuhi syarat. Penolakan tetap boleh untuk menutup usulan historis tersebut.

## Integrasi F5 dan batas waktu

- Persiapan `diproses` setelah pembayaran diverifikasi masih boleh sebelum jadwal.
- Status pemenuhan nyata (`siap_diambil`, `diantar`, dan penyerahan/kedatangan menuju `menunggu_penerimaan`) ditolak sebelum **awal slot jadwal efektif WIB**. Tepat pada awal slot diperbolehkan.
- Pemeriksaan memakai tanggal siap **snapshot pesanan**, bukan tanggal produk terkini. Perubahan produk setelah checkout tidak menggeser pesanan lama.
- Penyelesaian yang terlambat setelah akhir slot masih diperbolehkan; tidak ada penyelesaian otomatis atau pemblokiran pesanan berbayar karena terlambat.
- `gagal_dipenuhi` tetap boleh sebelum jadwal dengan alasan, agar reservasi dapat dilepas dan proses refund ditangani F7.
- Jika slot sudah dimulai sebelum petani menerima atau pembeli membayar, server memberi 409 `SCHEDULE_ELAPSED`. Para pihak perlu menyetujui jadwal baru selama order masih memenuhi syarat. Tidak ditambahkan kewajiban buffer 24 jam pada semua jadwal; jendela respons/pembayaran F5/F6 tetap mengikuti aturan Geisar.

Kode lain yang perlu ditangani UI: `SCHEDULE_NOT_STARTED`, `SCHEDULE_CHANGE_PENDING`, `SCHEDULE_CHANGED`, `SCHEDULE_KEY_REUSED`, `SCHEDULE_ALREADY_DECIDED`, `SCHEDULE_CHANGE_UNAVAILABLE`, dan `ORDER_EXPIRED` (409). Input tanggal/slot/alasan salah memakai 400; kepemilikan/role salah memakai 403; sumber tidak ditemukan memakai 404.

F3 tidak mengubah aksi stok pada state machine F5: implementasi lama masih mengurangi stok fisik pada `menunggu_penerimaan`. Perbedaan dengan PRD yang menyebut awal pengantaran tetap perlu diselaraskan pada pekerjaan F2/F5 tersendiri. Storage POSIX bukti pembayaran/refund Geisar juga tidak diubah oleh F3; storage foto F1 tetap terpisah.

## Menjalankan dan menguji

Gunakan JDK 17 dan Docker. Flyway menjalankan migrasi baru **V22**; jangan mengedit checksum migrasi lama atau me-reset database berisi data. Perhatian terhadap database dengan nomor migrasi lama dari workspace Geisar tetap mengikuti catatan integrasi README.

Dari folder `backend`:

```powershell
.\gradlew.bat test --tests '*Scheduling*Test'
.\gradlew.bat test bootJar
```

Suite Geisar mencakup seeder demo yang memerlukan `python3`, `psql`, dan `htpasswd` pada PATH. Pengujian penyimpanan POSIX memakai Linux/macOS; pada Windows gunakan lingkungan Linux yang sesuai untuk suite lengkap tersebut. Ini kebutuhan suite Geisar, bukan dependensi runtime kalender F3.

Tes F3 memakai PostgreSQL sungguhan, waktu server terkontrol, API melalui MockMvc, dua request bersamaan, dan kegagalan database terkontrol. Skenario pemenuhan lama pada fixture tes dipindahkan ke waktu jadwal yang sebenarnya; batas refund diuji relatif terhadap `received_at`, bukan tanggal hardcoded sebelum barang tersedia. UAT UI Android belum dilakukan dalam implementasi backend ini.

Verifikasi 17 November 2026 (WIB), JDK 17 di Linux: **226 tes lulus, 0 gagal/error, 0 dilewati**, terdiri dari 189 tes sebelumnya dan 37 tes F3. `test bootJar` berhasil. PostgreSQL 16 dan MinIO F1 sungguhan dipakai oleh suite integrasi. Pemeriksaan mencakup API usulan/persetujuan, elemen daftar null, konkurensi, rollback, snapshot, dan alur F1/F2/F5/F6/F7 yang sudah ada.
