# nanobot sur nimbus : le cerveau du robot

[nanobot](https://github.com/HKUDS/nanobot) (MIT, Python) tourne sur nimbus et sert une API compatible OpenAI. Le sidecar lui transmet chaque phrase entendue, nanobot ajoute la soul, la mémoire et les skills, et appelle Lemonade : Gemma pour la conversation, Qwen3 pour les tâches lourdes.

Testé le 9 novembre 2026 avec le commit `5c72ced` de nanobot (version 0.4.1), sans Docker et contre un faux Lemonade. Rien n'a encore tourné sur nimbus.

## Fichiers

| Fichier | Rôle |
|---|---|
| `docker-compose.yml` | Construit l'image depuis le `Dockerfile` officiel (il n'y a pas d'image publiée) et lance deux services : `nanobot serve` sur `127.0.0.1:8900` (API pour le sidecar) et `nanobot gateway` (WebUI sur le port 8765, santé sur `127.0.0.1:18790`) |
| `config.example.json` | Lemonade comme fournisseur, presets `rapide` (Gemma) et `lourd` (Qwen3), web activé (DuckDuckGo sans clé + `web_fetch`), canal WebSocket de la WebUI, shell et outils d'administration coupés |
| `workspace/SOUL.md` | Personnalité, façon de parler, balises d'émotion |
| `workspace/AGENTS.md` | Balises d'action du robot, tâches longues, création de skills |
| `.env.example` | Clé de l'API nanobot, mot de passe de la WebUI (`NANOBOT_WEB_TOKEN`), adresse d'écoute (`NANOBOT_WEBUI_BIND`) et adresse de Lemonade |

## 1. Lemonade : deux modèles chargés et appels d'outils

Garder Gemma et Qwen3 chargés en même temps (sinon Lemonade décharge l'un pour charger l'autre) :

```bash
lemonade config set max_loaded_models=2
lemonade pull Qwen3-4B-Instruct-2507-GGUF
```

Vérifier qu'un modèle renvoie bien un `tool_calls` (à lancer pour les deux modèles) :

```bash
for M in gemma4-it-e2b-FLM Qwen3-4B-Instruct-2507-GGUF; do
  echo "== $M"
  curl -s http://nimbus:13305/api/v1/chat/completions -H "Content-Type: application/json" -d '{
    "model": "'"$M"'",
    "messages": [{"role": "user", "content": "Allume la lumière du salon"}],
    "tools": [{"type": "function", "function": {
      "name": "allumer", "description": "Allume une pièce",
      "parameters": {"type": "object", "properties": {"piece": {"type": "string"}}, "required": ["piece"]}}}]
  }' | python3 -c 'import json,sys; m=json.load(sys.stdin)["choices"][0]["message"]; print(m.get("tool_calls") or m.get("content"))'
done
```

Attendu : `[{... "name": "allumer", "arguments": "{\"piece\": \"salon\"}"}]`. nanobot envoie toujours ses requêtes en streaming : refaire le test avec `"stream": true` et vérifier que des `delta.tool_calls` arrivent. Mesuré sur nimbus le 11 novembre 2026 (modèle déjà chargé) :

| Modèle | Appel d'outil | Temps |
|---|---|---|
| `gemma4-it-e2b-FLM` | oui, aussi en streaming | 1,8 s |
| `gemma4e-flash-e2b-FLM` | non, répond en texte qu'il ne peut pas | 1,4 s |
| `Qwen3-4B-Instruct-2507-GGUF` | oui | 0,7 s |

Le preset `rapide` utilise donc `gemma4-it-e2b-FLM`.

## 2. Installer et lancer nanobot

```bash
cd deploy/nanobot
git clone https://github.com/HKUDS/nanobot nanobot-src && git -C nanobot-src checkout 5c72ced
cp .env.example .env
# changer NANOBOT_API_KEY et NANOBOT_WEB_TOKEN (openssl rand -hex 16 pour chacun)
mkdir -p data/workspace
cp config.example.json data/config.json
cp workspace/SOUL.md workspace/AGENTS.md data/workspace/
sudo chown -R 1000:1000 data     # le conteneur tourne en UID 1000
docker compose up -d --build
docker compose logs -f nanobot-rux           # attendu : "Registered 12 tools" puis "Running on http://0.0.0.0:8900"
docker compose logs -f nanobot-rux-gateway   # attendu : le gateway écoute, WebUI sur 8765
```

Sans Docker, ça marche aussi (c'est ainsi que je l'ai testé) : `uv pip install -e "nanobot-src[api]"` dans un venv (le paquet PyPI s'appelle `nanobot-ai`), puis `nanobot serve --config data/config.json` avec `LEMONADE_API_BASE` et `NANOBOT_API_KEY` dans l'environnement, et `workspace` pointé sur `data/workspace`. Pour la WebUI, lancer aussi `nanobot gateway --config data/config.json` avec `NANOBOT_WEB_TOKEN` dans l'environnement.

### Docker rootless (nimbus)

Si Docker tourne en mode rootless (le démon appartient à un utilisateur, par exemple `/run/user/1000/docker.sock`), `host-gateway` ne mène pas à l'hôte et nanobot répond `Error calling LLM: Connection error.` Mettre l'IP LAN de nimbus dans `.env` (`LEMONADE_API_BASE=http://192.168.2.155:13305/api/v1`), puis `docker compose up -d`.

Le déploiement sur nimbus est le Docker rootless de bob. Après un `git pull` de cette branche (ou de `main` une fois fusionnée) :

```bash
cd ~/GeeUIVoice/deploy/nanobot
git pull
# Ajouter dans .env, s'ils n'y sont pas déjà (ne pas committer .env) :
#   NANOBOT_WEB_TOKEN=$(openssl rand -hex 16)   # mot de passe de la page http://nimbus:8765
#   NANOBOT_WEBUI_BIND=0.0.0.0                  # déjà la valeur par défaut ; 127.0.0.1 pour rester en local
# Fusionner gateway + channels.websocket dans data/config.json s'ils manquent
# (l'installation existante a déjà ce fichier : ne pas l'écraser avec l'exemple) :
cp -a data/config.json data/config.json.bak
python3 - <<'NBCONFIG'
import json
from pathlib import Path
p = Path("data/config.json")
cfg = json.loads(p.read_text())
cfg.setdefault("gateway", {})
cfg["gateway"]["host"] = "0.0.0.0"
cfg["gateway"]["port"] = 18790
ws = cfg.setdefault("channels", {}).setdefault("websocket", {})
ws.update({
    "enabled": True,
    "host": "0.0.0.0",
    "port": 8765,
    "tokenIssueSecret": "${NANOBOT_WEB_TOKEN}",
    "websocketRequiresToken": True,
})
p.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + "\n")
print("config mise à jour")
NBCONFIG
docker compose up -d --build
docker compose ps
docker compose logs -f nanobot-rux-gateway
```

Ouvrir ensuite `http://nimbus:8765` depuis le réseau local et saisir `NANOBOT_WEB_TOKEN` dans le formulaire de connexion. L'API du sidecar reste sur `127.0.0.1:8900`. Pour vérifier la santé du gateway depuis nimbus : `curl -sS http://127.0.0.1:18790/health`.

Pour n'écouter la WebUI qu'en local : `NANOBOT_WEBUI_BIND=127.0.0.1` dans `.env`, puis `docker compose up -d`, et un tunnel `ssh -L 8765:127.0.0.1:8765 bob@nimbus`.

### Ce que la WebUI expose, et l'authentification

Le processus `nanobot gateway` sert, sur le port 8765 :

- l'interface web (sujets, mémoire, skills, réglages, automations) ;
- le canal WebSocket de cette interface ;
- l'émission de jetons courts sur `/auth/token`.

Le port 18790 est uniquement le `/health` du gateway : il n'est pas authentifié, d'où l'écoute limitée à `127.0.0.1` sur l'hôte. Ce n'est pas l'interface.

L'authentification reprend le mécanisme officiel : `channels.websocket.tokenIssueSecret` vaut `${NANOBOT_WEB_TOKEN}` (variable lue au démarrage, jamais écrite en clair dans `config.json`). Avec `host: "0.0.0.0"`, le gateway refuse de démarrer sans ce secret (ou un `token` statique). La page demande ce mot de passe ; elle s'en sert pour obtenir un jeton de session à usage unique. Tous les utilisateurs connectés voient les mêmes sujets et les mêmes réglages d'administration : ne partager le mot de passe qu'avec des personnes de confiance. Le micro du navigateur ne fonctionne en HTTP que sur `localhost` ; depuis un autre poste, il faudrait du HTTPS (non configuré ici).

L'image officielle plus récente a retiré `nanobot serve`. Ce déploiement le conserve, parce que le sidecar du robot en a besoin. Rester sur le commit testé (`5c72ced`) ou un équivalent qui fournit encore `serve`.

### Gateway et API : même fichier, même workspace

Les deux conteneurs montent `./data` sur `/home/nanobot/.nanobot` et lisent le même `config.json`, avec le même workspace (`/home/nanobot/.nanobot/workspace`). C'est voulu : la WebUI permet d'administrer le même agent que le robot (SOUL, souvenirs, skills).

Le compose officiel de nanobot (commit `5c72ced`) isolait au contraire l'API sur `-w /home/nanobot/.nanobot/api-workspace`, « pour éviter les conflits de sessions et de mémoire » avec le gateway (PR amont #2841). Les verrous de session sont internes à chaque processus, pas partagés entre `serve` et `gateway`. En partageant le workspace :

- les conversations de l'API (`api:rux`, `api:rux-paul`, …) et celles de la WebUI n'ont en principe pas le même identifiant de session, donc leurs historiques SQLite (sous `data/sessions/`, hors du workspace) ne devraient pas s'écrire en même temps sur la même clé ;
- en revanche, `USER.md`, `memory/MEMORY.md` et `SOUL.md` peuvent être lus ou écrits des deux côtés. Le Dream, le heartbeat et la compaction des conversations inactives sont lancés par le **gateway** (ce que `serve` ne fait pas tout seul). Éviter d'ouvrir dans la WebUI la session du robot pendant qu'il parle, et de modifier les mêmes fichiers mémoire des deux côtés en même temps ;
- un seul `gateway` par workspace : ne pas en lancer un second sur les mêmes fichiers.

On n'active pas `NANOBOT_DOCKER_BOOTSTRAP` : le `--config` explicite saute l'initialisation amont, ce qui préserve le `data/config.json` déjà en service.

## 3. Tester l'API nanobot

```bash
source .env
curl -s http://127.0.0.1:8900/health
curl -s http://127.0.0.1:8900/v1/models -H "Authorization: Bearer $NANOBOT_API_KEY"

# Une phrase. Un seul message "user" par requête : nanobot garde lui-même l'historique de la session.
curl -s http://127.0.0.1:8900/v1/chat/completions \
  -H "Authorization: Bearer $NANOBOT_API_KEY" -H "Content-Type: application/json" \
  -d '{"session_id": "rux", "messages": [{"role": "user", "content": "Salut, je m appelle Olivier"}]}'

# Il doit se souvenir du prénom dans la même session
curl -s http://127.0.0.1:8900/v1/chat/completions \
  -H "Authorization: Bearer $NANOBOT_API_KEY" -H "Content-Type: application/json" \
  -d '{"session_id": "rux", "messages": [{"role": "user", "content": "Comment je m appelle ?"}]}'

# Streaming, comme l'app
curl -sN http://127.0.0.1:8900/v1/chat/completions \
  -H "Authorization: Bearer $NANOBOT_API_KEY" -H "Content-Type: application/json" \
  -d '{"session_id": "rux", "stream": true, "messages": [{"role": "user", "content": "Avance un peu"}]}'
# Attendu : "[happy] ... [move:forward]" puis "data: [DONE]". Un flux sans [DONE] est une erreur.

# Passer la session sur Qwen3, puis revenir sur Gemma
for c in "/model lourd" "Prépare-moi un plan pour ranger le bureau" "/model rapide"; do
  curl -s http://127.0.0.1:8900/v1/chat/completions \
    -H "Authorization: Bearer $NANOBOT_API_KEY" -H "Content-Type: application/json" \
    -d "{\"session_id\": \"rux\", \"messages\": [{\"role\": \"user\", \"content\": \"$c\"}]}"; echo
done

# Mettre à jour les souvenirs (USER.md, memory/MEMORY.md) avec Qwen3
curl -s http://127.0.0.1:8900/v1/chat/completions \
  -H "Authorization: Bearer $NANOBOT_API_KEY" -H "Content-Type: application/json" \
  -d '{"session_id": "rux", "messages": [{"role": "user", "content": "/dream"}]}'
```

Mesurer la latence avec `curl -w "%{time_total}\n" -o /dev/null ...` : nanobot envoie environ 20 000 caractères (5 000 tokens) à chaque tour, dont 15 000 pour la description des outils.



## 4. Brancher le sidecar (`emo_server.py`)

Quand `NANOBOT_URL` est défini, le sidecar envoie chaque tour de chat à nanobot au lieu de Lemonade (sinon comportement inchangé).

| Variable | Défaut | Rôle |
|---|---|---|
| `NANOBOT_URL` | *(vide)* | Base OpenAI de nanobot, ex. `http://127.0.0.1:8900/v1`. Vide = désactivé |
| `NANOBOT_API_KEY` | *(vide)* | Bearer obligatoire côté nanobot |
| `NANOBOT_SESSION` | `rux` | `session_id` envoyé à chaque requête |
| `NANOBOT_FAST` | `rapide` | Preset Gemma après une tâche lourde |
| `NANOBOT_HEAVY` | `lourd` | Preset Qwen3 pour les demandes longues / mots-clés |
| `NANOBOT_HEAVY_WORDS` | explique,résume,… | Mots (FR) qui déclenchent le preset lourd |
| `NANOBOT_HEAVY_CHARS` | `200` | Au-delà, la phrase est traitée comme lourde |
| `NANOBOT_TIMEOUT` | `120` | Délai max (s) pour un tour nanobot |

Exemple (nimbus, sidecar déjà lancé sur 13306) :

```bash
export NANOBOT_URL=http://127.0.0.1:8900/v1
export NANOBOT_API_KEY=...   # même clé que nanobot
# redémarrer le sidecar pour prendre l'env

# Via le sidecar (comme l'app le fera) : une phrase simple
curl -s http://127.0.0.1:13306/v1/chat/completions   -H "Content-Type: application/json"   -d '{"messages":[{"role":"user","content":"Salut"}],"stream":false}'

# Streaming
curl -sN http://127.0.0.1:13306/v1/chat/completions   -H "Content-Type: application/json"   -d '{"messages":[{"role":"user","content":"Avance un peu"}],"stream":true}'
# Attendu : flux SSE puis data: [DONE]. Logs sidecar : chat route=nanobot ...

# Demande lourde (le sidecar envoie /model lourd, la phrase, /model rapide)
curl -s http://127.0.0.1:13306/v1/chat/completions   -H "Content-Type: application/json"   -d '{"messages":[{"role":"user","content":"explique-moi la photosynthèse"}]}'
```

Si nanobot est arrêté, le sidecar journalise `chat route=lemonade fallback` et renvoie la requête d'origine à Lemonade.

## La maison (Home Assistant via MCP)

Home Assistant est un serveur MCP (intégration « Model Context Protocol », depuis 2025.2). nanobot s'y connecte en client MCP HTTP (`streamableHttp`) avec un jeton d'un utilisateur **non administrateur**. Le jeton est dans `ha.env`, jamais dans `config.json` ni dans git.

Garde-fous, du plus sûr au moins sûr :

1. **Côté Home Assistant** : n'exposer à Assist (Paramètres > Assistants vocaux > Exposer) que les lumières, prises et capteurs. Pas de serrure, d'alarme, de portail. Ce qui n'est pas exposé n'existe pas pour RUX.
2. **`enabledTools`** : liste blanche des outils MCP. On commence en lecture seule.
3. **`AGENTS.md` « La maison »** : confirmation à la voix pour ce qui ouvre, chauffe ou coupe ; jamais d'alarme ni de serrure ; rien pour un invité ou un enfant. Le sidecar envoie déjà les invités vers Lemonade sans outils (`SPEAKER_GUEST_ROUTE=lemonade`, PR « Qui parle »).

### Étape 1 : lecture seule

Dans Home Assistant : ajouter l'intégration « Model Context Protocol », puis **Configurer** : décocher « Expose all AI tools », cocher seulement « Device control », décocher « Require an administrator account », cocher « Read-only ». Créer un utilisateur `rux` (non administrateur), puis dans son profil > Sécurité, un jeton longue durée.

```bash
cd $NB
cp ha.env.example ha.env && chmod 600 ha.env   # renseigner HA_MCP_URL et HA_TOKEN
# Fusionner l'extrait dans la config (corriger l'IP de ssrfWhitelist = IP de Home Assistant) :
jq -s '.[0] * .[1]' data/config.json home-assistant.example.json > /tmp/c.json && mv /tmp/c.json data/config.json
cp workspace/AGENTS.md data/workspace/
docker compose up -d
docker compose logs nanobot-rux | grep -i mcp     # attendu : le serveur « maison » et ses outils
```

`ssrfWhitelist` est obligatoire : nanobot bloque par défaut les URL du réseau local, MCP compris. Attention : une fois l'extrait fusionné, nanobot **refuse de démarrer** si `HA_MCP_URL` ou `HA_TOKEN` manque (`ha.env` absent). Pour couper la maison, retirer `tools.mcpServers.maison` de `data/config.json`.

Vérifier le jeton sans nanobot :

```bash
. ./ha.env
curl -s -X POST "$HA_MCP_URL" -H "Authorization: Bearer $HA_TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"rux","version":"1"}}}'
# attendu : "serverInfo" ; 401 = mauvais jeton ou URL qui exige un administrateur
```

### Étape 2 : les commandes

Quand la lecture marche : `HA_MCP_URL=http://<ha>:8123/api/mcp/assist` (« Device control », jamais en lecture seule, accepte un non-administrateur), et dans `data/config.json` :

```json
"enabledTools": ["GetLiveContext", "GetDateTime", "HassTurnOn", "HassTurnOff", "HassLightSet"]
```

`HassClimateSetTemperature`, `HassSetPosition` (volets) et les autres restent hors liste tant que la confirmation n'a pas été vérifiée à l'usage. Les noms exacts dépendent de la version de Home Assistant : les lire dans les logs nanobot au démarrage (outils `mcp_maison_…`).

## Bon à savoir

- **Une seule phrase par requête** : l'API refuse un message `system` ou un historique (`400 Only a single user message is supported`). Le sidecar doit n'envoyer que la dernière phrase.
- **Souvenirs** : seul le « Dream » écrit dans `USER.md` et `memory/MEMORY.md`. `nanobot serve` ne le lance pas tout seul ; c'est le `gateway` (maintenant lancé à côté) qui le planifie. Le `/dream` manuel ou un cron le soir restent possibles si on veut forcer une passe. Chaque Dream est versionné en git dans le workspace : `/dream-log` et `/dream-restore` pour revenir en arrière. Dream peut aussi retoucher `SOUL.md`, garder une copie.
- **Soul et skills** : ce sont des fichiers dans `data/workspace/`. On les modifie à la main puis `docker compose restart`. Les skills appris à la voix arrivent d'abord en brouillon (section suivante).

## Skills appris à la voix

« Apprends à… quand je dis bonne nuit, éteins l'antenne. » RUX résume, demande « je l'enregistre ? », puis écrit `data/workspace/brouillons/<nom>/SKILL.md` (skill `apprendre`). nanobot ne charge pas `brouillons/`. Le skill intégré `skill-creator` est désactivé (`disabledSkills`), parce qu'il écrit directement dans `skills/`.

Validation sur la page du sidecar : `http://nimbus:13306/admin/skills` (mot de passe `SIDECAR_ADMIN_KEY`, section « Skills appris à la voix » de GeeUIVoiceSidecar). Valider déplace le dossier dans `skills/`, et nanobot le lit au tour suivant. Une balise d'action hors de la liste de `AGENTS.md` bloque la validation.

Installer le skill et la règle :

```bash
cd $NB
cp -r workspace/skills/apprendre data/workspace/skills/
cp workspace/AGENTS.md data/workspace/
mkdir -p data/workspace/brouillons
# ajouter "skill-creator" à agents.defaults.disabledSkills dans data/config.json
docker compose restart
```

Le prompt Dream de RUX (`prompts/dream.md`, PR « Souvenirs ») interdit déjà à Dream de créer des skills.
- **Sécurité** : pas de shell (`tools.exec.enable: false`), pas de web, fichiers limités au workspace (`tools.restrictToWorkspace: true`), API sur la boucle locale uniquement, avec clé obligatoire. La WebUI écoute sur le port 8765 du réseau local, protégée par `NANOBOT_WEB_TOKEN` ; le `/health` du gateway reste sur `127.0.0.1:18790`. Les serveurs MCP s'ajoutent dans `tools.mcpServers` avec `enabledTools` pour limiter les outils. Une URL MCP du réseau local doit être ajoutée à `tools.ssrfWhitelist` (par exemple `"192.168.1.50/32"`).
- **Soul et skills** : ce sont des fichiers dans `data/workspace/`. On les modifie à la main puis `docker compose restart`. Les skills créés à la voix arrivent dans `data/workspace/skills/<nom>/SKILL.md`.
- **Sécurité** : pas de shell (`tools.exec.enable: false`), web en lecture seule (recherche DuckDuckGo + `web_fetch`, voir plus bas), fichiers limités au workspace (`tools.restrictToWorkspace: true`), API sur la boucle locale uniquement, avec clé obligatoire. La WebUI écoute sur le port 8765 du réseau local, protégée par `NANOBOT_WEB_TOKEN` ; le `/health` du gateway reste sur `127.0.0.1:18790`. Les serveurs MCP s'ajoutent dans `tools.mcpServers` avec `enabledTools` pour limiter les outils. Une URL MCP du réseau local doit être ajoutée à `tools.ssrfWhitelist` (par exemple `"192.168.1.50/32"`).
- **Alléger le prompt** : `"tools": {"file": {"enable": false}}` divise la requête par deux (11 000 caractères), mais nanobot ne peut alors plus lire ni créer de skills.


## Qui parle (sidecar)

La reconnaissance de la voix est faite par le sidecar ([GeeUIVoiceSidecar](https://github.com/ogrums/GeeUIVoiceSidecar), section « Qui parle »), pas par nanobot. Ce que nanobot voit :

| Qui | `session_id` | Début du message |
|---|---|---|
| Fonction éteinte, ou personne d'enregistré | `rux` | `(ton entendu : sad)` si besoin |
| Paul reconnu | `rux-paul` | `(Paul parle, ton entendu : sad)` |
| Inconnu | `rux-invite` | `(un invité parle)` |

La mémoire reste une par workspace : `USER.md` a une section par personne (voir `workspace/SOUL.md` et `workspace/AGENTS.md`). Après une mise à jour, recopier ces deux fichiers dans `data/workspace/` puis `docker compose restart`.

Vérifier côté nanobot qu'une session par personne existe :

```bash
# Les sessions sont dans une base SQLite, sous data/ mais hors du workspace.
DB=$(find data -name sessions.sqlite3 | head -1)
python3 -c "import sqlite3,sys; print([r[0] for r in sqlite3.connect(sys.argv[1]).execute('select key from sessions')])" "$DB"
# attendu après une conversation : 'api:rux-paul'
```
