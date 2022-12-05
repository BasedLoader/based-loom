/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2022 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import org.gradle.api.logging.Logger;
import org.jetbrains.annotations.Nullable;

public final class ModUtils {
	private ModUtils() {
	}

	public static boolean isMod(File file, ModPlatform platform) {
		return isMod(file.toPath(), platform);
	}

	public static boolean isMod(Path input, ModPlatform platform) {
		if (platform == ModPlatform.FORGE) {
			return ZipUtils.contains(input, "META-INF/mods.toml");
		} else if (platform == ModPlatform.QUILT) {
			return ZipUtils.contains(input, "quilt.mod.json") || isMod(input, ModPlatform.FABRIC);
		}

		return ZipUtils.contains(input, "fabric.mod.json");
	}

	@Nullable
	public static JsonObject getFabricModJson(Path path) {
		try {
			return ZipUtils.unpackGsonNullable(path, "fabric.mod.json", JsonObject.class);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to extract fabric.mod.json from " + path, e);
		}
	}

	public static boolean shouldRemapMod(Logger logger, Path input, ModPlatform platform, String config) {
		if (ZipUtils.contains(input, "architectury.common.marker")) return true;
		if (isMod(input, platform)) return true;

		if (platform == ModPlatform.FORGE) {
			logger.lifecycle(":could not find forge mod in " + config + " but forcing: {}", input.getFileName());
			return true;
		}

		return false;
	}
}
