#!/usr/bin/env bash
# Regenerates store-assets/feature-graphic-1024x500.png from store-assets/icon-512.png
# and the app's Material theme colors (icon-512.png itself is rendered elsewhere; see step 1).
#
# Requirements: rsvg-convert, ImageMagick (`magick`), a sans-serif font (Noto Sans used here).
#
# Source of truth for colors/shapes (read, not modified, by this script):
#   app/src/main/res/drawable/ic_launcher_background.xml
#   app/src/main/res/drawable/ic_launcher_foreground.xml
#   app/src/main/java/com/fletcheese/checklist/ui/theme/Color.kt / Theme.kt
#     (LightColorScheme: primary = Purple40 #6650a4, secondary = PurpleGrey40 #625b71,
#      tertiary = Pink40 #7D5260 -- the actual Material3 light scheme used by ChecklistTheme)

set -euo pipefail
cd "$(dirname "$0")"
REPO_ROOT="$(cd .. && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# 1. App icon: icon-512.png is no longer drawn here. Since 2026-11-25 (the
#    "check plus plus" icon: a gradient-filled brush path on the FletchTunes
#    gradient) it is rendered from the adaptive-icon layers by the icon
#    generator in fletcheese-dev (apps/icons/2026-11-25/generator): the inner
#    72dp of the 108dp layers, full-bleed, 512x512. This script reads it as is.
# ---------------------------------------------------------------------------
test -f "icon-512.png"

# Smaller copy of the icon used inside the feature graphic.
magick "icon-512.png" -resize 300x300 "$WORK/icon-300.png"

# ---------------------------------------------------------------------------
# 2. Feature graphic: 1024x500 plain background in the app's Material3 light
#    scheme primary color, app name + tagline, with the icon as an accent.
# ---------------------------------------------------------------------------
PRIMARY="#6650a4"    # Purple40 (LightColorScheme.primary)
TAGLINE_COLOR="#EFE6FA"  # near-white tint for contrast on the primary purple

cat > "feature-graphic-source.svg" << EOF
<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
  <rect width="1024" height="500" fill="${PRIMARY}" />
  <text x="400" y="235" font-family="Noto Sans, sans-serif" font-weight="700"
        font-size="76" fill="#FFFFFF">Checklist++</text>
  <text x="402" y="300" font-family="Noto Sans, sans-serif" font-weight="400"
        font-size="30" fill="${TAGLINE_COLOR}">
    <tspan x="402" dy="0">Reusable checklist templates with</tspan>
    <tspan x="402" dy="38">saved ordering, intended for groceries.</tspan>
  </text>
</svg>
EOF

rsvg-convert -w 1024 -h 500 "feature-graphic-source.svg" -o "$WORK/feature-bg.png"
magick "$WORK/feature-bg.png" "$WORK/icon-300.png" -geometry +70+100 -composite \
  "feature-graphic-1024x500.png"

echo "Wrote $REPO_ROOT/store-assets/icon-512.png"
echo "Wrote $REPO_ROOT/store-assets/feature-graphic-1024x500.png"
