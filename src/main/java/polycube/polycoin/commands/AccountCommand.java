package polycube.polycoin.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.AccountArgument;
import polycube.polycoin.commands.commandArguments.AmountArgument;
import polycube.polycoin.commands.commandArguments.CurrencyArgument;
import polycube.polycoin.commands.commandArguments.PolyCoinIdentifierArgument;
import polycube.polycoin.economy.PolyCoinEconomyAccount;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycoin.economy.PolyCoinEconomyData;
import polycube.polycoin.util.Helpers;
import polycube.polycore.commands.CommandResult;
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;

import java.util.Collection;
import java.util.Set;

public final class AccountCommand extends PolyCommand {
    private static final String ID_ARGUMENT = "account";

    public AccountCommand() {
        super(
                PolyCoin.MOD_ID,
                "account",
                "Lists, creates, deletes, inspects, and modifies your accounts",
                "list [currencyId] | info [id] | default [id] | transfer <amount> [from <id>] [to <id>] | create <id> <name> <icon> [currencyId] | delete [id] [confirm] | modify [id] <name|icon|currencyId> [value] | modify [id] owners [add|remove <player>]",
                PermissionLevel.ALL
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name, CommandBuildContext buildContext) {
        return super.getCommand(name, buildContext)
                .then(Commands.literal("list")
                        .executes(context -> listAccounts(context, null))
                        .then(Commands.argument("currencyId", StringArgumentType.string())
                                .suggests(CurrencyArgument::suggestCurrencies)
                                .executes(context -> listAccounts(
                                        context,
                                        StringArgumentType.getString(context, "currencyId")
                                ))
                        )
                )
                .then(Commands.literal("info").executes(context -> showAccountInfo(context, null)).then(
                        Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                                .suggests(AccountArgument::suggestAccounts)
                                .executes(context -> showAccountInfo(
                                        context,
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                ))
                .then(Commands.literal("default")
                        .executes(context -> defaultAccount(context, null))
                        .then(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                                .suggests(AccountArgument::suggestAccounts)
                                .executes(context -> defaultAccount(context, StringArgumentType.getString(context, ID_ARGUMENT)))
                        )
                )
                .then(transferCommand())
                .then(createCommand(buildContext))
                .then(deleteCommand())
                .then(modifyCommand(buildContext));
    }

    private int listAccounts(CommandContext<CommandSourceStack> context, @Nullable String rawCurrencyId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context);

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency filter = null;
        if (rawCurrencyId != null) {
            filter = CurrencyArgument.getCurrency(data, rawCurrencyId);
        }

        var accounts = filter == null ? data.getAccounts(owner.id()) : data.getAccounts(owner.id(), filter.getId());
        var message = TextComponents.header(!AccountArgument.isActingAs(context) ? "Your accounts" : "Accounts for " + owner.name());
        if (filter != null) message.append(" • ").append(CommandText.currency(filter));
        message.append(TextComponents.muted(" (" + accounts.size() + ")"));

        if (accounts.isEmpty()) {
            message.append("\nNo accounts found.");
        } else {
            for (PolyCoinEconomyAccount account : accounts.values()) {
                PolyCoinEconomyCurrency currency = CommandResult.require(account.getCurrency());
                message.append("\n  • ").append(CommandText.account(account));
                if (data.isDefaultAccount(owner.id(), account.getId())) message.append(TextComponents.badge());
                message.append("\n    ").append(TextComponents.amount(currency.formatValueComponent(account.balance(), true)));
            }
        }

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private int showAccountInfo(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);

        PolyCoinEconomyAccount account = selection.account();
        PolyCoinEconomyCurrency currency = CommandResult.require(account.getCurrency());
        var message = TextComponents.header("Account details")
                .append(TextComponents.field("Name", TextComponents.value(account.name())))
                .append(TextComponents.field("ID", TextComponents.value(account.id())))
                .append(TextComponents.field("Owners", TextComponents.value(Helpers.playerNames(selection.owners()))))
                .append(TextComponents.field("Icon", TextComponents.value(BuiltInRegistries.ITEM.getKey(account.iconItem()))))
                .append(TextComponents.field("Currency", CommandText.currency(currency)))
                .append(TextComponents.field("Balance", TextComponents.amount(currency.formatValueComponent(account.balance(), true))))
                .append(TextComponents.field("Default for at least one owner", TextComponents.yesNo(selection.data().isDefaultAccount(account.getId()))));

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private int defaultAccount(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        GameProfile owner = AccountArgument.getOwner(context);
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
        PolyCoinEconomyAccount account = AccountArgument.getAccount(data, owner.id(), rawId);
        if (rawId != null) CommandResult.require(data.setDefaultAccount(owner.id(), account.getId()));
        var message = (rawId == null ? TextComponents.header("Default account") : TextComponents.success("Default account updated"))
                .append(TextComponents.field("Owner", TextComponents.value(owner.name())))
                .append(TextComponents.field("Account", CommandText.account(account)))
                .append(TextComponents.field("Currency", CommandText.currency(CommandResult.require(account.getCurrency()))));
        context.getSource().sendSuccess(() -> message, rawId != null && AccountArgument.isActingAs(context));
        return 1;
    }

    private ArgumentBuilder<CommandSourceStack, ?> transferCommand() {
        return Commands.literal("transfer").then(AccountArgument.transferArguments(null, this::transfer));
    }

    private int transfer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var amount = AmountArgument.parse(StringArgumentType.getString(context, "amount"), false);
        var owner = AccountArgument.getOwner(context);
        var data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
        var accounts = AccountArgument.getTransferAccounts(
                data, owner.id(), PolyCoinIdentifierArgument.getOptionalId(context, "from"),
                owner.id(), PolyCoinIdentifierArgument.getOptionalId(context, "to")
        );
        var currency = CommandResult.require(accounts.source().getCurrency());
        CommandResult.require(data.transfer(accounts.source().getId(), accounts.target().getId(), amount));
        var message = TextComponents.success("Transfer complete")
                .append(TextComponents.field("Amount", TextComponents.amount(currency.formatValueComponent(amount, true))))
                .append(TextComponents.field("From", CommandText.account(accounts.source())))
                .append(TextComponents.field("To", CommandText.account(accounts.target())))
                .append(TextComponents.field("Owner", TextComponents.value(owner.name())));
        context.getSource().sendSuccess(() -> message, AccountArgument.isActingAs(context));
        return 1;
    }

    private ArgumentBuilder<CommandSourceStack, ?> createCommand(CommandBuildContext buildContext) {
        return Commands.literal("create").then(
                Commands.argument(ID_ARGUMENT, StringArgumentType.string()).then(
                        Commands.argument("name", StringArgumentType.string()).then(
                                Commands.argument("icon", ItemArgument.item(buildContext))
                                        .executes(context -> createAccount(
                                                context, StringArgumentType.getString(context, ID_ARGUMENT),
                                                StringArgumentType.getString(context, "name"),
                                                ItemArgument.getItem(context, "icon").item().value(), null
                                        )).then(
                                        Commands.argument("currencyId", StringArgumentType.string())
                                                .suggests(CurrencyArgument::suggestCurrencies)
                                                .executes(context -> createAccount(
                                                        context,
                                                        StringArgumentType.getString(context, ID_ARGUMENT),
                                                        StringArgumentType.getString(context, "name"),
                                                        ItemArgument.getItem(context, "icon").item().value(),
                                                        StringArgumentType.getString(context, "currencyId")
                                                ))
                                )
                        )
                )
        );
    }

    private ArgumentBuilder<CommandSourceStack, ?> deleteCommand() {
        return deletionArguments(Commands.literal("delete"))
                .then(deletionArguments(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                        .suggests((context, builder) -> AccountArgument.suggestAccounts(context, builder, "confirm"))));
    }

    private <T extends ArgumentBuilder<CommandSourceStack, T>> T deletionArguments(T command) {
        return command.executes(context -> requestDeletion(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                .then(Commands.literal("confirm").executes(context ->
                        deleteAccount(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> modifyCommand(CommandBuildContext buildContext) {
        return modificationArguments(Commands.literal("modify"), buildContext)
                .then(modificationArguments(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                        .suggests((context, builder) -> AccountArgument.suggestAccounts(context, builder, "name", "icon", "currencyId", "owners")), buildContext));
    }

    private <T extends ArgumentBuilder<CommandSourceStack, T>> T modificationArguments(T command, CommandBuildContext buildContext) {
        return command
                .then(Commands.literal("name")
                        .executes(context -> queryName(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", StringArgumentType.string())
                                .executes(context -> setName(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("icon")
                        .executes(context -> queryIcon(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", ItemArgument.item(buildContext))
                                .executes(context -> setIcon(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        ItemArgument.getItem(context, "value").item().value()))))
                .then(Commands.literal("currencyId")
                        .executes(context -> queryCurrency(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", StringArgumentType.string())
                                .suggests(CurrencyArgument::suggestCurrencies)
                                .executes(context -> setCurrency(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("owners")
                        .executes(context -> queryOwners(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.literal("add")
                                .then(Commands.argument("value", GameProfileArgument.gameProfile())
                                        .executes(context -> addOwners(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                                GameProfileArgument.getGameProfiles(context, "value")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("value", GameProfileArgument.gameProfile())
                                        .executes(context -> removeOwners(context, PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                                GameProfileArgument.getGameProfiles(context, "value"))))));
    }

    private int createAccount(
            CommandContext<CommandSourceStack> context,
            String rawId,
            String name,
            Item icon,
            @Nullable String rawCurrencyId
    ) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context);

        String id = AccountArgument.parseId(rawId);
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(data, rawCurrencyId);
        PolyCoinEconomyAccount account = CommandResult.require(data.createAccount(Set.of(owner.id()), id, name, icon, currency.getId()));

        source.sendSuccess(
                () -> TextComponents.success("Account created")
                        .append(TextComponents.field("Account", CommandText.account(account)))
                        .append(TextComponents.field("Owner", TextComponents.value(owner.name())))
                        .append(TextComponents.field("Balance", TextComponents.amount(currency.formatValueComponent(account.balance(), true)))),
                AccountArgument.isActingAs(context)
        );
        return 1;
    }

    private int requestDeletion(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context);

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyAccount account = AccountArgument.getAccount(data, owner.id(), rawId);
        if (data.isDefaultAccount(account.getId())) {
            source.sendFailure(TextComponents.error("The default account for this currency cannot be deleted. Choose another default first."));
            return 0;
        }
        source.sendFailure(TextComponents.confirmation(
                CommandText.account(account),
                TextComponents.field("Owners affected", TextComponents.value(Helpers.playerNames(source.getServer(), account.owners())))
                        .append(TextComponents.field("Balance to be lost", TextComponents.amount(account.formattedBalance()))),
                deletionCommand(context, owner, account.getId())
        ));
        return 0;
    }

    private int deleteAccount(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GameProfile owner = AccountArgument.getOwner(context);

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyAccount account = AccountArgument.getAccount(data, owner.id(), rawId);
        var deleted = CommandResult.require(data.deleteAccount(account.getId()));

        source.sendSuccess(() -> TextComponents.success("Account deleted")
                .append(TextComponents.field("Account", TextComponents.value(deleted.getId())))
                .append(TextComponents.field("Owners affected", TextComponents.value(Helpers.playerNames(source.getServer(), deleted.owners())))),
                AccountArgument.isActingAs(context));
        return 1;
    }

    private int queryName(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        source.sendSuccess(
                () -> TextComponents.property(CommandText.account(selection.account()), "Name", TextComponents.value(selection.account().name())),
                false
        );
        return 1;
    }

    private int setName(CommandContext<CommandSourceStack> context, @Nullable String rawId, String value) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        PolyCoinEconomyAccount account = selection.account();
        var updated = CommandResult.require(selection.data().updateAccount(account.getId(), value, account.iconItem(), account.currencyId()));
        source.sendSuccess(
                () -> TextComponents.updated(CommandText.account(updated), "Name", TextComponents.value(value))
                        .append(TextComponents.field("Owners", TextComponents.value(Helpers.playerNames(selection.owners())))),
                AccountArgument.isActingAs(context)
        );
        return 1;
    }

    private int queryIcon(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        Identifier iconId = BuiltInRegistries.ITEM.getKey(selection.account().iconItem());
        source.sendSuccess(
                () -> TextComponents.property(CommandText.account(selection.account()), "Icon", TextComponents.value(iconId)),
                false
        );
        return 1;
    }

    private int setIcon(CommandContext<CommandSourceStack> context, @Nullable String rawId, Item value) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        PolyCoinEconomyAccount account = selection.account();
        var updated = CommandResult.require(selection.data().updateAccount(account.getId(), account.displayName(), value, account.currencyId()));
        Identifier iconId = BuiltInRegistries.ITEM.getKey(value);
        source.sendSuccess(
                () -> TextComponents.updated(CommandText.account(updated), "Icon", TextComponents.value(iconId))
                        .append(TextComponents.field("Owners", TextComponents.value(Helpers.playerNames(selection.owners())))),
                AccountArgument.isActingAs(context)
        );
        return 1;
    }

    private int queryCurrency(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        var currency = CommandResult.require(selection.account().getCurrency());
        source.sendSuccess(
                () -> TextComponents.property(CommandText.account(selection.account()), "Currency", CommandText.currency(currency)),
                false
        );
        return 1;
    }

    private int setCurrency(CommandContext<CommandSourceStack> context, @Nullable String rawId, String rawCurrencyId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        PolyCoinEconomyAccount account = selection.account();
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(selection.data(), rawCurrencyId);

        var updated = CommandResult.require(selection.data().updateAccount(
                account.getId(), account.displayName(), account.iconItem(), currency.getId()
        ));
        source.sendSuccess(
                () -> TextComponents.updated(CommandText.account(updated), "Currency", CommandText.currency(currency))
                        .append(TextComponents.field("Owners", TextComponents.value(Helpers.playerNames(selection.owners())))),
                AccountArgument.isActingAs(context)
        );
        return 1;
    }

    private int queryOwners(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        var owners = Helpers.playerNames(source.getServer(), selection.account().owners());
        source.sendSuccess(
                () -> TextComponents.property(CommandText.account(selection.account()), "Owners", TextComponents.value(owners)),
                false
        );
        return 1;
    }

    private int addOwners(CommandContext<CommandSourceStack> context, @Nullable String rawId, Collection<NameAndId> newOwners) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        PolyCoinEconomyAccount account = selection.account();
        var updated = CommandResult.require(selection.data().addAccountOwners(account.getId(), newOwners));
        var owners = Helpers.playerNames(source.getServer(), updated.owners());
        source.sendSuccess(
                () -> TextComponents.updated(CommandText.account(updated), "Owners", TextComponents.value(owners)),
                AccountArgument.isActingAs(context)
        );
        return 1;
    }

    private int removeOwners(CommandContext<CommandSourceStack> context, @Nullable String rawId, Collection<NameAndId> ownersToRemove) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        AccountSelection selection = findAccount(context, rawId);
        PolyCoinEconomyAccount account = selection.account();
        var updated = CommandResult.require(selection.data().removeAccountOwners(account.getId(), ownersToRemove));
        var owners = Helpers.playerNames(source.getServer(), updated.owners());
        source.sendSuccess(
                () -> TextComponents.updated(CommandText.account(updated), "Owners", TextComponents.value(owners)),
                AccountArgument.isActingAs(context)
        );
        return 1;
    }

    private AccountSelection findAccount(CommandContext<CommandSourceStack> context, @Nullable String rawId) throws CommandSyntaxException {
        GameProfile owner = AccountArgument.getOwner(context);
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(context.getSource().getServer());
        var account = AccountArgument.getAccount(data, owner.id(), rawId);
        return new AccountSelection(account.owners().stream().map(uuid -> Helpers.playerProfile(context.getSource().getServer(), uuid)).toList(), data, account);
    }

    private String deletionCommand(CommandContext<CommandSourceStack> context, GameProfile owner, String id) {
        String prefix = !AccountArgument.isActingAs(context)
                ? "/" + PolyCoin.MOD_ID + " account"
                : "/" + PolyCoin.MOD_ID + " as " + owner.name() + " account";
        return prefix + " delete \"" + id + "\" confirm";
    }

    private record AccountSelection(
            Collection<NameAndId> owners,
            PolyCoinEconomyData data,
            PolyCoinEconomyAccount account
    ) {}
}
