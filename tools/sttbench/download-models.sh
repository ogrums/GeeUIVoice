#!/bin/bash
# Télécharge les modèles *légers* par défaut (Vosk small FR, 41 Mo).
# Les poids Whisper de faster-whisper sont récupérés automatiquement au premier
# lancement (cache Hugging Face). Parakeet est optionnel (PARAKEET=1).
#
# Usage : ./download-models.sh [répertoire]
# Ne rien écrire sur nimbus sans vérifier l'espace (df -h).
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")" && pwd)/models}"
mkdir -p "$ROOT"
echo "modèles → $ROOT"
if [ ! -d "$ROOT/vosk-model-small-fr-0.22" ]; then
  echo "Vosk small FR 0.22 (Apache-2.0, ≈ 41 Mo)…"
  tmp=$(mktemp -d)
  curl -L --fail -o "$tmp/vosk.zip" \
    "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
  unzip -q "$tmp/vosk.zip" -d "$ROOT"
  rm -rf "$tmp"
fi
echo "ok."
if [ "${PARAKEET:-0}" = "1" ]; then
  echo "Parakeet TDT 0.6B v3 int8 (CC-BY-4.0, ≈ 671 Mo)…"
  python3 -c "
from huggingface_hub import snapshot_download
snapshot_download(
    'csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8',
    local_dir='${ROOT}/parakeet',
    allow_patterns=['*.int8.onnx', 'tokens.txt', 'README.md'],
)
print('parakeet ok')
"
else
  echo "Optionnel, Parakeet TDT 0.6B v3 int8 (CC-BY-4.0, ≈ 671 Mo) :"
  echo "  PARAKEET=1 $0 $ROOT"
  echo "  (huggingface : csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8)"
fi
