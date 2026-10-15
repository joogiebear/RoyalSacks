#!/bin/bash
# Runs the RoyalSacks bot scenarios: A (pickup, withdraw, upgrade), B (cross-server), C (rapid clicks), with DB checks
cd ~/rstest; S=~/rstest/scen; R=~/rstest/results; mkdir -p $R
SL="node $HOME/spawnloft/spawnloft.mjs"
db() { mariadb -urs -prstest royalsacks -N -e "$1"; }
python3 - <<'PY'
import json, os
p = os.path.expanduser('~/.local/share/mcctl/instances.json')
j = json.load(open(p))
for n in ('sacks-a', 'sacks-b'):
    j['instances'][n]['mcVersion'] = '26.1'
json.dump(j, open(p, 'w'), indent=2)
PY
db "delete from sack_contents; delete from sack_tiers; delete from sack_players"
echo "### A"
$SL bot run $S/a-sacks.json > $R/a.txt 2>&1; echo "exit $?"; grep -E '^(ok|FAIL|skip|setup|PASSED|FAILED)' $R/a.txt
sleep 2
echo "db after A:"; db "select item_id, qty from sack_contents where qty <> 0; select sack_id, tier from sack_tiers; select pickup from sack_players"
echo "### B"
$SL bot run $S/b-sacks.json > $R/b.txt 2>&1; echo "exit $?"; grep -E '^(ok|FAIL|skip|setup|PASSED|FAILED)' $R/b.txt
sleep 2
echo "db after B:"; db "select item_id, qty from sack_contents"
echo "### C"
db "delete from sack_contents"
$SL bot run $S/c-spam.json > $R/c.txt 2>&1; echo "exit $?"; grep -E '^(ok|FAIL|skip|setup|PASSED|FAILED)' $R/c.txt
echo "db after C (expect wheat 36):"; db "select item_id, qty from sack_contents"
python3 - <<'PY'
import json, os
p = os.path.expanduser('~/.local/share/mcctl/instances.json')
j = json.load(open(p))
for n in ('sacks-a', 'sacks-b'):
    j['instances'][n]['mcVersion'] = '26.2'
json.dump(j, open(p, 'w'), indent=2)
PY
