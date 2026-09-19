package polycube.polycoin.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.Transformation;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import polycube.polycoin.PolyCoin;
import polycube.polycoin.commands.commandArguments.CurrencyArgument;
import polycube.polycoin.economy.PolyCoinEconomyAccountData;
import polycube.polycoin.economy.PolyCoinEconomyCurrency;
import polycube.polycoin.economy.PolyCoinEconomyData;
import polycube.polycoin.util.Helpers;
import polycube.polycore.NpcCreator;
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;

import java.util.List;
import java.util.UUID;

public final class PnjCommand extends PolyCommand {
    private static final Identifier TOP_BALANCES_NPC_TYPE = PolyCoin.id("top_balances");
    private static final int ENTRY_LIMIT = 10;
    private static final int MAX_ENTRY_LIMIT = 100;
    private static final int UPDATE_INTERVAL_TICKS = 100;
    private static final String LIMIT_TAG = "limit";
    private static final String CURRENCY_TAG = "currency";
    private static final String PLAYER_ID_TAG = "playerId";

    public PnjCommand() {
        super(
                PolyCoin.MOD_ID,
                "pnj",
                "Spawns an immovable mannequin that displays the top balances",
                "[currency] | <limit:1-100> [currency] | <limit:1-100> [currency] <pos> <yaw> <pitch>",
                PermissionLevel.GAMEMASTERS
        );
        NpcCreator.registerNpcType(TOP_BALANCES_NPC_TYPE, TopBalancesCallback::new);
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        var currency = Commands.argument("currency", StringArgumentType.string())
                .suggests(CurrencyArgument::suggestCurrencies)
                .executes(context -> spawn(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "limit"),
                        StringArgumentType.getString(context, "currency")
                ))
                .then(Commands.argument("pos", Vec3Argument.vec3())
                        .then(Commands.argument("rotation", RotationArgument.rotation())
                                .executes(context -> spawn(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit"),
                                        StringArgumentType.getString(context, "currency"),
                                        Vec3Argument.getVec3(context, "pos"),
                                        RotationArgument.getRotation(context, "rotation").getRotation(context.getSource())
                                ))));
        var limit = Commands.argument("limit", IntegerArgumentType.integer(1, MAX_ENTRY_LIMIT))
                .executes(context -> spawn(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "limit"),
                        null
                ))
                .then(currency);

        return super.getCommand(name)
                .then(limit)
                .then(Commands.argument("currency", StringArgumentType.string())
                        .suggests((context, builder) -> CurrencyArgument.suggestCurrencies(context, builder, "help"))
                        .executes(context -> spawn(
                                context.getSource(),
                                ENTRY_LIMIT,
                                StringArgumentType.getString(context, "currency")
                        )));
    }

    @Override
    protected int execute(CommandSourceStack source) throws CommandSyntaxException {
        return spawn(source, ENTRY_LIMIT, null);
    }

    private static int spawn(CommandSourceStack source, int limit, @Nullable String currencyId) throws CommandSyntaxException {
        return spawn(source, limit, currencyId, source.getPlayerOrException().position(), Vec2.ZERO);
    }

    private static int spawn(CommandSourceStack source, int limit, @Nullable String currencyId, Vec3 pos, Vec2 rotation) {
        var tag = new CompoundTag();
        tag.putInt(LIMIT_TAG, limit);
        if (currencyId != null) {
            tag.putString(CURRENCY_TAG, currencyId);
        }
        var playerId = UUID.randomUUID();
        tag.putString(PLAYER_ID_TAG, playerId.toString());

        var result = NpcCreator.summonNpc(
                source.getLevel(), TOP_BALANCES_NPC_TYPE, tag,
                ResolvableProfile.createUnresolved(playerId),
                pos, rotation, Pose.STANDING
        );

        if (result.error().isPresent()) {
            source.sendFailure(TextComponents.error("Failed to create the top balances pnj: " + result.error().get()));
            return 0;
        }

        source.sendSuccess(
                () -> TextComponents.success("Spawned top balances pnj at")
                        .append(TextComponents.value(" %.2f %.2f %.2f".formatted(pos.x, pos.y, pos.z)))
                        .append(TextComponents.muted(" (yaw: %.2f, pitch: %.2f)".formatted(Mth.wrapDegrees(rotation.x), Mth.wrapDegrees(rotation.y))))
                        .append(TextComponents.muted(" • Limit: " + limit))
                        .append(currencyId != null ? TextComponents.muted(" • Currency: " + currencyId) : Component.empty()),
                true
        );
        return 1;
    }

    private static final class TopBalancesCallback implements NpcCreator.NpcCallback {
        private final CompoundTag tag;
        private List<PolyCoinEconomyAccountData.LeaderboardEntry> topAccounts = List.of();
        private int tickCounter;

        private TopBalancesCallback(CompoundTag tag) {
            this.tag = tag;
        }

        @Override
        public boolean onTick(MinecraftServer server) {
            if (tickCounter++ % UPDATE_INTERVAL_TICKS != 0) {
                return false;
            }

            var data = PolyCoin.INSTANCE.getData(server);
            var limit = tag.getInt(LIMIT_TAG).orElse(ENTRY_LIMIT);
            var currency = getCurrency(data);
            if (currency == null) {
                return false;
            }

            var entries = data.getTopAccounts(currency.getId(), limit).result().orElse(List.of());
            if (entries.isEmpty()) {
                return false;
            }

            var topPlayer = getTopPlayerId();
            var topPlayerId = entries.getFirst().owners().stream().findFirst().orElseGet(UUID::randomUUID);
            var accountsChanged = !topAccounts.equals(entries);
            if (accountsChanged) {
                topAccounts = entries;
            }

            var playerChanged = !topPlayerId.equals(topPlayer);
            if (playerChanged) {
                tag.putString(PLAYER_ID_TAG, topPlayerId.toString());
            }

            return accountsChanged || playerChanged;
        }

        @Override
        public void update(Display.TextDisplay textDisplay, Mannequin mannequin) {
            textDisplay.setBillboardConstraints(Display.BillboardConstraints.VERTICAL);
            textDisplay.setBackgroundColor(0x42000000);
            textDisplay.setFlags(Display.TextDisplay.FLAG_SHADOW);
            textDisplay.setBrightnessOverride(Brightness.FULL_BRIGHT);
            textDisplay.setLineWidth(400);
            textDisplay.setTransformation(new Transformation(null , null, new Vector3f(0.75F, 0.75F, 0.75F), null));

            var topPlayer = getTopPlayerId();
            mannequin.setComponent(DataComponents.PROFILE, ResolvableProfile.createUnresolved(topPlayer));

            if (topAccounts.isEmpty()) {
                textDisplay.setText(Component.empty()
                        .append(Component.literal("TOP BALANCES").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                        .append(Component.literal("\nNo accounts found").withStyle(ChatFormatting.GRAY)));
                return;
            }
            var server = mannequin.level().getServer();
            if (server == null) {
                return;
            }

            var data = PolyCoin.INSTANCE.getData(server);
            var currency = getCurrency(data);
            if (currency == null) {
                textDisplay.setText(Component.empty()
                        .append(Component.literal("TOP BALANCES").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                        .append(Component.literal("\nInvalid currency").withStyle(ChatFormatting.RED)));
                return;
            }

            var entryLabel = topAccounts.size() == 1 ? " entry" : " entries";
            var message = Component.empty()
                    .append(TextComponents.styled("TOP BALANCES", ChatFormatting.GOLD, true))
                    .append("\n")
                    .append(currency.name().copy().withStyle(ChatFormatting.YELLOW))
                    .append(TextComponents.styled(" • " + topAccounts.size() + entryLabel, ChatFormatting.GRAY))
                    .append(TextComponents.styled("\n────────────────────", ChatFormatting.DARK_GRAY));

            for (int index = 0; index < topAccounts.size(); index++) {
                var entry = topAccounts.get(index);
                message.append("\n")
                        .append(TextComponents.styled("#" + (index + 1), CommandText.rankColor(index), true))
                        .append("  ")
                        .append(TextComponents.value(Helpers.playerNames(server, entry.owners())))
                        .append(TextComponents.styled(" • ", CommandText.rankColor(index)))
                        .append(TextComponents.amount(currency.formatValueComponent(entry.balance(), true)));
            }

            textDisplay.setText(message);
        }

        private @Nullable PolyCoinEconomyCurrency getCurrency(PolyCoinEconomyData data) {
            var currencyId = tag.getString(CURRENCY_TAG).orElse(data.getDefaultCurrency());
            return data.getCurrency(currencyId).result().orElse(null);
        }

        private UUID getTopPlayerId() {
            var playerId = tag.getString(PLAYER_ID_TAG).orElse(null);
            if (playerId == null) {
                return UUID.randomUUID();
            }
            try {
                return UUID.fromString(playerId);
            } catch (IllegalArgumentException _) {
                return UUID.randomUUID();
            }
        }
    }
}
