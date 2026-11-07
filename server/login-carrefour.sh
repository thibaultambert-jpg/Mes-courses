#!/usr/bin/env bash
# Connexion unique au compte Carrefour (captcha + code par e-mail) : ouvre une fenêtre Chromium.
# Les cookies sont ensuite conservés et renouvelés automatiquement par CarrefourDriveMCP.
set -euo pipefail
cd "$(dirname "$0")"
export CARREFOUR_DATA_DIR="${MES_COURSES_DATA:-$PWD/data}/carrefour"
mkdir -p "$CARREFOUR_DATA_DIR"
npx -y @modelcontextprotocol/inspector@2.14.0 --cli -e "CARREFOUR_DATA_DIR=$CARREFOUR_DATA_DIR" \
  npx -y github:maximeallanic/CarrefourDriveMCP \
  --method tools/call --tool-name carrefour_browser_login
