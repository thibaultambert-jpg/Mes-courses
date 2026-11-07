# Mes courses

Application Android **personnelle** (testée pour un Honor Magic 5 Pro) pour planifier ses repas en
discutant avec un **LLM 100 % local**, tenir un **livre de recettes**, une **liste de courses** libre,
puis **remplir son panier drive** (Carrefour, Leclerc ; Auchan en phase 2) via des serveurs **MCP**.

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

### Drives : MCP communautaires sur un hôte maison

Aucune enseigne ne publie de MCP ouvert (l'app ChatGPT de Carrefour utilise un MCP privé). L'application
se connecte en **client MCP (Streamable HTTP)** à des serveurs communautaires qui tournent sur une machine
de la maison, joignable via Tailscale — voir **[server/README.md](server/README.md)** :

- Carrefour : [CarrefourDriveMCP](https://github.com/maximeallanic/CarrefourDriveMCP)
- Leclerc (et Super U) : [fr-grocery-mcp](https://github.com/maxime-desmarchelier/fr-grocery-mcp)
- Auchan : aucun MCP existant → phase 2

L'application détecte automatiquement les outils de chaque serveur (noms et paramètres différents),
lit les paramètres de contexte nécessaires dans le panier, et **refuse tout outil de commande ou de paiement**.

## Structure

```
core/    Logique métier Kotlin/JVM pur, testée (ingrédients, fusion de liste, extraction de recettes
         schema.org, outils du LLM, client MCP, préparation du panier)
app/     Application Android (Jetpack Compose, Room, DataStore, LiteRT-LM)
server/  Hôte maison : passerelle MCP (supergateway), SearXNG, scripts de démarrage
```

## Installer l'application

1. Onglet **Actions** du dépôt GitHub → dernier « Build APK » réussi → artefact **mes-courses-apk**.
2. Décompresse-le et ouvre l'`.apk` sur le téléphone (autorise l'installation d'applications inconnues).
3. **Réglages** → télécharge **Gemma 4 E4B** (≈ 3,6 Go, en Wi-Fi). Si Hugging Face demande une
   autorisation, accepte la licence Gemma sur la page du modèle et colle ton jeton HF ; ou télécharge le
   fichier `.litertlm` sur un PC depuis [litert-community](https://huggingface.co/litert-community) et utilise
   « Importer un fichier ».
4. (Optionnel) Configure l'hôte maison pour la recherche web et les drives.

## Développement

```bash
./gradlew -p core test          # tests de la logique métier (aucun SDK Android requis)
./gradlew :app:assembleDebug    # nécessite le SDK Android (ANDROID_HOME)
```
