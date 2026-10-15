#!/bin/bash
# RoyalSacks two-server test: servers sacks-a / sacks-b sharing MariaDB database royalsacks
set -euo pipefail
W=~/rstest; cd $W
SL="node $HOME/spawnloft/spawnloft.mjs"
J25=/usr/lib/jvm/java-25-openjdk-amd64/bin/java
sudo systemctl start mariadb
sudo mariadb -e "CREATE DATABASE IF NOT EXISTS royalsacks; CREATE USER IF NOT EXISTS 'rs'@'localhost' IDENTIFIED BY 'rstest'; GRANT ALL ON royalsacks.* TO 'rs'@'localhost'; FLUSH PRIVILEGES;"
mkdir -p plugins
cp RoyalSacks/target/RoyalSacks.jar plugins/
cp ~/rbtest/plugins/viaversion.jar ~/rbtest/plugins/viabackwards.jar ~/rbtest/plugins/placeholderapi.jar \
   ~/rbtest/plugins/essentialsx.jar ~/rbtest/plugins/vaultunlocked.jar plugins/
JAR=$(ls ~/.local/share/mcctl/jars | grep '^paper-26.2-' | sort -V | tail -1); echo $JAR
dir() { python3 -c "import json;print(json.load(open('$HOME/.local/share/mcctl/instances.json'))['instances']['$1']['dir'])"; }
for n in a b; do
  p=$([ $n = a ] && echo 25610 || echo 25611)
  $SL list | grep -q "^sacks-$n " || $SL new sacks-$n --jar $JAR --memory 2G --port $p --accept-eula --offline
  D=$(dir sacks-$n)
  mkdir -p $D/plugins && cp plugins/*.jar $D/plugins/
  $SL props sacks-$n server-ip=127.0.0.1 spawn-protection=0 >/dev/null || true
  $SL set sacks-$n java=$J25
done
echo "== first start (generate configs)"
for n in a b; do $SL start sacks-$n; $SL stop sacks-$n; done
for n in a b; do
  D=$(dir sacks-$n)
  C=$D/plugins/RoyalSacks/config.yml
  python3 - "$C" <<'PY'
import sys,re
p=sys.argv[1]; s=open(p).read()
s=s.replace('  type: SQLITE','  type: MYSQL',1)
s=re.sub(r'(    username: )root',r'\1rs',s,1)
s=re.sub(r'(    password: )""',r'\1"rstest"',s,1)
open(p,'w').write(s)
PY
  grep -n -A10 '^storage' $C | head -12
  B=$D/bukkit.yml; [ -f $B ] && sed -i 's/connection-throttle: .*/connection-throttle: -1/' $B
done
echo SETUP_DONE
