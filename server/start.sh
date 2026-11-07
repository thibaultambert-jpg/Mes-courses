#!/usr/bin/env bash
# Démarre l'hôte « Mes courses » : Chrome (session Leclerc) + passerelle MCP (+ SearXNG si Docker est là).
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f .env ]]; then set -a; source .env; set +a; fi
: "${MES_COURSES_TOKEN:?Crée server/.env à partir de .env.example (MES_COURSES_TOKEN)}"
export MES_COURSES_DATA="${MES_COURSES_DATA:-$PWD/data}"
export MES_COURSES_CONFIG="${MES_COURSES_CONFIG:-$PWD/fr-grocery.json}"
mkdir -p "$MES_COURSES_DATA"

major=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)
if (( major < 26 )); then
  echo "Node.js ≥ 26 est requis (fr-grocery-mcp). Version actuelle : $(node -v 2>/dev/null || echo absente)" >&2
  exit 1
fi

# 1. Chrome dédié avec port de débogage, pour fr-grocery-mcp (Leclerc).
if ! curl -s http://127.0.0.1:9222/json/version >/dev/null 2>&1; then
  CHROME="${CHROME:-$(command -v google-chrome || command -v google-chrome-stable || command -v chromium || command -v chromium-browser || true)}"
  if [[ -z "$CHROME" && -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]]; then
    CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  fi
  if [[ -n "$CHROME" ]]; then
    echo "→ Lancement de Chrome (profil dédié, port 9222)…"
    "$CHROME" --remote-debugging-port=9222 --user-data-dir="$MES_COURSES_DATA/chrome" \
      --no-first-run https://www.e.leclerc/ >/dev/null 2>&1 &
    sleep 3
  else
    echo "⚠ Chrome introuvable : Leclerc ne fonctionnera pas (définis CHROME=/chemin/vers/chrome)." >&2
  fi
fi

# 2. SearXNG (recherche de recettes) si Docker est disponible.
if command -v docker >/dev/null 2>&1; then
  docker compose up -d searxng || echo "⚠ SearXNG non démarré" >&2
fi

# 3. Passerelle MCP (Streamable HTTP, port 8811).
echo "→ Passerelle MCP sur http://0.0.0.0:8811 (/carrefour/mcp, /leclerc/mcp)"
exec npx -y supergateway@4.2.1 --config supergateway.jsonc
