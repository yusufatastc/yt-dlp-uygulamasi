#!/usr/bin/env bash
# YtKlip - Linux kurulum (Ubuntu/Debian, Fedora, Arch, openSUSE)
# Kullanim:  bash install.sh
set -e

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
APP="$DATA/YtKlip/app"
BIN="$HOME/.local/bin"
DESK="$DATA/applications"

if [ ! -f "$SRC/ytklip.py" ]; then
  echo "ytklip.py bulunamadi. Bu scripti 'desktop/linux' klasorunden calistir."
  exit 1
fi

need_pkgs=()
if ! command -v python3 >/dev/null 2>&1; then need_pkgs+=(python3); fi
if ! python3 -c "import tkinter" >/dev/null 2>&1; then need_pkgs+=(tk); fi
if ! command -v ffmpeg >/dev/null 2>&1; then need_pkgs+=(ffmpeg); fi

if [ "${YTKLIP_SKIP_DEPS:-0}" != "1" ] && [ ${#need_pkgs[@]} -gt 0 ]; then
  echo "Eksik paketler kuruluyor: ${need_pkgs[*]} (sudo sifresi istenebilir)"
  if command -v apt-get >/dev/null 2>&1; then
    pk=()
    for p in "${need_pkgs[@]}"; do
      case "$p" in tk) pk+=(python3-tk) ;; *) pk+=("$p") ;; esac
    done
    sudo apt-get update
    sudo apt-get install -y "${pk[@]}"
  elif command -v dnf >/dev/null 2>&1; then
    pk=()
    for p in "${need_pkgs[@]}"; do
      case "$p" in tk) pk+=(python3-tkinter) ;; *) pk+=("$p") ;; esac
    done
    sudo dnf install -y "${pk[@]}"
  elif command -v pacman >/dev/null 2>&1; then
    sudo pacman -S --needed --noconfirm "${need_pkgs[@]}"
  elif command -v zypper >/dev/null 2>&1; then
    pk=()
    for p in "${need_pkgs[@]}"; do
      case "$p" in tk) pk+=(python3-tk) ;; *) pk+=("$p") ;; esac
    done
    sudo zypper install -y "${pk[@]}"
  else
    echo "Paket yoneticisi taninmadi. Elle kur: ${need_pkgs[*]}"
    exit 1
  fi
fi

mkdir -p "$APP" "$BIN" "$DESK"
cp "$SRC/ytklip.py" "$APP/"
rm -rf "$APP/assets"
cp -r "$SRC/assets" "$APP/assets"

cat > "$BIN/ytklip" << LAUNCH
#!/usr/bin/env bash
exec python3 "$APP/ytklip.py" "\$@"
LAUNCH
chmod +x "$BIN/ytklip"

cat > "$DESK/ytklip.desktop" << ENTRY
[Desktop Entry]
Type=Application
Name=YtKlip
Comment=Videodan sadece istedigin kismi indir
Exec=$BIN/ytklip
Icon=$APP/assets/logo_icon.png
Terminal=false
Categories=AudioVideo;Video;Network;
StartupWMClass=ytklip.py
ENTRY

command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$DESK" >/dev/null 2>&1 || true

echo
echo "Kurulum tamam."
echo "Uygulama menusunde 'YtKlip' yaz ya da terminalde 'ytklip' komutunu calistir."
case ":$PATH:" in
  *":$BIN:"*) ;;
  *) echo "Not: terminalden calistirmak icin oturumu kapat-ac (PATH'e $BIN eklenir)." ;;
esac
