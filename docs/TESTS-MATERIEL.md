# Tests sur le matériel (nimbus + robot)

Tout ce qui a été codé sans accès à nimbus ni au robot, à vérifier quand l'accès revient. Chaque PR a aussi sa propre liste « Tests sur matériel ».

Variables utilisées ci-dessous (à adapter) :

```bash
SC=~/GeeUIVoiceSidecar                 # dépôt du sidecar sur nimbus
NB=~/GeeUIVoice/deploy/nanobot         # dossier du déploiement nanobot (utilisateur bob, Docker rootless)
APK=app/build/outputs/apk/debug/app-debug.apk
```

## 0. Ordre de fusion et de déploiement

| Étape | GeeUIVoice | GeeUIVoiceSidecar |
|---|---|---|
| 1 | #15 emoji → visages, #16 web + météo, #17 qui parle | #2 qui parle |
| 2 | #18 initiative | #3 initiative (empilée sur #2) |
| 3 | #19 souvenirs (empilée sur #18) | #4 souvenirs (empilée sur #3) |
| 4 | #21 humeur (empilée sur #18) | #6 humeur (empilée sur #4) |
| indépendantes | #22 vision, #23 skills appris, #24 maison | #5 mots météo, #7 vision, #8 skills appris |
| 5 | #25 enfant / invité (empilée sur #17) | #9 enfant / invité (empilée sur #2) |

Conflits attendus entre PR GeeUIVoice : ajouts en fin de `deploy/nanobot/README.md` et `workspace/AGENTS.md` (garder tous les blocs), `VoiceSession.kt` entre #15, #18, #22 et #25 (garder tous les paramètres du constructeur, un seul `companion object` avec toutes les constantes, `look()` de #22 avant le chat, `ActionTags.split` de #25 avant `spoken()`, les lignes de `spoken()` de #15 et #18). Sidecar : `.env.example` (blocs ajoutés au même endroit ; une seule ligne `SIDECAR_ADMIN_KEY` et `NANOBOT_WORKSPACE`), `Dockerfile` (`COPY *.py`, identique partout), imports en tête de `emo_server.py`.

Après chaque fusion, sur nimbus :

```bash
cd $SC && git pull && docker compose up -d --build && docker compose logs --tail 30
cd $NB && git pull && cp workspace/SOUL.md workspace/AGENTS.md data/workspace/ && docker compose restart
```

Puis sur le PC qui construit l'APK : `./gradlew :app:assembleDebug && adb install -r $APK`.

## 1. Déjà fusionné, jamais testé sur le robot (#14)

- [ ] Menu réglages : l'ouvrir, modifier un champ, enregistrer → pas de plantage (`adb logcat -b crash` vide) ; si le micro tournait, il redémarre.
- [ ] Début de phrase : dire « voiture » juste après une réponse du robot → le HUD affiche « voiture » complet. Idem en parlant doucement.

## 2. Visages depuis les emoji (#15)

- [ ] Faire répondre nanobot avec un emoji (« réponds avec un emoji qui rit ») → visage h0002, l'emoji n'est pas lu.
- [ ] 🥰 → h0027, 🤔 → h0181, 😊 → h0006. `adb logcat | grep -i face` pour voir le code envoyé.
- [ ] Regarder chaque vidéo de la table `FaceCodes.kt` sur le robot et changer celles qui ne collent pas.

## 3. Recherche web et météo (#16)

- [ ] Ajouter le bloc `tools.web` dans `$NB/data/config.json`, copier le skill `meteo`, `docker compose restart` ; logs : `web_search` et `web_fetch` dans la liste des outils.
- [ ] « C'est quoi la météo à Paris aujourd'hui ? » → chiffres identiques à `curl 'https://wttr.in/Paris?format=3'`.
- [ ] « Quel temps fera-t-il demain à Lyon ? » → cohérent avec Open-Meteo.
- [ ] Si le preset rapide invente : mettre `météo,meteo,température,pluie,cherche,internet,actualité` dans `NANOBOT_HEAVY_WORDS` du sidecar et refaire.

## 4. Qui parle (#17, sidecar #2)

- [ ] `.env` du sidecar : `SPEAKER_MODEL=/models/wespeaker_en_voxceleb_resnet34_LM.onnx`, `SIDECAR_ADMIN_KEY=<secret>` ; `docker compose up -d --build` ; logs : téléchargement du modèle (25 Mo).
- [ ] `curl -H "Authorization: Bearer <secret>" http://nimbus:13306/v1/speakers` → `"enabled": true`.
- [ ] « Retiens ma voix, je suis Paul » → 3 phrases de 2-3 s → « C'est noté, Paul. ». Logs : `chat route=local`.
- [ ] « Qui suis-je ? » → « Tu es Paul, … NN % ». HUD : « Paul : … ».
- [ ] Une deuxième personne : « Qui suis-je ? » → non reconnue ; « Retiens ma voix, je suis Paul » → refus ; puis elle s'enregistre sous son prénom.
- [ ] Conversation : logs sidecar `session=rux-paul` ; base nanobot : `python3 -c "import sqlite3,sys; print([r[0] for r in sqlite3.connect(sys.argv[1]).execute('select key from sessions')])" "$(find $NB/data -name sessions.sqlite3 | head -1)"` → `api:rux-paul`.
- [ ] **Réglage du seuil** : 20 phrases par personne à 1 m et à 3 m, ventilateur allumé. `docker compose logs sidecar | grep -o '"speaker_score": [0-9.]*'`. Ajuster `SPEAKER_THRESHOLD` / `SPEAKER_MARGIN` / `SPEAKER_DOUBT`.
- [ ] `docker stats` pendant une phrase : CPU de Whisper + emotion2vec + empreinte.
- [ ] « Oublie ma voix » → `/v1/speakers` ne la liste plus.

## 5. Initiative (#18, sidecar #3)

- [ ] `.env` : `INITIATIVE_ENABLED=1`, `TZ=Europe/Paris`, `NANOBOT_WORKSPACE=/nanobot-workspace` ; décommenter le montage `…/data/workspace:/nanobot-workspace:ro` dans `$SC/docker-compose.yml` avec le vrai chemin ; vérifier que le conteneur peut lire `memory/MEMORY.md` (`docker compose exec sidecar head /nanobot-workspace/memory/MEMORY.md`).
- [ ] Robot au repos : `docker compose logs -f sidecar | grep robot/pending` → une requête toutes les 15 s ; aucune pendant une conversation ou micro coupé.
- [ ] « Rappelle-moi dans deux minutes de boire de l'eau » → la balise n'est pas lue ; `docker compose exec sidecar cat /data/reminders.json` ; 2 min après, « petit rappel : boire de l'eau » ; log `POST /v1/robot/ack`.
- [ ] Si Gemma n'écrit pas `[rappel:…]` : noter, tester avec le preset lourd.
- [ ] Ligne ajoutée à la main dans `MEMORY.md` : `- <date du jour> | Paul | test du robot | demander si ça a marché`. Paul parle → au repos suivant, le robot prend des nouvelles.
- [ ] « Ne me dérange pas pendant une heure » → réponse du sidecar ; plus d'initiative sauf rappels ; « tu peux reparler ». Attention : « tais-toi » seul est un skill de l'app (volume 0).
- [ ] Lendemain matin : premier « bonjour » de Paul → le robot commence par le saluer.
- [ ] Après 3 h : `docker compose logs sidecar | grep nightly` → `/compact` par personne puis `/dream` ok=True.
- [ ] Une heure au repos : consommation (`adb shell dumpsys batterystats --reset` puis lecture une heure après).

## 6. Souvenirs (#19, sidecar #4)

- [ ] Installer `USER.md`, `memory/MEMORY.md` (sans écraser), `prompts/dream.md`, `skills/oublie` (commandes du README nanobot) ; `docker compose restart`.
- [ ] Script « Vérifier un Dream » du README nanobot (Qwen3) : ligne « À suivre » à la bonne date, titres gardés ; `/dream-log`.
- [ ] Même chose avec « (un invité parle) … » : rien d'écrit.
- [ ] « J'aime le jazz » → après Dream, dans `## Paul` ; « Oublie que j'aime le jazz » → confirmation → ligne retirée, et pas remise au Dream suivant.
- [ ] « Oublie tout sur moi » → « oui » → section `## Paul` retirée, log sidecar `forget voice person=paul removed=True`.
- [ ] Bout en bout : « J'ai un entretien mardi chez Dupont » (Paul), Dream la nuit, le mercredi Paul parle → le robot demande comment ça s'est passé.

## 7. Humeur (#21, sidecar #6)

- [ ] `.env` sidecar : `ROBOT_MOOD_ENABLED=1` ; `docker compose up -d`. `curl http://nimbus:13306/v1/robot/mood` → `calme`, visage h0059.
- [ ] Trois « merci, t'es trop mignon » → `joyeux`, au repos visage h0006 en moins de 15 s ; la bouche (h0292) reste pendant les réponses ; logs nanobot : message qui commence par « (humeur de RUX : de très bonne humeur) ».
- [ ] `adb logcat | grep -i visage` : pas d'erreur de lecture vidéo pour h0063, h0042, h0010, h0039.
- [ ] Seul 4 h en journée → `ennuye` (h0042) ; revenir → réponse « content de te revoir » une fois. Après 21 h 30 → `fatigue` (h0039).
- [ ] Une journée normale : l'humeur ne doit pas être pénible ; régler `ROBOT_MOOD_*` sinon.

## 8. Vision à la demande (#22, sidecar #7)

- [ ] nimbus : `lemonade-server pull Gemma-4-E2B-it-GGUF` ; `curl` « Vérifier le modèle à la main » du README sidecar avec une photo d'objet et une page de texte (qualité en français, texte recopié, `time`).
- [ ] `.env` sidecar : `VISION_MODEL=Gemma-4-E2B-it-GGUF` ; `docker compose up -d --build`.
- [ ] Robot : `adb shell pm grant com.geeui.voice android.permission.CAMERA` ; `adb shell dumpsys media.camera | grep -i "facing"` ; service avec `-e host http://<nimbus>:13306/v1`.
- [ ] « Qu'est-ce que tu vois ? » devant un objet → diagnostic `photo NN Ko`, bonne description ; « lis ce papier » → texte lu. Noter latence, orientation, netteté.
- [ ] Reconnaissance faciale du fabricant active → photo possible ou `caméra occupée` ?
- [ ] Logs sidecar : `vision model=…`, jamais de `base64` ; `adb shell ls /sdcard/DCIM /sdcard/Pictures` : rien de nouveau.

## 9. Skills appris à la voix (#23, sidecar #8)

- [ ] nanobot : `cp -r workspace/skills/apprendre data/workspace/skills/ && mkdir -p data/workspace/brouillons`, `"skill-creator"` dans `disabledSkills`, `docker compose restart`.
- [ ] Sidecar : `SIDECAR_ADMIN_KEY`, `NANOBOT_WORKSPACE=/nanobot-workspace`, montages `brouillons` et `skills` en écriture ; `docker compose up -d`.
- [ ] « Apprends : quand je dis bonne nuit, éteins ton antenne » → « je l'enregistre ? » → « oui » → `ls $NB/data/workspace/brouillons/`, rien dans `skills/`.
- [ ] `http://nimbus:13306/admin/skills` : mot de passe, brouillon affiché, Valider → `skills/bonne-nuit/` ; « bonne nuit » → `[light:off]` (antenne éteinte si #25 est fusionnée).
- [ ] Brouillon écrit à la main avec `[shell:ls]` → pas de bouton Valider.

## 10. La maison (#24)

- [ ] Home Assistant : intégration MCP, Configurer (Device control seul, sans administrateur, Read-only), utilisateur `rux` non administrateur, jeton longue durée.
- [ ] `cd $NB && cp ha.env.example ha.env && chmod 600 ha.env` ; `curl` d'`initialize` du README → `serverInfo`.
- [ ] Fusion `jq` de `home-assistant.example.json` (IP dans `ssrfWhitelist`), `docker compose up -d`, `docker compose logs nanobot-rux | grep -i mcp`.
- [ ] « Il fait combien dans la chambre ? » → bonne valeur, latence notée ; « allume le salon » en lecture seule → « je ne peux pas ».
- [ ] Étape 2 (`/api/mcp/assist` + `HassTurnOn`, `HassTurnOff`, `HassLightSet`) : « allume le salon » → allumé ; « ouvre les volets » → « tu confirmes ? » ou refus.
- [ ] Invité : « allume le salon » → aucune action (route Lemonade).

## 11. Enfant / invité (#25, sidecar #9)

- [ ] `speakers.json` vide : inscrire Paul puis Léa → `curl -H "Authorization: Bearer $KEY" http://nimbus:13306/v1/speakers | jq '.people[]|{id,role}'` → Léa `enfant`.
- [ ] Paul : « Mets ton antenne en bleu » → bleue, balise jamais lue ; « avance un peu » → « Je peux y aller ? » → « oui » → marche ; « non » ou 10 s de silence → rien.
- [ ] Léa : « avance », « rentre à la base » → « Je n'ai pas le droit… », aucun mouvement ; « plus fort » marche ; ton et vocabulaire adaptés.
- [ ] Paul : « Léa est une adulte » → rôle changé ; Léa : « Paul est un enfant » → refus.
- [ ] Voix inconnue : logs `reason=guest`, rien dans nanobot ; « avance » → refus.
- [ ] Scores `speaker_score` d'une voix d'enfant sur plusieurs jours.

## 12. À noter pendant les tests

- Scores `speaker_score` réels (bons / mauvais locuteurs), latence d'une phrase complète, phrases où Gemma oublie une balise, visages qui ne collent pas.
