/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea.run.deployment;

import com.android.annotations.VisibleForTesting;
import com.android.tools.idea.flags.StudioFlags;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.util.Condition;
import com.intellij.ui.popup.PopupFactoryImpl.ActionGroupPopup;
import icons.StudioIcons;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.swing.JComponent;
import org.jetbrains.android.actions.RunAndroidAvdManagerAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class DeviceAndSnapshotComboBoxAction extends ComboBoxAction {
  private static final String SELECTED_DEVICE = "DeviceAndSnapshotComboBoxAction.selectedDevice";

  private final Supplier<Boolean> mySelectDeviceSnapshotComboBoxVisible;
  private final Supplier<Boolean> mySelectDeviceSnapshotComboBoxSnapshotsEnabled;

  private final AsyncDevicesGetter myDevicesGetter;
  private final AnAction myOpenAvdManagerAction;

  private List<Device> myDevices;
  private String mySelectedSnapshot;

  @SuppressWarnings("unused")
  private DeviceAndSnapshotComboBoxAction() {
    this(() -> StudioFlags.SELECT_DEVICE_SNAPSHOT_COMBO_BOX_VISIBLE.get(),
         () -> StudioFlags.SELECT_DEVICE_SNAPSHOT_COMBO_BOX_SNAPSHOTS_ENABLED.get(),
         new AsyncDevicesGetter(ApplicationManager.getApplication()));
  }

  @VisibleForTesting
  DeviceAndSnapshotComboBoxAction(@NotNull Supplier<Boolean> selectDeviceSnapshotComboBoxVisible,
                                  @NotNull Supplier<Boolean> selectDeviceSnapshotComboBoxSnapshotsEnabled,
                                  @NotNull AsyncDevicesGetter devicesGetter) {
    mySelectDeviceSnapshotComboBoxVisible = selectDeviceSnapshotComboBoxVisible;
    mySelectDeviceSnapshotComboBoxSnapshotsEnabled = selectDeviceSnapshotComboBoxSnapshotsEnabled;

    myDevicesGetter = devicesGetter;
    myOpenAvdManagerAction = new RunAndroidAvdManagerAction();

    Presentation presentation = myOpenAvdManagerAction.getTemplatePresentation();

    presentation.setIcon(StudioIcons.Shell.Toolbar.DEVICE_MANAGER);
    presentation.setText("Open AVD Manager");

    myDevices = Collections.emptyList();
  }

  boolean areSnapshotsEnabled() {
    return mySelectDeviceSnapshotComboBoxSnapshotsEnabled.get();
  }

  @NotNull
  @VisibleForTesting
  AnAction getOpenAvdManagerAction() {
    return myOpenAvdManagerAction;
  }

  @NotNull
  @VisibleForTesting
  List<Device> getDevices() {
    return myDevices;
  }

  @Nullable
  Device getSelectedDevice(@NotNull Project project) {
    if (myDevices.isEmpty()) {
      return null;
    }

    Object key = PropertiesComponent.getInstance(project).getValue(SELECTED_DEVICE);

    Optional<Device> selectedDevice = myDevices.stream()
      .filter(device -> device.getKey().equals(key))
      .findFirst();

    return selectedDevice.orElse(myDevices.get(0));
  }

  void setSelectedDevice(@NotNull Project project, @Nullable Device selectedDevice) {
    PropertiesComponent properties = PropertiesComponent.getInstance(project);

    if (selectedDevice == null) {
      properties.unsetValue(SELECTED_DEVICE);
      return;
    }

    properties.setValue(SELECTED_DEVICE, selectedDevice.getKey());
  }

  @Nullable
  String getSelectedSnapshot() {
    return mySelectedSnapshot;
  }

  void setSelectedSnapshot(@Nullable String selectedSnapshot) {
    mySelectedSnapshot = selectedSnapshot;
  }

  @NotNull
  @Override
  protected ComboBoxButton createComboBoxButton(@NotNull Presentation presentation) {
    return new ComboBoxButton(presentation) {
      @Override
      protected JBPopup createPopup(@NotNull Runnable runnable) {
        DataContext context = getDataContext();

        ActionGroup group = createPopupActionGroup(this, context);
        boolean show = shouldShowDisabledActions();
        int count = getMaxRows();
        Condition<AnAction> condition = getPreselectCondition();

        JBPopup popup = new ActionGroupPopup(null, group, context, false, true, show, false, runnable, count, condition, null, true);
        popup.setMinimumSize(new Dimension(getMinWidth(), getMinHeight()));

        return popup;
      }
    };
  }

  @NotNull
  @Override
  protected DefaultActionGroup createPopupActionGroup(@NotNull JComponent button) {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  protected DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
    DefaultActionGroup group = new DefaultActionGroup();

    Project project = context.getData(CommonDataKeys.PROJECT);
    assert project != null;

    Collection<AnAction> actions = newSelectDeviceAndSnapshotActions(project);
    group.addAll(actions);

    if (!actions.isEmpty()) {
      group.addSeparator();
    }

    group.add(myOpenAvdManagerAction);
    AnAction action = getTroubleshootDeviceConnectionsAction();

    if (action == null) {
      return group;
    }

    group.addSeparator();
    group.add(action);

    return group;
  }

  @NotNull
  private Collection<AnAction> newSelectDeviceAndSnapshotActions(@NotNull Project project) {
    Collection<VirtualDevice> virtualDevices = new ArrayList<>(myDevices.size());
    Collection<Device> physicalDevices = new ArrayList<>(myDevices.size());

    myDevices.forEach(device -> {
      if (device instanceof VirtualDevice) {
        virtualDevices.add((VirtualDevice)device);
      }
      else if (device instanceof PhysicalDevice) {
        physicalDevices.add(device);
      }
      else {
        assert false;
      }
    });

    Collection<AnAction> actions = new ArrayList<>(virtualDevices.size() + 1 + physicalDevices.size());

    virtualDevices.stream()
      .map(device -> newSelectDeviceAndSnapshotActionOrSnapshotActionGroup(project, device))
      .forEach(actions::add);

    if (!virtualDevices.isEmpty() && !physicalDevices.isEmpty()) {
      actions.add(Separator.create());
    }

    physicalDevices.stream()
      .map(device -> newSelectDeviceAndSnapshotAction(project, device))
      .forEach(actions::add);

    return actions;
  }

  @NotNull
  private AnAction newSelectDeviceAndSnapshotActionOrSnapshotActionGroup(@NotNull Project project, @NotNull VirtualDevice device) {
    Collection<String> snapshots = device.getSnapshots();

    if (snapshots.isEmpty() ||
        snapshots.equals(VirtualDevice.DEFAULT_SNAPSHOT_COLLECTION) ||
        !mySelectDeviceSnapshotComboBoxSnapshotsEnabled.get()) {
      return newSelectDeviceAndSnapshotAction(project, device);
    }

    return new SnapshotActionGroup(device, this, project);
  }

  @NotNull
  private AnAction newSelectDeviceAndSnapshotAction(@NotNull Project project, @NotNull Device device) {
    return new SelectDeviceAndSnapshotAction.Builder()
      .setComboBoxAction(this)
      .setProject(project)
      .setDevice(device)
      .build();
  }

  @Nullable
  private static AnAction getTroubleshootDeviceConnectionsAction() {
    AnAction action = ActionManager.getInstance().getAction("DeveloperServices.ConnectionAssistant");

    if (action == null) {
      return null;
    }

    Presentation presentation = action.getTemplatePresentation();

    presentation.setIcon(null);
    presentation.setText("Troubleshoot device connections");

    return action;
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    Project project = event.getProject();

    if (project == null) {
      return;
    }

    Presentation presentation = event.getPresentation();

    if (!mySelectDeviceSnapshotComboBoxVisible.get()) {
      presentation.setVisible(false);
      return;
    }

    presentation.setVisible(true);
    myDevices = myDevicesGetter.get(project);

    if (myDevices.isEmpty()) {
      mySelectedSnapshot = null;

      presentation.setIcon(null);
      presentation.setText("No devices");

      return;
    }

    updateSelectedSnapshot(project);

    Device device = getSelectedDevice(project);
    assert device != null;

    presentation.setIcon(device.getIcon());
    presentation.setText(mySelectedSnapshot == null ? device.getName() : device + " - " + mySelectedSnapshot);
  }

  private void updateSelectedSnapshot(@NotNull Project project) {
    if (!mySelectDeviceSnapshotComboBoxSnapshotsEnabled.get()) {
      return;
    }

    Device device = getSelectedDevice(project);
    assert device != null;

    Collection<String> snapshots = device.getSnapshots();

    if (mySelectedSnapshot == null) {
      Optional<String> selectedDeviceSnapshot = snapshots.stream().findFirst();
      selectedDeviceSnapshot.ifPresent(snapshot -> mySelectedSnapshot = snapshot);

      return;
    }

    if (snapshots.contains(mySelectedSnapshot)) {
      return;
    }

    Optional<String> selectedSnapshot = snapshots.stream().findFirst();
    mySelectedSnapshot = selectedSnapshot.orElse(null);
  }
}
