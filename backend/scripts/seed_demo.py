#!/usr/bin/env python3
"""Explicit, local-only fixture for a dedicated empty `panenku_demo` database.

This is deliberately separate from Flyway and normal application startup. It
never resets data and never targets a remote or non-demo database.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "demo-assets" / "foto-dummy"


def fail(message):
    raise RuntimeError(message)


def psql(url, sql, capture=False):
    result = subprocess.run(["psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "--dbname", url],
                            input=sql, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # Do not echo a query that may contain credentials or large image payloads.
        fail("PostgreSQL menolak operasi demo. Pastikan database demo kosong, migrasi selesai, dan koneksi benar.")
    return result.stdout.strip() if capture else None


def request_hash(values):
    return hashlib.sha256(json.dumps(values, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def main():
    if os.environ.get("PANENKU_DEMO_CONFIRM") != "YES":
        fail("Set PANENKU_DEMO_CONFIRM=YES hanya untuk database demo lokal yang kosong.")
    url = os.environ.get("PANENKU_DEMO_DATABASE_URL", "")
    parsed = urlsplit(url)
    if (parsed.scheme not in ("postgres", "postgresql") or parsed.hostname not in ("localhost", "127.0.0.1", "::1")
            or parsed.path != "/panenku_demo" or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment):
        fail("PANENKU_DEMO_DATABASE_URL harus menunjuk localhost ke database panenku_demo tanpa kredensial di URL.")
    password = os.environ.get("PANENKU_DEMO_PASSWORD", "")
    if not 12 <= len(password.encode("utf-8")) <= 72 or "\n" in password or "\r" in password:
        fail("PANENKU_DEMO_PASSWORD harus 12–72 byte, tanpa baris baru, dan hanya untuk demo.")
    folder_raw = os.environ.get("PANENKU_DEMO_STORAGE_DIR", "")
    if not folder_raw or not Path(folder_raw).is_absolute() or Path(folder_raw).name != "panenku-demo-storage":
        fail("PANENKU_DEMO_STORAGE_DIR harus path absolut bernama panenku-demo-storage.")
    folder = Path(folder_raw)
    if folder.is_symlink():
        fail("Folder penyimpanan demo tidak boleh berupa symlink.")
    if not shutil.which("psql") or not shutil.which("htpasswd"):
        fail("Perintah psql dan htpasswd diperlukan untuk setup demo.")
    if psql(url, "SELECT current_database();", True) != "panenku_demo":
        fail("Database bukan panenku_demo.")
    ready = psql(url, "SELECT count(*) FROM flyway_schema_history WHERE version='21' AND success;", True)
    if ready != "1":
        fail("Jalankan migrasi Flyway sampai V21 pada database demo terlebih dahulu.")
    state = psql(url, "SELECT to_regclass('public.panenku_demo_seed_marker') IS NOT NULL;", True)
    if state == "t":
        print("Data demo sudah pernah dibuat; tidak ada data yang diubah.")
        return
    if psql(url, "SELECT (SELECT count(*) FROM users)+(SELECT count(*) FROM products)+(SELECT count(*) FROM orders)+(SELECT count(*) FROM commodities);", True) != "0":
        fail("Database demo tidak kosong. Seeder tidak mengubah database yang sudah berisi data.")
    if folder.exists() and (not folder.is_dir() or any(folder.iterdir())):
        fail("Folder penyimpanan demo harus kosong pada setup pertama.")
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    if folder.stat().st_mode & 0o077:
        fail("Folder penyimpanan demo harus privat (chmod 700).")
    result = subprocess.run(["htpasswd", "-niBC", "10", "dummy"], input=password + "\n", text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    password_hash = result.stdout.strip().partition(":")[2]
    if result.returncode or not re.fullmatch(r"\$2[aby]\$10\$[./A-Za-z0-9]{53}", password_hash):
        fail("Gagal membuat hash kata sandi demo.")

    files = {"qris": "qris-asep-laris.png", "payment": "bukti-pembayaran-mok.png", "refund": "sawi-rusak-refund.png"}
    blobs = {key: (ASSETS / name).read_bytes() for key, name in files.items()}
    if any(not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data) > 5242880 for data in blobs.values()):
        fail("Gambar demo harus PNG dan maksimal 5 MB.")
    keys = {key: f"{uuid.uuid4()}.png" for key in files}
    created = []
    try:
        for key, data in blobs.items():
            path = folder / keys[key]
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as handle:
                handle.write(data)
            created.append(path)
    except Exception:
        for path in created:
            path.unlink(missing_ok=True)
        raise

    buyer = str(uuid.uuid4()); farmer = str(uuid.uuid4()); admin = str(uuid.uuid4())
    commodity = str(uuid.uuid4()); product = str(uuid.uuid4()); method = str(uuid.uuid4())
    pending = str(uuid.uuid4()); complete = str(uuid.uuid4()); refund = str(uuid.uuid4())
    proof_pending = str(uuid.uuid4()); proof_paid = str(uuid.uuid4())
    qris_base64 = base64.b64encode(blobs["qris"]).decode("ascii")
    payment_sha = hashlib.sha256(blobs["payment"]).hexdigest()
    refund_sha = hashlib.sha256(blobs["refund"]).hexdigest()
    pending_hash = request_hash([pending, payment_sha]); paid_hash = request_hash([complete, payment_sha])
    refund_hash = request_hash([complete, "Sawi rusak, sobek, dan tidak segar", "bank", "Bank Dummy", "00000001", "Mok", refund_sha])
    zeros = "0" * 64
    # Every interpolated value below is generated locally or an explicitly validated hash/UUID/base64.
    sql = f"""
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('panenku-demo-seed-v1'));
DO $$ BEGIN IF (SELECT count(*) FROM users)+(SELECT count(*) FROM products)+(SELECT count(*) FROM orders)+(SELECT count(*) FROM commodities) <> 0
  THEN RAISE EXCEPTION 'Database demo tidak kosong'; END IF; END $$;
CREATE TABLE panenku_demo_seed_marker (id SMALLINT PRIMARY KEY CHECK(id=1), created_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO users(id,email,password_hash,role) VALUES
 ('{buyer}','mok@example.test','{password_hash}','pembeli'),
 ('{farmer}','asep@example.test','{password_hash}','petani'),
 ('{admin}','admin@example.test','{password_hash}','admin');
UPDATE users SET display_name='Admin Demo' WHERE id='{admin}';
INSERT INTO buyer_profiles(id,full_name) VALUES ('{buyer}','Mok');
INSERT INTO farmer_profiles(id,store_name,owner_name,pickup_location,service_area,time_slots,ktp_status)
 VALUES ('{farmer}','Asep Laris','Asep','Lokasi demo','kepanjen','08:00-10:00','disetujui');
INSERT INTO commodities(id,name) VALUES ('{commodity}','Sawi');
INSERT INTO products(id,farmer_id,commodity_id,name,description,photo_url,price_per_kg,stock_physical,stock_reserved,ready_date,publication_status)
 VALUES ('{product}','{farmer}','{commodity}','Sawi Segar','Produk contoh',
 'demo-assets/foto-dummy/produk-sawi-segar.png',25000,9,1,(now() AT TIME ZONE 'Asia/Jakarta')::date,'aktif');
INSERT INTO farmer_payment_methods(id,farmer_id,method_type,qris_image_path,account_holder_name)
 VALUES ('{method}','{farmer}','qris','{keys['qris']}','Asep Laris');
INSERT INTO orders(id,buyer_id,farmer_id,fulfillment_method,delivery_fee,subtotal_items,total_payment,transaction_limit_applied,
 order_status,payment_status,scheduled_date,scheduled_time_slot,pickup_location_snapshot,received_at,reserved_at,expires_at,created_at,updated_at,idempotency_key,request_hash)
 VALUES ('{pending}','{buyer}','{farmer}','pickup',0,25000,25000,10000000,'verifikasi_pembayaran','menunggu_verifikasi',
 (now() AT TIME ZONE 'Asia/Jakarta')::date + 1,'08:00-10:00','Lokasi demo',NULL,now()-interval '15 minutes',now()+interval '24 hours',now()-interval '15 minutes',now(),'demo-pending','{zeros}'),
 ('{complete}','{buyer}','{farmer}','pickup',0,25000,25000,10000000,'selesai','terverifikasi',
 (now() AT TIME ZONE 'Asia/Jakarta')::date,'08:00-10:00','Lokasi demo',now()-interval '1 hour',now()-interval '3 hours',now()+interval '24 hours',now()-interval '3 hours',now()-interval '1 hour','demo-complete','{zeros}');
INSERT INTO order_items(id,order_id,product_id,product_name_snapshot,commodity_snapshot,ready_date_snapshot,quantity_kg,price_per_kg_snapshot,item_subtotal)
 VALUES ('{uuid.uuid4()}','{pending}','{product}','Sawi Segar','Sawi',(now() AT TIME ZONE 'Asia/Jakarta')::date,1,25000,25000),
 ('{uuid.uuid4()}','{complete}','{product}','Sawi Segar','Sawi',(now() AT TIME ZONE 'Asia/Jakarta')::date,1,25000,25000);
INSERT INTO stock_reservations(id,product_id,order_id,quantity_kg,status,reserved_at,fulfilled_at)
 VALUES ('{uuid.uuid4()}','{product}','{pending}',1,'active',now()-interval '15 minutes',NULL),
 ('{uuid.uuid4()}','{product}','{complete}',1,'fulfilled',now()-interval '3 hours',now()-interval '1 hour 30 minutes');
INSERT INTO order_payment_options(order_id,method_id,method_type,account_holder_name,qris_image,qris_content_type)
 VALUES ('{pending}','{method}','qris','Asep Laris',decode('{qris_base64}','base64'),'image/png'),
 ('{complete}','{method}','qris','Asep Laris',decode('{qris_base64}','base64'),'image/png');
INSERT INTO order_payment_selections(order_id,method_id) VALUES ('{pending}','{method}'),('{complete}','{method}');
INSERT INTO order_payment_snapshots(order_id,method_id,method_type,account_holder_name,qris_image,qris_content_type)
 SELECT order_id,method_id,method_type,account_holder_name,qris_image,qris_content_type FROM order_payment_options WHERE order_id IN ('{pending}','{complete}');
INSERT INTO payment_submissions(id,order_id,buyer_id,idempotency_key,request_hash,expected_amount,proof_image_path,status,submitted_at,
 farmer_decision,farmer_decided_at,original_file_name,file_size_bytes)
 VALUES ('{proof_pending}','{pending}','{buyer}','demo-proof-pending','{pending_hash}',25000,'{keys['payment']}','menunggu_verifikasi',now()-interval '5 minutes',NULL,NULL,'bukti-pembayaran-mok.png',{len(blobs['payment'])}),
 ('{proof_paid}','{complete}','{buyer}','demo-proof-paid','{paid_hash}',25000,'{keys['payment']}','terverifikasi',now()-interval '2 hours 30 minutes','terima',now()-interval '2 hours 20 minutes','bukti-pembayaran-mok.png',{len(blobs['payment'])});
INSERT INTO order_status_history(id,order_id,from_status,to_status,actor_id,actor_role,changed_at,note)
 VALUES ('{uuid.uuid4()}','{pending}',NULL,'menunggu_petani','{buyer}','pembeli',now()-interval '15 minutes','Data demo'),
 ('{uuid.uuid4()}','{pending}','menunggu_petani','menunggu_pembayaran','{farmer}','petani',now()-interval '10 minutes','Data demo'),
 ('{uuid.uuid4()}','{pending}','menunggu_pembayaran','verifikasi_pembayaran','{buyer}','pembeli',now()-interval '5 minutes','Data demo'),
 ('{uuid.uuid4()}','{complete}',NULL,'menunggu_petani','{buyer}','pembeli',now()-interval '3 hours','Data demo'),
 ('{uuid.uuid4()}','{complete}','menunggu_petani','menunggu_pembayaran','{farmer}','petani',now()-interval '2 hours 45 minutes','Data demo'),
 ('{uuid.uuid4()}','{complete}','menunggu_pembayaran','verifikasi_pembayaran','{buyer}','pembeli',now()-interval '2 hours 30 minutes','Data demo'),
 ('{uuid.uuid4()}','{complete}','verifikasi_pembayaran','diproses','{farmer}','petani',now()-interval '2 hours 20 minutes','Data demo'),
 ('{uuid.uuid4()}','{complete}','diproses','siap_diambil','{farmer}','petani',now()-interval '2 hours','Data demo'),
 ('{uuid.uuid4()}','{complete}','siap_diambil','menunggu_penerimaan','{farmer}','petani',now()-interval '1 hour 30 minutes','Data demo'),
 ('{uuid.uuid4()}','{complete}','menunggu_penerimaan','selesai','{buyer}','pembeli',now()-interval '1 hour','Data demo');
INSERT INTO refunds(id,order_id,buyer_id,idempotency_key,request_hash,reason,evidence_path,refund_amount,destination_type,destination_provider,destination_account,destination_name,status,created_at,updated_at)
 VALUES ('{refund}','{complete}','{buyer}','demo-refund','{refund_hash}','Sawi rusak, sobek, dan tidak segar','{keys['refund']}',25000,'bank','Bank Dummy','00000001','Mok','diajukan',now(),now());
INSERT INTO refund_history(id,refund_id,from_status,to_status,actor_id,actor_role,note,changed_at)
 VALUES ('{uuid.uuid4()}','{refund}',NULL,'diajukan','{buyer}','pembeli','Data demo',now());
INSERT INTO panenku_demo_seed_marker(id) VALUES (1);
COMMIT;
"""
    # On uncertain DB failure retain the files: the commit might have succeeded.
    psql(url, sql)
    print("Data demo dibuat: Asep Laris, Mok, admin, sawi, dua pesanan, bukti, dan satu refund.")
    print("Folder foto privat: " + str(folder))
    print("Gunakan sandi demo yang sama dengan PANENKU_DEMO_PASSWORD; jangan gunakan di produksi.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError) as exc:
        print("Setup demo gagal: " + str(exc), file=sys.stderr)
        sys.exit(1)
