# Consommation au repos

Mesures du 14 novembre 2026 (heure de Paris) sur le RUX (RK3566, 4 cœurs), robot au repos, écran allumé, `top -H` toutes les 5 s pendant 60 à 90 s, `dumpsys meminfo`, `/proc/net/tcp`. Les % sont en « % d’un cœur » (400 % = tout le processeur).

## Avant / après

| Configuration | CPU GeeUIVoice | dont Vosk (Hey Dobby) | dont rendu vidéo (RenderThread + GPU Mali) | thread principal | Mémoire (PSS) |
|---|---|---|---|---|---|
| Avant (36c4716, porte fixe RMS 50, h0059 en boucle) | **78 %** | 37 % | 29 % | 7 % | 156 Mo |
| Après, réglages par défaut (porte `auto`, `idle_video=loop`) | **32 %** | 8–10 % | 17 % | 5 % | 155 Mo |
| Après, `idle_video=once` | **11 %** | 10 % | 0 % | 0,5 % | 151 Mo |
| Après, `idle_video=once`, sans Hey Dobby (`wake_word=hirux`) | **1 %** | — | 0 % | 0,5 % | **36 Mo** |

Sur le robot entier, le temps processeur libre passe de ~263 % à ~340 % (sur 400) avec `idle_video=once`.

La mémoire ne grossit pas : ~155 Mo PSS au démarrage comme après 45 min. Les « ~270 Mo » notés plus tôt étaient la RSS (bibliothèques partagées comprises), pas une fuite. Le modèle Vosk représente ~115 Mo de ces 155 Mo.

## Ce qui consommait

1. **Vosk tournait en continu.** La source `VOICE_COMMUNICATION` remonte le bruit de la pièce : avec une porte fixe à RMS 50, presque toutes les trames de 100 ms passaient. La porte suit maintenant le bruit de fond (`dobby_gate=auto`, défaut) : une trame passe si elle est 2× plus forte que le fond (entre 50 et 300). Mesure sur le robot : 12 % des trames données à Vosk au repos. Détection inchangée : 14/14 en simulation avec bruit RMS 15 à 150, 4/4 sur le robot (confiance 1,00, pic de la voix 4 800–6 800 contre une porte à ~60–75).
2. **La vidéo de repos en boucle.** h0059 (8 s, 25 i/s) est décodée et composée sans fin : ~17 à 29 % d’un cœur. 
3. **Des sockets oubliées.** L’app interrogeait `/v1/robot/pending` toutes les 15 s ; le sidecar déployé répond 404, et le corps de la réponse n’était jamais lu ni fermé : 34 sockets `CLOSE_WAIT` vers nimbus après ~45 min. Corrigé (connexion toujours fermée), et après un 404 l’app ne redemande que toutes les 10 min. Après correctif : 1 socket au plus.

## Réglages

| Réglage | Valeurs | Défaut |
|---|---|---|
| `idle_video` | `loop` (en boucle), `once` (une lecture, puis la dernière image reste), ou un nombre de secondes entre deux lectures (min. 10) | `loop` |
| `dobby_gate` | `auto`, ou un RMS fixe (ex. `50`, l’ancien comportement) | `auto` |
| `talk_face` | `speak` (bouche h0292 pendant la parole, émotion 3 s après), `emotion` (visage d’émotion pendant la parole, ancien comportement) | `speak` |

Les réglages se changent dans le menu (bande en bas de l’écran) ou au lancement :

```bash
adb -s 2006014140527 shell am start -S -n com.geeui.voice/.VoiceActivity --es idle_video once
```

Une valeur enregistrée dans le menu l’emporte sur l’extra de lancement.

## Propositions (à choisir)

| Option | Gain mesuré / estimé | Ce que ça change à l’écran |
|---|---|---|
| A. `idle_video=once` | −21 points (32 % → 11 %) | le visage de repos joue une fois puis reste figé sur la dernière image |
| B. `idle_video=30` | ~−18 points (une lecture de 8 s toutes les 38 s) | le visage s’anime de temps en temps |
| C. Couper notre HUD au repos et laisser GeeUIFace afficher le repos | ~−17 points chez nous, mais GeeUIFace décode à notre place | dépend de GeeUIFace (fermé par TaskService à chaque réveil Lex) |
| D. Clip de repos plus léger (h0059 ré-encodé à 12 i/s, ou un clip court) | ~−8 points estimé | animation moins fluide |
| E. Hey Dobby seulement à certaines heures, ou `wake_word=hirux` | −10 points et −115 Mo | plus de Hey Dobby à ces moments |

Recommandation : **B** (`idle_video=30`) ou **A** si un visage fixe ne te gêne pas ; garder la porte `auto`.

Reproduire les mesures : script `prof.ps1` (RoadRunner) — `top -H -p <pid>`, `dumpsys meminfo`, `/proc/net/tcp` filtré sur l’uid de l’app.
