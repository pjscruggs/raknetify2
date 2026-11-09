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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Checks exact cipher results, buffer ownership, scratch bounds and failure cleanup. */
class FrameCipherBuffersTest {
    /**
     * Array, direct, sliced, read-only and composite inputs match independent JCE output.
     *
     * @param length plaintext size including scratch-boundary cases
     * @throws Exception if encryption or reflection fails
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 64, 4096, 65504, 65520, 65521, 65536, 1048576})
    void layoutsMatchIndependentCipher(final int length) throws Exception {
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        byte[] plaintext = payload(length);
        byte[] encrypted = cipher(key, true).doFinal(plaintext);
        for (int style = 0; style < 4; style++) {
            for (boolean directOutput : new boolean[] {false, true}) {
                checkTransform(key, true, plaintext, encrypted, style, directOutput);
                checkTransform(key, false, encrypted, plaintext, style, directOutput);
            }
        }
    }

    /**
     * Shared backing arrays work for forward, reverse and overlapping destination regions.
     *
     * @throws Exception if encryption or reflection fails
     */
    @Test
    void sharedArraysPreserveCipherResults() throws Exception {
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        byte[] plaintext = payload(4096);
        byte[] encrypted = cipher(key, true).doFinal(plaintext);
        for (int targetOffset : new int[] {128, 256, 257, 8192}) {
            checkSharedArray(key, true, plaintext, encrypted, targetOffset);
            checkSharedArray(key, false, encrypted, plaintext, targetOffset);
        }
    }

    /**
     * Authentication failure exposes no result and wipes reused private arrays.
     *
     * @throws Exception if fixture setup or reflection fails
     */
    @Test
    void failedAuthenticationWipesScratchAndPreservesIndices() throws Exception {
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        byte[] encrypted = cipher(key, true).doFinal(payload(32768));
        var buffers = new FrameCipherBuffers();
        ByteBuf input = Unpooled.directBuffer(encrypted.length).writeBytes(encrypted);
        ByteBuf output = Unpooled.directBuffer(32769).writeByte(71);
        try {
            buffers.transform(cipher(key, false), input, 0, encrypted.length, output);
            assertWiped(buffers);
            output.clear().writeByte(71);
            input.setByte(encrypted.length - 1, input.getByte(encrypted.length - 1) ^ 1);
            Cipher decoder = cipher(key, false);
            assertThrows(
                    GeneralSecurityException.class,
                    () -> buffers.transform(decoder, input, 0, encrypted.length, output));
            assertEquals(0, input.readerIndex());
            assertEquals(1, output.writerIndex());
            assertEquals(71, output.getByte(0));
            assertWiped(buffers);
        } finally {
            input.release();
            output.release();
        }
    }

    /**
     * A destination write failure still clears plaintext copied to scratch.
     *
     * @throws Exception if fixture setup or reflection fails
     */
    @Test
    void readOnlyDestinationWipesScratch() throws Exception {
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        var buffers = new FrameCipherBuffers();
        ByteBuf input = Unpooled.directBuffer(64).writeBytes(payload(64));
        ByteBuf output = Unpooled.directBuffer(128).asReadOnly();
        try {
            Cipher encoder = cipher(key, true);
            assertThrows(
                    RuntimeException.class, () -> buffers.transform(encoder, input, 0, 64, output));
            assertEquals(0, output.writerIndex());
            assertWiped(buffers);
        } finally {
            input.release();
            output.release();
        }
    }

    /**
     * Growing and oversized frames never retain more than the per-direction scratch budget.
     *
     * @throws Exception if fixture setup or reflection fails
     */
    @Test
    void scratchGrowthIsBoundedAndRetiredArraysAreWiped() throws Exception {
        var buffers = new FrameCipherBuffers();
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        byte[] oldInput = scratch(buffers, "inputScratch");
        byte[] oldOutput = scratch(buffers, "outputScratch");
        for (int size : new int[] {64, 4096, 65520, 1048576, 1}) {
            runDirect(buffers, key, size);
            assertArrayEquals(new byte[oldInput.length], oldInput);
            assertArrayEquals(new byte[oldOutput.length], oldOutput);
            assertWiped(buffers);
            oldInput = scratch(buffers, "inputScratch");
            oldOutput = scratch(buffers, "outputScratch");
        }
        assertEquals(65520, oldInput.length);
        assertEquals(65536, oldOutput.length);
    }

    /**
     * Concurrent independent connections never share mutable scratch storage.
     *
     * @throws Exception if a worker or cryptographic assertion fails
     */
    @Test
    void concurrentConnectionsKeepBoundedIndependentStorage() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        var futures = new ArrayList<Future<FrameCipherBuffers>>();
        try {
            for (int connection = 0; connection < 64; connection++) {
                futures.add(executor.submit(FrameCipherBuffersTest::exerciseConnection));
            }
            var identities = Collections.newSetFromMap(new IdentityHashMap<byte[], Boolean>());
            for (Future<FrameCipherBuffers> future : futures) {
                FrameCipherBuffers buffers = future.get();
                assertWiped(buffers);
                assertTrue(identities.add(scratch(buffers, "inputScratch")));
                assertTrue(identities.add(scratch(buffers, "outputScratch")));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Exercises one direction using its own key and scratch storage.
     *
     * @return bounded storage retained by this connection
     * @throws Exception if encryption fails
     */
    private static FrameCipherBuffers exerciseConnection() throws Exception {
        var buffers = new FrameCipherBuffers();
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        runDirect(buffers, key, 65520);
        return buffers;
    }

    /**
     * Checks one large backing array with independently indexed slices.
     *
     * @param key synthetic session key
     * @param encrypting true for encryption
     * @param source input bytes
     * @param expected independently computed output
     * @param targetOffset destination location within the shared array
     * @throws Exception if transformation fails
     */
    private static void checkSharedArray(
            final SecretKey key,
            final boolean encrypting,
            final byte[] source,
            final byte[] expected,
            final int targetOffset)
            throws Exception {
        ByteBuf owner = Unpooled.wrappedBuffer(new byte[16 * 1024 * 1024]);
        ByteBuf input = owner.slice(256, source.length).clear().writeBytes(source);
        ByteBuf output = owner.slice(targetOffset, expected.length).clear();
        try {
            var buffers = new FrameCipherBuffers();
            buffers.transform(cipher(key, encrypting), input, 0, source.length, output);
            assertArrayEquals(expected, ByteBufUtil.getBytes(output));
            assertEquals(0, input.readerIndex());
            assertEquals(1, owner.refCnt());
            assertWiped(buffers);
        } finally {
            owner.release();
        }
    }

    /**
     * Compares one layout and direction without permitting index or ownership changes.
     *
     * @param key synthetic session key
     * @param encrypting true for encryption
     * @param source input bytes
     * @param expected independently computed output
     * @param style source storage layout
     * @param directOutput whether to use direct result storage
     * @throws Exception if transformation or reflection fails
     */
    private static void checkTransform(
            final SecretKey key,
            final boolean encrypting,
            final byte[] source,
            final byte[] expected,
            final int style,
            final boolean directOutput)
            throws Exception {
        ByteBuf input = input(source, style);
        ByteBuf output = directOutput ? Unpooled.directBuffer() : Unpooled.buffer();
        try {
            output.writeByte(71);
            var buffers = new FrameCipherBuffers();
            buffers.transform(cipher(key, encrypting), input, 3, source.length, output);
            assertArrayEquals(expected, ByteBufUtil.getBytes(output, 1, expected.length));
            assertArrayEquals(source, ByteBufUtil.getBytes(input, 3, source.length));
            assertEquals(3, input.readerIndex());
            assertEquals(source.length + 3, input.writerIndex());
            assertEquals(1, input.refCnt());
            assertEquals(1, output.refCnt());
            assertEquals(71, output.getByte(0));
            assertWiped(buffers);
        } finally {
            input.release();
            output.release();
        }
    }

    /**
     * Builds a source with a nonzero reader index in each supported storage layout.
     *
     * @param source bytes following the prefix
     * @param style heap slice, direct slice, read-only or composite
     * @return owned source buffer
     * @throws IllegalArgumentException if the layout is unknown
     */
    private static ByteBuf input(final byte[] source, final int style) {
        ByteBuf result =
                switch (style) {
                    case 0 -> Unpooled.buffer(source.length + 20).slice(7, source.length + 3);
                    case 1 -> Unpooled.directBuffer(source.length + 20).slice(7, source.length + 3);
                    case 2 -> Unpooled.buffer(source.length + 3);
                    case 3 ->
                            Unpooled.compositeBuffer()
                                    .addComponents(
                                            true,
                                            Unpooled.wrappedBuffer(new byte[3]),
                                            Unpooled.wrappedBuffer(new byte[source.length]));
                    default -> throw new IllegalArgumentException("Unknown layout");
                };
        result.clear().writeZero(3).writeBytes(source).readerIndex(3);
        return style == 2 ? result.asReadOnly() : result;
    }

    /**
     * Runs one direct-buffer operation while preserving exact ciphertext.
     *
     * @param buffers direction-local scratch owner
     * @param key synthetic session key
     * @param size plaintext byte count
     * @throws Exception if transformation fails
     */
    private static void runDirect(
            final FrameCipherBuffers buffers, final SecretKey key, final int size)
            throws Exception {
        byte[] plaintext = payload(size);
        ByteBuf input = Unpooled.directBuffer(size).writeBytes(plaintext);
        ByteBuf output = Unpooled.directBuffer(size + 16);
        try {
            buffers.transform(cipher(key, true), input, 0, size, output);
            assertArrayEquals(cipher(key, true).doFinal(plaintext), ByteBufUtil.getBytes(output));
        } finally {
            input.release();
            output.release();
        }
    }

    /**
     * Creates an isolated reference cipher with a fixed nonce used only by these fixtures.
     *
     * @param key synthetic session key
     * @param encrypting true for encryption
     * @return newly initialized reference cipher
     * @throws GeneralSecurityException if the provider rejects initialization
     */
    private static Cipher cipher(final SecretKey key, final boolean encrypting)
            throws GeneralSecurityException {
        Cipher result = Cipher.getInstance("AES/GCM/NoPadding");
        result.init(
                encrypting ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                key,
                new GCMParameterSpec(128, new byte[12]));
        result.updateAAD(new byte[] {1, 2, 3});
        return result;
    }

    /**
     * Produces nonzero plaintext to detect incomplete clearing.
     *
     * @param size payload length
     * @return synthetic payload
     */
    private static byte[] payload(final int size) {
        byte[] result = new byte[size];
        Arrays.fill(result, (byte) 91);
        return result;
    }

    /**
     * Verifies both the retained capacity bound and every scratch byte.
     *
     * @param buffers scratch owner under test
     * @throws ReflectiveOperationException if fields cannot be inspected
     */
    private static void assertWiped(final FrameCipherBuffers buffers)
            throws ReflectiveOperationException {
        for (String name : new String[] {"inputScratch", "outputScratch"}) {
            byte[] bytes = scratch(buffers, name);
            assertTrue(bytes.length <= FrameCipherBuffers.SCRATCH_LIMIT);
            assertArrayEquals(new byte[bytes.length], bytes);
        }
    }

    /**
     * Reads retained storage without exposing a production inspection API.
     *
     * @param buffers scratch owner under test
     * @param name scratch field name
     * @return retained array
     * @throws ReflectiveOperationException if the field cannot be read
     */
    private static byte[] scratch(final FrameCipherBuffers buffers, final String name)
            throws ReflectiveOperationException {
        Field field = FrameCipherBuffers.class.getDeclaredField(name);
        field.setAccessible(true);
        return (byte[]) field.get(buffers);
    }
}
