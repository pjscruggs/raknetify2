/*
 * This file is a part of the Raknetify project, licensed under MIT.
 *
 * Copyright (c) 2022-2025 ishland
 * Copyright (c) 2026 pjscruggs
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

package com.ishland.raknetify.legacy.compat;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restores AntiXray's block presets when Changed's additional palette captured them first. */
@Mixin(value = LevelChunkSection.class, priority = 1500, remap = false)
abstract class ChangedSectionMixin {
    /**
     * Resolves Changed's palette after Mixin has merged the additional field.
     *
     * @return the section's latex cover palette
     */
    @Accessor("coverStates")
    protected abstract PalettedContainer<?> raknetify$getCovers();

    /** Vanilla block palette, named differently in development and installed Forge. */
    @Shadow(aliases = "f_62972_")
    @Final
    private PalettedContainer<BlockState> states;

    /**
     * Moves the preset array before any blocks can resize the new single-value palette. Sections
     * constructed from saved palettes already have their own deserialization path.
     *
     * @param callback constructor completion
     */
    @Inject(method = "<init>(Lnet/minecraft/core/Registry;)V", at = @At("RETURN"))
    private void raknetify$restoreBlockPresets(final CallbackInfo callback) {
        PalettePresetsAccessor covers = (PalettePresetsAccessor) (Object) raknetify$getCovers();
        if (covers.raknetify$getPresets() instanceof BlockState[] presets) {
            PalettePresetsAccessor blocks = (PalettePresetsAccessor) (Object) states;
            if (blocks.raknetify$getPresets() == null) {
                blocks.raknetify$setPresets(presets);
            }
            covers.raknetify$setPresets(null);
        }
    }
}
