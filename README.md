<p align="center">
  <img src=".github/images/logo.png" alt="PolyCoin logo">
</p>

<h1 align="center">PolyCoin</h1>

<p align="center">
  <strong>PolyCoin - Economy system for Minecraft.</strong>
</p>

<p align="center">
  <a href="https://github.com/RenardElectric/polycoin/releases/latest"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/RenardElectric/polycoin"></a>
  <img alt="Minecraft 26.3" src="https://img.shields.io/badge/Minecraft-26.3-3C8527">
  <img alt="Fabric Loader 0.19.5 or newer" src="https://img.shields.io/badge/Fabric%20Loader-0.19.5%2B-DBD0B4">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&amp;logoColor=white">
  <a href="LICENSE.txt"><img alt="MIT License" src="https://img.shields.io/github/license/RenardElectric/polycoin"></a>
</p>

<p align="center">
  <a href="#what-you-can-do">What you can do</a> ·
  <a href="#start-playing">Start playing</a> ·
  <a href="#accounts-and-currencies">Accounts &amp; currencies</a> ·
  <a href="#server-administrators">Server admins</a> ·
  <a href="#developers">Developers</a>
</p>

PolyCoin is a server-side Fabric mod and Common Economy API provider. It gives players currency-backed
accounts, payments and balance leaderboards while giving server administrators complete in-game
control over currencies and balances.

> [!NOTE]
> **Just joining an existing PolyCoin server?** You can ignore the
> [server administrator](#server-administrators) and [developer](#developers) sections.

## What you can do

| Functionality                   | What it means in-game                                                                                       |
|---------------------------------|-------------------------------------------------------------------------------------------------------------|
| **Check your money**            | View the balance of your default account or choose any account you own.                                     |
| **Pay other players**           | Send an exact two-decimal amount to another online player, with optional source and destination accounts.   |
| **Manage multiple accounts**    | Create, inspect, rename and delete accounts, choose an icon and select a default for each currency.         |
| **Share an account**            | Add or remove owners so a group can use the same account and balance.                                       |
| **Move money between accounts** | Transfer funds between compatible accounts without paying yourself.                                         |
| **Explore server currencies**   | List currencies and inspect their names, symbols, icons, starting balances, circulation and account counts. |
| **Compare top balances**        | View up to 100 of the richest accounts for a chosen currency with `/balancetop` or `/baltop`.               |
| **Use economy integrations**    | Other server mods can access PolyCoin currencies and accounts through the bundled Common Economy API.       |

## Start playing

### Joining a multiplayer server

1. Add the server in Minecraft and connect as usual.
2. Open chat and run `/polycoin help` to see the commands available to you.
3. Run `/polycoin balance` to view your automatically created default account and starting balance.
4. Use `/polycoin pay <player> <amount>` when you are ready to send money.

### Playing in singleplayer

Singleplayer runs its own local server, so PolyCoin must be installed in your Fabric game instance.
Follow [Installing PolyCoin](#installing-polycoin).

### Making your first payment

1. Run `/polycoin currency list` to see the currencies provided by the server.
2. Run `/polycoin account list` to see your accounts and which one is selected by default.
3. Run `/polycoin pay <player> <amount>` to pay another online player from your default account.
4. If you have several accounts, add `from <account>` and optionally `to <account>` to choose both
   sides of the payment.

> [!TIP]
> Command arguments support tab completion. Run `/polycoin <command> help` to see that command's
> complete syntax in-game.

### Player commands

| Command                                                           | What it does                                                          |
|-------------------------------------------------------------------|-----------------------------------------------------------------------|
| `/polycoin`                                                       | Show the installed PolyCoin version, author and description.          |
| `/polycoin help`                                                  | List the commands available to you with clickable usage help.         |
| `/polycoin balance [account]`                                     | Show the balance of your default or selected account.                 |
| `/polycoin pay <player> <amount> [from <account>] [to <account>]` | Pay another online player.                                            |
| `/polycoin balancetop [currency]`                                 | Show the ten richest accounts in the default or selected currency.    |
| `/polycoin balancetop <limit:1-100> [currency]`                   | Choose how many leaderboard entries to show.                          |
| `/polycoin currency list`                                         | List every configured currency.                                       |
| `/polycoin currency info [id]`                                    | Inspect a currency; omitting the ID uses the default currency.        |
| `/polycoin currency default`                                      | Show the server's default currency.                                   |
| `/polycoin account list [currencyId]`                             | List your accounts, optionally filtered by currency.                  |
| `/polycoin account info [id]`                                     | Inspect an account, its owners, currency, icon and balance.           |
| `/polycoin account default [id]`                                  | Show your default account or select one for its currency.             |
| `/polycoin account transfer <amount> [from <id>] [to <id>]`       | Move money between accounts you own.                                  |
| `/polycoin account create <id> <name> <icon> [currencyId]`        | Create another account; the icon is a Minecraft item ID.              |
| `/polycoin account delete [id] [confirm]`                         | Preview and then confirm account deletion.                            |
| `/polycoin account modify [id] <property> ...`                    | Read or change an account's `name`, `icon`, `currencyId` or `owners`. |

The balance, payment and leaderboard commands also have the shorter root forms `/balance`, `/pay`,
`/balancetop` and `/baltop`.

## Accounts and currencies

### Default economy

| Property                      | Initial value                                |
|-------------------------------|----------------------------------------------|
| Currency ID                   | `polycoin`                                   |
| Display name                  | `PolyCoin`                                   |
| Denomination                  | `℗`                                          |
| Decimal places                | `2`                                          |
| Automatic-account balance     | `1000.00 ℗`                                  |
| Automatically created account | `Main Account` for every player and currency |

Servers can define additional currencies with their own name, denomination, icon and starting
balance. When a player first uses a currency, PolyCoin ensures that they have a default account for it.
Manually created accounts start at `0.00`; the currency's starting balance is used only for automatic
default accounts.

### How accounts work

- Account IDs are unique across the whole economy, while names are the human-readable labels shown in
  commands.
- Every account uses one currency and stores its balance exactly in two decimal places.
- Each player can select one default account per currency. Payments use those defaults unless account
  IDs are supplied explicitly.
- Accounts can have several owners. All owners see the shared account, and an owner cannot be removed
  while it is still their default account.
- A default account cannot be deleted or moved to another currency until another default is selected.
- Amounts accept forms such as `10`, `10.5` and `10.50`; commas, scientific notation and values that
  would require rounding beyond two decimal places are rejected.

---

## Server administrators

This section is for people installing PolyCoin in singleplayer or running a multiplayer server. Players
joining an existing server can return to [Start playing](#start-playing).

### Requirements

| Component          | Current requirement                                            |
|--------------------|----------------------------------------------------------------|
| Minecraft          | `26.3`                                                         |
| Java               | `25` or newer                                                  |
| Fabric Loader      | `0.19.5` or newer                                              |
| Fabric API         | `0.160.2+26.3` or newer compatible build                       |
| Common Economy API | Bundled inside the PolyCoin JAR; no separate download required |

### Installing PolyCoin

1. Install Java 25.
2. Install the Minecraft 26.3 version of [Fabric Loader](https://fabricmc.net/use/) for your
   game or dedicated server.
3. Open that game instance or server directory and create a folder named `mods` if it is not present.
4. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place its JAR in the `mods` folder.
5. Download PolyCoin from [GitHub Releases](https://github.com/RenardElectric/polycoin/releases) and
   place its main JAR in the same `mods` folder.
6. Start the game or server.

For singleplayer, follow these steps in the Fabric Minecraft instance you intend to play. For a
multiplayer server, install PolyCoin and Fabric API on the server; connecting players do not need the
PolyCoin JAR.

### Client compatibility and icons

> [!IMPORTANT]
> PolyCoin's economy and command output are server-side and vanilla-client-compatible. Players joining
> a multiplayer server do not need the PolyCoin JAR on their clients.

PolyCoin includes custom icons for its default currency and account. These are useful to client modpacks
and economy integrations that display item icons. If an integration shows them to otherwise unmodded
clients, distribute the matching assets through your server resource pack.

### Configuration

PolyCoin has no separate configuration file. Administrators manage currencies and balances in-game;
the resulting economy data is stored with the world. Player account management remains available to
the account owners through the commands in [Start playing](#start-playing).

### Administrative commands

| Command                                                                              | What it does                                                                                      |
|--------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| `/polycoin balance [account] <set\|add\|remove> <amount>`                            | Set a non-negative balance or add/remove a positive amount.                                       |
| `/polycoin currency default <id>`                                                    | Select the server's default currency without changing existing accounts or balances.              |
| `/polycoin currency create <id> <name> <denomination> <icon> <default_balance>`      | Create a currency; the icon is a registered Minecraft item ID.                                    |
| `/polycoin currency delete [id] [confirm]`                                           | Preview, then permanently delete a non-default currency and all accounts using it.                |
| `/polycoin currency modify [id] <name\|denomination\|icon\|default_balance> [value]` | Read or change a property; starting-balance changes affect only automatic accounts created later. |
| `/polycoin as <player> <account\|balance\|pay> ...`                                  | Run supported commands as another profile; account and balance actions support offline players.   |

These operations use Minecraft's **Gamemasters** permission level. Public currency list, info and
default-query commands remain available to every player. Arguments provide tab completion, and delete
commands require an explicit confirmation step.

### Stored data

- Currencies, the server default currency, accounts, balances, owners and each owner's per-currency
  default account are persistent world data under `polycoin:polycoin_economy_data`.
- Currency, account, balance, transfer and ownership changes are recorded in the server log.
- Common Economy-compatible mods can access the same currencies, accounts and balances through
  PolyCoin's registered `polycoin` provider.
- Back up the world as usual before removing the mod or moving a save between incompatible versions.

---

## Developers

### Building from source

#### Prerequisites

- Git
- JDK 25
- No system Gradle installation is required; the repository includes the Gradle 9.7.1 wrapper.

Clone the repository:

```bash
git clone https://github.com/RenardElectric/polycoin.git
cd polycoin
```

<details open>
<summary><strong>Windows PowerShell</strong></summary>

```powershell
.\gradlew.bat build --stacktrace
```

</details>

<details>
<summary><strong>Linux / macOS</strong></summary>

```bash
chmod +x ./gradlew
./gradlew build --stacktrace
```

</details>

The `build` task compiles the mod, runs its configured checks and writes artifacts to `build/libs/`.

### Repository layout

| Path                              | Purpose                                                                           |
|-----------------------------------|-----------------------------------------------------------------------------------|
| `src/main/java/polycube/polycoin` | Server initializer, commands, Common Economy provider, validation and persistence |
| `src/main/resources`              | Fabric metadata and default currency/account icon assets                          |
| `.github/workflows`               | Build and release automation                                                      |
| `.github/scripts`                 | Shared CI metadata, release and summary scripts                                   |

Before submitting a change, run the build command above and confirm there are no errors.

## Authors and license

PolyCoin is made by **RenardElectric**.

This project is available under the [MIT License](LICENSE.txt).
