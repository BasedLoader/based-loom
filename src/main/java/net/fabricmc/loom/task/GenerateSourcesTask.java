/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
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

package net.fabricmc.loom.task;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.gradle.workers.internal.WorkerDaemonClientsManager;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.decompilers.DecompilationMetadata;
import net.fabricmc.loom.api.decompilers.DecompilerOptions;
import net.fabricmc.loom.api.decompilers.LoomDecompiler;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerFile;
import net.fabricmc.loom.configuration.accesswidener.TransitiveAccessWidenerMappingsProcessor;
import net.fabricmc.loom.decompilers.LineNumberRemapper;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.IOStringConsumer;
import net.fabricmc.loom.util.OperatingSystem;
import net.fabricmc.loom.util.gradle.ThreadedProgressLoggerConsumer;
import net.fabricmc.loom.util.gradle.ThreadedSimpleProgressLogger;
import net.fabricmc.loom.util.gradle.WorkerDaemonClientsManagerHelper;
import net.fabricmc.loom.util.ipc.IPCClient;
import net.fabricmc.loom.util.ipc.IPCServer;

public abstract class GenerateSourcesTask extends AbstractLoomTask implements DecompilationTask {
	private final DecompilerOptions decompilerOptions;

	/**
	 * The jar to decompile, can be the unpick jar.
	 */
	@Override
	@InputFile
	public abstract RegularFileProperty getInputJar();

	/**
	 * The jar used at runtime.
	 */
	@Override
	@InputFile
	public abstract RegularFileProperty getRuntimeJar();

	@Inject
	public abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public abstract WorkerDaemonClientsManager getWorkerDaemonClientsManager();

	@Inject
	public GenerateSourcesTask(DecompilerOptions decompilerOptions) {
		this.decompilerOptions = decompilerOptions;

		getOutputs().upToDateWhen((o) -> false);
	}

	@TaskAction
	public void run() throws IOException {
		if (!OperatingSystem.is64Bit()) {
			throw new UnsupportedOperationException("GenSources task requires a 64bit JVM to run due to the memory requirements.");
		}

		if (!OperatingSystem.isUnixDomainSocketsSupported()) {
			getProject().getLogger().warn("Decompile worker logging disabled as Unix Domain Sockets is not supported on your operating system.");

			doWork(null);
			return;
		}

		// Set up the IPC path to get the log output back from the forked JVM
		final Path ipcPath = Files.createTempFile("loom", "ipc");
		Files.deleteIfExists(ipcPath);

		try (ThreadedProgressLoggerConsumer loggerConsumer = new ThreadedProgressLoggerConsumer(getProject(), decompilerOptions.getName(), "Decompiling minecraft sources");
				IPCServer logReceiver = new IPCServer(ipcPath, loggerConsumer)) {
			doWork(logReceiver);
		} catch (InterruptedException e) {
			throw new RuntimeException("Failed to shutdown log receiver", e);
		} finally {
			Files.deleteIfExists(ipcPath);
		}
	}

	private void doWork(@Nullable IPCServer ipcServer) {
		final String jvmMarkerValue = UUID.randomUUID().toString();
		final WorkQueue workQueue = createWorkQueue(jvmMarkerValue);

		workQueue.submit(DecompileAction.class, params -> {
			params.getDecompilerOptions().set(decompilerOptions.toDto());

			params.getInputJar().set(getInputJar());
			params.getRuntimeJar().set(getRuntimeJar());
			params.getSourcesDestinationJar().set(getMappedJarFileWithSuffix("-sources.jar"));
			params.getLinemap().set(getMappedJarFileWithSuffix("-sources.lmap"));
			params.getLinemapJar().set(getMappedJarFileWithSuffix("-linemapped.jar"));
			params.getMappings().set(getMappings(getProject(), getExtension()).toFile());

			if (ipcServer != null) {
				params.getIPCPath().set(ipcServer.getPath().toFile());
			}

			params.getClassPath().setFrom(getProject().getConfigurations().getByName(Constants.Configurations.MINECRAFT_DEPENDENCIES));
		});

		try {
			workQueue.await();
		} finally {
			if (ipcServer != null) {
				boolean stopped = WorkerDaemonClientsManagerHelper.stopIdleJVM(getWorkerDaemonClientsManager(), jvmMarkerValue);

				if (!stopped && ipcServer.hasReceivedMessage()) {
					throw new RuntimeException("Failed to stop decompile worker JVM");
				}
			}
		}
	}

	private WorkQueue createWorkQueue(String jvmMarkerValue) {
		if (!useProcessIsolation()) {
			return getWorkerExecutor().noIsolation();
		}

		return getWorkerExecutor().processIsolation(spec -> {
			spec.forkOptions(forkOptions -> {
				forkOptions.setMaxHeapSize("%dm".formatted(decompilerOptions.getMemory().get()));
				forkOptions.systemProperty(WorkerDaemonClientsManagerHelper.MARKER_PROP, jvmMarkerValue);
				forkOptions.bootstrapClasspath(decompilerOptions.getClasspath());
			});
		});
	}

	private boolean useProcessIsolation() {
		// Useful if you want to debug the decompiler, make sure you run gradle with enough memory.
		return !Boolean.getBoolean("fabric.loom.genSources.debug");
	}

	public interface DecompileParams extends WorkParameters {
		Property<DecompilerOptions.Dto> getDecompilerOptions();

		RegularFileProperty getInputJar();
		RegularFileProperty getRuntimeJar();
		RegularFileProperty getSourcesDestinationJar();
		RegularFileProperty getLinemap();
		RegularFileProperty getLinemapJar();
		RegularFileProperty getMappings();

		RegularFileProperty getIPCPath();

		ConfigurableFileCollection getClassPath();
	}

	public abstract static class DecompileAction implements WorkAction<DecompileParams> {
		@Override
		public void execute() {
			if (!getParameters().getIPCPath().isPresent() || !OperatingSystem.isUnixDomainSocketsSupported()) {
				// Does not support unix domain sockets, print to sout.
				doDecompile(System.out::println);
				return;
			}

			final Path ipcPath = getParameters().getIPCPath().get().getAsFile().toPath();

			try (IPCClient ipcClient = new IPCClient(ipcPath)) {
				doDecompile(new ThreadedSimpleProgressLogger(ipcClient));
			} catch (Exception e) {
				throw new RuntimeException("Failed to decompile", e);
			}
		}

		private void doDecompile(IOStringConsumer logger) {
			final Path inputJar = getParameters().getInputJar().get().getAsFile().toPath();
			final Path sourcesDestinationJar = getParameters().getSourcesDestinationJar().get().getAsFile().toPath();
			final Path linemap = getParameters().getLinemap().get().getAsFile().toPath();
			final Path linemapJar = getParameters().getLinemapJar().get().getAsFile().toPath();
			final Path runtimeJar = getParameters().getRuntimeJar().get().getAsFile().toPath();

			final DecompilerOptions.Dto decompilerOptions = getParameters().getDecompilerOptions().get();

			final LoomDecompiler decompiler;

			try {
				final String className = decompilerOptions.className();
				final Constructor<LoomDecompiler> decompilerConstructor = getDecompilerConstructor(className);
				Objects.requireNonNull(decompilerConstructor, "%s must have a no args constructor".formatted(className));

				decompiler = decompilerConstructor.newInstance();
			} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
				throw new RuntimeException("Failed to create decompiler", e);
			}

			DecompilationMetadata metadata = new DecompilationMetadata(
					decompilerOptions.maxThreads(),
					getParameters().getMappings().get().getAsFile().toPath(),
					getLibraries(),
					logger,
					decompilerOptions.options()
			);

			decompiler.decompile(
					inputJar,
					sourcesDestinationJar,
					linemap,
					metadata
			);

			// Close the decompile loggers
			try {
				metadata.logger().accept(ThreadedProgressLoggerConsumer.CLOSE_LOGGERS);
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to close loggers", e);
			}

			if (Files.exists(linemap)) {
				try {
					// Line map the actually jar used to run the game, not the one used to decompile
					remapLineNumbers(metadata.logger(), runtimeJar, linemap, linemapJar);

					Files.copy(linemapJar, runtimeJar, StandardCopyOption.REPLACE_EXISTING);
					Files.delete(linemapJar);
				} catch (IOException e) {
					throw new UncheckedIOException("Failed to remap line numbers", e);
				}
			}
		}

		static void remapLineNumbers(IOStringConsumer logger, Path oldCompiledJar, Path linemap, Path linemappedJarDestination) throws IOException {
			LineNumberRemapper remapper = new LineNumberRemapper();
			remapper.readMappings(linemap.toFile());

			try (FileSystemUtil.Delegate inFs = FileSystemUtil.getJarFileSystem(oldCompiledJar.toFile(), true);
					FileSystemUtil.Delegate outFs = FileSystemUtil.getJarFileSystem(linemappedJarDestination.toFile(), true)) {
				remapper.process(logger, inFs.get().getPath("/"), outFs.get().getPath("/"));
			}
		}

		private Collection<Path> getLibraries() {
			return toPaths(getParameters().getClassPath());
		}

		static Collection<Path> toPaths(FileCollection files) {
			return files.getFiles().stream().map(File::toPath).collect(Collectors.toSet());
		}
	}

	private File getMappedJarFileWithSuffix(String suffix) {
		return getMappedJarFileWithSuffix(getRuntimeJar(), suffix);
	}

	static File getMappedJarFileWithSuffix(RegularFileProperty runtimeJar, String suffix) {
		String path = runtimeJar.get().getAsFile().getAbsolutePath();

		if (!path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
			throw new RuntimeException("Invalid mapped JAR path: " + path);
		}

		return new File(path.substring(0, path.length() - 4) + suffix);
	}

	static Path getMappings(Project project, LoomGradleExtension extension) {
		Path baseMappings = extension.isForge() ? extension.getMappingsProvider().tinyMappingsWithSrg : extension.getMappingsProvider().tinyMappings;

		if (extension.getEnableTransitiveAccessWideners().get()) {
			List<AccessWidenerFile> accessWideners = extension.getTransitiveAccessWideners();

			if (accessWideners.isEmpty()) {
				return baseMappings;
			}

			Path outputMappings;

			try {
				outputMappings = Files.createTempFile("loom-transitive-mappings", ".tiny");
			} catch (IOException e) {
				throw new RuntimeException("Failed to create temp file", e);
			}

			TransitiveAccessWidenerMappingsProcessor.process(baseMappings, outputMappings, accessWideners, project.getLogger());

			return outputMappings;
		}

		return baseMappings;
	}

	private static Constructor<LoomDecompiler> getDecompilerConstructor(String clazz) {
		try {
			//noinspection unchecked
			return (Constructor<LoomDecompiler>) Class.forName(clazz).getConstructor();
		} catch (NoSuchMethodException | ClassNotFoundException e) {
			return null;
		}
	}
}
