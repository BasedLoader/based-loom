/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
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

package net.fabricmc.loom.util.srg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.commons.io.IOUtils;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgReader;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.InnerClassMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.util.function.CollectionUtil;
import net.fabricmc.mapping.tree.ClassDef;
import net.fabricmc.mapping.tree.FieldDef;
import net.fabricmc.mapping.tree.MethodDef;
import net.fabricmc.mapping.tree.TinyMappingFactory;
import net.fabricmc.mapping.tree.TinyTree;
import net.fabricmc.mappingio.format.TsrgReader;
import net.fabricmc.stitch.commands.tinyv2.TinyClass;
import net.fabricmc.stitch.commands.tinyv2.TinyField;
import net.fabricmc.stitch.commands.tinyv2.TinyFile;
import net.fabricmc.stitch.commands.tinyv2.TinyHeader;
import net.fabricmc.stitch.commands.tinyv2.TinyMethod;
import net.fabricmc.stitch.commands.tinyv2.TinyV2Writer;

/**
 * Utilities for merging SRG mappings.
 *
 * @author Juuz
 */
public final class SrgMerger {
	/**
	 * Merges SRG mappings with a tiny mappings tree through the obf names.
	 *
	 * @param srg     the SRG file in .tsrg format
	 * @param tiny    the tiny file
	 * @param out     the output file, will be in tiny v2
	 * @param lenient whether to ignore missing tiny mapping
	 * @throws IOException      if an IO error occurs while reading or writing the mappings
	 * @throws MappingException if the input tiny tree's default namespace is not 'official'
	 *                          or if an element mentioned in the SRG file does not have tiny mappings
	 */
	public static void mergeSrg(Path srg, Path tiny, Path out, boolean lenient) throws IOException, MappingException {
		MappingSet arr = readSrg(srg);
		TinyTree foss;

		try (BufferedReader reader = Files.newBufferedReader(tiny)) {
			foss = TinyMappingFactory.loadWithDetection(reader);
		}

		List<String> namespaces = new ArrayList<>(foss.getMetadata().getNamespaces());
		namespaces.add(1, "srg");

		if (!"official".equals(namespaces.get(0))) {
			throw new MappingException("Mapping file " + tiny + " does not have the 'official' namespace as the default!");
		}

		TinyHeader header = new TinyHeader(namespaces, 2, 0, Collections.emptyMap());

		List<TinyClass> classes = new ArrayList<>();

		for (TopLevelClassMapping klass : arr.getTopLevelClassMappings()) {
			classToTiny(foss, namespaces, klass, classes::add, lenient);
		}

		TinyFile file = new TinyFile(header, classes);
		TinyV2Writer.write(file, out);
	}

	private static MappingSet readSrg(Path srg) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(srg)) {
			String content = IOUtils.toString(reader);

			if (content.startsWith("tsrg2")) {
				return readTsrg2(content);
			} else {
				try (TSrgReader srgReader = new TSrgReader(new StringReader(content))) {
					return srgReader.read();
				}
			}
		}
	}

	private static MappingSet readTsrg2(String content) throws IOException {
		MappingSet set;

		try (Tsrg2Utils.MappingsIO2LorenzWriter lorenzWriter = new Tsrg2Utils.MappingsIO2LorenzWriter(0, false)) {
			TsrgReader.read(new StringReader(content), lorenzWriter);
			set = lorenzWriter.read();
		}

		return set;
	}

	private static void classToTiny(TinyTree foss, List<String> namespaces, ClassMapping<?, ?> klass, Consumer<TinyClass> classConsumer, boolean lenient) {
		String obf = klass.getFullObfuscatedName();
		String srg = klass.getFullDeobfuscatedName();
		ClassDef classDef = foss.getDefaultNamespaceClassMap().get(obf);

		if (classDef == null) {
			if (lenient) {
				return;
			} else {
				throw new MappingException("Missing class: " + obf + " (srg: " + srg + ")");
			}
		}

		List<String> classNames = CollectionUtil.map(
				namespaces,
				namespace -> "srg".equals(namespace) ? srg : classDef.getName(namespace)
		);

		List<TinyMethod> methods = new ArrayList<>();
		List<TinyField> fields = new ArrayList<>();

		for (MethodMapping method : klass.getMethodMappings()) {
			MethodDef def = CollectionUtil.find(
					classDef.getMethods(),
					m -> m.getName("official").equals(method.getObfuscatedName()) && m.getDescriptor("official").equals(method.getObfuscatedDescriptor())
			).orElse(nullOrThrow(lenient, () -> new MappingException("Missing method: " + method.getFullObfuscatedName() + " (srg: " + method.getFullDeobfuscatedName() + ")")));

			if (def == null) continue;

			List<String> methodNames = CollectionUtil.map(
					namespaces,
					namespace -> "srg".equals(namespace) ? method.getDeobfuscatedName() : def.getName(namespace)
			);

			methods.add(new TinyMethod(
					def.getDescriptor("official"), methodNames,
					/* parameters */ Collections.emptyList(),
					/* locals */ Collections.emptyList(),
					/* comments */ Collections.emptyList()
			));
		}

		for (FieldMapping field : klass.getFieldMappings()) {
			FieldDef def = CollectionUtil.find(
					classDef.getFields(),
					f -> f.getName("official").equals(field.getObfuscatedName())
			).orElse(nullOrThrow(lenient, () -> new MappingException("Missing field: " + field.getFullObfuscatedName() + " (srg: " + field.getFullDeobfuscatedName() + ")")));

			if (def == null) continue;

			List<String> fieldNames = CollectionUtil.map(
					namespaces,
					namespace -> "srg".equals(namespace) ? field.getDeobfuscatedName() : def.getName(namespace)
			);

			fields.add(new TinyField(def.getDescriptor("official"), fieldNames, Collections.emptyList()));
		}

		TinyClass tinyClass = new TinyClass(classNames, methods, fields, Collections.emptyList());
		classConsumer.accept(tinyClass);

		for (InnerClassMapping innerKlass : klass.getInnerClassMappings()) {
			classToTiny(foss, namespaces, innerKlass, classConsumer, lenient);
		}
	}

	@Nullable
	private static <T, X extends Exception> T nullOrThrow(boolean lenient, Supplier<X> exception) throws X {
		if (lenient) {
			return null;
		} else {
			throw exception.get();
		}
	}
}
