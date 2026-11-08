#!/bin/bash
# Launcher icons: crops of the official box art of each game
# (icons/jk_launcher.png: Jedi Knight: Dark Forces II, GOG store art;
#  icons/mots_launcher.png: Mysteries of the Sith, original cover).
outdir=app/src/main/res

for pair in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
    dir="$outdir/mipmap-${pair%:*}"
    size="${pair#*:}"
    mkdir -p "$dir"
    convert icons/jk_launcher.png   -filter Lanczos -resize "${size}x${size}" "$dir/ic_launcher.png"
    convert icons/mots_launcher.png -filter Lanczos -resize "${size}x${size}" "$dir/ic_launcher_mots.png"
done
