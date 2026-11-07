# Routes Carrefour (carrefour.fr) — référence pour l'intégration native

> Intégration **non officielle**, pour un usage personnel. Le site peut changer sans préavis.
> Dans l'app, toutes ces routes sont centralisées dans un seul fichier de constantes (`CarrefourRoutes.kt`, Phase 2).

**Sources analysées le 07/11/2026** :
- **[DMCP]** [maximeallanic/CarrefourDriveMCP](https://github.com/maximeallanic/CarrefourDriveMCP) : définitions `tools/*.json` (capturées sur le site réel) et `src/services/*.ts` (session, Cloudflare).
- **[NMCP]** [nicovlr/mcp-carrefour](https://github.com/nicovlr/mcp-carrefour) : `src/api/client.py` (lecture des pages Nuxt), `src/api/models.py`.

> ⚠️ **Le panier de [NMCP] est fictif** : `add_to_cart` / `view_cart` gèrent un panier en mémoire (`Cart(id=uuid4())` dans `server.py`), sans rien envoyer à Carrefour. Seules les routes panier de **[DMCP]** agissent sur le vrai panier.

Légende du statut : ✅ route relevée sur le site réel par [DMCP] · ⚠️ **à confirmer** (via `chrome://inspect` → onglet Network sur la WebView de l'app) · ❌ inconnue.

---

## 0. En-têtes communs (toutes les requêtes `/api/*` et `/s`)

```
accept: application/json, text/plain, */*
x-requested-with: XMLHttpRequest
content-type: application/json        (requêtes avec corps)
```

- **Cookies** : ceux de la session WebView (`CookieManager`), envoyés automatiquement par `fetch(…, {credentials: "include"})` depuis une page `https://www.carrefour.fr/…`. Il n'y a **aucun cookie à fabriquer**.
- **Cloudflare** [DMCP `browser.service.ts`] : un client HTTP natif reçoit `403` avec l'en-tête `cf-mitigated: challenge` dès la première requête. Un vrai navigateur avec fenêtre reçoit `200`. Les requêtes doivent donc partir **de l'intérieur d'une page** : c'est le principe du pont WebView.

---

## 1. Session

| Élément | Valeur | Source | Statut |
|---|---|---|---|
| Hôte de connexion (ForgeRock AM) | `https://moncompte.carrefour.fr` | DMCP `session.service.ts` | ✅ |
| URL de connexion à ouvrir dans la WebView visible | `https://moncompte.carrefour.fr/iam/oauth2/CarrefourConnect/authorize?client_id=carrefour_onecarrefour_web&redirect_uri=https%3A%2F%2Fwww.carrefour.fr%2Flogin%2Fcheck&response_type=code&scope=openid%20iam` | DMCP `auth.service.ts` (`authorizeUrl`) | ✅ |
| Connexion réussie | la WebView arrive sur l'origine `https://www.carrefour.fr` après `/login/check?code=…` | DMCP `auth.service.ts` (`landedOnStorefront`) | ✅ |
| Cookie SSO | `c4iamsecuretk` sur `moncompte.carrefour.fr` : **24 h maximum, perdu après 60 min d'inactivité** | DMCP `auth.service.ts`, `session.service.ts` | ✅ |
| Cookies de la boutique | cookies httpOnly sur `www.carrefour.fr`, créés à partir du code OAuth. Ils expirent **avant** le SSO | DMCP `auth.service.ts` | ✅ |
| Renouvellement sans saisie | recharger l'URL d'autorisation dans la WebView invisible. Si elle revient sur `www.carrefour.fr`, la session est renouvelée. Si elle reste sur `moncompte`, il faut se reconnecter | DMCP `auth.service.ts` (`refreshSession`) | ✅ |
| Vérifier la session | `GET /api/cart` : `200` = connecté, `401`/`403` = session expirée (essayer le renouvellement une fois) | DMCP `http.service.ts`, `session.tools.ts` | ⚠️ distinguer 403 « session » de 403 Cloudflare (en-tête `cf-mitigated`) |
| Connexion | captcha **Cloudflare Turnstile** + **code reçu par e-mail** : toujours manuelle | DMCP `browser-login.ts` | ✅ |

**Conséquence pratique** : après plus d'une heure sans utilisation, il faudra souvent se reconnecter (code par e-mail). Le renouvellement automatique ne marche que tant que le cookie SSO est vivant.

---

## 2. Recherche de produits — ✅ `GET https://www.carrefour.fr/s`

| | |
|---|---|
| Paramètres de l'URL | `q` (texte), `page` (à partir de 1) |
| Connexion requise | non |
| Réponse | JSON (format JSON:API) quand `accept: application/json` et `x-requested-with` sont envoyés : liste paginée, facettes, total |
| Champs utiles | les éléments `{type:"product", id, attributes:{ean, title, brand, slug, offers:{<ean>:{<offre>:{attributes:{price:{price, perUnitLabel}}}}}, nutriscore:{value}, images:[{medium}], promotion:{label}, customerReviews:{average,count}}}` |
| Source | DMCP `tools/search_products.json` ; champs relevés dans NMCP `client.py` (`_extract_products_from_route_data`) |
| Plan B | sans ces en-têtes, page HTML rendue côté serveur : données dans `window.__INITIAL_STATE__ = {…}` → `routeData` (une chaîne JSON contenant un **tableau indexé** de type Nuxt : chaque valeur entière renvoie à `rd[i]`, à résoudre) — NMCP `_parse_search_results` + `_resolve` |
| Statut | route ✅ ; forme exacte du JSON ⚠️ (le lecteur `ProductParser` existant gère déjà le JSON:API avec attributs aplatis et prix en profondeur) |

## 3. Détail d'un produit — ⚠️

| | |
|---|---|
| Option A | page produit `GET https://www.carrefour.fr/p/{slug}-{ean}` en HTML, avec JSON-LD `@type: Product` (name, brand, offers.price, gtin13, availability) — NMCP `_parse_product_page`. ⚠️ la composition et les allergènes sont peut-être dans `__INITIAL_STATE__`, à confirmer |
| Option B | `POST https://www.carrefour.fr/products`, corps `{"gtins": ["<ean>"], "facilityServiceId": "<id>"}`, en-têtes `accept`/`content-type: application/json` — DMCP `tools/get_products_by_gtins.json` ✅ route ; champs ⚠️ |
| Avis clients | `GET /product/{ean}/reviews?limit=&sortField=&sortOrder=` — DMCP ✅ (pas utile ici) |

## 4. Magasins Drive

| Opération | Route | Source | Statut |
|---|---|---|---|
| Drives proches | `GET /api/eligibility/drive?latitude=&longitude=&postalCode=&city=&page=&limit=` (sans connexion). Réponse : id, nom, référence, distance, enseigne, adresse, format, type de commande, horaires | DMCP `get_eligible_drive_stores.json` | ✅ route, champs ⚠️ |
| Code postal → coordonnées | `Geocoder` Android (aucune dépendance) | — | — |
| Magasin favori | `GET /api/favoritestore` (connexion requise) | DMCP `get_favorite_store.json` | ✅ |
| Simuler le panier dans un magasin | `GET /api/cart/simulate?storeRef=` | DMCP `simulate_cart_for_store.json` | ✅ |
| **Choisir le magasin** | **❌ inconnue** : DMCP ne la capture pas. Plan B : choisir le magasin dans la WebView visible sur carrefour.fr, puis relire `basketServiceId` et `facilityServiceId` dans `GET /api/cart` | — | ❌ → à capturer |

## 5. Panier — connexion requise

| Opération | Route | Corps | Source | Statut |
|---|---|---|---|---|
| Lire | `GET /api/cart` (paramètre `refresh=true` facultatif) | — | DMCP `get_cart.json` | ✅ route. Réponse : totaux, remises, frais, sous-paniers ; champs exacts ⚠️ |
| Ajouter / fixer la quantité | `PATCH /api/cart` | `{"trackingRequest":{"pageType":"search","pageId":"search"},"items":[{"basketServiceId":"0678-150-7052","counter":2,"ean":"3560070000000","subBasketType":"drive_clcv"}]}` | DMCP `add_item_to_cart.json`, exemple de valeurs dans `__tests__/resolve.test.ts` | ✅ |
| Ajouter une unité | `PATCH /api/cart/items` | `{"ean":…,"basketServiceId":…,"subBasketType":…,"trackingRequest":{…}}` | DMCP `add_item_to_cart_by_ean.json` | ✅ |
| Retirer | `PATCH /api/cart` avec `"counter": 0` | idem | déduit | ⚠️ **à confirmer** |
| Réponse aux écritures | totaux et résumé du panier mis à jour | — | DMCP (description) | ⚠️ champs |

- `counter` est la quantité **totale** voulue, pas un incrément (« desired quantity » dans DMCP).
- `basketServiceId` (ex. `0678-150-7052`) et `subBasketType` (ex. `drive_clcv`) identifient le drive.
  - Ils se lisent dans `GET /api/cart`, **mais seulement si le panier contient déjà un article**.
  - Panier vide : `basketServiceId` est la clé du 2ᵉ niveau des offres d'une recherche, `offers.{ean}.{offerServiceId}` (forme `\d+-\d+-\d+`). C'est `CarrefourClient.context()` qui fait cette lecture.
  - Le contexte trouvé est enregistré dans les Réglages.

## 6. Créneaux — ✅ `GET /api/timeslots?facilityServiceId=<id>` (connexion requise)

Réponse : des jours, chacun avec ses cases de créneaux (disponibilité, dates, type de livraison collaborative). Source : DMCP `get_delivery_timeslots.json`. Les champs sont ⚠️. `facilityServiceId` (ex. `0678-150-149`) vient du panier ou du magasin.

## 7. Routes **exclues** de l'app (jamais appelées, jamais exposées à Gemma)

| Route | Raison |
|---|---|
| `POST /api/checkout/payment` (`submit_checkout_payment`) | **déclenche un vrai paiement** |
| `POST /api/checkout/{type}/validate/slot`, `…/validate/summary` | validation de commande |
| `PUT /api/cart/slot` (`select_cart_delivery_slot`) | réservation de créneau : se fait sur carrefour.fr |
| `POST /api/cart/promo_code` | hors périmètre |

## 8. Opérations passant par le DOM ou par Playwright dans DMCP
- **Connexion uniquement** (`browser-login.ts`) : fenêtre Chromium normale, sans pilotage tant que la connexion n'est pas finie, parce que Turnstile refuse de valider dans un navigateur piloté (CDP).
  - **Dans l'app** : une WebView visible où l'utilisateur se connecte lui-même. Aucune injection de script pendant la connexion.
- **Toutes les autres opérations** sont de **vrais appels réseau** (`page.evaluate(fetch)` dans DMCP). Aucun scraping du DOM n'est nécessaire.

## 9. À capturer sur le téléphone (onglet Network de `chrome://inspect`)
1. La requête envoyée quand on **choisit un drive** sur carrefour.fr.
2. Une réponse de `GET /s?q=lait` en JSON, anonymisée → fixture `search_lait.json`.
3. Une réponse de `GET /api/cart` avec 2 articles, sans données personnelles → fixture `cart.json`.
4. La réponse à `PATCH /api/cart` avec `counter: 0` (vérifier que l'article est bien retiré).
5. Une réponse de `GET /api/timeslots` → fixture `timeslots.json`.

L'écran de debug (Phase 2) a un bouton « Exporter la réponse (anonymisée) » pour faire ces captures sans PC.
