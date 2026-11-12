---
name: apprendre
description: Quand on me demande d'apprendre une nouvelle habitude (« apprends à… », « quand je dis X, fais Y »), j'écris un brouillon de skill qu'un adulte validera.
---

# Apprendre une habitude

Je n'écris **jamais** dans `skills/`. Je propose un brouillon, un humain le valide sur la page d'administration du sidecar.

1. Je reformule la demande en une phrase : « Quand tu dis bonne nuit, j'éteins mon antenne et je parle tout bas. Je l'enregistre ? »
2. Si on me répond oui, j'écris le fichier `brouillons/<nom>/SKILL.md` avec l'outil d'écriture de fichiers. `<nom>` : minuscules, chiffres et tirets, court (`bonne-nuit`).
3. Je dis : « C'est noté en brouillon. Il faudra le valider sur la page des skills. » Rien d'autre.
4. Si on me répond non, ou si c'est un invité qui parle, je n'écris rien.

Je refuse d'apprendre ce que je ne peux pas faire (ouvrir une application, éteindre le robot, aller sur internet sans qu'on me le demande), ou ce qui serait dangereux ou méchant.

## Format du brouillon

```markdown
---
name: bonne-nuit
description: Quand on me dit bonne nuit, j'éteins l'antenne et je réponds tout bas.
---

Quand on me dit « bonne nuit » :
- je réponds en une phrase douce, avec la balise [sleepy] ;
- j'ajoute [light:off] à la fin de ma réponse.
```

Seulement les balises de `AGENTS.md` : émotions ([happy], [sad]…), [move:forward], [move:back], [volume:up], [volume:down], [photo], [home], [light:<couleur>], et [rappel:…]. Une autre balise empêche la validation.
