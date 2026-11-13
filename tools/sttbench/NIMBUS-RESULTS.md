# Résultats du banc STT — nimbus, 12 novembre 2026 (Europe/Paris)

Campagne `20:00:28`–`20:12:42` Europe/Paris, **sur nimbus** (`localhost`, pas de saut réseau PC). 78 clips CaFE identiques à [BOX-RESULTS.md](BOX-RESULTS.md) (4 acteurs adultes 01, 02, 09, 12, acteur 07 exclu, 6 phrases officielles + 10 variations ffmpeg, 436 mots). Warmup 1, **2 passages chauds** (médiane et p90 de latence). Lecture seule sur Lemonade et Kyutai : **aucun** `POST /load`, **aucun** arrêt de `pockettts-server`, `kyutai`, phonon, sidecar, nanobot de bob.

Le PC RoadRunner était en ligne ; le banc a tourné en SSH depuis cette machine. PR #30 était déjà fusionnée : ces résultats vont dans une **nouvelle** PR depuis `main`.

## Inventaire (lecture seule)

### Lemonade `:13305`

Processus `lemonade` (`/usr/bin/lemond`, ~115 Mo RSS) + `whisper-server` **vulkan** déjà chargé sur `:8001` avec `ggml-small.bin`.

Whisper / transcription **téléchargés** :

| id | Recette | Taille | État |
|---|---|---:|---|
| **Whisper-Small** | whispercpp (`ggerganov/whisper.cpp:ggml-small.bin`) | 0,454 Go | **téléchargé et chargé** (c'est le STT de l'app) |
| Qwen2.5-Omni-7B-GGUF | llamacpp, label `chat-transcription` | 6,82 Go | téléchargé, pas un STT dédié ; **non utilisé** |
| gemma4-it-e2b-FLM / gemma4e-flash-e2b-FLM | flm, `chat-transcription` | 6 Go | téléchargés (LLM audio), **non utilisés** |

Au catalogue (`?show_all=true`, 200 modèles) mais **non téléchargés** : Whisper-Tiny, Base, Medium, Large-v3, Large-v3-Turbo, `whisper-v3-turbo-FLM`, Moonshine Tiny/Small/Medium (streaming). **Aucun n'a été chargé** (un `POST /load` aurait pu décharger Whisper-Small).

Cache NPU optionnel `amd/whisper-small-onnx-npu` : **absent**. Le serveur tourne en **vulkan**.

### Kyutai `:8080` (conteneur existant, non modifié)

- Image `kyutai-stt-openai-api`, `python app.py`, saine depuis ~20 h.
- `/health` : `model_loaded=true`, `api_format=openai_whisper_compatible`.
- `/v1/models` : `whisper-1`, **`kyutai/stt-1b-en_fr`**. `MODEL_NAME=kyutai/stt-1b-en_fr`, `CUDA_VISIBLE_DEVICES=0`. Licence du modèle : **CC-BY-4.0**.
- RAM au repos (avant le banc) : **665 Mo**. Pendant le banc : pic **2,85 Go**, encore **~2,7 Go** après (allocations d'inférence ; le conteneur n'a **pas** été redémarré).

### Sidecar `:13306`

Proxy `/v1/audio/transcriptions` vers Lemonade (même précision STT que Whisper-Small). `/v1/models` reflète le catalogue Lemonade. Pas un moteur distinct ; non relancé pour le WER. Le dépôt local de nimbus était **en retard** sur `main` au moment de l'inventaire (commit de la PR sidecar #1) — à jour seulement pour les tests matériel qui suivent, `#10` étant fusionnée.

### Parakeet (temporaire, `:13312`)

Conteneur `sttbench-stt-parakeet-1`, image 988 Mo, wrapper OpenAI `serve.py`, modèle int8 sherpa-onnx (CC-BY-4.0 NVIDIA, 671 Mo). Démarré pour le banc, **arrêté et image supprimée** ensuite. Ports protégés non utilisés.

## Tableau d'ensemble (nimbus)

Latence = médiane des passages **chauds** (après 1 warmup). p90 = 90ᵉ percentile des médianes par clip. RAM = pic `docker stats` / RSS processus, pas le ΔRSS du client HTTP (toujours 0).

| moteur | licence | WER | émotion | phrases 2–6 | variation | phrase 1 | parfaits | coupures début / fin | extra début | latence méd. / p90 (s) | RTF méd. | froid (s) | RAM pic |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **Parakeet TDT 0.6B v3 int8** (temporaire `:13312`) | CC-BY-4.0 (NVIDIA) | **18,8 %** | **9,5 %** | **31,7 %** | **0 %** | **7,0 %** | **43 / 78** | 6 % / 1 % | 4 % | **0,154 / 0,175** | **0,036** | **0,126** | **930 Mo** |
| Lemonade Whisper-Small (vulkan, déjà en service) | MIT (Whisper) ; Lemonade Apache-2.0 | 34,9 % | 19,6 % | 57,2 % | 0 % | 14,5 % | 30 / 78 | 3 % / 0 % | 8 % | 0,229 / 0,249 | 0,055 | 0,254 | **165 Mo** (`whisper-server`) + 115 Mo (`lemond`) |
| Kyutai `stt-1b-en_fr` (conteneur existant `:8080`) | CC-BY-4.0 | 47,9 % | 49,4 % | 55,3 % | 18,3 % | 41,2 % | 4 / 78 | 12 % / **46 %** | 13 % | 4,213 / 5,293 | 0,956 | 3,269 | **2,85 Go** (repos 665 Mo) |

Parakeet : 0 clip à WER > 1, 1 clip vide (`cafe-12-fear-2-s1`, comme sur la box). Lemonade : 4 clips WER > 1, 0 vide. Kyutai : 6 clips WER > 1, 3 vides, et souvent une **coupure de fin** (« Un cheval fou dans mon » sans *jardin*) ou un **changement de langue** (anglais, parfois allemand / autre) sur les voix très émotionnelles, malgré `language=fr`.

## Comparaison avec la box CPU

Les **WER** sont très proches de [BOX-RESULTS.md](BOX-RESULTS.md) (Parakeet 19,3 % → 18,8 % ; faster-whisper small 39,4 % vs Lemonade Whisper-Small 34,9 %, runtime différent — whisper.cpp vulkan plutôt que CTranslate2). Les **latences ne se comparent pas** à la box : ici Lemonade fait 0,23 s (contre 0,70 s pour fw-small sur la box) et Parakeet 0,15 s (contre 0,24 s). Kyutai n'avait pas été mesuré sur la box.

## Recommandation pour le robot

1. **Parakeet TDT 0.6B v3 int8** est le plus précis **et** le plus rapide sur ce jeu, déjà en OpenAI-compatible : environ **deux fois moins d'erreurs** que le Whisper-Small actuel, quasiment pas d'hallucination, RTF 0,036, ~930 Mo de RAM — tout à fait tenable sur nimbus (90 Go). Pour le mettre en service : reparcourir le compose sur **13312** (ou brancher le sidecar dessus) et **attribuer NVIDIA** (CC-BY-4.0). Le STT de l'app pointe aujourd'hui le sidecar → Lemonade.
2. **Garder Whisper-Small / Lemonade en repli** : déjà intégré, très léger (165 Mo), déjà rapide (0,23 s, p90 0,25 s), parfait sur les variations, nettement devant Kyutai. Ne pas charger Tiny/Base/Turbo sans mesure, et jamais via un `/load` qui éjecterait Small pendant que le robot tourne.
3. **Ne pas basculer le robot sur Kyutai** en l'état : WER le plus haut, **46 % de coupures en fin de phrase**, changements de langue, ~4,2 s de latence (presque temps réel, trop lent pour un tour vocal), pic mémoire 2,85 Go. Le wrapper OpenAI fonctionne, le modèle `stt-1b-en_fr` n'est simplement pas le bon compromis ici.

## Nettoyage nimbus

- Conteneur `sttbench-stt-parakeet-1` arrêté et **supprimé**.
- Image `sttbench-stt-parakeet:latest` (988 Mo) **supprimée**. Aucune image `sttbench` / `parakeet` restante. Les autres images (kyutai, pockettts, phonon, sidecar, vllm, etc.) **intactes**.
- Poids Parakeet (671 Mo) et WAV CaFE **effacés**.
- Restent des scripts et les JSON de campagne sous `/home/grokbot/sttbench/` (quelques centaines de Ko) et `/home/grokbot/nimbus-stt-inventory.py`. Disque : 63 Go libres (94 %), contre 64 Go (93 %) avant le banc — écart probablement dû au cache de build Docker ; aucune image candidate conservée.
- Kyutai, pockettts, phonon, sidecar, qdrant, portainer, Lemonade : toujours en cours. Kyutai est resté à ~2,7 Go après les requêtes (pas redémarré, consigne de ne pas y toucher).
