/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.rt.execution.junit;

import junit.framework.ComparisonFailure;

/**
 * This File is taken from intellij-community sources <a href="https://github.com/JetBrains/intellij-community/blob/master/java/java-runtime/src/com/intellij/rt/execution/junit/FileComparisonFailure.java">FileComparisonFailure.java</a>
 * <p/>
 * Throwing this special exception will be handled by intellij so that you can diff directly with the file
 * <p/>
 * It is handled in <a href="https://github.com/JetBrains/intellij-community/blob/master/java/java-runtime/src/com/intellij/rt/execution/junit/ComparisonFailureData.java#L207-L223">ComparisonFailureData::create(Throwable assertion)</a>
 */
public class FileComparisonFailure extends ComparisonFailure {
    private final String myExpected;
    private final String myActual;
    private final String myFilePath;
    private final String myActualFilePath;

    public FileComparisonFailure(String message, /*@NotNull */String expected, /*@NotNull */String actual, String expectedFilePath) {
        this(message, expected, actual, expectedFilePath, null);
    }

    public FileComparisonFailure(String message, /*@NotNull */String expected, /*@NotNull */String actual, String expectedFilePath, String actualFilePath) {
        super(message, expected, actual);
        if (expected == null) throw new NullPointerException("'expected' must not be null");
        if (actual == null) throw new NullPointerException("'actual' must not be null");
        myExpected = expected;
        myActual = actual;
        myFilePath = expectedFilePath;
        myActualFilePath = actualFilePath;
    }

    public String getFilePath() {
        return myFilePath;
    }

    public String getActualFilePath() {
        return myActualFilePath;
    }

    public String getExpected() {
        return myExpected;
    }

    public String getActual() {
        return myActual;
    }
}

