#!/bin/bash
# À copier et lancer sur nimbus (grokbot), après SSH depuis RoadRunner.
#   ssh -i "$HOME/.ssh/grokbot_nimbus" -o BatchMode=yes -o IdentitiesOnly=yes grokbot@nimbus
#
# Ce script :
#   1. inventorie les STT déjà là (lecture seule, pas de docker stop, pas de /load)
#   2. optionnellement installe des candidats temporaires sur 13310+
#   3. lance le banc
#   4. avec --clean, arrête ces candidats et peut effacer ./models
#
# Il ne touche PAS à pockettts, kyutai, au nanobot de bob, ni aux ports 8080,
# 8880, 8900, 13305, 13306, 49112.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
OUT="${OUT:-$DIR/out}"
mkdir -p "$OUT"
CLEAN=0
PROFILES=""
MANIFEST="${MANIFEST:-$DIR/manifest.json}"
while [ $# -gt 0 ]; do
  case "$1" in
    --clean) CLEAN=1; shift ;;
    --profile) PROFILES="$PROFILES $2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    *) echo "option inconnue: $1" >&2; exit 2 ;;
  esac
done

echo "== disque =="
df -h . || true
echo "== inventaire (lecture seule) =="
python3 sttbench.py inventory --out "$OUT/inventory.json" --timeout 8

if [ -n "$PROFILES" ]; then
  echo "== candidats temporaires :$PROFILES =="
  # shellcheck disable=SC2086
  docker compose -f docker-compose.candidates.yml $(for p in $PROFILES; do echo --profile $p; done) up -d --build
fi

if [ -f "$MANIFEST" ]; then
  echo "== banc =="
  python3 sttbench.py run --manifest "$MANIFEST" --out "$OUT/campaign.json" \
    --note "nimbus $(hostname) $(date -Iseconds)"
else
  echo "pas de manifest ($MANIFEST) — lancer d'abord : python3 sttbench.py prepare ..."
fi

if [ "$CLEAN" -eq 1 ]; then
  echo "== nettoyage candidats temporaires =="
  docker compose -f docker-compose.candidates.yml --profile vosk --profile fw-small --profile parakeet down || true
  echo "les modèles dans $DIR/models sont conservés (passer rm -rf models pour libérer le disque)"
fi
echo "terminé. rapports dans $OUT"
