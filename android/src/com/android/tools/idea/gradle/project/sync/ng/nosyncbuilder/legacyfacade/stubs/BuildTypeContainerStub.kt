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
package com.android.tools.idea.gradle.project.sync.ng.nosyncbuilder.legacyfacade.stubs

import com.android.builder.model.BuildType
import com.android.builder.model.BuildTypeContainer
import com.android.builder.model.SourceProvider
import com.android.builder.model.SourceProviderContainer

data class BuildTypeContainerStub(
  private val buildType: BuildType,
  private val sourceProvider: SourceProvider,
  private val extraSourceProviders: Collection<SourceProviderContainer>
) : BuildTypeContainer {
  override fun getBuildType(): BuildType = buildType
  override fun getSourceProvider(): SourceProvider = sourceProvider
  override fun getExtraSourceProviders(): Collection<SourceProviderContainer> = extraSourceProviders
}
