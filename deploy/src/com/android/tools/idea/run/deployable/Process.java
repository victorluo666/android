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
package com.android.tools.idea.run.deployable;

import com.android.ddmlib.Client;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class Process {
  private final int myPid;
  @Nullable private volatile String myApplicationId;
  @NotNull private Client myClient;

  Process(@NotNull Client client) {
    myPid = client.getClientData().getPid();
    myClient = client;
  }

  int getPid() {
    return myPid;
  }

  @NotNull
  Client getClient() {
    return myClient;
  }

  void setClient(@NotNull Client client) {
    myClient = client;
  }

  void setApplicationId(@NotNull String applicationId) {
    myApplicationId = applicationId;
  }

  @Nullable
  String getApplicationId() {
    return myApplicationId;
  }
}