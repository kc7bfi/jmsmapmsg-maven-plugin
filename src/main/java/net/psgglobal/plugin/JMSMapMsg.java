package net.psgglobal.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.velocity.Template;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.apache.velocity.tools.ToolContext;
import org.apache.velocity.tools.ToolManager;

import com.codesnippets4all.json.parsers.JSONParser;
import com.codesnippets4all.json.parsers.JsonParserFactory;

/*
This file is part of wsrpc.

wsrpc is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

wsrpc is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with wsrpc.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * The plugin class
 */
@Mojo(name = "generate")
@Execute(phase = LifecyclePhase.GENERATE_SOURCES)
public class JMSMapMsg extends AbstractMojo {

	@Parameter(defaultValue = "${project.basedir}/src/main/resources/jmsmapmsg", property = "inputDir", required = true)
	private File inputDir;

	@Parameter(defaultValue = "${project.build.directory}/generated-sources/jmsmapmsg/java", property = "outputDir", required = true)
	private File outputDir;

	@Parameter(defaultValue = "${project}")
	private MavenProject project;

	/**
	 * Execute the plugin
	 * @throws MojoExecutionException any errors
	 */
	@SuppressWarnings("unchecked")
	public void execute() throws MojoExecutionException {

		// process each specification file
		if (inputDir == null) throw new MojoExecutionException("Cannot find inputDir");
		if (!inputDir.exists()) throw new MojoExecutionException("inputDir does not exist");

		getLog().info("inputDir = " + inputDir.getAbsolutePath());
		getLog().info("outputDir = " + outputDir.getAbsolutePath());

		for (File specificationFile : inputDir.listFiles()) {

			// read the specification file
			BufferedReader reader = null;
			String specificationSource = null;
			try {
				reader = new BufferedReader(new FileReader(specificationFile));
				specificationSource = readAsString(reader);
			} catch (IOException e) {
				getLog().warn("Error reading input sorce files: " + e.getMessage());
				throw new MojoExecutionException("Error reading input sorce files", e);
			} finally {
				if (reader != null) try { reader.close(); } catch (Exception e) { getLog().warn("Could not close reader " + e.getMessage()); }
			}
			JSONParser parser = JsonParserFactory.getInstance().newJsonParser();
			Map<String, Object> specification = null;
			try {
				specification = parser.parseJson(specificationSource);
			} catch (Exception e) {
				int i0 = e.getMessage().indexOf("Position::");
				if (i0 > 0) {
					int at = Integer.parseInt(e.getMessage().substring(i0 + "Position::".length()));
					getLog().info("JSON error at " + specificationSource.substring(at));
				}
				throw new MojoExecutionException("Cannot parse specification file", e);
			}
			String specName = (String) specification.get("name");
			String specPackage = (String) specification.get("package");

			javaGenerateEnumFiles(specName, specPackage, specification);
			javaGenerateMessageFiles(specName, specPackage, specification);

			project.addCompileSourceRoot(outputDir.getAbsolutePath());
		}
	}

	/**
	 * Read an entire resource as a string
	 * @param reader the buffered reader
	 * @return the string
	 * @throws IOException any errors
	 */
	private String readAsString(BufferedReader reader) throws IOException {
		StringBuilder str = new StringBuilder();
		for (String ln = reader.readLine(); ln != null; ln = reader.readLine()) str.append(ln + "\n");
		return str.toString();
	}

	/**
	 * Capitalize a string
	 * @param name the name
	 * @return the name capitalized
	 */
	private String capitalize(String name) {
		return name.substring(0, 1).toUpperCase() + name.substring(1);
	}

	/**
	 * Create the directories for the generated files
	 * @param specName the specification name
	 * @param specPackage the package
	 * @return the package path
	 */
	private String createPackageDirs(String specName, String specPackage) {
		StringBuilder pathName = new StringBuilder(outputDir.getAbsolutePath() + "/");
		String[] subdirNames = specPackage.split("\\.");
		for (String subdirName : subdirNames) pathName.append(subdirName + "/");
		File pathFile = new File(pathName.toString());
		pathFile.mkdirs();
		return pathName.toString();
	}

	/**
	 * Generate all the classes files
	 * @param specName the specification name
	 * @param specPackage the specification package
	 * @param specification the specification
	 * @throws MojoExecutionException Errors
	 */
	@SuppressWarnings("unchecked")
	private void javaGenerateMessageFiles(String specName, String specPackage, Map<String, Object> specification) throws MojoExecutionException {
		List<Map<String, Object>> claszs = (List<Map<String, Object>>) specification.get("messages");
		if (claszs == null) return;
		for (Map<String, Object> clasz : claszs) {
			javaGenerateMessageFile(specName, specPackage, clasz);
		}
	}

	/**
	 * Generate a single class file
	 * @param specName the specification name
	 * @param specPackage the specification package
	 * @param classSpecification the class specification
	 * @throws MojoExecutionException errors
	 */
	@SuppressWarnings("unchecked")
	private void javaGenerateMessageFile(String specName, String specPackage, Map<String, Object> classSpecification) throws MojoExecutionException {

		// initialize the Velocity engine
		VelocityEngine velocityEngine = new VelocityEngine();
		velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
		velocityEngine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
		velocityEngine.init();
		Template vilocityTemplate = velocityEngine.getTemplate("templates/java/MessageTemplate.vm");

		// use standard tools
		Map<String, Object> toolProperties = new HashMap<String, Object>();
		toolProperties.put("engine", velocityEngine);
		ToolManager toolManager = new ToolManager(true, true);

		// set up the Velocity context model
		ToolContext velocityContext = toolManager.createContext();
		velocityContext.put("packageName", specPackage);
		velocityContext.put("className", classSpecification.get("name"));
		velocityContext.put("classJavadoc", classSpecification.get("javadoc"));
		velocityContext.put("properties", getParametersMap((List<Map<String, Object>>) classSpecification.get("properties")));

		// generate the code
		StringWriter codeWriter = new StringWriter();
		vilocityTemplate.merge(velocityContext, codeWriter);

		// save the code
		String dirPath = createPackageDirs(specName, specPackage);
		String sourceCodePath = dirPath + capitalize((String) classSpecification.get("name")) + ".java";
		File claszCodeFile = new File(sourceCodePath);
		try {
			claszCodeFile.createNewFile();
		} catch (IOException e) {
			getLog().warn("Cannot create source code file " + sourceCodePath);
			throw new MojoExecutionException("Cannot create source code file");
		}
		FileWriter writer = null;
		try {
			writer = new FileWriter(claszCodeFile);
			writer.write(codeWriter.toString());
			getLog().info("Wrote " + sourceCodePath);
		} catch (IOException e) {
			getLog().warn("Cannot write source code file " + sourceCodePath + ": " + e.getMessage());
			throw new MojoExecutionException("Cannot write source code file");
		} finally {
			if (writer != null) try { writer.close(); } catch (Exception e) { getLog().warn("Could not close writer: " + e.getMessage()); }
		}
	}

	/**
	 * Generate all the constants files
	 * @param specName the specification name
	 * @param specPackage the specification package
	 * @param specification the specification
	 * @throws MojoExecutionException Errors
	 */
	@SuppressWarnings("unchecked")
	private void javaGenerateEnumFiles(String specName, String specPackage, Map<String, Object> specification) throws MojoExecutionException {
		List<Map<String, Object>> enums = (List<Map<String, Object>>) specification.get("enums");
		if (enums == null) return;
		for (Map<String, Object> enumm : enums) {
			javaGenerateEnumFile(specName, specPackage, enumm);
		}
	}

	/**
	 * Generate a single constants file
	 * @param specName the specification name
	 * @param specPackage the specification package
	 * @param enumSpecification the constant specification
	 * @throws MojoExecutionException errors
	 */
	@SuppressWarnings("unchecked")
	private void javaGenerateEnumFile(String specName, String specPackage, Map<String, Object> enumSpecification) throws MojoExecutionException {

		// initialize the Velocity engine
		VelocityEngine velocityEngine = new VelocityEngine();
		velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
		velocityEngine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
		velocityEngine.init();
		Template vilocityTemplate = velocityEngine.getTemplate("templates/java/EnumTemplate.vm");

		// use standard tools
		Map<String, Object> toolProperties = new HashMap<String, Object>();
		toolProperties.put("engine", velocityEngine);
		ToolManager toolManager = new ToolManager(true, true);

		// set up the Velocity context model
		ToolContext velocityContext = toolManager.createContext();
		velocityContext.put("packageName", specPackage);
		velocityContext.put("enumName", enumSpecification.get("name"));
		velocityContext.put("enumJavadoc", enumSpecification.get("javadoc"));
		velocityContext.put("members", getParametersMap((List<Map<String, Object>>) enumSpecification.get("members")));

		// generate the code
		StringWriter codeWriter = new StringWriter();
		vilocityTemplate.merge(velocityContext, codeWriter);

		// save the code
		String dirPath = createPackageDirs(specName, specPackage);
		String sourceCodePath = dirPath + capitalize((String) enumSpecification.get("name")) + ".java";
		File claszCodeFile = new File(sourceCodePath);
		try {
			claszCodeFile.createNewFile();
		} catch (IOException e) {
			getLog().warn("Cannot create source code file " + sourceCodePath);
			throw new MojoExecutionException("Cannot create source code file");
		}
		FileWriter writer = null;
		try {
			writer = new FileWriter(claszCodeFile);
			writer.write(codeWriter.toString());
			getLog().info("Wrote " + sourceCodePath);
		} catch (IOException e) {
			getLog().warn("Cannot write source code file " + sourceCodePath + ": " + e.getMessage());
			throw new MojoExecutionException("Cannot write source code file");
		} finally {
			if (writer != null) try { writer.close(); } catch (Exception e) { getLog().warn("Could not close writer: " + e.getMessage()); }
		}
	}

	/**
	 * Gather the parameter/members elements
	 * @param paramatersSpec the parameter specification
	 * @return the parameters map
	 */
	private List<Map<String, String>> getParametersMap(List<Map<String, Object>> paramatersSpec) {
		List<Map<String, String>> parameters = new LinkedList<Map<String, String>>();
		if (paramatersSpec != null) {
			for (Map<String, Object> paramaterSpec : paramatersSpec) {
				Map<String, String> parameter = new HashMap<String, String>();
				parameter.put("name", (String) paramaterSpec.get("name"));
				parameter.put("type", (String) paramaterSpec.get("type"));
				parameter.put("value", (String) paramaterSpec.get("value"));
				parameter.put("javadoc", (String) paramaterSpec.get("javadoc"));
				parameters.add(parameter);
			}
		}
		return parameters;
	}
}
