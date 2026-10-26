// Judge 1 harness only. This class shadows support-1.0.0.jar's TimeUtilsKt on the run classpath.
// It keeps the same JVM signature, so the class under test calls it without recompilation.
package com.linecorp.cse.reqshield.support.utils

import judge.FakeClock

fun nowToEpochTime(): Long = FakeClock.read()
