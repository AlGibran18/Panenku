# Usulan integrasi F6 dan checkout

Dokumen ini mencatat rancangan integrasi F6 dan batasan yang masih perlu diselesaikan.

## Tujuan

Pembeli memilih metode pembayaran setelah petani menerima pesanan. Rincian tujuan pembayaran yang ditunjukkan kepada pembeli harus sama dengan salinan saat checkout, meskipun petani kemudian mengubah metode pembayaran. Pembayaran tetap dilakukan di luar aplikasi dan diverifikasi manual oleh petani.

## Kontrak checkout yang diterapkan

1. Checkout baru tidak menerima pilihan metode; setelah petani menerima, pembeli melihat `payment-options` pesanan dan memilih melalui `payment-destination`.
2. Dalam transaksi checkout, backend menyimpan salinan metode yang lengkap dan tersedia milik petani pesanan. Gambar QRIS disalin secara privat agar tetap tersedia meskipun petani mengubah atau menghapus file asal.
3. Endpoint pesanan yang berotorisasi menyediakan rincian tujuan pembayaran dari salinan tersebut. Jangan mengekspos data tersebut pada katalog publik.
4. Retry checkout lama dengan key dan payload lama tetap menggunakan hash semula. Checkout baru yang membawa field pilihan lama ditolak; pilihan sebelum bukti pertama masih dapat diganti.
5. Pesanan lama tanpa snapshot belum memiliki tujuan pembayaran; tidak ada backfill otomatis yang menebak data dari profil terkini.

## Cadangan stok dan batas waktu

- Checkout saat ini memeriksa stok dan membuat order serta reservasi dalam satu transaksi. Checkout gagal tidak menghasilkan order dan reservasi parsial.
- Setelah petani menerima pesanan, pembeli memiliki jendela waktu untuk mengunggah bukti. Scheduler melepaskan reservasi order yang memenuhi syarat kedaluwarsa. Bukti yang sedang diverifikasi tidak di-expire otomatis menurut rancangan saat ini.
- Transfer bank/QRIS terjadi di luar backend. Sekalipun aplikasi menunjukkan status dan batas waktu terkini, sistem tidak bisa mencegah transfer yang dilakukan setelah reservasi dilepas. Untuk pesanan kedaluwarsa yang belum terverifikasi tersedia laporan terpisah agar admin memeriksa bukti dan mencatat tindak lanjut; laporan tidak mengubah status pembayaran atau otomatis mengembalikan dana.
- Backend harus memeriksa ulang status dan waktu di bawah lock saat bukti dikirim, termasuk saat upload dan scheduler berjalan bersamaan.

## Pengujian integrasi yang diperlukan

- Dua checkout bersamaan atas stok terbatas; tidak terjadi oversell.
- Snapshot tidak berubah ketika rekening atau QRIS petani diubah, dan hanya pihak yang berwenang dapat melihatnya.
- Replay checkout dengan key dan payload yang sama menghasilkan pesanan yang sama; perubahan pilihan setelah bukti dikirim ditolak.
- Pengajuan bukti, expiry, keputusan petani, dan retry bersamaan tidak menghasilkan keputusan ganda atau perubahan stok yang tidak konsisten.
- Uji keamanan file privat, kegagalan penyimpanan, rollback, dan alur pembayaran ditolak lalu dikirim ulang.

## Prototipe penyimpanan lokal

`LocalPrivateImageStore` digunakan untuk mengunggah dan membaca gambar QRIS di filesystem POSIX privat (misalnya macOS/Linux). Petani aktif dapat mengunggah dan melihat QRIS miliknya; checkout menyimpan salinan gambar di snapshot pesanan yang hanya dapat diakses pembeli pemilik setelah petani menerima pesanan. `FileStorage` lama masih mengembalikan 503. Windows dan penyimpanan produksi MinIO/S3 belum didukung oleh prototipe ini.

Komponen penyimpanan tidak melakukan autentikasi atau otorisasi. Service/endpoint yang akan memanggil `read` harus lebih dahulu memverifikasi hak pembeli, petani pemilik pesanan, atau admin yang berwenang. Integrasi juga harus merencanakan pembersihan file apabila penyimpanan berhasil tetapi transaksi database dibatalkan. Jangan meletakkan direktori tersebut di dalam direktori web publik.

## Keputusan yang masih terbuka

- Cara menangani pesanan lama tanpa snapshot dan migrasi klien Android.
- Ubah/hapus metode QRIS dan penyimpanan produksi MinIO/S3.
- Penyelesaian dana nyata setelah laporan transfer terlambat tetap dilakukan manual; laporan untuk pesanan ditolak belum tersedia.
- Penyesuaian aplikasi Android dengan kontrak checkout baru.
