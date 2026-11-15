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

import net.minecraft.world.level.chunk.PalettedContainer;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads and assigns AntiXray's per-container replacement states during section construction. */
@Mixin(value = PalettedContainer.class, priority = 1500, remap = false)
public interface PalettePresetsAccessor {
    /**
     * Reads the presets captured by AntiXray for this palette.
     *
     * @return configured replacement values, or null when obfuscation is inactive
     */
    @Accessor("antiXray$presetValues")
    Object @Nullable [] raknetify$getPresets();

    /**
     * Assigns the original replacement array without changing palette entries or world data.
     *
     * @param presets replacement values belonging to this container
     */
    @Accessor("antiXray$presetValues")
    void raknetify$setPresets(Object @Nullable [] presets);
}
