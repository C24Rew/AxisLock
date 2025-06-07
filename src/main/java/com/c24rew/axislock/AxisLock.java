package com.c24rew.axislock;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AxisLock implements ClientModInitializer {

    public static final String MOD_ID = "axislock";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleModKey;
    private static KeyBinding cycleAxisKey;
    private static KeyBinding holdAxisLockKey;
    private static KeyBinding ReferencePointKey;

    private static KeyBinding selectAxisXKey;
    private static KeyBinding selectAxisYKey;
    private static KeyBinding selectAxisZKey;

    private static boolean modEnabled = false;
    private BlockPos manualReferencePoint = null; // Used for manual reference point setting
    private static boolean holdAxisLockActive = false; // Tracks if the hold-to-lock key is currently pressed
    private static Axis currentSelectedAxis = Axis.Y;
    private static BlockPos firstBlockPosInSequence = null;
    // True when the first block in a locked-axis sequence is placed and the place key is held
    private static boolean isPlacingSequenceActive = false;

    @Override
    public void onInitializeClient() {
        // Register Keybindings
        toggleModKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.axislock.toggle", // Translation key for toggle functionality
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.axislock.keys"
        ));

        cycleAxisKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.axislock.cycle_axis", // Translation key for cycling through axes
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,
                "category.axislock.keys"
        ));

        ReferencePointKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.axislock.set_reference_point", // Translation key
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.axislock.keys"
        ));

        // Not bounded keys
        holdAxisLockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.axislock.hold", // Translation key for hold-to-lock functionality
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(), // Unbound by default
                "category.axislock.keys"
        ));

        selectAxisXKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.axislock.select_x", // Translation key
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(), // Unbound by default
                "category.axislock.keys"
        ));

        selectAxisYKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.axislock.select_y", // Translation key
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(), // Unbound by default
                "category.axislock.keys"
        ));

        selectAxisZKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.axislock.select_z", // Translation key
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(), // Unbound by default
                "category.axislock.keys"
        ));


        // Register client tick event for handling key presses and placement logic
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // Register event for intercepting block placement attempts
        // UseBlockCallback is fired when a player right-clicks a block, suitable for pre-placement checks
        UseBlockCallback.EVENT.register(this::onBlockPlaceAttempt);
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null) {
            return;
        }

        boolean previousHoldState = holdAxisLockActive;
        holdAxisLockActive = holdAxisLockKey.isPressed();

        // Handle Hold Axis Lock state changes and feedback messages
        if (holdAxisLockActive && !previousHoldState) {
            client.player.sendMessage(
                    Text.translatable("axislock.hold.enabled",
                            currentSelectedAxis.toString()).formatted(Formatting.GREEN),
                    true
            );
        } else if (!holdAxisLockActive && previousHoldState) {
            client.player.sendMessage(
                    Text.translatable("axislock.hold.disabled").formatted(Formatting.RED),
                    true
            );
            // If hold key is released and toggle mode is also off, reset the placement sequence
            if (!modEnabled) {
                isPlacingSequenceActive = false;
                firstBlockPosInSequence = null;
            }
        }

        // Handle Toggle Mod Key (only if the Hold key is NOT pressed)
        if (!holdAxisLockActive) {
            while (toggleModKey.wasPressed()) {
                modEnabled = !modEnabled;
                if (modEnabled) {
                    client.player.sendMessage(
                            Text.translatable("axislock.toggle.enabled",
                                    currentSelectedAxis.toString()).formatted(Formatting.GREEN),
                            true
                    );
                } else {
                    client.player.sendMessage(
                            Text.translatable("axislock.toggle.disabled").formatted(Formatting.RED),
                            true
                    );
                }
                // If mod is disabled (and hold is not active), reset the placement sequence
                if (!modEnabled) {
                    isPlacingSequenceActive = false;
                    firstBlockPosInSequence = null;
                }
            }
        }

        // Determine if axis lock is effectively active (either by toggle or hold)
        boolean isAxisLockEffectivelyActive = modEnabled || holdAxisLockActive;

        // Handle Cycle Axis Key
        while (cycleAxisKey.wasPressed()) {
            currentSelectedAxis = currentSelectedAxis.next();
            client.player.sendMessage(
                    Text.translatable("axislock.cycle",
                            currentSelectedAxis.toString()).formatted(Formatting.WHITE),
                    true
            );
            // If axis changes during an active sequence, reset the sequence
            if (isAxisLockEffectivelyActive && isPlacingSequenceActive) {
                isPlacingSequenceActive = false;
                firstBlockPosInSequence = null;
            }
        }

        // Logic for detecting "place key release" or if lock becomes inactive, to end a sequence
        if (isPlacingSequenceActive) {
            if (!isAxisLockEffectivelyActive) { // If axis lock is no longer active
                isPlacingSequenceActive = false;
                firstBlockPosInSequence = null;
            } else if (!MinecraftClient.getInstance().options.useKey.isPressed()) { // Or if the place key is released
                isPlacingSequenceActive = false;
                firstBlockPosInSequence = null;
            }
        }

        // Handle Direct Axis Selection Keys
        boolean axisChangedByDirectSelection = false;
        if (selectAxisXKey.wasPressed()) {
            currentSelectedAxis = Axis.X;
            axisChangedByDirectSelection = true;
        } else if (selectAxisYKey.wasPressed()) {
            currentSelectedAxis = Axis.Y;
            axisChangedByDirectSelection = true;
        } else if (selectAxisZKey.wasPressed()) {
            currentSelectedAxis = Axis.Z;
            axisChangedByDirectSelection = true;
        }

        if (axisChangedByDirectSelection) {
            client.player.sendMessage(
                    Text.translatable("axislock.select",
                            currentSelectedAxis.toString()).formatted(Formatting.WHITE),
                    true
            );
            // If axis changes during an active sequence, reset the sequence
            if (isAxisLockEffectivelyActive && isPlacingSequenceActive) {
                isPlacingSequenceActive = false;
                firstBlockPosInSequence = null;
            }
        }

        // Handle Reference Point Key
        if (ReferencePointKey.wasPressed()) {
            if (manualReferencePoint != null) {
                client.player.sendMessage(
                        Text.translatable("axislock.reference_point.reset").formatted(Formatting.YELLOW),
                        true
                );
                // this first two are just to make sure nothing weird happens
                isPlacingSequenceActive = false;
                firstBlockPosInSequence = null;
                manualReferencePoint = null; // Reset the reference point
                return;
            }
            // Get the block that the player is looking at and use it as the reference point

            BlockHitResult hitResult = client.crosshairTarget instanceof BlockHitResult ? (BlockHitResult) client.crosshairTarget : null;
            if (client.world != null && hitResult != null && !client.world.getBlockState(hitResult.getBlockPos()).isAir()) {
                manualReferencePoint = hitResult.getBlockPos();
                client.player.sendMessage(
                        Text.translatable("axislock.reference_point.set",
                                manualReferencePoint.toShortString()).formatted(Formatting.YELLOW),
                        true
                );
            } else {
                client.player.sendMessage(
                        Text.translatable("axislock.reference_point.error").formatted(Formatting.RED),
                        true
                );
            }
        }
    }

    private ActionResult onBlockPlaceAttempt(net.minecraft.entity.player.PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        // Determine if axis lock is effectively active (either by toggle or hold)
        boolean isAxisLockEffectivelyActive = modEnabled || holdAxisLockActive;

        if (!world.isClient() || !isAxisLockEffectivelyActive || player.isSpectator()) {
            return ActionResult.PASS;
        }

        ItemStack stackInHand = player.getStackInHand(hand);
        if (!(stackInHand.getItem() instanceof BlockItem)) {
            return ActionResult.PASS;
        }

        BlockPos potentialPlacePos = hitResult.getBlockPos().offset(hitResult.getSide());
        BlockPos referencePosForCheck;

        if (manualReferencePoint != null) {
            referencePosForCheck = manualReferencePoint;
        } else {
            if (!isPlacingSequenceActive) {
                // This is the first block being placed with the lock active
                firstBlockPosInSequence = potentialPlacePos;
                isPlacingSequenceActive = true;
                return ActionResult.PASS; // Allow placement of the first block
            } else {
                if (firstBlockPosInSequence == null) {
                    // This state should ideally not be reached if logic is correct
                    LOGGER.warn("AxisLock: Inconsistent state: isPlacingSequenceActive is true, but firstBlockPosInSequence is null. Resetting sequence.");
                    isPlacingSequenceActive = false;
                    return ActionResult.PASS; // Allow placement to avoid getting stuck, but log error
                }
                referencePosForCheck = firstBlockPosInSequence;
            }
        }

        boolean allowPlacement = switch (currentSelectedAxis) {
            case X -> (potentialPlacePos.getX() == referencePosForCheck.getX());
            case Y -> (potentialPlacePos.getY() == referencePosForCheck.getY());
            case Z -> (potentialPlacePos.getZ() == referencePosForCheck.getZ());
        };

        return allowPlacement ? ActionResult.PASS : ActionResult.FAIL;
    }
}