/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.common.mixin.core.network;

import static org.spongepowered.common.util.SpongeCommonTranslationHelper.t;

import com.flowpowered.math.vector.Vector3d;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityMinecartCommandBlock;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.PacketThreadUtil;
import net.minecraft.network.play.client.CPacketAnimation;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.network.play.client.CPacketUpdateSign;
import net.minecraft.network.play.client.CPacketUseEntity;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.network.play.server.SPacketSetSlot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.server.management.PlayerList;
import net.minecraft.tileentity.CommandBlockBaseLogic;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityCommandBlock;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.IntHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.block.tileentity.Sign;
import org.spongepowered.api.data.manipulator.mutable.tileentity.SignData;
import org.spongepowered.api.data.value.mutable.ListValue;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.SpongeEventFactory;
import org.spongepowered.api.event.block.InteractBlockEvent;
import org.spongepowered.api.event.block.tileentity.ChangeSignEvent;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.event.entity.InteractEntityEvent;
import org.spongepowered.api.event.message.MessageEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.network.PlayerConnection;
import org.spongepowered.api.resourcepack.ResourcePack;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageChannel;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.common.SpongeImpl;
import org.spongepowered.common.entity.EntityUtil;
import org.spongepowered.common.entity.player.tab.SpongeTabList;
import org.spongepowered.common.event.tracking.CauseTracker;
import org.spongepowered.common.event.tracking.PhaseContext;
import org.spongepowered.common.event.tracking.phase.WorldPhase;
import org.spongepowered.common.interfaces.IMixinNetworkManager;
import org.spongepowered.common.interfaces.IMixinPacketResourcePackSend;
import org.spongepowered.common.interfaces.network.IMixinNetHandlerPlayServer;
import org.spongepowered.common.interfaces.world.IMixinWorldServer;
import org.spongepowered.common.network.PacketUtil;
import org.spongepowered.common.registry.provider.DirectionFacingProvider;
import org.spongepowered.common.text.SpongeTexts;
import org.spongepowered.common.util.StaticMixinHelper;
import org.spongepowered.common.util.VecHelper;
import org.spongepowered.common.world.WorldManager;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayServer implements PlayerConnection, IMixinNetHandlerPlayServer {

    private static final String UPDATE_SIGN = "Lnet/minecraft/network/play/client/CPacketUpdateSign;getLines()[Ljava/lang/String;";

    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final public NetworkManager netManager;
    @Shadow @Final private MinecraftServer serverController;
    @Shadow @Final private IntHashMap<Short> pendingTransactions;
    @Shadow public EntityPlayerMP playerEntity;

    @Shadow public abstract void sendPacket(final Packet<?> packetIn);
    @Shadow public abstract void kickPlayerFromServer(String reason);

    private boolean justTeleported = false;
    @Nullable private Location<World> lastMoveLocation = null;

    private final Map<String, ResourcePack> sentResourcePacks = new HashMap<>();

    private Long lastPacket;
    // Store the last block right-clicked
    private boolean allowClientLocationUpdate = true;
    @Nullable private Item lastItem;


    @Override
    public Map<String, ResourcePack> getSentResourcePacks() {
        return this.sentResourcePacks;
    }

    @Override
    public Player getPlayer() {
        return (Player) this.playerEntity;
    }

    @Override
    public InetSocketAddress getAddress() {
        return ((IMixinNetworkManager) this.netManager).getAddress();
    }

    @Override
    public InetSocketAddress getVirtualHost() {
        return ((IMixinNetworkManager) this.netManager).getVirtualHost();
    }

    @Override
    public int getLatency() {
        return this.playerEntity.ping;
    }

    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayerMP;onUpdateEntity()V"))
    private void onPlayerTick(EntityPlayerMP playerEntity) {
        if (playerEntity.worldObj.isRemote) {
            playerEntity.onUpdateEntity();
            return;
        }
        final IMixinWorldServer mixinWorldServer = (IMixinWorldServer) playerEntity.worldObj;
        final CauseTracker causeTracker = mixinWorldServer.getCauseTracker();
        causeTracker.switchToPhase(WorldPhase.Tick.PLAYER, PhaseContext.start()
                .add(NamedCause.source(playerEntity))
                .addCaptures()
                .addEntityDropCaptures()
                .addBlockCaptures()
                .complete());
        for (WorldServer worldServer : WorldManager.getWorlds()) {
            if (worldServer == mixinWorldServer) { // we don't care about entering the phase for this world server of which we already entered
                continue;
            }
            final IMixinWorldServer otherMixinWorldServer = (IMixinWorldServer) worldServer;
            otherMixinWorldServer.getCauseTracker().switchToPhase(WorldPhase.Tick.PLAYER, PhaseContext.start()
                    .add(NamedCause.source(playerEntity))
                    .addCaptures()
                    .addEntityDropCaptures()
                    .addBlockCaptures()
                    .complete());
        }
        playerEntity.onUpdateEntity();
        causeTracker.completePhase();
        for (WorldServer worldServer : WorldManager.getWorlds()) {
            if (worldServer == mixinWorldServer) { // we don't care about entering the phase for this world server of which we already entered
                continue;
            }
            final IMixinWorldServer otherMixinWorldServer = (IMixinWorldServer) worldServer;
            otherMixinWorldServer.getCauseTracker().completePhase();
        }
    }

    @Override
    public void setAllowClientLocationUpdate(boolean flag) {
        this.allowClientLocationUpdate = flag;
    }

    /**
     * @param manager The player network connection
     * @param packet The original packet to be sent
     * @author kashike
     */
    @Redirect(method = "sendPacket(Lnet/minecraft/network/Packet;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/NetworkManager;sendPacket(Lnet/minecraft/network/Packet;)V"))
    public void onSendPacket(NetworkManager manager, Packet<?> packet) {
        if (!this.allowClientLocationUpdate && packet instanceof SPacketPlayerPosLook) {
            return;
        }
        manager.sendPacket(this.rewritePacket(packet));
    }

    /**
     * This method wraps packets being sent to perform any additional actions,
     * such as rewriting data in the packet.
     *
     * @param packetIn The original packet to be sent
     * @return The rewritten packet if we performed any changes, or the original
     *     packet if we did not perform any changes
     * @author kashike
     */
    private Packet<?> rewritePacket(final Packet<?> packetIn) {
        // Update the tab list data
        if (packetIn instanceof SPacketPlayerListItem) {
            ((SpongeTabList) ((Player) this.playerEntity).getTabList()).updateEntriesOnSend((SPacketPlayerListItem) packetIn);
        }
        // Store the resource pack for use when processing resource pack statuses
        else if (packetIn instanceof IMixinPacketResourcePackSend) {
            IMixinPacketResourcePackSend packet = (IMixinPacketResourcePackSend) packetIn;
            this.sentResourcePacks.put(packet.setFakeHash(), packet.getResourcePack());
        }

        return packetIn;
    }

    /**
     * @author Zidane
     *
     * Invoke before {@code System.arraycopy(packetIn.getLines(), 0, tileentitysign.signText, 0, 4);} (line 1156 in source) to call SignChangeEvent.
     * @param packetIn Injected packet param
     * @param ci Info to provide mixin on how to handle the callback
     * @param worldserver Injected world param
     * @param blockpos Injected blockpos param
     * @param tileentity Injected tilentity param
     * @param tileentitysign Injected tileentitysign param
     */
    @Inject(method = "processUpdateSign", at = @At(value = "INVOKE", target = UPDATE_SIGN), cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD)
    public void callSignChangeEvent(CPacketUpdateSign packetIn, CallbackInfo ci, WorldServer worldserver, BlockPos blockpos, IBlockState iblockstate, TileEntity tileentity, TileEntitySign tileentitysign) {
        ci.cancel();
        if (!PacketUtil.processSignPacket(packetIn, ci, tileentitysign, this.playerEntity)) {
            return;
        }
        final Optional<SignData> existingSignData = ((Sign) tileentitysign).get(SignData.class);
        if (!existingSignData.isPresent()) {
            // TODO Unsure if this is the best to do here...
            throw new RuntimeException("Critical error! Sign data not present on sign!");
        }
        final SignData changedSignData = existingSignData.get().copy();
        final ListValue<Text> lines = changedSignData.lines();
        for (int i = 0; i < packetIn.getLines().length; i++) {
            lines.set(i, SpongeTexts.toText(new TextComponentString(packetIn.getLines()[i])));
        }
        changedSignData.set(lines);
        // I pass changedSignData in here twice to emulate the fact that even-though the current sign data doesn't have the lines from the packet
        // applied, this is what it "is" right now. If the data shown in the world is desired, it can be fetched from Sign.getData
        final ChangeSignEvent event =
                SpongeEventFactory.createChangeSignEvent(Cause.of(NamedCause.source(this.playerEntity)),
                    changedSignData.asImmutable(), changedSignData, (Sign) tileentitysign);
        if (!SpongeImpl.postEvent(event)) {
            ((Sign) tileentitysign).offer(event.getText());
        } else {
            // If cancelled, I set the data back that was fetched from the sign. This means that if its a new sign, the sign will be empty else
            // it will be the text of the sign that was showing in the world
            ((Sign) tileentitysign).offer(existingSignData.get());
        }
        tileentitysign.markDirty();
        worldserver.getPlayerChunkMap().markBlockForUpdate(blockpos);
    }

    /**
     * @author zml
     *
     * Purpose: replace the logic used for command blocks to make functional
     *
     * @param ci callback
     * @param packetIn method param
     */
    @Inject(method = "processCustomPayload", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "net/minecraft/network/PacketThreadUtil.checkThreadAndEnqueue(Lnet/minecraft/network/Packet;"
                    + "Lnet/minecraft/network/INetHandler;Lnet/minecraft/util/IThreadListener;)V"), cancellable = true)
    public void processCommandBlock(CPacketCustomPayload packetIn, CallbackInfo ci) {
        if ("MC|AdvCdm".equals(packetIn.getChannelName())) {
            PacketBuffer packetbuffer;
            try {
                if (!this.serverController.isCommandBlockEnabled()) {
                    this.playerEntity.addChatMessage(new TextComponentTranslation("advMode.notEnabled", new Object[0]));
                    // Sponge: Check permissions for command block usage TODO: Maybe throw an event instead?
                    // } else if (this.playerEntity.canCommandSenderUseCommand(2, "") && this.playerEntity.capabilities.isCreativeMode) {
                } else {
                    packetbuffer = packetIn.getBufferData();

                    try {
                        byte b0 = packetbuffer.readByte();
                        CommandBlockBaseLogic commandblocklogic = null;

                        String permissionCheck = null; // Sponge
                        if (b0 == 0) {
                            TileEntity tileentity = this.playerEntity.worldObj
                                    .getTileEntity(new BlockPos(packetbuffer.readInt(), packetbuffer.readInt(), packetbuffer.readInt()));

                            if (tileentity instanceof TileEntityCommandBlock) {
                                commandblocklogic = ((TileEntityCommandBlock) tileentity).getCommandBlockLogic();
                                permissionCheck = "minecraft.commandblock.edit.block." + commandblocklogic.getName(); // Sponge
                            }
                        } else if (b0 == 1) {
                            Entity entity = this.playerEntity.worldObj.getEntityByID(packetbuffer.readInt());

                            if (entity instanceof EntityMinecartCommandBlock) {
                                commandblocklogic = ((EntityMinecartCommandBlock) entity).getCommandBlockLogic();
                                permissionCheck = "minecraft.commandblock.edit.minecart." + commandblocklogic.getName(); // Sponge
                            }
                            // Sponge begin
                        } else {
                            throw new IllegalArgumentException("Unknown command block type!");
                        }
                        Player spongePlayer = ((Player) this.playerEntity);
                        if (permissionCheck == null || !spongePlayer.hasPermission(permissionCheck)) {
                            spongePlayer.sendMessage(t("You do not have permission to edit this command block!").toBuilder()
                                    .color(TextColors.RED).build());
                            return;
                            // Sponge end
                        }

                        String s1 = packetbuffer.readStringFromBuffer(packetbuffer.readableBytes());
                        boolean flag = packetbuffer.readBoolean();

                        if (commandblocklogic != null) {
                            commandblocklogic.setCommand(s1);
                            commandblocklogic.setTrackOutput(flag);

                            if (!flag) {
                                commandblocklogic.setLastOutput(null);
                            }

                            commandblocklogic.updateCommand();
                            this.playerEntity.addChatMessage(new TextComponentTranslation("advMode.setCommand.success", new Object[] {s1}));
                        }
                    } catch (Exception exception1) {
                        LOGGER.error("Couldn\'t set command block", exception1);
                    } finally {
                        packetbuffer.release();
                    }
                /*} else { // Sponge: Give more accurate no permission message
                    this.playerEntity.addChatMessage(new ChatComponentTranslation("advMode.notAllowed", new Object[0]));*/
                }
            } finally {
                ci.cancel();
            }
        }
    }

    @Redirect(method = "processChatMessage", at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/StringUtils;normalizeSpace(Ljava/lang/String;)Ljava/lang/String;", remap = false))
    public String onNormalizeSpace(String input) {
        return input;
    }

    @Inject(method = "setPlayerLocation(DDDFFLjava/util/Set;)V", at = @At(value = "RETURN"))
    public void setPlayerLocation(double x, double y, double z, float yaw, float pitch, Set<?> relativeSet, CallbackInfo ci) {
        this.justTeleported = true;
    }

    // TODO - 1.9 player movement packet changes
    /*
    @Inject(method = "processPlayer", at = @At(value = "FIELD", target = "net.minecraft.network.NetHandlerPlayServer.hasMoved:Z", ordinal = 2), cancellable = true)
    public void processPlayerMoved(CPacketPlayer packetIn, CallbackInfo ci){
        if (packetIn.moving || packetIn.rotating && !this.playerEntity.isDead) {
            Player player = (Player) this.playerEntity;
            IMixinEntity spongeEntity = (IMixinEntity) this.playerEntity;
            Vector3d fromrot = player.getRotation();

            // If Sponge used the player's current location, the delta might never be triggered which could be exploited
            Location<World> from = player.getLocation();
            if (this.lastMoveLocation != null) {
                from = this.lastMoveLocation;
            }

            Vector3d torot = new Vector3d(packetIn.pitch, packetIn.yaw, 0);
            Location<World> to = new Location<>(player.getWorld(), packetIn.x, packetIn.y, packetIn.z);

            // Minecraft sends a 0, 0, 0 position when rotation only update occurs, this needs to be recognized and corrected
            boolean rotationOnly = !packetIn.moving && packetIn.rotating;
            if (rotationOnly) {
                // Correct the to location so it's not misrepresented to plugins, only when player rotates without moving
                // In this case it's only a rotation update, which isn't related to the to location
                from = player.getLocation();
                to = from;
            }

            // Minecraft does the same with rotation when it's only a positional update
            boolean positionOnly = packetIn.moving && !packetIn.rotating;
            if (positionOnly) {
                // Correct the new rotation to match the old rotation
                torot = fromrot;
            }

            ((IMixinEntityPlayerMP) this.playerEntity).setVelocityOverride(to.getPosition().sub(from.getPosition()));

            double deltaSquared = to.getPosition().distanceSquared(from.getPosition());
            double deltaAngleSquared = fromrot.distanceSquared(torot);

            // These magic numbers are sad but help prevent excessive lag from this event.
            // eventually it would be nice to not have them
            if (deltaSquared > ((1f / 16) * (1f / 16)) || deltaAngleSquared > (.15f * .15f)) {
                Transform<World> fromTransform = player.getTransform().setLocation(from).setRotation(fromrot);
                Transform<World> toTransform = player.getTransform().setLocation(to).setRotation(torot);
                DisplaceEntityEvent.Move.TargetPlayer event =
                        SpongeEventFactory.createDisplaceEntityEventMoveTargetPlayer(Cause.of(NamedCause.source(player)), fromTransform, toTransform, player);
                SpongeImpl.postEvent(event);
                if (event.isCancelled()) {
                    spongeEntity.setLocationAndAngles(fromTransform);
                    this.lastMoveLocation = from;
                    ((IMixinEntityPlayerMP) this.playerEntity).setVelocityOverride(null);
                    ci.cancel();
                } else if (!event.getToTransform().equals(toTransform)) {
                    spongeEntity.setLocationAndAngles(event.getToTransform());
                    this.lastMoveLocation = event.getToTransform().getLocation();
                    ((IMixinEntityPlayerMP) this.playerEntity).setVelocityOverride(null);
                    ci.cancel();
                } else if (!from.equals(player.getLocation()) && this.justTeleported) {
                    this.lastMoveLocation = player.getLocation();
                    // Prevent teleports during the move event from causing odd behaviors
                    this.justTeleported = false;
                    ((IMixinEntityPlayerMP) this.playerEntity).setVelocityOverride(null);
                    ci.cancel();
                } else {
                    this.lastMoveLocation = event.getToTransform().getLocation();
                }
            }
        }
    }*/

    @Redirect(method = "onDisconnect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/management/PlayerList;sendChatMsg(Lnet/minecraft/util/text/ITextComponent;)V"))
    public void onDisconnectHandler(PlayerList this$0, ITextComponent component) {
        final Player player = ((Player) this.playerEntity);
        final Text message = SpongeTexts.toText(component);
        final MessageChannel originalChannel = player.getMessageChannel();
        final ClientConnectionEvent.Disconnect event = SpongeEventFactory.createClientConnectionEventDisconnect(
                Cause.of(NamedCause.source(player)), originalChannel, Optional.of(originalChannel), new MessageEvent.MessageFormatter(message),
                player, false
        );
        SpongeImpl.postEvent(event);
        if (!event.isMessageCancelled()) {
            event.getChannel().ifPresent(channel -> channel.send(player, event.getMessage()));
        }
    }


    @Inject(method = "processPlayerDigging", at = @At("HEAD"), cancellable = true)
    public void injectDig(CPacketPlayerDigging packetIn, CallbackInfo ci) {
        if (!SpongeImpl.getServer().isCallingFromMinecraftThread()) {
            StaticMixinHelper.lastPrimaryPacketTick = SpongeImpl.getServer().getTickCounter();
        }
    }

    @Nullable
    @Redirect(method = "processPlayerDigging", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayerMP;dropItem(Z)Lnet/minecraft/entity/item/EntityItem;"))
    public EntityItem onPlayerDropItem(EntityPlayerMP player, boolean dropAll) {
        EntityItem item = null;
        ItemStack stack = this.playerEntity.inventory.getCurrentItem();
        if (stack != null) {
            int size = stack.stackSize;
            item = this.playerEntity.dropItem(dropAll);
            // force client itemstack update if drop event was cancelled
            if (item == null) {
                Slot slot = this.playerEntity.openContainer.getSlotFromInventory(this.playerEntity.inventory, this.playerEntity.inventory.currentItem);
                int windowId = this.playerEntity.openContainer.windowId;
                stack.stackSize = size;
                this.sendPacket(new SPacketSetSlot(windowId, slot.slotNumber, stack));
            }
        }

        return item;
    }

    @Redirect(method = "processPlayerDigging", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/management/PlayerInteractionManager;onBlockClicked(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;)V"))
    public void handleLeftBlockClick(PlayerInteractionManager interactionManager, BlockPos pos, EnumFacing side) {
        Location<World> location = new Location<>((World) this.playerEntity.worldObj, VecHelper.toVector3d(pos));
        InteractBlockEvent.Primary event = SpongeEventFactory.createInteractBlockEventPrimaryMainHand(Cause.of(NamedCause.source(this.playerEntity)),
                Optional.empty(), location.createSnapshot(), DirectionFacingProvider.getInstance().getKey(side).get());
        if (SpongeImpl.postEvent(event)) {
            this.playerEntity.connection.sendPacket(new SPacketBlockChange(this.playerEntity.worldObj, pos));
            return;
        }

        interactionManager.onBlockClicked(pos, side);
    }

    /**
     * @author blood - April 5th, 2016
     *
     * @reason Due to all the changes we now do for this packet, it is much easier
     * to read it all with an overwrite. Information detailing on why each change
     * was made can be found in comments below.
     *
     * @param packetIn The entity use packet
     */
    @Overwrite
    public void processUseEntity(CPacketUseEntity packetIn) {
        // All packets received by server are handled first on the Netty Thread
        if (!SpongeImpl.getServer().isCallingFromMinecraftThread()) {
            if (packetIn.getAction() == CPacketUseEntity.Action.INTERACT) {
                // This is a horrible hack needed because the client sends 2 packets on 'right mouse click'
                // aimed at any entity that is not an armor stand. We shouldn't need the INTERACT packet as the
                // INTERACT_AT packet contains the same data but also includes a hitVec.
                return;
            } else { // queue packet for main thread
                PacketThreadUtil.checkThreadAndEnqueue(packetIn, (NetHandlerPlayServer) (Object) this, this.playerEntity.getServerWorld());
                return;
            }
        }

        WorldServer worldserver = this.serverController.worldServerForDimension(this.playerEntity.dimension);
        Entity entity = packetIn.getEntityFromWorld(worldserver);
        this.playerEntity.markPlayerActive();

        if (entity != null) {
            boolean flag = this.playerEntity.canEntityBeSeen(entity);
            double d0 = 36.0D; // 6 blocks

            if (!flag) {
                d0 = 9.0D; // 1.5 blocks
            }

            if (this.playerEntity.getDistanceSqToEntity(entity) < d0) {
                // Since we ignore any packet that has hitVec set to null, we can safely ignore this check
                // if (packetIn.getAction() == C02PacketUseEntity.Action.INTERACT) {
                //    this.playerEntity.interactWith(entity);
                // } else
                if (packetIn.getAction() == CPacketUseEntity.Action.INTERACT_AT) {
                    InteractEntityEvent.Secondary event;
                    if (packetIn.getHand() == EnumHand.MAIN_HAND) {
                        event = SpongeEventFactory.createInteractEntityEventSecondaryMainHand(
                                Cause.of(NamedCause.source(this.playerEntity)), Optional.of(VecHelper.toVector3d(packetIn.getHitVec())), EntityUtil.fromNative(entity));
                    } else {
                        event = SpongeEventFactory.createInteractEntityEventSecondaryOffHand(
                                Cause.of(NamedCause.source(this.playerEntity)), Optional.of(VecHelper.toVector3d(packetIn.getHitVec())), EntityUtil.fromNative(entity));
                    }

                    SpongeImpl.postEvent(event);
                    if (!event.isCancelled()) {

                        EnumHand hand = packetIn.getHand();
                        ItemStack itemstack = this.playerEntity.getHeldItem(hand);

                        // If INTERACT_AT returns a false result, we assume this packet was meant for interactWith
                        if (entity.applyPlayerInteraction(this.playerEntity, packetIn.getHitVec(), itemstack, hand) != EnumActionResult.SUCCESS) {
                            this.playerEntity.interact(entity, itemstack, hand);
                        }
                    }
                } else if (packetIn.getAction() == CPacketUseEntity.Action.ATTACK) {
                    if (entity instanceof EntityItem || entity instanceof EntityXPOrb || entity instanceof EntityArrow || entity == this.playerEntity) {
                        this.kickPlayerFromServer("Attempting to attack an invalid entity");
                        this.serverController.logWarning("Player " + this.playerEntity.getName() + " tried to attack an invalid entity");
                        return;
                    }

                    if (entity instanceof Player && !((World) this.playerEntity.worldObj).getProperties().isPVPEnabled()) {
                        return; // PVP is disabled, ignore
                    }

                    InteractEntityEvent.Primary event;
                    final Optional<Vector3d> interactionPoint = packetIn.getHitVec() == null
                                                                ? Optional.empty()
                                                                : Optional.of(VecHelper.toVector3d(packetIn.getHitVec()));
                    if (packetIn.getHand() == EnumHand.MAIN_HAND) {
                        event = SpongeEventFactory.createInteractEntityEventPrimaryMainHand(
                                Cause.of(NamedCause.source(this.playerEntity)),
                                interactionPoint, EntityUtil.fromNative(entity));
                    } else {
                        event = SpongeEventFactory.createInteractEntityEventPrimaryOffHand(
                                Cause.of(NamedCause.source(this.playerEntity)),
                                interactionPoint, EntityUtil.fromNative(entity));
                    }
                    SpongeImpl.postEvent(event);
                    if (!event.isCancelled()) {
                        this.playerEntity.attackTargetEntityWithCurrentItem(entity);
                    }
                }
            }
        }
    }

    // Format disconnection message properly, causes weird messages with our console colors
    // Also see https://bugs.mojang.com/browse/MC-59535
    @Redirect(method = "onDisconnect", at = @At(value = "INVOKE",
            target = "Lorg/apache/logging/log4j/Logger;info(Ljava/lang/String;[Ljava/lang/Object;)V", ordinal = 0, remap = false))
    private void logDisconnect(Logger logger, String message, Object[] args) {
        args[1] = SpongeTexts.toLegacy((ITextComponent) args[1]);
        logger.info(message, args);
    }

    @Inject(method = "handleAnimation", at = @At(value = "HEAD"))
    public void onProcessAnimationHead(CPacketAnimation packetIn, CallbackInfo ci) {
        if (!SpongeImpl.getServer().isCallingFromMinecraftThread()) {
            StaticMixinHelper.lastAnimationPacketTick = SpongeImpl.getServer().getTickCounter();
            StaticMixinHelper.lastAnimationPlayer = this.playerEntity;
        }
    }
}
