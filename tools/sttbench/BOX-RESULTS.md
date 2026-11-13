# Résultats du banc STT — 12 novembre 2026 (Europe/Paris)

Première campagne du banc `tools/sttbench/`. **Hôte : la box CPU** (`grok-bot-vm-454328330`, 8 cœurs, 16 Go), **pas nimbus**. Les WER et les coupures sont comparables entre moteurs. **Les latences et le RTF ne le sont pas** avec le Ryzen AI 9 HX 470 de nimbus (Lemonade peut en plus utiliser le NPU).

Le PC RoadRunner était hors ligne : aucun inventaire des STT déjà en service (Lemonade 13305, Kyutai 8080, sidecar 13306), pas de Phonon, pas d'enregistrements micro de campagne chaîne. **Rien n'a été installé ni modifié sur nimbus.**

## Jeu

78 clips, 436 mots de référence, acteur 07 exclu :

| Condition | Clips | Contenu |
|---|---:|---|
| `cafe-emotion` | 28 | Phrase 1 (*Un cheval fou dans mon jardin*), acteurs 01, 02, 09, 12, 6 émotions intensité 2 + neutre |
| `cafe-sentences` | 40 | Phrases 2–6 (neutre + joie 2) + joie/neutre déjà hors phrase 1 ; mots rares (*éléphantesques*, *ânes aigris*…) |
| `variation` | 10 | speed 0,8 / 1,3, gain 0,3, pitch ±3, à partir de deux neutres phrase 1 (ffmpeg, pas Phonon) |

## Tableau d'ensemble

Passé 1 (12:11–12:15 Europe/Paris) : Vosk, faster-whisper tiny / base / small. Passé 2 (12:16–12:17) : Parakeet + faster-whisper small avec `condition_on_previous_text=False` (chiffres `fw-small` ci-dessous). ΔRSS de `fw-small` au passé 1, processus frais : **550 Mo** (au passé 2 le processus gardait encore Parakeet en mémoire).

| moteur | licence | WER | WER fold | café-émotion | café-phrases | variation | phrase 1 seule | clips parfaits | coupures début / fin | extra début | latence méd. box (s) | RTF méd. box | ΔRSS (Mo) | froid box (s) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **parakeet-tdt-0.6b-v3** (sherpa-onnx int8) | CC-BY-4.0 (attribution NVIDIA) | **19,3 %** | 19,3 % | **10,7 %** | **31,7 %** | **0 %** | **7,9 %** | **43 / 78** | 6 % / 1 % | 4 % | 0,239 | 0,053 | 791 | 0,254 |
| fw-small (faster-whisper int8, proche du Whisper-Small de Lemonade) | MIT | 39,4 % | 39,4 % | 25,6 % | 62,0 % | 0 % | 18,9 % | 30 / 78 | 3 % / 0 % | 17 % | 0,700 | 0,165 | 550 | 0,794 |
| vosk-small-fr 0.22 | Apache-2.0 | 46,8 % | 46,8 % | 29,8 % | 73,1 % | 3,3 % | 22,8 % | 20 / 78 | 12 % / 0 % | 10 % | 1,169 | 0,267 | 142 | 1,884 |
| fw-base | MIT | 57,6 % | 57,6 % | 48,2 % | 78,8 % | 10,0 % | 38,2 % | 22 / 78 | 6 % / 8 % | 21 % | 0,260 | 0,062 | 141 | 0,363 |
| fw-tiny | MIT | 95,0 % | 94,7 % | 86,3 % | 120,7 % | 30,0 % | 71,5 % | 9 / 78 | 3 % / 0 % | 40 % | 0,161 | 0,039 | 79 | 0,206 |

Lemonade, Kyutai et le sidecar : **non mesurés** (nimbus injoignable). Le sidecar a la même précision STT que Lemonade (proxy).

## Lecture

- **Parakeet est nettement plus précis** que Whisper small, sur ce jeu : environ **deux fois moins d'erreurs**, beaucoup moins d'insertions / hallucinations (0 clip à WER > 1, contre 7 pour `fw-small`), et le seul moteur à transcrire correctement *Quatre vieilles truies éléphantesques* (4 clips sur 8). Sur la phrase 1 (la plus proche d'un usage courant) : 7,9 % contre 18,9 %.
- **faster-whisper small** reste honnête sur la phrase 1 et parfait sur les variations, mais invente souvent sur les phrases rares (*Sous-titres par C.R.*, *6 Hours et Mans*, *C'est ce que j'ai fait* répété). C'est la même famille que le STT actuel (Whisper-Small via Lemonade) : on s'attend à un WER du même ordre, le runtime CTranslate2 n'étant pas strictement identique à Lemonade.
- **Vosk small FR** est le plus léger (142 Mo, 41 Mo sur disque, Apache-2.0) et correct sur les variations, mais clairement derrière Parakeet et un peu derrière Whisper small, surtout en émotion et sur les phrases 2–6.
- **tiny et base** : trop faibles en français pour le robot. `fw-tiny` hallucine (WER > 100 % sur les phrases 2–6).
- Les **phrases CaFE 4–6** sont volontairement difficiles (vocabulaire rare, parfois crié). Elles discriminent bien les moteurs, mais **surestiment le WER** par rapport à des tours du type « quelle heure est-il ». La colonne « phrase 1 » et la condition `variation` sont plus proches du quotidien ; il manquera Phonon + micro robot pour le confirmer.
- Erreur fréquente partagée : *cygnes* → *signes* (pas un problème d'accents : `wer_fold` = WER).
- Un clip Parakeet vide : `cafe-12-fear-2-s1` (peur, actrice 12).

## Recommandation pour le robot

1. **Ne pas changer le STT en service** tant que le banc n'a pas tourné sur nimbus (Lemonade / NPU, Kyutai déjà là, latences réelles).
2. Dès que RoadRunner est en ligne : `sttbench.py inventory` (lecture seule, **pas** de `POST /load`, **pas** d'arrêt de pockettts / kyutai / nanobot), puis le même `manifest.json` contre Lemonade, éventuellement Kyutai s'il expose `/v1/audio/transcriptions`, et **Parakeet en conteneur temporaire sur 13312** (`docker-compose.candidates.yml --profile parakeet`). Ajouter Phonon et les WAV chaîne s'il y en a sur le PC.
3. Si les chiffres nimbus confirment l'écart de WER et un RTF acceptable, **Parakeet TDT 0.6B v3 int8** est le meilleur candidat light FR vu ici : OpenAI-compatible via `serve.py`, ~800 Mo de RAM, CC-BY-4.0 (**attribution NVIDIA obligatoire**). Vosk n'est intéressant que si l'on veut un empreinte très petite. Écarter tiny/base. Distil-FR bofenghuang et zipformer FR restent à mesurer (non lancés ici, pour limiter les téléchargements).

## Ce qui reste sur la box (rien sur nimbus)

| Chemin | Taille approx. | Contenu |
|---|---|---|
| `/workspace/sttbench-models/` | 1,4 Go | Vosk small FR (66 Mo décompressé), Parakeet int8 (671 Mo), cache Hugging Face (Whisper tiny/base/small) |
| `/workspace/sttbench-venv/` | 546 Mo | Python 3.13 : faster-whisper 1.2.1, vosk 0.3.45, sherpa-onnx 2.0.1 |
| `/workspace/sttbench-data/` | 11 Mo | WAV 16 kHz + manifest + campagnes JSON/MD (audio **hors git**, CaFE CC BY-NC-SA 4.0) |

Disque box après coup : 114 Go libres (11 %). On peut effacer ces trois dossiers si on a besoin de place ; le banc se relance avec `download-models.sh` et un `pip install`.
