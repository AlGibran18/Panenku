package id.panenku.app.ui.admin

import id.panenku.app.ui.components.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.panenku.app.data.*
import id.panenku.app.ui.theme.PanenkuTheme


@Composable
fun AdminHomeScreen(onNavigate: (String) -> Unit, onLogout: () -> Unit) {
    Page("Ringkasan admin", "Admin Maya · data contoh", admin = true) {
        item { AdaptiveMetrics(listOf("Verifikasi petani" to "8", "Tiket eskalasi" to "3")) { onNavigate(if (it == 0) "admin-verification" else "admin-disputes") } }
        item { Panel("Prioritas hari ini") {
            StatusBadge("Perlu tindakan", Tone.WARNING)
            MenuRow("Tinjau #VER-019", "Petani Contoh · Menunggu pemeriksaan", Icons.Outlined.VerifiedUser) { onNavigate("admin-verification") }
            MenuRow("Dana refund belum diterima", "#TKT-026 · #RF-002 · Buka kronologi", Icons.Outlined.ReportProblem) { onNavigate("admin-disputes") }
            MenuRow("2 produk dilaporkan", "Satu laporan kategori tidak sesuai", Icons.Outlined.Inventory2) { onNavigate("admin-moderation") }
            MenuRow("1 akun perlu ditinjau", "Laporan transaksi berulang", Icons.Outlined.PeopleOutline) { onNavigate("admin-users") }
        } }
        item { Panel("Ruang administrasi") {
            MenuRow("Pengguna & status akun", "Cari pengguna, suspensi / pulihkan", Icons.Outlined.PeopleOutline) { onNavigate("admin-users") }
            MenuRow("Komoditas", "Kelola kategori aktif dan nonaktif", Icons.Outlined.Category) { onNavigate("admin-commodities") }
            MenuRow("Moderasi produk", "Tinjau laporan dan alasan perbaikan", Icons.Outlined.Inventory2) { onNavigate("admin-moderation") }
            MenuRow("Pantau seluruh pesanan", "Status, pembayaran, jadwal, dan aktor", Icons.AutoMirrored.Outlined.ReceiptLong) { onNavigate("admin-orders") }
            MenuRow("Pengaturan admin", "Limit transaksi & kontak WhatsApp CS", Icons.Outlined.Settings) { onNavigate("admin-settings") }
        } }
        item { Panel("Keputusan terbaru · Audit contoh") {
            Timeline(listOf("#VER-018 ditolak" to "22 Juni, 09.00 · Maya · Foto identitas terpotong", "#PRD-041 dinonaktifkan" to "21 Juni, 15.10 · Maya · Komoditas tidak sesuai"))
            Text("Audit server, hak akses, dan waktu tindakan akan disediakan backend.", style = MaterialTheme.typography.bodySmall)
        } }
        item { SecondaryButton("Keluar dari akun admin", onLogout) }
    }
}

@Composable
fun AdminVerificationScreen(decisions: Map<String, String>, onBack: () -> Unit, onDecision: (String, String, String, Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Menunggu") }
    var selected by rememberSaveable { mutableStateOf("VER-019") }
    val queue = listOf("VER-019" to "Kebun Contoh", "VER-020" to "Tani Demo")
    val filtered = queue.filter { (id, name) -> ("$id $name").contains(query, true) && when (filter) {
        "Disetujui" -> decisions[id]?.startsWith("Disetujui") == true
        "Ditolak" -> decisions[id]?.startsWith("Ditolak") == true
        else -> decisions[id] == null
    } }
    val review = filtered.find { it.first == selected } ?: filtered.firstOrNull()
    var reason by rememberSaveable(review?.first) { mutableStateOf("") }
    var checks by rememberSaveable(review?.first) { mutableStateOf(false) }
    Page("Verifikasi petani", "Pengajuan menunggu pemeriksaan · demo", onBack, true) {
        item { SearchField(query, { query = it }, "Cari nama toko / ID pengajuan") }
        item { FilterChips(listOf("Menunggu", "Disetujui", "Ditolak"), filter, { filter = it }) }
        if (filtered.isEmpty()) item { EmptyState("Tidak ada pengajuan pada filter ini") }
        items(filtered, key = { it.first }) { (id, name) -> Panel { MenuRow("#$id · $name", if (review?.first == id) "Dipilih untuk ditinjau" else "Ketuk untuk meninjau", Icons.Outlined.Description) { selected = id } } }
        if (review != null) {
            val id = review.first
            item { Panel("Tinjau #$id") {
                StatusBadge(decisions[id] ?: "Menunggu keputusan admin", Tone.WARNING)
                KeyValue("Pemohon contoh", "Petani Contoh"); KeyValue("Nama toko", review.second); KeyValue("Lokasi", "Lowokwaru, Malang"); KeyValue("Email contoh", "petani@example.com")
                Notice("KTP DUMMY · CONTOH", "Nama: Petani Contoh · NIK: DUMMY-0000. Bukan dokumen identitas asli.", Tone.INFO)
                EvidenceCard("Dokumen ktp-dummy-${id.takeLast(3)}.jpg")
            } }
            item { Panel("Pemeriksaan & keputusan") {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checks, { checks = it }); Text("Data toko, lokasi, dan keterbacaan dokumen sudah diperiksa.", style = MaterialTheme.typography.bodyMedium) }
                PrimaryButton("Setujui verifikasi petani", { onDecision(id, "Disetujui", "Pemeriksaan demo lengkap", false) }, checks && decisions[id] == null)
                Field("Alasan penolakan (wajib)", reason, { reason = it }, "Jelaskan bagian data yang harus diperbaiki", multiline = true)
                SecondaryButton("Tolak dengan alasan", { onDecision(id, "Ditolak", reason, true) }, enabled = decisions[id] == null)
            } }
            item { Notice("Keputusan dapat ditelusuri", "Persetujuan membuka syarat verifikasi untuk penerbitan produk. Penolakan memerlukan alasan perbaikan.") }
        }
    }
}

@Composable
fun AdminUsersScreen(decisions: Map<String, String>, onBack: () -> Unit, onDecision: (String, String, String, Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Semua") }
    var selected by rememberSaveable { mutableStateOf("USR-012") }
    val users = listOf(Triple("USR-012", "Darto Sobianto", "Petani"), Triple("USR-021", "Rina Catering", "Pembeli"), Triple("USR-034", "Tani Contoh", "Petani"))
    fun status(id: String) = decisions[id] ?: if (id == "USR-034") "Disuspensi · 21 Juni, 10.00" else "Aktif"
    val filtered = users.filter { (id, name, role) -> "$id $name".contains(query, true) && (filter == "Semua" || role == filter || (filter == "Disuspensi" && status(id).startsWith("Disuspensi"))) }
    val user = filtered.find { it.first == selected } ?: filtered.firstOrNull()
    var reason by rememberSaveable(user?.first) { mutableStateOf("") }
    Page("Pengguna & status akun", "Kelola akses tanpa menghapus riwayat transaksi", onBack, true) {
        item { SearchField(query, { query = it }, "Cari nama / email / ID pengguna") }
        item { FilterChips(listOf("Semua", "Petani", "Pembeli", "Disuspensi"), filter, { filter = it }) }
        items(filtered, key = { it.first }) { (id, name, role) -> Panel { MenuRow("$name · #$id", "$role · ${status(id)}", Icons.Outlined.PersonOutline) { selected = id } } }
        if (filtered.isEmpty()) item { EmptyState() }
        if (user != null) item { Panel("Detail #${user.first}") {
            StatusBadge(status(user.first), if (status(user.first).startsWith("Disuspensi")) Tone.WARNING else Tone.SUCCESS)
            KeyValue("Nama pengguna", user.second); KeyValue("Tipe", user.third); KeyValue("Lokasi", "Lowokwaru, Malang")
            Field("Alasan tindakan (wajib)", reason, { reason = it }, "Tulis pelanggaran atau hasil pemeriksaan pemulihan", multiline = true)
            if (status(user.first).startsWith("Disuspensi")) PrimaryButton("Tinjau & pulihkan ${user.first}", { onDecision(user.first, "Aktif · Dipulihkan", reason, true) })
            else PrimaryButton("Suspensi akun dengan alasan", { onDecision(user.first, "Disuspensi", reason, true) })
            Text("Suspensi membatasi produk dan transaksi baru. Pesanan serta refund lama tetap bisa ditangani.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Notice("Semua perubahan masuk log", "Demo mencatat keputusan di memori. Pencatatan audit permanen memerlukan backend.") }
    }
}

@Composable
fun AdminSettingsScreen(values: Map<String, String>, onBack: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    var limit by rememberSaveable { mutableStateOf(values["Limit"] ?: "10000000") }
    var phone by rememberSaveable { mutableStateOf(values["WhatsApp"] ?: "+6280000000000") }
    Page("Pengaturan admin", "Aturan transaksi & kanal dukungan", onBack, true) {
        item { Panel("Batas awal transaksi") {
            StatusBadge("Berlaku untuk checkout baru")
            Field("Batas total per pesanan (Rp)", limit, { limit = it.filter(Char::isDigit).take(12) }, keyboard = KeyboardType.Number)
            KeyValue("Yang dihitung", "Σ kg × Rp/kg + ongkir")
            Notice("Validasi saat disimpan", "Ambil seluruh ongkir dan nilai produk dalam total. Pesanan lama memakai batas saat transaksi dibuat.")
        } }
        item { Panel("WhatsApp customer service") {
            Field("Nomor WhatsApp CS", phone, { phone = it }, keyboard = KeyboardType.Phone)
            Text("Contoh nomor tersamarkan. Ganti dengan nomor CS resmi sebelum integrasi layanan.", style = MaterialTheme.typography.bodySmall)
            Text("Pesan aman untuk CS", style = MaterialTheme.typography.labelLarge)
            Text("ID tiket, ID pesanan/refund, dan ringkasan masalah. Jangan menyertakan rekening, KTP, atau alamat pribadi.", style = MaterialTheme.typography.bodySmall)
        } }
        item { PrimaryButton("Simpan pengaturan admin", { onSave(mapOf("Limit" to limit, "WhatsApp" to phone)) }, (limit.toLongOrNull() ?: 0) > 0 && phone.startsWith("+") && phone.drop(1).all(Char::isDigit) && phone.length >= 10) }
        item { Panel("Konteks audit") { KeyValue("Versi pengaturan", "Konfigurasi awal · V2"); KeyValue("Aktor perubahan", "Admin Maya"); Text("Penyimpanan saat ini hanya di memori demo.", style = MaterialTheme.typography.bodySmall) } }
    }
}


@Preview(showBackground = true, widthDp = 375, heightDp = 1100)
@Composable private fun AdminPreview() { PanenkuTheme { AdminHomeScreen({}, {}) } }
