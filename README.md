# Mes courses

Application Android **personnelle** (testée pour un Honor Magic 5 Pro) pour planifier ses repas en
discutant avec un **LLM 100 % local**, tenir un **livre de recettes**, une **liste de courses** libre,
puis **remplir son panier Carrefour Drive** directement depuis le téléphone (sans serveur).

| Onglet | Rôle |
|---|---|
| **Menu** | Tu choisis le nombre de repas, les adultes et les enfants, tes envies, puis « Proposer un menu ». Le LLM local propose des plats **sains, bio et de saison**. Tu ajustes en discutant (« ajoute 3 adultes et 2 enfants au plat 2 », « remplace le plat 4 ») ou avec les boutons. « Valider » écrit les recettes, les range dans le livre (Historique) et remplit la liste de courses. |
| **Recettes** | Livre de recettes vide au départ, en 3 listes : **Historique** (plats validés), **Favoris** (que le LLM peut reproposer ; les recettes partagées depuis un site ou écrites à la main y arrivent), **Ne plus proposer** (jamais reproposées). Un bouton déplace un plat d'une liste à l'autre. |
| **Liste** | Liste de courses libre + ingrédients des menus ; vue « fusionnée » (doublons regroupés, unités converties). Les quantités tiennent compte des enfants (demi-portion par défaut). |
| **Panier** | Pour chaque article, recherche du produit au drive (bio en priorité), **validation par toi**, puis ajout au panier. La commande et le paiement se font ensuite chez l'enseigne. |

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

### Carrefour Drive : intégration native (sans serveur)

Les requêtes vers carrefour.fr partent **d'une WebView connectée** à ton compte (cookies + vrai navigateur :
c'est ce qui passe la protection Cloudflare, qu'un client HTTP classique ne passe pas).

- **Connexion** : une fois, dans l'app (e-mail + mot de passe + code reçu par e-mail ; la connexion « via Google » ne marche pas
  dans une WebView). L'app ne voit ni ne stocke ton mot de passe ; seuls les cookies sont gardés par Android.
  La session Carrefour expire après ~1 h d'inactivité : l'app la renouvelle seule tant que possible, sinon elle te redemande de te connecter.
- **Panier** : un produit proposé par article (bio d'abord, tes choix précédents en priorité), tu vérifies, l'app ajoute.
- **Assistant (mode Courses)** : Gemma cherche, propose au plus 3 produits ; **chaque ajout est confirmé** par une carte.
- **Jamais** de paiement, de validation de commande ni de réservation de créneau : bouton « Finaliser sur Carrefour ».
- Routes utilisées et sources : [`docs/carrefour/ROUTES_CARREFOUR.md`](docs/carrefour/ROUTES_CARREFOUR.md) ;
  toutes sont dans un seul fichier (`core/.../carrefour/CarrefourRoutes.kt`).
- **Écran de debug** : Réglages → appuyer 7 fois sur « version » (requête de test, export anonymisé d'une réponse,
  option « se présenter comme Chrome » si Cloudflare bloque la WebView).

Intégration **non officielle**, pour un usage personnel : elle peut casser si Carrefour modifie son site.
Leclerc et Auchan pourront être ajoutés plus tard sur le même modèle (interface `RetailerBackend`).

### Honor / MagicOS

MagicOS ferme les applications en arrière-plan. Pour garder la session et le modèle chargé plus longtemps :
**Paramètres › Batterie › Lancement des applications › Mes courses › Gérer manuellement** (activer les trois options).
L'app libère d'elle-même le modèle (plusieurs Go) quand le système manque de mémoire, et le recharge à la demande.

## Structure

```
core/    Logique métier Kotlin/JVM pur, testée (ingrédients, fusion de liste, extraction de recettes
         schema.org, outils du LLM, client MCP, préparation du panier)
app/     Application Android (Jetpack Compose, Room, DataStore, LiteRT-LM)
docs/    Routes Carrefour documentées
```

## Installer l'application

1. Onglet **Actions** du dépôt GitHub → dernier « Build APK » réussi → artefact **mes-courses-apk**.
2. Décompresse-le et ouvre l'`.apk` sur le téléphone (autorise l'installation d'applications inconnues).
3. **Réglages** → télécharge **Gemma 4 E4B** (≈ 3,6 Go, en Wi-Fi). Si Hugging Face demande une
   autorisation, accepte la licence Gemma sur la page du modèle et colle ton jeton HF ; ou télécharge le
   fichier `.litertlm` sur un PC depuis [litert-community](https://huggingface.co/litert-community) et utilise
   « Importer un fichier ».
4. Onglet **Panier** → « Se connecter », puis choisis ton drive.

## Développement

```bash
./gradlew -p core test          # tests de la logique métier (aucun SDK Android requis)
./gradlew :app:assembleDebug    # nécessite le SDK Android (ANDROID_HOME)
```
