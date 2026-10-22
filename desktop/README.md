# YtKlip masaüstü

Windows (.exe) ve Linux sürümü. GitHub Actions ile otomatik derlenir:
Actions -> "Masaustu uygulamasi" -> son çalışma -> Artifacts.

- yt-dlp ve ffmpeg exe'nin içinde gelir, ayrıca bir şey kurmak gerekmez.
- Videolar varsayılan olarak `Videolar/YtKlip` klasörüne kaydedilir (uygulamadan değiştirilebilir).
- Motor güncellemesi uygulamadaki "yt-dlp'yi güncelle" düğmesiyle yapılır.

Kaynaktan çalıştırmak için (Python 3.9+ ve tkinter gerekir):

    python3 ytklip.py

## Linux'ta hemen kurmak (Actions beklemeden)

    bash desktop/linux/install.sh

Gerekli paketleri (python3-tk, ffmpeg) kurar, uygulama menüsüne "YtKlip" ekler.
Kaldırmak için: `bash desktop/linux/uninstall.sh`
