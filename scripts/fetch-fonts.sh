#!/usr/bin/env bash
# Extra free fonts offered in the font menu (Microsoft's own fonts cannot be bundled).
#   DejaVu Sans / Sans Condensed / Serif / Sans Mono (Bitstream Vera licence), Comic Neue and Anton (SIL OFL).
# LibreOffice on Android does not apply font substitution tables, so these are offered under their real names.
# Downloaded into engine/fonts (gitignored) and copied into the app assets.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p engine/fonts app/src/main/assets/unpack/user/fonts
cd engine/fonts
if [ ! -f dejavu.zip ]; then
  wget -q -O dejavu.zip https://github.com/dejavu-fonts/dejavu-fonts/releases/download/version_2_37/dejavu-fonts-ttf-2.37.zip
fi
unzip -q -o -j dejavu.zip 'dejavu-fonts-ttf-2.37/ttf/DejaVuSans.ttf' 'dejavu-fonts-ttf-2.37/ttf/DejaVuSans-Bold.ttf' \
  'dejavu-fonts-ttf-2.37/ttf/DejaVuSans-Oblique.ttf' 'dejavu-fonts-ttf-2.37/ttf/DejaVuSans-BoldOblique.ttf' \
  'dejavu-fonts-ttf-2.37/ttf/DejaVuSansMono.ttf' 'dejavu-fonts-ttf-2.37/ttf/DejaVuSansMono-Bold.ttf' \
  'dejavu-fonts-ttf-2.37/ttf/DejaVuSansMono-Oblique.ttf' 'dejavu-fonts-ttf-2.37/ttf/DejaVuSansMono-BoldOblique.ttf' \
  'dejavu-fonts-ttf-2.37/ttf/DejaVuSerif.ttf' 'dejavu-fonts-ttf-2.37/ttf/DejaVuSerif-Bold.ttf' \
  'dejavu-fonts-ttf-2.37/ttf/DejaVuSerif-Italic.ttf' 'dejavu-fonts-ttf-2.37/ttf/DejaVuSerif-BoldItalic.ttf' \
  'dejavu-fonts-ttf-2.37/ttf/DejaVuSansCondensed.ttf' 'dejavu-fonts-ttf-2.37/ttf/DejaVuSansCondensed-Bold.ttf' \
  'dejavu-fonts-ttf-2.37/ttf/DejaVuSansCondensed-Oblique.ttf' 'dejavu-fonts-ttf-2.37/ttf/DejaVuSansCondensed-BoldOblique.ttf'
G=https://raw.githubusercontent.com/google/fonts/main/ofl
for f in comicneue/ComicNeue-Regular comicneue/ComicNeue-Bold comicneue/ComicNeue-Italic comicneue/ComicNeue-BoldItalic anton/Anton-Regular; do
  [ -f "$(basename $f).ttf" ] || wget -q -O "$(basename $f).ttf" "$G/$f.ttf"
done
cp -f ./*.ttf ../../app/src/main/assets/unpack/user/fonts/
echo "fonts ready: $(ls ./*.ttf | wc -l) files"
