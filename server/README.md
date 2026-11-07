# Hôte maison « Mes courses »

L'application tourne entièrement sur le téléphone, LLM compris. Les **serveurs MCP des drives**
(Carrefour, Leclerc) ont cependant besoin d'un **vrai navigateur connecté à ton compte** (protections
Cloudflare / DataDome) : ils ne peuvent pas tourner sur Android. Ils tournent donc sur une machine de la
maison (PC, Mac, mini-PC…) allumée pendant que tu fais tes courses, et le téléphone s'y connecte.

```
Téléphone ──Tailscale──► hôte :8811  supergateway ─┬─ /carrefour/mcp → CarrefourDriveMCP (Chromium)
                         hôte :8888  SearXNG       └─ /leclerc/mcp   → fr-grocery-mcp (ton Chrome)
```

> ⚠️ Ces MCP sont **communautaires et non officiels**. Ils automatisent ta propre session, à rythme humain,
> pour un usage personnel. Les CGU des enseignes s'appliquent et un changement de leur site peut les casser.
> L'application **ne passe jamais commande** : les outils de paiement, de créneau et de validation sont
> masqués par la passerelle (`supergateway.jsonc`) **et** refusés par l'application.

## 1. Prérequis

- **Node.js ≥ 26** (exigé par fr-grocery-mcp) — <https://nodejs.org>
- **Google Chrome**
- **Docker** (facultatif, pour SearXNG : recherche de recettes sur internet)
- **Tailscale** sur l'hôte **et** sur le téléphone (gratuit) — <https://tailscale.com/download>.
  Le téléphone joint alors l'hôte par son adresse `100.x.y.z`, sans rien ouvrir sur ta box.

## 2. Configuration

```bash
cd server
cp .env.example .env
# mets un jeton aléatoire dans .env, ex. : openssl rand -hex 24
```

Dans `searxng/settings.yml`, remplace `secret_key` par une valeur aléatoire.

## 3. Connexion aux comptes (une seule fois)

**Carrefour** — ouvre une fenêtre pour se connecter (captcha + code reçu par e-mail) ;
les cookies sont ensuite conservés et renouvelés automatiquement :

```bash
./login-carrefour.sh
```

**Leclerc** — `start.sh` ouvre un Chrome dédié (profil séparé, port 9222). Connecte-toi sur
e.leclerc dans **cette** fenêtre et choisis ton drive. Laisse-la ouverte (réduite).
Si ton magasin n'est pas reconnu, renseigne-le dans `fr-grocery.json` (voir le README de
[fr-grocery-mcp](https://github.com/maxime-desmarchelier/fr-grocery-mcp) : `storeId`, `retrievalPointId`, `host`).

## 4. Démarrage

```bash
./start.sh          # macOS / Linux
# ou, sous Windows :  powershell -ExecutionPolicy Bypass -File start.ps1
```

Vérification depuis l'hôte :

```bash
curl http://localhost:8811/healthz                     # → ok
npx @modelcontextprotocol/inspector                    # puis http://localhost:8811/carrefour/mcp
                                                       # avec l'en-tête Authorization: Bearer <jeton>
curl "http://localhost:8888/search?q=quiche&format=json" | head -c 300
```

## 5. Réglages dans l'application

Récupère l'IP Tailscale de l'hôte (`tailscale ip -4`), puis dans **Réglages** :

| Champ | Valeur |
|---|---|
| SearXNG | `http://100.x.y.z:8888` |
| Jeton d'accès MCP | le jeton de `.env` |
| MCP Carrefour | `http://100.x.y.z:8811/carrefour/mcp` |
| MCP E.Leclerc Drive | `http://100.x.y.z:8811/leclerc/mcp` |

Le bouton **Tester** affiche les outils détectés (recherche, ajout au panier, lecture du panier).

## Auchan (phase 2)

Aucun MCP Auchan France n'existe à ce jour (`auchan-mcp` sur npm vise Auchan **Portugal**).
La voie la plus simple est d'écrire un petit adaptateur sur le modèle de fr-grocery-mcp
(`docs/adding-a-retailer.md`) : capturer les requêtes d'auchan.fr (recherche, panier) dans l'onglet
Réseau de Chrome, puis les rejouer dans le Chrome connecté. L'entrée `auchan` est déjà prévue
(désactivée) dans `supergateway.jsonc`, et l'application la gère sans modification.
