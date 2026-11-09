# Routes Auchan Drive (auchan.fr) — référence pour l'intégration native

> Intégration **non officielle**, pour un usage personnel. Le site peut changer sans préavis.
> Dans l'app, toutes ces routes sont dans un seul fichier : `core/.../auchan/AuchanRoutes.kt`.

**Source analysée le 08/11/2026** : [Spaghetih/auchan-drive-mcp](https://github.com/Spaghetih/auchan-drive-mcp)
(`src/auchan_mcp/endpoints.py`, relevé le 24/10/2026, `browser.py`, `dom.py`, `server.py`).

Légende : ✅ utilisé par la source sur le site réel · ⚠️ à confirmer sur le téléphone.

---

## 0. Principe

- Deux origines : `https://www.auchan.fr` (pages, recherche, ajout) et `https://api.auchan.fr` (API panier). ✅
- L'API panier exige les en-têtes que le site envoie lui-même : `authorization`, `client-id`, `x-gravitee-api-key`, et un
  `consentId` passé dans l'adresse. ✅ La source les intercepte sur les requêtes `/checkout/` du site.
  - **Dans l'app** : la WebView invisible est garée sur la page panier (`/checkout/cart/`), qui appelle l'API ; les en-têtes
    sont relevés avec `WebViewClient.shouldInterceptRequest` (en mémoire seulement, jamais enregistrés ni journalisés).
    ⚠️ à confirmer : qu'Android transmet bien l'en-tête `authorization` d'un `fetch` dans `getRequestHeaders()`.
    L'écran de debug indique quels jetons ont été vus.
  - Jeton périmé (401/403) : l'app recharge la page panier pour en relever de nouveaux, une fois.
- L'appel à `api.auchan.fr` part de la page `www.auchan.fr` (autre origine) : l'app essaie avec les cookies
  (`credentials: include`), puis sans si le navigateur le refuse (CORS). ⚠️
- Connexion : e-mail et mot de passe sur le site (la connexion Google est refusée dans un navigateur intégré). ✅

## 1. Recherche — ✅ `GET https://www.auchan.fr/recherche?text={texte}`

Page HTML rendue côté serveur. Une fiche par produit : `article[data-current-offer-id]`.

| Élément | Sens |
|---|---|
| `article@data-id` | identifiant produit |
| `article@data-current-offer-id` | offre (à renvoyer pour l'ajout) |
| `.quantity-selector@data-seller-id` | vendeur (le drive) |
| `.quantity-selector@data-stock` | stock |
| `.product-thumbnail__description` | libellé |
| `.product-price` | prix (« 1,22 € ») |
| `span[data-product-id]` | prix au kg / L (« 1,22€ / l ») |
| `article@data-list` commençant par `KAMINO` | produit sponsorisé (placé en dernier par l'app) |

## 2. Panier

| Opération | Route | Corps | Statut |
|---|---|---|---|
| Lire | `GET https://api.auchan.fr/checkout/v1/carts/mine?consentId=…` | — | ✅ |
| Ajouter (produit absent du panier) | `POST https://www.auchan.fr/cart/update` | `{"cartId","items":[{"productId","offerId","sellerType":"GROCERY","desiredQuantity","desiredType":"DEFAULT","sellerId"}],"consentId","reservationId":null,"mbaAvailabilityNeeded":true}` | ✅ |
| Modifier / retirer une ligne | `POST https://api.auchan.fr/checkout/v1/carts/{cartId}/items?consentId=…` | `[{"id":<ligne>,"productId","offerId","desiredQuantity"}]` (0 = retirer) | ✅ |

- `desiredQuantity` = quantité **totale** voulue.
- Réponse panier : `id`, `items[]` avec `id` (ligne), `productId`, `offerId`, `desiredQuantity`,
  `offering.actualQuantity`, `offering.prices.price.amount` (**centimes**), `offering.context.seller.name` (magasin),
  `product.name` (souvent absent : l'app reprend le nom vu en recherche).
- Le magasin (nom du vendeur) est lu dans le panier et affiché dans les Réglages.

## 3. Non relevé / exclu

- **Choix du drive** : pas de route relevée → à faire sur auchan.fr (bouton dans l'écran « Mon drive Auchan »).
- **Créneaux**, **détail produit** : non relevés (l'app renvoie une liste vide de créneaux).
- Routes **exclues** : tout chemin contenant `/payment`, `/checkout/v1/orders`, `/slot`, `/reservation`.

## 4. À capturer sur le téléphone

1. Vérifier dans l'écran de debug que `authorization`, `client-id`, `x-gravitee-api-key` et `consentId` sont relevés.
2. Une page de recherche réelle → comparer avec `core/src/test/resources/auchan/search.html`.
3. La requête envoyée quand on choisit un drive sur le site.
4. La route des créneaux.
