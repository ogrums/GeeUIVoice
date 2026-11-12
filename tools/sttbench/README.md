# Banc d'essai STT français (RUX)

Comparer la **précision** (WER), les **coupures** en début/fin de phrase, la **latence** et le **coût** (RTF, RAM) des moteurs de reconnaissance vocale, en français, dans les conditions du robot.

Le jeu de test et les scripts vivent ici. **Aucun audio n'est commité** (CaFE est [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)). Le banc se lance sur nimbus (chiffres comparables) ou, à défaut, sur la box (WER utilisable, **temps non comparables**).

## Ce que le banc ne fait pas

- Il n'arrête et ne reconfigure **aucun** conteneur déjà là : `pockettts-server`, `kyutai`, le nanobot de bob.
- Il n'appelle **pas** `POST /api/v1/load` sur Lemonade par défaut : charger une variante Whisper peut décharger le modèle en service. On liste les modèles (`GET /api/v1/models` et `?show_all=true`) ; on ne charge que si on le décide explicitement, puis on remet Whisper-Small.
- Les candidats temporaires n'écoutent que les ports **13310–13314**. Jamais 8080, 8880, 8900, 13305, 13306, 49112.

## Jeu de test

Phrases officielles CaFE v1.1 (`Readme.txt` du zip 48 kHz) :

1. Un cheval fou dans mon jardin
2. Deux ânes aigris au pelage brun
3. Trois cygnes aveugles au bord du lac
4. Quatre vieilles truies éléphantesques
5. Cinq pumas fiers et passionnés
6. Six ours aimants domestiqués

L'acteur **07 a 15 ans** : il est exclu, comme pour les tests d'émotion.

| Condition | Contenu | Intérêt |
|---|---|---|
| `cafe-emotion` | Phrase 1, 4 acteurs adultes (01, 02, 09, 12), 6 émotions en intensité forte + neutre | Voix réelles, émotions (souvent plus difficiles pour le STT) |
| `cafe-sentences` | Les 6 phrases, mêmes acteurs, neutre + joie forte | Variété phonétique (ânes, éléphantesques, passionnés…) |
| `variation` | speed 0,8 / 1,3, gain 0,3, pitch ±3 demi-tons, via ffmpeg | Proche des variations voicetest, **sans Phonon** |
| `phonon` | Phrases de `tools/voicetest/phrases.json` synthétisées par Phonon (`http://nimbus:8880`) | Voix TTS de la maison, seulement si nimbus est joignable |
| `chaine` | Enregistrements micro d'une campagne voicetest `--mode chaine`, texte connu | Le plus réaliste (robot / pièce), si les WAV sont disponibles sur le PC |

Préparer (exemple, CaFE déjà sur la machine de travail, **pas** téléchargé sur nimbus si le disque est juste) :

```bash
python3 sttbench.py prepare \
  --cafe-zip /chemin/CaFE_48k.zip \
  --variations \
  --phrases ../voicetest/phrases.json \
  --out-wavs wavs --out-manifest manifest.json
# optionnel, nimbus joignable :
#   --phonon http://nimbus:8880
#   --chaine-dir /chemin/campaign-...   # wav + même id que phrases.json, ou .txt à côté
```

## Métriques

- **WER** principal : casse et ponctuation ignorées, **accents conservés** (`ânes` ≠ `anes`), chiffres 0–20 en lettres (`2` = `deux`), élisions séparées (`c'est` → `c'` + `est`) sauf `aujourd'hui`.
- **WER fold** : même chose après repli des accents (indicatif).
- **Coupure début / fin** : suppressions alignées en tête ou en queue de la référence. **Extra** : insertions en tête ou en queue (hallucinations).
- **Latence** : premier passage = froid (warmup) ; ensuite médiane des passages chauds. **RTF** = temps / durée de l'audio.
- **RAM** : ΔRSS au chargement pour les moteurs locaux. Les moteurs HTTP (Lemonade, Kyutai) ne publient pas leur RAM ici, sauf lecture de `docker stats` à la main.

## Inventaire nimbus (lecture seule)

Depuis une machine qui joint nimbus (typiquement RoadRunner en SSH) :

```bash
python3 sttbench.py inventory --out out/inventory.json
```

Sondes : `/health`, `/v1/models`, `/api/v1/models`, `/api/v1/models?show_all=true`. Rien n'est chargé ni arrêté. Lemonade est sur `http://nimbus:13305/api/v1` ; Kyutai sur `http://nimbus:8080` (l'API officielle est plutôt un WebSocket : l'inventaire teste aussi un éventuel `/v1/audio/transcriptions` sans l'exiger). Le sidecar `http://nimbus:13306/v1` est un proxy vers le même Whisper : même précision, pas un moteur distinct.

## Lancer le banc

```bash
# Moteurs locaux déjà installés dans un venv :
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt

# Exemple : Vosk small FR (41 Mo, Apache-2.0)
#   mkdir -p models && cd models
#   curl -LO https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip
#   unzip vosk-model-small-fr-0.22.zip && cd ..

python3 sttbench.py run \
  --manifest manifest.json \
  --engine vosk-small-fr --engine fw-tiny --engine fw-base --engine fw-small \
  --model-root models \
  --out out/campaign.json \
  --note "hôte et contexte"
```

Pour Lemonade / Kyutai déjà en service, ajouter `--engine lemonade` / `--engine kyutai` (voir `candidates.json`). Ne pas lancer de nouveau Whisper sur 13305.

Candidats temporaires en OpenAI-compatible, ports 13310+ :

```bash
# voir docker-compose.candidates.yml et nimbus-run.sh
docker compose -f docker-compose.candidates.yml --profile vosk up -d --build
# ... run ...
docker compose -f docker-compose.candidates.yml --profile vosk down
```

Après le merge, **revenir aux services d'origine**. Avec `--clean`, `nimbus-run.sh` arrête uniquement ces profils. Les poids dans `./models` restent jusqu'à un `rm` volontaire (le disque de nimbus était déjà chargé).

## Candidats et licences

Le fichier `candidates.json` est la source. Résumé :

| id | Moteur | Taille approx. | Licence | FR | Défaut |
|---|---|---|---|---|---|
| lemonade | Whisper via Lemonade (déjà sur nimbus) | selon variante | MIT (Whisper) | oui | inventaire |
| kyutai | conteneur existant :8080, souvent `stt-1b-en_fr` | ~1 B | CC-BY-4.0 | oui (à confirmer sur le conteneur) | inventaire, **ne pas modifier** |
| fw-tiny / base / small | faster-whisper int8 CPU | 75 / 140 / 470 Mo | MIT | oui (multilingue) | oui |
| fw-turbo | faster-whisper large-v3-turbo | ~1,6 Go | MIT | oui | non (plus lourd) |
| fw-distil-fr-dec2 | bofenghuang whisper-large-v3-french-distil-dec2 | ~0,8 B params | MIT | spécialisé FR | non (1er lancement = conversion HF) |
| vosk-small-fr | Vosk 0.22 small | 41 Mo | Apache-2.0 | oui | oui |
| parakeet-tdt-0.6b-v3 | NVIDIA, sherpa-onnx int8 | 456 Mo | CC-BY-4.0 (attribution) | oui, 25 langues EU | non (recommandé à mesurer sur nimbus) |
| sherpa-zipformer-fr | sherpa-onnx FR 2023-04-14 | ~100–250 Mo | Apache-2.0 + licence du modèle | oui | non |
| canary-1b-flash | NVIDIA | 1 B | CC-BY-4.0 | oui | non, trop lourd pour l'objectif « light » |
| moonshine | Useful Sensors | petit | MIT (EN) ; hors-EN souvent NC | incertain | **exclu** (licence) |
| SenseVoice Small | FunASR | ~228 Mo | — | **non** (zh/yue/en/ja/ko) | **exclu** |

Vosk grand (`vosk-model-fr-0.22`, 1,4 Go, Apache-2.0) est optionnel. Éviter `vosk-model-fr-0.6-linto` (AGPL) et `pguyot` (CC BY-NC-SA) sauf décision explicite.


## Résultats déjà obtenus (box, 12 nov. 2026)

Voir [BOX-RESULTS.md](BOX-RESULTS.md). En résumé, sur 78 clips CaFE (4 acteurs adultes, 6 phrases + variations ffmpeg), **Parakeet TDT 0.6B v3 int8** (sherpa-onnx, CC-BY-4.0) fait 19,3 % de WER, contre 39,4 % pour faster-whisper small, 46,8 % pour Vosk small FR, 57,6 % / 95 % pour Whisper base / tiny. Lemonade et Kyutai n'ont pas été mesurés (nimbus injoignable). Les temps de la box **ne sont pas** ceux de nimbus.

faster-whisper est appelé avec `condition_on_previous_text=False` (tours isolés du robot) et un chargement PCM via `wave`+numpy, pour éviter un conflit PyAV 19 (`metadata_errors`).

## Tests unitaires

```bash
python3 -m unittest discover -s tests -v
```

Sans modèle et sans nimbus : normalisation / WER, moteur `dummy`, inventaire d'une URL morte.

## Accès nimbus

L'inventaire et Phonon passent par nimbus, joignable en SSH **depuis le PC RoadRunner** :

```powershell
ssh -i "$env:USERPROFILE\.ssh\grokbot_nimbus" -o BatchMode=yes -o IdentitiesOnly=yes grokbot@nimbus 'bash -s' < nimbus-run.sh
```

Si le PC est hors ligne, préparer ici (scripts, catalogue, compose), lancer le WER sur la box, et **ne pas comparer les latences** à celles de nimbus (Ryzen AI 9 HX 470).
