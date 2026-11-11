plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.library) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 24
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
extra["managerVersionCode"] = getVersionCode()
extra["managerVersionName"] = getVersionName()

fun getGitCommitCount(): Int {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
    return process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
}

fun getGitDescribe(): String {
    val process = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--always", "--abbrev=0"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

fun getVersionCode(): Int {
    val overrideCode = (findProperty("KSU_VERSION_CODE") ?: System.getenv("KSU_VERSION_CODE"))?.toString()
    if (!overrideCode.isNullOrBlank()) return overrideCode.toInt()
    val commitCount = getGitCommitCount()
    val major = 4
    val end = 2815
    return major * 10000 + commitCount - end
}

fun getVersionName(): String {
    val overrideName = (findProperty("KSU_VERSION_NAME") ?: System.getenv("KSU_VERSION_NAME"))?.toString()
    if (!overrideName.isNullOrBlank()) return overrideName
    return getGitDescribe()
}