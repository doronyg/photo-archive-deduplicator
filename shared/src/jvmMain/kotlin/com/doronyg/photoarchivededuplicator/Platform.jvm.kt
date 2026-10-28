package com.doronyg.photoarchivededuplicator

actual fun platformName(): String = "Desktop JVM ${System.getProperty("java.version")}"
