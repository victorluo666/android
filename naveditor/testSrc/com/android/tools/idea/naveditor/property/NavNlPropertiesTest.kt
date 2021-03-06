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
package com.android.tools.idea.naveditor.property

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_ID
import com.android.SdkConstants.AUTO_URI
import com.android.tools.idea.naveditor.NavModelBuilderUtil.navigation
import com.android.tools.idea.naveditor.NavTestCase
import com.android.tools.idea.uibuilder.property.NlProperties
import org.jetbrains.android.dom.navigation.NavigationSchema.ATTR_DESTINATION
import org.jetbrains.android.dom.navigation.NavigationSchema.ATTR_ENTER_ANIM
import org.jetbrains.android.dom.navigation.NavigationSchema.ATTR_EXIT_ANIM
import org.jetbrains.android.dom.navigation.NavigationSchema.ATTR_POP_ENTER_ANIM
import org.jetbrains.android.dom.navigation.NavigationSchema.ATTR_POP_EXIT_ANIM
import org.jetbrains.android.dom.navigation.NavigationSchema.ATTR_POP_UP_TO
import org.jetbrains.android.dom.navigation.NavigationSchema.ATTR_POP_UP_TO_INCLUSIVE
import org.jetbrains.android.dom.navigation.NavigationSchema.ATTR_SINGLE_TOP

class NavNlPropertiesTest : NavTestCase() {

  fun testActionProperties() {
    val model = model("nav.xml") {
      navigation {
        fragment("f1")
        fragment("f2") {
          action("a1", destination = "f1")
        }
      }
    }

    val properties = NlProperties.getInstance().getProperties(myFacet, null, listOf(model.find("a1")))
    assertContainsElements(properties.row(AUTO_URI).keys, ATTR_DESTINATION, ATTR_ENTER_ANIM, ATTR_EXIT_ANIM,
                           ATTR_POP_ENTER_ANIM, ATTR_POP_EXIT_ANIM, ATTR_POP_UP_TO, ATTR_POP_UP_TO_INCLUSIVE,
                           ATTR_SINGLE_TOP)
    assertContainsElements(properties.row(ANDROID_URI).keys, ATTR_ID)
  }

  // TODO: tests for other types
}