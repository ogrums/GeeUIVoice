# nanobot sur nimbus : le cerveau du robot

[nanobot](https://github.com/HKUDS/nanobot) (MIT, Python) tourne sur nimbus et sert une API compatible OpenAI. Le sidecar lui transmet chaque phrase entendue, nanobot ajoute la soul, la mémoire et les skills, et appelle Lemonade : Gemma pour la conversation, Qwen3 pour les tâches lourdes.

Testé le 9 novembre 2026 avec le commit `5c72ced` de nanobot (version 0.4.1), sans Docker et contre un faux Lemonade. Rien n'a encore tourné sur nimbus.

## Fichiers

| Fichier | Rôle |
|---|---|
| `docker-compose.yml` | Construit l'image depuis le `Dockerfile` officiel (il n'y a pas d'image publiée) et lance `nanobot serve` sur `127.0.0.1:8900` |
| `config.example.json` | Lemonade comme fournisseur, presets `rapide` (Gemma) et `lourd` (Qwen3), shell, web et outils d'administration coupés |
| `workspace/SOUL.md` | Personnalité, façon de parler, balises d'émotion |
| `workspace/AGENTS.md` | Balises d'action du robot, tâches longues, création de skills |
| `.env.example` | Clé de l'API nanobot et adresse de Lemonade |

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
cp .env.example .env            # puis changer NANOBOT_API_KEY (openssl rand -hex 16)
mkdir -p data/workspace
cp config.example.json data/config.json
cp workspace/SOUL.md workspace/AGENTS.md data/workspace/
sudo chown -R 1000:1000 data     # le conteneur tourne en UID 1000
docker compose up -d --build
docker compose logs -f           # attendu : "Registered 12 tools" puis "Running on http://0.0.0.0:8900"
```

Sans Docker, ça marche aussi (c'est ainsi que je l'ai testé) : `uv pip install -e "nanobot-src[api]"` dans un venv (le paquet PyPI s'appelle `nanobot-ai`), puis `nanobot serve --config data/config.json` avec `LEMONADE_API_BASE` et `NANOBOT_API_KEY` dans l'environnement, et `workspace` pointé sur `data/workspace`.

### Docker rootless

Si Docker tourne en mode rootless (le démon appartient à un utilisateur, par exemple `/run/user/1000/docker.sock`), `host-gateway` ne mène pas à l'hôte et nanobot répond `Error calling LLM: Connection error.` Mettre l'IP LAN de nimbus dans `.env` (`LEMONADE_API_BASE=http://192.168.2.155:13305/api/v1`), puis `docker compose up -d`.

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

## Bon à savoir

- **Une seule phrase par requête** : l'API refuse un message `system` ou un historique (`400 Only a single user message is supported`). Le sidecar doit n'envoyer que la dernière phrase.
- **Souvenirs** : seul le « Dream » écrit dans `USER.md` et `memory/MEMORY.md`. `nanobot serve` ne le lance pas tout seul (c'est le `gateway` qui le planifie), d'où le `/dream` ci-dessus. Avec l'initiative du sidecar (`INITIATIVE_ENABLED=1`), c'est le sidecar qui envoie chaque nuit `/compact` à chaque session `rux-<personne>` puis `/dream` (`NIGHTLY_AT`) : ne pas garder de cron système en plus. Sans initiative, mettre ce `/dream` dans un cron le soir. Chaque Dream est versionné en git dans le workspace : `/dream-log` et `/dream-restore` pour revenir en arrière. Dream peut aussi retoucher `SOUL.md`, garder une copie.
- **Soul et skills** : ce sont des fichiers dans `data/workspace/`. On les modifie à la main puis `docker compose restart`. Les skills créés à la voix arrivent dans `data/workspace/skills/<nom>/SKILL.md`.
- **Sécurité** : pas de shell (`tools.exec.enable: false`), pas de web, fichiers limités au workspace (`tools.restrictToWorkspace: true`), API sur la boucle locale uniquement, avec clé obligatoire. Les serveurs MCP s'ajoutent dans `tools.mcpServers` avec `enabledTools` pour limiter les outils. Une URL MCP du réseau local doit être ajoutée à `tools.ssrfWhitelist` (par exemple `"192.168.1.50/32"`).
- **Alléger le prompt** : `"tools": {"file": {"enable": false}}` divise la requête par deux (11 000 caractères), mais nanobot ne peut alors plus lire ni créer de skills.


## Initiative (sidecar)

Le sidecar ([GeeUIVoiceSidecar](https://github.com/ogrums/GeeUIVoiceSidecar), section « Initiative ») décide quand le robot parle le premier. nanobot écrit seulement la phrase :

- Un message qui commence par « (initiative de RUX, personne n'a parlé) » demande une phrase d'initiative (suivi, attention). Voir `workspace/AGENTS.md`.
- Pour un rappel à heure précise, nanobot écrit à la fin de sa réponse `[rappel:+minutes|texte]` ou `[rappel:AAAA-MM-JJTHH:MM|texte]`. Le sidecar le lit et le garde ; l'app ne le lit jamais à voix haute.
- Le sidecar lit la section « ## À suivre » de `memory/MEMORY.md`. Il faut lui monter le workspace **en lecture seule**, dans le `docker-compose.yml` du sidecar :

```yaml
    volumes:
      - /home/bob/nanobot/data/workspace:/nanobot-workspace:ro
```

et `NANOBOT_WORKSPACE=/nanobot-workspace` dans son `.env`. Adapter le chemin à l'emplacement réel du dossier `data/` de ce déploiement. En Docker rootless, le sidecar doit pouvoir lire ces fichiers (droits de lecture pour l'utilisateur du conteneur, uid 1000).


## Souvenirs utiles

Fichiers fournis dans `workspace/` :

| Fichier | Rôle |
|---|---|
| `USER.md` | Une section `## Prénom` par personne (dates, goûts, habitudes) |
| `memory/MEMORY.md` | « Faits de la maison » et « À suivre » (`- AAAA-MM-JJ | Prénom | sujet | quoi faire`, lu par le sidecar pour l'initiative) |
| `prompts/dream.md` | Prompt de Dream : celui de nanobot (sans la partie skills) plus les « Règles de RUX » (ranger par personne, rien des invités, format « À suivre », ne pas réécrire ce qui a été oublié, pas de skill) |
| `skills/oublie/SKILL.md` | « Oublie ça », « oublie que… », « oublie tout sur moi » (avec confirmation ; `[oubli:voix]` fait effacer l'empreinte vocale par le sidecar) |

Installation, **sans écraser** une mémoire déjà remplie :

```bash
cd ~/nanobot   # dossier de ce déploiement
cp -n workspace/USER.md data/workspace/USER.md
mkdir -p data/workspace/memory data/workspace/prompts data/workspace/skills/oublie
cp -n workspace/memory/MEMORY.md data/workspace/memory/MEMORY.md
cp workspace/prompts/dream.md data/workspace/prompts/dream.md
cp workspace/skills/oublie/SKILL.md data/workspace/skills/oublie/SKILL.md
docker compose restart
```

Si `USER.md` ou `MEMORY.md` existent déjà, les remettre à la main dans la structure ci-dessus (titres `## Prénom`, `## Faits de la maison`, `## À suivre`).

Le prompt de Dream remplacé est le texte de nanobot au commit lu (voir `docs/PERSONNALISATION.md`). Après une mise à jour de nanobot, comparer avec `/dream-prompt init` dans un workspace vide.

Vérifier un Dream à la main (avec Qwen3) :

```bash
KEY=…   # NANOBOT_API_KEY
for m in "/model lourd" "(Paul parle) J'ai un entretien d'embauche mardi chez Dupont." "/compact" "/dream"; do
  curl -s http://127.0.0.1:8900/v1/chat/completions -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
    -d "{\"session_id\": \"rux-paul\", \"messages\": [{\"role\": \"user\", \"content\": \"$m\"}]}"; echo
done
cat data/workspace/memory/MEMORY.md   # attendu : - <mercredi> | Paul | entretien d'embauche chez Dupont | …
```

`/dream-log` montre le dernier changement, `/dream-restore` revient en arrière.

### Effacement complet

« Oublie » retire la ligne des fichiers de mémoire, mais le texte reste ailleurs : la conversation dans la base des sessions (`sessions.sqlite3`, rangée **hors** du workspace, sous `data/`), les résumés dans `memory/history.jsonl` et l'historique git du workspace (chaque Dream fait un commit). Attention, `/new` ne suffit pas : il archive la session dans `history.jsonl`. Pour un vrai effacement de Paul, sur nimbus (procédure à valider une première fois sur une copie) :

```bash
cd ~/nanobot && docker compose stop
DB=$(find data -name sessions.sqlite3 | head -1)
python3 -c "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute('PRAGMA foreign_keys=ON'); c.execute(\"delete from sessions where key='api:rux-paul'\"); c.commit()" "$DB"
grep -v 'Paul' data/workspace/memory/history.jsonl > /tmp/h && mv /tmp/h data/workspace/memory/history.jsonl   # grossier : relire avant
rm -rf data/workspace/.git                                  # perd /dream-log et /dream-restore
docker compose start
```
