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
package org.spongepowered.common.mixin.core.entity.item;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.objectweb.asm.Opcodes;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.data.manipulator.DataManipulator;
import org.spongepowered.api.data.manipulator.mutable.RepresentedItemData;
import org.spongepowered.api.data.value.mutable.Value;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.Item;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.inventory.ItemStackSnapshot;
import org.spongepowered.api.text.translation.Translation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.common.data.manipulator.mutable.SpongeRepresentedItemData;
import org.spongepowered.common.data.value.mutable.SpongeValue;
import org.spongepowered.common.event.SpongeCommonEventFactory;
import org.spongepowered.common.interfaces.world.IMixinWorld;
import org.spongepowered.common.item.inventory.util.ItemStackUtil;
import org.spongepowered.common.mixin.core.entity.MixinEntity;

import java.util.List;

@Mixin(EntityItem.class)
public abstract class MixinEntityItem extends MixinEntity implements Item {

    private static final short MAGIC_INFINITE_PICKUP_DELAY = 32767;
    private static final short MAGIC_INFINITE_DESPAWN_TIME = -32768;
    private static final int MAGIC_INFINITE = -1;

    @Shadow private int delayBeforeCanPickup;
    @Shadow private int age;

    @Shadow public abstract ItemStack getEntityItem();

    public int lifespan;
    public float dropChance = 1.0f;
    /**
     * A simple cached value of the merge radius for this item.
     * Since the value is configurable, the first time searching for
     * other items, this value is cached.
     */
    private double cachedRadius = -1;

    //
    // In the case where a Forge mod sets the delay to MAGIC_INFINITE_PICKUP_DELAY, but a plugin has
    // never called setPickupDelay or setInfinitePickupDelay, delayBeforeCanPickup would be decremented,
    // as infiniteDelay is set to false. However, this is not the intended behavior, as the Forge
    // mod meant an infinite delay to be set.

    // To resolve the ambiguity, this flag is used to determine whether infiniteDelay is false because it was never changed
    // from the default, or if it was explicitly set by a plugin
    private boolean pluginPickupSet;
    private boolean infinitePickupDelay;
    private boolean pluginDespawnSet;
    private boolean infiniteDespawnDelay;

    @Inject(method = "onUpdate()V", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/item/EntityItem;delayBeforeCanPickup:I",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void onOnUpdate(CallbackInfo ci) {
        if (this.delayBeforeCanPickup == MAGIC_INFINITE_PICKUP_DELAY && !this.infinitePickupDelay && this.pluginPickupSet) {
            this.delayBeforeCanPickup--;
        }
    }

    @Inject(method = "onUpdate()V", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/item/EntityItem;age:I", opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER))
    private void onOnUpdateAge(CallbackInfo ci) {
        if (this.delayBeforeCanPickup == MAGIC_INFINITE_DESPAWN_TIME && !this.infiniteDespawnDelay && this.pluginDespawnSet) {
            this.delayBeforeCanPickup--;
        }
    }

    @Inject(method = "onUpdate()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/item/EntityItem;setDead()V"))
    public void onEntityItemUpdate(CallbackInfo ci) {
        this.destructCause = Cause.of(NamedCause.of("ExpiredItem", this));
    }

    @ModifyConstant(method = "searchForOtherItemsNearby", constant = @Constant(doubleValue = 0.5D))
    private double getSearchRadius(double originalRadius) {
        if (this.worldObj.isRemote) {
            return originalRadius;
        }
        if (this.cachedRadius == -1) {
            final double configRadius = ((IMixinWorld) this.worldObj).getActiveConfig().getConfig().getWorld().getItemMergeRadius();
            this.cachedRadius = configRadius < 0 ? 0 : configRadius;
        }
        return cachedRadius;
    }

    public int getPickupDelay() {
        if (this.delayBeforeCanPickup == MAGIC_INFINITE_PICKUP_DELAY) {
            // There are two cases when -1 should be returned:

            // The plugin has called set an infinite pickup delay
            // The plugin has not set a pickup delay (neither setPickupDelay nor setInfinitePickupDelay
            // has been called) - a Forge mod or something else has set the pickup delay, and they presumably
            // know about the magic value.
            if ((this.pluginPickupSet && this.infinitePickupDelay) || !this.pluginPickupSet) {
                return MAGIC_INFINITE;
            }
        }
        return this.delayBeforeCanPickup;
    }

    public void setPickupDelay(int delay) {
        this.delayBeforeCanPickup = delay;
        this.pluginPickupSet = true;
        this.infinitePickupDelay = false;
    }

    public void setInfinitePickupDelay() {
        this.delayBeforeCanPickup = MAGIC_INFINITE_PICKUP_DELAY;
        this.pluginPickupSet = true;
        this.infinitePickupDelay = true;
    }

    public int getDespawnTime() {
        if (this.age == MAGIC_INFINITE_DESPAWN_TIME) {
            if ((this.pluginDespawnSet && this.infiniteDespawnDelay) || !this.pluginDespawnSet) {
                return MAGIC_INFINITE;
            }
        }
        return this.lifespan - this.age;
    }

    public void setDespawnTime(int time) {
        this.lifespan = this.age + time;
        this.pluginDespawnSet = true;
        this.infiniteDespawnDelay = false;
    }

    public void setInfiniteDespawnTime() {
        this.age = MAGIC_INFINITE_DESPAWN_TIME;
        this.pluginDespawnSet = true;
        this.infiniteDespawnDelay = true;
    }

    @Override
    public void readFromNbt(NBTTagCompound compound) {
        super.readFromNbt(compound);
        // If the key exists, the value has been set by a plugin
        if (compound.hasKey("infinitePickupDelay")) {
            this.pluginPickupSet = true;
            if (compound.getBoolean("infinitePickupDelay")) {
                this.setInfinitePickupDelay();
            } else {
                this.infinitePickupDelay = false;
            }
        }
        if (compound.hasKey("infiniteDespawnDelay")) {
            this.pluginDespawnSet = true;
            if (compound.getBoolean("infiniteDespawnDelay")) {
                this.setInfiniteDespawnTime();
            } else {
                this.infiniteDespawnDelay = false;
            }
        }
    }

    @Override
    public void writeToNbt(NBTTagCompound compound) {
        super.writeToNbt(compound);
        if (this.pluginPickupSet) {
            compound.setBoolean("infinitePickupDelay", this.infinitePickupDelay);
        } else {
            compound.removeTag("infinitePickupDelay");
        }
        if (this.pluginDespawnSet) {
            compound.setBoolean("infiniteDespawnDelay", this.infiniteDespawnDelay);
        } else {
            compound.removeTag("infiniteDespawnDelay");
        }
    }

    @Override
    public Translation getTranslation() {
        return getItemData().item().get().getType().getTranslation();
    }

    @Override
    public ItemType getItemType() {
        return (ItemType) getEntityItem().getItem();
    }

    @Inject(method = "combineItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/item/EntityItem;setDead()V"))
    public void onCombineItems(EntityItem other, CallbackInfoReturnable<Boolean> cir) {
        this.destructCause = Cause.of(NamedCause.of("CombinedItem", other));
    }

    @Inject(method = "onCollideWithPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/item/EntityItem;getEntityItem()Lnet/minecraft/item/ItemStack;"), cancellable = true)
    public void onPlayerItemPickup(EntityPlayer entityIn, CallbackInfo ci) {
        if (!SpongeCommonEventFactory.callPlayerChangeInventoryPickupEvent(entityIn, this.getEntityItem(), this.delayBeforeCanPickup, ((Entity)(Object) this).getCreator().orElse(null))) {
            ci.cancel();
        }
    }

    // Data delegated methods - Reduces potentially expensive lookups for accessing guaranteed data

    @Override
    public RepresentedItemData getItemData() {
        return new SpongeRepresentedItemData(ItemStackUtil.createSnapshot(getEntityItem()));
    }

    @Override
    public Value<ItemStackSnapshot> item() {
        return new SpongeValue<>(Keys.REPRESENTED_ITEM, ItemStackSnapshot.NONE, ItemStackUtil.createSnapshot(getEntityItem()));
    }

    @Override
    public void supplyVanillaManipulators(List<DataManipulator<?, ?>> manipulators) {
        super.supplyVanillaManipulators(manipulators);
        manipulators.add(getItemData());
    }
}
