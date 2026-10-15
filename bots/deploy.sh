#!/bin/bash
# copies the built jar into both servers and (re)starts them
set -euo pipefail
SL="node $HOME/spawnloft/spawnloft.mjs"
dir() { python3 -c "import json;print(json.load(open('$HOME/.local/share/mcctl/instances.json'))['instances']['$1']['dir'])"; }
for n in a b; do
  $SL stop sacks-$n >/dev/null 2>&1 || true
  cp ~/rstest/RoyalSacks/target/RoyalSacks.jar $(dir sacks-$n)/plugins/RoyalSacks.jar
done
for n in a b; do $SL start sacks-$n; done
sleep 5
for n in a b; do echo "== sacks-$n"; $SL logs sacks-$n -n 300 | grep -iE 'royalsacks|vault|essentials|error|exception|severe' | tail -12; done
