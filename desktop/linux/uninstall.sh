#!/usr/bin/env bash
# YtKlip'i kaldirir (indirdigin videolara dokunmaz)
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
rm -rf "$DATA/YtKlip"
rm -f "$HOME/.local/bin/ytklip" "$DATA/applications/ytklip.desktop"
echo "YtKlip kaldirildi."
