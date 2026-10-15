# Bot tests

Mineflayer scenarios run with [SpawnLoft](https://github.com/joogiebear/spawnloft)'s `bot run` against two
Paper 26.2 servers, `sacks-a` (port 25610) and `sacks-b` (port 25611), that share one MariaDB database.

| Scenario | Server | Checks |
|---|---|---|
| `scen/a-sacks.json` | A | pickup into a sack, capacity (overflow stays in the inventory, "full" message), withdraw a stack from the menu, buy the next size (coins taken, second purchase refused), pickup off |
| `scen/b-sacks.json` | B | contents, size and pickup setting written on A are read on B; fill-inventory withdraw; empty slot |
| `scen/c-spam.json` | A | five rapid withdraw clicks hand out exactly what the sack lost |

Bots read blank window titles and item names, so they click by slot number. The action-bar pickup summary
isn't seen by bots and needs a look in a real client.

## Running

On the test VM, with the jar built at `~/rstest/RoyalSacks/target/RoyalSacks.jar` and the helper plugins
(ViaVersion, ViaBackwards, PlaceholderAPI, EssentialsX, VaultUnlocked) in `~/rbtest/plugins`:

```sh
mkdir -p ~/rstest && cp -r bots/* ~/rstest/
bash ~/rstest/setup.sh     # once: database, the two servers, MySQL config
bash ~/rstest/deploy.sh    # copy the jar in and (re)start both servers
bash ~/rstest/run.sh       # all scenarios, with database checks between them
```
