# Visage RuxFace (option `face_backend`)

Par défaut rien ne change : `face_backend` = `geeuiface`, les visages sont les clips vidéo (GeeUIFace via le bus
Letianpai, et le TextureView de VoiceActivity).

Avec `face_backend` = `ruxface` **et** l'appli RuxFace (`com.ogrums.ruxface`, visage vectoriel OpenGL) installée :

| avant (GeeUIFace) | avec RuxFace |
|---|---|
| `bus.showFace(id)` → `setExpression controlFace` → GeeUIFace décode le clip | l'id est seulement noté (`VoiceHud.face`), GeeUIFace n'est pas sollicité |
| VoiceActivity joue `dialogueFace(mode, playing, pose)` + rémanence de l'émotion | `FaceDirector` (même logique, dans le service) → broadcast `com.ogrums.ruxface.STATE --es face hNNNN` toutes les 100 ms si changement (renvoi toutes les 5 s) |
| bouche du clip h0292 (fixe) | `com.ogrums.ruxface.LEVEL` toutes les 40 ms : enveloppe du clip TTS WAV (`SpeechEnvelope`) → ouverture de la bouche ; mp3/ogg : bouche simulée par RuxFace |
| `ScreenOn.bringToFront()` remet VoiceActivity devant | remet RuxFace devant (`--es renderer gl --ez auto false --es face …`), au plus toutes les 4 s |
| — | `RuxFaceLink.params(json)` : expression composée (`com.ogrums.ruxface.PARAMS`), prête pour la suite |

Mêmes identifiants qu'aujourd'hui : écoute `listen_face` (h0347), attente backend `backend_face` (h0242), parole
h0292, émotions, repos `RestFace` (h0059 ou humeur). RuxFace les traduit avec sa table `FaceTags` (h0242 → son
propre visuel « waiting »).

Repli : si RuxFace n'est pas installé (vérifié toutes les 10 s), tout repasse sur GeeUIFace. Pour revenir en
arrière : `face_backend` = `geeuiface` (menu Réglages, ou extra de lancement si rien n'est enregistré).
VoiceActivity lancée en `--es mode debug` garde ses clips et n'est pas recouverte au démarrage.

## Essai sur le robot (à faire avec accord)

APK : `app-debug.apk` de la branche `feat/ruxface-backend`, signé avec la même clé que la version installée
(`adb install -r` garde les réglages).

```bash
adb install -r app-debug.apk
# choisir RuxFace : menu Réglages de VoiceActivity (bande du bas) → « Visage » = ruxface → Enregistrer
# (ou, si aucune valeur n'est enregistrée : am start -n com.geeui.voice/.VoiceActivity --es face_backend ruxface)
adb logcat -s GeeUIVoiceRux GeeUIVoiceTest RuxFace
```
À vérifier : RuxFace passe devant au démarrage ; « Hi RUX » / « Hey Dobby » → visage d'écoute (anneaux), phrase
envoyée → attente (anneau de chargement), réponse → bouche qui suit la voix, puis l'émotion ~3 s, puis repos ;
journal `ruxface face hNNNN` dans events.log. Retour : `face_backend` = `geeuiface`.
