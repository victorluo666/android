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
package com.android.tools.profilers.cpu.simpleperf

import com.android.testutils.TestUtils
import com.android.tools.profiler.protobuf3jarjar.ByteString
import com.android.tools.profilers.cpu.CpuProfilerTestUtils
import com.android.tools.profilers.cpu.TracePreProcessor
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.util.io.FileUtil
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths


class SimpleperfSampleReporterTest {

  @get:Rule
  val expected = ExpectedException.none()!!

  private lateinit var sampleReporter : SimpleperfSampleReporter

  private val ideaHome = Paths.get(TestUtils.getWorkspaceRoot().absolutePath, "tools", "idea")

  @Before
  fun setUp() {
    sampleReporter = SimpleperfSampleReporter(ideaHome.toString()) { emptySet() }
  }

  @Test
  fun simpleperfBinaryShouldExist() {
    assertThat(File(sampleReporter.simpleperfBinaryPath).exists()).isTrue()
  }

  @Test
  fun preProcessingInvalidTraceReturnsFailure() {
    val processedTrace = sampleReporter.preProcessTrace(ByteString.copyFromUtf8("bad trace"))
    assertThat(processedTrace).isEqualTo(TracePreProcessor.FAILURE)

    val trace = FileUtil.createTempFile("cpu_trace", ".trace", true)
    FileOutputStream(trace).use { out -> out.write(processedTrace.toByteArray()) }

    // Trace can't be parsed by SimpleperfTraceParser. Expect an exception
    expected.expect(Exception::class.java)
    SimpleperfTraceParser().parse(trace, 0)
  }

  @Test
  fun preProcessingRawTraceReturnsValidTrace() {
    val processedTrace = sampleReporter.preProcessTrace(CpuProfilerTestUtils.traceFileToByteString("simpleperf_raw_trace.trace"))
    assertThat(processedTrace).isNotEqualTo(TracePreProcessor.FAILURE)

    val trace = FileUtil.createTempFile("cpu_trace", ".trace", true)
    FileOutputStream(trace).use { out -> out.write(processedTrace.toByteArray()) }

    // Trace can be parsed by SimpleperfTraceParser
    val parsedTrace = SimpleperfTraceParser().parse(trace, 0)
    assertThat(parsedTrace).isNotNull()
  }

  @Test
  fun unknownSymbolsSymbolizedWhenProvidingSymDir() {
    val symDir = TestUtils.getWorkspaceFile("tools/adt/idea/profilers/testData")
    val reporter = SimpleperfSampleReporter(ideaHome.toString()) { hashSetOf(symDir) }
    val rawTrace = CpuProfilerTestUtils.traceFileToByteString("simpleperf_trace_without_symbols.trace")

    // When providing a path to SimpleperfSampleReporter, we should include the --symdir flag in the report-sample command.
    assertThat(reporter.getReportSampleCommand(rawTrace, FileUtil.createTempFile("any", "file", true))).contains("--symdir")

    val trace = FileUtil.createTempFile("cpu_trace", ".trace", true)
    FileOutputStream(trace).use { out -> out.write(reporter.preProcessTrace(rawTrace).toByteArray()) }
    val parsedTrace = SimpleperfTraceParser().parse(trace, 0)

    val unknownSymbol = parsedTrace.getCaptureNode(27465)!!.children[0].children[0].children[0].data.name
    // The unknown symbol should be properly symbolized
    assertThat(unknownSymbol).isEqualTo("android_app_entry")
  }

  @Test
  fun unknownSymbolsNotSymbolizedWhenSymDirNotProvided() {
    val reporter = SimpleperfSampleReporter(ideaHome.toString()) { emptySet() }
    val rawTrace = CpuProfilerTestUtils.traceFileToByteString("simpleperf_trace_without_symbols.trace")

    // When not providing a path to SimpleperfSampleReporter, we shouldn't include the --symdir flag in the report-sample command.
    assertThat(reporter.getReportSampleCommand(rawTrace, FileUtil.createTempFile("any", "file", true))).doesNotContain("--symdir")

    val trace = FileUtil.createTempFile("cpu_trace", ".trace", true)
    FileOutputStream(trace).use { out -> out.write(reporter.preProcessTrace(rawTrace).toByteArray()) }
    val parsedTrace = SimpleperfTraceParser().parse(trace, 0)

    val unknownSymbol = parsedTrace.getCaptureNode(27465)!!.children[0].children[0].children[0].data.name
    // The unknown symbol should not be properly symbolized
    assertThat(unknownSymbol).isEqualTo("libgame.so+0x29508")
  }

  @Test
  fun unknownSymbolsNotSymbolizedWhenProvidingInvalidSymDir() {
    val symDir = TestUtils.getWorkspaceFile("tools/adt/idea/profilers/testData/cputraces") // Dir without valid .so files
    val reporter = SimpleperfSampleReporter(ideaHome.toString()) { hashSetOf(symDir) }
    val rawTrace = CpuProfilerTestUtils.traceFileToByteString("simpleperf_trace_without_symbols.trace")

    // When providing a path to SimpleperfSampleReporter, we should include the --symdir flag in the report-sample command.
    // That happens even if the path does not contain valid .so files.
    assertThat(reporter.getReportSampleCommand(rawTrace, FileUtil.createTempFile("any", "file", true))).contains("--symdir")

    val trace = FileUtil.createTempFile("cpu_trace", ".trace", true)
    FileOutputStream(trace).use { out -> out.write(reporter.preProcessTrace(rawTrace).toByteArray()) }
    val parsedTrace = SimpleperfTraceParser().parse(trace, 0)

    val unknownSymbol = parsedTrace.getCaptureNode(27465)!!.children[0].children[0].children[0].data.name
    // The unknown symbol should be properly symbolized
    assertThat(unknownSymbol).isEqualTo("libgame.so+0x29508")
  }

  @Test
  fun providingMultipleSymDirsResultsInMultipleFlags() {
    val symDir1 = TestUtils.getWorkspaceFile("tools/adt/idea/profilers/testData/cputraces")
    val symDir2 = TestUtils.getWorkspaceFile("tools/adt/idea/profilers/testData")
    // Passing a linked set is important for this test so we have predictable iteration order and can make the index checks below
    val reporter = SimpleperfSampleReporter(ideaHome.toString()) { linkedSetOf(symDir1, symDir2) }
    val rawTrace = CpuProfilerTestUtils.traceFileToByteString("simpleperf_trace_without_symbols.trace")

    // When providing multiples path to SimpleperfSampleReporter, we should include a --symdir flag in the report-sample command
    // corresponding to each directory passed.
    val command = reporter.getReportSampleCommand(rawTrace, FileUtil.createTempFile("any", "file", true))
    assertThat(command.count {it == "--symdir"}).isEqualTo(2)

    val firstSymDirIndex = command.indexOfFirst { it == "--symdir" }
    assertThat(command[firstSymDirIndex + 1]).isEqualTo(symDir1.absolutePath)

    val secondSymDirIndex = command.indexOfLast { it == "--symdir" }
    assertThat(command[secondSymDirIndex + 1]).isEqualTo(symDir2.absolutePath)
  }
}