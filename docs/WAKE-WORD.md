# Mot de réveil (wake word)

Proposition seulement — rien n'est implémenté ici. Inspection en lecture seule
sur le robot (`adb -s 2006014140527`, aucune commande `am` / `pm` / `settings put`),
le 12 novembre 2026 vers 21:42 Europe/Paris.

Matériel vu : Rockchip RK3566 (4× Cortex-A55), 3,3 Go de RAM, Android 11 (SDK 30),
`arm64-v8a`, écran 480×480 allumé, `isSleeping=false`.

## 1. Comment le robot écoute aujourd'hui

Deux systèmes tournent en même temps, et ils se marchent dessus.

### GeeUIVoice : VAD continu, pas de mot de réveil

Une fois l'app ouverte, `VoiceService` (service avant-plan, type `microphone`,
`START_STICKY`) ouvre `AudioRecord` 16 kHz et pousse des trames de 20 ms dans
`EnergyVad` (`LiveTurn`). Dès qu'une phrase dépasse le seuil, elle part au STT
puis au LLM. Il n'y a pas de mot de réveil dans la boucle : l'interface
`WakeWord` existe dans `voice-core` mais l'implémentation réelle est `NoWake`.
L'activité n'a ni `WAKE_LOCK` ni `setTurnScreenOn`. Le manifeste ne déclare pas
`android.permission.WAKE_LOCK`.

Au moment de l'inspection, `com.geeui.voice/.VoiceService` tournait depuis
environ une heure (démarré pendant les tests émotions), alors que l'activité
au premier plan était `com.letianpai.robot.desktop`. Le service survit donc
quand l'activité n'est plus visible, **tant que personne ne le tue**.

### Lex : « Hi RUX », moteur iFlytek IVW/VTN

`com.geeui.lex` (v1.1.202504282, application système mise à jour, UID
`android.uid.system` / 1000) est le moteur stock. Il tourne en permanence via
`com.geeui.lex/.services.BotService` (démarré par le launcher, **non exporté**).

Le mot est bien « hi rux ». Dans l'APK :

- `assets/resources/config/vtn.ini` : `[ivw] ivw_enable = 1`, chemins vers
  `mlp_hi_rux.bin` et `keywords_hi_rux.bin` (moteur **iFlytek IVW**, en-tête
  binaire `-iflytek-~~~~~~~-dec-`, chaîne `hi rux`).
- `libvtn.so`, `libwakeup.so`, `libcae-jni.so` (CAE iFlytek, 1 micro).
- Table `assets/hiruxbase.table`.

L'APK embarque aussi d'autres piles (SDK Azure Speech avec extensions KWS,
DUI / AISpeech `duicore.zip`) : ce n'est pas le chemin du « Hi RUX » local.

Aucune *receiver* ni *service* exporté n'apparaît dans `dumpsys package`
(seulement `MainActivity` LAUNCHER). On ne peut pas s'abonner à un broadcast
public « le robot a entendu Hi RUX ». Le canal prévu est le callback parole
du binder Letianpai :

```text
ILetianpaiService.registerSpeechCallback(LtpSpeechCallback)
  → onSpeechCommandReceived("wakeup_status" | "wakeup_doa", data)
```

`LtpSpeechCallback` est un AIDL public (`onSpeechCommandReceived(command, data)`).
GeeUIVoice connaît déjà les numéros de transaction (`LtpApi`) mais son sous-ensemble
AIDL actuel n'expose que `setMcuCommand` / `setSpeechCmd` / `setTTS` / `setExpression` :
il faudrait l'étendre pour s'enregistrer. Ce n'est pas un hook « pirate » ; c'est
l'API que Lex utilise déjà (`SpeechConst.COMMAND_WAKE_UP_STATUS`).

### Conflit de micro (constat important)

`dumpsys audio`, 21:42 Europe/Paris :

| Session | Source | Format | UID / paquet | État |
|---|---|---|---|---|
| 9 | `VOICE_RECOGNITION` | 32 kHz mono | 1000 / `com.letianpai.robot.desktop` | actif |
| 17, 33, 49 | `MIC` | 16 kHz mono | 1000 / même UID système | actif |
| **393** | **`MIC`** | **16 kHz mono** | **`u0a130` / `com.geeui.voice`** | **`silenced:true`** |

Les captures uid 1000 sont imputées à `desktop` parce que Lex, le bureau et
taskservice partagent `android.uid.system`. La session 9 à 32 kHz
`VOICE_RECOGNITION` est le profil typique d'iFlytek CAE/IVW.

Android 10+ **silence** l'enregistrement d'une app tierce quand une app
privilégiée tient déjà le micro. GeeUIVoice « écoute » donc des zéros : le VAD
ne part pas, le STT revient vide. C'est la cause la plus plausible des tests
chaîne (haut-parleur PC → micro robot) vides de ce soir. Les deux processus
peuvent être *vivants*, mais un seul obtient le vrai signal.

Conséquence pour tout mot de réveil : **GeeUIVoice ne doit pas garder
`AudioRecord` ouvert au repos**. Sinon Lex n'entend pas « Hi RUX », et notre
STT est muet.

## 2. Sommeil de nuit Letianpai

`com.letianpai.robot.taskservice` (`DispatchService`, `SleepModeManager`,
`RobotModeManager`) gère la nuit. Chaîne vue dans le DEX (lecture seule, le
robot n'a **pas** été mis en sommeil pendant l'inspection) :

- Tâche / tag `letianpai_sleep`, avec `start_time` / `end_time`
  (`SleepModeConfig` : `sleep_mode_switch`, `close_screen_mode_switch`,
  `sleep_sound_mode_switch`, `sleep_time_status_mode_switch`).
- `gotoNightBlackScreenSleep` / `gotoBlackScreenSleep` / `gotoDeepSleepMode`.
- Modes : `VM_SLEEP_MODE`, `VM_DEEP_SLEEP_MODE`,
  `VM_BLACK_SCREEN_SLEEP_MODE`, **`VM_BLACK_SCREEN_NIGHT_SLEEP_MODE`**
  (la valeur numérique citée par ailleurs comme `robotMode` 28 — le nom de
  constante est certain, le 28 n'a pas été relu en live).
- MCU : `powerControl` fonctions 3 et 5, statut 0 — ce sont les deux rails
  de servos (`AT+FunCtr,3,0` / `5,0`). Écran noir via `darkScreen` /
  `controlDisplayMode`.
- Commandes bus déjà documentées : `go_sleep`, `rhj.controller.openSleep`,
  `setRobotMode`, `updateSleepModeConfig`.

Point crucial : il existe un **`VM_AUDIO_WAKEUP_MODE`**
(`switchAudioWakeupMode`). Le firmware prévoit donc un sommeil où Lex
continue d'écouter « Hi RUX » pour réveiller (écran + servos). C'est le
comportement « comme actuellement » demandé.

Est-ce que *notre* app continue d'écouter écran éteint ? On ne l'a pas
provoqué (contrainte lecture seule). D'après le code :

- `VoiceService` est un FGS `START_STICKY` : Android *peut* le garder, et
  l'activité n'arrête pas le micro dans `onPause` / `onDestroy`.
- Mais le micro est déjà silencé dès maintenant, écran allumé, Lex présent.
  La nuit, ce n'est pas mieux.
- taskservice a `killThirdApps`, `killProcess`, `VM_KILL_SPEECH_SERVICE`.
  Une app tierce comme `com.geeui.voice` (`u0a130`) peut être tuée à
  l'entrée en sommeil. Dans ce cas un callback AIDL ne nous réveille pas :
  le process n'est plus là.

À vérifier sur accord, en mettant réellement le robot en mode nuit : est-ce
que `VoiceService` survit, et est-ce que Lex reste en `VM_AUDIO_WAKEUP_MODE` ?

## 3. Options

### A — S'accrocher à « Hi RUX » (recommandé en premier)

Réutiliser le moteur déjà entraîné, déjà en écoute, déjà prévu pour le
sommeil (`VM_AUDIO_WAKEUP_MODE`).

1. Au repos : **fermer** notre `AudioRecord` (Lex récupère un micro non
   contesté).
2. S'enregistrer sur `registerSpeechCallback` et attendre `wakeup_status`.
3. Au mot : arrêter le recorder de Lex (déjà exigé par [FUSION.md](FUSION.md)
   avant d'ouvrir notre boucle), prendre le micro, allumer l'écran (ci-dessous),
   éventuellement `exitDarkScreen` / `openRobotMode` / `setRobotMode`, puis
   VAD → STT comme aujourd'hui.
4. Fin de tour (ou courte fenêtre d'écoute) : rendre le micro à Lex.

Limites : pas de receiver exporté, donc pas de `BroadcastReceiver` simple ;
le mot reste « Hi RUX » (anglais, modèle iFlytek propriétaire, pas
d'entraînement d'un mot français dans cette pile). Si taskservice tue
GeeUIVoice pendant la nuit, il faudra un petit service qui survit au sommeil
(FGS, ou relance depuis un callback système) juste pour recevoir le
`wakeup_status`. À confirmer en test réel.

Coût CPU / licence pour nous : nuls. Le travail est de la coordination
micro + AIDL.

### B — Mot à nous, sur le robot (sherpa-onnx KWS ou openWakeWord)

Utile si on veut un mot français (« salut RUX », etc.). Lex doit quand même
lâcher le micro, sinon notre KWS sera silencé comme le VAD actuel.

| Moteur | Licence | Mot français | Coût CPU (RK3566) | Remarque |
|---|---|---|---|---|
| **sherpa-onnx KWS** | Apache-2.0 | À entraîner (icefall) | Léger, un cœur A55 largement suffisant | Déjà dans l'écosystème (Parakeet, speaker-id). Aligné avec `WakeWord` dans `voice-core`. |
| **openWakeWord** | Code Apache-2.0 ; **poids officiels non commerciaux** | À entraîner | Léger (ONNX). Port [openwakeword-android-kt](https://github.com/Re-MENTIA/openwakeword-android-kt) déjà cité dans le README | Le README prévoyait un modèle « hi rux » : ce n'est toujours pas disponible, et les poids officiels ne conviennent pas à un usage produit. |
| **Porcupine** (Picovoice) | Gratuiciel limité ; **commercial** en production | Custom possible | Très léger | Licence à acheter ; à éviter tant que A ou sherpa suffisent. |
| iFlytek IVW de Lex | Propriétaire | Non (modèle `hi rux` seulement) | Déjà payé par le firmware | On ne réentraîne pas cette pile. |

Recommandation si un mot français est nécessaire : **sherpa-onnx KWS**, après
une petite campagne d'enregistrements. Même politique micro que A : KWS seul
au repos (pas de VAD/STT), VAD seulement après le mot.

### C — Hybride

Garder « Hi RUX » (A) tout de suite, pour retrouver le réveil actuel et
réparer le micro. Ajouter un mot français (B) plus tard, en parallèle ou
à la place, une fois le partage du micro réglé.

## 4. Allumer l'écran depuis l'app

Le robot est en Android 11 (API 30). Pas besoin de la permission `TURN_SCREEN_ON`
(API 33+).

1. Déclarer `android.permission.WAKE_LOCK` (absente du manifeste aujourd'hui).
2. Sur `VoiceActivity` : `android:turnScreenOn="true"` et, au besoin,
   `android:showWhenLocked="true"` / `setShowWhenLocked(true)` (API 27).
3. Dans le code, au `wakeup_status` : `setTurnScreenOn(true)`, éventuellement
   un `PowerManager.WakeLock` avec `ACQUIRE_CAUSES_WAKEUP` (encore fonctionnel
   sur API 30 ; déprécié plus tard) puis `SCREEN_BRIGHT_WAKE_LOCK` ou
   `FLAG_KEEP_SCREEN_ON` pendant le tour.
4. Côté robot, en plus du framework Android : `controlDisplayMode` /
   `exitDarkScreen`, et sortir du mode sommeil (`openRobotMode`, inverse de
   `go_sleep`). Sans ça, l'écran Android peut s'allumer sur un `darkScreen`
   Letianpai.

`DEVICE_POWER` et `USER_ACTIVITY` sont des permissions système : Lex les a
(UID 1000), GeeUIVoice (`u0a130`) non. On reste sur les API publiques ci-dessus.

## 5. Coexister avec le sommeil

Objectif : la nuit, le robot est écran noir, servos coupés (`powerControl` 3 et
5 à 0), et un mot le réveille « comme maintenant ».

- **Entrer en nuit** : écouter `go_sleep` / `updateSleepModeConfig` /
  `setRobotMode` via `registerAppCmdCallback` (même famille d'AIDL). Fermer
  notre `AudioRecord`. Ne pas parler, ne pas animer le visage. Laisser Lex
  en `VM_AUDIO_WAKEUP_MODE`.
- **Pendant la nuit** : pas de VAD continu. Si on est en option B, un KWS
  très léger *à la place* de Lex, pas en plus — un seul `AudioRecord`.
- **Au mot** : écran (section 4), rallumer les rails (`powerControl` 3 et 5
  à 1, déjà le geste `robotOpenMotor` de l'app), prendre le micro, écouter.
- **Fin de conversation** : si on est encore dans la plage
  `start_time`–`end_time`, reposer le robot (écran noir, rails coupés, micro
  rendu à Lex). Sinon rester éveillé.

Ne pas déclencher `killThirdApps` nous-mêmes. Si le test réel montre que
taskservice nous tue, négocier un FGS assez « système-friendly » pour
survivre, ou une relance au `wakeup_status` depuis un processus qui reste
(à explorer : ne pas compter sur un broadcast Lex, il n'est pas exporté).

## 6. Recommandation

**Faire A maintenant, préparer B.**

1. Céder le micro au repos — indispensable, même sans mot de réveil : c'est
   ce qui mute le STT aujourd'hui.
2. Étendre l'AIDL, s'enregistrer à `registerSpeechCallback`, réagir à
   `wakeup_status` : écran on, rails on, micro à nous, VAD, puis restitution
   à Lex.
3. Brancher ça sur les modes de sommeil (`VM_AUDIO_WAKEUP_MODE`,
   `gotoNightBlackScreenSleep`) pour que « Hi RUX » réveille comme le
   firmware le prévoit déjà.
4. Si un mot français est voulu, l'ajouter ensuite en sherpa-onnx KWS
   (Apache-2.0), avec la même discipline de micro.

Pas d'implémentation dans ce document. Les étapes 1–3 touchent l'app et
demandent un feu vert, plus un test réel du mode nuit (volontairement non
fait ici).
