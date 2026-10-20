# tv-screentime

Batasi waktu nonton balita di Android TV — dikendalikan dari HP orang tua lewat Wi-Fi rumah, tanpa server, tanpa akun, tanpa langganan.

> **English summary:** an open-source screen-time limiter for Android TV. A custom launcher + full-screen "time's up" overlay on the TV, controlled from parents' phones over the local network (HTTP/WebSocket + mDNS, QR pairing). Whitelisted YouTube channels play through the official IFrame Player. No cloud, no accounts. UI in Indonesian and English.

Diuji di **Xiaomi Mi TV 4A (2018, Android TV 9, RAM 1 GB)**. Target berikutnya: Coocaa 32" Android TV.

---

## Fitur

### Di TV (`tv-app`)
- **Launcher sendiri** (layar Home) bergaya Apple TV: latar gelap, "top shelf" buram dari app yang dipilih, satu baris tile besar, navigasi D-pad. Hanya menampilkan:
  - **Video** — video terbaru dari channel YouTube yang diizinkan orang tua,
  - **TV Digital** (tuner bawaan),
  - app yang diizinkan orang tua.
- **Overlay "Waktu habis"** layar penuh di atas semua app, termasuk TV Digital. Semua tombol remote ditahan dan suara dibisukan selama terkunci.
- **Timer berjalan di TV**, tetap jalan tanpa internet. Hanya berkurang saat layar menyala. Status tersimpan, aktif lagi setelah reboot, dan berjalan sebagai foreground service.
- **Mode:** kunci total, bebas (tanpa batas), timer, tambah/kurangi waktu.
- **Server LAN** (Ktor, HTTP + WebSocket, port `8787`) dan pengumuman diri via **mDNS/NSD** (`_tvscreentime._tcp`).
- **Bar atas launcher** (tekan ▲): Sumber input (HDMI/AV/tuner), Jaringan, Setelan TV, dan pairing HP.
- **Pairing via QR** (alamat + kode sekali pakai). Banyak HP bisa terhubung ke satu TV; menu **Perangkat terhubung** di TV untuk mencabut akses.
- **Video:** daftar dari RSS YouTube (`youtube.com/feeds/videos.xml?channel_id=…`), diputar dengan **YouTube IFrame Player resmi** di WebView. Saat video selesai, TV kembali ke daftar sendiri — bukan ke rekomendasi YouTube. Shorts disaring. Tidak ada unduhan video (patuh ToS YouTube).

### Di HP orang tua (`parent-app`)
- Kelola **banyak TV**: tambah lewat scan QR, beri nama, status live tiap TV (sisa menit / terkunci / bebas / offline).
- Kontrol **per TV atau semua TV**: kunci, buka, set timer (15/30/60/90 atau bebas), +15 / −15 menit.
- Atur **app yang boleh dibuka** di TV.
- Kelola **channel YouTube** (link channel, `@handle`, atau ID) — bisa untuk satu TV atau semua TV.
- Lihat dan cabut **HP lain** yang terhubung.
- **Bahasa Indonesia / English** — pilihan bahasa di HP ikut dikirim ke semua TV.
- Jika IP TV berubah (DHCP), app menemukannya lagi otomatis lewat mDNS.

---

## Cara kerja singkat

```
 HP orang tua ──Wi-Fi──▶  TV: ScreenTimeService (foreground)
   (parent-app)             ├─ LanServer   HTTP + WebSocket :8787, mDNS
                            ├─ timer + status di SharedPreferences
                            └─ overlay TYPE_APPLICATION_OVERLAY saat terkunci
                          TV: LauncherActivity (HOME) → Video / TV Digital / app diizinkan
```

Semua state ada di TV. HP hanya mengirim perintah dan menerima status live lewat WebSocket. Tidak ada data yang keluar dari jaringan rumah, kecuali TV mengambil RSS/thumbnail/video dari YouTube.

---

## Kebutuhan

- Mac/Linux/Windows dengan **JDK 17+** dan **Android SDK** (Android Studio sudah cukup).
- `adb` (ada di `Android/sdk/platform-tools`).
- TV Android TV 9+ dengan **Developer options** aktif.
- HP Android 9+ dengan Google Play Services (untuk scanner QR).
- TV dan HP di **Wi-Fi yang sama**.

## Build

```sh
./gradlew :tv-app:assembleDebug       # → tv-app/build/outputs/apk/debug/tv-app-debug.apk
./gradlew :parent-app:assembleDebug   # → parent-app/build/outputs/apk/debug/parent-app-debug.apk
```

---

## Pasang di TV (langkah untuk orang tua)

### 1. Aktifkan debugging di TV
1. **Settings → Device Preferences → About** → klik **Build** 7× sampai muncul "You are now a developer".
2. **Settings → Device Preferences → Developer options** → nyalakan **USB debugging**.
   Di Mi TV nyalakan juga **MiTV ADB debugging** (agar bisa lewat Wi-Fi).
3. Catat IP TV: **Settings → Network & Internet → (Wi-Fi Anda)**.

### 2. Sambungkan dan install
```sh
ADB=~/Library/Android/sdk/platform-tools/adb      # sesuaikan lokasi SDK
$ADB connect <ip-tv>:5555                          # setujui dialog "Allow USB debugging?" di TV
$ADB install -r tv-app/build/outputs/apk/debug/tv-app-debug.apk
```

### 3. Beri izin overlay
Android TV sering tidak punya layar pengaturan izin overlay, jadi berikan lewat adb:
```sh
$ADB shell appops set com.tvscreentime.tv SYSTEM_ALERT_WINDOW allow
```

### 4. Jadikan launcher utama
```sh
$ADB shell cmd package set-home-activity com.tvscreentime.tv/.LauncherActivity
$ADB shell pm disable-user --user 0 com.google.android.tvlauncher   # launcher Google
```
Khusus **Mi TV** (PatchWall membuka dirinya sendiri setelah boot):
```sh
$ADB shell pm disable-user --user 0 com.mitv.tvhome.atv
$ADB shell pm disable-user --user 0 com.mitv.tvhome.mitvplus
```
Tekan Home di remote — launcher Screentime harus muncul. Reboot sekali untuk memastikan.

**Mengembalikan seperti semula:**
```sh
$ADB shell pm enable com.google.android.tvlauncher
$ADB shell pm enable com.mitv.tvhome.atv
$ADB shell pm enable com.mitv.tvhome.mitvplus
$ADB uninstall com.tvscreentime.tv
```

### 5. Pasang app HP dan hubungkan
1. Install `parent-app-debug.apk` di HP (kirim file APK, atau `adb install` lewat Wireless debugging).
2. Di TV: dari layar utama tekan **▲**, pilih ikon **HP 📱** → muncul QR.
3. Di HP: **Tambah TV** → scan QR. Selesai.

Pairing hanya terbuka selama layar QR tampil, dan satu QR hanya berlaku untuk satu HP. Untuk HP kedua, buka ulang layar QR.

---

## Penggunaan sehari-hari

| Di HP | Efek di TV |
|---|---|
| **Timer** 15/30/60/90 | Mulai hitung mundur; saat 0 → overlay "Waktu habis" |
| **+15 / −15** | Tambah/kurangi sisa waktu (juga membuka TV yang sedang terkunci karena timer) |
| **Kunci** | Overlay langsung muncul |
| **Bebas / Buka** | Tanpa batas waktu |
| **Atur → App** | Tampilkan/sembunyikan app di launcher |
| **Atur → Channel YouTube** | Isi tile Video |
| **ID / EN** | Bahasa HP dan semua TV |

Kontrol remote di pemutar video: **OK** = putar/jeda, **◀ ▶** = mundur/maju 10 detik, **Back** = kembali ke daftar.

---

## Perintah adb (debug tanpa HP)

```sh
S="-n com.tvscreentime.tv/.ScreenTimeService"
$ADB shell am start-foreground-service $S --es cmd timer --ei minutes 30
$ADB shell am start-foreground-service $S --es cmd add   --ei minutes 15     # -15 untuk mengurangi
$ADB shell am start-foreground-service $S --es cmd block
$ADB shell am start-foreground-service $S --es cmd unblock
$ADB shell am start-foreground-service $S --es cmd allow --es pkg com.google.android.youtube.tv
$ADB shell am start-foreground-service $S --es cmd deny  --es pkg com.google.android.youtube.tv
$ADB shell am start-foreground-service $S --es cmd lang  --es pkg en         # in | en
$ADB shell am start -a android.settings.SETTINGS                             # buka Settings TV
$ADB logcat -s ScreenTime
```

## API LAN

Semua request (kecuali `/pair`) memakai header `Authorization: Bearer <token>`. Body JSON.

| Method | Path | Keterangan |
|---|---|---|
| `POST` | `/pair` | `{code, name}` → `{token, status}` — hanya saat QR tampil |
| `GET` | `/status` | `{id, name, mode, remainingMs, allowed[]}` |
| `POST` | `/command` | `{cmd, minutes?, pkg?}` — `block` `unblock` `timer` `add` `allow` `deny` `lang` |
| `GET` | `/apps` | `[{pkg, label, allowed}]` |
| `GET` | `/devices` | `[{id, name, self}]` |
| `DELETE` | `/devices/{id}` | cabut akses HP |
| `GET` | `/channels` | `[{id, title}]` |
| `POST` | `/channels` | `{q}` — link channel, `@handle`, atau `UC…` |
| `DELETE` | `/channels/{id}` | hapus channel |
| `WS` | `/ws?token=…` | status JSON setiap berubah |

Isi QR: `{"v":1,"h":"<ip>","p":8787,"c":"<kode>","id":"<tv-id>","n":"<nama-tv>"}`.

---

## Struktur repo

```
tv-app/       Kotlin, minSdk 28 — launcher, overlay, timer, server LAN, video
  ScreenTimeService.kt   timer, mode, overlay, perintah
  LanServer.kt           Ktor HTTP/WebSocket, mDNS, pairing, daftar HP
  LauncherActivity.kt    layar Home
  PairActivity.kt        QR + perangkat terhubung
  Channels.kt            whitelist channel + RSS
  VideoActivity.kt       grid video
  PlayerActivity.kt      YouTube IFrame Player
  BootReceiver.kt        mulai lagi setelah reboot
  Lang.kt                bahasa ID/EN
parent-app/   Kotlin + Jetpack Compose, minSdk 28 — app HP orang tua
  Tvs.kt                 daftar TV, HTTP/WebSocket, mDNS
  MainActivity.kt        UI
```

---

## Keamanan & batasan yang perlu diketahui

- Komunikasi **HTTP tanpa enkripsi** di jaringan rumah; perlindungannya adalah token acak per HP. Jangan buka port 8787 ke internet.
- Tombol **Home** tidak bisa ditahan app biasa, tetapi overlay tetap di atas. Tombol khusus pabrikan (mis. Input/Settings) mungkin masih bisa membuka sesuatu — mode **Device Owner** (rencana) akan menutup celah ini.
- Pada beberapa TV, gambar **HDMI/tuner** digambar langsung oleh hardware. Di Mi TV 4A overlay terbukti menutupinya; di TV lain perlu dicek.
- Daftar paket tuner masih tetap (`com.droidlogic.droidlivetv`, `com.android.tv`). Untuk TV lain, izinkan app tuner-nya lewat menu **App** di HP.
- Server Ktor butuh ±6 detik untuk siap setelah TV menyala (RAM 1 GB).

## Roadmap

- [x] Overlay + timer, bertahan setelah reboot
- [x] Launcher
- [x] Server LAN + pairing QR + mDNS
- [x] App HP orang tua
- [x] Whitelist channel YouTube
- [x] Bahasa Indonesia / English
- [ ] Device Owner (`setPackagesSuspended`, kunci launcher) + panduan `adb shell dpm set-device-owner`
- [ ] Uji di Coocaa 32"
- [ ] Rilis APK bertanda tangan

## Lisensi

MIT — lihat [LICENSE](LICENSE).
