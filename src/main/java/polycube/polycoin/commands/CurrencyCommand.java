package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.CurrencyArgument;
import polycube.polycoin.commands.commandArguments.PolyCoinIdentifierArgument;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycoin.economy.PolyCoinEconomyData;

import java.math.BigInteger;
import java.util.TreeMap;

public final class CurrencyCommand extends PolyCoinCommand {
    private static final String ID_ARGUMENT = "currency";

    public CurrencyCommand() {
        super(
                "currency",
                "Lists and inspects currencies; changing currencies and the default is admin-only",
                "list | info [id] | default [id] | create <id> <name> <icon> <default_balance> | delete [id] [confirm] | modify [id] <name|icon|default_balance> [value]",
                PermissionLevel.ALL
        );
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name, CommandBuildContext buildContext) {
        return super.getCommand(name, buildContext)
                .then(Commands.literal("list").executes(context -> listCurrencies(context.getSource())))
                .then(Commands.literal("info").executes(context -> showCurrencyInfo(context.getSource(), null)).then(
                        Commands.argument(ID_ARGUMENT, StringArgumentType.word())
                                .suggests(CurrencyArgument::suggestCurrencies)
                                .executes(context -> showCurrencyInfo(
                                        context.getSource(),
                                        StringArgumentType.getString(context, ID_ARGUMENT)
                                ))
                ))
                .then(Commands.literal("default")
                        .executes(context -> defaultCurrency(context.getSource(), null))
                        .then(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                                .requires(this::canManageCurrencies)
                                .suggests(CurrencyArgument::suggestCurrencies)
                                .executes(context -> defaultCurrency(context.getSource(), StringArgumentType.getString(context, ID_ARGUMENT)))
                        )
                )
                .then(createCommand(buildContext).requires(this::canManageCurrencies))
                .then(deleteCommand().requires(this::canManageCurrencies))
                .then(modifyCommand(buildContext).requires(this::canManageCurrencies));
    }

    private boolean canManageCurrencies(CommandSourceStack source) {
        return hasPermission(source, PermissionLevel.GAMEMASTERS);
    }

    private static int listCurrencies(CommandSourceStack source) {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        var currencies = new TreeMap<>(data.getCurrencies());
        var message = Component.literal("Currencies (" + currencies.size() + "):");

        if (currencies.isEmpty()) {
            message.append("\nNo currencies found.");
        } else {
            for (PolyCoinEconomyCurrency currency : currencies.values()) {
                message.append("\n- " + currency.id().getPath() + " - ").append(currency.name());
                if (data.getDefaultCurrency() == currency) {
                    message.append(" (default)");
                }
            }
        }

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int showCurrencyInfo(CommandSourceStack source, @Nullable String rawId) {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, data, rawId);
        if (currency == null) return 0;

        var message = Component.literal("Currency: " + currency.id())
                .append("\nName: ").append(currency.name())
                .append("\nIcon: " + BuiltInRegistries.ITEM.getKey(currency.iconItem()))
                .append("\nDefault balance: ").append(currency.formatValueComponent(currency.defaultBalance(), true))
                .append("\nDefault currency: " + (data.getDefaultCurrency() == currency ? "yes" : "no"));

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int defaultCurrency(CommandSourceStack source, @Nullable String rawId) {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, data, rawId);
        if (currency == null) return 0;
        if (rawId != null) data.setDefaultCurrency(currency.id());
        var message = Component.literal((rawId == null ? "Default currency: " : "Set default currency to ")
                + currency.id().getPath() + " - ").append(currency.name());
        if (rawId != null) message.append(". Existing accounts and balances were not changed.");
        source.sendSuccess(() -> message, rawId != null);
        return 1;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createCommand(CommandBuildContext buildContext) {
        return Commands.literal("create").then(
                Commands.argument(ID_ARGUMENT, StringArgumentType.word()).then(
                        Commands.argument("name", StringArgumentType.string()).then(
                                Commands.argument("icon", ItemArgument.item(buildContext)).then(
                                        Commands.argument("default_balance", StringArgumentType.word())
                                                .executes(context -> createCurrency(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, ID_ARGUMENT),
                                                        StringArgumentType.getString(context, "name"),
                                                        ItemArgument.getItem(context, "icon").item().value(),
                                                        StringArgumentType.getString(context, "default_balance")
                                                ))
                                )
                        )
                )
        );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> deleteCommand() {
        return deletionArguments(Commands.literal("delete"))
                .then(deletionArguments(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                        .suggests((context, builder) -> CurrencyArgument.suggestCurrencies(context, builder, "confirm"))));
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T deletionArguments(T command) {
        return command.executes(context -> requestDeletion(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                .then(Commands.literal("confirm").executes(context ->
                        deleteCurrency(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> modifyCommand(CommandBuildContext buildContext) {
        return modificationArguments(Commands.literal("modify"), buildContext)
                .then(modificationArguments(Commands.argument(ID_ARGUMENT, StringArgumentType.string())
                        .suggests((context, builder) -> CurrencyArgument.suggestCurrencies(context, builder, "name", "icon", "default_balance")), buildContext));
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T modificationArguments(T command, CommandBuildContext buildContext) {
        return command
                .then(Commands.literal("name")
                        .executes(context -> queryName(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", StringArgumentType.string())
                                .executes(context -> setName(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("icon")
                        .executes(context -> queryIcon(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", ItemArgument.item(buildContext))
                                .executes(context -> setIcon(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        ItemArgument.getItem(context, "value").item().value()))))
                .then(Commands.literal("default_balance")
                        .executes(context -> queryDefaultBalance(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT)))
                        .then(Commands.argument("value", StringArgumentType.word())
                                .executes(context -> setDefaultBalance(context.getSource(), PolyCoinIdentifierArgument.getOptionalId(context, ID_ARGUMENT),
                                        StringArgumentType.getString(context, "value")))));
    }

    private static int createCurrency(
            CommandSourceStack source, String rawId,
            String name, Item icon, String rawDefaultBalance
    ) {
        Identifier id = PolyCoinIdentifierArgument.parse(rawId);
        if (id == null) {
            source.sendFailure(Component.literal("Invalid currency id. Use a PolyCoin id such as coins or polycoin:coins."));
            return 0;
        }
        if (name.isBlank()) {
            source.sendFailure(Component.literal("Currency name cannot be blank."));
            return 0;
        }

        BigInteger defaultBalance = parseNonNegativeAmount(source, rawDefaultBalance);
        if (defaultBalance == null) return 0;

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency created = data.createCurrency(id, name, icon, defaultBalance);
        if (created == null) {
            source.sendFailure(Component.literal("Currency already exists: " + id.getPath()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Created currency " + id.getPath() + " (" + name + ") with default balance "
                        + created.formatValue(defaultBalance, true) + "."),
                true
        );
        return 1;
    }

    private static int requestDeletion(CommandSourceStack source, @Nullable String rawId) {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, data, rawId);
        if (currency == null) return 0;
        Component deletionError = data.getCurrencyDeletionError(currency.id());
        if (deletionError != null) {
            source.sendFailure(deletionError);
            return 0;
        }

        int accountCount = data.countAccounts(currency);
        String id = currency.id().getPath();
        source.sendFailure(Component.literal(
                "This will permanently delete currency " + id + " and " + accountCount + " linked account(s). "
                        + "Run /" + PolyCoin.MOD_ID + " currency delete " + id + " confirm to continue."
        ));
        return 0;
    }

    private static int deleteCurrency(CommandSourceStack source, @Nullable String rawId) {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, data, rawId);
        if (currency == null) return 0;
        Component deletionError = data.getCurrencyDeletionError(currency.id());
        if (deletionError != null) {
            source.sendFailure(deletionError);
            return 0;
        }

        PolyCoinEconomyData.CurrencyDeletionResult result = data.deleteCurrency(currency.id());
        if (result == null) {
            source.sendFailure(Component.literal("Currency no longer exists: " + currency.id().getPath()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Deleted currency " + result.currency().id().getPath() + " and "
                        + result.deletedAccounts() + " linked account(s)."),
                true
        );
        return 1;
    }

    private static int queryName(CommandSourceStack source, @Nullable String rawId) {
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, rawId);
        if (currency == null) return 0;
        source.sendSuccess(() -> Component.literal(currency.id().getPath() + " name: " + currency.displayName()), false);
        return 1;
    }

    private static int setName(CommandSourceStack source, @Nullable String rawId, String value) {
        if (value.isBlank()) {
            source.sendFailure(Component.literal("Currency name cannot be blank."));
            return 0;
        }

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, data, rawId);
        if (currency == null) return 0;
        if (data.updateCurrency(currency.id(), value, currency.iconItem(), currency.defaultBalance()) == null) {
            source.sendFailure(Component.literal("Currency no longer exists: " + currency.id().getPath()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Set " + currency.id().getPath() + " name to " + value + "."), true);
        return 1;
    }

    private static int queryIcon(CommandSourceStack source, @Nullable String rawId) {
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, rawId);
        if (currency == null) return 0;
        Identifier itemId = BuiltInRegistries.ITEM.getKey(currency.iconItem());
        source.sendSuccess(() -> Component.literal(currency.id().getPath() + " icon: " + itemId), false);
        return 1;
    }

    private static int setIcon(CommandSourceStack source, @Nullable String rawId, Item value) {
        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, data, rawId);
        if (currency == null) return 0;
        if (data.updateCurrency(currency.id(), currency.displayName(), value, currency.defaultBalance()) == null) {
            source.sendFailure(Component.literal("Currency no longer exists: " + currency.id().getPath()));
            return 0;
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(value);
        source.sendSuccess(() -> Component.literal("Set " + currency.id().getPath() + " icon to " + itemId + "."), true);
        return 1;
    }

    private static int queryDefaultBalance(CommandSourceStack source, @Nullable String rawId) {
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, rawId);
        if (currency == null) return 0;
        source.sendSuccess(
                () -> Component.literal(currency.id().getPath() + " default balance: "
                        + currency.formatValue(currency.defaultBalance(), true)),
                false
        );
        return 1;
    }

    private static int setDefaultBalance(CommandSourceStack source, @Nullable String rawId, String rawValue) {
        BigInteger value = parseNonNegativeAmount(source, rawValue);
        if (value == null) return 0;

        PolyCoinEconomyData data = PolyCoin.INSTANCE.getData(source.getServer());
        PolyCoinEconomyCurrency currency = CurrencyArgument.getCurrency(source, data, rawId);
        if (currency == null) return 0;
        PolyCoinEconomyCurrency updated = data.updateCurrency(
                currency.id(), currency.displayName(), currency.iconItem(), value
        );
        if (updated == null) {
            source.sendFailure(Component.literal("Currency no longer exists: " + currency.id().getPath()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Set " + updated.id().getPath() + " default balance to "
                        + updated.formatValue(value, true) + ". Existing account balances were not changed."),
                true
        );
        return 1;
    }

    private static @Nullable BigInteger parseNonNegativeAmount(CommandSourceStack source, String rawValue) {
        BigInteger value;
        try {
            value = PolyCoinEconomyCurrency.parseAmount(rawValue);
        } catch (NumberFormatException exception) {
            source.sendFailure(Component.literal("Invalid balance: " + rawValue));
            return null;
        }

        if (value.signum() < 0) {
            source.sendFailure(Component.literal("Default balance cannot be negative."));
            return null;
        }
        return value;
    }

}
