# Aqil Launcher 2.0

Launcher Android TV gaya iOS "liquid glass" dengan latar aurora hidup, kad 3D (cover-flow + goyang + sinaran),
Live TV (M3U/IPTV), dan **remote dari telefon** (tanpa pasang apa-apa di telefon — buka pelayar).

## Ciri
- **Skrin utama**: jam, Live TV, Remote Telefon, Galeri, Tetapan, Kegemaran, semua apl. Tekan lama OK (atau butang Menu) pada apl: favorit / maklumat / nyahpasang.
- **Remote telefon**: buka `http://IP-TV:8080` di telefon (sama Wi-Fi), masukkan PIN 4-digit yang dipaparkan di TV. D-pad, Back/Home/Menu, volume, media, buka apl, pilih saluran TV, taip teks, buka pautan, touchpad.
- **Hantar gambar**: tab *Gambar* di telefon → terus papar di TV dengan flip 3D; tayangan slaid; jadikan wallpaper.
- **Live TV**: senarai M3U (asal: iptv-org Malaysia). Tukar URL dari telefon (tab *Lagi*).

## Kawal apl LAIN (Netflix, YouTube, dll.)
Android tak benarkan launcher menghantar kekunci ke apl lain. Hidupkan **Tetapan › Kebolehcapaian › Aqil Launcher Remote**
untuk Back/Home/Recents, navigasi fokus, sentuhan dan taip teks di luar launcher. Volume/media berfungsi tanpa ini.

## Bina
GitHub Actions membina APK pada setiap push → muat turun di **Releases** (atau Artifacts).
Lokal: `./gradlew assembleDebug` (perlu Android SDK 34).
APK ditandatangani dengan kunci tetap (`app/debug.keystore`) supaya kemas kini boleh dipasang atas versi lama
bagi build dari repo ini. Jika dah ada APK lama dengan kunci lain, nyahpasang dulu.
