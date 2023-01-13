package com.minecolonies.coremod.commands.colonycommands;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.research.IGlobalResearchTree;
import com.minecolonies.api.research.ILocalResearch;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.api.util.constant.translation.CommandTranslationConstants;
import com.minecolonies.coremod.commands.commandTypes.IMCColonyOfficerCommand;
import com.minecolonies.coremod.commands.commandTypes.IMCCommand;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.List;

import static com.minecolonies.api.research.util.ResearchConstants.BASE_RESEARCH_TIME;
import static com.minecolonies.coremod.commands.CommandArgumentNames.COLONYID_ARG;

public class CommandResearch implements IMCColonyOfficerCommand {

    private static final String RESEARCHID_ARG = "researchId";
    private static final String RESEARCH_COMMAND_ARG = "list|complete|cancel";
    private static final String ID_AND_TIME_TEXT = "%s (%s) ";
    private static final String RESEARCH_ENTRY_ID = "ID: ";
    private static final String RESEARCHID_NOT_FOUND = "Failed to find in-progress research with the ID: <%s>!";
    private static final String RESEARCH_COMPLETED = "Research progress set to max. Awaiting researcher for completion.";
    private static final String RESARCH_IN_PROGRESS_EMPTY = "There is no research in-progress for this colony.";

    @Override
    public int onExecute(CommandContext<CommandSourceStack> context)
    {
        return researchExecute(context, "");
    }

    /**
     * Gets called when a research ID is not given in the command.
     *
     * @param context the context of the command execution
     * @return execution return code: 1 on success, 0 on failure
     */
    public int onSpecificExecute(final CommandContext<CommandSourceStack> context)
    {
        if (!checkPreCondition(context))
        {
            return 0;
        }
        return researchExecute(context, StringArgumentType.getString(context, RESEARCHID_ARG));
    }

    /**
     * Executes commands relating to research in a colony.
     *
     * @param context the context of the command execution
     * @param researchId Research ID to complete or cancel. This can be empty string.
     * @return execution return code: 1 on success, 0 on failure
     */
    public int researchExecute(final CommandContext<CommandSourceStack> context, String researchId)
    {
        final int colonyId = IntegerArgumentType.getInteger(context, COLONYID_ARG);
        final String researchCommand = StringArgumentType.getString(context, RESEARCH_COMMAND_ARG);

        final Player player = context.getSource().getPlayer();
        if (player == null)
        {
            // Why would this happen?
            return 0;
        }

        final IColony colony = IColonyManager.getInstance().getColonyByDimension(colonyId, player.getLevel().dimension());
        if (colony == null)
        {
            // Tell the player this colony doesn't exist
            context.getSource().sendSuccess(Component.translatable(CommandTranslationConstants.COMMAND_COLONY_ID_NOT_FOUND, colonyId), true);
            return 0;
        }

        List<ILocalResearch> inProgress = colony.getResearchManager().getResearchTree().getResearchInProgress();
        switch (researchCommand)
        {
            case "list" ->
                printInProgressResearch(context, inProgress);
            // Only OP players can complete or cancel the colony research from the command line
            case "complete", "cancel" ->
            {
                boolean foundResearch = false;
                if (researchId.trim().equals(""))
                {
                    researchId = "NULL";
                    MessageUtils.format(RESEARCHID_NOT_FOUND, researchId).sendTo(player);
                    return 0;
                }

                // find the specified colony in the in progress list
                // we work backwards here because if inProgress gets modified, it won't affect our for loop
                for (int i = inProgress.size() - 1; i >= 0 && !foundResearch; i--)
                {
                    final ILocalResearch research = inProgress.get(i);
                    if (research.getId().toString().equals(researchId))
                    {
                        foundResearch = true;
                        if (researchCommand.equals("complete"))
                        {
                            // Only OPs can complete research of colonies
                            if (!context.getSource().hasPermission(OP_PERM_LEVEL))
                            {
                                context.getSource().sendSuccess(Component.translatable(CommandTranslationConstants.COMMAND_REQUIRES_OP), true);
                                return 0;
                            }

                            ResourceLocation branch = research.getBranch();
                            int depth = research.getDepth();

                            // set research progress to max. will complete when a researcher gets access to it
                            research.setProgress(IGlobalResearchTree.getInstance().getBranchData(branch).getBaseTime(depth));
                            context.getSource().sendSuccess(Component.literal(RESEARCH_COMPLETED), true);
                        }
                        else
                        {
                            colony.getResearchManager().getResearchTree().attemptResetResearch(player, colony, research);
                        }
                    }
                }

                if (!foundResearch)
                {
                    MessageUtils.format(RESEARCHID_NOT_FOUND, researchId).sendTo(player);
                    return 0;
                }
            }
            default ->
            {
                context.getSource().sendSuccess(Component.translatable(CommandTranslationConstants.COMMAND_COLONY_ID_NOT_FOUND, colonyId), true);
                return 0;
            }
        }

        return 1;
    }

    /**
     * Helper function to print the list of in-progress research for a colony.
     *
     * This function also prints out the estimated time left until the research is completed (very estimated)
     *
     * @param context the context of the command execution
     * @param inProgress List of research that gets relayed tot he user.
     */
    private void printInProgressResearch(final CommandContext<CommandSourceStack> context, List<ILocalResearch> inProgress)
    {
        if (inProgress.size() == 0)
        {
            context.getSource().sendSuccess(Component.literal(RESARCH_IN_PROGRESS_EMPTY), true);
        }
        else
        {
            for (final ILocalResearch research : inProgress)
            {
                final MutableComponent researchEntryId = Component.literal(RESEARCH_ENTRY_ID).withStyle(
                        Style.EMPTY.withBold(true).withColor(ChatFormatting.GOLD)
                );
                final double progressToGo = IGlobalResearchTree.getInstance().getBranchData(research.getBranch()).getBaseTime(research.getDepth()) - research.getProgress();
                final int hours = (int) (progressToGo / (BASE_RESEARCH_TIME * 2));
                final int increments = (int) Math.ceil(progressToGo % (BASE_RESEARCH_TIME * 2) / (BASE_RESEARCH_TIME / 2d));

                final String timeRemaining;
                if (increments == 4)
                {
                    timeRemaining = String.format("%d:%02d", hours + 1, 0);
                }
                else
                {
                    timeRemaining = String.format("%d:%02d", hours, increments * 15);
                }

                // Make an empty parent component to preserve coloring
                context.getSource().sendSuccess(
                        Component.literal("")
                                .append(researchEntryId)
                                .append(Component.literal(String.format(ID_AND_TIME_TEXT, research.getId().toString(), timeRemaining))),
                        true);
            }
        }
    }

    @Override
    public String getName() {
        return "research";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build()
    {
        String[] s = new String[] {
                "list",
                "complete",
                "cancel"
        };
        return IMCCommand.newLiteral(getName())
                .then(IMCCommand.newArgument(COLONYID_ARG, IntegerArgumentType.integer(1))
                        .then(IMCCommand.newArgument(RESEARCH_COMMAND_ARG, StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(s, builder))
                                .then(IMCCommand.newArgument(RESEARCHID_ARG, StringArgumentType.greedyString())
                                        .executes(this::onSpecificExecute))
                                .executes(this::checkPreConditionAndExecute)));
    }

}
