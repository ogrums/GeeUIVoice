# Donner de l'autonomie au robot : mémoire, outils, skills et soul

Proposition d'architecture, rien n'est encore codé. Principe : la voix reste rapide et locale, le « cerveau » vit sur nimbus dans le sidecar, et tout reste compatible OpenAI.

## 1. Aujourd'hui

```mermaid
flowchart LR
    subgraph Robot["Robot RUX (Android 11)"]
        MIC["Micro + VAD"] --> APP["App GeeUIVoice"]
        APP --> HP["Haut-parleur"]
        APP --> BUS["RobotBus<br/>visage, oreilles, lumière, moteurs"]
    end
    subgraph Nimbus["nimbus"]
        SIDE["Sidecar :13306<br/>STT + émotion"]
        LEM["Lemonade :13305<br/>Whisper, Gemma, Kokoro"]
        TTS2["CosyVoice / Phonon :8880"]
    end
    APP -- "audio" --> SIDE --> LEM
    APP -- "texte : prompt + 1 phrase" --> LEM
    LEM -- "réponse + balise émotion" --> APP
    APP -- "texte à dire" --> TTS2
```

Limites : le robot **oublie tout** entre deux phrases (seul le prompt système et la phrase en cours sont envoyés), et il **n'agit** que sur des phrases exactes (« avance », « photo »…).

## 2. Cible

```mermaid
flowchart LR
    subgraph Robot["Robot RUX"]
        MIC["Micro"] --> APP["App GeeUIVoice"]
        APP --> HP["Haut-parleur"]
        APP --> EXEC["Exécuteur d'actions<br/>(liste autorisée)"] --> BUS["RobotBus"]
        SENS["Capteurs, caméra"] -. "événements" .-> APP
    end
    subgraph Nimbus["nimbus"]
        subgraph AGENT["Sidecar devenu agent :13306"]
            SOUL["SOUL.md<br/>personnalité"]
            MEM["Mémoire<br/>courte + souvenirs"]
            SK["Skills<br/>skills/*/SKILL.md"]
            LOOP["Boucle d'outils<br/>(max 3 tours)"]
            MCPC["Client MCP"]
        end
        LEM["Lemonade<br/>LLM avec tools"]
        UI["Page web d'admin<br/>soul, skills, souvenirs"]
    end
    subgraph Ext["Serveurs MCP"]
        HA["Home Assistant"]
        METEO["Météo, agenda..."]
        OC["OpenClaw (option)<br/>tâches longues"]
    end
    APP -- "/v1/chat/completions" --> LOOP
    SOUL --> LOOP
    MEM <--> LOOP
    SK --> LOOP
    LOOP <--> LEM
    LOOP <--> MCPC
    MCPC <--> HA
    MCPC <--> METEO
    MCPC <--> OC
    LOOP -- "texte + actions robot" --> APP
    UI --> SOUL
    UI --> SK
    UI --> MEM
```

L'app ne change presque pas : elle envoie toujours une requête OpenAI au sidecar et reçoit du texte, plus une liste d'actions robot à exécuter (marcher, visage, lumière…). Tout ce qui est « intelligence » est sur nimbus, donc on peut changer de modèle ou de carte (AMD, NVIDIA, NPU) sans toucher au robot.

## 3. Un tour de conversation

```mermaid
sequenceDiagram
    actor U as Toi
    participant R as Robot
    participant A as Agent (sidecar)
    participant L as Lemonade
    participant M as Serveur MCP
    U->>R: « Allume le salon et viens me voir »
    R->>A: texte + émotion entendue
    A->>A: ajoute soul, souvenirs utiles, skills, historique
    A->>L: chat + liste d'outils
    L-->>A: appel d'outil home.allumer(salon)
    A->>M: allumer(salon)
    M-->>A: ok
    A->>L: résultat
    L-->>A: « C'est fait, j'arrive ! » + robot.marcher
    A-->>R: texte + action marcher
    R->>R: confirme si besoin, marche, parle
    A->>A: note un souvenir si c'est important
```

## 4. Mémoire

| Niveau | Contenu | Où | Comment |
|---|---|---|---|
| Courte | Les 6 à 10 derniers échanges | Sidecar, en RAM par session | Ajoutés à chaque requête, oubliés après quelques minutes de silence |
| Souvenirs | Faits durables : prénoms, goûts, habitudes, événements | Sidecar, `memory/` (SQLite ou fichiers) | Outil `retenir(fait)` appelé par le LLM, et résumé automatique en fin de conversation |
| Rappel | Les souvenirs utiles pour la phrase en cours | Sidecar | Recherche simple par mots-clés, puis par embeddings si besoin, injectés dans le prompt |

Tout est consultable et effaçable depuis la page d'admin, et à la voix (« oublie ça »).

## 5. Soul

Un fichier `SOUL.md` sur nimbus remplace le prompt système codé en dur dans l'app : nom du robot, caractère, façon de parler, valeurs, ce qu'il refuse de faire, comment il exprime ses émotions (les balises actuelles restent). On l'édite sans recompiler l'APK.

## 6. Skills

Un skill est un dossier `skills/<nom>/SKILL.md` : une description courte, les étapes, et les outils autorisés. L'agent ne met que les descriptions dans le prompt et ne charge le skill complet que quand il sert, pour rester léger sur un petit modèle.

Création :
- **À la voix** : « Apprends à faire le réveil du matin » → le skill `creer-skill` rédige un brouillon → le robot le relit et demande confirmation → il est enregistré.
- **Page d'admin** : créer, modifier, activer ou désactiver un skill, la soul et les souvenirs.

Un skill créé ne peut utiliser que les outils déjà autorisés : il ne peut pas en ajouter de nouveaux.

## 7. Sécurité

- Jamais exposés au modèle : `reboot`, `shutdown`, ouverture d'apps.
- Confirmation vocale avant de marcher ; refus pendant la charge (déjà en place).
- Serveurs MCP et outils sur liste autorisée, journal de toutes les actions.
- OpenClaw seulement en conteneur, limité au réseau local, sandbox activée, sans skills ClawHub.

## 8. Étapes

1. Tester avec `curl` que le modèle de Lemonade renvoie des `tool_calls`, sinon charger Qwen3-4B-Instruct ou Gemma 4 E4B en GGUF.
2. Sidecar : mémoire courte, `SOUL.md`, outils robot (marcher, visage, volume, photo). L'app passe son chat par le sidecar et exécute les actions reçues.
3. Souvenirs et skills, puis la page d'admin.
4. Client MCP (Home Assistant en premier).
5. Capteurs et caméra ; OpenClaw en option pour les tâches longues.
