# Mes courses

Application Android **personnelle** (testée pour un Honor Magic 5 Pro) pour planifier ses repas en
discutant avec un **LLM 100 % local**, tenir un **livre de recettes**, une **liste de courses** libre,
puis **remplir son panier Carrefour, E.Leclerc ou Auchan Drive** directement depuis le téléphone (sans serveur),
et **comparer les prix** de sa liste entre les trois enseignes.

| Onglet | Rôle |
|---|---|
| **Menu** | Un formulaire « envies de la semaine » : repas et convives, temps en cuisine (Express ≤ 30 min, Normal, J'ai le temps), composition (végé / poisson / viande), ingrédients à écouler, part de nouveautés, envies libres. L'app cherche de **vraies recettes** sur Marmiton, 750g, Cuisine AZ et Journal des Femmes à partir des légumes de saison, écarte les plats bannis ou mangés ces 4 dernières semaines, puis le LLM local choisit celles qui vont le mieux ensemble (saison, variété, ingrédients partagés pour une liste de courses courte). Sans modèle, un compositeur intégré fait ce choix ; sans réseau, le chef invente les plats comme avant. Tu ajustes en discutant ou avec les boutons : « autre recette des sites », ou une **recette créée par le chef** à la demande. « Valider » range les recettes dans le livre, remplit la liste de courses et alimente l'historique des menus. |
| **Recettes** | Livre de recettes vide au départ, en 3 listes : **Historique** (plats validés), **Favoris** (que le LLM peut reproposer ; les recettes partagées depuis un site ou écrites à la main y arrivent), **Ne plus proposer** (jamais reproposées). Un bouton déplace un plat d'une liste à l'autre. **Découvrir** : recherche sur Marmiton, 750g, Cuisine AZ et Journal des Femmes, aperçu, étoile pour ajouter aux favoris ; navigateur intégré qui détecte la recette de la page affichée ; ajout par lien. |
| **Liste** | Liste de courses libre + ingrédients des menus ; vue « fusionnée » (doublons regroupés, unités converties). Les quantités tiennent compte des enfants (demi-portion par défaut). |
| **Panier** | Chez ton **enseigne préférée** (Réglages) ou une autre choisie pour ce panier : pour chaque article, recherche du produit au drive (bio en priorité), **validation par toi**, puis ajout au panier. **Comparer les prix** : la même liste cherchée chez Carrefour, E.Leclerc et Auchan, total par enseigne, puis « Remplir le panier chez… ». La commande et le paiement se font ensuite chez l'enseigne. |

## Choix techniques

### LLM local : LiteRT-LM + Gemma 4 (et pourquoi pas xybrid)

| Moteur | Accélération sur Snapdragon 8 Gen 2 | Appels d'outils | Maturité |
|---|---|---|---|
| **LiteRT-LM (Google)** ✅ | **GPU Adreno** (OpenCL) | natifs, exécutés automatiquement | SDK Kotlin stable, base de l'app *AI Edge Gallery* |
| xybrid | **CPU seulement** sur Android | oui (boucle côté app) | très jeune (v0.17, pré-1.0), API mouvante |
| llama.cpp (JNI, llama.rn…) | GPU OpenCL (Q4_0), NPU Hexagon expérimental | via le modèle | très mature, tous modèles GGUF |
| MLC LLM, ExecuTorch, ONNX GenAI, Qualcomm Genie | NPU complexe ou non pris en charge sur 8 Gen 2 | manuels | moins adaptés |

Modèles proposés dans l'app : **Gemma 4 E4B** (qualité, ~10-14 tok/s estimés) et **Gemma 4 E2B** (rapide).
Le moteur est derrière l'interface `LlmEngine` : on pourra brancher xybrid (quand il gérera le GPU Android)
ou llama.cpp (Qwen3.5, Ministral 3) pour comparer.

### Drives Carrefour, E.Leclerc et Auchan : intégration native (sans serveur)

Les requêtes vers chaque enseigne partent **d'une WebView connectée** à ton compte (cookies + vrai navigateur :
c'est ce qui passe les protections Cloudflare ou DataDome, qu'un client HTTP classique ne passe pas). Une WebView
invisible par enseigne ; seule celle de l'enseigne active est chargée (les trois pendant une comparaison).

- **Enseigne préférée** (Réglages → Mes drives) : utilisée par défaut. Dans l'onglet Panier, les pastilles permettent
  d'en changer pour un panier (la recherche est relancée), et « Comparer les prix » interroge les trois enseignes.

- **Connexion** : une fois, dans l'app (e-mail + mot de passe + code reçu par e-mail ; la connexion « via Google » ne marche pas
  dans une WebView). L'app ne voit ni ne stocke ton mot de passe ; seuls les cookies sont gardés par Android.
  La session Carrefour expire après ~1 h d'inactivité : l'app la renouvelle seule tant que possible, sinon elle te redemande de te connecter.
- **Panier** : un produit proposé par article (bio d'abord, tes choix précédents en priorité), tu vérifies, l'app ajoute.
- **Assistant (mode Courses)** : Gemma cherche, propose au plus 3 produits ; **chaque ajout est confirmé** par une carte.
- **Jamais** de paiement, de validation de commande ni de réservation de créneau : bouton « Finaliser sur … ».
- Routes utilisées et sources : [`docs/carrefour/ROUTES_CARREFOUR.md`](docs/carrefour/ROUTES_CARREFOUR.md),
  [`docs/leclerc/ROUTES_LECLERC.md`](docs/leclerc/ROUTES_LECLERC.md), [`docs/auchan/ROUTES_AUCHAN.md`](docs/auchan/ROUTES_AUCHAN.md) ;
  un seul fichier de routes par enseigne (`CarrefourRoutes.kt`, `LeclercRoutes.kt`, `AuchanRoutes.kt`).
- **Particularités** : chez Leclerc, le drive se cherche par code postal mais la session est liée au drive ouvert sur le site
  (l'ouvrir une fois) ; chez Auchan, le drive se choisit sur auchan.fr et l'app lit le magasin dans le panier.
  Les créneaux et le détail produit (composition) ne sont lus que chez Carrefour pour l'instant.
- **Écran de debug** : Réglages → appuyer 7 fois sur « version » (choix de l'enseigne, requête de test, export anonymisé
  d'une réponse, jetons Auchan relevés ou non, option « se présenter comme Chrome » si l'anti-robot bloque la WebView).

Intégrations **non officielles**, pour un usage personnel : elles peuvent casser si une enseigne modifie son site.
Toutes passent par l'interface `RetailerBackend` (`core/.../cart/RetailerBackend.kt`).

### Sites de recettes

L'import lit la fiche `schema.org/Recipe` que les sites publient pour Google ; il marche donc sur la plupart des sites
(Marmiton, 750g, Cuisine AZ, Journal des Femmes, Cuisine Actuelle, Ptitchef, Chef Simon, Elle à table,
HelloFresh, blogs WordPress…). La **recherche intégrée** (onglet Découvrir) couvre Marmiton, 750g, Cuisine AZ et
Journal des Femmes : elle lit les pages de résultats sans dépendre de leur mise en page exacte (liens dont l'adresse a
la forme d'une recette). Les pages sont d'abord demandées en HTTP, puis, si le site bloque, chargées dans un navigateur
invisible. Si un site ne répond pas, l'app propose d'ouvrir sa recherche dans le navigateur intégré, où le bandeau
« Recette détectée » permet quand même l'ajout. Adresses et formes d'URL : `core/.../recipes/RecipeSites.kt`.
Recettes non accessibles : Cookidoo (abonnés), Jow (à tester).

### Catalogue local de recettes

Découvrir et la création du menu cherchent d'abord dans un **catalogue enregistré sur le téléphone** (base `catalogue.db`,
à part de celle de l'appli) : titre, ingrédients, durées, note, type de plat, saison et lien de l'image — ni étapes ni
images. La fiche complète est lue sur le site quand la recette est ouverte ou validée. Le bouton « Chercher aussi sur
les sites » de Découvrir garde la recherche en ligne en complément.

- **Construction** : `tools/catalog` lit les sitemaps des sites (`core/.../catalog/SiteCrawler.kt`) puis chaque fiche, en
  fichier `<site>.jsonl.gz` par site. 750g et Cuisine Actuelle, sans sitemap, sont lus par leurs rubriques paginées
  (workflow « Catalogue — sites par rubriques », lancé en poussant `tools/catalog/extra.conf`).
  fichier `<site>.jsonl.gz` par site.
- **Rattrapage** : pousser `tools/catalog/retry.conf` (liste `SITES`) rejoue les fiches restées en erreur sur ces sites
  (workflow « Catalogue — rattrapage »). Chaque passage écrit `<site>.erreurs.tsv` (toutes les fiches encore en erreur,
  avec leur message), rangé dans l'artefact `erreurs-<site>-<passage>`. La publication prend le rattrapage le plus récent.
- **Publication** : pousser `tools/catalog/publish.conf` rassemble le dernier passage terminé de chaque site dans la
  release « catalogue » ; la compilation suivante de l'APK embarque ces fichiers.
- **Contrôle** : ouvrir `tools/catalog/viewer.html` dans un navigateur et y déposer les fichiers (ou l'archive .zip
  téléchargée depuis GitHub) : recherche, filtres, fiches à contrôler, tableau par site.
- **Mise à jour hebdomadaire** : le workflow « Catalogue — mise à jour hebdomadaire » (`catalog-weekly.yml`, chaque dimanche, ou
  « Run workflow ») repart des bases déjà publiées, ne lit que les fiches nouvelles ou modifiées de chaque site, republie la
  release « catalogue » (bases `<site>.jsonl.gz`, état de lecture `<site>.seen.tsv.gz`, `version.txt`), puis relance « Build APK »
  pour que l'APK embarque les bases à jour. Un site sans base publiée est ignoré (la première collecte se fait avec les workflows
  ci-dessus). Les déclencheurs planifiés ne tournent que sur `main`.
- **Dans l'appli** : une base par site, **téléchargée à la demande** depuis la release « catalogue ». Au premier lancement,
  l'écran « Bases de recettes » s'affiche en premier (Tout télécharger, ou site par site, ou Passer) ; il reste accessible dans
  Réglages › Bases de recettes (télécharger, mettre à jour quand une version plus récente est publiée, supprimer). Sans réseau,
  la copie embarquée dans l'APK (`assets/catalog/`, avec `version.txt`) sert de secours. Dans les filtres de Découvrir, un site
  dont la base n'est pas téléchargée est grisé, non sélectionnable ; le toucher ouvre l'écran de choix. Seules les bases
  téléchargées sont tenues à jour par le téléphone (nouveautés des sites, une fois par jour à l'ouverture, hors données mobiles,
  ou à la demande dans Réglages).

### Honor / MagicOS

MagicOS ferme les applications en arrière-plan. Pour garder la session et le modèle chargé plus longtemps :
**Paramètres › Batterie › Lancement des applications › Mes courses › Gérer manuellement** (activer les trois options).
L'app libère d'elle-même le modèle (plusieurs Go) quand le système manque de mémoire, et le recharge à la demande.

## Structure

```
core/    Logique métier Kotlin/JVM pur, testée (ingrédients, fusion de liste, extraction de recettes
         schema.org, outils du LLM, clients Carrefour / Leclerc / Auchan, préparation du panier, comparaison des prix)
app/     Application Android (Jetpack Compose, Room, DataStore, LiteRT-LM)
docs/    Routes des enseignes documentées
```

## Installer l'application

1. Onglet **Actions** du dépôt GitHub → dernier « Build APK » réussi → artefact **mes-courses-apk**.
2. Décompresse-le et ouvre l'`.apk` sur le téléphone (autorise l'installation d'applications inconnues).
3. **Réglages** → télécharge **Gemma 4 E4B** (≈ 3,6 Go, en Wi-Fi). Si Hugging Face demande une
   autorisation, accepte la licence Gemma sur la page du modèle et colle ton jeton HF ; ou télécharge le
   fichier `.litertlm` sur un PC depuis [litert-community](https://huggingface.co/litert-community) et utilise
   « Importer un fichier ».
4. Réglages → **Mes drives** : choisis ton enseigne préférée, connecte-toi et choisis ton drive (pour chaque enseigne à comparer).

## Développement

```bash
./gradlew -p core test          # tests de la logique métier (aucun SDK Android requis)
./gradlew :app:assembleDebug    # nécessite le SDK Android (ANDROID_HOME)
```
