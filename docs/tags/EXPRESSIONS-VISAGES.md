# Expressions et visages (tags h)

Liste officielle des vidéos d'expression du robot Letianpai RUX (`cmd_tag` `h0001`–`h0302`). Le nom chinois `cmd_tag_name` vient des fichiers de tags du fabricant. L'identifiant est celui passé à `robotStartExpression` / `controlFace`. L'APK GeeUIFace installée sur le robot contient aussi des clips hors liste, jusqu'à `h0351` (328 fichiers) ; voir plus bas pour `h0347`.

Copie machine : [`faces_h_tags.json`](faces_h_tags.json) (champs `cmd_tag`, `cmd_tag_name`, `name_fr`, `usage`, et `uncertain` si la traduction n'est pas sûre).

Les vidéos vivent dans l'app système GeeUIFace (`assets/video/<id>.mp4`). GeeUIVoice n'en embarque qu'un sous-ensemble pour l'écran local (`TextureView`). Un tag absent de l'APK peut quand même s'afficher via le bus (`controlFace`), comme le dégoût `h0033`. Les clips bundlés aujourd'hui :

- `h0001` — colère
- `h0002` — arrogance — bundlé pour l'ancienne nuance « laugh », plus mappée
- `h0006` — grand rire (yeux en X / XD) — nuance laugh seulement
- `h0020` — joie — émotion joyeux et humeur joyeux
- `h0010` — pleurs 1 — humeur triste
- `h0027` — cœur — nuance love
- `h0033` — refus / rejet — dégoût
- `h0036` — clin d'œil — nuance wink
- `h0039` — sommeil — humeur fatigué, nuance sleepy
- `h0058` — anneau froncé — humeur boudeur
- `h0042` — regard en bas à gauche — humeur ennuyé
- `h0046` — surprise
- `h0051` — tremblements — nuance cold
- `h0059` — anneau au repos
- `h0060` — anneau sourire — humeur content
- `h0063` — retour au centre (jaune) — bundlé pour l'ancienne humeur « content »
- `h0099` — a chaud — nuance hot
- `h0116` — cœur avec les doigts — nuance heart
- `h0119` — pleurs — émotion triste
- `h0131` — V5 — bundlé pour l'ancienne nuance « wink »
- `h0133` — peur
- `h0180` — écoute vocale — ancien état hear (toujours bundlé)
- `h0181` — réflexion vocale — nuance thinking 🤔, ancien état think (toujours bundlé)
- `h0292` — bouche qui parle — état speak neutre

Les six visages d'émotion d'origine (PR #15 et la série « Bundle the six emotion face clips ») sont `h0001`, `h0006`, `h0046`, `h0059`, `h0119`, `h0133`. D'autres clips ont été ajoutés ensuite pour le dialogue, les nuances d'emoji et l'humeur. On les garde tous dans l'APK, y compris ceux qui ne sont plus mappés (`h0002`, `h0131`, `h0063`).

## Correspondances utilisées par l'app

### Émotions (`FaceCodes.EMOTION`)

| Rôle | Tag | Nom chinois | Traduction |
|------|-----|-------------|------------|
| joyeux | `h0020` | 快乐 | Joie |
| triste | `h0119` | 哭泣 | Pleurs |
| colère | `h0001` | 愤怒 | Colère |
| peur | `h0133` | 害怕 | Peur |
| surprise | `h0046` | 惊讶 | Surprise |
| dégoût | `h0033` | 抗拒 | Refus / rejet |
| neutre | `h0059` | 常规环 | Anneau au repos |

Il n'existe pas de clip nommé 恶心 ou 厌恶 (dégoût). `h0033` 抗拒 (refus / rejet) reste le plus proche, distinct de la colère `h0001` et de l'anneau froncé `h0058`.

### Nuances d'emoji et de balises (`FaceCodes.NUANCE`)

| Rôle | Tag | Nom chinois | Traduction |
|------|-----|-------------|------------|
| love (🥰😍😘❤💖💕💗) | `h0027` | 爱心 | Cœur |
| heart (🫶) | `h0116` | 比心 | Cœur avec les doigts |
| laugh (😂🤣😆😁) | `h0006` | 大笑 | Grand rire (yeux en X / XD) |
| wink (😉😜) | `h0036` | 单眨眼 | Clin d'œil |
| cry (😭) | `h0010` | 哭泣1 | Pleurs 1 |
| thinking (🤔🧐) | `h0181` | 语音思考 | Réflexion vocale |
| sleepy (😴🥱) | `h0039` | 睡觉 | Sommeil |
| cold (🥶) | `h0051` | 发抖 | Tremblements |
| hot (🥵) | `h0099` | 感到热 | A chaud |

L'amour (`love`) pointe déjà vers `h0027` 爱心. L'utilisateur a confirmé que `h0006` 大笑 montre les yeux en grand X un peu aplati (rire « XD »), pas un sourire : on le garde pour la nuance « laugh » (😂🤣😆😁), et on met l'émotion joyeux (`HAPPY`, `[happy]`, 😊🙂) sur `h0020` 快乐 (joie), présent dans GeeUIFace et désormais dans l'APK. Il n'existe pas de clip nommé 微笑, 开心 ou 高兴 ; `h0060` 笑容环 reste la boucle sourire de l'humeur « content » au repos, pas la réaction joyeux. Le clin d'œil utilisait `h0131` V5 : on le rattache à `h0036` 单眨眼.

### États de dialogue (écoute, réflexion, parole)

Les visages d'écoute et de réflexion sont envoyés sur le bus (`controlFace`) et affichés sur l'écran local (`TextureView`). Ils ne remplacent pas le visage d'émotion pendant la parole.

| État | Moment | Tag par défaut | Nom chinois | Traduction | Réglage |
|------|--------|----------------|-------------|------------|---------|
| Écoute | Après « Hi RUX » / « Hey Dobby », pendant l'enregistrement de la phrase | `h0347` | — | Début VAD (assistant d'origine) | `listen_face` |
| Backend | Fin de la phrase, pendant l'envoi au sidecar / STT / LLM, jusqu'au début de la voix | `h0242` | 无 | Sans nom (attente backend) | `backend_face` (alias `think_face`) |
| Parole | Pendant la synthèse : visage d'émotion (ou `h0292` bouche qui parle si neutre), inchangé | émotion ou `h0292` | — | — | (pas de réglage) |
| Repos | Fin de conversation, ou 12 s sans parole | `h0059` ou humeur (alternance optionnelle) | 常规环 | Anneau au repos | `idle_faces` (défaut `off`), `idle_face_interval` |

L'attente STT et l'appel au modèle sont la même phase dans la boucle : on affiche `h0242` (réglable via `backend_face` ou l'alias `think_face`). La nuance 🤔 de la réponse reste `h0181` 语音思考.

Pourquoi ces tags : `h0347` est le clip que l'assistant d'origine joue au début du VAD. Il est dans l'APK GeeUIFace du robot (`assets/video/h0347.mp4`, 350 Ko) mais absent de la liste publique h0001–h0302. L'ancien `h0180` 语音听 (yeux et un « ? ») reste disponible dans le menu. `h0242` n'a pas de nom officiel (无) ; on s'en sert pour l'attente du backend. `h0181` et `h0050` restent des visages de réflexion, plus pour cette phase.

Au repos, le visage par défaut est `h0059` (ou l'humeur). L'alternance parmi `h0135`–`h0175` est **coupée par défaut** (`idle_faces=off`) : sur le RK3566, un `controlFace` trop fréquent fait planter le décodeur vidéo (`libomxvpu_dec.so`, `Rkvpu_Wait_ProcessPause`) et fige l'écran.

Pour la réactiver : `idle_faces=on` (ou une liste de tags). L'intervalle est alors d'au moins 60 s (90 s par défaut, réglage `idle_face_interval`) afin de laisser la vidéo en cours se terminer. Sont exclus, parce qu'ils ne conviennent pas au repos ou qu'ils se confondent avec le backend :

- stupéfaction : `h0142`, `h0148`
- réflexion : `h0149`, `h0170`
- yeux écarquillés (air surpris) : `h0157`, `h0158`, `h0160`, `h0164`, `h0172`

32 clips restent dans la liste « on ». Une humeur autre que « calme » (triste, fatigué…) garde son visage, sans rotation. On ne change pas de visage si l'écran est éteint (sommeil de nuit Letianpai).

Les identifiants se changent dans le menu (`listen_face`, `backend_face`, `think_face`, `idle_faces`, `idle_face_interval`) ou par extra de lancement. Valeurs acceptées : `h0347` ou `0347`. Un identifiant invalide retombe sur le défaut. L'humeur au repos ne remplace pas le visage tant que le robot écoute, attend le backend ou parle.

### Humeur au repos (`RestFace`)

Les trois boucles d'attente du fabricant sont faites pour tourner à l'infini : `h0059` 常规环 (neutre), `h0060` 笑容环 (sourire), `h0058` 皱眉环 (froncé).

| Rôle | Tag | Nom chinois | Traduction |
|------|-----|-------------|------------|
| calme | `h0059` | 常规环 | Anneau au repos |
| content | `h0060` | 笑容环 | Anneau sourire |
| joyeux | `h0020` | 快乐 | Joie |
| ennuyé | `h0042` | 左下斜 | Regard en bas à gauche |
| boudeur | `h0058` | 皱眉环 | Anneau froncé |
| triste | `h0010` | 哭泣1 | Pleurs 1 |
| fatigué | `h0039` | 睡觉 | Sommeil |

`h0060`, `h0058`, `h0036` et `h0033` sont ajoutés à l'APK pour l'écran local (comme les clips d'humeur et de nuance déjà présents). L'humeur « ennuyé » reste sur `h0042` 左下斜 (regard en bas à gauche, bundlé) : ce n'est pas un visage d'ennui dédié, `h0021` 无奈 serait un autre candidat.

### À ne pas prendre pour une émotion

| Tags | Pourquoi |
|------|----------|
| `h0134` 听歌星光 | Écran musique, pas de la peur |
| `h0189`–`h0191` | Réactions au toucher (caresse, tape, appui long) |
| `h0079`–`h0114`, `h0228`–`h0233` | Habillages GPT / Spark / Wenxin |
| `h0250`–`h0284` | Cris d'animaux (sauf `h0276`, voir plus bas) |
| `h0276` 倾听 | Le nom veut dire « écoute attentive », mais le clip connu montre une libellule |
| `h0290`–`h0302` | Identifiants vides |
| `h0104`–`h0106`, `h0188`, `h0216`–`h0219`, `h0221`–`h0226`, `h0242`–`h0246`, `h0248`–`h0249` | Nommés 无 (sans nom) |

## Liste complète

| Tag | Nom chinois | Traduction | Usage |
|-----|-------------|------------|-------|
| `h0059` | 常规环 | Anneau au repos | Boucle d'attente neutre. Visage de repos par défaut (émotion neutre, humeur calme). |
| `h0060` | 笑容环 | Anneau sourire | Boucle d'attente souriante. Humeur « content » au repos. |
| `h0058` | 皱眉环 | Anneau froncé | Boucle d'attente sourcils froncés. Humeur « boudeur » au repos. À ne pas confondre avec le dégoût (h0033). |
| `h0006` | 大笑 | Grand rire (yeux en X / XD) ✓ vérifié par l'utilisateur | Les yeux forment un grand X un peu aplati, comme un rire « XD », pas un sourire. Nuance « laugh » (😂🤣). L'émotion joyeux utilise h0020 快乐. |
| `h0027` | 爱心 | Cœur | Cœur à l'écran. Nuance « love » (🥰😍😘❤). |
| `h0017` | 皱眉 | Froncement de sourcils | Sourcils froncés, une fois (pas la boucle h0058). |
| `h0007` | 大小眼 | Yeux dépareillés | Un œil plus grand que l'autre. |
| `h0010` | 哭泣1 | Pleurs 1 | Larmes. Humeur « triste » au repos. |
| `h0011` | 眩晕 | Vertige | Yeux qui tournent, étourdissement. |
| `h0016` | 害怕 | Peur | Émotion peur. L'app utilise h0133, pas l'écran musique h0134. |
| `h0034` | 摇头 | Hochement de tête (non) | Dit non de la tête. |
| `h0076` | 左上看 | Regard en haut à gauche | Regarde vers le haut à gauche. |
| `h0024` | 左倾听 | Écoute à gauche | Oriente l'écoute vers la gauche. |
| `h0061` | 斜看 | Regard de biais | Regard en coin. |
| `h0001` | 愤怒 | Colère | Émotion colère (h0001). h0018 狂怒 est plus violent. |
| `h0002` | 傲慢 | Arrogance | Air superior. Anciennement utilisé à tort pour 😂 (getNoListWinGesture). |
| `h0003` | 汗颜 | Gêne (sueur) | Gêné, une goutte de sueur. |
| `h0004` | 睡着 | Endormi | Déjà endormi. Pour s'endormir, voir h0039 睡觉. |
| `h0005` | 芭比Q | « Barbie Q » (grillés) ⚠️ traduction incertaine | Argot : BBQ, « c'est mort / on est cuits ». |
| `h0008` | 闹钟 | Réveil | Alarme / réveil-matin. |
| `h0009` | 疑惑 | Perplexité | Air interrogateur. |
| `h0012` | 睁眼睡 | Dors les yeux ouverts | Sommeil les yeux ouverts. |
| `h0013` | 邪笑 | Sourire malicieux | Sourire en coin, un peu fourbe. |
| `h0014` | 嫉妒 | Jalousie | Air jaloux. |
| `h0015` | 期待 | Attente | A hâte, attend quelque chose. |
| `h0018` | 狂怒 | Rage | Colère plus forte que h0001. Non utilisée par l'app. |
| `h0019` | 左右看 | Regard gauche-droite | Regarde de gauche à droite. |
| `h0020` | 快乐 | Joie | Visage de joie, plus doux que le rire en X de h0006. Émotion joyeux (HAPPY, [happy], 😊🙂) et humeur « joyeux » au repos. |
| `h0021` | 无奈 | Résignation | Soupir, rien à faire. |
| `h0022` | 无奈1 | Résignation 1 | Variante de h0021 无奈. |
| `h0023` | 无奈2 | Résignation 2 | Variante de h0021 无奈. |
| `h0025` | 右倾听 | Écoute à droite | Oriente l'écoute vers la droite. |
| `h0026` | 向下看 | Regard vers le bas | Baisse les yeux. |
| `h0028` | 疼 | Douleur | A mal. |
| `h0029` | 喘气 | Essoufflement | Hors d'haleine. |
| `h0030` | 偷看 | Coup d'œil furtif | Regarde en cachette. |
| `h0031` | 偷看1 | Coup d'œil furtif 1 | Variante de coup d'œil furtif. |
| `h0032` | 拍照 | Photo | Prend une photo / pose appareil. |
| `h0033` | 抗拒 | Refus / rejet | Repousse, refuse. Visage du dégoût dans l'app : pas de clip 恶心/厌恶 dans la liste. |
| `h0035` | 闭嘴 | Tais-toi | Bouche fermée, « silence ». |
| `h0036` | 单眨眼 | Clin d'œil | Un seul clin d'œil. Nuance « wink » des emoji 😉😜. |
| `h0037` | 单眨眼1 | Clin d'œil 1 | Variante de clin d'œil. |
| `h0038` | 单眨眼2 | Clin d'œil 2 | Variante de clin d'œil. |
| `h0039` | 睡觉 | Sommeil | S'endort / dort. Humeur « fatigué » et nuance « sleepy ». |
| `h0040` | 抱歉 | Excuse | Air désolé. |
| `h0041` | 眯眼 | Yeux plissés | Plisse les yeux. |
| `h0042` | 左下斜 | Regard en bas à gauche | Humeur « ennuyé » (approximation : pas de clip « ennui » dédié). |
| `h0043` | 左上斜 | Regard en haut à gauche | Regard en diagonale haut-gauche. |
| `h0044` | 右下斜 | Regard en bas à droite | Regard en diagonale bas-droite. |
| `h0045` | 右上斜 | Regard en haut à droite | Regard en diagonale haut-droite. |
| `h0046` | 惊讶 | Surprise | Émotion surprise (h0046). h0047 est la variante 2. |
| `h0047` | 惊讶2 | Surprise 2 | Variante de h0046 惊讶. |
| `h0048` | 怀疑 | Soupçon | Air méfiant. |
| `h0049` | 怀疑2 | Soupçon 2 | Variante de h0048 怀疑. |
| `h0050` | 思考 | Réflexion | Réfléchit (visage générique). L'app utilise h0181 pour la voix. |
| `h0051` | 发抖 | Tremblements | Tremble, a froid. Nuance « cold » 🥶. |
| `h0052` | 难受 | Malaise | Mal à l'aise, pas bien. |
| `h0053` | 起床气 | Humeur du réveil | De mauvais poil au saut du lit. |
| `h0054` | 委屈 | Vexation | Se sent lésé, a les larmes aux yeux. |
| `h0055` | 打哈欠 | Bâillement | Bâille. |
| `h0056` | 哭泣 | Pleurs | Larmes. L'émotion triste utilise h0119. |
| `h0057` | 左右看 | Regard gauche-droite | Regarde de gauche à droite. |
| `h0062` | 回正白 | Retour au centre (blanc) | Revient de face, variante yeux clairs. |
| `h0063` | 回正黄 | Retour au centre (jaune) | Revient de face, variante yeux jaunes. Anciennement humeur « content » (mal nommé). |
| `h0064` | 吃香蕉 | Mange une banane | Animation ludique. |
| `h0065` | 汽车 | Voiture | Animation voiture. |
| `h0066` | 对话 | Dialogue | Bulle / conversation. |
| `h0067` | 云朵 | Nuage | Animation nuage. |
| `h0068` | 喝果汁 | Boit un jus | Animation ludique. |
| `h0069` | 看书 | Lit un livre | Animation lecture. |
| `h0070` | 吃面 | Mange des nouilles | Animation ludique. |
| `h0071` | 喷图 | Projection d'image ⚠️ traduction incertaine | Peut désigner un jet d'image, un sticker ou un « dump » visuel. |
| `h0072` | 弹球 | Bille / flipper | Joue à la bille. |
| `h0073` | 踢球 | Joue au foot | Animation ballon. |
| `h0074` | 俄罗斯 | Tetris (occupé à jouer) ✓ vérifié par l'utilisateur | Le robot est occupé / en train de jouer à Tetris (俄罗斯方块). Suggestion pour un futur état « tâche longue / occupé » ; aujourd'hui l'attente STT/LLM reste h0181. |
| `h0075` | 打字 | Tape au clavier | Animation dactylographie. |
| `h0077` | 举铁 | Musculation | Soulève des poids. |
| `h0078` | 更好自己 | Meilleure version de soi | Animation « améliorer » / coaching. |
| `h0079` | 紫GPT1 | GPT violet 1 | Habillage de l'assistant GPT violet. Non utilisé. |
| `h0080` | 紫GPT2 | GPT violet 2 | Habillage GPT violet. Non utilisé. |
| `h0081` | 紫GPT3 | GPT violet 3 | Habillage GPT violet. Non utilisé. |
| `h0082` | 紫GPT4 | GPT violet 4 | Habillage GPT violet. Non utilisé. |
| `h0083` | 绿GPT1 | GPT vert 1 | Habillage GPT vert. Non utilisé. |
| `h0084` | 绿GPT2 | GPT vert 2 | Habillage GPT vert. Non utilisé. |
| `h0085` | 绿GPT3 | GPT vert 3 | Habillage GPT vert. Non utilisé. |
| `h0086` | 绿GPT4 | GPT vert 4 | Habillage GPT vert. Non utilisé. |
| `h0087` | 生日快乐 | Joyeux anniversaire | Animation d'anniversaire. |
| `h0088` | 紫GPT1 | GPT violet 1 | Habillage de l'assistant GPT violet. Non utilisé. |
| `h0089` | 紫GPT2 | GPT violet 2 | Habillage GPT violet. Non utilisé. |
| `h0090` | 紫GPT3 | GPT violet 3 | Habillage GPT violet. Non utilisé. |
| `h0091` | 紫GPT4 | GPT violet 4 | Habillage GPT violet. Non utilisé. |
| `h0092` | 紫GPT听 | GPT violet écoute | Habillage GPT violet, état d'écoute. Non utilisé. |
| `h0093` | 猜拳计时 | Compte à rebours (PFC) | Timer du pierre-feuille-ciseaux. |
| `h0094` | 布赢 | Papier gagne | Résultat du jeu : la feuille gagne. |
| `h0095` | 剪刀赢 | Ciseaux gagnent | Résultat du jeu : les ciseaux gagnent. |
| `h0096` | 剪刀输 | Ciseaux perdent | Résultat du jeu : les ciseaux perdent. |
| `h0097` | 打呼噜 | Ronflements | Ronfle. |
| `h0098` | 语音听 | Écoute vocale | État d'écoute de l'assistant. L'app utilise h0180, pas h0098. |
| `h0099` | 感到热 | A chaud | A trop chaud. Nuance « hot » 🥵. |
| `h0100` | 烹饪完成 | Cuisine terminée | Alerte / fin de cuisson. |
| `h0101` | 燃气警报 | Alerte gaz | Alerte domestique. |
| `h0102` | 浸水警报 | Alerte inondation | Alerte domestique (eau). |
| `h0103` | 烟雾警报 | Alerte fumée | Alerte incendie / fumée. |
| `h0104` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0105` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0106` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0107` | 白GPT听 | GPT blanc écoute | Habillage GPT blanc, état d'écoute. Non utilisé. |
| `h0108` | 白色环顾 | Regard circulaire (blanc) | Regarde autour, habillage blanc. |
| `h0109` | 白GPT听 | GPT blanc écoute | Habillage GPT blanc, état d'écoute. Non utilisé. |
| `h0110` | 星火说 | Spark parle | Habillage iFlytek Spark, parole. Non utilisé. |
| `h0111` | 星火说2 | Spark parle 2 | Habillage Spark. Non utilisé. |
| `h0112` | 星火说3 | Spark parle 3 | Habillage Spark. Non utilisé. |
| `h0113` | 星火说4 | Spark parle 4 | Habillage Spark. Non utilisé. |
| `h0114` | 星火听 | Spark écoute | Habillage Spark, écoute. Non utilisé. |
| `h0115` | 666 | « 666 » | Argot internet chinois : « trop fort », « bravo ». |
| `h0116` | 比心 | Cœur avec les doigts | Geste 比心. Nuance « heart » 🫶. |
| `h0117` | good | Good | Signe « good » / pouce ou OK visuel. |
| `h0118` | 害羞 | Timidité | Air timide, rougit. |
| `h0119` | 哭泣 | Pleurs | Larmes. L'émotion triste utilise h0119. |
| `h0120` | 飘过云 | Nuage qui passe | Un nuage traverse l'écran. |
| `h0121` | give me five | Tape-m'en cinq | Geste high five. |
| `h0122` | 砸玻璃 | Brise une vitre | Animation qui casse une vitre. |
| `h0123` | 愚公移山 | Yu Gong déplace les montagnes | Fable : persévérance. Yu Gong creuse la montagne. |
| `h0124` | 香蕉皮 | Peau de banane | Glissade / peau de banane. |
| `h0125` | 右看 | Regard à droite | Regarde à droite. |
| `h0126` | 左看 | Regard à gauche | Regarde à gauche. |
| `h0127` | 选择题 | Question à choix | Affiche un QCM / un choix. |
| `h0128` | Rock | Rock (pierre) ⚠️ traduction incertaine | Pierre du pierre-feuille-ciseaux, ou geste rock. |
| `h0129` | 点头 | Hochement de tête (oui) | Dit oui de la tête. |
| `h0130` | ok | OK | Signe OK. |
| `h0131` | V5 | V5 | Argot 威武 (« impressionnant »), souvent un V de victoire. Ancienne nuance « wink ». |
| `h0132` | 听声波 | Écoute d'ondes | Visualisation d'ondes sonores. |
| `h0133` | 害怕 | Peur | Émotion peur. L'app utilise h0133, pas l'écran musique h0134. |
| `h0134` | 听歌星光 | Écoute de musique (étoiles) | Écran musique, pas une émotion. Ne pas prendre pour de la peur. |
| `h0135` | 低头看 | Regard baissé | Baisse la tête pour regarder. |
| `h0136` | 东张西望 | Regard aux alentours | Regarde un peu partout. |
| `h0137` | 瞎看 | Regard au hasard | Regarde n'importe où. |
| `h0138` | 偷看 | Coup d'œil furtif | Regarde en cachette. |
| `h0139` | 抖机灵 | Fait le malin | Petite vanne, air espiègle. |
| `h0140` | 眨眼笑 | Clin d'œil souriant | Cligne de l'œil en souriant. |
| `h0141` | 眨眼笑2 | Clin d'œil souriant 2 | Variante de h0140. |
| `h0142` | 惊呆 | Stupéfaction | Reste bouche bée. Plus figé que 惊讶. |
| `h0143` | 眯眼看 | Regard les yeux plissés | Observe en plissant les yeux. |
| `h0144` | 想笑 | Envie de rire | Retient un rire. |
| `h0145` | 想笑2 | Envie de rire 2 | Variante de h0144. |
| `h0146` | 想笑3 | Envie de rire 3 | Variante de h0144. |
| `h0147` | 想笑4 | Envie de rire 4 | Variante de h0144. |
| `h0148` | 惊呆2 | Stupéfaction 2 | Variante de h0142. |
| `h0149` | 想一想 | Réfléchit un peu | Court moment de réflexion. |
| `h0150` | 笑看天下 | Rit en regardant le monde | Rire ample, contemplatif. |
| `h0151` | 偷看2 | Coup d'œil furtif 2 | Variante de coup d'œil furtif. |
| `h0152` | 斜看 | Regard de biais | Regard en coin. |
| `h0153` | 左右看看 | Regarde à gauche et à droite | Balaye du regard. |
| `h0154` | 偷看3 | Coup d'œil furtif 3 | Variante de coup d'œil furtif. |
| `h0155` | 瞅一瞅 | Jette un œil | Coup d'œil rapide. |
| `h0156` | 眨眼 | Clignement | Cligne des deux yeux (pas un clin d'œil). |
| `h0157` | 瞪眼 | Yeux écarquillés | Ouvre grand les yeux. |
| `h0158` | 瞪眼看 | Regard les yeux écarquillés | Fixe en ouvrant grand les yeux. |
| `h0159` | 瞅右边 | Coup d'œil à droite | Jette un œil à droite. |
| `h0160` | 瞪大眼 | Grands yeux écarquillés | Ouvre encore plus les yeux. |
| `h0161` | 瞅右边2 | Coup d'œil à droite 2 | Variante de h0159. |
| `h0162` | 悄悄看 | Regard discret | Regarde sans se faire remarquer. |
| `h0163` | 扫视 | Balayage du regard | Parcourt la scène des yeux. |
| `h0164` | 瞪大眼2 | Grands yeux écarquillés 2 | Variante de h0160. |
| `h0165` | 瞅右边3 | Coup d'œil à droite 3 | Variante de h0159. |
| `h0166` | 瞅右边4 | Coup d'œil à droite 4 | Variante de h0159. |
| `h0167` | 瞅右边5 | Coup d'œil à droite 5 | Variante de h0159. |
| `h0168` | 瞅右边6 | Coup d'œil à droite 6 | Variante de h0159. |
| `h0169` | 瞅右边7 | Coup d'œil à droite 7 | Variante de h0159. |
| `h0170` | 考虑 | Réflexion | Pèse le pour et le contre. |
| `h0171` | 眨眼2 | Clignement 2 | Variante de clignement. |
| `h0172` | 瞪眼4 | Yeux écarquillés 4 | Variante de h0157. |
| `h0173` | 眨眼笑3 | Clin d'œil souriant 3 | Variante de h0140. |
| `h0174` | 超左看 | Regard loin à gauche | Regarde tout à gauche. |
| `h0175` | 眨眼3 | Clignement 3 | Variante de clignement. |
| `h0176` | 眨眼4 | Clignement 4 | Variante de clignement. |
| `h0177` | 收音 | Prise de son | Enregistre / capte le son. |
| `h0178` | 失落 | Déception | Air déçu, abattu. |
| `h0179` | 偷看2 | Coup d'œil furtif 2 | Variante de coup d'œil furtif. |
| `h0180` | 语音听 | Écoute vocale | Ancien visage d'écoute de l'app (yeux et un « ? »). Le défaut est maintenant h0347. Toujours utilisable via `listen_face`. |
| `h0181` | 语音思考 | Réflexion vocale | Nuance 🤔. Ancien visage d'attente STT/LLM (remplacé par h0242). |
| `h0182` | 拍照 | Photo | Prend une photo / pose appareil. |
| `h0183` | 紫GPT思考 | GPT violet réfléchit | Habillage GPT violet. Non utilisé. |
| `h0184` | 紫GPT听 | GPT violet écoute | Habillage GPT violet, état d'écoute. Non utilisé. |
| `h0185` | 星火听 | Spark écoute | Habillage Spark, écoute. Non utilisé. |
| `h0186` | 星火思考 | Spark réfléchit | Habillage Spark, réflexion. Non utilisé. |
| `h0187` | 看手势 | Regard vers un geste | Regarde un geste de la main. |
| `h0188` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0189` | 摸头 | Caresse sur la tête | Réaction au toucher (caresse). Pas une émotion. |
| `h0190` | 拍头 | Tape sur la tête | Réaction au toucher (petite tape). Pas une émotion. |
| `h0191` | 长按头 | Appui long sur la tête | Réaction au toucher (appui long). Pas une émotion. |
| `h0192` | 障碍 | Obstacle | Rencontre un obstacle. |
| `h0193` | 障碍2 | Obstacle 2 | Variante d'obstacle. |
| `h0194` | 障碍3 | Obstacle 3 | Variante d'obstacle. |
| `h0195` | 障碍4 | Obstacle 4 | Variante d'obstacle. |
| `h0196` | 挣扎 | Se débat | Essaie de se dégager. |
| `h0197` | 左右扫 | Balayage gauche-droite | Balaye du regard, plus ample. |
| `h0198` | 瞪眼看 | Regard les yeux écarquillés | Fixe en ouvrant grand les yeux. |
| `h0199` | 瞪左看 | Regard écarquillé à gauche | Yeux grands ouverts vers la gauche. |
| `h0200` | 瞪左看2 | Regard écarquillé à gauche 2 | Variante de h0199. |
| `h0201` | 跳看 | Regard saccadé | Le regard saute d'un point à l'autre. |
| `h0202` | 朝左看2 | Regard vers la gauche 2 | Regarde à gauche, variante. |
| `h0203` | 朝左看3 | Regard vers la gauche 3 | Regarde à gauche, variante. |
| `h0204` | 生日快乐2 | Joyeux anniversaire 2 | Variante d'anniversaire. |
| `h0205` | 能做什么 | Que puis-je faire | Présentation des capacités. |
| `h0206` | 奸笑 | Rire narquois | Rire un peu sournois. |
| `h0207` | 奸笑2 | Rire narquois 2 | Variante de h0206. |
| `h0208` | 右拽拽 | Attitude prétentieuse à droite | Air « je suis trop fort », côté droit. 拽 = fanfaron. |
| `h0209` | 左拽拽 | Attitude prétentieuse à gauche | Air « je suis trop fort », côté gauche. |
| `h0210` | 大笑 | Grand rire | Même nom officiel que h0006. L'apparence en X / XD n'a été confirmée que pour h0006. Non utilisé par l'app. |
| `h0211` | 伤心2 | Tristesse 2 | Autre visage triste. L'app garde h0119 哭泣. |
| `h0212` | 弹吉他 | Joue de la guitare | Animation guitare. |
| `h0213` | 不错 | Pas mal | Air satisfait, « pas mal ». |
| `h0214` | 嘿嘿嘿 | Hi hi hi | Petit rire « héhéhé ». |
| `h0215` | 眨眼 | Clignement | Cligne des deux yeux (pas un clin d'œil). |
| `h0216` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0217` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0218` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0219` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0220` | 低电量 | Batterie faible | Indicateur de batterie faible. |
| `h0221` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0222` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0223` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0224` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0225` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0226` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0227` | 监控中 | Surveillance en cours | Mode monitoring. |
| `h0228` | 文心思考 | Wenxin réfléchit | Habillage Baidu Wenxin. Non utilisé. |
| `h0229` | 文心说 | Wenxin parle | Habillage Wenxin, parole. Non utilisé. |
| `h0230` | 文心说2 | Wenxin parle 2 | Habillage Wenxin. Non utilisé. |
| `h0231` | 文心说3 | Wenxin parle 3 | Habillage Wenxin. Non utilisé. |
| `h0232` | 文心说4 | Wenxin parle 4 | Habillage Wenxin. Non utilisé. |
| `h0233` | 文心听 | Wenxin écoute | Habillage Wenxin, écoute. Non utilisé. |
| `h0234` | 该喝水 | Il faut boire | Rappel santé : boire. |
| `h0235` | 该运动 | Il faut bouger | Rappel santé : se dépenser. |
| `h0236` | 该健身 | Il faut faire du sport | Rappel santé : musculation / sport. |
| `h0237` | 注意坐姿 | Attention à la posture | Rappel de s'asseoir droit. |
| `h0238` | 该喝水2 | Il faut boire 2 | Variante du rappel boire. |
| `h0239` | 该运动2 | Il faut bouger 2 | Variante du rappel mouvement. |
| `h0240` | 该健身2 | Il faut faire du sport 2 | Variante du rappel sport. |
| `h0241` | 注意坐姿2 | Attention à la posture 2 | Variante du rappel posture. |
| `h0242` | 无 | Sans nom | Attente backend (STT / LLM). Réglage `backend_face`. |
| `h0243` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0244` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0245` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0246` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0247` | 要提醒了 | Rappel imminent | Un rappel va sonner. |
| `h0248` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0249` | 无 | Sans nom | Réservé, pas de nom officiel. |
| `h0250` | 鸟叫 | Cri d'oiseau | Imitation animale. Pas une émotion. |
| `h0251` | 老虎叫 | Cri du tigre | Imitation animale. Pas une émotion. |
| `h0252` | 豹子叫 | Cri du léopard | Imitation animale. Pas une émotion. |
| `h0253` | 狮子叫 | Cri du lion | Imitation animale. Pas une émotion. |
| `h0254` | 猪叫 | Cri du cochon | Imitation animale. Pas une émotion. |
| `h0255` | 羊叫 | Bêlement | Imitation animale. Pas une émotion. |
| `h0256` | 小狗叫 | Aboiement | Imitation animale. Pas une émotion. |
| `h0257` | 牛叫 | Meuglement | Imitation animale. Pas une émotion. |
| `h0258` | 狼叫 | Hurlement de loup | Imitation animale. Pas une émotion. |
| `h0259` | 苍蝇叫 | Bourdonnement de mouche | Imitation animale. Pas une émotion. |
| `h0260` | 蚊子叫 | Bourdonnement de moustique | Imitation animale. Pas une émotion. |
| `h0261` | 蜜蜂叫 | Bourdonnement d'abeille | Imitation animale. Pas une émotion. |
| `h0262` | 知了叫 | Cigale | Imitation animale. Pas une émotion. |
| `h0263` | 青蛙叫 | Coassement | Imitation animale. Pas une émotion. |
| `h0264` | 马叫 | Hennissement | Imitation animale. Pas une émotion. |
| `h0265` | 公鸡打鸣 | Chant du coq | Imitation animale. Pas une émotion. |
| `h0266` | 猫叫 | Miaulement | Imitation animale. Pas une émotion. |
| `h0267` | 鸭子叫 | Coin-coin | Imitation animale. Pas une émotion. |
| `h0268` | 乌鸦叫 | Croassement | Imitation animale. Pas une émotion. |
| `h0269` | 大象叫 | Barrit d'éléphant | Imitation animale. Pas une émotion. |
| `h0270` | 大鹅叫 | Cri de l'oie | Imitation animale. Pas une émotion. |
| `h0271` | 老鹰叫 | Cri de l'aigle | Imitation animale. Pas une émotion. |
| `h0272` | 鹤叫 | Cri de la grue | Imitation animale. Pas une émotion. |
| `h0273` | 老鼠叫 | Cri de souris | Imitation animale. Pas une émotion. |
| `h0274` | 骆驼叫 | Cri du chameau | Imitation animale. Pas une émotion. |
| `h0275` | 驴叫 | Braiment | Imitation animale. Pas une émotion. |
| `h0276` | 倾听 | Écoute attentive | Le nom dit « écouter », mais le clip connu montre une libellule, pas un visage d'écoute. Ne pas l'utiliser pour l'écoute vocale. |
| `h0277` | 蛐蛐 | Grillon | Imitation animale (蛐蛐). Pas une émotion. |
| `h0278` | 蛇叫 | Sifflement de serpent | Imitation animale. Pas une émotion. |
| `h0279` | 母鸡叫 | Cot-cot de poule | Imitation animale. Pas une émotion. |
| `h0280` | 熊叫 | Cri de l'ours | Imitation animale. Pas une émotion. |
| `h0281` | 蟋蟀叫 | Cri du grillon | Imitation animale (蟋蟀). Pas une émotion. |
| `h0282` | 蝈蝈叫 | Cri de la sauterelle | Imitation animale (蝈蝈). Pas une émotion. |
| `h0283` | 海豚叫 | Cri du dauphin | Imitation animale. Pas une émotion. |
| `h0284` | 猴子叫 | Cri du singe | Imitation animale. Pas une émotion. |
| `h0285` | 表演 | Spectacle | Animation de représentation. |
| `h0286` | BBOX | Beatbox | Animation beatbox (B-box). |
| `h0287` | 圣诞节 | Noël | Animation de Noël. |
| `h0288` | 新年 | Nouvel An | Animation du Nouvel An. |
| `h0289` | 复活节 | Pâques | Animation de Pâques. |
| `h0290` | h0290 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0291` | h0291 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0292` | h0292 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0293` | h0293 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0294` | h0294 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0295` | h0295 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0296` | h0296 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0297` | h0297 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0298` | h0298 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0299` | h0299 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0300` | h0300 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0301` | h0301 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |
| `h0302` | h0302 | Réservé (sans nom) | Identifiant vide dans la liste officielle, jusqu'à h0302. |


## Clips présents sur le robot, hors liste publique

L'APK système GeeUIFace du robot (`com.geeui.face`, `assets/video/`) contient **328** fichiers `hNNNN.mp4`, de `h0001` à `h0351`. La liste officielle s'arrête à `h0302`. Manquent dans cette plage élargie : `h0303`, `h0341`, `h0346`, `h0352` (ce dernier est cité par l'assistant d'origine, mais le fichier n'est pas dans l'APK).

| Tag | Nom | Usage |
|-----|-----|-------|
| `h0347` | Début VAD (pas de nom dans la liste publique) | Visage d'écoute par défaut de l'app. L'assistant d'origine le joue au début du VAD. Fichier `assets/video/h0347.mp4` (350 Ko) dans GeeUIFace sur le robot. |

Les autres identifiants `h0304`–`h0351` (sauf les absents ci-dessus) existent sur le robot mais n'ont pas de nom officiel ici.

## Vérifié par l'utilisateur

| Tag | Nom chinois | Note |
|-----|-------------|------|
| `h0006` | 大笑 | Les yeux forment un grand X un peu aplati (rire « XD »), pas un sourire. Gardé pour 😂🤣, plus pour le joyeux simple. |
| `h0074` | 俄罗斯 | Tetris : indique que le robot est occupé à jouer. Pas branché dans l'app (pas d'état « occupé à jouer » aujourd'hui ; l'attente STT/LLM utilise `h0242`). |

## Traductions incertaines

| Tag | Nom chinois | Traduction | Usage |
|-----|-------------|------------|-------|
| `h0005` | 芭比Q | « Barbie Q » (grillés) ⚠️ traduction incertaine | Argot : BBQ, « c'est mort / on est cuits ». |
| `h0071` | 喷图 | Projection d'image ⚠️ traduction incertaine | Peut désigner un jet d'image, un sticker ou un « dump » visuel. |
| `h0128` | Rock | Rock (pierre) ⚠️ traduction incertaine | Pierre du pierre-feuille-ciseaux, ou geste rock. |
