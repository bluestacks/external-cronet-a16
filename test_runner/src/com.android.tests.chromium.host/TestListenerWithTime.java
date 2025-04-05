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

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestSummary;

import java.util.HashMap;
import java.util.Map;

public class TestListenerWithTime implements ITestInvocationListener {
    private final long testStartingTime;
    private final ITestInvocationListener delegate;
    private final String mRunName;
    private boolean mTestRunStarted;

    public TestListenerWithTime(
            long testStartingTime, String runName, ITestInvocationListener delegate) {
        this.testStartingTime = testStartingTime;
        this.mRunName = runName;
        this.delegate = delegate;
    }

    public void failRun(FailureDescription failure) {
        // We call testRunStarted here (if it's not called) and
        // testRunFailed as the parent runner does not guarantee that it
        // will call that for us.
        this.testRunStartIfNotStarted();
        this.testRunFailed(failure);
        this.testRunEnded(0, new HashMap<String, String>());
    }

    public void testRunStartIfNotStarted() {
        if (!mTestRunStarted) {
            this.testRunStarted(mRunName, 0);
        }
    }

    @Override
    public void testRunStarted(String runName, int testCount) {
        mTestRunStarted = true;
        delegate.testRunStarted(runName, testCount, /* attemptNumber= */ 0, testStartingTime);
    }

    // -------------- Delegate ---------------

    @Override
    public void invocationStarted(IInvocationContext context) {
        delegate.invocationStarted(context);
    }

    @Override
    public void invocationEnded(long elapsedTime) {
        delegate.invocationEnded(elapsedTime);
    }

    @Override
    public void invocationFailed(Throwable cause) {
        delegate.invocationFailed(cause);
    }

    @Override
    public void invocationFailed(FailureDescription failure) {
        delegate.invocationFailed(failure);
    }

    @Override
    public TestSummary getSummary() {
        return delegate.getSummary();
    }

    @Override
    public void invocationInterrupted() {
        delegate.invocationInterrupted();
    }

    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        delegate.testModuleStarted(moduleContext);
    }

    @Override
    public void testModuleEnded() {
        delegate.testModuleEnded();
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber) {
        mTestRunStarted = true;
        delegate.testRunStarted(runName, testCount, attemptNumber, testStartingTime);
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber, long startTime) {
        mTestRunStarted = true;
        delegate.testRunStarted(runName, testCount, attemptNumber, startTime);
    }

    @Override
    public void testRunFailed(String errorMessage) {
        delegate.testRunFailed(errorMessage);
    }

    @Override
    public void testRunFailed(FailureDescription failure) {
        delegate.testRunFailed(failure);
    }

    @Override
    public void testRunEnded(long elapsedTimeMillis, Map<String, String> runMetrics) {
        delegate.testRunEnded(elapsedTimeMillis, runMetrics);
    }

    @Override
    public void testRunEnded(
            long elapsedTimeMillis, HashMap<String, MetricMeasurement.Metric> runMetrics) {
        delegate.testRunEnded(elapsedTimeMillis, runMetrics);
    }

    @Override
    public void testRunStopped(long elapsedTime) {
        delegate.testRunStopped(elapsedTime);
    }

    @Override
    public void testStarted(TestDescription test) {
        delegate.testStarted(test);
    }

    @Override
    public void testStarted(TestDescription test, long startTime) {
        delegate.testStarted(test, startTime);
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        delegate.testFailed(test, trace);
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        delegate.testFailed(test, failure);
    }

    @Override
    public void testAssumptionFailure(TestDescription test, String trace) {
        delegate.testAssumptionFailure(test, trace);
    }

    @Override
    public void testAssumptionFailure(TestDescription test, FailureDescription failure) {
        delegate.testAssumptionFailure(test, failure);
    }

    @Override
    public void testIgnored(TestDescription test) {
        delegate.testIgnored(test);
    }

    @Override
    public void testEnded(TestDescription test, Map<String, String> testMetrics) {
        delegate.testEnded(test, testMetrics);
    }

    @Override
    public void testEnded(
            TestDescription test, HashMap<String, MetricMeasurement.Metric> testMetrics) {
        delegate.testEnded(test, testMetrics);
    }

    @Override
    public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
        delegate.testLog(dataName, dataType, dataStream);
    }

    @Override
    public void testEnded(TestDescription test, long endTime, Map<String, String> testMetrics) {
        delegate.testEnded(test, endTime, testMetrics);
    }

    @Override
    public void testEnded(
            TestDescription test,
            long endTime,
            HashMap<String, MetricMeasurement.Metric> testMetrics) {
        delegate.testEnded(test, endTime, testMetrics);
    }

    public void failRunIfNotStarted() {
        if (!mTestRunStarted) {
            this.failRun(
                    FailureDescription.create("testRunStarted was not called on the listener!"));
        }
    }
}
