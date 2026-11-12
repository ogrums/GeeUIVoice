# Tests vocaux autonomes (RUX / GeeUIVoice)

Deux modes, à lancer depuis le PC RoadRunner (PowerShell 5, `adb`, Python 3). Le robot a le numéro de série `2006014140527`. **Rien n'a encore été exécuté sur le matériel** : ce dossier prépare les campagnes pour quand l'accès au robot et à nimbus reviendra.

Le fichier `docs/TESTS-MATERIEL.md` n'est pas encore sur `main` (il vit dans la PR #20). La checklist de cette campagne est donc ici. Après fusion de #20, on pourra y recopier la section 12 ci-dessous.

## Prérequis

- L'APK de cette PR est installée, GeeUIVoice est démarré (boucle micro + Lemonade / Phonon / sidecar déjà configurés comme d'habitude).
- `adb devices` voit `2006014140527`.
- nimbus joignable : Phonon `http://nimbus:8880`, Whisper / Lemonade `http://nimbus:13305/api/v1`.
- Python 3. Le mode `wav` n'a pas de dépendance. Le mode `chaine` a besoin de :

```text
pip install -r tools/voicetest/requirements.txt
```

(`sounddevice` et `numpy` — micro et haut-parleur du PC.)

`ffmpeg` est optionnel : s'il est dans le PATH, les changements de hauteur gardent la durée d'origine. Sinon le script accélère ou ralentit un peu en même temps.

WAV déjà enregistrés (optionnel) : placez `tools/voicetest/wavs/<id>.wav` (même id que dans `phrases.json`) pour ne pas appeler Phonon.

## Mode wav — hook adb, sans acoustique

Le script pousse un WAV sur le robot et envoie le broadcast. L'app injecte le fichier à la place du micro (STT → chat → TTS), **sans passer par le VAD**. Elle écrit un JSON et recopie le clip TTS, récupérables par `adb pull`.

Commande exacte (depuis ce dossier, ou via le wrapper PowerShell) :

```text
python voicetest.py --mode wav --suite all
```

```powershell
.\voicetest.ps1 -Mode wav -Suite all
```

Une phrase seule :

```text
python voicetest.py --mode wav --ids question-simple
```

Ce que le script fait pour chaque phrase :

```text
adb -s 2006014140527 push prompt.wav /sdcard/Android/data/com.geeui.voice/files/voicetest/in.wav
adb -s 2006014140527 shell am broadcast -a com.geeui.voice.TEST_WAV -n com.geeui.voice/.TestWavReceiver --es path /sdcard/Android/data/com.geeui.voice/files/voicetest/in.wav
adb -s 2006014140527 pull /sdcard/Android/data/com.geeui.voice/files/voicetest/result.json
adb -s 2006014140527 pull /sdcard/Android/data/com.geeui.voice/files/voicetest/status.txt
adb -s 2006014140527 pull /sdcard/Android/data/com.geeui.voice/files/voicetest/tts.mp3
```

`status.txt` vaut `running`, puis `done` ou `error`. Le JSON contient la transcription, la réponse, le visage, les balises, **l'émotion audio emotion2vec** (`audio.emotion` / `audio.confidence`), et les temps (STT, premier jeton, premier audio TTS, total).

Le hook ne fait rien tant qu'on n'envoie pas ce broadcast. Les chemins acceptés sont uniquement :

- `/sdcard/geeui/…wav`
- `/sdcard/Android/data/com.geeui.voice/…wav`
- `/data/local/tmp/geeui/…wav`

(et les équivalents `/storage/emulated/0/…`). Un `..` ou une autre extension est refusé.

La boucle vocale doit déjà tourner. Sinon `result.json` indique *« démarrez d'abord GeeUIVoice (START) »*.

## Mode chaine — toute la chaîne, micro et haut-parleur

Le PC est **près du robot**, dans un endroit calme. Phonon génère la phrase, le haut-parleur du PC la joue, le micro du PC enregistre la réponse, Whisper (Lemonade) la transcrit. On récupère aussi `last-turn.json` et le logcat (`GeeUIVoiceTest`).

```text
python voicetest.py --mode chaine --suite all
```

```powershell
.\voicetest.ps1 -Mode chaine -Suite all
```

Le rapport ajoute la **latence** (fin de la phrase jouée → début de la voix du robot) et le contrôle **début de phrase coupé** (premier mot manquant).

## Suites (`--suite`)

| Suite | Contenu |
|---|---|
| `dialogue` | Météo Paris, emoji / expression, question simple, « Voiture » (pré-roll). |
| `emotion` | La même phrase (« Bonjour, comment ça va aujourd'hui ? ») colorée happy / sad / angry / neutral. |
| `voice` | Même phrase « Comment tu t'appelles ? » à 0,8× / 1,0× / 1,3×, calme / normal / fort, grave / aigu (−4 / +4 demi-tons). En chaîne seulement : `var-distance` (volume PC bas, à jouer un peu plus loin). |
| `all` | Les trois (défaut). `var-distance` est ignoré en mode wav. |

Les émotions sont produites par une post-prosodie (débit, volume, hauteur) sur une voix Phonon neutre : Phonon n'offre pas de style d'émotion documenté dans l'API OpenAI. On peut remplacer un clip par un WAV pré-enregistré `wavs/emotion-happy.wav` (etc.) pour une voix réellement jouée.

Valeurs attendues (pass/fail dans le rapport) :

| Contrôle | Attendu |
|---|---|
| Mots entendus | Mots-clés de `expect_heard` dans la transcription robot. |
| Mots de la réponse | `expect_reply` (ex. « rux », « paris », un degré) dans la réponse. |
| Début coupé | Pour « Voiture » et les phrases `loud_start`, le premier mot n'est pas un suffixe ni absent. |
| emotion2vec | Label = `expect_audio_emotion`. Neutral accepte aussi une confiance &lt; 0,45. Un label de même valence (happy/surprise, sad/angry/fear) est noté en échec « proche ». |
| Visage | `h0006` happy, `h0119` sad, `h0001` angry, `h0059` (ou vide) neutral — seulement si la fusion dépasse 0,45. |
| VAD | En chaîne, les phrases calmes / à distance doivent quand même produire un `heard`. |
| Latence | En chaîne, fin de phrase → voix du robot &lt; 8 s (la valeur mesurée est toujours reportée). |
| Stabilité émotion | Sur la suite `voice`, au plus deux labels francs (confiance ≥ 0,45) différents. |

## Rapport

Chaque campagne écrit `tools/voicetest/out/campaign-AAAAMMJJ-HHMMSS/report.md` et `report.json`, plus un dossier par phrase (`prompt.wav`, `result.json`, `logcat.txt`, et en chaîne `pc-record.wav`). La synthèse pass/fail est en tête du Markdown.

## Checklist matériel (à faire quand l'accès revient)

- [ ] Installer l'APK de cette PR, démarrer GeeUIVoice comme d'habitude (host Lemonade, sidecar, Phonon).
- [ ] `python voicetest.py --mode wav --suite dialogue` — les 4 phrases de base, `result.json` rempli, clip TTS récupérable.
- [ ] `python voicetest.py --mode wav --suite emotion` — emotion2vec et visages ; noter si un label « proche » revient souvent (réglage du seuil / de la prosodie).
- [ ] `python voicetest.py --mode wav --suite voice` — STT toujours complet à 0,8×, 1,3×, calme, fort, grave, aigu.
- [ ] PC près du robot, `pip install -r requirements.txt`, puis `python voicetest.py --mode chaine --suite all` — latence, début de « Voiture » non coupé, VAD à distance (`var-distance` : reculer d'un ou deux mètres).
- [ ] Relire `report.md` : aucun FAIL inattendu, et les visages correspondent bien aux codes ci-dessus sur l'écran du robot.

## Options utiles

| Option | Défaut | Rôle |
|---|---|---|
| `--mode` | `wav` | `wav` ou `chaine` |
| `--suite` | `all` | `dialogue`, `emotion`, `voice`, `all` |
| `--adb-serial` | `2006014140527` | Série du robot |
| `--phonon` | `http://nimbus:8880` | TTS OpenAI (`/v1/audio/speech`) |
| `--whisper` | `http://nimbus:13305/api/v1` | STT OpenAI (`/audio/transcriptions`) |
| `--phonon-voice` | `Hélène` | Voix Phonon |
| `--timeout` | `90` | Secondes max par phrase |
| `--regen` | off | Regénérer les WAV Phonon |
| `--ids` | | Sous-ensemble, ex. `meteo-paris,emotion-happy` |
| `--wav-dir` / `--out-dir` | `wavs/` et `out/` | Caches et rapports |

## Android 11 (robot actuel)

`adb push` directement dans `/sdcard/Android/data/com.geeui.voice/…` échoue (`remote fchown : Operation not permitted`), et les fichiers écrits par l'app sont en mode 600 : `adb pull` et `adb shell cat` sont refusés. Le script pousse donc via `/data/local/tmp`, et l'app rend les résultats du hook lisibles (copie aussi dans `/data/local/tmp/geeui/`). En mode chaîne, on ignore `result.json` (il n'est écrit que par le hook WAV) et on lit `last-turn.json`.

## Voix émotionnelles réelles (CaFE, RAVDESS)

La post-prosodie Phonon (vitesse / volume / hauteur) ne trompe pas emotion2vec.
Pour tester avec de vraies voix, utilisez deux corpus en **CC BY-NC-SA 4.0**
(usage non commercial, citation obligatoire). **N'ajoutez pas l'audio au dépôt.**

1. [CaFE](https://doi.org/10.5281/zenodo.1478765) (français canadien, à privilégier) — téléchargez `CaFE_48k.zip` (~187 Mo), version 1.1.
2. [RAVDESS](https://doi.org/10.5281/zenodo.1188976) (anglais, parole seule) — `Audio_Speech_Actors_01-24.zip` (~208 Mo).

```text
python prepare_emotion_datasets.py --cafe-zip CaFE_48k.zip --ravdess-zip Audio_Speech_Actors_01-24.zip
python voicetest.py --mode wav --suite emotion --phrases phrases-cafe.json --wav-dir wavs
python voicetest.py --mode wav --suite emotion --phrases phrases-ravdess.json --wav-dir wavs
python voicetest.py --mode chaine --suite emotion --phrases phrases-chain-fr.json --wav-dir wavs
```

Le script convertit un sous-ensemble (4 acteurs × émotions, intensité forte, une phrase)
en WAV 16 kHz mono, et écrit un JSON de phrases **sans** champ `emotion` (sinon
voicetest retoucherait la prosodie). L'acteur CaFE 07 (15 ans) est exclu.

Citations : Gournay, Lahaie, Lefebvre, *A Canadian French Emotional Speech Dataset*, ACM MMSys 2018 ; Livingstone & Russo, *RAVDESS*, PLoS ONE 2018.
