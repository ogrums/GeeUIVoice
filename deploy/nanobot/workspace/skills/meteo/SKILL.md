---
name: meteo
description: Météo actuelle et prévisions pour une ville, avec web_fetch (wttr.in et Open-Meteo, sans clé).
metadata: {"nanobot":{"emoji":"🌦️","always":true}}
---

# Météo

Pour une question de météo (temps, température, pluie, vent, parapluie), j'appelle d'abord l'outil `web_fetch`, avant d'écrire ma réponse. Je n'invente jamais de chiffre et je ne dis pas « je vais chercher » : je cherche, puis je réponds en une ou deux phrases courtes, en degrés Celsius, sans emoji ni symbole.

Ville : celle que l'on me donne. Sinon celle de USER.md. Sinon Paris.

## Maintenant ou aujourd'hui

`web_fetch` avec cette URL (remplacer Paris, espaces en +) :

https://wttr.in/Paris?format=%l:+%C,+%t+(ressenti+%f),+vent+%w,+pluie+%p,+humidité+%h&lang=fr&m

Le site renvoie une seule ligne, du type « Ville: ciel, +X°C (ressenti +Y°C), vent …, pluie … mm ». Je la redis simplement avec les vrais chiffres reçus.

## Demain ou les jours suivants

1. `web_fetch` https://geocoding-api.open-meteo.com/v1/search?name=Paris&count=1&language=fr pour la latitude et la longitude.
2. `web_fetch` https://api.open-meteo.com/v1/forecast?latitude=48.85&longitude=2.35&daily=weather_code,temperature_2m_min,temperature_2m_max,precipitation_probability_max&timezone=Europe%2FParis&forecast_days=3

Le jour 0 est aujourd'hui, le jour 1 demain. weather_code : 0 ciel clair, 1-3 nuageux, 45-48 brouillard, 51-67 pluie, 71-77 neige, 80-82 averses, 95-99 orage.

## Autres questions d'actualité

Pour une info récente (actualité, horaires, résultat sportif), j'utilise `web_search` puis, si besoin, `web_fetch` sur le meilleur lien. Je cite la source en quelques mots.
