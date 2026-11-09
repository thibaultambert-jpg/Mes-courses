# Routes E.Leclerc Drive (leclercdrive.fr) — référence pour l'intégration native

> Intégration **non officielle**, pour un usage personnel. Le site peut changer sans préavis.
> Dans l'app, toutes ces routes sont dans un seul fichier : `core/.../leclerc/LeclercRoutes.kt`.

**Source analysée le 08/11/2026** : [skunkobi/mcp-leclerc-drive](https://github.com/skunkobi/mcp-leclerc-drive)
(`docs/api-capture.md`, relevé sur le site réel le 13/06/2026, et `src/leclerc/client.ts`, `locator.ts`).

Légende : ✅ relevé et rejoué par la source · ⚠️ à confirmer sur le téléphone (écran de debug ou `chrome://inspect`).

---

## 0. Principe

- Chaque drive a **son propre hôte** : `https://fdN-courses.leclercdrive.fr` (fd8, fd9, fd14… selon le magasin) et
  son chemin `magasin-{noPL}-{noPR}-{Ville}` (les deux numéros sont égaux pour un drive). ⚠️ **Le nom de la ville
  n'est pas décoratif pour tous les drives** : sans lui, `fd14-courses…/magasin-159201-159201/` répond « page introuvable »
  (relevé sur le téléphone le 08/11/2026). L'app garde le chemin exact donné par `urlSiteCourse` ou par l'adresse du site.
- Protection **DataDome** : un client HTTP natif reçoit `403`. Les requêtes partent donc d'une **page du drive** dans la
  WebView invisible (même hôte, cookies du navigateur). ✅
- **Rythme** : des écritures envoyées en rafale déclenchent DataDome (403 sur tout). L'app envoie une requête à la fois,
  espacées. ✅
- La **session est liée au drive** ouvert dans le navigateur : choisir un drive dans l'app ne suffit pas, il faut
  l'ouvrir une fois sur le site (l'écran « Mon drive E.Leclerc » propose le bouton). ✅ (changement de drive côté
  serveur : route non relevée.)
- Une réponse `200` sans le marqueur `lstProduitsLight` est une page anti-robot ou « session expirée » (texte
  « session a expiré »). ✅

## 1. Recherche de produits — ✅ `GET {drive}/recherche.aspx?TexteRecherche={texte}`

Page HTML (ASP.NET), pas d'appel XHR séparé. Les produits sont des objets JSON passés aux widgets
(`Utilitaires.widget.initOptions(…, {"objContenu":{"lstElements":[{"objElement":{…}}]}})`) ; l'app prend le plus petit
objet qui entoure chaque `"iIdProduit"`.

| Champ | Sens |
|---|---|
| `iIdProduit` | identifiant produit (utilisé pour le panier ; **ce n'est pas l'EAN**) |
| `sLibelleLigne1`, `sLibelleLigne2` | libellé |
| `nrPVUnitaireTTC` / `sPrixUnitaire` | prix |
| `sPrixPromo` | prix promo |
| `sPrixParUniteDeMesure` | prix au kg / L |
| `iQteDisponible` | stock (0 = indisponible) |
| `iQuantitePanier` | quantité déjà au panier |
| `sUrlVignetteProduit` | vignette |

## 2. Panier

| Opération | Route | Statut |
|---|---|---|
| Lire | aucune page panier (`GET panier.aspx` = 404). Chaque page du drive contient le panier : tableau `"lstProduits":[…]` (fiches complètes avec `iQuantitePanier`, `rTotalAPayer`) puis `"lstProduitsLight"` suivi du total `"sTotalAPayer"`. L'app lit une **recherche sans résultat** (`TexteRecherche=zzzznomatchzzz`) pour n'avoir que les produits du panier. | ✅ |
| Ajouter / modifier / retirer | `POST https://{hôte}/magasin-{noPL}-{noPR}/panier.aspx?op=1` (chemin **court, sans la ville** : le chemin long renvoie 404), corps **JSON** `{"eTypeAction":1,"iIdProduit":"…","iQuantite":<quantité cible>,"sNoPointLivraison":"…","objContexteProvenanceArticle":{…}}` (`application/json`, `x-requested-with: XMLHttpRequest`). Réponse : tableau d'événements (`Produit…`, `Rayon…`, `Panier…` avec `sTotalAPayer`). Relevé dans Chrome, novembre 2026. | ✅ |

Corps JSON : `{"eTypeAction":1,"iIdProduit":"2612","iQuantite":2,"sNoPointLivraison":"053701"}`
- `eTypeAction` : **1** = ajouter / augmenter, **2** = diminuer / retirer.
- `iQuantite` : quantité **cible** (pas un ajout). Retirer = action 2, quantité 0. ✅
- `objContexteProvenanceArticle` (statistiques) : omis par l'app ; ⚠️ vérifié facultatif seulement pour un retrait.

Réponse : tableau d'événements. `Produit{id}` = ligne modifiée (`iQuantitePanier`, `rTotalAPayer`) ;
`Panier{magasin}` = total (`rTotalAPayer`, `iQuantitePanier`). ✅

## 3. Magasins — ✅ `https://api-recherchemagasins.leclercdrive.fr/API_RechercheMagasins/api/v1`

Appelée depuis une page de `https://www.leclercdrive.fr/` (origine autorisée). Trois appels :

1. `GET /autocomplete?search={code postal}&provider=Woosmap` → `postalCodes[0].id`
2. `GET /autocomplete/coordinates?id={id}&provider=Woosmap` → `latitude`, `longitude`
3. `GET /MapPoint/nearby?latitude=&longitude=&postalCode=` → `points[]` : `name`, `noPL`, `noPR`, `serviceType`
   (`drive`, `relais`, `livraison`), `distance`, `urlSiteCourse` / `urlBase` (**hôte du drive**).

L'app range l'hôte et `noPR` dans `Store.ref` (`hôte|noPR`). Quand on ouvre un drive sur le site dans la WebView visible,
l'app lit aussi le drive dans l'adresse de la page.

## 4. Non relevé / exclu

- **Créneaux** : non relevés (à choisir sur le site). L'app renvoie une liste vide.
- **Détail produit** (composition, allergènes) : non relevé.
- **Connexion** : manuelle sur le site, dans la WebView visible ; pas de renouvellement automatique.
- Routes **exclues** (jamais appelées) : tout chemin contenant `/commande`, `/paiement`, `/validation`, `/creneau`.

## 5. À capturer sur le téléphone

1. Une page de recherche réelle (vérifier les objets `iIdProduit`) → comparer avec `core/src/test/resources/leclerc/search.html`.
2. La réponse d'un ajout au panier sans `objContexteProvenanceArticle`.
3. La requête envoyée quand on change de drive sur le site.
4. La route des créneaux.
