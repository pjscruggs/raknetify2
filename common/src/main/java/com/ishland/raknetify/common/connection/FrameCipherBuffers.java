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

package com.ishland.raknetify.common.connection;

import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;

/** Owns bounded, wiped cipher scratch storage for one connection direction. */
final class FrameCipherBuffers {
    /** Maximum retained bytes in each scratch array. */
    static final int SCRATCH_LIMIT = 65536;

    /** Input copy used only when the source does not expose its backing array. */
    private byte[] inputScratch = new byte[0];

    /** Private result used only when the destination does not expose its backing array. */
    private byte[] outputScratch = new byte[0];

    /**
     * Transforms bytes without transferring buffer ownership or advancing the input index.
     *
     * @param cipher initialized direction-local cipher
     * @param input source buffer
     * @param start first source byte
     * @param length source byte count
     * @param output destination published only on success
     * @throws GeneralSecurityException if authentication or transformation fails
     */
    void transform(
            final Cipher cipher,
            final ByteBuf input,
            final int start,
            final int length,
            final ByteBuf output)
            throws GeneralSecurityException {
        int capacity = cipher.getOutputSize(length);
        output.ensureWritable(capacity);
        if (length > SCRATCH_LIMIT - 16 || capacity > SCRATCH_LIMIT) {
            transformNio(cipher, input, start, length, output, capacity);
            return;
        }
        try {
            transformArrays(cipher, input, start, length, output, capacity);
        } finally {
            wipe(input, output, length, capacity);
        }
    }

    /**
     * Preserves the provider's bounded NIO path for large frames.
     *
     * @param cipher initialized cipher
     * @param input source buffer
     * @param start first source byte
     * @param length source byte count
     * @param output destination buffer
     * @param capacity required destination capacity
     * @throws GeneralSecurityException if transformation fails
     */
    private static void transformNio(
            final Cipher cipher,
            final ByteBuf input,
            final int start,
            final int length,
            final ByteBuf output,
            final int capacity)
            throws GeneralSecurityException {
        int written =
                cipher.doFinal(
                        input.nioBuffer(start, length),
                        output.nioBuffer(output.writerIndex(), capacity));
        output.writerIndex(output.writerIndex() + written);
    }

    /**
     * Uses caller arrays or reusable private storage without allocating a per-call copy.
     *
     * @param cipher initialized cipher
     * @param input source buffer
     * @param start first source byte
     * @param length source byte count
     * @param output destination buffer
     * @param capacity required destination capacity
     * @throws GeneralSecurityException if transformation fails
     */
    private void transformArrays(
            final Cipher cipher,
            final ByteBuf input,
            final int start,
            final int length,
            final ByteBuf output,
            final int capacity)
            throws GeneralSecurityException {
        byte[] source = readableArray(input, start, length);
        int sourceOffset = input.hasArray() ? input.arrayOffset() + start : 0;
        byte[] target = writableArray(output, capacity);
        int targetOffset = output.hasArray() ? output.arrayOffset() + output.writerIndex() : 0;
        int written = finish(cipher, source, sourceOffset, length, target, targetOffset, capacity);
        if (output.hasArray()) {
            output.writerIndex(output.writerIndex() + written);
        } else {
            output.writeBytes(target, 0, written);
        }
    }

    /**
     * Bounds provider overlap handling to the participating regions of shared arrays.
     *
     * @param cipher initialized cipher
     * @param source source array
     * @param sourceOffset first source byte
     * @param length source byte count
     * @param target destination array
     * @param targetOffset first destination byte
     * @param capacity available destination region
     * @return bytes written
     * @throws GeneralSecurityException if transformation fails
     */
    private static int finish(
            final Cipher cipher,
            final byte[] source,
            final int sourceOffset,
            final int length,
            final byte[] target,
            final int targetOffset,
            final int capacity)
            throws GeneralSecurityException {
        if (sameStorage(source, target) && sourceOffset < targetOffset) {
            return cipher.doFinal(
                    ByteBuffer.wrap(source, sourceOffset, length),
                    ByteBuffer.wrap(target, targetOffset, capacity));
        }
        return cipher.doFinal(source, sourceOffset, length, target, targetOffset);
    }

    /**
     * Uses array Object.equals identity because equal contents do not imply overlapping memory.
     *
     * @param source source array viewed as an object
     * @param target destination array viewed as an object
     * @return whether both references identify the same storage
     */
    private static boolean sameStorage(final Object source, final Object target) {
        return source.equals(target);
    }

    /**
     * Copies non-array sources into bounded direction-local storage.
     *
     * @param input source buffer
     * @param start first source byte
     * @param length source byte count
     * @return caller array or private input copy
     */
    private byte[] readableArray(final ByteBuf input, final int start, final int length) {
        if (input.hasArray()) {
            return input.array();
        }
        if (inputScratch.length < length) {
            inputScratch = new byte[length];
        }
        input.getBytes(start, inputScratch, 0, length);
        return inputScratch;
    }

    /**
     * Selects a caller array or bounded private result storage.
     *
     * @param output destination buffer
     * @param capacity required result capacity
     * @return caller array or private output array
     */
    private byte[] writableArray(final ByteBuf output, final int capacity) {
        if (output.hasArray()) {
            return output.array();
        }
        if (outputScratch.length < capacity) {
            outputScratch = new byte[capacity];
        }
        return outputScratch;
    }

    /**
     * Clears every potentially used scratch byte after success or failure.
     *
     * @param input source identifying whether input scratch was used
     * @param output destination identifying whether output scratch was used
     * @param length maximum copied input bytes
     * @param capacity maximum written output bytes
     */
    private void wipe(
            final ByteBuf input, final ByteBuf output, final int length, final int capacity) {
        if (!input.hasArray()) {
            Arrays.fill(inputScratch, 0, Math.min(length, inputScratch.length), (byte) 0);
        }
        if (!output.hasArray()) {
            Arrays.fill(outputScratch, 0, Math.min(capacity, outputScratch.length), (byte) 0);
        }
    }
}
