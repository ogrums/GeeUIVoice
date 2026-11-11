# Une APK ou plusieurs ?

Question posée : certaines fonctions seraient-elles mieux dans une APK à part, qui tourne en fond sur le robot (vision, initiative et notifications, pont Home Assistant, interface d'enregistrement des voix) ? **Rien n'est construit ici** : ce document pose les options pour qu'on décide ensemble.

## Les contraintes du robot

Lues dans [ROBOT.md](ROBOT.md) et le code :

| Contrainte | Conséquence |
|---|---|
| Android 11, peu de RAM, beaucoup d'apps du fabricant déjà en fond (`com.geeui.face`, `com.geeui.lex`, `com.ltp.ident`, `letianpaiservice`…) | Chaque processus de plus coûte de la mémoire et peut être tué par Android |
| **Un seul `AudioRecord` à la fois.** Lex et GeeUIVoice ne peuvent pas tenir le micro ensemble | Tout ce qui écoute doit être dans le même processus que la boucle vocale, ou se coordonner avec elle |
| Un seul haut-parleur, un seul visage (`com.geeui.face`), un seul bus robot (AIDL `letianpaiservice`) | Deux APK qui parlent ou bougent en même temps se marchent dessus : il faut un seul « chef d'orchestre » |
| Caméra : `com.ltp.ident` (reconnaissance faciale du fabricant) l'utilise ; la photo passe aujourd'hui par `rhj.controller.takephoto` | Une APK vision devrait partager la caméra avec `com.ltp.ident` |
| Les modèles tournent sur nimbus, pas sur le robot | Le robot n'a besoin que de clients HTTP légers |
| Mises à jour par `adb install` | Chaque APK de plus = une installation, une version et des réglages de plus |

## Les fonctions, une par une

| Fonction | Ce qu'elle fait sur le robot | Où la mettre |
|---|---|---|
| **Qui parle** (empreinte) | Rien : l'empreinte est calculée par le sidecar sur le WAV déjà envoyé | Sidecar (fait, GeeUIVoiceSidecar#2). Pas d'APK |
| **Enregistrement des voix** | Dialogue à la voix (« retiens ma voix ») | Sidecar (fait). Si on veut un écran : une page web d'administration servie par le sidecar (`/admin`, clé `SIDECAR_ADMIN_KEY`), ouverte depuis un téléphone. Pas d'APK |
| **Initiative / notifications** | Lire `pending` au repos, dire la phrase, `ack` | **Dans GeeUIVoice** (fait, #18) : il faut savoir si le robot écoute ou parle, et utiliser la même voix et le même visage |
| **Home Assistant** | Rien sur le robot : nanobot appelle le serveur MCP de HA depuis nimbus | nanobot sur nimbus (point 6). Pas d'APK |
| **Vision à la demande** (« qu'est-ce que tu vois ? ») | Une photo, envoyée au sidecar | Dans GeeUIVoice (balise `[photo]` → photo → `POST /v1/robot/see`) |
| **Vision continue** (présence, visage connu) | Caméra ouverte en continu, détection locale ou envois réguliers | **Seul vrai candidat à une APK à part** (voir option C) |

## Les options

### A. Tout dans GeeUIVoice (aujourd'hui)

- Pour : un seul processus tient le micro, le haut-parleur et le visage ; pas d'IPC ; une seule installation ; les règles (ne pas parler pendant une écoute) sont simples à garantir.
- Contre : l'APK grossit ; un plantage de la vision ferait tomber la voix ; tout redémarre ensemble.

### B. GeeUIVoice + une APK « services » (initiative, notifications, pont maison)

- Pour : on pourrait mettre à jour les services sans toucher à la voix.
- Contre : l'APK services devrait demander à GeeUIVoice de parler (IPC : intent, service lié AIDL ou HTTP local) et savoir s'il est occupé. On recrée à deux endroits ce que GeeUIVoice sait déjà. Rien de ce qu'elle ferait n'a besoin du matériel du robot : le sidecar sur nimbus le fait déjà mieux (toujours allumé, pas tué par Android).

### C. GeeUIVoice + une APK « yeux » (vision continue seulement)

- Pour : la caméra et son traitement sont isolés ; un plantage ou une fuite mémoire ne coupe pas la voix ; on peut l'arrêter facilement pour économiser la batterie ; elle peut être développée et testée seule.
- Contre : partage de la caméra avec `com.ltp.ident` à étudier ; IPC vers GeeUIVoice pour signaler « quelqu'un est là » (le plus simple : `POST /v1/robot/event` au sidecar, sans lien direct entre les deux APK) ; une installation de plus.
- À vérifier avant : est-ce que `com.ltp.ident` expose déjà un événement « visage reconnu » (broadcast ou AIDL) ? Si oui, il suffit de l'écouter dans GeeUIVoice et l'APK « yeux » n'a plus de raison d'être.

### D. Une APK par fonction

- Contre : coordination micro / haut-parleur / visage impossible à garantir, mémoire, maintenance. Écartée.

## Recommandation

1. **Garder GeeUIVoice comme seul chef d'orchestre** du micro, de la voix, du visage et des gestes (option A) pour tout ce qui est fait ou prévu jusqu'au point 8, sauf la vision continue.
2. **Mettre la logique sur nimbus** (sidecar, nanobot) plutôt que dans une APK : enregistrement des voix, initiative, souvenirs, maison, mode enfant / invité. Le robot reste un client léger, et ce qui tourne sur nimbus ne dépend pas de la mémoire du robot.
3. **Vision à la demande dans GeeUIVoice** (point 5, une photo quand on la demande).
4. **Décider plus tard** d'une APK « yeux » (option C), seulement si on veut une présence par la caméra en continu, et après avoir regardé ce que `com.ltp.ident` offre déjà.
5. Si une APK à part est créée, elle **ne parle pas et ne bouge pas** : elle envoie des événements au sidecar (`POST /v1/robot/event`), et c'est GeeUIVoice qui décide de dire quelque chose via `pending`. Un seul chemin pour la parole.

## Questions pour toi

- Veux-tu une présence par la caméra (le robot remarque qu'on entre dans la pièce), ou la vision à la demande suffit-elle pour l'instant ?
- Une page web d'administration sur le sidecar (liste des voix, rappels, brouillons de skills) te convient-elle à la place d'une interface sur le robot ?
