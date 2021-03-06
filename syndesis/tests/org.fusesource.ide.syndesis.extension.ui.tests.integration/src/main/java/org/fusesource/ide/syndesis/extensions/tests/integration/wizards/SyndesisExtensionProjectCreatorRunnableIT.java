/******************************************************************************* 
 * Copyright (c) 2017 Red Hat, Inc. 
 * Distributed under license by Red Hat, Inc. All rights reserved. 
 * This program is made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.eclipse.org/legal/epl-v10.html 
 * 
 * Contributors: 
 * Red Hat, Inc. - initial API and implementation 
 ******************************************************************************/
package org.fusesource.ide.syndesis.extensions.tests.integration.wizards;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.function.Predicate;

import javax.management.MalformedObjectNameException;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.embedder.IMavenExecutionContext;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.WorkbenchException;
import org.fusesource.ide.camel.editor.CamelEditor;
import org.fusesource.ide.camel.tests.util.AbstractProjectCreatorRunnableIT;
import org.fusesource.ide.camel.tests.util.CommonTestUtils;
import org.fusesource.ide.foundation.core.util.Strings;
import org.fusesource.ide.foundation.ui.util.ScreenshotUtil;
import org.fusesource.ide.preferences.initializer.StagingRepositoriesPreferenceInitializer;
import org.fusesource.ide.syndesis.extensions.core.model.SyndesisExtension;
import org.fusesource.ide.syndesis.extensions.core.util.SyndesisExtensionsUtil;
import org.fusesource.ide.syndesis.extensions.tests.integration.SyndesisExtensionIntegrationTestsActivator;
import org.fusesource.ide.syndesis.extensions.ui.templates.BasicSyndesisExtensionXmlProjectTemplate;
import org.fusesource.ide.syndesis.extensions.ui.util.NewSyndesisExtensionProjectMetaData;
import org.fusesource.ide.syndesis.extensions.ui.wizards.SyndesisExtensionProjectCreatorRunnable;
import org.junit.Before;

/**
 * @author lheinema
 */
public abstract class SyndesisExtensionProjectCreatorRunnableIT extends AbstractProjectCreatorRunnableIT {

	protected static final String CAMEL_RESOURCE_PATH = "src/main/resources/camel/extension.xml";
	
	boolean buildFinished = false;
	boolean buildOK = false;
	
	@Before
	public void setup() throws WorkbenchException {
		SyndesisExtensionIntegrationTestsActivator.pluginLog().logInfo("Starting setup for "+ SyndesisExtensionProjectCreatorRunnableIT.class.getSimpleName());
		CommonTestUtils.prepareIntegrationTestLaunch(SCREENSHOT_FOLDER);

		String projectName = project != null ? project.getName() : String.format("%s", getClass().getSimpleName());
		ScreenshotUtil.saveScreenshotToFile(String.format("%s/MavenLaunchOutput-%s_BEFORE.png", SCREENSHOT_FOLDER, projectName), SWT.IMAGE_PNG);

		// TODO: for now we need the staging repos, disable before GA
		new StagingRepositoriesPreferenceInitializer().setStagingRepositoriesEnablement(true);

		SyndesisExtensionIntegrationTestsActivator.pluginLog().logInfo("End setup for "+ SyndesisExtensionProjectCreatorRunnableIT.class.getSimpleName());
	}

	private SyndesisExtension createDefaultNewSyndesisExtension() {
		SyndesisExtension extension = new SyndesisExtension();
		SyndesisExtensionsUtil.IgniteVersionInfoModel model = SyndesisExtensionsUtil.getIgniteVersionModel();
		extension.setSpringBootVersion(model.getSpringBootVersion());
		extension.setCamelVersion(model.getCamelVersion());
		extension.setSyndesisVersion(model.getSyndesisVersion());
		extension.setExtensionId("com.acme.custom");
		extension.setVersion("1.0.0");
		extension.setName("ACME Custom Extension");
		extension.setDescription("ACME Custom Extension Filter");
		extension.setTags(Arrays.asList("test", "acme"));
		return extension;
	}

	protected NewSyndesisExtensionProjectMetaData createDefaultNewProjectMetadata(final String projectName) {
		NewSyndesisExtensionProjectMetaData metadata = new NewSyndesisExtensionProjectMetaData();
		metadata.setProjectName(projectName);
		metadata.setLocationPath(null);
		metadata.setSyndesisExtensionConfig(createDefaultNewSyndesisExtension());
		metadata.setTemplate(new BasicSyndesisExtensionXmlProjectTemplate());
		return metadata;
	}
	
	protected void testProjectCreation(String projectNameSuffix, String camelPath, String syndesisPath) throws InterruptedException, InvocationTargetException, CoreException, MalformedObjectNameException, IOException {
		final String projectName = getClass().getSimpleName() + projectNameSuffix;
		SyndesisExtensionIntegrationTestsActivator.pluginLog().logInfo("Starting creation of the project: "+projectName);
		assertThat(ResourcesPlugin.getWorkspace().getRoot().getProject(projectName).exists()).isFalse();

		NewSyndesisExtensionProjectMetaData metaData = createDefaultNewProjectMetadata(projectName);

		new ProgressMonitorDialog(Display.getDefault().getActiveShell()).run(false, true, new SyndesisExtensionProjectCreatorRunnable(metaData));

		project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);

		assertThat(project.exists()).describedAs("The project "+ project.getName()+ " doesn't exist.").isTrue();
		SyndesisExtensionIntegrationTestsActivator.pluginLog().logInfo("Project created: "+projectName);
		
		final IFile camelResource = project.getFile(Strings.isBlank(camelPath) ? CAMEL_RESOURCE_PATH : camelPath);
		assertThat(camelResource.exists()).isTrue();

		final IFile syndesisResource = project.getFile(Strings.isBlank(syndesisPath) ? SyndesisExtensionProjectCreatorRunnable.SYNDESIS_RESOURCE_PATH : syndesisPath);
		assertThat(syndesisResource.exists()).isTrue();
		
		waitJob();

		checkCamelEditorOpened(camelResource);
		waitJob();
		checkJSONEditorOpened(syndesisResource);
		waitJob();
		checkCorrectFacetsEnabled(project);
		waitJob();
		checkCorrectNatureEnabled(project);
		waitForValidationThreads();
		checkNoValidationError();
		checkNoValidationWarning();
		additionalChecks(project);
		
		launchBuild(project, new NullProgressMonitor());
	}

	private void waitForValidationThreads() throws InterruptedException {
		int waitTimeLeft = 30000;
		while(isValidationThreadRunning() && waitTimeLeft > 0) {
			Thread.sleep(100);
			waitTimeLeft -= 100;
		}
		if (waitTimeLeft < 0) {
			SyndesisExtensionIntegrationTestsActivator.pluginLog().logError("The validation thread is still active!");
		}
	}

	protected boolean isValidationThreadRunning() {
		return Thread.getAllStackTraces().keySet().stream()
				.anyMatch(thread -> "org.eclipse.wst.sse.ui.internal.reconcile.StructuredRegionProcessor".equals(thread.getName()));
	}

	private void checkNoValidationWarning() throws CoreException {
		checkNoValidationIssueOfType(filterWarning());
	}
	
	private Predicate<IMarker> filterWarning(){
		return marker -> {
			try {
				Object severity = marker.getAttribute(IMarker.SEVERITY);
				boolean isWarning = severity ==null  || severity.equals(IMarker.SEVERITY_WARNING);
				String message = (String)marker.getAttribute(IMarker.MESSAGE);
				return isWarning
						//TODO: managed other dependencies than camel
						&& !message.startsWith("Duplicating managed version")
						//TODO: manage community version and pure fis version
						&& !message.startsWith("Overriding managed version");
			} catch (CoreException e1) {
				return true;
			}
		};
	}

	/**
	 * @param camelResource
	 * @throws InterruptedException
	 */
	private void checkCamelEditorOpened(IFile camelResource) throws InterruptedException, PartInitException {
		readAndDispatch(0);
		int currentAwaitedTime = 0;
		while (CommonTestUtils.getCurrentOpenEditors().length < 2 && currentAwaitedTime < 30000) {
			Thread.sleep(100);
			currentAwaitedTime += 100;
			System.out.println("awaited activation of editor " + currentAwaitedTime);
		}
		IEditorReference editor = getEditorForFile(camelResource);
		assertThat(editor).as("No editor has been opened.").isNotNull();
		assertThat(editor.isDirty()).as("A newly created project should not have dirty editor.").isFalse();
		
		// if xml context we check if the design editor loads fine
		if ("xml".equalsIgnoreCase(camelResource.getFileExtension()) && editor instanceof CamelEditor) {
			CamelEditor ed = (CamelEditor)editor;
			assertThat(ed.getDesignEditor()).as("The Camel Designer has not been created.").isNotNull();
			assertThat(ed.getDesignEditor().getDiagramTypeProvider()).as("Error retrieving the diagram type provider.").isNotNull();
			assertThat(ed.getDesignEditor().getDiagramTypeProvider().getDiagram()).as("Unable to access the camel context diagram.").isNotNull();
		}
	}
	
	private IEditorReference getEditorForFile(IFile file) throws PartInitException {
		for (IEditorReference ref : CommonTestUtils.getCurrentOpenEditors()) {
			IEditorInput editorInput = ref.getEditorInput();
			if ((editorInput.getAdapter(IFile.class)).equals(file)) {
				return ref;
			}
		}
		return null;
	}
	
	/**
	 * @param syndesisResource
	 * @throws InterruptedException
	 */
	private void checkJSONEditorOpened(IFile syndesisResource) throws InterruptedException, PartInitException {
		readAndDispatch(0);
		int currentAwaitedTime = 0;
		while (CommonTestUtils.getCurrentOpenEditors().length < 2 && currentAwaitedTime < 30000) {
			Thread.sleep(100);
			currentAwaitedTime += 100;
			System.out.println("awaited activation of editor " + currentAwaitedTime);
		}
		IEditorReference editor = getEditorForFile(syndesisResource);
		assertThat(editor).as("No editor has been opened.").isNotNull();
		assertThat(editor.isDirty()).as("A newly created project should not have dirty editor.").isFalse();
	}
	
    protected void launchBuild(IProject project, IProgressMonitor monitor) throws CoreException, InterruptedException, IOException, MalformedObjectNameException {
		IMaven maven = MavenPlugin.getMaven();
		IMavenExecutionContext executionContext = maven.createExecutionContext();
		MavenExecutionRequest executionRequest = executionContext.getExecutionRequest();
		executionRequest.setPom(project.getFile("pom.xml").getLocation().toFile());
		executionRequest.setGoals(Arrays.asList("clean", "verify"));
		
		MavenExecutionResult result = maven.execute(executionRequest, monitor);
		buildFinished = true;
		buildOK = !result.hasExceptions();
		for (Throwable t : result.getExceptions()) {
			SyndesisExtensionIntegrationTestsActivator.pluginLog().logError(t);
		}
		assertThat(buildOK).isTrue();
	}
}
