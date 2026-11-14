# Plan révisé : vectorisation hybride, rationalisation du catalogue, mapping canonique et gestion du contexte LLM

## 1. Contexte

**Mes courses** compose le menu de la semaine avec un LLM 100 % local (LiteRT-LM 0.18.1 + Gemma 4 E2B/E4B sur un Honor Magic 5 Pro, Snapdragon 8 Gen 2) à partir d'un catalogue local de recettes (`catalogue.db`, FTS4). Le plan fourni veut (1) un mapping canonique des ingrédients, (2) une rationalisation du catalogue, (3) des embeddings calculés en CI, (4) une recherche hybride FTS4 + cosinus INT8 sur le téléphone, (5) le branchement dans Menu et Découvrir. L'utilisateur demande aussi de revoir la gestion de la fenêtre de contexte du LLM dans toutes les fonctionnalités, et de faire exécuter le tout par des sous-agents Opus 5.5 (jugement, algorithmes, intégration risquée) et Sonnet 5.5 (tâches locales bien spécifiées).

L'exploration du dépôt et la vérification de l'API LiteRT-LM (javap sur l'AAR) changent sensiblement le plan d'origine : échelle ×10, embedder natif déjà disponible, API de contexte (comptage de tokens, messages initiaux) existante. Ce document est le plan final, découpé en tâches directement transformables en briefs de sous-agents.

## 2. Faits établis (ne pas re-vérifier)

### Fichiers fournis par l'utilisateur (vérifiés, à utiliser tels quels — ne pas ré-analyser)
- **Base consolidée** `catalogue-rationalise-v2.jsonl.gz` (upload `7d70f643-…`, 30 Mo gz, 162 Mo brut) : **192 437 fiches** déjà rationalisées par l'utilisateur ; 11 sites (ptitchef 49 534, cuisineaz 46 599, marmiton 38 383, chefsimon 31 902, cuisineactuelle 7 036, 750g 7 028, hellofresh 6 840, jow 2 329, picnic 1 715, jdf 961, cuisineaddict 110) ; champs : url, siteId, title, imageUrl, ingredients, totalMinutes, course, keys (100 %), servings/rating/ratingCount/category/months/kind/balanced/lastmod partiels ; **aucun `embedding`, aucun `nutritionTags`**. C'est **la base de la prochaine compilation** : elle est vectorisée puis publiée telle quelle (recalcul des champs dérivés avec le mapping autorisé).
- **Mapping** `mapping-ingredients.json` (upload `1b9205d4-…`) : format v2 du viewer, 155 `merges`, 7 `keys`, 78 `lines` à ignorer. **`rayon` contient le nom de l'énumération `Aisle`** (`FRUITS_LEGUMES`, `EPICERIE`, `CREMERIE`, `BOISSONS`), pas le libellé : le chargeur accepte le nom d'enum d'abord, le libellé en repli.
- **Plan de rationalisation v2** (upload `0985e646-…`) : (1) corrections de `FoodName.kt` (blanc d'œuf, mots `cm`, `pouces`, `éclats`, `quartiers`, `c. à thé`, `bou`, `paquets`) ; (2) `RationalizationConfig(requireUniqueImage, excludedCourses, defaultMinRating = 4.5, minRatingBySite {hellofresh: 4.0}, minRatingCount = 10, keepUnrated = true, requireTime = true, minIngredients = 3)` et `CatalogRationalizer.filterSingle(entry, config): CatalogEntry?` + déduplication Jaccard ≥ 0,75 sur le **titre** (optionnelle) ; (3) CLI `rationalize --in --out`, étape dans `catalog-publish.yml` avant le packaging, et **`filterSingle` appliqué dans l'appli** aux fiches lues quotidiennement (`CatalogUpdater`) avant `store.upsert()`. Ce plan **remplace** la conception initiale du lot 2 (seuils, plafond par site, PMI).
- Ces fichiers sont copiés dans le dépôt au lot 0 : mapping → `core/src/main/resources/fr/mescourses/core/ingredients/mapping-ingredients.json` ; base → `tools/catalog/data/catalogue-rationalise-v2.jsonl.gz` (30 Mo, sous la limite GitHub ; sert d'amorce au workflow d'import, peut être retirée du dépôt après la première publication) ; ce plan → `docs/PLAN-vectorisation.md`.

### Échelle
- Release `catalogue` actuelle : ~41 Mo de `.jsonl.gz` ≈ 300 k fiches brutes. Base consolidée : **192 437 fiches** → 192 k × 256 o = **49 Mo** d'index INT8 en RAM sur le téléphone, ≈ +50 Mo gz de téléchargement en Base64 inline, 192 k produits scalaires ≈ 60–120 ms (option `--dim 128` pour diviser par deux si nécessaire). Jaccard O(n²) impossible : blocage obligatoire.

### Ce qui n'existe pas
- Ni `IngredientMapper`, ni `nutritionTags`/`embedding`, ni ressource chargée dans `core/main` (pas de `core/src/main/resources`).
- CLI `tools/catalog/Main.kt` : seulement `build` et `stats` ; arguments en paires `--k v` (`args.drop(1).chunked(2)`), pas de flag booléen ; écrit du `.jsonl` brut, gzip fait par le shell des workflows. Pas de Python dans la CI.
- Pas de WorkManager (coroutines + service premier plan `MenuWorkService`), aucun test dans `app/`, aucune dépendance TFLite classique.
- Room `catalogue.db` : version 1, `fallbackToDestructiveMigration(true)`, pas de TypeConverter ni BLOB, FTS4 externe sur la colonne `search` (tokenizer par défaut), tri par note bayésienne ou pseudo-aléatoire.

### Ce qui existe et doit être réutilisé
- `tools/catalog/viewer.html` exporte déjà `mapping-ingredients.json` **v2** : `{version:2, merges:[{from,to}], keys:[{key,status:'ok'|'split'|'ignore'|null, rayon:<libellé Aisle>, search, recipes}], lines:[{text,key,status,count}]}`. `rayon` est, dans le fichier fourni, le **nom d'enum `Aisle`** (`FRUITS_LEGUMES`…) — le viewer peut aussi exporter le libellé, les deux sont acceptés ; `lines[].text` est la ligne sans quantité/unité en minuscules (fonction JS `variantOf`, regex `QTY` L298-299).
- Clés : `ShoppingListMerger.key` (shopping/ShoppingListMerger.kt:35) → `IngredientOverlap.key` (menu/IngredientOverlap.kt) = clé stockée dans `CatalogEntry.keys`, utilisée à 7 endroits (catalogue, `MenuCandidates`, `MenuScoring`, `MenuHistory`, `MenuValidator`). `FoodName.clean`, `Aisle.of` (shopping/Aisles.kt:44), `CartPlanner.searchQuery`/`queryFor` (cart/CartPlanner.kt:162-169, surcharges utilisateur indexées par `ShoppingListMerger.key`), `Pantry.isStaple`, `RecipeRating.score`, `CatalogPolicy` (catalog/Catalog.kt:138), `CatalogEntry.of` (L110-130).
- Recherche : `CatalogQuery` (core/catalog/CatalogStore.kt:13-99, `words()` L50 avec stop words), `CatalogRanking.search` (L120-165 : une requête par site, entrelacement), `RoomCatalogStore.search` (app/data/CatalogDatabase.kt:192-230, SQL brut), `CatalogSync.importFile` (app/data/CatalogSync.kt:281, filtre des assets `.jsonl.gz` L209-225), `CatalogUpdater` (lecture quotidienne de nouveautés **sur le téléphone**, donc sans embedding), `ModelManager.download` (reprise HTTP Range). Faux `InMemoryCatalog` dans `CatalogRankingTest`.
- Menu : `CandidateFinder` (core/menu/MenuCandidates.kt:160-457 ; `wishQuery` L202 limite à 4 mots), `Candidate.line` (L69-78), `MenuComposer.score` (core/menu/MenuScoring.kt:90-137), `MenuPrompt.systemWeb` (core/menu/MenuPrompt.kt:67-105, 18 candidates), `MenuTools` (compteur `maxChanges` figé par instance), tests `MenuPlannerTest` (bornes en caractères L288-297).
- Tests `core` : kotlin.test sur JUnit Platform, noms en français snake_case, `Json { ignoreUnknownKeys = true; isLenient = true }`.

### API LiteRT-LM 0.18.1 vérifiée (javap sur `litertlm-android-0.18.1.aar`)
- **`EmbeddingEngine` natif** : `EmbeddingEngineConfig(modelPath, backend, visionBackend, audioBackend, cacheDir, modelFd, maxInputLength, …)`, `initialize()`, `computeEmbedding(List<InputData>, EmbeddingOptions(normalize, insertSpecialTokens, outputSize, …)) : EmbeddingResponse(float[])`, `computeEmbeddingBatch`, `computeEmbeddingAsync`. Modèle `litert-community/embeddinggemma-2-740m-litert-lm` : **non soumis à licence bloquante** (`gated: false`, vérifié), fichier `embeddinggemma-2-740m.litertlm` 485 Mo (int4 QAT, sorties Matryoshka 128/256/512/768, ~26 ms/texte, 334 Mo RAM), variante NPU `_Qualcomm_SM8550` (563 Mo, puce du téléphone cible). Préfixes obligatoires : `task: search result | text: …` (passage), `task: search query | text: …` (requête). Paquet PyPI `litert-lm` (0.19.2 actuel, roue `py3-none-any`) avec la même API `EmbeddingEngine`.
- `EngineConfig(modelPath, backend, visionBackend, audioBackend, **maxNumTokens: Int?**, maxNumImages, cacheDir, activationDataType)` — jamais réglé aujourd'hui.
- `ConversationConfig(systemInstruction, **initialMessages: List<Message>**, tools, samplerConfig, automaticToolCalling, channels, extraContext, loraConfig, **prefillPrefaceOnInit**, **maxOutputToken**, **thinkingConfig**, **enableResponseFormat**, enableSpeculativeDecoding, chatTemplate)` ; `Conversation.**getTokenCount()**`, `cancelProcess()`, `isAlive()`, `sendMessage(…, responseFormat: ResponseFormat(Type, schemaOrPattern))` ; `Message(Role, Contents, toolCalls, channels)` ; `ModelInfo.maxContextTokens()`. **Aucun clonage/snapshot de conversation.**

### Usage actuel du LLM
| Fonction | Session | Historique | Remise à zéro |
|---|---|---|---|
| Composition (`MenuViewModel.chooseFromCandidates`) | neuve, 1 échange, `systemWeb` ≈ 1 300–1 500 tokens + schémas des 8 outils ≈ 600–800 | non | — |
| Ajuster en discutant (`MenuViewModel.send`) | **neuve à chaque demande**, état dans le message (`MenuPrompt.context`) | non | chaque tour |
| Chef invente / hors ligne | neuve, 1 échange | non | — |
| Validation, étapes (`MenuValidator`, `RecipeStepsWriter`) | `generateOnce` : **ferme la conversation courante, `owner = null`** | non | chaque appel |
| Assistant Courses (`ShoppingChatViewModel`) | **persistante** par enseigne ; résultats d'outils 400–700 tokens par recherche | oui | sur exception « contexte plein », puis rappel de 600 caractères |
Aucun budget de tokens, `thinkingConfig` jamais fixé (des marqueurs `<channel|>` fuient : regex `modelMarkers` MenuViewModel.kt:744), deux détecteurs `isContextFull` divergents. **Bug** : la file d'écriture des étapes (`ChefWork`) tue silencieusement la conversation Courses.

## 3. Réponse à la question sur la fenêtre de contexte

**Ni l'un ni l'autre tel quel.** Avec LiteRT-LM, « compresser le contexte » et « recréer une session » sont la **même opération** : il n'existe ni compaction en place, ni suppression de messages, ni clonage. La seule manière de « compresser » est d'ouvrir une nouvelle `Conversation` amorcée par `initialMessages` = un résumé compact. La vraie question est donc *quand* basculer et *quoi* reporter, et la réponse dépend de la fonction :

| Fonction | Politique retenue | Pourquoi |
|---|---|---|
| Composition, Ajuster, Chef invente | **Conversation neuve par requête** (état du menu dans le message, comme aujourd'hui) **+ pré-chauffage** : la conversation suivante est créée dès la fin d'une réponse avec `prefillPrefaceOnInit = true`, pour payer les 3–6 s de prefill du prompt système pendant que l'utilisateur tape | Un tour vaut ~2 500–3 000 tokens ; un historique d'appels d'outils fait rejouer au petit modèle les retouches précédentes (raison d'être de `maxChanges`) ; l'état tient en < 700 caractères |
| Validation, étapes | **One-shot** (`generateOnce`) **+ `ResponseFormat` JSON** ; ne ferme plus la conversation du propriétaire sans la sauvegarder (suspension/restauration par `initialMessages`) | Moins de relances JSON (3 tentatives aujourd'hui) ; corrige le bug de propriété |
| Assistant Courses | **Persistante bornée, bascule proactive** : surveiller `getTokenCount()` contre un budget dérivé de `maxNumTokens`, basculer **avant** le tour qui déborderait (ou après N tours à outils), en reportant panier + produits vus + extrait de la dernière réponse dans `initialMessages` | L'historique a de la valeur (« prends le 2ᵉ ») ; attendre l'exception perd le tour après un prefill déjà payé |

Règles transverses : `maxNumTokens` explicite (**E4B : 6 144**, **E2B : 8 192**, repli 4 096 si `initialize()` échoue ; à calibrer sur l'appareil, valeur exposée par `LlmEngine.maxTokens()`), `ThinkingConfig(enableThinking = false)`, `maxOutputToken` (400 menu/courses, 900 recettes), un seul détecteur `isContextFull` conservé comme filet, estimation chars→tokens (≈ 3,3 car./token en français, 2,8 en JSON) calibrée une fois avec `getTokenCount()`.

## 4. Ce qui change par rapport au plan d'origine (challenge)

1. **Tâche 3.2 supprimée** (export TFLite + tokenizer Kotlin) : l'AAR déjà embarqué contient `EmbeddingEngine` ; le même `.litertlm` sert en CI (Python `litert-lm`) et sur le téléphone. Plus de validation inter-runtime à faire. Coût : 485 Mo à télécharger (contre 100–150 Mo prévus), accepté puisque l'appli télécharge déjà 2–3,6 Go de Gemma.
2. **La base consolidée fournie (192 437 fiches) est la base vectorisée et publiée pour la prochaine compilation** : un workflow d'import la découpe par site, recalcule les champs dérivés (clés avec mapping, tags nutrition), vectorise, publie. Les mises à jour hebdomadaires repartent ensuite des bruts (`.source`) et appliquent `rationalize` (règles du plan v2 de l'utilisateur) + vectorisation incrémentale (cache = fichier publié précédent).
3. **Rationalisation = plan v2 de l'utilisateur** : `filterSingle` par fiche (image unique, cours exclus, note mini par site, nombre d'avis mini, non notées gardées, temps obligatoire, ≥ 3 ingrédients) + déduplication **par titre** (Jaccard ≥ 0,75 sur les mots du titre, avec blocage par filtre de préfixe pour rester linéaire) ; `filterSingle` aussi côté appli sur les fiches lues chaque jour.
4. **Deux familles d'assets dans la release** : `<site>.source.jsonl.gz` (brut : crawler hebdo, viewer, `catalogue-complet.gz`) et `<site>.jsonl.gz` (rationalisé + `embedding` + `nutritionTags` : appli, APK). Vecteurs inline en Base64 (≈ 21 Mo gz pour 80 k fiches) : zéro changement de `importFile`/`android.yml`, pas de jointure à maintenir.
5. **Le mapping s'applique dans `IngredientOverlap.key`** (sinon menu et catalogue divergent) ; `ShoppingListMerger.key` reste intact (données utilisateur). Toutes les clés du catalogue changent → `rationalize` est relancé.
6. **Objectif de performance réaliste** : 80 k vecteurs < 60 ms sur téléphone (< 200 ms en test JVM) au lieu de « 25 k < 15 ms ».
7. **Budget du prompt** : « < 900 tokens » ne vaut que pour le bloc candidates ; budget total système ≈ 1 300 tokens, candidates ajoutées jusqu'au budget puis en forme compacte, et `CandidatePool` ne numérote que ce qui est montré.
8. **Ajouts** : contrats partagés en lot 0 ; `recompute()` sur `CatalogEntry` ; fiches lues par le téléphone sans vecteur (FTS seul, puis vectorisation locale optionnelle) ; cache incrémental = le fichier publié précédent (hash `sha1(MODEL_ID + texte)`) sans asset supplémentaire ; workflow réutilisable `catalog-release.yml` ; lot 6 « contexte LLM ».
9. **Pas de WorkManager** : « fournir l'embedder aux WorkManager » devient « au `CandidateFinder` via `AppContainer` ».

## 5. Lots et tâches

Format de chaque tâche : **Modèle** · fichiers · spécification · tests · critères. Les signatures sont des contrats que les autres tâches importent.

### Lot 0 — Contrats partagés (préalable, un seul PR court)

**0.1 Contrats `core` — Opus 5.5**
- `core/.../catalog/Catalog.kt` : `enum class NutritionTag { LIGHT, HIGH_FIBER, OMEGA3, COMFORT }` ; `CatalogEntry + val nutritionTags: Set<NutritionTag> = emptySet()`, `+ val embedding: String? = null` (défauts → anciens fichiers lisibles ; `encodeDefaults = false` côté CLI → non émis si vide). Extraire de `of()` un calcul partagé `computed(title, names, raws, categories)` (course, kind, months, produce, balanced, keys, nutritionTags), `keysOf(names, raws)` comme point d'édition unique, et **`fun CatalogEntry.recompute(): CatalogEntry`** (noms via `IngredientParser.parseAll(ingredients)`, `embedding` conservé).
- `core/.../catalog/CatalogStore.kt` : `CatalogQuery + val vector: ByteArray? = null`.
- `core/.../ingredients/IngredientMapper.kt` : API publique à **identité** (compile tout de suite) : `enum Status { OK, SPLIT, IGNORE }`, `data class LineMapping(key: String?, status: Status?)`, `mapKey(key)`, `status(key)`, `mapLine(raw)`, `aisleOverride(key): Aisle?`, `searchQueryOverride(key): String?`, `lineText(raw)`.
- `core/src/main/resources/fr/mescourses/core/ingredients/mapping-ingredients.json` = **copie du fichier fourni** (`/root/.claude/uploads/5441cb08-8548-5929-960f-c37c3aadd845/1b9205d4-mapping-ingredients_1.json`) ; `pairings.json` `{ "version": 1, "pairs": [] }`.
- `tools/catalog/data/catalogue-rationalise-v2.jsonl.gz` = copie de l'upload `7d70f643-catalogue-rationalise-v2.jsonl.gz` (+ `tools/catalog/data/README.md` : origine, 192 437 fiches, usage par `catalog-import.yml`, peut être supprimé après la première publication).
- `docs/PLAN-vectorisation.md` = copie de ce plan (pour reprise ultérieure).
- `core/.../catalog/Vectors.kt` : interface `TextEmbedder { suspend fun available(): Boolean; suspend fun embedQuery(text): ByteArray?; suspend fun embedPassages(texts): List<ByteArray>? }`, `object EmbeddingText { const val VERSION = 1; fun passage(e: CatalogEntry): String; fun query(text): String }` (textes : `task: search result | text: {title}. Ingrédients : {keys joinToString ", "}` et `task: search query | text: {texte}`).
- `core/.../ingredients/PairingGraph.kt` : API `affinityScore(keys: Collection<String>): Double`, `bestPairings(key, n = 5): List<Pair<String, Double>>`, `PairingGraph.EMPTY` (implémentation en 1.4).
- `tools/catalog/build.gradle.kts` : `applicationDefaultJvmArgs = listOf("-Xmx6g")`.
- Tests `CatalogTest` : `recompute_redonne_les_memes_champs_calcules`, `fiche_ancienne_sans_embedding_se_lit`. Critère : `./gradlew -p core test` vert, `tools/catalog` compile, aucun comportement modifié.

### Lot 1 — Mapping canonique, nutrition, accords

**1.0 Corrections de `FoodName.kt` (plan v2, phase 1.1) — Sonnet 5.5** (indépendant)
- `core/.../ingredients/FoodName.kt` : retrait de `blancs?` seulement s'il n'est pas suivi de `d'œuf`/`d'oeuf` (`blancs?(?!\s+d['’]o?eufs?)`) ; ajout dans `leading` de `cm`, `pouces?`, `éclats?`, `quartiers?`, `c\.?\s*[àa]\s*(?:thé|the|t)\.?`, `bou`, `paquets?` (biais HelloFresh). Tests `ShoppingCleanupTest`/`IngredientParserTest` : `blanc_d_oeuf_conserve`, `paquet_et_cm_retires`, `cuillere_a_the_retiree` ; tous les tests existants restent verts.

**1.1 `IngredientMapper` complet — Opus 5.5** (après 0.1)
- Chargement `by lazy` de la ressource (`getResourceAsStream`), classes `@Serializable` privées ; `merges` aplatis à la construction (résolution transitive bornée à 50, cycle → `IllegalStateException` nommant la chaîne) ; `lines` indexées par `ShoppingListMerger.key(text)` des deux côtés (tolère accents/casse/pluriel entre JS et Kotlin) ; `lineText` = port Kotlin de `variantOf` (regex `QTY` copiée verbatim du viewer) ; `rayon` → `Aisle.entries.firstOrNull { it.name == rayon } ?: Aisle.entries.firstOrNull { it.label == rayon }` ; `SPLIT` traité comme non mappé (documenté). `internal fun load(json)` + `override(m)`/reset pour les tests. Le fichier réel contient des `merges` dont la cible est elle-même source d'un autre merge : la résolution transitive doit les suivre ; `from` en doublon → dernier gagne, signalé.
- Tests `IngredientMapperTest` : `regroupement_transitif_resolu`, `cycle_de_regroupement_refuse`, `ligne_reconnue_malgre_accents_et_pluriel`, `rayon_par_nom_d_enum_ou_label`, `statut_ignore_et_split`, `line_text_identique_au_viewer` (15 fixtures relevées dans le viewer), `mapping_ingredients_json_est_coherent` (ressource réelle : pas de cycle, cibles résolues, statuts valides, rayons connus, clés en forme normalisée `key == ShoppingListMerger.key(key)` — si une entrée réelle viole une règle, le test l'énumère dans son message et la règle est assouplie en avertissement plutôt qu'en échec, puisque les données sont validées par l'utilisateur).

**1.2 Branchement du mapper — Sonnet 5.5** (après 1.1 et 1.3)
- `IngredientOverlap.key(name) = IngredientMapper.mapKey(rawKey(name))` (`rawKey` = calcul actuel, public).
- `Catalog.kt::keysOf` : par ligne brute `mapLine(raw)` → `IGNORE` sauté, `key` imposée sinon chaîne actuelle (`FoodName.clean` → `Pantry.isStaple` → `IngredientOverlap.key`), puis retrait des clés `status == IGNORE`.
- `ShoppingListMerger.fromRecipe` : filtre les lignes/clés `IGNORE`. `Aisle.of` : `aisleOverride(IngredientOverlap.key(name)) ?: calcul actuel`. `CartPlanner.queryFor` : `custom[ShoppingListMerger.key] ?: searchQueryOverride(IngredientOverlap.key) ?: searchQuery`.
- Tests : `ligne_ignoree_par_le_mapping_n_entre_pas_dans_la_liste`, `rayon_force_par_le_mapping`, `recherche_drive_forcee_par_le_mapping_apres_choix_utilisateur`, `cles_du_catalogue_passent_par_le_mapping`. Critère : tous les tests existants verts avec le mapping vide.

**1.3 `NutritionTags` — Sonnet 5.5** (après 0.1)
- `core/.../catalog/NutritionTags.kt` : `fun of(title, keys, course, minutes): Set<NutritionTag>`, branché dans `computed`. `RecipeFilters.Words` passe `internal`. Règles : `LIGHT` = course SOUPE/SALADE ou titre (léger, light, minceur, vapeur, poché, papillote, grillé) et course ≠ DESSERT et < 2 clés riches (crème, beurre, lardon, fromage, mascarpone, mayonnaise, pâte feuilletée…) ; `HIGH_FIBER` = ≥ 1 légumineuse ou ≥ 2 clés de la liste fibres (brocoli, chou, épinard, poireau, quinoa, boulgour, avoine, riz complet…) ; `OMEGA3` = saumon, maquereau, sardine, hareng, truite, thon, noix, graine de lin/chia, huile de colza/noix ; `COMFORT` = `DishForms` gratin/pâtes/pizza, titre (tartiflette, raclette, lasagne, parmentier, blanquette, bourguignon, carbonara, risotto, purée, cassoulet, brownie, fondant, crumble…), ≥ 2 clés riches, ou PLAT ≥ 90 min.
- Tests `NutritionTagsTest` : `soupe_sans_creme_est_legere`, `gratin_de_pates_est_reconfortant`, `lentilles_fibres`, `saumon_omega3`, `dessert_jamais_leger`, `creme_et_lardons_retirent_leger`.

**1.4 `PairingGraph` — Sonnet 5.5** (après 0.1, indépendant)
- Ressource `pairings.json` `{version:1, pairs:[{a,b,w}]}` (clés en forme `IngredientOverlap.key`, symétriques, `w ∈ (0,1]`, ~20 paires d'amorçage) ; `affinityScore` = moyenne des `w` des paires présentes (0 si < 2 clés) ; `bestPairings` trié.
- Tests `PairingGraphTest` : `score_moyen_des_paires_presentes`, `symetrie`, `meilleurs_accords_tries`, `pairings_json_est_coherent`.

### Lot 2 — Rationalisation (plan v2 de l'utilisateur)

**2.1 `RationalizationConfig` + `CatalogRationalizer.filterSingle` — Sonnet 5.5** (après 0.1)
- `core/.../catalog/CatalogRationalizer.kt` : `@Serializable data class RationalizationConfig(val requireUniqueImage: Boolean = true, val excludedCourses: Set<Course> = setOf(Course.BOISSON), val defaultMinRating: Double = 4.5, val minRatingBySite: Map<String, Double> = mapOf("hellofresh" to 4.0), val minRatingCount: Int = 10, val keepUnrated: Boolean = true, val requireTime: Boolean = true, val minIngredients: Int = 3)` (vérifier le nom exact de la valeur `Course` boisson ; sinon ensemble vide).
- `fun filterSingle(entry: CatalogEntry, config: RationalizationConfig, seenImages: MutableSet<String>? = null): CatalogEntry?` : `null` si titre vide, `ingredients.size < minIngredients`, `course ∈ excludedCourses`, `requireTime && totalMinutes == null`, image déjà vue (`requireUniqueImage`, `imageUrl` non vide, via `seenImages`), note présente avec `ratingCount < minRatingCount` ou `rating < minRatingBySite[siteId] ?: defaultMinRating` ; sans note : gardée si `keepUnrated`. Sinon renvoie `entry.recompute()`.
- `fun process(entries: List<CatalogEntry>, config): Result(kept, report)` : tri `(siteId, url)`, dédoublonnage par `id` (dernier gagne), `filterSingle` séquentiel (image unique globale), puis `TitleDeduplicator` (2.1b, appelé si présent ; sinon identité), `Report(sites: Map<String, SiteReport(read, rejected: Map<String, Int>, kept)>).markdown()`.
- Tests `CatalogRationalizerTest` : `rejet_sans_temps`, `rejet_note_trop_basse_selon_le_site`, `non_notee_gardee`, `image_en_double_rejetee`, `cours_exclu`, `rapport_par_site`, `resultat_deterministe`.

**2.1b Déduplication par titre — Opus 5.5** (après 2.1)
- `core/.../catalog/TitleDeduplicator.kt` : `fun groups(entries, threshold = 0.75, maxPostings = 5_000): List<IntArray>` sur `CatalogQuery.words(title)` (mots sans stop words, singulier) avec filtre de préfixe AllPairs (mots triés par fréquence croissante, préfixe `|w| − ⌈t·|w|⌉ + 1`, index inversé plafonné), vérification Jaccard ≥ t, union-find ; `fun dedupe(entries, …)`: gardée par groupe = max par (`RecipeRating.score`, `ratingCount`, `ingredients.size`, `url asc`). Déterministe. Branché dans `process` via un paramètre `dedupeTitles: Boolean = true` de `RationalizationConfig`.
- Tests `TitleDeduplicatorTest` : `titres_identiques_regroupes`, `titres_proches_regroupes_titres_differents_separes`, `filtre_par_prefixe_ne_rate_aucune_paire` (500 titres aléatoires vs force brute), `la_mieux_notee_gardee`, test lent 200 k titres synthétiques < 2 min (propriété système).

**2.2 CLI `rationalize` et `split` — Sonnet 5.5** (après 2.1 ; 2.1b facultatif)
- `tools/catalog/src/main/kotlin/Main.kt` : `rationalize --in <dir> --out <dir> [--config <file>] [--site <id>]` (écrit `<out>/<site>.jsonl` + `rationalize-report.md`) ; `split --in <fichier.jsonl(.gz)> --out <dir>` (découpe la base consolidée par `siteId` en `<out>/<site>.jsonl`, après `recompute()`, affiche le compte par site) ; helper partagé `readEntries(file)` (`.jsonl` et `.jsonl.gz` par sniff gzip, réutilisé par `stats`) ; `stats` gagne une colonne « avec vecteur » ; commentaire d'en-tête à jour. Critères : `split` sur `tools/catalog/data/catalogue-rationalise-v2.jsonl.gz` produit 11 fichiers totalisant 192 437 lignes ; `rationalize` sur un dossier contenant `jow.jsonl` produit un rapport.

**2.3 `filterSingle` dans l'appli (plan v2, phase 3.3) — Sonnet 5.5** (après 2.1 ; `core` seulement)
- `core/.../catalog/CatalogStore.kt::CatalogUpdater` : paramètre `config: RationalizationConfig = RationalizationConfig()` ; chaque fiche lue est passée dans `CatalogRationalizer.filterSingle(entry, config)` avant `store.upsert` (fiche rejetée → `markEmpty` ou simplement ignorée, à choisir selon le code existant pour ne pas la relire chaque jour). Test `CatalogUpdaterTest.fiche_rejetee_par_la_rationalisation_n_est_pas_inseree`.

### Lot 3 — Vectorisation en CI

**3.1 `tools/catalog/embed.py` — Opus 5.5** (après 0.1)
- `python3 -I embed.py --in <dir> --model <file.litertlm> [--previous <dir>] [--sites a,b] [--batch 32] [--dim 256] [--dry-run]`. Constantes `MODEL_ID = "embeddinggemma-2-740m@256"`, `TEXT_VERSION = 1`, `SCALE = 0.25`. Fonctions `passage_text(entry)` (identique à `EmbeddingText.passage`), `quantize(vec) -> bytes` (`clamp(round(x / 0.25 × 127), −127, 127)` int8 signé, Base64 standard = 344 caractères), `cache_key(text) = sha1(MODEL_ID + "\n" + text)`. Cache = fiches du dossier `--previous` (le `<site>.jsonl.gz` publié précédemment) dont la clé coïncide. Réécrit chaque `<site>.jsonl` en place, résumé réutilisés/calculés/secondes. `--dry-run` : sans modèle, vérifie le cache et écrit `embedder-golden.json` à partir d'un vecteur flottant fixé (fixture partagée avec `VectorsTest`).
- Premier point à établir dans la tâche : API exacte de `litert_lm` Python (`EmbeddingEngine`, `EmbeddingOptions`, `outputSize`) et fonctionnement de la roue sur `ubuntu-latest` x86 ; sinon `ubuntu-24.04-arm` (dépôt public : runners Arm gratuits).
- Tests : `tools/catalog/embed_test.py` (`python3 -I -m unittest`) : quantification, hash, texte ; exécution sur 100 fiches de jow si le modèle est disponible.

**3.2 — supprimée** (voir §4.1).

**3.3 `embedder.json` et modèle — Sonnet 5.5** (indépendant)
- `.github/workflows/embedder-publish.yml` (`workflow_dispatch`) : télécharge `embeddinggemma-2-740m.litertlm` depuis Hugging Face (aucun jeton nécessaire), calcule `sha256`, écrit `embedder.json` `{model, file, dim: 256, textVersion: 1, scale: 0.25, url: <URL HF directe>, bytes, sha256}`, `gh release upload catalogue embedder.json --clobber`. Le modèle lui-même **n'est pas redistribué** dans la release (l'appli et la CI le prennent chez Hugging Face ; cache CI par `actions/cache` sur le sha256).

**3.4 Workflows : import de la base consolidée + publication réutilisable — Opus 5.5** (après 2.2, 3.1, 3.3)
- `.github/workflows/catalog-release.yml` (`workflow_call`, entrée `source: import|raw`) en 3 jobs : `preparer` (mode `raw` : rassemble les bruts comme `publier` aujourd'hui, `stats`, upload `*.source.jsonl.gz` + `*.seen.tsv.gz`, puis `rationalize --in raw --out app` ; mode `import` : `split --in tools/catalog/data/catalogue-rationalise-v2.jsonl.gz --out app` **et** renommage unique des assets actuels `<site>.jsonl.gz` de la release en `<site>.source.jsonl.gz` s'ils n'existent pas encore) → artefact `app-<site>` + rapport dans `$GITHUB_STEP_SUMMARY` → `vectoriser` (matrice des 11 sites, `fail-fast: false`, télécharge le `<site>.jsonl.gz` publié précédent comme cache, modèle via `actions/cache` sur son sha256, `pip install litert-lm`, `embed.py`) → `publier` (gzip -9, `version.txt`, upload `*.jsonl.gz` + `version.txt`, `catalogue-complet.gz` construit depuis `*.source.jsonl.gz`, `gh workflow run android.yml` sur entrée booléenne).
- `.github/workflows/catalog-import.yml` (`workflow_dispatch`) appelle `catalog-release.yml` avec `source: import` : **c'est le run de mise en service** (base consolidée → vecteurs → release → APK).
- `catalog-publish.yml` et `catalog-weekly.yml` deviennent des appelants (`source: raw`) ; job `maj` du weekly : `--pattern "<site>.source.jsonl.gz"` puis renommage avant `build`, **sans repli** sur `<site>.jsonl.gz`. `android.yml` : `rm -f app/src/main/assets/catalog/*.source.jsonl.gz` après le téléchargement. `CatalogSync.loadRemote` : exclure `.source.jsonl.gz` (fait en 4.4).
- Critères : `actionlint` propre ; `catalog-import.yml` produit les trois familles d'assets avec 192 437 fiches vectorisées au total. Transition : désactiver le cron du weekly jusqu'au premier import réussi.

### Lot 4 — Stockage vectoriel et recherche hybride

**4.1 Math vectorielle, index, RRF (`core`) — Opus 5.5** (après 0.1)
- `core/.../catalog/Vectors.kt` : `object Int8Vectors { DIM = 256; SCALE = 0.25f; quantize(FloatArray): ByteArray; dot(a, aOffset, b): Int; encode/decode Base64 }` ; `class Int8VectorIndex(ids: LongArray trié, data: ByteArray contigu)` avec `mask(allowedSortedIds): BooleanArray` (fusion à deux pointeurs), `topK(query, k, allowed): List<Hit(id, score)>` (tas borné, boucle déroulée par 8), `build(rows)` ; `object ReciprocalRankFusion { fuse(rankings: List<List<Long>>, k = 60): List<Long> }`.
- `CatalogRanking` : lecture `perSite × 3` quand `vector != null`, `rank(..., keepOrder = random != null || vector != null)` (l'ordre reçu par site est conservé, bonus « tous les mots dans le titre » maintenu). `InMemoryCatalog` (test) : force brute quand `vector != null`.
- Tests `VectorsTest` : `quantification_identique_au_golden`, `produit_scalaire_symetrique_et_borne`, `top_k_egal_a_la_force_brute` (10 k aléatoires), `masque_de_filtres_par_fusion_d_ids`, `rrf_favorise_le_present_dans_les_deux_listes`, `index_de_80k_vecteurs_sous_200_ms` (temps imprimé), `texte_de_passage_stable` ; `CatalogRankingTest.avec_vecteur_l_ordre_semantique_de_chaque_site_est_garde`.

**4.2 Schéma Room — Sonnet 5.5** (après 0.1)
- `app/.../data/CatalogDatabase.kt` : `CatalogRecipeEntity + nutritionTags: String` (noms joints par virgule), `+ embedding: ByteArray?` (`@ColumnInfo(typeAffinity = BLOB)`) ; `of()` décode le Base64, `toEntry()` reconstitue les tags (`embedding = null`) ; `version = 2` (destructif accepté : la base se re-télécharge et l'écran de choix réapparaît — à noter dans la PR). DAO : `vectors(): List<IdVector>`, `ids(@RawQuery)`, `byIds(ids)`, `setEmbedding(id, bytes)`, `withoutEmbedding(limit)`. Critère : `./gradlew :app:assembleDebug` vert ; import d'un `.jsonl` avec `embedding` remplit le BLOB.

**4.3 `LiteRtTextEmbedder` — Opus 5.5** (après 4.1)
- `app/.../llm/LiteRtTextEmbedder.kt : TextEmbedder` sur `EmbeddingEngine` (signatures confirmées §2) : init paresseuse sous `Mutex`, `EmbeddingOptions(normalize = true, outputSize = 256)`, backend CPU d'abord (GPU/NPU `SM8550` en option ultérieure), `close()` sur `onTrimMemory` via `ModelController.releaseIfIdle` ; `embedQuery = Int8Vectors.quantize(computeEmbedding(InputData.Text(EmbeddingText.query(text))))`. Fichier attendu : `files/embedder/embeddinggemma-2-740m.litertlm`. Instancié dans `AppContainer` (`val embedder: TextEmbedder`). Critère : fumée manuelle, temps par requête loggé.

**4.4 Téléchargement de l'embedder — Sonnet 5.5** (après 3.3, 4.3)
- `CatalogSync` : `loadRemote` lit `embedder.json` et exclut `.source.jsonl.gz` ; `CatalogStatus + embedderRemote, embedderInstalled` ; `installEmbedder()` réutilise `ModelManager.download` (ajouter un paramètre `dir`, `installed()` restreint à `modelsDir`) + vérification `sha256` ; `removeEmbedder()`. UI : une ligne « Recherche par le sens — 485 Mo » dans l'écran des bases (`CatalogScreen.kt`). Critère : téléchargement reprenable, état visible.

**4.5 Recherche hybride — Opus 5.5** (après 4.1, 4.2, 4.3)
- `RoomCatalogStore` : `whereClause(query)` extrait du SQL actuel (source unique des filtres durs) ; `VectorIndexCache` (chargement paresseux de `SELECT id, embedding WHERE embedding IS NOT NULL`, invalidation par compteur dans `upsert`/`deleteSite`/`setEmbedding`) ; `search` : si `vector != null` → ids filtrés en SQL → masque → `topK(limit × 3)` ; si `words` non vides → ids FTS (`limit × 3`) ; `ReciprocalRankFusion.fuse(…, k = 60)` → `take(limit)` → `byIds` réordonné ; `seed` ignoré avec vecteur ; sans vecteur, chemin actuel inchangé. Les fiches sans embedding ne participent qu'à la branche FTS.
- `DiscoverViewModels.kt::search()` : `vector = c.embedder.embedQuery(texte)` hors thread UI quand le texte n'est pas vide et l'embedder prêt ; anti-rebond 250 ms sur la frappe (`snapshotFlow`/`debounce`), bouton = immédiat.
- Critères : sans embedder, résultats identiques ; avec, « quelque chose de réconfortant pour ce soir » renvoie des plats pertinents ; `topK` loggé < 60 ms sur 80 k.

**4.6 Vecteurs des fiches lues par le téléphone — Sonnet 5.5** (après 4.5, optionnel)
- `CatalogSync.update()` : si embedder disponible, `withoutEmbedding(500)` → `embedPassages(EmbeddingText.passage)` → `setEmbedding` → invalidation de l'index. Critère : le nombre de lignes sans embedding décroît après une mise à jour.

### Lot 5 — Menu et Découvrir

**5.1 `CandidateFinder` enrichi — Opus 5.5** (après 0.1, 1.3, 1.4)
- `MenuCandidates.kt` : constructeur `+ embedder: TextEmbedder? = null, pairings: PairingGraph = PairingGraph.EMPTY` ; `Spec + semantic: String?` ; `wishSpec(wish)` : 1–4 mots utiles → mots + `semantic = phrase` ; > 4 mots → **plus de null** : `words` vides, `text = 4 premiers mots` (sites), `semantic = phrase` (vecteur) ; `specs()` pour `useUp` : `bestPairings(IngredientOverlap.key(reste), 2)` → mot = filtre dur, `semantic = "<reste> avec <accord1> et <accord2>"` ; `findLocal`/`search` : `CatalogQuery(..., vector = spec.semantic?.let { embedder?.embedQuery(it) })` (une fois par spec) ; `Candidate + tags: Set<NutritionTag>` (depuis `CatalogEntry.nutritionTags` ou `NutritionTags.of`) ; `line(number, compact = false)` ajoute 2 tags max, forme compacte (titre ≤ 48 car., 3 ingrédients, 1 tag), ligne ≤ 120 caractères.
- Tests `MenuPlannerTest` : `envie_longue_devient_une_recherche_semantique`, `restes_a_ecouler_filtrent_par_mot_et_classent_par_accord`, `ligne_de_candidate_compacte_et_etiquettes`, `sans_embedder_le_catalogue_se_cherche_comme_avant`.

**5.2 Scoring nutrition/accords et prompt budgété — Sonnet 5.5** (après 5.1)
- `MenuScoring.kt` : `Chosen + tags` ; `score(…, pairings = EMPTY)` : `+ 3.0 × affinityScore(c.keys ∪ clés choisies)` ; `COMFORT` : `−3.0 × n` si `n ≥ 1` déjà choisi ; `LIGHT` : `+2` si ≥ 1 COMFORT choisi ; `OMEGA3` : `+2` si aucun OMEGA3/poisson choisi ; `compose`/`improveSharing`/`replacement` propagent `pairings`.
- `core/.../llm/TokenEstimate.kt` (si le lot 6 n'est pas encore fusionné, le créer ici avec les mêmes constantes) ; `MenuPrompt.systemWeb(…, budgetTokens = 1_300): PromptBuild(text, shown)` : consignes d'abord, candidates ajoutées tant que le budget tient, puis en forme compacte, puis arrêt ; `MenuViewModel.kt:360` : `SHORTLIST` devient un plafond, le pool est tronqué à `shown`.
- Tests : `score_harmonie_et_confort`, `score_sans_graphe_identique_a_avant` (non-régression), `prompt_coupe_les_candidates_au_budget`, `bilans_et_prompt_bornes_pour_le_contexte_du_telephone` renforcé (`TokenEstimate.text(prompt) ≤ 1_300` et `length < 4_500`).

**5.3 Câblage appli — Sonnet 5.5** (après 4.3, 4.5, 5.2)
- `AppContainer.kt` : `embedder`, `pairings` (`PairingGraph.load`), `CandidateFinder(..., embedder, pairings)` (L310), `menuSystemPrompt` renvoie `PromptBuild` ; `MenuViewModel` passe `pairings` à `compose/replacement/improveSharing` (L360, L399, L499, L725) ; Découvrir déjà fait en 4.5.
- Critères : « poêlée de légumes réconfortante pour un soir froid » (7 mots) donne des résultats du catalogue ; `useUp = ["chou rouge"]` renvoie des candidates contenant « chou rouge » classées pomme/lardons en tête ; compilation verte, `./gradlew -p core test` vert.

### Lot 6 — Gestion de la fenêtre de contexte du LLM

**6.1 Politique en `core` (nouveau paquet `fr.mescourses.core.llm`) — Sonnet 5.5** (indépendant)
- `TokenEstimate` (`CHARS_PER_TOKEN_TEXT = 3.3`, `CHARS_PER_TOKEN_JSON = 2.8`, `text()`, `json()`, `tools(openApiJsons)`) ; `ContextBudget(maxTokens, reserve)` (`softLimit`, `menu(max)` réserve 1 000, `courses(max)` réserve 1 400) ; `Msg(role, text)`, `Transcript(turns: List<Turn(user, assistant, toolTokens)>)` ; `sealed Rollover { Keep; Recreate(reason, initial) }` ; `ConversationPolicy(budget, maxToolTurns = 8).decide(tokenCount: Int?, transcript, nextMessage, expectedToolTokens, carryOver)` : `Recreate` si `tokenCount (ou estimation) + estimate(nextMessage) + expectedToolTokens + 300 > softLimit` ou trop de tours à outils ; `CarryOver.courses(cartSummary, seenProducts, lastAnswer, maxChars = 900)`, `menu(context, lastAnswer)`, `chat(lastAnswer, 600)` — toujours une paire USER (état) / ASSISTANT (« D'accord, je continue à partir de là. ») pour respecter l'alternance du chat template.
- Tests `ConversationPolicyTest` : `estimation_tokens_calibree_sur_un_prompt_menu` (±25 % d'une valeur mesurée sur l'appareil, constante à relever une fois via `getTokenCount()`), `bascule_avant_le_tour_qui_deborde`, `bascule_apres_n_tours_a_outils`, `report_courses_borne_et_alterne_les_roles`, `sans_token_count_on_estime_depuis_la_transcription`.

**6.2 Moteur — Opus 5.5** (indépendant)
- `LlmEngine.kt` : `load(modelPath, preferGpu, maxTokens: Int?)`, `maxTokens(): Int`, `newConversation(systemPrompt, tools, initial: List<Msg> = emptyList(), prefill = false)`, `tokenCount(): Int?`, `cancel()`, `generateOnce(system, prompt, jsonSchema: String? = null)` ; `ChatEvent.ToolResult + size: Int` (taille du résultat renvoyé au modèle).
- `LiteRtLmEngine.kt` : `EngineConfig(…, maxNumTokens)` avec boucle de repli `[configuré, 4096] × [GPU, CPU]` ; `ConversationConfig(systemInstruction, initialMessages = initial.map { Message(Role, Contents.of(text)) }, tools, samplerConfig, thinkingConfig = ThinkingConfig(enableThinking = false), prefillPrefaceOnInit = prefill, enableResponseFormat = jsonSchema != null, maxOutputToken)` ; `sendMessage(…, responseFormat = ResponseFormat(JSON_SCHEMA, schema))` dans `generateOnce` ; `cancelProcess()` dans `cancel()`. Critère : compilation ; fumée sur l'appareil avec log de `tokenCount` après chaque tour.

**6.3 `ModelController` et propriété — Opus 5.5** (après 6.1, 6.2)
- `startConversation(id, system, tools, initial = ∅, prefill = false)` ; `send(id, message)` (si `!owns(id)` et un `Suspended(id)` existe → restauration par `CarryOver`, sinon erreur explicite) ; `generateOnce` enregistre `suspended[owner] = Suspended(system, tools, transcript)` **avant** de fermer ; tient la `Transcript` du propriétaire (texte, réponse, tokens des résultats d'outils) ; `isContextFull` unique (suppression du doublon de `MenuViewModel`) ; `budget(kind) = ContextBudget.menu/courses(llm.maxTokens())` ; `StateFlow<Boolean> chatForeground` consulté par `ChefWork` pour différer la file d'étapes pendant une discussion Courses active. Réglage `Settings.contextTokens` (« Auto » par défaut). Critère : lancer la file d'étapes pendant une discussion Courses ; le message suivant mentionne encore le produit précédent.

**6.4 Assistant Courses — Sonnet 5.5** (après 6.3)
- `ShoppingChatViewModel` (`CartViewModel.kt:298-371`) : `startConversation(owner, …, prefill = true)` à l'entrée de l'écran ; avant chaque `chat(t)` : `policy.decide(c.model.tokenCount(), transcript, t, expectedToolTokens = 700) { CarryOver.courses(cartSummary(), seen, lastAnswer) }` → ligne « ↻ Nouvelle conversation (mémoire) » + `startConversation(…, initial)` ; `try/catch isContextFull` conservé comme filet ; `cartSummary()` via `Cart.compact()` de `CarrefourTools` rendu public et tronqué. Critère : 10 recherches de suite sans erreur ni perte du panier dans le fil.

**6.5 Menu — Opus 5.5** (après 6.3, 5.2)
- `MenuViewModel.kt` : `exchange()` lance `warmNext()` (`startConversation(adjusting = true, prefill = true)`) après chaque réponse ; `send()` réutilise la conversation chaude si `owns("menu")` et règle `menuTools.maxChanges` (devient `var` + `resetChanges()` dans `MenuTools`) ; `propose()` pré-chauffe après `c.candidates.set(...)` (pendant `addCourseDishes`) ; sur `Recreate`/contexte plein : `CarryOver.menu(MenuPrompt.context(draft), lastAnswer)` ; `MenuValidator`/`RecipeStepsWriter` passent le schéma JSON de `RecipeResponseParser` à `generateOnce`. Critères : latence au 1ᵉʳ token < 1,5 s sur « Ajuster » après le 1ᵉʳ tour ; aucune bulle ne contient `<channel|>` ; taux de relance JSON < 10 % (log).

## 6. Affectation des modèles (récapitulatif) et orchestration

| Opus 5.5 (jugement, algorithmes, intégration) | Sonnet 5.5 (local, spécifié) |
|---|---|
| 0.1 contrats · 1.1 mapper · 2.1b dédoublonnage titres · 3.1 embed.py · 3.4 workflows · 4.1 vecteurs/RRF · 4.3 embedder · 4.5 hybride · 5.1 finder · 6.2 moteur · 6.3 controller · 6.5 menu | 1.0 FoodName · 1.2 branchement · 1.3 nutrition · 1.4 accords · 2.1 filterSingle · 2.2 CLI · 2.3 updater · 3.3 embedder.json · 4.2 Room · 4.4 téléchargement · 4.6 vecteurs téléphone · 5.2 scoring/prompt · 5.3 câblage · 6.1 politique core · 6.4 courses |

Escalade : un échec, un résultat incomplet ou une ambiguïté chez un agent Sonnet 5.5 relance immédiatement la tâche sur Opus 5.5 avec le contexte de l'échec.

Mécanique : un sous-agent par tâche via l'outil Agent (`model: "opus"` ou `"sonnet"`, `isolation: "worktree"` sur la branche `claude/inspiring-curie-5xcuup`), brief autonome = section de tâche ci-dessus + §2 (faits) + commandes de vérification ; chaque agent termine par `./gradlew -p core test` (et `:app:assembleDebug` si `app/` est touché), commit sur sa worktree ; fusion des worktrees dans la branche par l'orchestrateur à la fin de chaque vague, puis `./gradlew -p core test` global avant la vague suivante. Fichiers partagés à ne pas donner à deux agents d'une même vague : `Catalog.kt` (0.1 / 1.3 / 1.2), `Main.kt` (2.2), `MenuPlannerTest.kt` (5.1 puis 5.2), `AppContainer.kt`/`MenuViewModel.kt` (5.3 puis 6.5), workflows (3.4 seul).

Vagues :
1. **V1** : 0.1 (contrats + copie des fichiers fournis + plan dans `docs/`) ; en parallèle, indépendants : 1.0, 3.3, 6.1, 6.2.
2. **V2** (parallèle, fichiers disjoints) : 1.1, 1.3, 1.4, 2.1, 3.1, 4.1, 4.2.
3. **V3** : 1.2 (après 1.1 et 1.3), 2.1b, 2.2, 2.3, 4.3, 5.1, 6.3.
4. **V4** : 3.4, 4.4, 4.5, 5.2, 6.4.
5. **V5** : 5.3, 6.5, 4.6.
6. **Mise en service** (manuelle, après fusion et push) : run de `embedder-publish.yml` → run de `catalog-import.yml` (base consolidée → vecteurs → release) → `android.yml` → réactivation du cron hebdo.

## 7. Vérification de bout en bout

- `./gradlew -p core test` après chaque vague ; `./gradlew :app:assembleDebug` dès qu'`app/` change ; `actionlint` sur les workflows (3.4).
- `./gradlew -p tools/catalog run --args="rationalize --in <dossier avec jow.jsonl> --out <sortie>"` → rapport lisible, fiches gardées cohérentes.
- `python3 -I tools/catalog/embed.py --dry-run` + `embed_test.py` ; premier run réel en CI sur un seul site (`--sites jow`) pour mesurer le débit et valider la roue `litert-lm`.
- Sur l'appareil (manuel) : installer l'embedder depuis l'écran des bases ; Découvrir avec une phrase longue ; Menu avec une envie de 7 mots et un reste à écouler ; « Ajuster » deux fois de suite (latence) ; Courses : 10 recherches enchaînées ; lancer l'écriture des étapes pendant une discussion Courses ; vérifier les logs `tokenCount`/`topK`.

## 8. Risques et décisions ouvertes

1. Roue `litert-lm` x86 absente ou API différente de la doc → bascule sur runner Arm, découverte au premier run (3.1 prévoit `--dry-run`).
2. `rationalize` sur les bruts (mises à jour hebdo) doit redonner approximativement la base consolidée : le rapport du premier run hebdo compare les comptes par site avec les 192 437 fiches importées ; écart notable → ajuster `RationalizationConfig`.
2b. Dépôt : la base consolidée (30 Mo) entre dans l'historique git ; à retirer du dépôt après le premier import réussi si l'utilisateur le souhaite (elle reste dans la release sous forme découpée).
3. Transition `.source` : cron hebdo désactivé jusqu'au premier publish réussi (sinon perte de fiches).
4. Bump Room destructif : base locale vidée, re-téléchargement, écran de choix (accepté par l'énoncé, à mentionner dans les notes de version).
5. `maxNumTokens` 6 144/8 192 : à calibrer (mémoire GPU à l'`initialize()`, qualité des appels d'outils) ; repli 4 096 automatique.
6. Embedder 485 Mo (contre 100–150 Mo prévus) : accepté pour éviter un second runtime et un tokenizer maison ; variante NPU `SM8550` possible plus tard.
7. Clés changeant avec le mapping : `MenuHistory` compare d'anciennes et de nouvelles clés pendant une semaine (dégradation bénigne).
