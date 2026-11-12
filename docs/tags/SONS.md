# Sons (tags a)

Liste officielle des effets sonores du robot Letianpai RUX (`cmd_tag` `a0001`–`a0133`). Le fichier source n'avait pas le `[` d'ouverture et se terminait par une virgule : la copie ci-dessous est un tableau JSON valide. Plusieurs numéros manquent (ils n'étaient pas dans la liste fournie, pas forcément absents du robot).

Copie machine : [`sounds_a_tags.json`](sounds_a_tags.json) (champs `cmd_tag`, `cmd_tag_name`, `name_fr`, `usage`, et `uncertain` si la traduction n'est pas sûre).

Ces ids passent par `setAudioEffect("controlSound", id)` / le champ `sound` de `SdkPose`. Ce ne sont pas les noms symboliques du firmware (`happy`, `sad`, …), qu'il ne faut pas mélanger dans le même appel. Voir aussi [ROBOT.md](../ROBOT.md).

## Correspondances utilisées par l'app (`SdkMap`)

| Émotion | Tag | Nom chinois | Traduction |
|---------|-----|-------------|------------|
| joyeux | `a0032` | 哈哈哈 | Ha ha ha |
| triste | `a0086` | 难过 | Tristesse |
| colère | `a0020` | 生气 | Colère |
| peur | `a0037` | 害怕 | Peur |
| surprise | `a0095` | 惊讶 | Surprise |
| dégoût | `a0033` | 反感 | Dégoût / aversion |
| neutre | — | — | Pas de son. |

Le dégoût n'avait pas de son (« no stock a00xx »). La liste officielle contient `a0033` 反感 (aversion / dégoût), plus précis que `a0053` 讨厌 (déplaisir).

Quand le nom chinois dit « 和N重复 » ou « 和N一样 », c'est un doublon du clip `a00N` (parfois `a0N`). `a0071` renvoie à `a0051`, qui n'apparaît pas dans cette liste.

## Liste complète

| Tag | Nom chinois | Traduction | Usage |
|-----|-------------|------------|-------|
| `a0001` | 嘟 | Bip | Court bip. |
| `a0005` | 打铁声 | Coup de forge | Bruit de marteau sur le fer. |
| `a0007` | 激光 | Laser | Effet laser. |
| `a0010` | 脉冲 | Impulsion | Impulsion électronique. |
| `a0011` | 电磁 | Électromagnétique | Effet électromagnétique. |
| `a0012` | 电磁2 | Électromagnétique 2 | Variante de a0011. |
| `a0014` | 冲锋枪 | Mitraillette | Rafale d'arme (effet sonore). |
| `a0019` | 咕咚 | Glouglou / plouf | Onomatopée d'un objet qui tombe dans l'eau, ou d'une gorgée. |
| `a0020` | 生气 | Colère | Son de l'émotion colère (SdkMap). |
| `a0021` | 嗡嗡 | Bourdonnement | Vrombissement / buzz. |
| `a0024` | 避障 | Évitement d'obstacle | Son d'évitement. |
| `a0025` | 充电 | Charge | Branche / charge. |
| `a0026` | 充电2 | Charge 2 | Variante de charge. |
| `a0027` | 充电3 | Charge 3 | Variante de charge. |
| `a0028` | 点击 | Clic | Clic d'interface. |
| `a0029` | 闹钟 | Réveil | Sonnerie d'alarme. |
| `a0030` | 舒服 | Bien-être | Soupir de confort. |
| `a0031` | 疑惑 | Perplexité | Son interrogateur. |
| `a0032` | 哈哈哈 | Ha ha ha | Rire. Son de l'émotion joyeux (SdkMap). |
| `a0033` | 反感 | Dégoût / aversion | Son le plus proche du dégoût. Utilisé par l'émotion « disgust ». |
| `a0034` | 眩晕 | Vertige | Son d'étourdissement. |
| `a0035` | 眩晕2 | Vertige 2 | Variante de a0034. |
| `a0036` | 疲劳 | Fatigue | Son de fatigue. |
| `a0037` | 害怕 | Peur | Son de l'émotion peur (SdkMap). |
| `a0038` | 寻人 | Recherche de quelqu'un | Son de tracking / recherche de personne. |
| `a0039` | 惊喜感 | Bonne surprise | Surprise agréable. Pas le son de l'émotion surprise (a0095). |
| `a0040` | 完成 | Terminé | Jingle de fin / succès. |
| `a0041` | 和29重复 | Doublon de a0029 | Même clip que le réveil a0029, d'après le nom officiel. |
| `a0053` | 讨厌 | Déplaisir | « Je n'aime pas ». Proche du dégoût, moins précis que a0033 反感. |
| `a0054` | 我是乐天派 | « Je suis Letianpai » | Phrase de marque du fabricant. |
| `a0055` | 低电量 | Batterie faible | Alerte batterie. |
| `a0056` | 警报 | Alerte | Alarme générique. |
| `a0057` | 没电了 | Plus de batterie | Batterie vide. |
| `a0058` | 主跳转 | Navigation principale ⚠️ traduction incertaine | Sans doute un jingle de changement d'écran principal. |
| `a0059` | 错误 | Erreur | Son d'erreur. |
| `a0064` | 喘气 | Essoufflement | Hors d'haleine. |
| `a0067` | 啊呜 | Ahou / miam ⚠️ traduction incertaine | Onomatopée (morsure ou cri « ahou »). |
| `a0069` | 噔噔噔~ | Dun-dun-dun | Accord de suspense / révélation. |
| `a0070` | 慌张 | Panique | Affolement. Plus agité que a0037 害怕. |
| `a0071` | 和51重复 | Doublon de a0051 ⚠️ traduction incertaine | Le nom dit « même clip que 51 », mais a0051 n'est pas dans cette liste. |
| `a0074` | 和31重复 | Doublon de a0031 | Même clip que la perplexité a0031, d'après le nom officiel. |
| `a0077` | 打呼噜 | Ronflements | Ronfle. |
| `a0078` | 和36重复 | Doublon de a0036 | Même clip que la fatigue a0036, d'après le nom officiel. |
| `a0082` | 和70重复 | Doublon de a0070 | Même clip que la panique a0070, d'après le nom officiel. |
| `a0083` | 和64重复 | Doublon de a0064 | Même clip que l'essoufflement a0064, d'après le nom officiel. |
| `a0084` | 回应 | Réponse | Petite réponse vocale / ack. |
| `a0086` | 难过 | Tristesse | Son de l'émotion triste (SdkMap). |
| `a0088` | 关机 | Extinction | Jingle d'arrêt. |
| `a0089` | 啾~~ | Tchiou ⚠️ traduction incertaine | Onomatopée légère (baiser ou oiseau). |
| `a0090` | 害羞 | Timidité | Son timide. |
| `a0091` | 和77重复 | Doublon de a0077 | Même clip que les ronflements a0077, d'après le nom officiel. |
| `a0092` | 偷瞄 | Coup d'œil furtif | Son d'un regard en cachette. |
| `a0093` | 开机 | Allumage | Jingle de démarrage. |
| `a0094` | 寻人2 | Recherche de quelqu'un 2 | Variante de a0038. |
| `a0095` | 惊讶 | Surprise | Son de l'émotion surprise (SdkMap). |
| `a0097` | 和95一样 | Identique à a0095 | Même clip que la surprise a0095, d'après le nom officiel. |
| `a0098` | 累了 | Fatigué | Soupir de fatigue. |
| `a0099` | 睡醒 | Sortie de sommeil | Vient de se réveiller. |
| `a0100` | 生日快乐 | Joyeux anniversaire | Jingle d'anniversaire. |
| `a0104` | 噔噔噔~ | Dun-dun-dun | Accord de suspense / révélation. |
| `a0106` | 打乌拉 | « Da wula » ⚠️ traduction incertaine | Nom opaque. Peut-être une onomatopée ou un surnom interne ; à confirmer en écoutant le clip. |
| `a0107` | 尴尬 | Gêne | Son d'embarras. |
| `a0108` | 叮铃铃 | Dring-dring | Sonnette aiguë. |
| `a0112` | 恩？ | Hein ? | Interjection « hein ? » / « hm ? ». |
| `a0113` | 咬碎 | Mastication | Bruit de quelque chose qu'on croque. |
| `a0114` | 敲鼓 | Tambour | Coup de tambour. |
| `a0115` | 得意 | Fierté | Air satisfait, un peu fanfaron. |
| `a0117` | 和107一样 | Identique à a0107 | Même clip que la gêne a0107, d'après le nom officiel. |
| `a0118` | 按喇叭 | Klaxon | Coup de klaxon. |
| `a0120` | 蝉鸣 | Cigale | Chant de cigale. |
| `a0121` | 门铃 | Sonnette | Sonnette de porte. |
| `a0124` | 咕嘟咕嘟 | Glouglou | Liquide qui s'écoule ou qu'on boit. |
| `a0125` | 打字 | Frappe au clavier | Bruit de clavier. |
| `a0126` | 新消息 | Nouveau message | Notification de message. |
| `a0127` | 新消息2 | Nouveau message 2 | Variante de notification. |
| `a0128` | 新消息3 | Nouveau message 3 | Variante de notification. |
| `a0132` | 电话声2 | Sonnerie de téléphone 2 | Sonnerie (la variante 1 n'est pas dans cette liste). |
| `a0133` | 失落 | Déception | Son déçu. Plus abattu que a0086 难过. |

## Traductions incertaines

| Tag | Nom chinois | Traduction | Usage |
|-----|-------------|------------|-------|
| `a0058` | 主跳转 | Navigation principale ⚠️ traduction incertaine | Sans doute un jingle de changement d'écran principal. |
| `a0067` | 啊呜 | Ahou / miam ⚠️ traduction incertaine | Onomatopée (morsure ou cri « ahou »). |
| `a0071` | 和51重复 | Doublon de a0051 ⚠️ traduction incertaine | Le nom dit « même clip que 51 », mais a0051 n'est pas dans cette liste. |
| `a0089` | 啾~~ | Tchiou ⚠️ traduction incertaine | Onomatopée légère (baiser ou oiseau). |
| `a0106` | 打乌拉 | « Da wula » ⚠️ traduction incertaine | Nom opaque. Peut-être une onomatopée ou un surnom interne ; à confirmer en écoutant le clip. |
