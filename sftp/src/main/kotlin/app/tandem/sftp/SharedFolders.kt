// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.sftp

import java.nio.file.Path

/**
 * Which top-level folders of the shared storage the laptop may reach. The SFTP file system is rooted at the
 * storage directory, so a path such as `/DCIM/2024/a.jpg` has the folder `DCIM` as its first name. The root
 * itself may be listed, so the laptop can see the folders, but only the allowed ones appear in it.
 */
class SharedFolders(initial: Collection<String> = emptyList()) {
    @Volatile
    var names: Set<String> = initial.toSet()

    /** True for the root: it can be read (listed) but never changed. */
    fun isRoot(path: Path): Boolean = firstName(path) == null

    /** True when [path] is a shared folder or lies inside one. The root is not inside any. */
    fun isInside(path: Path): Boolean = firstName(path)?.let { it in names } == true

    /** The first name of an absolute path, or null for the root. `..` is resolved first, so it cannot escape. */
    private fun firstName(path: Path): String? {
        val clean = path.toAbsolutePath().normalize()
        return if (clean.nameCount == 0) null else clean.getName(0).toString()
    }
}
