# Kesesuaian Figma pembeli dan petani dengan backend

Rujukan: [PAPB PANENKU](https://www.figma.com/design/OFj847BJFoT1TACWnmw5Ta/PAPB-PANENKU?node-id=0-1). Ditinjau pada 16 November 2026 melalui tampilan viewer. Ini pemetaan tampilan yang terlihat, bukan spesifikasi lengkap seluruh interaksi Figma. Konektor Figma tidak memiliki akses editor sehingga metadata/detail layer belum dapat diperiksa.

## Pembeli

| Tampilan | Kondisi backend dan tindak lanjut |
|---|---|
| Beranda, pencarian, daftar dan detail produk | Katalog, pencarian, detail, dan komoditas tersedia. Rating/ulasan, kategori visual dan beberapa informasi promosi di desain belum tersedia sebagai data API. Penambahan katalog adalah area Gibran. |
| Keranjang | Keranjang dan jumlah barang tersedia. Desain menampilkan pilihan pengiriman instan/standar; backend saat ini memakai pickup/delivery dan jadwal/slot petani. Jangan menganggap kedua istilah memiliki aturan yang sama tanpa kesepakatan F3/F4. |
| Checkout dan konfirmasi petani | Checkout, snapshot barang dan reservasi, serta respons petani tersedia. Sesuai keputusan Rampan, pilihan rekening/QRIS diberikan setelah petani menerima pesanan. Pilihan yang tersedia dicatat saat checkout agar perubahan rekening toko tidak mengubah transaksi lama. |
| Alamat pengiriman | Checkout menerima alamat dan kecamatan. Daftar beberapa alamat tersimpan, alamat utama, dan CRUD alamat belum tersedia. |
| Pembayaran dan bukti ditolak | Lihat pilihan dan QRIS, pilih metode, unggah bukti, riwayat, verifikasi petani, alasan penolakan dan tenggat baru tersedia melalui API. Bukti baru mengembalikan nama/ukuran file; data lama dapat bernilai null. |
| Detail perjalanan pesanan | Daftar/detail, riwayat status, dan konfirmasi penerimaan tersedia. Label visual dan indikator tahap perlu dipetakan ke status backend oleh aplikasi Android. |
| Usulan perubahan jadwal | Desain menampilkan usulan petani dan persetujuan/penolakan pembeli. Endpoint dan aturan ini belum tersedia; jadwal checkout saat ini immutable. Perlu kesepakatan dengan Gibran dan tim sebelum merancang perubahan. |
| Refund, riwayat refund dan bantuan | API pengajuan dengan alasan/foto/rekening tujuan, status, riwayat, bukti transfer balik, konfirmasi dana, dan eskalasi sudah tersedia. Belum diintegrasikan atau diuji bersama UI; gambar masih di penyimpanan lokal pengembangan. |
| Transfer setelah pesanan kedaluwarsa | Sesuai keputusan Rampan, backend menyediakan laporan terpisah dengan bukti untuk pemeriksaan dan tindak lanjut admin; laporan bukan bukti pembayaran terverifikasi dan tidak mengembalikan dana otomatis. UI untuk laporan ini belum terintegrasi. |

## Petani

| Tampilan | Kondisi backend dan tindak lanjut |
|---|---|
| Beranda/ringkasan toko | Data produk dan daftar pesanan sebagian tersedia melalui API; endpoint ringkasan dashboard dengan hitungan pesanan/jadwal/bukti belum ada. |
| Stok, draft, dan produk | CRUD produk, draft, publikasi, stok fisik/reservasi tersedia. Upload foto produk dan URL sementara telah tersedia melalui storage F1 milik Gibran; integrasi UI belum diuji. |
| Pesanan masuk dan verifikasi bukti | Daftar/detail, respons, daftar dan gambar bukti, keputusan terima/tolak, fulfillment, ringkasan tujuan pembayaran snapshot, dan metadata bukti baru tersedia melalui API. |
| Usulan jadwal baru | Belum tersedia dan perlu koordinasi F3. Jangan mengubah snapshot jadwal atau kontrak checkout secara sepihak. |
| Refund dan transfer balik | API respons petani, nominal penuh, rekening tujuan, bukti privat, dan konfirmasi pembeli sudah tersedia. Transfer tetap dilakukan di luar aplikasi dan UI belum terintegrasi. |
| Profil dan layanan toko | API profil toko, lokasi, area, slot, dan pengajuan KTP dummy sudah tersedia; KTP asli tidak didukung. Format data profil perlu dikoordinasikan dengan Gibran dan Android. |
| Rekening bank dan QRIS | API tambah, detail privat, ubah, dan hapus rekening bank tersedia dengan field sesuai form: bank, nomor rekening, nama pemilik. Upload, lihat, ganti, dan hapus QRIS tersedia di penyimpanan lokal pengembangan; adapter penyimpanan produksi belum ada. Integrasi UI Android belum diuji. |

## Keputusan dan batasan

- Desain pembayaran memperlihatkan rekening dan QRIS setelah pesanan diterima. Keputusan Rampan: pilih metode setelah petani menerima, dengan salinan tujuan yang diambil saat checkout.
- Pilihan dapat diganti sebelum bukti pertama dikirim. Setelah bukti dikirim, metode terkunci untuk menjaga konsistensi bukti dan tujuan pembayaran; perubahan metode setelah penolakan perlu keputusan bisnis tersendiri.
- Pesanan lama tanpa snapshot tujuan tidak diisi otomatis dengan rekening yang mungkin sudah berubah.
- Upload bukti dan QRIS saat ini menggunakan filesystem lokal privat untuk pengembangan, bukan MinIO/S3 produksi. Pengujian UI Android dilakukan setelah integrasi UI tersedia.
- Area F1–F4 milik Gibran. Perubahan tampilan yang memerlukan perubahan logika katalog, keranjang, jadwal atau perhitungan checkout harus dibahas bersama Gibran.

## Pengguna dan status akun admin

Untuk layar admin, `GET /api/v1/admin/users/{id}` menyediakan identitas akun, nama pemilik/toko/lokasi dan status KTP jika tersedia, alasan suspend saat ini, jumlah refund aktif, dan paling banyak lima refund aktif terbaru. Suspend memerlukan alasan; catatan pemulihan opsional. `ownerName` bisa kosong bagi akun lama yang belum melengkapi profil. Tampilan Android dan integrasinya belum diuji.
