/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.api.decompilers.architectury;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.process.ExecResult;
import org.gradle.process.JavaExecSpec;

import net.fabricmc.loom.task.GenerateSourcesTask;

public interface ArchitecturyLoomDecompiler {
	String name();

	void decompile(Logger logger, GenerateSourcesTask.DecompileParams params);

	static ExecResult javaexec(Project project, Action<? super JavaExecSpec> action) {
		return project.javaexec(spec -> {
			spec.classpath(getClasspath(project));
			action.execute(spec);
		});
	}

	private static Object getClasspath(Project project) {
		return getRuntimeClasspath(project.getRootProject().getPlugins().hasPlugin("fabric-loom") ? project.getRootProject() : project);
	}

	private static FileCollection getRuntimeClasspath(Project project) {
		ConfigurationContainer configurations = project.getBuildscript().getConfigurations();
		DependencyHandler handler = project.getDependencies();
		return configurations.getByName("classpath")
				.plus(project.getRootProject().getBuildscript().getConfigurations().getByName("classpath"))
				.plus(configurations.detachedConfiguration(handler.localGroovy()));
	}
}
