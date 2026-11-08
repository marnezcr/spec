# Spectro Online — proyek APK (WebView murni, tanpa dependensi)

Tanpa Capacitor, tanpa npm, tanpa AndroidX. Folder `app/src/main/assets/public` sudah berisi
halaman yang siap pakai, jadi proyek ini langsung bisa dibuild.

## Build di NP Manager
1. Salin folder `SpectroOnline` ke HP, buka di NP Manager (buka sebagai proyek / pilih folder).
2. Pilih build/compile proyek Android. Hasilnya APK; NP Manager menandatanganinya sendiri.
   Kalau NP Manager hanya mau proyek dengan `AndroidManifest.xml` + `res` + `java` + `assets`,
   pakai isi `app/src/main/` (strukturnya sudah standar).
3. Alternatif: AIDE, Android Studio (`Build > Build APK`), atau `gradle assembleDebug`.

Catatan: proyek memakai minSdk 24, targetSdk/compileSdk 34. Jika tool-mu punya android.jar lebih tua
dan error, turunkan `compileSdk`/`targetSdk` ke 33 (kode tidak memakai API di atas itu).

## Build lewat GitHub
Upload semua isi folder ini (termasuk `.github`), lalu Actions -> Build APK -> Run workflow.
Artifact `SpectroOnline-apk` berisi `app-debug.apk`.

## Mengubah isi aplikasi
- Cara cepat (tanpa Node): edit langsung file di `app/src/main/assets/public/`.
  Kalau mengganti HTML dari sumber baru, sisipkan lagi shim (lihat di bawah) atau pakai cara Node.
- Cara rapi (butuh Node): ganti file di `src/`, jalankan `node tools/prepare.js`.
  Skrip itu menyisipkan `tools/bridge-polyfill.js` + `tools/android-shim.js` ke tiap HTML
  dan menambal: cetak -> dialog cetak Android, unduh file -> folder Download,
  `produk-kom.json`/`copas.json` -> penyimpanan privat app + ambil dari repo (`dataBaseUrl`).

## Pengaturan
- Nama/ID app: `app/src/main/AndroidManifest.xml` (label), `app/build.gradle` (applicationId, versi).
  Jika mengganti ID/package, ubah juga `package` di Manifest dan folder/`package` di file Java.
- `apk.config.json`: `dataBaseUrl`, `publicUrl`, `bundleData` (dipakai `tools/prepare.js`).

## Struktur
- `MainActivity.java` — WebView, menyajikan assets lewat `https://localhost/`, dialog alert/confirm/prompt,
  pemilih file (impor .json), tombol Kembali, link luar dibuka di browser.
- `Bridge.java` — simpan ke Download, penyimpanan privat, cetak, notifikasi, clipboard.
