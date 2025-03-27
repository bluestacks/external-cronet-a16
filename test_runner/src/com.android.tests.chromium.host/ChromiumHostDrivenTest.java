/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.tests.chromium.host;

import static com.android.tests.chromium.host.InstrumentationFlags.COMMAND_LINE_FLAGS_KEY;
import static com.android.tests.chromium.host.InstrumentationFlags.DUMP_COVERAGE_KEY;
import static com.android.tests.chromium.host.InstrumentationFlags.EXTRA_SHARD_NANO_TIMEOUT_KEY;
import static com.android.tests.chromium.host.InstrumentationFlags.LIBRARY_TO_LOAD_ACTIVITY_KEY;
import static com.android.tests.chromium.host.InstrumentationFlags.NATIVE_TEST_ACTIVITY_KEY;
import static com.android.tests.chromium.host.InstrumentationFlags.NATIVE_UNIT_TEST_ACTIVITY_KEY;
import static com.android.tests.chromium.host.InstrumentationFlags.RUN_IN_SUBTHREAD_KEY;
import static com.android.tests.chromium.host.InstrumentationFlags.STDOUT_FILE_KEY;
import static com.android.tests.chromium.host.InstrumentationFlags.TEST_RUNNER;

import android.annotation.NonNull;

import com.android.ddmlib.MultiLineReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.TestInvocation;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.TestErrorIdentifier;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.testtype.GTestListTestParser;
import com.android.tradefed.testtype.GTestResultParser;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestCollector;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A host-side test-runner capable of running Chromium unit-tests.
 */
public class ChromiumHostDrivenTest implements IRemoteTest, IDeviceTest, ITestCollector,
        ITestFilterReceiver {

    private static final String CLEAR_CLANG_COVERAGE_FILES =
            "find /data/misc/trace -name '*.profraw' -delete";
    private static final Duration TESTS_TIMEOUT = Duration.ofMinutes(30);
    private static final String GTEST_FLAG_PRINT_TIME = "--gtest_print_time";
    private static final String GTEST_FLAG_FILTER = "--gtest_filter";
    private static final String GTEST_FLAG_LIST_TESTS = "--gtest_list_tests";
    private static final String GTEST_FLAG_FILE = "--gtest_flagfile";
    @Option(
        name = "include-filter",
        description = "The set of annotations a test must have to be run.")
    private Set<String> includeFilters = new LinkedHashSet<>();
    @Option(
        name = "exclude-filter",
        description =
            "The set of annotations to exclude tests from running. A test must have "
                + "none of the annotations in this list to run.")
    private Set<String> excludeFilters = new LinkedHashSet<>();
    private boolean collectTestsOnly = false;
    private ITestDevice device = null;

    @Option(
            name = "dump-native-coverage",
            description = "Force APK under test to dump native test coverage upon exit"
    )
    private boolean isCoverageEnabled = false;
    @Option(
            name = "library-to-load",
            description = "Name of the .so file under test"
    )
    private String libraryToLoad = "";


    /**
     * Creates a temporary file on the host machine then push it to the device in a temporary
     * location It is necessary to create a temp file for output for each instrumentation run and
     * not module invocation. This is preferred over using
     * {@link com.android.tradefed.targetprep.RunCommandTargetPreparer}
     * because RunCommandTargetPreparer is only run once before the test invocation which leads to
     * incorrect parsing as the retries will all use the same file for test result outputs.
     */
    @NonNull
    private String createTempResultFileOnDevice() throws DeviceNotAvailableException {
        File resultFile = null;
        String deviceFileDestination;
        try {
            resultFile = FileUtil.createTempFile("gtest_results", ".txt");
            deviceFileDestination = String.format("/data/local/tmp/%s", resultFile.getName());
            getDevice().pushFile(resultFile, deviceFileDestination);
            FileUtil.deleteFile(resultFile);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create temp file for result on the device.", e);
        } finally {
            FileUtil.deleteFile(resultFile);
        }
        return deviceFileDestination;
    }

    /**
     * This creates the gtest filter string which indicates which test should be run.
     * Sometimes the gtest filter is long (> 500 character) which results in creating
     * a temporary flag file and have gtest result the filter from the flag file.
     *
     * @return A gtest argument for flag file or --gtest_filter directly.
     */
    @NonNull
    private String getGTestFilters() throws DeviceNotAvailableException {
        StringBuilder filter = new StringBuilder();
        if (!includeFilters.isEmpty() || !excludeFilters.isEmpty()) {
            filter.append(GTEST_FLAG_FILTER);
            filter.append('=');
            Joiner joiner = Joiner.on(":").skipNulls();
            if (!includeFilters.isEmpty()) {
                joiner.appendTo(filter, includeFilters);
            }
            if (!excludeFilters.isEmpty()) {
                filter.append("-");
                joiner.appendTo(filter, excludeFilters);
            }
        }
        String filterFlag = filter.toString();
        // Handle long args
        if (filterFlag.length() > 500) {
            String tmpFlag = createFlagFileOnDevice(filterFlag);
            return String.format("%s=%s", GTEST_FLAG_FILE, tmpFlag);
        }
        return filterFlag;
    }

    /**
     * Helper method for getGTestFilters which creates a temporary flag file and push it to device.
     *
     * If it fails to create a file then it will directly use the filter in the adb command.
     *
     * @param filter the string to append to the flag file.
     * @return path to the flag file on device or null if it could not be created.
     */
    @NonNull
    private String createFlagFileOnDevice(@NonNull String filter)
            throws DeviceNotAvailableException {
        File tmpFlagFile = null;
        String devicePath;
        try {
            tmpFlagFile = FileUtil.createTempFile("flagfile", ".txt");
            FileUtil.writeToFile(filter, tmpFlagFile);
            devicePath = String.format("/data/local/tmp/%s", tmpFlagFile.getName());
            getDevice().pushFile(tmpFlagFile, devicePath);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create temp file for gtest filter flag on the device.", e);
        } finally {
            FileUtil.deleteFile(tmpFlagFile);
        }
        return devicePath;
    }

    @NonNull
    private String getAllGTestFlags() throws DeviceNotAvailableException {
        String flags = String.format("%s %s", GTEST_FLAG_PRINT_TIME, getGTestFilters());
        if (isCollectTestsOnly()) {
            flags = String.format("%s %s", flags, GTEST_FLAG_LIST_TESTS);
        }
        return flags;
    }

    /**
     * The flags all exist in Chromium's instrumentation APK
     * {@link org.chromium.build.gtest_apk.NativeTestInstrumentationTestRunner} and
     * {@link org.chromium.native_test.NativeTest}.
     *
     * The following is a brief explanation for each flag
     * <ul>
     * <li> NATIVE_TEST_ACTIVITY_KEY: Indicates the name of the activity which should be
     * started by the instrumentation APK. This activity is responsible for executing gtests.
     * <li> RUN_IN_SUBTHREAD_KEY: Whether to run the tests in the main-thread or a sub-thread.
     * <li> EXTRA_SHARD_NANO_TIMEOUT_KEY: Shard timeout (Equal to the test timeout and not
     * important as we only use a single shard).
     * <li> LIBRARY_TO_LOAD_ACTIVITY_KEY: Name of the native library which has the code under
     * test. System.LoadLibrary will be invoked on the value of this flag
     * <li> STDOUT_FILE_KEY: Path to the file where stdout/stderr will be redirected to.</li>
     * <li> COMMAND_LINE_FLAGS_KEY: Command line flags delegated to the gtest executor. This is
     * mostly used for gtest flags
     * <li> DUMP_COVERAGE_KEY: Flag used to indicate that the apk should not exit before dumping
     * native coverage.
     * </ul>
     *
     * @param resultFilePath path to a temporary file on the device which the gtest result will be
     *                       directed to
     * @return an instrumentation command that can be executed using adb shell am instrument.
     */
    @NonNull
    private String createRunAllTestsCommand(@NonNull String resultFilePath)
            throws DeviceNotAvailableException {
        InstrumentationCommandBuilder builder = new InstrumentationCommandBuilder(TEST_RUNNER)
                .addArgument(NATIVE_TEST_ACTIVITY_KEY, NATIVE_UNIT_TEST_ACTIVITY_KEY)
                .addArgument(RUN_IN_SUBTHREAD_KEY, "1")
                .addArgument(EXTRA_SHARD_NANO_TIMEOUT_KEY, String.valueOf(TESTS_TIMEOUT.toNanos()))
                .addArgument(LIBRARY_TO_LOAD_ACTIVITY_KEY, libraryToLoad)
                .addArgument(STDOUT_FILE_KEY, resultFilePath)
                .addArgument(COMMAND_LINE_FLAGS_KEY,
                        String.format("'%s'", getAllGTestFlags()));
        if (isCoverageEnabled) {
            builder.addArgument(DUMP_COVERAGE_KEY, "true");
        }
        return builder.build();
    }

    /**
     * Those logs can be found in host_log_%s.txt which is bundled with test execution.
     *
     * @param cmd Command used to instrumentation, this has all the flags which can help debugging
     *            unusual behaviour.
     */
    private void printHostLogs(@NonNull String cmd) {
        LogUtil.CLog.i(String.format("[Cronet] Library to be loaded: %s\n", libraryToLoad));
        LogUtil.CLog.i(String.format("[Cronet] Command used to run gtests: adb shell %s\n", cmd));
        LogUtil.CLog.i(String.format("[Cronet] Native-Coverage = %b", isCoverageEnabled));
    }

    /**
     * This is automatically invoked by the {@link com.android.tradefed.testtype.HostTest}.
     *
     * @param testInfo The {@link TestInformation} object containing useful information to run
     *     tests.
     * @param listener the {@link ITestInvocationListener} of test results
     */
    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        TestListenerWithTime testListener =
                new TestListenerWithTime(System.currentTimeMillis(), libraryToLoad, listener);
        try {
            if (Strings.isNullOrEmpty(libraryToLoad)) {
                throw new IllegalStateException("No library provided to be loaded.");
            }
            String resultFilePath = createTempResultFileOnDevice();
            String cmd = createRunAllTestsCommand(resultFilePath);
            printHostLogs(cmd);
            // We don't care about the result of this command. If it
            // fails then it will be retried as |executeShellV2Command| retries
            // by default 2 more times and if all of them fail then it will
            // throw an exception which will fail the test anyway.
            getDevice().executeShellV2Command(CLEAR_CLANG_COVERAGE_FILES);
            CommandResult result =
                    getDevice()
                            .executeShellV2Command(
                                    cmd,
                                    /* timeout= */ TESTS_TIMEOUT.toMillis(),
                                    /* timeUnit= */ TimeUnit.MILLISECONDS,
                                    // Don't retry as the parent runner will already
                                    // handle that.
                                    /* retry= */ 0);
            if (result.getStatus().equals(CommandStatus.TIMED_OUT)) {
                throw new HarnessRuntimeException(
                        "The test instrumentation has timed out! Check the device logcat for"
                                + " further information.",
                        TestErrorIdentifier.TEST_TIMEOUT);
            } else if (!result.getStatus().equals(CommandStatus.SUCCESS)) {
                throw new HarnessRuntimeException(
                        String.format("Failed to run adb command: '%s'", cmd),
                        TestErrorIdentifier.TEST_BINARY_EXIT_CODE_ERROR);
            }
            parseAndReport(resultFilePath, testListener);
        } catch (Exception e) {
            // We want to catch the exception before we propagate it upward
            // to the parent runner. It's the responsibility of the test runner
            // to call testRunStarted and testRunFailed. However, if our test
            // fails before we even reach the parsing stage of results then
            // testRunStarted would never be called and this leads to TH
            // silently passing the test.
            // b/402068773
            testListener.failRun(
                    TestInvocation.createFailureFromException(e, FailureStatus.TEST_FAILURE));
            throw e;
        } finally {
            // If testRunStarted was not called and we didn't throw any
            // exceptions then something has gone wrong in the test runner. It's
            // safer to fail than to silently pass. b/402068773
            testListener.failRunIfNotStarted();
        }
    }

    private void parseAndReport(@NonNull String resultFilePath,
            @NonNull ITestInvocationListener listener) throws DeviceNotAvailableException {
        File resultFile = device.pullFile(resultFilePath);
        if (resultFile == null) {
            throw new IllegalStateException(
                    "Failed to retrieve gtest results file from device.");
        }
        try (FileInputStreamSource data = new FileInputStreamSource(resultFile)) {
            listener.testLog(
                String.format("gtest_output_%s", resultFile.getName()), LogDataType.TEXT, data);
        }
        // Loading all the lines is fine since this is done on the host-machine.
        String[] lines;
        try {
            lines = Files.readAllLines(resultFile.toPath()).toArray(String[]::new);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read gtest results file on host machine.", e);
        }
        if (lines.length == 0) {
            throw new IllegalStateException("Expected gtest results but found nothing.");
        }
        MultiLineReceiver parser;
        // the parser automatically reports the test result back to the infra through the listener.
        if (isCollectTestsOnly()) {
            parser = new GTestListTestParser(libraryToLoad, listener);
        } else {
            parser = new GTestResultParser(libraryToLoad, listener);
        }
        parser.processNewLines(lines);
        parser.done();
    }

    // ------- Everything below is called by HostTest and should not be invoked manually -----
    public boolean isCollectTestsOnly() {
        return collectTestsOnly;
    }

    @Override
    public void setCollectTestsOnly(boolean shouldCollectTest) {
        collectTestsOnly = shouldCollectTest;
    }

    public String cleanFilter(String filter) {
        return filter.replace('#', '.');
    }

    @Override
    public void addIncludeFilter(String filter) {
        includeFilters.add(cleanFilter(filter));
    }

    @Override
    public void addAllIncludeFilters(Set<String> filters) {
        for (String filter : filters) {
            includeFilters.add(cleanFilter(filter));
        }
    }

    @Override
    public void addExcludeFilter(String filter) {
        excludeFilters.add(cleanFilter(filter));
    }

    @Override
    public void addAllExcludeFilters(Set<String> filters) {
        for (String filter : filters) {
            excludeFilters.add(cleanFilter(filter));
        }
    }

    @Override
    public void clearIncludeFilters() {
        includeFilters.clear();
    }

    @Override
    public Set<String> getIncludeFilters() {
        return includeFilters;
    }

    @Override
    public Set<String> getExcludeFilters() {
        return excludeFilters;
    }

    @Override
    public void clearExcludeFilters() {
        excludeFilters.clear();
    }

    @Override
    public ITestDevice getDevice() {
        return device;
    }

    @Override
    public void setDevice(ITestDevice device) {
        this.device = device;
    }
}