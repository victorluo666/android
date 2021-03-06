/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.databinding

import com.android.tools.idea.databinding.psiclass.LightBindingClass
import com.android.tools.idea.res.DataBindingLayoutInfoFile
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.facet.FacetManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiClassOwner
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import com.intellij.util.IncorrectOperationException
import junit.framework.Assert.fail
import org.jetbrains.android.facet.AndroidFacet
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Tests that verify navigating between data binding components work
 */
@RunsInEdt
@RunWith(Parameterized::class)
class DataBindingNavigationTests(private val mode: DataBindingMode) {
  companion object {
    @Suppress("unused") // Used by JUnit via reflection
    @JvmStatic
    @get:Parameterized.Parameters(name = "{0}")
    val modes = listOf(DataBindingMode.SUPPORT, DataBindingMode.ANDROIDX)
  }

  private val projectRule = AndroidProjectRule.onDisk()

  // We want to run tests on the EDT thread, but we also need to make sure the project rule is not
  // initialized on the EDT.
  @get:Rule
  val ruleChain = RuleChain.outerRule(projectRule).around(EdtRule())!!

  /**
   * Expose the underlying project rule fixture directly.
   *
   * We know that the underlying fixture is a [JavaCodeInsightTestFixture] because our
   * [AndroidProjectRule] is initialized to use the disk.
   */
  private val fixture
    get() = projectRule.fixture as JavaCodeInsightTestFixture

  private val androidFacet
    get() = FacetManager.getInstance(projectRule.module).getFacetByType(AndroidFacet.ID)!!


  @Before
  fun setUp() {
    fixture.testDataPath = TestDataPaths.TEST_DATA_ROOT
    fixture.addFileToProject("AndroidManifest.xml", """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.db">
        <application />
      </manifest>
    """.trimIndent())

    ModuleDataBinding.getInstance(androidFacet).setMode(mode)
  }

  @Test
  fun canNavigateToXmlFromLightBindingClass() {
    fixture.addFileToProject("res/layout/activity_main.xml", """
      <?xml version="1.0" encoding="utf-8"?>
      <layout xmlns:android="http://schemas.android.com/apk/res/android">
        <data>
          <variable name="strValue" type="String"/>
          <variable name="intValue" type="Integer"/>
        </data>
      </layout>
    """.trimIndent())

    val editors = FileEditorManager.getInstance(fixture.project)
    assertThat(editors.selectedFiles).isEmpty()
    // ActivityMainBinding is in-memory and generated on the fly from activity_main.xml
    val binding = fixture.findClass("test.db.databinding.ActivityMainBinding") as LightBindingClass
    binding.navigate(true)
    assertThat(editors.selectedFiles[0].name).isEqualTo("activity_main.xml")

    // Additionally, let's verify the behavior of the LightBindingClass's navigation element, for
    // code coverage purposes.
    binding.navigationElement.let { navElement ->
      assertThat(navElement).isInstanceOf(DataBindingLayoutInfoFile::class.java)
      assertThat(navElement.containingFile).isSameAs(navElement)
      // This next cast has to be true or else Java code coverage will crash. More details in the
      // header docs of DataBindingLayoutInfoFile
      val psiClassOwner = navElement.containingFile as PsiClassOwner
      assertThat(psiClassOwner.classes).hasLength(1)
      assertThat(psiClassOwner.classes[0]).isEqualTo(binding)
      assertThat(psiClassOwner.packageName).isEqualTo("test.db.databinding")

      try {
        psiClassOwner.packageName = "setting.packages.is.not.supported"
        fail()
      }
      catch (ignored: IncorrectOperationException) {}
    }

  }
}