# Mot de réveil (wake word)

Le 12 novembre 2026 (Europe/Paris), ce document n'était qu'une proposition,
issue d'une inspection en lecture seule. Depuis, l'option A (« Hi RUX ») est
dans l'app (#34, correctifs #37) : au repos on cède le micro, on s'abonne au
callback Lex, et un `wakeup_status avatar.listening` allume l'écran et ouvre
notre boucle.

Il restait un trou : après un vrai « Hi RUX », l'app ouvrait bien
`AudioRecord` mais n'entendait rien. Voir la [section 7](#7-micro-silencé-après-hi-rux).
Un mot de réveil français à nous est chiffré en [section 8](#8-mot-de-réveil-français-à-nous).

Inspection d'origine : `adb -s 2006014140527`, le 12 novembre 2026 vers 21:42
Europe/Paris, sans commande `am` / `pm` / `settings put`.

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

Les étapes 1–3 de l'option A sont dans l'app (#34 / #37). Le mode nuit n'a
pas encore été revalidé après ces changements. Le trou restant est le micro
silencé (section 7).

## 7. Micro silencieux après « Hi RUX »

Constat du 13 novembre 2026 vers 20:22 (heure de Paris), après #37 : deux
réveils acceptés (`wake accepted` à 20:22:09 et 20:22:51). `com.geeui.voice`
ouvre bien une session `MIC` 16 kHz pendant ~12 s, puis la rend. Aucune
phrase n'est transcrite.

`dumpsys audio` pendant ces fenêtres :

- Session 9, `VOICE_RECOGNITION` 32 kHz, uid 1000, paquet attribué à
  `com.renhejia.robot.launcher` : **reste ouverte tout le temps**.
  `stop_audio_service` tel qu'on l'envoyait ne l'arrête pas.
- Des sessions `MIC` 16 kHz du même uid démarrent et s'arrêtent autour du
  réveil, sans libérer la session 9.
- Sur Android 11, une capture privilégiée `VOICE_RECOGNITION` (uid 1000)
  **silence** l'`AudioRecord` d'une app tierce, même en service avant-plan
  de type `microphone`. Changer de source (`VOICE_RECOGNITION`,
  `VOICE_COMMUNICATION`, `UNPROCESSED`, `CAMCORDER`) ne suffit pas tant que
  la session 9 tourne. L'activité n'a pas besoin d'être au premier plan une
  fois cette session arrêtée : le FGS suffit.

### Mauvaise commande, bonne commande

On envoyait `setAppCmd("stop_audio_service", "com.geeui.lex")`. Ce n'est pas
le chemin du firmware.

Dans le code ouvert Letianpai (`GeeUITaskService`, `GeeUIAIAudioService`) et
dans le `classes2.dex` de `com.geeui.lex` installé :

```text
setRobotStatusCmd("stop_audio_service", "stop_audio_service")
  → LtpRobotStatusCallback.onRobotStatusChanged
  → RhjAudioManager.disableWakeup()   // DDS / moteur d'écoute

setRobotStatusCmd("start_audio_service", "start_audio_service")
  → RhjAudioManager.enableWakeup()
```

`AidlBus.pauseLexAudio()` / `resumeLexAudio()` utilise maintenant cette
paire. Rien n'est désactivé par `pm`, aucun fichier système n'est modifié.
Un hook `adb` `com.geeui.voice.TEST_MIC` compare l'ancienne commande, la
nouvelle, et les cinq sources, avec RMS sur 3 s et le drapeau
`isClientSilenced`.

## 8. Mot de réveil français à nous

Utile si « Hi RUX » reste trop juste (seuil iFlytek 712, ncm souvent à peine
au-dessus) ou si on veut un mot français. **Cela dépend de la section 7** :
tant que la session 32 kHz uid 1000 tourne, *notre* mot de réveil serait
silencé comme le STT. Il faudrait donc, au repos, arrêter l'écoute Lex
(`setRobotStatusCmd stop_audio_service`) et garder *un seul* `AudioRecord`,
le nôtre. « Hi RUX » cesserait d'être le mot de repos, sauf en secours si
on relance Lex au besoin.

Matériel : RK3566, 4× Cortex-A55, 3,3 Go de RAM, Android 11.

| Moteur | Licence | Mot français prêt | Coût estimé sur le robot | Faux réveils | Remarque |
|---|---|---|---|---|---|
| **Vosk small FR 0.22**, grammaire fermée (`salut rux`, `hey rux`, …) | Apache-2.0 | Oui, dès maintenant | 41 Mo sur disque, **~140 Mo de RAM** (mesure box). RTF 0,27 sur une box x86 ; sur un A55, temps réel probable mais à mesurer. Un cœur suffit si on ne lance pas le STT en même temps. | Moyens : ce n'est pas un spotter, c'est un reconnaisseur. La grammaire courte limite les inventions, pas les quasi-homophones. Il faut un seuil d'énergie pour ne pas écouter le silence. | Meilleur candidat *immédiat*. Déjà mesuré dans `tools/sttbench`. |
| **sherpa-onnx KWS** | Apache-2.0 | **Non** (pas de modèle français public) | Très léger (quelques Mo, une fraction de cœur) une fois entraîné | Faibles, si le modèle est bon | Aligné avec Parakeet / speaker-id. Il faudrait une campagne d'enregistrements et un entraînement icefall. Pas un basculement de la semaine. |
| **openWakeWord** | Code Apache-2.0 ; **poids officiels non commerciaux** | Non (à entraîner) | Léger (ONNX) | Faibles après entraînement | Inutilisable en l'état pour un usage produit à cause de la licence des poids. Entraîner les nôtres est possible, plus de travail que Vosk. |
| **Porcupine** (Picovoice) | Gratuiciel limité ; **commercial** en production | Custom possible | Très léger | Faibles | À éviter tant que Vosk ou sherpa suffisent. |
| iFlytek IVW de Lex | Propriétaire | Non (modèle `hi rux` seulement) | Déjà là | Juste au seuil (712) | On ne réentraîne pas cette pile. On peut seulement baisser le seuil dans `keywords_hi_rux.bin` (fichier système, pas fait). |

**Recommandation.** D'abord valider la section 7 (entendre après « Hi RUX »).
Si le mot d'origine reste trop peu fiable, ajouter **Vosk small FR à
grammaire fermée** dans l'app, au repos seulement, à la place de Lex, et
garder « Hi RUX » comme option de repli (réglage). Mesurer faux réveils et
RTF sur le robot avant d'en faire le défaut. Un KWS sherpa-onnx français
peut le remplacer plus tard, plus léger, une fois un modèle entraîné.
