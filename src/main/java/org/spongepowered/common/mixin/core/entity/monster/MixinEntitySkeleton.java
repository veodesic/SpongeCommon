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
package org.spongepowered.common.mixin.core.entity.monster;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.monster.EntitySkeleton;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.data.manipulator.DataManipulator;
import org.spongepowered.api.data.manipulator.mutable.entity.SkeletonData;
import org.spongepowered.api.data.type.SkeletonType;
import org.spongepowered.api.data.value.mutable.Value;
import org.spongepowered.api.entity.living.monster.Skeleton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.common.data.manipulator.mutable.entity.SpongeSkeletonData;
import org.spongepowered.common.data.util.DataConstants;
import org.spongepowered.common.data.value.mutable.SpongeValue;
import org.spongepowered.common.entity.SpongeEntityConstants;

import java.util.List;

@Mixin(EntitySkeleton.class)
public abstract class MixinEntitySkeleton extends MixinEntityMob implements Skeleton {

    @Shadow public abstract net.minecraft.entity.monster.SkeletonType func_189771_df(); // getSkeletonType

    @Override
    public SkeletonData getSkeletonData() {
        // TODO: int -> SkeletonType (add Mixin)
        return new SpongeSkeletonData((SkeletonType) (Object) this.func_189771_df());
    }

    @Override
    public Value<SkeletonType> variant() {
        return new SpongeValue<>(Keys.SKELETON_TYPE, DataConstants.Catalog.DEFAULT_SKELETON, (SkeletonType) (Object) func_189771_df());
    }

    @Override
    public void supplyVanillaManipulators(List<DataManipulator<?, ?>> manipulators) {
        super.supplyVanillaManipulators(manipulators);
        manipulators.add(getSkeletonData());
    }

    @Redirect(method = "onInitialSpawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/ai/EntityAITasks;addTask"
            + "(ILnet/minecraft/entity/ai/EntityAIBase;)V"), require = 2)
    private void onAddTaskForInitialSpawn(EntityAITasks tasks, int molex, EntityAIBase aiBase) {
        if (this.worldObj.isRemote) {
            return;
        }
        this.initEntityAI();
        tasks.addTask(molex, aiBase);
    }
}
