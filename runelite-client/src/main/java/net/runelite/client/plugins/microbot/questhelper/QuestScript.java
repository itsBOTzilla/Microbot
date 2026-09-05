package net.runelite.client.plugins.microbot.questhelper;

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.questhelper.logic.PiratesTreasure;
import net.runelite.client.plugins.microbot.questhelper.logic.QuestRegistry;
import net.runelite.client.plugins.microbot.questhelper.questinfo.QuestHelperQuest;
import net.runelite.client.plugins.microbot.questhelper.managers.QuestContainerManager;
import net.runelite.client.plugins.microbot.questhelper.questhelpers.QuestHelper;
import net.runelite.client.plugins.microbot.questhelper.requirements.Requirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.item.ItemRequirement;
import net.runelite.client.plugins.microbot.questhelper.steps.*;
import net.runelite.client.plugins.microbot.questhelper.steps.widget.WidgetHighlight;
import net.runelite.client.plugins.microbot.shortestpath.ShortestPathPlugin;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.grandexchange.models.WikiPrice;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.shop.Rs2Shop;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.api.tileitem.Rs2TileItemQueryable;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.slf4j.event.Level;
import net.runelite.api.coords.WorldArea;

@lombok.extern.slf4j.Slf4j
public class QuestScript extends Script {
    public static double version = 0.4;

    private static final long MISSING_REQUIREMENT_NOTIFY_INTERVAL_MS = 10_000L;
    private static final Map<Integer, Long> lastMissingRequirementNotice = new HashMap<>();

    public static List<ItemRequirement> itemRequirements = new ArrayList<>();

    public static List<ItemRequirement> itemsMissing = new ArrayList<>();
    public static List<ItemRequirement> grandExchangeItems = new ArrayList<>();

    private static final AtomicBoolean valeTotemsPromptInFlight = new AtomicBoolean(false);
    private static volatile QuestHelperConfig.ValeTotemsWoodType valeTotemsSessionWoodType;

    private static final AtomicBoolean obtainItemsPromptInFlight = new AtomicBoolean(false);
    private static volatile Boolean obtainItemsSessionChoice;

    volatile boolean unreachableTarget = false;

    private QuestHelperConfig config;
    private QuestHelperPlugin mQuestPlugin;
    private static Set<Integer> npcsHandled = new HashSet<>();
    private static Set<Long> objectsHandeled = new HashSet<>();

    private int heldTrackingQuestId = -1;
    private final Set<Integer> everHeldItemRequirementIds = new HashSet<>();

    QuestStep dialogueStartedStep = null;
    private final QuestDialogueContinuation dialogueContinuation = new QuestDialogueContinuation();

    private static final AtomicLong interactionResetGeneration = new AtomicLong();
    private long observedResetGeneration;
    private final QuestDialogueAdvance dialogueAdvance = new QuestDialogueAdvance();
    private PendingInteraction pendingInteraction;
    private long interactionSequence;
    private long targetReadyAt;
    private QuestStep lastCustomStep;
    private volatile long nextCustomAttemptAt;
    private boolean customActionPending;

    private static WorldPoint scenePlayerLocation() {
        Player player = Microbot.getClient().getLocalPlayer();
        return player == null ? null : player.getWorldLocation();
    }

    private WorldPoint routeLocation(WorldPoint scenePoint) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            LocalPoint local = LocalPoint.fromWorld(Microbot.getClient().getTopLevelWorldView(), scenePoint);
            return local == null ? null : WorldPoint.fromLocalInstance(Microbot.getClient(), local);
        }).orElse(null);
    }

    private boolean isSceneTileWalkable(WorldPoint point) {
        WorldView view = Microbot.getClient().getTopLevelWorldView();
        LocalPoint local = LocalPoint.fromWorld(view, point);
        if (local == null || view.getCollisionMaps() == null || point.getPlane() != view.getPlane()) return false;
        CollisionData map = view.getCollisionMaps()[point.getPlane()];
        if (map == null) return false;
        int[][] flags = map.getFlags();
        int x = local.getSceneX();
        int y = local.getSceneY();
        return x >= 0 && x < flags.length && y >= 0 && y < flags[x].length
                && (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
    }

    public boolean run(QuestHelperConfig config, QuestHelperPlugin mQuestPlugin) {
        this.config = config;
        this.mQuestPlugin = mQuestPlugin;


        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!config.startStopQuestHelper() || !Microbot.isLoggedIn() || !super.run()) {
                    clearInteractionState();
                    return;
                }
                if (getQuestHelperPlugin().getSelectedQuest() == null) {
                    clearInteractionState();
                    return;
                }

                if (observedResetGeneration != interactionResetGeneration.get()) {
                    clearInteractionState();
                    observedResetGeneration = interactionResetGeneration.get();
                }

                QuestStep questStep = getQuestHelperPlugin().getSelectedQuest().getCurrentStep().getActiveStep();
                if (questStep == null) {
                    clearInteractionState();
                    return;
                }

                observePendingInteraction(questStep);
                if (QuestInteractionFlow.handleCutscene(Microbot.getVarbitValue(4606) > 0,
                        this::continueReadyDialogue)) return;

                if (Rs2Dialogue.isInDialogue() && dialogueStartedStep == null)
                    dialogueStartedStep = questStep;

                if (questStep != null && Rs2Widget.isWidgetVisible(ComponentID.DIALOG_OPTION_OPTIONS)) {
                    var dialogOptions = Rs2Widget.getWidget(ComponentID.DIALOG_OPTION_OPTIONS);
                    var dialogChoices = dialogOptions.getDynamicChildren();

                    for (var choice : questStep.getChoices().getChoices()) {
                        if (choice.getExpectedPreviousLine() != null)
                            continue; // TODO

                        if (choice.getExcludedStrings() != null && choice.getExcludedStrings().stream().anyMatch(Rs2Widget::hasWidget))
                            continue;

                        for (var dialogChoice : dialogChoices) {
                            if (dialogChoice.getText().endsWith(choice.getChoice())) {
                                if (allowDialogueAdvance()) {
                                    Rs2Keyboard.keyPress(dialogChoice.getOnKeyListener()[7].toString().charAt(0));
                                }
                                return;
                            }
                        }
                    }
                }

                if (!Rs2Dialogue.isInDialogue() && (pendingInteraction != null || Rs2Player.isAnimating())) return;

                if (questStep != null && !questStep.getWidgetsToHighlight().isEmpty()) {
                    var visibleWidgetHighlights = questStep.getWidgetsToHighlight().stream()
                            .filter(x -> x instanceof WidgetHighlight)
                            .map(x -> (WidgetHighlight) x)
                            .filter(x -> Rs2Widget.isWidgetVisible(x.getInterfaceID()))
                            .collect(Collectors.toList());

                    List<Integer> shopItemIds = visibleWidgetHighlights.stream()
                            .map(WidgetHighlight::getItemIdRequirement)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    if (Rs2Shop.isOpen() && !shopItemIds.isEmpty()) {
                        int itemId = firstMissingShopItemId(shopItemIds, Rs2Inventory::hasItem);
                        if (itemId != -1 && Rs2Shop.buyItem(itemId, "1")) {
                            sleepUntil(() -> Rs2Inventory.hasItem(itemId), 3_000);
                        }
                        return;
                    }

                    var widgetHighlight = visibleWidgetHighlights.stream().findFirst().orElse(null);

                    if (widgetHighlight != null) {
                        Integer highlightedItemId = widgetHighlight.getItemIdRequirement();
                        if (highlightedItemId != null) {
                            if (Rs2Widget.isProductionWidgetOpen()) {
                                ItemComposition itemComposition = Microbot.getRs2ItemManager()
                                        .getItemComposition(highlightedItemId);
                                if (itemComposition != null) {
                                    Rs2Widget.enableQuantityOption("All");
                                    if (Rs2Widget.handleProcessingInterface(itemComposition.getName())) {
                                        Rs2Player.waitForAnimation();
                                    }
                                    return;
                                }
                            }

                            Rectangle targetBounds = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                                Widget root = Microbot.getClient().getWidget(widgetHighlight.getInterfaceID());
                                Widget target = findVisibleWidgetByItemId(root, highlightedItemId);
                                return target == null ? null : new Rectangle(target.getBounds());
                            }).orElse(null);
                            if (targetBounds != null && !targetBounds.isEmpty()) {
                                Microbot.getMouse().click(targetBounds);
                                Rs2Player.waitForAnimation();
                            }
                            return;
                        }

                        var widget = Rs2Widget.getWidget(widgetHighlight.getInterfaceID());
                        if (widget != null) {
                            if (widgetHighlight.getChildChildId() != -1) {
                                var childWidget = widget.getChildren()[widgetHighlight.getChildChildId()];
                                if (childWidget != null) {
                                    Rs2Widget.clickWidget(childWidget.getId());
                                    return;
                                }
                            } else {
                                if (widgetHighlight.getNameToCheckFor() != null && !widgetHighlight.getNameToCheckFor().isEmpty()) {
                                    Rs2Widget.clickWidget(widgetHighlight.getNameToCheckFor());
                                } else {
                                    Rs2Widget.clickWidget(widget.getId());
                                    if (Rs2Shop.isOpen() && getQuestHelperPlugin().getSelectedQuest().getQuest().getId() == Quest.PIRATES_TREASURE.getId()) {
                                        Rs2Shop.buyItemOptimally("karamjan rum", 1);
                                    }
                                }
                                return;
                            }
                        }
                    }
                }

                boolean dialogueAdvanceReserved = Rs2Dialogue.isInDialogue();
                if (dialogueAdvanceReserved && !QuestInteractionFlow.allowGenericDialogue(
                        this::allowDialogueAdvance, this::executeQuestCustomLogic)) return;

                if (getQuestHelperPlugin().getSelectedQuest() != null && !Microbot.getClientThread().runOnClientThreadOptional(() ->
                        getQuestHelperPlugin().getSelectedQuest().isCompleted()).orElse(null)) {
                    if (Rs2Widget.isWidgetVisible(ComponentID.DIALOG_OPTION_OPTIONS) && getQuestHelperPlugin().getSelectedQuest().getQuest().getId() != Quest.COOKS_ASSISTANT.getId() && !Rs2Bank.isOpen()) {
                        if (!dialogueAdvanceReserved && !allowDialogueAdvance()) return;
                        boolean hasOption = Rs2Dialogue.handleQuestOptionDialogueSelection();
                        //if there is no quest option in the dialogue, just click player location to remove
                        // the dialogue to avoid getting stuck in an infinite loop of dialogues
                        if (!hasOption) {
                            if (Rs2Dialogue.acceptQuestStartDialogue()) {
                                return;
                            }
                            if (getQuestHelperPlugin().getSelectedQuest() != null &&
                                    getQuestHelperPlugin().getSelectedQuest().getQuest().getId() == Quest.IMP_CATCHER.getId()
                                    && Microbot.getClient().getTopLevelWorldView().getPlane() == 1) {
                                Rs2Dialogue.keyPressForDialogueOption(1); // presses option 1
                                sleep(1200,1800);
                            }
                            Rs2Walker.walkFastCanvas(Rs2Player.getWorldLocation());
                        }
                        return;
                    }

                    if (getQuestHelperPlugin().getSelectedQuest() != null &&
                            getQuestHelperPlugin().getSelectedQuest().getQuest().getId() == Quest.COOKS_ASSISTANT.getId() &&
                            Rs2Dialogue.isInDialogue()) {
                        dialogueStartedStep = questStep;  // Force this to be true for Cook's Assistant
                    }

                    if (getQuestHelperPlugin().getSelectedQuest() != null &&
                            getQuestHelperPlugin().getSelectedQuest().getQuest().getId() == Quest.PIRATES_TREASURE.getId() &&
                            Rs2Dialogue.isInDialogue()) {
                        dialogueStartedStep = questStep;
                    }

                    if (Rs2Dialogue.isInDialogue() && dialogueStartedStep == questStep) {
                        if (Rs2Dialogue.hasContinue() && (dialogueAdvanceReserved || allowDialogueAdvance())) {
                            continueReadyDialogue();
                        }
                        return;
                    } else {
                        dialogueStartedStep = null;
                        if (!Rs2Dialogue.isInDialogue()) {
                            dialogueAdvance.reset();
                            dialogueContinuation.reset();
                        }
                    }

                    if (pendingInteraction != null || Rs2Player.isAnimating()) return;

                    if (!runIdleCustomLogic(questStep)) return;

                    boolean isInCutscene = Microbot.getVarbitValue(4606) > 0;
                    if (isInCutscene) {
                        if (ShortestPathPlugin.getMarker() != null)
                            ShortestPathPlugin.exit();
                        return;
                    }

					if (questStep instanceof DetailedQuestStep && handleRequirements((DetailedQuestStep) questStep)) {
						sleep(500, 1000);
						return;
					}

					if (questStep instanceof DetailedQuestStep && handleMissingItemRequirements((DetailedQuestStep) questStep)) {
						return;
					}

					/**
					 * This portion is needed when using item on another item in your inventory.
					 * If we do not prioritize this, the script will think we are missing items
					 */
					if (questStep instanceof DetailedQuestStep && !(questStep instanceof NpcStep || questStep instanceof ObjectStep || questStep instanceof DigStep)) {
                        boolean result = applyDetailedQuestStep((DetailedQuestStep) getQuestHelperPlugin().getSelectedQuest().getCurrentStep().getActiveStep());
                        if (result) {
                            sleepUntil(() -> Rs2Player.isInteracting() || Rs2Player.isMoving() || Rs2Player.isAnimating() || Rs2Dialogue.isInDialogue(), 500);
                            sleepUntil(() -> !Rs2Player.isInteracting() && !Rs2Player.isMoving() && !Rs2Player.isAnimating());
                            return;
                        }
                    }

                    if (getQuestHelperPlugin().getSelectedQuest().getCurrentStep() instanceof ConditionalStep) {
                        QuestStep conditionalStep = getQuestHelperPlugin().getSelectedQuest().getCurrentStep().getActiveStep();
                        applyStep(conditionalStep);
                    } else if (getQuestHelperPlugin().getSelectedQuest().getCurrentStep() instanceof NpcStep) {
                        applyNpcStep((NpcStep) getQuestHelperPlugin().getSelectedQuest().getCurrentStep());
                    } else if (getQuestHelperPlugin().getSelectedQuest().getCurrentStep() instanceof ObjectStep) {
                        applyObjectStep((ObjectStep) getQuestHelperPlugin().getSelectedQuest().getCurrentStep());
                    } else if (getQuestHelperPlugin().getSelectedQuest().getCurrentStep() instanceof DigStep) {
                        applyDigStep((DigStep) getQuestHelperPlugin().getSelectedQuest().getCurrentStep());
                    } else if (getQuestHelperPlugin().getSelectedQuest().getCurrentStep() instanceof PuzzleStep) {
                        applyPuzzleStep((PuzzleStep) getQuestHelperPlugin().getSelectedQuest().getCurrentStep());
                    }

                    if (!(questStep instanceof NpcStep) && !(questStep instanceof ObjectStep)) {
                        sleepUntil(() -> Rs2Player.isInteracting() || Rs2Player.isMoving() || Rs2Player.isAnimating() || Rs2Dialogue.isInDialogue(), 500);
                        sleepUntil(() -> !Rs2Player.isInteracting() && !Rs2Player.isMoving() && !Rs2Player.isAnimating());
                    }
                }

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
                ex.printStackTrace(System.out);
            }
        }, 0, 200, TimeUnit.MILLISECONDS);
        return true;
    }

    private QuestDialogueContinuation.Prompt readyDialoguePrompt() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget options = Microbot.getClient().getWidget(ComponentID.DIALOG_OPTION_OPTIONS);
            if (options != null && !options.isHidden()) {
                return null;
            }
            return QuestDialogueContinuation.capture(Rs2Widget.findWidget("Click here to continue", true));
        }).orElse(null);
    }

    private void continueReadyDialogue() {
        QuestDialogueContinuation.Prompt prompt = readyDialoguePrompt();
        dialogueContinuation.advance(prompt, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), bounds -> {
            Rs2Walker.clearWalkingRoute("quest-helper:dialogue-continue");
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        });
    }
	private boolean handleRequirements(DetailedQuestStep questStep) {
		var requirements = questStep.getRequirements();

		for (var requirement : requirements) {
			if (requirement instanceof ItemRequirement) {
				var itemRequirement = (ItemRequirement) requirement;

				if (itemRequirement.mustBeEquipped()) {
					if (!hasItemRequirementOnPlayer(itemRequirement)) {
						notifyMissingRequirement(itemRequirement);
						continue;
					}

					if (itemRequirement.getAllIds().stream().noneMatch(Rs2Equipment::isWearing)) {
						Rs2Inventory.wear(itemRequirement.getAllIds().stream().filter(Rs2Inventory::contains).findFirst().orElse(-1));
						return true;
					}
				}
			}
		}

		return false;
	}

	private boolean handleMissingItemRequirements(DetailedQuestStep questStep) {
		List<ItemRequirement> missing = new ArrayList<>();
		List<ItemRequirement> needsUnnoting = new ArrayList<>();

		for (Requirement requirement : questStep.getRequirements()) {
			if (!(requirement instanceof ItemRequirement)) {
				continue;
			}

			ItemRequirement itemRequirement = (ItemRequirement) requirement;

			if (itemRequirement.mustBeEquipped()
					&& Rs2Inventory.contains(itemRequirement.getAllIds().stream().mapToInt(i -> i).toArray())
					&& itemRequirement.getAllIds().stream().noneMatch(Rs2Equipment::isWearing)) {
				Rs2Inventory.wear(itemRequirement.getAllIds().stream().filter(Rs2Inventory::contains).findFirst().orElse(-1));
				return true;
			}

			if (hasItemRequirementOnPlayer(itemRequirement)) {
				continue;
			}

			if (hasNotedVersionInInventory(itemRequirement)) {
				needsUnnoting.add(itemRequirement);
				continue;
			}

			missing.add(itemRequirement);
		}

		if (!needsUnnoting.isEmpty()) {
			return unnoteItemsViaBank(needsUnnoting);
		}

		if (missing.isEmpty()) {
			return false;
		}

		if (!shouldObtainMissingItems()) {
			return obtainItemsPromptInFlight.get();
		}

		ItemRequirement nonTradable = missing.stream()
				.filter(ir -> !isItemRequirementTradable(ir))
				.findFirst()
				.orElse(null);

		if (nonTradable != null) {
			return attemptToAcquireRequirementItem(questStep, nonTradable);
		}

		return acquireMissingTradableItems(missing);
	}

	private boolean acquireMissingTradableItems(List<ItemRequirement> missing) {
		notifyMissingRequirement(missing.get(0));

		List<ItemRequirement> actionable = new ArrayList<>();
		for (ItemRequirement ir : missing) {
			if (!hasMatchingGrandExchangeOffer(ir)) {
				actionable.add(ir);
			}
		}

		if (actionable.isEmpty()) {
			if (!Rs2GrandExchange.isOpen()) {
				Microbot.status = "Quest helper: heading to Grand Exchange for in-progress offers";
				Rs2GrandExchange.walkToGrandExchange();
				Rs2GrandExchange.openExchange();
				sleepUntil(Rs2GrandExchange::isOpen, 15_000);
				if (!Rs2GrandExchange.isOpen()) {
					return true;
				}
			}

			if (!Rs2GrandExchange.hasBoughtOffer()) {
				Microbot.status = "Waiting for Grand Exchange offers to fill";
				if (!sleepUntil(Rs2GrandExchange::hasBoughtOffer, 60_000)) {
					stopQuesterWithReason(
							"Grand Exchange offers for missing quest items did not fill within 60 seconds. "
									+ "They may be underpriced or low supply — cancel them manually and retry.");
					return true;
				}
			}

			collectPurchasedItemsViaBank(missing);
			return true;
		}

		if (!Rs2Bank.isOpen()) {
			Microbot.status = "Quest helper: walking to bank for missing items";
			Rs2Bank.walkToBankAndUseBank();
			sleepUntil(Rs2Bank::isOpen, 15_000);
			if (!Rs2Bank.isOpen()) {
				return true;
			}
		}

		List<ItemRequirement> fromBank = new ArrayList<>();
		Map<ItemRequirement, Integer> bankWithdrawId = new HashMap<>();
		List<ItemRequirement> toBuy = new ArrayList<>();

		for (ItemRequirement ir : actionable) {
			int needed = remainingQuantityNeeded(ir);
			if (needed <= 0) {
				continue;
			}

			int bestBankId = -1;
			int bestBankCount = 0;
			for (Integer id : ir.getAllIds()) {
				if (id == null || id <= 0) {
					continue;
				}
				int count = Rs2Bank.count(id);
				if (count > bestBankCount) {
					bestBankCount = count;
					bestBankId = id;
				}
			}

			if (bestBankCount >= needed) {
				fromBank.add(ir);
				bankWithdrawId.put(ir, bestBankId);
			} else {
				toBuy.add(ir);
			}
		}

		long totalBuyCost = 0L;
		Map<ItemRequirement, Integer> offerPrices = new HashMap<>();
		Map<ItemRequirement, Integer> buyQuantities = new HashMap<>();
		Map<ItemRequirement, Integer> buyPrimaryIds = new HashMap<>();

		for (ItemRequirement ir : toBuy) {
			int primaryId = tradablePrimaryId(ir);
			if (primaryId == -1) {
				Rs2Bank.closeBank();
				stopQuesterWithReason("Quest item is not tradable on the Grand Exchange: " + ir.getName());
				return true;
			}

			int basePrice = fetchInstabuyReferencePrice(primaryId);
			if (basePrice <= 0) {
				Rs2Bank.closeBank();
				stopQuesterWithReason("Failed to fetch Grand Exchange price for: " + ir.getName());
				return true;
			}

			int offerPrice = Math.max(1, (int) Math.ceil(basePrice * 1.2));
			int qty = remainingQuantityNeeded(ir);
			buyPrimaryIds.put(ir, primaryId);
			offerPrices.put(ir, offerPrice);
			buyQuantities.put(ir, qty);
			totalBuyCost += (long) offerPrice * qty;
		}

		long invCoins = Rs2Inventory.itemQuantity(ItemID.COINS_995);
		long bankCoins = Rs2Bank.count(ItemID.COINS_995);
		long availableGp = invCoins + bankCoins;

		if (availableGp < totalBuyCost) {
			Rs2Bank.closeBank();
			stopQuesterWithReason(String.format(
					"Not enough gp to buy missing quest items (need %,d gp, have %,d gp)",
					totalBuyCost, availableGp));
			return true;
		}

		if (!toBuy.isEmpty()) {
			int freeSlots = Rs2GrandExchange.getAvailableSlotsCount();
			if (freeSlots < toBuy.size()) {
				Rs2Bank.closeBank();
				stopQuesterWithReason(String.format(
						"Not enough free Grand Exchange slots for missing quest items (need %d, have %d)",
						toBuy.size(), freeSlots));
				return true;
			}
		}

		if (!Rs2Bank.setWithdrawAsItem()) {
			Rs2Bank.closeBank();
			stopQuesterWithReason("Failed to set bank withdraw mode to Item. Toggle it manually and restart.");
			return true;
		}

		for (ItemRequirement ir : fromBank) {
			Integer idToWithdraw = bankWithdrawId.get(ir);
			if (idToWithdraw == null || idToWithdraw <= 0) {
				continue;
			}
			int qty = remainingQuantityNeeded(ir);
			if (qty <= 0) {
				continue;
			}
			Microbot.status = "Withdrawing " + ir.getName() + " x" + qty;
			Rs2Bank.withdrawX(idToWithdraw, qty);
			if (!sleepUntil(() -> hasItemRequirementOnPlayer(ir), 2_000)) {
				Microbot.log("Quest helper: bank withdrawal for " + ir.getName() + " did not land in time",
						Level.WARN);
			}
		}

		if (!toBuy.isEmpty()) {
			final long requiredCoins = totalBuyCost;
			long currentInvCoins = Rs2Inventory.itemQuantity(ItemID.COINS_995);
			if (requiredCoins > currentInvCoins) {
				long coinsStillNeeded = requiredCoins - currentInvCoins;
				int coinsToWithdraw = (int) Math.min(Integer.MAX_VALUE, coinsStillNeeded);
				Microbot.status = "Withdrawing " + coinsToWithdraw + " gp for Grand Exchange";
				Rs2Bank.withdrawX(ItemID.COINS_995, coinsToWithdraw);
				sleepUntil(() -> Rs2Inventory.itemQuantity(ItemID.COINS_995) >= requiredCoins, 3_000);
			}

			long invCoinsAfter = Rs2Inventory.itemQuantity(ItemID.COINS_995);
			if (invCoinsAfter < requiredCoins) {
				Rs2Bank.closeBank();
				stopQuesterWithReason(String.format(
						"Bank withdrawal failed to supply enough coins (need %,d gp, have %,d gp in inventory)",
						requiredCoins, invCoinsAfter));
				return true;
			}
		}

		Rs2Bank.closeBank();
		sleepUntil(() -> !Rs2Bank.isOpen(), 3_000);

		if (toBuy.isEmpty()) {
			return true;
		}

		if (!Rs2GrandExchange.isOpen()) {
			Microbot.status = "Quest helper: walking to Grand Exchange";
			Rs2GrandExchange.walkToGrandExchange();
			Rs2GrandExchange.openExchange();
			sleepUntil(Rs2GrandExchange::isOpen, 15_000);
			if (!Rs2GrandExchange.isOpen()) {
				return true;
			}
		}

		int placedOffers = 0;
		for (ItemRequirement ir : toBuy) {
			int offerPrice = offerPrices.getOrDefault(ir, 0);
			int qty = buyQuantities.getOrDefault(ir, 0);
			int primaryId = buyPrimaryIds.getOrDefault(ir, -1);
			if (offerPrice <= 0 || qty <= 0 || primaryId == -1) {
				continue;
			}

			String canonicalName = canonicalItemName(primaryId);
			if (canonicalName == null || canonicalName.isEmpty() || "null".equalsIgnoreCase(canonicalName)) {
				stopQuesterWithReason("Unable to resolve in-game name for: " + ir.getName());
				return true;
			}

			Microbot.status = "Buying " + canonicalName + " x" + qty;
			if (Rs2GrandExchange.buyItem(canonicalName, offerPrice, qty)) {
				placedOffers++;
			}
			sleep(800, 1500);
		}

		if (placedOffers == 0) {
			stopQuesterWithReason("Failed to place any Grand Exchange offers for missing quest items");
			return true;
		}

		final int maxBuyAttempts = 5;
		final int perAttemptWaitMs = 15_000;

		for (int attempt = 1; attempt <= maxBuyAttempts; attempt++) {
			Microbot.status = String.format(
					"Waiting for Grand Exchange offers to fill (attempt %d/%d)",
					attempt, maxBuyAttempts);

			sleepUntil(() -> itemsStillBuying(toBuy, buyPrimaryIds).isEmpty(), perAttemptWaitMs);

			List<ItemRequirement> stillPending = itemsStillBuying(toBuy, buyPrimaryIds);
			if (stillPending.isEmpty()) {
				break;
			}

			if (attempt >= maxBuyAttempts) {
				Rs2GrandExchange.abortAllOffers(false);
				stopQuesterWithReason(String.format(
						"Grand Exchange offers did not fill after %d attempts with doubled prices. "
								+ "Aborted all offers — check your inventory for any partially-filled items "
								+ "and add more gp before restarting.",
						maxBuyAttempts));
				return true;
			}

			for (ItemRequirement ir : stillPending) {
				int primaryId = buyPrimaryIds.getOrDefault(ir, -1);
				if (primaryId == -1) {
					continue;
				}
				String canonicalName = canonicalItemName(primaryId);
				if (canonicalName == null || canonicalName.isEmpty()) {
					continue;
				}

				long preAbortCoins = Rs2Inventory.itemQuantity(ItemID.COINS_995);

				Microbot.status = "Cancelling unfilled " + canonicalName;
				if (!Rs2GrandExchange.abortOffer(canonicalName, false)) {
					Microbot.log("Quest helper: failed to abort offer for " + canonicalName
							+ "; skipping retry for this item this round", Level.WARN);
					continue;
				}

				sleepUntil(() -> Rs2Inventory.itemQuantity(ItemID.COINS_995) > preAbortCoins, 3_000);

				int alreadyOnHand = inventoryQuantityIncludingNoted(ir);
				int qtyNeeded = Math.max(0, ir.getQuantity() - alreadyOnHand);
				if (qtyNeeded <= 0) {
					Microbot.log("Quest helper: " + canonicalName
							+ " already obtained after abort (have " + alreadyOnHand
							+ "); skipping retry buy", Level.INFO);
					buyQuantities.put(ir, 0);
					continue;
				}
				buyQuantities.put(ir, qtyNeeded);

				int currentPrice = offerPrices.getOrDefault(ir, 0);
				long doubled = (long) currentPrice * 2L;
				int newPrice = doubled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) doubled;
				offerPrices.put(ir, newPrice);

				if (newPrice <= 0) {
					continue;
				}

				long retryInvCoins = Rs2Inventory.itemQuantity(ItemID.COINS_995);
				long costNeeded = (long) newPrice * (long) qtyNeeded;
				if (retryInvCoins < costNeeded) {
					Rs2GrandExchange.abortAllOffers(false);
					stopQuesterWithReason(String.format(
							"Not enough gp to retry %s at %,d gp each (need %,d, have %,d)",
							canonicalName, newPrice, costNeeded, retryInvCoins));
					return true;
				}

				Microbot.status = String.format(
						"Retry %d/%d: buying %s x%d at %,d gp",
						attempt + 1, maxBuyAttempts, canonicalName, qtyNeeded, newPrice);
				if (!Rs2GrandExchange.buyItem(canonicalName, newPrice, qtyNeeded)) {
					Microbot.log("Quest helper: retry buy for " + canonicalName + " failed to place",
							Level.WARN);
				}
				sleep(800, 1500);
			}
		}

		collectPurchasedItemsViaBank(toBuy);
		return true;
	}

	private List<ItemRequirement> itemsStillBuying(List<ItemRequirement> toBuy,
			Map<ItemRequirement, Integer> primaryIds) {
		List<ItemRequirement> pending = new ArrayList<>();
		GrandExchangeOffer[] offers = Microbot.getClient().getGrandExchangeOffers();
		if (offers == null) {
			return pending;
		}

		for (ItemRequirement ir : toBuy) {
			int primaryId = primaryIds.getOrDefault(ir, -1);
			if (primaryId == -1) {
				continue;
			}

			for (GrandExchangeOffer offer : offers) {
				if (offer == null) {
					continue;
				}
				if (offer.getItemId() == primaryId
						&& offer.getState() == GrandExchangeOfferState.BUYING) {
					pending.add(ir);
					break;
				}
			}
		}
		return pending;
	}

	private void collectPurchasedItemsViaBank(List<ItemRequirement> items) {
		Microbot.status = "Collecting purchased quest items to bank";
		Rs2GrandExchange.collectAllToBank();
		sleep(800, 1200);

		if (Rs2GrandExchange.isOpen()) {
			Rs2GrandExchange.closeExchange();
			sleepUntil(() -> !Rs2GrandExchange.isOpen(), 2_000);
		}

		if (!Rs2Bank.isOpen()) {
			Microbot.status = "Opening bank to retrieve purchased quest items";
			Rs2Bank.walkToBankAndUseBank();
			sleepUntil(Rs2Bank::isOpen, 15_000);
			if (!Rs2Bank.isOpen()) {
				Microbot.log("Quest helper: failed to open bank after Grand Exchange collection", Level.WARN);
				return;
			}
		}

		if (!Rs2Bank.setWithdrawAsItem()) {
			Rs2Bank.closeBank();
			stopQuesterWithReason("Failed to set bank withdraw mode to Item after Grand Exchange collection. Toggle it manually and restart.");
			return;
		}

		for (ItemRequirement ir : items) {
			int qty = remainingQuantityNeeded(ir);
			if (qty <= 0) {
				continue;
			}

			int idToWithdraw = -1;
			for (Integer id : ir.getAllIds()) {
				if (id == null || id <= 0) {
					continue;
				}
				if (Rs2Bank.count(id) > 0) {
					idToWithdraw = id;
					break;
				}
			}

			if (idToWithdraw == -1) {
				continue;
			}

			Microbot.status = "Withdrawing " + ir.getName() + " x" + qty;
			Rs2Bank.withdrawX(idToWithdraw, qty);
			if (!sleepUntil(() -> hasItemRequirementOnPlayer(ir), 2_000)) {
				Microbot.log("Quest helper: purchased item " + ir.getName() + " did not land in inventory after bank withdrawal",
						Level.WARN);
			}
		}

		Rs2Bank.closeBank();
		sleepUntil(() -> !Rs2Bank.isOpen(), 3_000);
	}

	private boolean isItemRequirementTradable(ItemRequirement itemRequirement) {
		return Microbot.getClientThread().runOnClientThreadOptional(() -> {
			for (Integer id : itemRequirement.getAllIds()) {
				if (id == null || id <= 0) {
					continue;
				}
				ItemComposition def = Microbot.getClient().getItemDefinition(id);
				if (def != null && def.isTradeable()) {
					return true;
				}
			}
			return false;
		}).orElse(false);
	}

	private int tradablePrimaryId(ItemRequirement itemRequirement) {
		List<Integer> tradableIds = Microbot.getClientThread().runOnClientThreadOptional(() -> {
			List<Integer> ids = new ArrayList<>();
			for (Integer id : itemRequirement.getAllIds()) {
				if (id == null || id <= 0) {
					continue;
				}
				ItemComposition def = Microbot.getClient().getItemDefinition(id);
				if (def != null && def.isTradeable()) {
					ids.add(id);
				}
			}
			return ids;
		}).orElse(new ArrayList<>());

		if (tradableIds.isEmpty()) {
			return -1;
		}

		// Pick the cheapest tradable variant to avoid league/cosmetic items priced at MAX_VALUE
		int bestId = tradableIds.get(0);
		int bestPrice = Integer.MAX_VALUE;
		for (int id : tradableIds) {
			int price = fetchInstabuyReferencePrice(id);
			if (price > 0 && price < bestPrice) {
				bestPrice = price;
				bestId = id;
			}
		}
		return bestId;
	}

	private int remainingQuantityNeeded(ItemRequirement itemRequirement) {
		int onPlayer = itemRequirement.checkTotalMatchesInContainers(
				QuestContainerManager.getEquippedData(),
				QuestContainerManager.getInventoryData());
		return Math.max(0, itemRequirement.getQuantity() - onPlayer);
	}

	private List<Requirement> collectAllItemRequirements(DetailedQuestStep questStep) {
		List<Requirement> combined = new ArrayList<>(questStep.getRequirements());

		Set<Integer> seenIds = new HashSet<>();
		for (Requirement req : questStep.getRequirements()) {
			if (req instanceof ItemRequirement) {
				seenIds.add(((ItemRequirement) req).getId());
			}
		}

		QuestHelper selectedQuest = getQuestHelperPlugin().getSelectedQuest();
		if (selectedQuest != null) {
			updateEverHeldItemTracking(selectedQuest);

			List<ItemRequirement> questLevel = selectedQuest.getItemRequirements();
			if (questLevel != null) {
				for (ItemRequirement ir : questLevel) {
					if (ir == null) {
						continue;
					}
					if (!seenIds.add(ir.getId())) {
						continue;
					}
					if (everHeldItemRequirementIds.contains(ir.getId())) {
						continue;
					}
					combined.add(ir);
				}
			}
		}

		if (selectedQuest != null
				&& selectedQuest.getQuest() != null
				&& selectedQuest.getQuest().getId() == Quest.VALE_TOTEMS.getId()) {
			combined = applyValeTotemsWoodType(combined);
		}

		return combined;
	}

	private void updateEverHeldItemTracking(QuestHelper selectedQuest) {
		if (selectedQuest == null || selectedQuest.getQuest() == null) {
			return;
		}

		int questId = selectedQuest.getQuest().getId();
		if (questId != heldTrackingQuestId) {
			heldTrackingQuestId = questId;
			everHeldItemRequirementIds.clear();

			QuestState state = null;
			try {
				state = selectedQuest.getQuest().getState(Microbot.getClient());
			} catch (Exception ignored) {
			}

			if (state == QuestState.IN_PROGRESS) {
				List<ItemRequirement> questLevel = selectedQuest.getItemRequirements();
				if (questLevel != null) {
					for (ItemRequirement ir : questLevel) {
						if (ir != null) {
							everHeldItemRequirementIds.add(ir.getId());
						}
					}
				}
			}
		}

		List<ItemRequirement> questLevel = selectedQuest.getItemRequirements();
		if (questLevel == null) {
			return;
		}

		for (ItemRequirement ir : questLevel) {
			if (ir == null) {
				continue;
			}
			if (everHeldItemRequirementIds.contains(ir.getId())) {
				continue;
			}
			if (hasItemRequirementOnPlayer(ir)) {
				everHeldItemRequirementIds.add(ir.getId());
			}
		}
	}

	private boolean shouldObtainMissingItems() {
		if (Rs2Player.isIronman()) {
			return false;
		}
		QuestHelperConfig.ObtainMissingItemsOption option = config.obtainMissingItems();
		if (option == QuestHelperConfig.ObtainMissingItemsOption.YES) {
			return true;
		}
		if (option == QuestHelperConfig.ObtainMissingItemsOption.NO) {
			return false;
		}
		if (obtainItemsSessionChoice != null) {
			return obtainItemsSessionChoice;
		}
		promptObtainMissingItems();
		return false;
	}

	private void promptObtainMissingItems() {
		if (!obtainItemsPromptInFlight.compareAndSet(false, true)) {
			return;
		}

		SwingUtilities.invokeLater(() -> {
			try {
				int choice = JOptionPane.showConfirmDialog(
						null,
						"The quest helper has detected missing items.\n\n" +
								"Would you like the quest helper to automatically obtain\n" +
								"missing items from the bank and Grand Exchange?",
						"Obtain Missing Items",
						JOptionPane.YES_NO_OPTION,
						JOptionPane.QUESTION_MESSAGE);

				obtainItemsSessionChoice = (choice == JOptionPane.YES_OPTION);
			} finally {
				obtainItemsPromptInFlight.set(false);
			}
		});
	}

	private void promptValeTotemsWoodType() {
		if (!valeTotemsPromptInFlight.compareAndSet(false, true)) {
			return;
		}

		stopQuesterWithReason("Vale Totems: pick a wood type in the dialog to continue.");

		SwingUtilities.invokeLater(() -> {
			try {
				QuestHelperConfig.ValeTotemsWoodType[] values = QuestHelperConfig.ValeTotemsWoodType.values();
				List<QuestHelperConfig.ValeTotemsWoodType> selectable = new ArrayList<>();
				for (QuestHelperConfig.ValeTotemsWoodType value : values) {
					if (value != QuestHelperConfig.ValeTotemsWoodType.ASK) {
						selectable.add(value);
					}
				}

				Object[] options = selectable.stream().map(Object::toString).toArray();

				int choice = JOptionPane.showOptionDialog(
						null,
						"Which wood type are you using for Vale Totems?\n\n" +
								"This must match the logs you used to build the totem.\n" +
								"The quester will source the matching logs and decorative items (shields/longbows/shortbows).",
						"Vale Totems - Wood Type",
						JOptionPane.DEFAULT_OPTION,
						JOptionPane.QUESTION_MESSAGE,
						null,
						options,
						options[0]);

				if (choice >= 0 && choice < selectable.size()) {
					QuestHelperConfig.ValeTotemsWoodType selected = selectable.get(choice);
					valeTotemsSessionWoodType = selected;
					Microbot.getConfigManager().setConfiguration(
							QuestHelperConfig.QUEST_HELPER_GROUP, "TurnOn", true);
					Microbot.status = "Vale Totems: using " + selected + " (this session)";
					Microbot.log("Quest helper: Vale Totems wood type set to " + selected
							+ " for this session (config stays on 'Ask me')", Level.INFO);
				}
			} finally {
				valeTotemsPromptInFlight.set(false);
			}
		});
	}

	private List<Requirement> applyValeTotemsWoodType(List<Requirement> requirements) {
		QuestHelperConfig.ValeTotemsWoodType configured = config.valeTotemsWoodType();
		QuestHelperConfig.ValeTotemsWoodType woodType;

		if (configured != null && configured != QuestHelperConfig.ValeTotemsWoodType.ASK) {
			woodType = configured;
		} else {
			woodType = valeTotemsSessionWoodType;
			if (woodType == null) {
				promptValeTotemsWoodType();
				return requirements;
			}
		}

		if (woodType == QuestHelperConfig.ValeTotemsWoodType.OAK) {
			return requirements;
		}

		List<Requirement> transformed = new ArrayList<>(requirements.size());
		for (Requirement req : requirements) {
			if (!(req instanceof ItemRequirement)) {
				transformed.add(req);
				continue;
			}

			ItemRequirement ir = (ItemRequirement) req;
			List<Integer> allIds = ir.getAllIds();

			if (allIds.contains(ItemID.OAK_LOGS)) {
				transformed.add(new ItemRequirement(
						woodType + " log", woodType.getLogId(), ir.getQuantity()));
			} else if (allIds.contains(ItemID.OAK_SHIELD)
					|| allIds.contains(ItemID.OAK_LONGBOW)
					|| allIds.contains(ItemID.OAK_SHORTBOW)
					|| allIds.contains(ItemID.OAK_LONGBOW_U)
					|| allIds.contains(ItemID.OAK_SHORTBOW_U)) {
				transformed.add(new ItemRequirement(
						woodType + " shield/longbow/shortbow",
						woodType.getDecorativeIds(),
						ir.getQuantity()));
			} else {
				transformed.add(req);
			}
		}
		return transformed;
	}

	private String canonicalItemName(int itemId) {
		return Microbot.getClientThread().runOnClientThreadOptional(() -> {
			ItemComposition def = Microbot.getClient().getItemDefinition(itemId);
			return def != null ? def.getName() : null;
		}).orElse(null);
	}

	private int fetchInstabuyReferencePrice(int itemId) {
		WikiPrice priceData = Rs2GrandExchange.getRealTimePrices(itemId);
		if (priceData != null && priceData.buyPrice > 0 && priceData.buyPrice < Integer.MAX_VALUE) {
			return priceData.buyPrice;
		}
		int price = Rs2GrandExchange.getPrice(itemId);
		return (price > 0 && price < Integer.MAX_VALUE) ? price : -1;
	}

	private int notedVariantId(int unnotedId) {
		return Microbot.getClientThread().runOnClientThreadOptional(() -> {
			ItemComposition def = Microbot.getClient().getItemDefinition(unnotedId);
			if (def == null) {
				return -1;
			}
			if (def.getNote() == 799) {
				return -1;
			}
			int linked = def.getLinkedNoteId();
			return linked > 0 ? linked : -1;
		}).orElse(-1);
	}

	private boolean hasNotedVersionInInventory(ItemRequirement itemRequirement) {
		if (remainingQuantityNeeded(itemRequirement) <= 0) {
			return false;
		}
		for (Integer id : itemRequirement.getAllIds()) {
			if (id == null || id <= 0) {
				continue;
			}
			int notedId = notedVariantId(id);
			if (notedId > 0 && Rs2Inventory.itemQuantity(notedId) > 0) {
				return true;
			}
		}
		return false;
	}

	private int inventoryQuantityIncludingNoted(ItemRequirement itemRequirement) {
		int total = 0;
		for (Integer id : itemRequirement.getAllIds()) {
			if (id == null || id <= 0) {
				continue;
			}
			total += Rs2Inventory.itemQuantity(id);
			int notedId = notedVariantId(id);
			if (notedId > 0) {
				total += Rs2Inventory.itemQuantity(notedId);
			}
		}
		return total;
	}

	private boolean unnoteItemsViaBank(List<ItemRequirement> items) {
		if (!Rs2Bank.isOpen()) {
			Microbot.status = "Quest helper: walking to bank to un-note items";
			Rs2Bank.walkToBankAndUseBank();
			sleepUntil(Rs2Bank::isOpen, 15_000);
			if (!Rs2Bank.isOpen()) {
				return true;
			}
		}

		if (!Rs2Bank.setWithdrawAsItem()) {
			Rs2Bank.closeBank();
			stopQuesterWithReason("Failed to set bank withdraw mode to Item while un-noting quest items. Toggle it manually and restart.");
			return true;
		}

		for (ItemRequirement ir : items) {
			int needed = remainingQuantityNeeded(ir);
			if (needed <= 0) {
				continue;
			}

			for (Integer unnotedId : ir.getAllIds()) {
				if (unnotedId == null || unnotedId <= 0) {
					continue;
				}
				int notedId = notedVariantId(unnotedId);
				if (notedId <= 0) {
					continue;
				}

				int notesInInventory = Rs2Inventory.itemQuantity(notedId);
				if (notesInInventory <= 0) {
					continue;
				}

				Microbot.status = "Depositing noted " + ir.getName();
				Rs2Bank.depositAll(notedId);
				sleepUntil(() -> Rs2Inventory.itemQuantity(notedId) == 0, 2_000);

				int toWithdraw = Math.min(notesInInventory, needed);
				Microbot.status = "Withdrawing " + ir.getName() + " x" + toWithdraw;
				Rs2Bank.withdrawX(unnotedId, toWithdraw);
				if (!sleepUntil(() -> hasItemRequirementOnPlayer(ir), 2_000)) {
					Microbot.log("Quest helper: un-note withdrawal for " + ir.getName() + " did not land in time",
							Level.WARN);
				}
				break;
			}
		}

		Rs2Bank.closeBank();
		sleepUntil(() -> !Rs2Bank.isOpen(), 3_000);
		return true;
	}

	private boolean hasMatchingGrandExchangeOffer(ItemRequirement itemRequirement) {
		GrandExchangeOffer[] offers = Microbot.getClient().getGrandExchangeOffers();
		if (offers == null) {
			return false;
		}

		Set<Integer> ids = new HashSet<>();
		for (Integer id : itemRequirement.getAllIds()) {
			if (id != null && id > 0) {
				ids.add(id);
			}
		}

		for (GrandExchangeOffer offer : offers) {
			if (offer == null) {
				continue;
			}
			GrandExchangeOfferState state = offer.getState();
			if ((state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT)
					&& ids.contains(offer.getItemId())) {
				return true;
			}
		}

		return false;
	}

	private void stopQuesterWithReason(String reason) {
		Microbot.status = reason;
		Microbot.log("Quest helper stopped: " + reason, Level.ERROR);
		if (Microbot.getConfigManager() != null) {
			Microbot.getConfigManager().setConfiguration(
					QuestHelperConfig.QUEST_HELPER_GROUP, "TurnOn", false);
		}
	}

	private boolean hasItemRequirementOnPlayer(ItemRequirement itemRequirement) {
		if (itemRequirement.mustBeEquipped()) {
			return itemRequirement.checkContainers(QuestContainerManager.getEquippedData());
		}

		return itemRequirement.checkContainers(
				QuestContainerManager.getEquippedData(),
				QuestContainerManager.getInventoryData());
	}

	private boolean attemptToAcquireRequirementItem(DetailedQuestStep questStep, ItemRequirement itemRequirement) {
		notifyMissingRequirement(itemRequirement);

		WorldPoint worldPoint = questStep.getDefinedPoint() != null ? questStep.getDefinedPoint().getWorldPoint() : null;
		int targetItemId = itemRequirement.getAllIds().stream().findFirst().orElse(itemRequirement.getId());

		if (worldPoint != null) {
			if ((Rs2Walker.canReach(worldPoint) && worldPoint.distanceTo(Rs2Player.getWorldLocation()) < 2)
					|| worldPoint.toWorldArea().hasLineOfSightTo(Microbot.getClient().getTopLevelWorldView(), Rs2Player.getWorldLocation().toWorldArea())
					&& Rs2Camera.isTileOnScreen(LocalPoint.fromWorld(Microbot.getClient().getTopLevelWorldView(), worldPoint))) {
				lootGroundItem(targetItemId, 10);
			} else {
				Rs2Walker.walkTo(worldPoint, 2);
			}
		} else {
			lootGroundItem(targetItemId, 20);
		}

		return true;
	}

	private void notifyMissingRequirement(ItemRequirement itemRequirement) {
		int key = itemRequirement.getAllIds().stream().findFirst().orElse(itemRequirement.getId());
		long now = System.currentTimeMillis();
		Long lastNotified = lastMissingRequirementNotice.get(key);

		if (lastNotified != null && now - lastNotified < MISSING_REQUIREMENT_NOTIFY_INTERVAL_MS) {
			return;
		}

		lastMissingRequirementNotice.put(key, now);

		String itemName = itemRequirement.getName() != null && !itemRequirement.getName().isEmpty()
				? itemRequirement.getName()
				: "Item " + key;
		int quantity = Math.max(itemRequirement.getQuantity(), 1);

		Microbot.status = "Missing: " + itemName;
		Microbot.log(String.format("Quest helper missing required item: %s x%d", itemName, quantity), Level.WARN);
	}

	private boolean lootGroundItem(int itemId, int radius) {
		Rs2TileItemModel item = new Rs2TileItemQueryable()
				.withId(itemId)
				.within(radius)
				.nearest();

		if (item == null) {
			return false;
		}

		return item.click("");
	}

	@Override
	public void shutdown() {
		super.shutdown();
        clearInteractionState();
		reset();
	}

    public static void reset() {
        interactionResetGeneration.incrementAndGet();
        itemsMissing = new ArrayList<>();
        itemRequirements = new ArrayList<>();
        grandExchangeItems = new ArrayList<>();
        lastMissingRequirementNotice.clear();
        valeTotemsPromptInFlight.set(false);
        valeTotemsSessionWoodType = null;
        obtainItemsPromptInFlight.set(false);
        obtainItemsSessionChoice = null;
    }

    public boolean applyStep(QuestStep step) {
        if (step == null) return false;

        if (step instanceof ObjectStep) {
            return applyObjectStep((ObjectStep) step);
        } else if (step instanceof NpcStep) {
            return applyNpcStep((NpcStep) step);
        } else if (step instanceof WidgetStep) {
            return applyWidgetStep((WidgetStep) step);
        } else if (step instanceof DigStep) {
            return applyDigStep((DigStep) step);
        } else if (step instanceof PuzzleStep) {
            return applyPuzzleStep((PuzzleStep) step);
        } else if (step instanceof DetailedQuestStep) {
            return applyDetailedQuestStep((DetailedQuestStep) step);
        }
        return true;
    }

    private boolean executeQuestCustomLogic() {
        var questLogic = QuestRegistry.getQuest(getQuestHelperPlugin().getSelectedQuest().getQuest().getId());
        if (questLogic instanceof PiratesTreasure) ((PiratesTreasure) questLogic).setMQuestPlugin(mQuestPlugin);
        return questLogic == null || questLogic.executeCustomLogic();
    }

    public void onGraphicsObjectCreated(GraphicsObject graphicsObject) {
        if (graphicsObject == null || getQuestHelperPlugin() == null
                || getQuestHelperPlugin().getSelectedQuest() == null) {
            return;
        }
        var questLogic = QuestRegistry.getQuest(
                getQuestHelperPlugin().getSelectedQuest().getQuest().getId());
        if (questLogic != null && questLogic.onGraphicsObjectCreated(graphicsObject)) {
            nextCustomAttemptAt = 0;
        }
    }

    private boolean runIdleCustomLogic(QuestStep step) {
        long now = System.nanoTime();
        if (lastCustomStep == step && now - nextCustomAttemptAt < 0) return !customActionPending;
        lastCustomStep = step;
        nextCustomAttemptAt = now + 600_000_000L;
        customActionPending = !executeQuestCustomLogic();
        return !customActionPending;
    }

    private void clearInteractionState() {
        pendingInteraction = null;
        unreachableTarget = false;
        lastCustomStep = null;
        customActionPending = false;
        targetReadyAt = 0;
        dialogueStartedStep = null;
        dialogueAdvance.reset();
        dialogueContinuation.reset();
        QuestRegistry.resetAll();
    }

    private boolean canDispatchQuestStep(QuestStep step) {
        return pendingInteraction == null && isCurrentQuestStep(step) && !Rs2Player.isAnimating()
                && !Rs2Dialogue.isInDialogue();
    }

    private boolean isCurrentQuestStep(QuestStep step) {
        if (Thread.currentThread().isInterrupted() || InputArbiter.isHuman() || Microbot.pauseAllScripts.get()
                || !Microbot.isLoggedIn() || config == null || !config.startStopQuestHelper()
                || observedResetGeneration != interactionResetGeneration.get()
                || mainScheduledFuture == null || mainScheduledFuture.isCancelled()) return false;
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            QuestHelperPlugin plugin = getQuestHelperPlugin();
            return plugin != null && plugin.getSelectedQuest() != null
                    && plugin.getSelectedQuest().getCurrentStep() != null
                    && plugin.getSelectedQuest().getCurrentStep().getActiveStep() == step
                    && Microbot.getVarbitValue(4606) == 0;
        }).orElse(false);
    }

    private Rs2NpcModel findQuestNpc(NpcStep step) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            WorldPoint player = scenePlayerLocation();
            if (player == null) return null;
            List<Rs2NpcModel> live = Microbot.getRs2NpcCache().getStream()
                    .filter(npc -> step.getNpcs().stream().anyMatch(highlight -> highlight == npc.getActor()))
                    .collect(Collectors.toList());
            return live.stream().min(Comparator
                    .comparingInt((Rs2NpcModel npc) -> step.isAllowMultipleHighlights()
                            && npcsHandled.contains(npc.getIndex()) ? 1 : 0)
                    .thenComparingInt(npc -> npc.getWorldLocation().distanceTo(player))).orElse(null);
        }).orElse(null);
    }

    private TileObject findQuestObject(ObjectStep step) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            WorldPoint player = scenePlayerLocation();
            if (player == null) return null;
            return Microbot.getRs2TileObjectCache().getStream()
                    .filter(object -> step.getObjects().stream().anyMatch(highlight -> highlight == object.getTileObject()))
                    .min(Comparator.comparingInt((Rs2TileObjectModel object) -> objectsHandeled.contains(object.getHash()) ? 1 : 0)
                            .thenComparingInt(object -> object.getWorldLocation().distanceTo(player)))
                    .map(Rs2TileObjectModel::getTileObject).orElse(null);
        }).orElse(null);
    }

    private boolean isCurrentInteractionReady(QuestStep step) {
        if (!isCurrentQuestStep(step) || unreachableTarget) {
            targetReadyAt = 0;
            return false;
        }
        boolean ready = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            WorldPoint player = scenePlayerLocation();
            if (player == null) return false;
            if (step instanceof NpcStep) {
                Rs2NpcModel npc = findQuestNpc((NpcStep) step);
                if (npc == null || npc.getLocalLocation() == null || npc.getWorldLocation().distanceTo(player) > 8
                        || !Rs2Camera.isTileOnScreen(npc.getLocalLocation()) || !npc.hasLineOfSight()) return false;
                // Local collision flood does not plan routes through closed doors.
                if (!QuestLocalApproach.reachable(Microbot.getClient().getTopLevelWorldView(), player, 8).contains(npc.getWorldLocation())) return false;
                if (step instanceof NpcEmoteStep || ((NpcStep) step).getIconItemID() != -1) return true;
                NPCComposition composition = Microbot.getClient().getNpcDefinition(npc.getId());
                if (composition != null && composition.getConfigs() != null) composition = composition.transform();
                String action = chooseCorrectNPCOption(step, npc);
                return composition != null && composition.getActions() != null
                        && java.util.Arrays.stream(composition.getActions()).anyMatch(action::equalsIgnoreCase);
            }
            if (step instanceof ObjectStep) {
                TileObject tile = findQuestObject((ObjectStep) step);
                if (tile == null) return false;
                Rs2TileObjectModel object = new Rs2TileObjectModel(tile);
                if (object.getWorldLocation().distanceTo(player) > 8 || object.getLocalLocation() == null
                        || !(Rs2Camera.isTileOnScreen(object.getLocalLocation()) || object.getCanvasLocation() != null)
                        || !QuestObjectGeometry.hasLineOfSight(Microbot.getClient().getTopLevelWorldView(), player, tile)) return false;
                WorldArea area = QuestObjectGeometry.area(Microbot.getClient().getTopLevelWorldView(), tile);
                Set<WorldPoint> reachable = QuestLocalApproach.reachable(Microbot.getClient().getTopLevelWorldView(), player, 8);
                if (!QuestLocalApproach.adjacentReachable(area, reachable)) return false;
                if (selectObjectApproachTile(area, player, reachable::contains,
                        point -> QuestObjectGeometry.hasLineOfSight(Microbot.getClient().getTopLevelWorldView(), point, tile)) == null) return false;
                return ((ObjectStep) step).getIconItemID() != -1 || !chooseCorrectObjectOption(step, object).isEmpty();
            }
            return false;
        }).orElse(false);
        if (ready && targetReadyAt == 0) {
            targetReadyAt = System.nanoTime();
            log.debug("[QuestInteraction] phase=target_ready attempt={} step={}", interactionSequence + 1,
                    step.getClass().getSimpleName());
        } else if (!ready) {
            targetReadyAt = 0;
        }
        return ready;
    }

    private WalkerState walkToInteraction(QuestStep step, WorldPoint target, int distance) {
        long started = System.nanoTime();
        WalkerState result = Rs2Walker.walkWithStateUntil(target, distance,
                () -> !isCurrentQuestStep(step) || isCurrentInteractionReady(step));
        if (result == WalkerState.ARRIVED) unreachableTarget = false;
        log.debug("[QuestInteraction] phase=walk_return step={} target={} result={} elapsed={}ms",
                step.getClass().getSimpleName(), target, result, (System.nanoTime() - started) / 1_000_000L);
        return result;
    }

    private boolean allowDialogueAdvance() {
        QuestDialogueContinuation.Prompt prompt = readyDialoguePrompt();
        // Do not consume the shared option/custom-logic gate while the click gate is waiting.
        if (prompt != null && !dialogueContinuation.canAdvance(prompt,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime()))) return false;
        Object page = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            List<Widget> roots = new ArrayList<>();
            int[] groups = {193, 217, 219, 229, 231, 11};
            for (int group : groups) {
                // Some dialogue groups have multiple independent roots (e.g. item messages).
                for (int child = 0; child < 20; child++) {
                    Widget root = Microbot.getClient().getWidget(group, child);
                    if (root != null) roots.add(root);
                }
            }
            Widget chatboxContinue = Microbot.getClient().getWidget(162, 44);
            if (chatboxContinue != null) roots.add(chatboxContinue);
            return QuestDialoguePage.snapshot(roots, Rs2Dialogue.hasContinue());
        }).orElse(null);
        return page != null && dialogueAdvance.shouldAdvance(page, System.nanoTime());
    }

    private void beginInteraction(QuestStep step, int id, long identity, WorldPoint location,
                                  String action, int itemId, int quantity) {
        long now = System.nanoTime();
        pendingInteraction = new PendingInteraction(step, id, identity, location, action, itemId, quantity,
                ++interactionSequence, now);
        traceInteraction("dispatch", pendingInteraction, "accepted");
        log.debug("[QuestInteraction] phase=dispatch_timing attempt={} readyToDispatch={}ms",
                interactionSequence, targetReadyAt == 0 ? -1 : (now - targetReadyAt) / 1_000_000L);
        targetReadyAt = 0;
    }

    private void traceInteraction(String phase, PendingInteraction pending, String reason) {
        log.debug("[QuestInteraction] phase={} attempt={} step={} id={} location={} action={} elapsed={}ms reason={}",
                phase, pending.sequence, pending.step.getClass().getSimpleName(), pending.id, pending.location,
                pending.action, pending.attempt.elapsedMillis(System.nanoTime()), reason);
    }

    private void observePendingInteraction(QuestStep currentStep) {
        PendingInteraction pending = pendingInteraction;
        if (pending == null) return;
        if (pending.step != currentStep) {
            traceInteraction("complete", pending, "step-changed");
            pendingInteraction = null;
            return;
        }
        boolean dialogue = Rs2Dialogue.isInDialogue();
        boolean itemChanged = pending.itemId != -1 && Rs2Inventory.itemQuantity(pending.itemId) != pending.quantity;
        boolean valid = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            WorldPoint player = Rs2Player.getWorldLocation();
            if (player == null || player.getPlane() != pending.location.getPlane()) return false;
            if (pending.step instanceof NpcStep) {
                return Microbot.getRs2NpcCache().getStream()
                        .anyMatch(npc -> npc.getId() == pending.id && npc.getIndex() == pending.identity);
            }
            return Microbot.getRs2TileObjectCache().getStream()
                    .anyMatch(object -> object.getId() == pending.id && object.getHash() == pending.identity);
        }).orElse(false);
        boolean animating = Rs2Player.isAnimating();
        boolean nearTarget = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            WorldPoint player = scenePlayerLocation();
            return player != null && player.distanceTo(pending.location) <= 2;
        }).orElse(false);
        pending.animationSeen |= animating && nearTarget && !Rs2Player.isMoving();
        boolean widgetResult = Rs2Shop.isOpen() || Rs2Widget.isProductionWidgetOpen();
        boolean busy = Rs2Player.isMoving() || animating || dialogue || Rs2Combat.inCombat();
        boolean actionFinished = pending.attempt.isAnimationSettled(System.nanoTime(), busy, pending.animationSeen);
        boolean progressed = pending.attempt.hasProgressed();
        // Dialogue confirms dispatch, but keep ownership until it closes (and a short acknowledgement
        // window afterwards) so a not-yet-updated step cannot immediately reopen the conversation.
        if (dialogue && !pending.dialogueSeen) {
            pending.dialogueSeen = true;
            traceInteraction("progress", pending, "dialogue");
            if (pending.step instanceof NpcStep && ((NpcStep) pending.step).isAllowMultipleHighlights()) {
                npcsHandled.add((int) pending.identity);
            } else if (pending.step instanceof ObjectStep) {
                objectsHandeled.add(pending.identity);
            }
        }
        QuestInteractionAttempt.Result result = pending.attempt.observe(System.nanoTime(), busy, itemChanged || actionFinished || widgetResult, valid);
        if (!progressed && pending.attempt.hasProgressed() && !dialogue) traceInteraction("progress", pending, "activity");
        if (result == QuestInteractionAttempt.Result.COMPLETE) {
            if (pending.step instanceof ObjectStep) objectsHandeled.add(pending.identity);
            traceInteraction("complete", pending, itemChanged ? "item-changed" : widgetResult ? "widget-opened" : "animation-finished");
        } else if (result == QuestInteractionAttempt.Result.RETRY) {
            traceInteraction("timeout", pending, "revalidate");
        } else if (result == QuestInteractionAttempt.Result.INVALIDATED) {
            traceInteraction("invalidated", pending, "target-unavailable");
        }
        if (result != QuestInteractionAttempt.Result.WAIT) pendingInteraction = null;
    }

    private static final class PendingInteraction {
        private final QuestStep step;
        private final int id;
        private final long identity;
        private final WorldPoint location;
        private final String action;
        private final int itemId;
        private final int quantity;
        private final long sequence;
        private final QuestInteractionAttempt attempt;
        private boolean dialogueSeen;
        private boolean animationSeen;

        private PendingInteraction(QuestStep step, int id, long identity, WorldPoint location, String action,
                                   int itemId, int quantity, long sequence, long now) {
            this.step = step;
            this.id = id;
            this.identity = identity;
            this.location = location;
            this.action = action;
            this.itemId = itemId;
            this.quantity = quantity;
            this.sequence = sequence;
            this.attempt = new QuestInteractionAttempt(now, now + 1_200_000_000L);
        }
    }

    public boolean applyNpcStep(NpcStep step) {
        return QuestInteractionFlow.run(() -> canDispatchQuestStep(step),
                () -> isCurrentInteractionReady(step), () -> {
                    Rs2NpcModel npc = findQuestNpc(step);
                    WorldPoint target = npc == null ? step.getDefinedPoint().getWorldPoint() : routeLocation(npc.getWorldLocation());
                    return walkToInteraction(step, target, 2);
                }, () -> dispatchNpcStep(step));
    }

    private boolean dispatchNpcStep(NpcStep step) {
        Rs2NpcModel npc = findQuestNpc(step);
        if (npc != null && canDispatchQuestStep(step) && isCurrentInteractionReady(step)) {
            Rs2Walker.clearWalkingRoute("quest-helper:npc-step-visible-interact");

            if (step.getText().stream().anyMatch(x -> x.toLowerCase().contains("kill"))) {
                if (!Rs2Combat.inCombat() && npc.click("Attack")) {
                    beginInteraction(step, npc.getId(), npc.getIndex(), npc.getWorldLocation(), "Attack", -1, 0);
                    return true;
                }
                return false;
            }

            if (step instanceof NpcEmoteStep) {
                var emoteStep = (NpcEmoteStep) step;

                for (Widget emoteWidget : Rs2Widget.getWidget(ComponentID.EMOTES_EMOTE_CONTAINER).getDynamicChildren()) {
                    if (emoteWidget.getSpriteId() == emoteStep.getEmote().getSpriteId()) {
                        var id = emoteWidget.getOriginalX() / 42 + ((emoteWidget.getOriginalY() - 6) / 49) * 4;

                        Microbot.doInvoke(new NewMenuEntry()
                                        .option("Perform")
                                        .target(emoteWidget.getText())
                                        .identifier(1)
                                        .type(MenuAction.CC_OP)
                                        .param0(id)
                                        .param1(ComponentID.EMOTES_EMOTE_CONTAINER)
                                , new Rectangle(0, 0, 1, 1));

                        Rs2Player.waitForAnimation();

                        if (Rs2Dialogue.isInDialogue())
                            return false;
                    }
                }
            }

            int itemId = step.getIconItemID();
            int initialQuantity = itemId == -1 ? 0 : Rs2Inventory.itemQuantity(itemId);
            String action = itemId == -1 ? chooseCorrectNPCOption(step, npc) : "Use";
            if (itemId != -1) {
                if (!Rs2Inventory.use(itemId) || !waitForSelectedInventoryItem(itemId)) {
                    return false;
                }
                Rs2NpcModel freshNpc = findQuestNpc(step);
                if (!canDispatchQuestStep(step) || !isCurrentInteractionReady(step)
                        || freshNpc == null || freshNpc.getId() != npc.getId() || freshNpc.getIndex() != npc.getIndex()) {
                    return false;
                }
                npc = freshNpc;
                if (!npc.click("")) {
                    return false;
                }
            } else {
                if (!npc.click(action)) return false;
            }
            beginInteraction(step, npc.getId(), npc.getIndex(), npc.getWorldLocation(), action, itemId, initialQuantity);
            return true;
        }
        return false;
    }


    public boolean applyObjectStep(ObjectStep step) {
        return QuestInteractionFlow.run(() -> canDispatchQuestStep(step),
                () -> isCurrentInteractionReady(step), () -> routeToObjectStep(step),
                () -> dispatchObjectStep(step));
    }

    private WalkerState routeToObjectStep(ObjectStep step) {
        TileObject tileObject = findQuestObject(step);
        if (tileObject == null) return walkToInteraction(step, step.getDefinedPoint().getWorldPoint(), 1);
        WorldPoint location = tileObject == null ? step.getDefinedPoint().getWorldPoint()
                : new Rs2TileObjectModel(tileObject).getWorldLocation();
        if (location == null) return WalkerState.EXIT;
        WorldPoint target = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            WorldPoint player = scenePlayerLocation();
            if (player == null) return null;
            WorldArea area = QuestObjectGeometry.area(Microbot.getClient().getTopLevelWorldView(), tileObject);
            WorldPoint adjacent = selectObjectApproachTile(area, player, this::isSceneTileWalkable,
                    point -> QuestObjectGeometry.hasLineOfSight(Microbot.getClient().getTopLevelWorldView(), point, tileObject));
            if (adjacent != null) return adjacent;
            for (int radius = 1; radius <= 10; radius++) {
                WorldPoint approach = new WorldArea(location.dx(-radius).dy(-radius), radius * 2 + 1, radius * 2 + 1).toWorldPointList().stream()
                        .filter(this::isSceneTileWalkable)
                        .filter(point -> QuestObjectGeometry.hasLineOfSight(Microbot.getClient().getTopLevelWorldView(), point, tileObject))
                        .min(Comparator.comparingInt(point -> point.distanceTo(player))).orElse(null);
                if (approach != null) return approach;
            }
            return location;
        }).orElse(location);
        // The full walker still owns walls, doors, transports and stuck recovery.
        return walkToInteraction(step, tileObject == null ? target : routeLocation(target), tileObject == null ? 1 : 0);
    }

    private boolean dispatchObjectStep(ObjectStep step) {
        TileObject tileObject = findQuestObject(step);
        if (tileObject == null || !canDispatchQuestStep(step) || !isCurrentInteractionReady(step)) return false;
        Rs2TileObjectModel object = new Rs2TileObjectModel(tileObject);
        int itemId = step.getIconItemID();
        int initialQuantity = itemId == -1 ? 0 : Rs2Inventory.itemQuantity(itemId);
        String action = itemId == -1 ? chooseCorrectObjectOption(step, object) : "Use";
        Rs2Walker.clearWalkingRoute("quest-helper:object-step-interact");
        boolean interacted;
        if (itemId == -1) {
            interacted = Rs2GameObject.interact(tileObject, action);
        } else {
            if (!Rs2Inventory.use(itemId) || !waitForSelectedInventoryItem(itemId)) return false;
            TileObject freshObject = findQuestObject(step);
            if (!canDispatchQuestStep(step) || !isCurrentInteractionReady(step) || freshObject == null
                    || freshObject.getHash() != object.getHash() || freshObject.getId() != object.getId()) return false;
            tileObject = freshObject;
            interacted = Rs2GameObject.interact(tileObject, "Use");
        }
        if (!interacted) return false;
        beginInteraction(step, object.getId(), object.getHash(), object.getWorldLocation(), action, itemId, initialQuantity);
        return true;
    }

    static boolean isAwayFromObjectStep(WorldPoint playerLocation, WorldPoint stepLocation) {
        if (playerLocation == null || stepLocation == null) {
            return false;
        }
        return playerLocation.getPlane() != stepLocation.getPlane()
                || playerLocation.distanceTo2D(stepLocation) > 1;
    }

    static boolean pathEndsNearObjectStep(List<WorldPoint> path, WorldPoint stepLocation) {
        if (path == null || path.isEmpty() || stepLocation == null) {
            return false;
        }
        WorldPoint endpoint = path.get(path.size() - 1);
        return endpoint != null && endpoint.distanceTo(stepLocation) <= 1;
    }

    static boolean canCompleteObjectStep(boolean objectAvailable) {
        return objectAvailable;
    }

    static boolean shouldWalkToObjectApproach(boolean objectAvailable, boolean moreThanOneTileFromStep,
                                              boolean objectInLineOfSight) {
        return objectAvailable
                ? !objectInLineOfSight
                : moreThanOneTileFromStep;
    }

    static WorldPoint selectObjectApproachTile(WorldArea objectArea, WorldPoint playerLocation,
                                               Predicate<WorldPoint> isWalkable,
                                               Predicate<WorldPoint> hasLineOfSight) {
        if (objectArea == null || playerLocation == null) {
            return null;
        }

        WorldArea surroundingArea = new WorldArea(
                objectArea.getX() - 1,
                objectArea.getY() - 1,
                objectArea.getWidth() + 2,
                objectArea.getHeight() + 2,
                objectArea.getPlane());

        return surroundingArea.toWorldPointList().stream()
                .filter(point -> !objectArea.contains(point))
                .filter(isWalkable)
                .filter(hasLineOfSight)
                .min(Comparator.comparingInt(point -> point.distanceTo2D(playerLocation)))
                .orElse(null);
    }

    private static boolean waitForSelectedInventoryItem(int expectedItemId) {
        return sleepUntil(() -> isInventoryItemSelected(expectedItemId), 1_500);
    }

    private static boolean isInventoryItemSelected(int expectedItemId) {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> {
                    Widget selectedWidget = Microbot.getClient().getSelectedWidget();
                    int selectedItemId = selectedWidget == null ? -1 : selectedWidget.getItemId();
                    return isExpectedInventoryItemSelected(
                            Microbot.getClient().isWidgetSelected(), selectedItemId, expectedItemId);
                })
                .orElse(false);
    }

    static boolean isExpectedInventoryItemSelected(boolean widgetSelected, int selectedItemId,
                                                   int expectedItemId) {
        return widgetSelected && selectedItemId == expectedItemId;
    }

    private boolean applyDigStep(DigStep step) {
        if (!Rs2Walker.walkTo(step.getDefinedPoint().getWorldPoint()))
            return false;
        else if (!Rs2Player.getWorldLocation().equals(step.getDefinedPoint().getWorldPoint()))
            Rs2Walker.walkFastCanvas(step.getDefinedPoint().getWorldPoint());
        else {
            Rs2Inventory.interact(ItemID.SPADE, "Dig");
            return true;
        }

        return false;
    }

    private boolean applyPuzzleStep(PuzzleStep step) {
        if (!step.getHighlightedButtons().isEmpty()) {
            var widgetDetails = step.getHighlightedButtons().stream().filter(x -> Rs2Widget.isWidgetVisible(x.groupID, x.childID)).findFirst().orElse(null);
            if (widgetDetails != null) {
                Rs2Widget.clickWidget(widgetDetails.groupID, widgetDetails.childID);
                return true;
            }
        }

        return false;
    }

    private String chooseCorrectObjectOption(QuestStep step, Rs2TileObjectModel object) {
        String[] actions = object.getActions();
        if (actions == null) return "";

        for (var action : actions) {
            if (action != null && step.getText().stream().anyMatch(x -> x.toLowerCase().contains(action.toLowerCase())))
                return action;
        }

        // Fallback: first non-empty action (the object's default left-click).
        for (var action : actions) {
            if (action != null && !action.isEmpty())
                return action;
        }

        return "";
    }

    private String chooseCorrectNPCOption(QuestStep step, Rs2NpcModel npc) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            NPCComposition composition = Microbot.getClient().getNpcDefinition(npc.getId());
            if (composition != null && composition.getConfigs() != null) composition = composition.transform();
            boolean shopStep = step.getWidgetsToHighlight().stream()
                    .filter(WidgetHighlight.class::isInstance).map(WidgetHighlight.class::cast)
                    .anyMatch(highlight -> highlight.getItemIdRequirement() != null);
            return chooseNpcAction(step.getText(), composition == null ? null : composition.getActions(), shopStep);
        }).orElse("");
    }

    static String chooseNpcAction(List<String> stepText, String[] actions, boolean shopStep) {
        if (actions == null) {
            return "Talk-to";
        }

        if (shopStep) {
            for (String action : actions) {
                if ("Trade".equalsIgnoreCase(action)) {
                    return action;
                }
            }
        }

        for (String action : actions) {
            if (action != null && stepText.stream()
                    .anyMatch(text -> text.toLowerCase().contains(action.toLowerCase()))) {
                return action;
            }
        }

        String fallback = null;
        for (String action : actions) {
            if (action == null || action.isEmpty()) {
                continue;
            }
            if ("Talk-to".equalsIgnoreCase(action)) {
                return action;
            }
            if (fallback == null) {
                fallback = action;
            }
        }
        return fallback != null ? fallback : "Talk-to";
    }

    static int firstMissingShopItemId(List<Integer> itemIds, IntPredicate hasItem) {
        return itemIds.stream()
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .filter(itemId -> !hasItem.test(itemId))
                .findFirst()
                .orElse(-1);
    }

    static Widget findVisibleWidgetByItemId(Widget widget, int itemId) {
        if (widget == null || widget.isHidden()) {
            return null;
        }
        if (widget.getItemId() == itemId) {
            return widget;
        }

        Widget[][] childGroups = {
                widget.getChildren(),
                widget.getNestedChildren(),
                widget.getDynamicChildren(),
                widget.getStaticChildren()
        };
        for (Widget[] childGroup : childGroups) {
            if (childGroup == null) {
                continue;
            }
            for (Widget child : childGroup) {
                Widget match = findVisibleWidgetByItemId(child, itemId);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

	private String chooseCorrectItemOption(QuestStep step, int itemId) {
		var actions = Rs2Inventory.get(itemId).getInventoryActions();

		for (var action : actions) {
			if (action != null && step.getText().stream().anyMatch(x -> x.toLowerCase().contains(action.toLowerCase())))
				return action;
		}

		// Fallback: first non-empty inventory action (the item's default left-click).
		for (var action : actions) {
			if (action != null && !action.isEmpty())
				return action;
		}

		return "use";
	}

    private boolean applyDetailedQuestStep(DetailedQuestStep conditionalStep) {
        if (conditionalStep instanceof NpcStep) return false;

        WorldPoint stepWorldPoint = conditionalStep.getDefinedPoint() == null
                ? null
                : conditionalStep.getDefinedPoint().getWorldPoint();

        if (conditionalStep.getIconItemID() != -1
                && stepWorldPoint != null
                && !stepWorldPoint.toWorldArea().hasLineOfSightTo(Microbot.getClient().getTopLevelWorldView(), Rs2Player.getWorldLocation())) {
            if (Rs2Tile.areSurroundingTilesWalkable(stepWorldPoint, 1, 1)) {
                WorldPoint nearestUnreachableWalkableTile = Rs2Tile.getNearestWalkableTileWithLineOfSight(stepWorldPoint);
                if (nearestUnreachableWalkableTile != null) {
                    return Rs2Walker.walkTo(nearestUnreachableWalkableTile, 0);
                }
            }
        }

        boolean usingItems = false;
        for (Requirement requirement : conditionalStep.getRequirements()) {
            if (requirement instanceof ItemRequirement) {
                ItemRequirement itemRequirement = (ItemRequirement) requirement;

				if (itemRequirement.shouldHighlightInInventory(Microbot.getClient())
						&& Rs2Inventory.contains(itemRequirement.getAllIds().stream().mapToInt(i -> i).toArray())) {
					var itemId = itemRequirement.getAllIds().stream().filter(Rs2Inventory::contains).findFirst().orElse(-1);
					Rs2Inventory.interact(itemId, chooseCorrectItemOption(conditionalStep, itemId));
					sleep(100, 200);
					usingItems = true;
					continue;
				}

				if (!hasItemRequirementOnPlayer(itemRequirement)) {
					return attemptToAcquireRequirementItem(conditionalStep, itemRequirement);
				}
			}
		}

        if (!usingItems && stepWorldPoint != null && !Rs2Walker.walkTo(stepWorldPoint))
            return true;

		if (conditionalStep.getIconItemID() != -1 && stepWorldPoint != null
				&& stepWorldPoint.toWorldArea().hasLineOfSightTo(Microbot.getClient().getTopLevelWorldView(), Rs2Player.getWorldLocation())) {
			if (conditionalStep.getQuestHelper().getQuest() == QuestHelperQuest.ZOGRE_FLESH_EATERS) {
				if (conditionalStep.getIconItemID() == 4836) { // strange potion
					lootGroundItem(ItemID.CUP_OF_TEA_4838, 20);
				}
			}
		}

		return usingItems;
	}

    private boolean applyWidgetStep(WidgetStep step) {
        var widgetDetails = step.getWidgetDetails().get(0);
        var widget = Microbot.getClient().getWidget(widgetDetails.groupID, widgetDetails.childID);

        if (widgetDetails.childChildID != -1) {
            var tmpWidget = widget.getChild(widgetDetails.childChildID);

            if (tmpWidget != null)
                widget = tmpWidget;
        }

        return Rs2Widget.clickWidget(widget.getId());
    }

    protected QuestHelperPlugin getQuestHelperPlugin() {
        return (QuestHelperPlugin) Microbot.getPluginManager().getPlugins().stream().filter(x -> x instanceof QuestHelperPlugin).findFirst().orElse(null);
    }

    public void onChatMessage(ChatMessage chatMessage) {
        if (chatMessage.getMessage().equalsIgnoreCase("I can't reach that!"))
            unreachableTarget = true;
    }
}
