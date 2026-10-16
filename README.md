# RoyalSacks

Hypixel-style sacks for Paper. Items a player picks up go straight into a sack instead of filling their
inventory, and `/sacks` shows what is stored, takes it back out, and buys bigger sacks.

Part of the Royal plugin suite. Works standalone; sacks are per profile when RoyalSkyblock is installed.
Requires Paper 26.2 or newer (Java 25).

---

## How it works

- **Sacks** are files in `plugins/RoyalSacks/sacks/`. Each lists the items it holds and its sizes. Five ship
  by default: Agronomy, Mining, Combat, Foraging and Fishing.
- **Pickup.** When a player picks up an item that one of their sacks holds and the sack has room, it goes into
  the sack. Whatever doesn't fit stays in the inventory as normal. Players turn this off with
  `/sacks pickup off` (remembered across servers sharing a database).
- **Taking items out.** In a sack, left-click an item for one stack, right-click to fill the inventory.
- **Sizes.** Every item in a sack holds up to the sack's size (Small holds 2,240 of each). Bigger sizes are
  bought in the sack's menu with coins (Vault) and items from the inventory. A first size with no cost is
  free for everyone; the shipped Small size is.
- **Per profile.** With RoyalSkyblock and PlaceholderAPI, each profile has its own sacks, the same way
  RoyalWardrobe scopes wardrobes. A player with no active profile has no sacks, so nothing moves into or out
  of an Ironman profile.

- **Enchanted items.** Each bundled sack also holds the EcoItems enchanted forms of its items
  (`ecoitems:enchanted_cobblestone`, `ecoitems:enchanted_coal_block` in the Mining Sack), the same ids the
  RoyalMinions compactors make. Ids that don't exist on the server are skipped with one startup warning.
  Existing installs keep their `sacks/` files: copy the new lines in, or delete the folder to get the defaults.

- **Locked sacks.** A sack file can set `permission: royalsacks.sack.mining`. Players without it can't
  open, fill or empty that sack (the menu shows it as locked), so quests, collections or ranks can unlock
  it by granting the permission. What a player already stored stays in the database.

Items are matched like RoyalBazaar matches them: `wheat` is a plain vanilla wheat stack (no name, lore,
enchantments or plugin data), and anything with a namespace (`ecoitems:enchanted_wheat`) is an eco item. With
RoyalItems installed, its dressed vanilla drops count as the plain item, and withdrawals come out dressed.

## Commands

| Command | Permission | |
|---|---|---|
| `/sacks` | `royalsacks.use` | Open your sacks |
| `/sacks pickup [on\|off]` | `royalsacks.use` | Toggle auto-pickup |
| `/sacks reload` | `royalsacks.admin` | Reload config, messages, menus and sacks |
| `/sacks admin give <player> <item> <amount>` | `royalsacks.admin` | Add to a player's current sacks (ignores size) |
| `/sacks admin take <player> <item> <amount>` | `royalsacks.admin` | Remove from a player's current sacks |
| `/sacks admin view <player>` | `royalsacks.admin` | List what a player's current sacks hold |

## Files

| File | Holds |
|---|---|
| `config.yml` | storage, per-profile placeholder, pickup settings |
| `sacks/*.yml` | one sack per file: display name, icon, items, sizes and their costs |
| `menus.yml` | menu layout and text |
| `messages.yml` | chat text |

An item may be in one sack only; a second sack listing it logs a warning and skips it.

## Storage

SQLite by default, or MySQL. Several servers can share one MySQL database:

- A sack's rows in the database are the truth. Every change is applied in SQL as an amount to add or
  subtract, and a withdrawal only commits if the row still holds enough. Two servers can't both spend the
  same items.
- Items are only handed to the player after the database has taken them out. Anything that doesn't fit, or a
  player who logged out or switched profile meanwhile, goes back into the sack.
- Pickups are written every `pickup.flush-seconds` (3 by default) and when the player leaves, so mining
  doesn't cost a database write per block. A server crash loses at most those last seconds of pickups.

## PlaceholderAPI

`%royalsacks_amount_<item>%`, `%royalsacks_capacity_<item>%`, `%royalsacks_tier_<sack>%`,
`%royalsacks_stored_<sack>%`, `%royalsacks_pickup%`. Item ids keep their namespace:
`%royalsacks_amount_ecoitems:enchanted_wheat%`.

## API

`com.mystipixel.royalsacks.api.SacksApi` has static methods using JDK and Bukkit types only, so other plugins
can call them by reflection:

- `int deposit(Player, ItemStack)`: stores as much of the stack as fits and returns how many went in.
- `int pickup(Player, ItemStack)`: the same, but only when the player has auto-pickup on, with the action bar.
  RoyalMinions uses it when a player collects from a minion.
- `long amount(Player, String itemId)`: what the player's current sacks hold, or -1 if not loaded.
- `String itemId(ItemStack)`: the sack item id a stack counts as, or null.

## Building

```
mvn clean package      # -> target/RoyalSacks.jar
```

Needs JDK 25 (paper-api 26.2 is compiled for it).
