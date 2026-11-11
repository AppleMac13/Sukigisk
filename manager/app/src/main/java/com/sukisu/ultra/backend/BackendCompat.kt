package com.sukisu.ultra.backend

/**
 * Capability table of the backend this manager is grafted onto (Magisk).
 *
 * The manager UI is written for a KernelSU kernel plus the `ksud` user-space
 * daemon. Magisk only provides its own daemon, module store and su. Anything it
 * cannot serve is reported as unavailable here so the corresponding entry points
 * stay hidden instead of surfacing as dead controls.
 */
object BackendCompat {

    /** SUSFS, KPM, umount configuration, app profiles and kernel extensions. */
    val supportsKernelExtensions: Boolean
        get() = false

    /** The tools screen is a shell over kernel-side knobs. */
    val supportsTools: Boolean
        get() = false

    /** `ksud boot-patch` mapped onto Magisk's own installer (patch a boot image). */
    val supportsBootPatch: Boolean
        get() = true

    /** Version spoofing is a kernel-side uname/property override. */
    val supportsVersionSpoof: Boolean
        get() = false

    /** The in-app assistant drives kernel-side controllers directly. */
    val supportsAiAssistant: Boolean
        get() = false

    /** Module repository downloads still work; module install maps to Magisk. */
    val supportsModules: Boolean
        get() = true

    /** Superuser listing comes from Magisk's own policy database. */
    val supportsSuperuser: Boolean
        get() = true
}
