# Aqil Launcher 3.0

Launcher Android TV gaya kaca (glass) dengan sidebar, banner berputar 3D, kad apl, dock bawah,
wallpaper LIVE (Aurora/Nebula/video sendiri) atau biasa (gradien/gambar), cuaca, Live TV (ExoPlayer)
dan **remote dari telefon** (buka pelayar sahaja, tiada apl perlu dipasang).

## Ciri
- **Susun atur ikut saiz TV**: semua saiz direka untuk 1920x1080 dan diskala automatik (720p/1080p/4K).
- **Home**: banner (Live TV / Galeri / Remote) dengan peralihan 3D, 2 kad apl, Kegemaran, Dibuka Baru-baru Ini.
- **Animasi 3D ringan**: kad condong masuk ikut arah D-pad, lantunan, bayang biru, sinaran — semuanya animasi GPU.
- **Wallpaper**: Tetapan › Wallpaper, atau dari telefon (Gambar › Wallpaper TV) — termasuk upload video sebagai wallpaper live.
- **Live TV (ExoPlayer)**: HLS/DASH/TS, pengepala UA/Referer dari M3U, ClearKey/Widevine (#KODIPROP), penimbal besar,
  sambung semula automatik, langkau saluran rosak, semak saluran mati, tukar saluran laju (atas/bawah/nombor).
- **Remote telefon**: imbas QR di halaman Remote atau buka `http://IP-TV:8686`, masukkan PIN.
  Kekunci dihantar melalui WebSocket (titik hijau = bersambung).

## Kawal apl LAIN (Netflix, YouTube, dll.)
Hidupkan **Tetapan › Kebolehcapaian › Aqil Launcher Remote** untuk Back/Home/navigasi/sentuhan/taip di luar launcher.

## Bina
GitHub Actions membina APK release pada setiap push → **Releases**.
Lokal: `./gradlew assembleRelease` (perlu Android SDK 34).
