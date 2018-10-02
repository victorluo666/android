/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.startup;

import static com.android.tools.idea.io.FilePaths.toSystemDependentPath;
import static com.android.tools.idea.npw.PathValidationResult.validateLocation;
import static com.android.tools.idea.sdk.VersionCheck.isCompatibleVersion;
import static com.android.tools.idea.startup.Actions.hideAction;
import static com.android.tools.idea.startup.Actions.moveAction;
import static com.android.tools.idea.startup.Actions.replaceAction;
import static com.intellij.openapi.actionSystem.Anchor.AFTER;
import static com.intellij.openapi.actionSystem.Anchor.BEFORE;
import static org.jetbrains.android.sdk.AndroidSdkUtils.DEFAULT_JDK_NAME;
import static org.jetbrains.android.sdk.AndroidSdkUtils.createNewAndroidPlatform;

import com.android.annotations.VisibleForTesting;
import com.android.ddmlib.Client;
import com.android.ddmlib.IDevice;
import com.android.prefs.AndroidLocation;
import com.android.sdklib.AndroidVersion;
import com.android.sdklib.IAndroidTarget;
import com.android.tools.idea.actions.AndroidActionGroupRemover;
import com.android.tools.idea.actions.AndroidImportModuleAction;
import com.android.tools.idea.actions.AndroidImportProjectAction;
import com.android.tools.idea.actions.AndroidNewModuleAction;
import com.android.tools.idea.actions.AndroidNewModuleInGroupAction;
import com.android.tools.idea.actions.AndroidNewProjectAction;
import com.android.tools.idea.actions.AndroidOpenFileAction;
import com.android.tools.idea.actions.CreateLibraryFromFilesAction;
import com.android.tools.idea.fd.actions.HotswapAction;
import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.gradle.actions.AndroidTemplateProjectSettingsGroup;
import com.android.tools.idea.gradle.actions.AndroidTemplateProjectStructureAction;
import com.android.tools.idea.npw.PathValidationResult;
import com.android.tools.idea.npw.PathValidationResult.WritableCheckMode;
import com.android.tools.idea.run.AndroidRunConfigurationBase;
import com.android.tools.idea.run.ApkProvisionException;
import com.android.tools.idea.run.ApplicationIdProvider;
import com.android.tools.idea.run.ApplyChangesAction;
import com.android.tools.idea.run.CodeSwapAction;
import com.android.tools.idea.run.DeviceFutures;
import com.android.tools.idea.run.editor.DeployTarget;
import com.android.tools.idea.run.editor.DeployTargetProvider;
import com.android.tools.idea.run.editor.DeployTargetState;
import com.android.tools.idea.run.editor.ShowChooserTargetProvider;
import com.android.tools.idea.sdk.AndroidSdks;
import com.android.tools.idea.sdk.IdeSdks;
import com.android.tools.idea.sdk.wizard.SdkQuickfixUtils;
import com.android.tools.idea.ui.GuiTestingService;
import com.android.tools.idea.welcome.config.FirstRunWizardMode;
import com.android.tools.idea.welcome.wizard.AndroidStudioWelcomeScreenProvider;
import com.android.utils.Pair;
import com.google.common.util.concurrent.ListenableFuture;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.actions.TemplateProjectSettingsGroup;
import com.intellij.ide.projectView.actions.MarkRootGroup;
import com.intellij.ide.projectView.impl.MoveModuleToGroupTopLevel;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationDisplayType;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Constraints;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.codeStyle.CodeStyleScheme;
import com.intellij.psi.codeStyle.CodeStyleSchemes;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import javax.swing.event.HyperlinkEvent;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.formatter.AndroidXmlPredefinedCodeStyle;
import org.jetbrains.android.sdk.AndroidPlatform;
import org.jetbrains.android.sdk.AndroidSdkAdditionalData;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Performs Gradle-specific IDE initialization
 */
public class GradleSpecificInitializer implements Runnable {

  private static final Logger LOG = Logger.getInstance(GradleSpecificInitializer.class);

  // Id for TemplateProjectSettingsGroup
  @NotNull public static final String TEMPLATE_PROJECT_SETTINGS_GROUP_ID = "TemplateProjectSettingsGroup";

  @Override
  public void run() {
    setUpNewProjectActions();
    setUpInstantRunActions();
    setUpWelcomeScreenActions();
    replaceProjectPopupActions();
    // Replace "TemplateProjectSettingsGroup" to cause "Find Action" menu use AndroidTemplateProjectSettingsGroup (b/37141013)
    replaceAction(TEMPLATE_PROJECT_SETTINGS_GROUP_ID, new AndroidTemplateProjectSettingsGroup());
    setUpGradleViewToolbarActions();
    checkInstallPath();

    ActionManager actionManager = ActionManager.getInstance();
    // "Configure Plugins..." Not sure why it's called StartupWizard.
    AnAction pluginAction = actionManager.getAction("StartupWizard");
    // Never applicable in the context of android studio, so just set to invisible.
    pluginAction.getTemplatePresentation().setVisible(false);

    if (AndroidSdkUtils.isAndroidSdkManagerEnabled()) {
      try {
        // Setup JDK and Android SDK if necessary
        setupSdks();
        checkAndroidSdkHome();
      }
      catch (Exception e) {
        LOG.error("Unexpected error while setting up SDKs: ", e);
      }
      checkAndSetAndroidSdkSources();
    }

    modifyCodeStyleSettings();
  }

  /**
   * Gradle has an issue when the studio path contains ! (http://b.android.com/184588)
   */
  private static void checkInstallPath() {
    if (PathManager.getHomePath().contains("!")) {
      final Application app = ApplicationManager.getApplication();

      app.getMessageBus().connect(app).subscribe(AppLifecycleListener.TOPIC, new AppLifecycleListener() {
        @Override
        public void appStarting(Project project) {
          app.invokeLater(() -> {
            String message = String.format("%1$s must not be installed in a path containing '!' or Gradle sync will fail!",
                                           ApplicationNamesInfo.getInstance().getProductName());
            Notification notification = getNotificationGroup().createNotification(message, NotificationType.ERROR);
            notification.setImportant(true);
            Notifications.Bus.notify(notification);
          });
        }
      });
    }
  }

  private static void setUpGradleViewToolbarActions() {
    hideAction("ExternalSystem.RefreshAllProjects");
    hideAction("ExternalSystem.SelectProjectDataToImport");
  }

  private static void setUpNewProjectActions() {
    // Unregister IntelliJ's version of the project actions and manually register our own.
    replaceAction("OpenFile", new AndroidOpenFileAction());
    replaceAction("NewProject", new AndroidNewProjectAction());
    replaceAction("NewModule", new AndroidNewModuleAction());
    replaceAction("NewModuleInGroup", new AndroidNewModuleInGroupAction());
    replaceAction("ImportProject", new AndroidImportProjectAction());
    replaceAction("CreateLibraryFromFile", new CreateLibraryFromFilesAction());
    replaceAction("ImportModule", new AndroidImportModuleAction());

    hideAction(IdeActions.ACTION_GENERATE_ANT_BUILD);
    hideAction("AddFrameworkSupport");
    hideAction("BuildArtifact");
    hideAction("RunTargetAction");
  }

  private static void setUpInstantRunActions() {
    // Since the executor actions are registered dynamically, and we want to insert ourselves in the middle, we have to do this
    // in code as well (instead of xml).
    ActionManager actionManager = ActionManager.getInstance();
    AnAction runnerActions = actionManager.getAction(IdeActions.GROUP_RUNNER_ACTIONS);
    if (runnerActions instanceof DefaultActionGroup) {
      DefaultActionGroup ag =  ((DefaultActionGroup)runnerActions);
      if (StudioFlags.JVMTI_REFRESH.get()) {
        AnAction codeswap = new CodeSwapAction(GradleSpecificInitializer::shouldEnableJvmtiCodeSwap);
        actionManager.registerAction(CodeSwapAction.ID, codeswap);
        ag.add(codeswap, new Constraints(AFTER, IdeActions.ACTION_DEFAULT_RUNNER));
        AnAction applyChanges = new ApplyChangesAction(GradleSpecificInitializer::shouldEnableJvmtiCodeSwap);
        String id = actionManager.getId(codeswap);
        ag.add(applyChanges, new Constraints(BEFORE, id));
      }
      else {
        ag.add(new HotswapAction(), new Constraints(AFTER, IdeActions.ACTION_DEFAULT_RUNNER));
      }
    }
  }

  private static void setUpWelcomeScreenActions() {
    // Force the new "flat" welcome screen.
    System.setProperty("ide.new.welcome.screen.force", "true");

    // Update the Welcome Screen actions
    replaceAction("WelcomeScreen.OpenProject", new AndroidOpenFileAction("Open an existing Android Studio project"));
    replaceAction("WelcomeScreen.CreateNewProject", new AndroidNewProjectAction("Start a new Android Studio project"));
    replaceAction("WelcomeScreen.ImportProject", new AndroidImportProjectAction("Import project (Gradle, Eclipse ADT, etc.)"));
    replaceAction("TemplateProjectStructure", new AndroidTemplateProjectStructureAction("Default Project Structure..."));

    moveAction("WelcomeScreen.ImportProject", "WelcomeScreen.QuickStart.IDEA",
               "WelcomeScreen.QuickStart", new Constraints(AFTER, "WelcomeScreen.GetFromVcs"));

    ActionManager actionManager = ActionManager.getInstance();
    AnAction getFromVcsAction = actionManager.getAction("WelcomeScreen.GetFromVcs");
    if (getFromVcsAction != null) {
      getFromVcsAction.getTemplatePresentation().setText("Check out project from Version Control");
    }

    AnAction configureIdeaAction = actionManager.getAction("WelcomeScreen.Configure.IDEA");
    if (configureIdeaAction instanceof DefaultActionGroup) {
      DefaultActionGroup settingsGroup = (DefaultActionGroup)configureIdeaAction;
      AnAction[] children = settingsGroup.getChildren(null);
      if (children.length == 1) {
        AnAction child = children[0];
        if (child instanceof TemplateProjectSettingsGroup) {
          settingsGroup.replaceAction(child, new AndroidTemplateProjectSettingsGroup());
        }
      }
    }
  }

  private static void replaceProjectPopupActions() {
    Deque<Pair<DefaultActionGroup, AnAction>> stack = new ArrayDeque<>();
    stack.add(Pair.of(null, ActionManager.getInstance().getAction("ProjectViewPopupMenu")));
    while (!stack.isEmpty()) {
      Pair<DefaultActionGroup, AnAction> entry = stack.pop();
      DefaultActionGroup parent = entry.getFirst();
      AnAction action = entry.getSecond();
      if (action instanceof DefaultActionGroup) {
        DefaultActionGroup actionGroup = (DefaultActionGroup)action;
        for (AnAction child : actionGroup.getChildActionsOrStubs()) {
          stack.push(Pair.of(actionGroup, child));
        }
      }

      if (action instanceof MoveModuleToGroupTopLevel) {
        parent.remove(action);
        parent.add(new AndroidActionGroupRemover((ActionGroup)action, "Move Module to Group"),
                   new Constraints(AFTER, "OpenModuleSettings"));
      }
      else if (action instanceof MarkRootGroup) {
        parent.remove(action);
        parent.add(new AndroidActionGroupRemover((ActionGroup)action, "Mark Directory As"),
                   new Constraints(AFTER, "OpenModuleSettings"));
      }
    }
  }

  private static void notifyInvalidSdk() {
    String key = "android.invalid.sdk.message";
    String message = AndroidBundle.message(key);

    NotificationListener listener = new NotificationListener.Adapter() {
      @Override
      protected void hyperlinkActivated(@NotNull Notification notification,
                                        @NotNull HyperlinkEvent e) {
        SdkQuickfixUtils.showAndroidSdkManager();
        notification.expire();
      }
    };
    addStartupWarning(message, listener);
  }

  private static void addStartupWarning(@NotNull final String message, @Nullable final NotificationListener listener) {
    final Application app = ApplicationManager.getApplication();

    app.getMessageBus().connect(app).subscribe(AppLifecycleListener.TOPIC, new AppLifecycleListener() {
      @Override
      public void appStarting(Project project) {
        app.invokeLater(() -> {
          Notification notification =
            getNotificationGroup().createNotification("SDK Validation", message, NotificationType.WARNING, listener);
          notification.setImportant(true);
          Notifications.Bus.notify(notification);
        });
      }
    });
  }

  private static NotificationGroup getNotificationGroup() {
    // Use the system health settings by default
    NotificationGroup group = NotificationGroup.findRegisteredGroup("System Health");
    if (group == null) {
      // This shouldn't happen
      group = new NotificationGroup("Gradle Initializer", NotificationDisplayType.STICKY_BALLOON, true);
    }
    return group;
  }

  private static void setupSdks() {
    IdeSdks ideSdks = IdeSdks.getInstance();
    File androidHome = ideSdks.getAndroidSdkPath();

    if (androidHome != null) {
      String androidHomePath = androidHome.getAbsolutePath();
      PathValidationResult result = validateLocation(androidHomePath, "Android SDK location", false, WritableCheckMode.DO_NOT_CHECK);
      if (result.isError()) {
        notifyInvalidSdk();
      }

      // Do not prompt user to select SDK path (we have one already.) Instead, check SDK compatibility when a project is opened.
      return;
    }

    // If running in a GUI test we don't want the "Select SDK" dialog to show up when running GUI tests.
    // In unit tests, we only want to set up SDKs which are set up explicitly by the test itself, whereas initialisers
    // might lead to unexpected SDK leaks because having not set up the SDKs, the test will consequently not release them either.
    if (GuiTestingService.getInstance().isGuiTestingMode() || ApplicationManager.getApplication().isUnitTestMode()
        || ApplicationManager.getApplication().isHeadlessEnvironment()) {
      // This is good enough. Later on in the GUI test we'll validate the given SDK path.
      return;
    }

    Sdk sdk = findFirstCompatibleAndroidSdk();
    if (sdk != null) {
      String sdkHomePath = sdk.getHomePath();
      assert sdkHomePath != null;
      ideSdks.createAndroidSdkPerAndroidTarget(toSystemDependentPath(sdkHomePath));
      return;
    }

    // Called in a 'invokeLater' block, otherwise file chooser will hang forever.
    ApplicationManager.getApplication().invokeLater(() -> {
      File androidSdkPath = getAndroidSdkPath();
      if (androidSdkPath == null) {
        return;
      }

      FirstRunWizardMode wizardMode = AndroidStudioWelcomeScreenProvider.getWizardMode();
      // Only show "Select SDK" dialog if the "First Run" wizard is not displayed.
      boolean promptSdkSelection = wizardMode == null;

      Sdk sdk1 = createNewAndroidPlatform(androidSdkPath.getPath(), promptSdkSelection);
      if (sdk1 != null) {
        // Rename the SDK to fit our default naming convention.
        String sdkNamePrefix = AndroidSdks.SDK_NAME_PREFIX;
        if (sdk1.getName().startsWith(sdkNamePrefix)) {
          SdkModificator sdkModificator = sdk1.getSdkModificator();
          sdkModificator.setName(sdkNamePrefix + sdk1.getName().substring(sdkNamePrefix.length()));
          sdkModificator.commitChanges();

          // Rename the JDK that goes along with this SDK.
          AndroidSdkAdditionalData additionalData = AndroidSdks.getInstance().getAndroidSdkAdditionalData(sdk1);
          if (additionalData != null) {
            Sdk jdk = additionalData.getJavaSdk();
            if (jdk != null) {
              sdkModificator = jdk.getSdkModificator();
              sdkModificator.setName(DEFAULT_JDK_NAME);
              sdkModificator.commitChanges();
            }
          }

          // Fill out any missing build APIs for this new SDK.
          ideSdks.createAndroidSdkPerAndroidTarget(androidSdkPath);
        }
      }
    });
  }

  private static void checkAndroidSdkHome() {
    try {
      AndroidLocation.checkAndroidSdkHome();
    }
    catch (AndroidLocation.AndroidLocationException e) {
      addStartupWarning(e.getMessage(), null);
    }
  }

  @Nullable
  private static Sdk findFirstCompatibleAndroidSdk() {
    List<Sdk> sdks = AndroidSdks.getInstance().getAllAndroidSdks();
    for (Sdk sdk : sdks) {
      String sdkPath = sdk.getHomePath();
      if (isCompatibleVersion(sdkPath)) {
        return sdk;
      }
    }
    if (!sdks.isEmpty()) {
      return sdks.get(0);
    }
    return null;
  }

  @Nullable
  private static File getAndroidSdkPath() {
    return AndroidSdkInitializer.findOrGetAndroidSdkPath();
  }

  @VisibleForTesting
  static void modifyCodeStyleSettings() {
    CodeStyleSchemes schemes = CodeStyleSchemes.getInstance();
    CodeStyleScheme scheme = schemes.getDefaultScheme();

    if (scheme != null) {
      AndroidCodeStyleSettingsModifier.modify(scheme.getCodeStyleSettings());
    }

    CommonCodeStyleSettings settings = schemes.getCurrentScheme().getCodeStyleSettings().getCommonSettings(XMLLanguage.INSTANCE);

    if (Objects.equals(settings.getArrangementSettings(), AndroidXmlPredefinedCodeStyle.createVersion1Settings())) {
      settings.setArrangementSettings(AndroidXmlPredefinedCodeStyle.createVersion2Settings());
    }
  }

  private static void checkAndSetAndroidSdkSources() {
    for (Sdk sdk : AndroidSdks.getInstance().getAllAndroidSdks()) {
      checkAndSetSources(sdk);
    }
  }

  private static void checkAndSetSources(@NotNull Sdk sdk) {
    VirtualFile[] storedSources = sdk.getRootProvider().getFiles(OrderRootType.SOURCES);
    if (storedSources.length > 0) {
      return;
    }

    AndroidPlatform platform = AndroidPlatform.getInstance(sdk);
    if (platform != null) {
      SdkModificator sdkModificator = sdk.getSdkModificator();
      IAndroidTarget target = platform.getTarget();
      AndroidSdks.getInstance().findAndSetPlatformSources(target, sdkModificator);
      sdkModificator.commitChanges();
    }
  }

  /**
   * Determines if JVMTI code swapping should be enabled for the current project.
   * @param project The project to test again.
   * @return true if code swapping should be enabled, false otherwise.
   */
  public static boolean shouldEnableJvmtiCodeSwap(@NotNull Project project) {
    RunnerAndConfigurationSettings configSettings = RunManager.getInstance(project).getSelectedConfiguration();
    if (configSettings == null) {
      return false; // No valid config settings available.
    }
    RunConfiguration config = configSettings.getConfiguration();
    if (!(config instanceof AndroidRunConfigurationBase)) {
      return false; // CodeSwap only works on Android.
    }

    AndroidRunConfigurationBase androidRunConfig = (AndroidRunConfigurationBase)config;
    DeployTargetProvider currentTargetProvider = androidRunConfig.getDeployTargetContext().getCurrentDeployTargetProvider();
    if (!(currentTargetProvider instanceof ShowChooserTargetProvider)) {
      // TODO(b/113614002) Need to handle potential new workflow for device dropdown.
      return true; // Allow code swap if there's no device picker.
    }

    ShowChooserTargetProvider chooserTargetProvider = ((ShowChooserTargetProvider)currentTargetProvider);
    Module module = androidRunConfig.getConfigurationModule().getModule();
    if (module == null) {
      return false; // We only support projects with Android facets, which needs to be in a module.
    }
    AndroidFacet facet = AndroidFacet.getInstance(module);
    if (facet == null) {
      return false; // Only support projects with Android facets.
    }
    DeployTarget deployTarget = chooserTargetProvider.getCachedDeployTarget(
      DefaultRunExecutor.getRunExecutorInstance(),
      facet,
      androidRunConfig.getDeviceCount(true),
      androidRunConfig.getDeployTargetContext().getDeployTargetStates(),
      androidRunConfig.getUniqueID());
    if (deployTarget == null) {
      return true; // No default deploy target, so allow code swapping.
    }
    DeployTargetState deployTargetState = androidRunConfig.getDeployTargetContext().getDeployTargetState(currentTargetProvider);
    DeviceFutures deviceFutures =
      deployTarget.getDevices(deployTargetState, facet, androidRunConfig.getDeviceCount(true), true, androidRunConfig.getUniqueID());
    if (deviceFutures == null) {
      return true; // Since no devices are available, it means no debuggers are attached, which means we can enable code swap.
    }
    List<ListenableFuture<IDevice>> listenableFuturesList = deviceFutures.get();
    ApplicationIdProvider idProvider = androidRunConfig.getApplicationIdProvider(facet);
    for (ListenableFuture<IDevice> deviceFuture : listenableFuturesList) {
      try {
        IDevice device = deviceFuture.get(); // This should resolve immediately for ShowChooserTargetProvider
        if (device.getVersion().getFeatureLevel() < AndroidVersion.VersionCodes.O_MR1) {
          return false; // We only support O MR1 or above.
        }
        Client client = device.getClient(idProvider.getPackageName());
        if (client == null) {
          return true; // The app doesn't have a Client associated with it, so it can't have a debugger attached.
        }
        return !client.isDebuggerAttached();
      }
      catch (InterruptedException | ExecutionException | ApkProvisionException e) {
        // Do nothing, fall through, and we'll just enable code swap.
      }
    }
    return true;
  }
}
