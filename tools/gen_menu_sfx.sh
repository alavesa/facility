#!/bin/zsh
# Synthesizes the main-menu sound effects with ffmpeg (no samples, nothing to license) and makes the ambient
# track loop-friendly. Run from the repo root:  zsh tools/gen_menu_sfx.sh
#
#   menu_click.ogg    relay tick when a dialog button opens another dialog
#   menu_glitch.ogg   static burst under the navigation glitch
#   menu_boot.ogg     monitor power-on, under the menu intro
#   menu_deploy.ogg   power-down sweep + thump, under the PLAY transition
#
# The sound events are registered in assets/facility/sounds.json. ffmpeg's built-in vorbis encoder is stereo only.
set -e
OUT="$(cd "$(dirname "$0")/.." && pwd)/resource-pack/assets/facility/sounds"
ENC=(-ac 2 -ar 44100 -c:a vorbis -strict -2 -q:a 4 -y -loglevel error)

ffmpeg -f lavfi -i "anoisesrc=d=0.07:c=white:a=0.5" -f lavfi -i "sine=f=2200:d=0.07" \
  -filter_complex "[0]highpass=f=3000[n];[1]volume=0.35[s];[n][s]amix=2:normalize=0,afade=t=out:st=0.01:d=0.06" \
  $ENC "$OUT/menu_click.ogg"

ffmpeg -f lavfi -i "anoisesrc=d=0.45:c=white:a=0.6" \
  -af "acrusher=bits=4:samples=24:mix=1,tremolo=f=27:d=0.9,bandpass=f=2500:w=3000,afade=t=in:d=0.01,afade=t=out:st=0.28:d=0.17,volume=0.8" \
  $ENC "$OUT/menu_glitch.ogg"

ffmpeg -f lavfi -i "aevalsrc='0.30*sin(2*PI*(60*t+90*t*t))*min(t*3\,1)*exp(-1.2*max(t-0.9\,0))+0.25*sin(2*PI*48*t)*exp(-9*t)':d=1.6:s=44100" \
  -f lavfi -i "anoisesrc=d=0.25:c=pink:a=0.35" \
  -filter_complex "[1]afade=t=out:d=0.25[n];[0][n]amix=2:normalize=0:duration=first,afade=t=out:st=1.2:d=0.4" \
  $ENC "$OUT/menu_boot.ogg"

ffmpeg -f lavfi -i "aevalsrc='0.32*sin(2*PI*(900*t-230*t*t))*exp(-1.6*t)+0.55*sin(2*PI*52*t)*exp(-4*max(t-0.9\,0))*gt(t\,0.9)':d=2.0:s=44100" \
  -f lavfi -i "anoisesrc=d=0.9:c=white:a=0.45" \
  -filter_complex "[1]acrusher=bits=5:samples=18:mix=1,tremolo=f=31:d=0.8,bandpass=f=2200:w=2600,afade=t=in:d=0.05,afade=t=out:st=0.55:d=0.35[n];[0][n]amix=2:normalize=0:duration=first,afade=t=out:st=1.6:d=0.4" \
  $ENC "$OUT/menu_deploy.ogg"

# The ambient loop: the plugin starts the next play `menu.music.overlap-seconds` before this one ends, so a fade of
# that length at both ends turns the seam into a crossfade. Only done once (a marker file remembers it).
FADE=1.5
MARK="$(dirname "$0")/.menu_ambient_faded"
if [ ! -f "$MARK" ]; then
  LEN=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/menu_ambient.ogg")
  ffmpeg -i "$OUT/menu_ambient.ogg" \
    -af "afade=t=in:d=$FADE,afade=t=out:st=$((LEN - FADE)):d=$FADE" $ENC "$OUT/menu_ambient.tmp.ogg"
  mv "$OUT/menu_ambient.tmp.ogg" "$OUT/menu_ambient.ogg"
  echo "ambient faded ${FADE}s at both ends (length ${LEN}s)" > "$MARK"
fi

for f in "$OUT"/menu_*.ogg; do
  echo "$(basename "$f")  $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f")s"
done
