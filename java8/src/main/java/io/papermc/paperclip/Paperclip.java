/*
 * Paperclip - Paper Minecraft launcher
 *
 * Copyright (c) 2019 Kyle Wood (DemonWav)
 * https://github.com/PaperMC/Paperclip
 *
 * MIT License
 */

package io.papermc.paperclip;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.jar.JarInputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.jbsdiff.InvalidHeaderException;
import org.jbsdiff.Patch;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class Paperclip {

    private static int getVersion() {
        String version = System.getProperty("java.version");
        if(version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf(".");
            if(dot != -1) { version = version.substring(0, dot); }
        } return Integer.parseInt(version);
    }

    public static void main(final String[] args) {
        if (getVersion() < 11) {
            System.err.println("Java 11 or higher is required to use Purpur!");
            System.err.println("");
            System.err.println("Please update your version of java!");
            System.err.println("");
            return;
        }

        final Method mainMethod;
        {
            final Path paperJar = setupEnv();
            final String main = getMainClass(paperJar);
            mainMethod = getMainMethod(paperJar, main);
        }

        // By making sure there are no other variables in scope when we run mainMethod.invoke we allow the JVM to
        // GC any objects allocated during the downloading + patching process, minimizing paperclip's overhead as
        // much as possible
        try {
            mainMethod.invoke(null, new Object[] {args});
        } catch (final IllegalAccessException | InvocationTargetException e) {
            System.err.println("Error while running patched jar");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Path setupEnv() {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            System.err.println("Could not create hashing instance");
            e.printStackTrace();
            System.exit(1);
            throw new InternalError();
        }

        final PatchData patchData;
        try (
            final InputStream defaultsInput = Paperclip.class.getResourceAsStream("/patch.properties");
            final Reader defaultsReader = new BufferedReader(new InputStreamReader(defaultsInput));
            final Reader optionalReader = getConfig()
        ) {
            patchData = PatchData.parse(defaultsReader, optionalReader);
        } catch (final IOException | IllegalArgumentException e) {
            if (e instanceof IOException) {
                System.err.println("Error reading patch file");
            } else {
                System.err.println("Invalid patch file");
            }
            e.printStackTrace();
            System.exit(1);
            throw new InternalError();
        }

        final Path paperJar = checkPaperJar(digest, patchData);

        // Exit if user has set `paperclip.patchonly` system property to `true`
        if (Boolean.getBoolean("paperclip.patchonly")) {
            System.exit(0);
        }

        return paperJar;
    }

    private static Path checkPaperJar(
        final MessageDigest digest,
        final PatchData patchData
    ) {
        final Path cache = Paths.get("cache");
        final Path paperJar = cache.resolve("patched_" + patchData.version + ".jar");

        if (!isJarInvalid(digest, paperJar, patchData.patchedHash)) {
            return paperJar;
        }

        final Path vanillaJar = checkVanillaJar(digest, patchData, cache);

        if (Files.exists(paperJar)) {
            try {
                Files.delete(paperJar);
            } catch (final IOException e) {
                System.err.println("Failed to delete invalid jar " + paperJar.toAbsolutePath());
                e.printStackTrace();
                System.exit(1);
            }
        }

        System.out.println("Patching vanilla jar...");
        final byte[] vanillaJarBytes;
        final byte[] patch;
        try {
            vanillaJarBytes = readBytes(vanillaJar);
            patch = readFully(patchData.patchFile.openStream());
        } catch (final IOException e) {
            System.err.println("Failed to read vanilla jar and patch file");
            e.printStackTrace();
            System.exit(1);
            throw new InternalError();
        }

        // Patch the jar to create the final jar to run
        try (
            final OutputStream jarOutput =
                 new BufferedOutputStream(Files.newOutputStream(paperJar, CREATE, WRITE, TRUNCATE_EXISTING))
        ) {
            Patch.patch(vanillaJarBytes, patch, jarOutput);
        } catch (final CompressorException | InvalidHeaderException | IOException e) {
            System.err.println("Failed to patch vanilla jar");
            e.printStackTrace();
            System.exit(1);
        }

        // Only continue from here if the patched jar is correct
        if (isJarInvalid(digest, paperJar, patchData.patchedHash)) {
            System.err.println("Failed to patch vanilla jar, output patched jar is still not valid");
            System.exit(1);
        }

        return paperJar;
    }

    private static Path checkVanillaJar(
        final MessageDigest digest,
        final PatchData patchData,
        final Path cache
    ) {
        final Path vanillaJar = cache.resolve("mojang_" + patchData.version + ".jar");
        if (!isJarInvalid(digest, vanillaJar, patchData.originalHash)) {
            return vanillaJar;
        }

        System.out.println("Downloading vanilla jar...");
        try {
            if (!Files.isDirectory(cache)) {
                Files.createDirectories(cache);
            }
            Files.deleteIfExists(vanillaJar);
        } catch (final IOException e) {
            System.err.println("Failed to setup cache directory");
            e.printStackTrace();
            System.exit(1);
        }

        try (
            final ReadableByteChannel source = Channels.newChannel(patchData.originalUrl.openStream());
            final FileChannel fileChannel = FileChannel.open(vanillaJar, CREATE, WRITE, TRUNCATE_EXISTING)
        ) {
            fileChannel.transferFrom(source, 0, Long.MAX_VALUE);
        } catch (final IOException e) {
            System.err.println("Failed to download vanilla jar");
            e.printStackTrace();
            System.exit(1);
        }

        // Only continue from here if the downloaded jar is correct
        if (isJarInvalid(digest, vanillaJar, patchData.originalHash)) {
            System.err.println("Downloaded vanilla jar is not valid");
            System.exit(1);
        }

        return vanillaJar;
    }

    private static String getMainClass(final Path paperJar) {
        try (
            final InputStream is = new BufferedInputStream(Files.newInputStream(paperJar));
            final JarInputStream js = new JarInputStream(is)
        ) {
            return js.getManifest().getMainAttributes().getValue("Main-Class");
        } catch (final IOException e) {
            System.err.println("Error reading from patched jar");
            e.printStackTrace();
            System.exit(1);
            throw new InternalError();
        }
    }

    private static Method getMainMethod(final Path paperJar, final String mainClass) {
        Agent.addToClassPath(paperJar);
        try {
            final Class<?> cls = Class.forName(mainClass, true, ClassLoader.getSystemClassLoader());
            return cls.getMethod("main", String[].class);
        } catch (final NoSuchMethodException | ClassNotFoundException e) {
            System.err.println("Failed to find main method in patched jar");
            e.printStackTrace();
            System.exit(1);
            throw new InternalError();
        }
    }

    private static Reader getConfig() throws IOException {
        final Path customPatchInfo = Paths.get("paperclip.properties");
        if (Files.exists(customPatchInfo)) {
            return Files.newBufferedReader(customPatchInfo);
        } else {
            return null;
        }
    }

    private static byte[] readFully(final InputStream in) throws IOException {
        try {
            // In a test this was 12 ms quicker than a ByteBuffer
            // and for some reason that matters here.
            byte[] buffer = new byte[16 * 1024];
            int off = 0;
            int read;
            while ((read = in.read(buffer, off, buffer.length - off)) != -1) {
                off += read;
                if (off == buffer.length) {
                    buffer = Arrays.copyOf(buffer, buffer.length * 2);
                }
            }
            return Arrays.copyOfRange(buffer, 0, off);
        } finally {
            in.close();
        }
    }

    private static byte[] readBytes(final Path file) {
        try {
            return readFully(Files.newInputStream(file));
        } catch (final IOException e) {
            System.err.println("Failed to read all of the data from " + file.toAbsolutePath());
            e.printStackTrace();
            System.exit(1);
            throw new InternalError();
        }
    }

    private static boolean isJarInvalid(final MessageDigest digest, final Path jar, final byte[] hash) {
        if (Files.exists(jar)) {
            final byte[] jarBytes = readBytes(jar);
            return !Arrays.equals(hash, digest.digest(jarBytes));
        }
        return true;
    }
}
