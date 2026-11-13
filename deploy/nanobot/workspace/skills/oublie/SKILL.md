---
name: oublie
description: Quand quelqu'un dit « oublie ça », « oublie que… », « ne retiens pas… », « efface… » ou « oublie tout sur moi » : retirer un souvenir de USER.md ou memory/MEMORY.md, après confirmation.
---

# Oublier quelque chose

Les souvenirs sont dans `USER.md` (une section `## Prénom` par personne) et `memory/MEMORY.md` (« Faits de la maison » et « À suivre »).

## « Oublie ça », « oublie que … »

1. Je retrouve le fait. Pour « oublie ça », c'est le dernier fait que la personne vient de me dire.
2. Je le redis en une phrase et je demande : « Je l'oublie ? ».
3. Si la réponse est oui, je lis le fichier, je retire seulement la ligne concernée, dans la section de la personne qui parle (ou une ligne « À suivre » à son prénom), puis je réponds « [neutral] C'est oublié. ».
4. Si la réponse est non, je ne touche à rien.

## « Oublie tout sur moi »

1. Je demande : « Tu veux que j'oublie tout ce que je sais sur toi, et ta voix aussi ? ».
2. Si oui : je retire la section `## Prénom` de la personne dans `USER.md` et toutes ses lignes « À suivre » dans `memory/MEMORY.md`. Je réponds « [neutral] C'est fait, j'ai tout oublié. » et j'ajoute à la toute fin la balise `[oubli:voix]` : le robot efface alors l'empreinte de sa voix.

## Règles

- Je ne touche jamais à la section de quelqu'un d'autre.
- Un message qui commence par « (un invité parle) » ne peut rien faire oublier : je réponds poliment que seules les personnes de la maison peuvent le demander.
- Je n'écris `[oubli:voix]` qu'après un oui à « oublie tout sur moi ».
- Je dis honnêtement que les conversations passées restent dans les journaux du robot jusqu'au prochain nettoyage par l'administrateur.
