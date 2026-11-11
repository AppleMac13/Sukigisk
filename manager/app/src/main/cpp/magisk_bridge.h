#ifndef KERNELSU_MAGISK_BRIDGE_H
#define KERNELSU_MAGISK_BRIDGE_H

/*
 * Magisk data-plane bridge.
 *
 * The JNI layer was written against the KernelSU driver: every value the
 * manager shows (kernel version, manager flag, UAPI version, the su policy
 * list) came back from an ioctl on the "[ksu_driver]" fd. In a grafted build
 * there is no such driver, so ksu::ksuctl() falls back to this bridge.
 *
 * The bridge answers the same ioctl numbers from the Magisk backend instead:
 * it asks the Magisk binary for its version and reads the su policy out of
 * Magisk's own database (/data/adb/magisk.db). Everything runs through `su`,
 * because the manager process itself is unprivileged and /data/adb is 0700
 * root - without a root shell there is nothing to read, and the bridge
 * reports failure so the front end keeps its preview mode.
 *
 * Contract: returns 0 on success, -1 (or a negative errno) when the request
 * cannot be served by this backend. Never writes anywhere except the arg
 * struct the caller passed in.
 */
int mb_ctl(unsigned long op, void *arg);

#endif // KERNELSU_MAGISK_BRIDGE_H
