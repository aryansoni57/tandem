// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.sftp

import org.apache.sshd.common.file.root.RootedFileSystemProvider
import java.io.IOException
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.ExecutorService

/**
 * A rooted file system that hides everything outside [folders]. The root can be listed and shows only the
 * shared folders; inside a shared folder everything works as on disk. Any other path is refused, and so is
 * changing the root. Paths are resolved first, so `/DCIM/../Secret` is judged as `/Secret`.
 */
internal class SharedFolderProvider(private val folders: SharedFolders) : RootedFileSystemProvider() {
    private fun denyUnlessInside(path: Path) {
        if (!folders.isInside(path)) throw AccessDeniedException(path.toString())
    }

    /** Reading is allowed on the root and inside folders; nothing else. */
    private fun denyUnlessReadable(path: Path) {
        if (!folders.isRoot(path)) denyUnlessInside(path)
    }

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>?,
    ): DirectoryStream<Path> {
        denyUnlessReadable(dir)
        val atRoot = folders.isRoot(dir)
        // At the root, hide every entry that is not a shared folder. The entries seen here are real paths below
        // the storage directory, so they are judged by their name, not by their position.
        val visible =
            DirectoryStream.Filter<Path> { entry ->
                (!atRoot || entry.fileName?.toString() in folders.names) && (filter?.accept(entry) ?: true)
            }
        return super.newDirectoryStream(dir, visible)
    }

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>?,
        vararg attrs: FileAttribute<*>?,
    ): SeekableByteChannel {
        denyUnlessInside(path)
        return super.newByteChannel(path, options, *attrs)
    }

    // The SFTP server opens files through channels, not through newByteChannel, so guard these too.
    override fun newFileChannel(
        path: Path,
        options: MutableSet<out OpenOption>?,
        vararg attrs: FileAttribute<*>?,
    ): FileChannel {
        denyUnlessInside(path)
        return super.newFileChannel(path, options, *attrs)
    }

    override fun newAsynchronousFileChannel(
        path: Path,
        options: MutableSet<out OpenOption>?,
        executor: ExecutorService?,
        vararg attrs: FileAttribute<*>?,
    ): AsynchronousFileChannel {
        denyUnlessInside(path)
        return super.newAsynchronousFileChannel(path, options, executor, *attrs)
    }

    override fun createDirectory(
        dir: Path,
        vararg attrs: FileAttribute<*>?,
    ) {
        denyUnlessInside(dir)
        super.createDirectory(dir, *attrs)
    }

    override fun delete(path: Path) {
        denyUnlessInside(path)
        super.delete(path)
    }

    override fun copy(
        source: Path,
        target: Path,
        vararg options: CopyOption?,
    ) {
        denyUnlessInside(source)
        denyUnlessInside(target)
        super.copy(source, target, *options)
    }

    override fun move(
        source: Path,
        target: Path,
        vararg options: CopyOption?,
    ) {
        denyUnlessInside(source)
        denyUnlessInside(target)
        super.move(source, target, *options)
    }

    override fun checkAccess(
        path: Path,
        vararg modes: AccessMode?,
    ) {
        if (modes.any { it != AccessMode.READ }) denyUnlessInside(path) else denyUnlessReadable(path)
        super.checkAccess(path, *modes)
    }

    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption?,
    ): A {
        denyUnlessReadable(path)
        return super.readAttributes(path, type, *options)
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption?,
    ): MutableMap<String, Any> {
        denyUnlessReadable(path)
        return super.readAttributes(path, attributes, *options)
    }

    // Attribute views are another way to read the same facts as readAttributes, so they are guarded alike.
    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption?,
    ): V? {
        denyUnlessReadable(path)
        return super.getFileAttributeView(path, type, *options)
    }

    override fun isHidden(path: Path): Boolean {
        denyUnlessReadable(path)
        return super.isHidden(path)
    }

    override fun isSameFile(
        path: Path,
        path2: Path,
    ): Boolean {
        denyUnlessReadable(path)
        denyUnlessReadable(path2)
        return super.isSameFile(path, path2)
    }

    override fun getFileStore(path: Path): FileStore {
        denyUnlessReadable(path)
        return super.getFileStore(path)
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any?,
        vararg options: LinkOption?,
    ) {
        denyUnlessInside(path)
        super.setAttribute(path, attribute, value, *options)
    }

    // Links could point out of the shared folders, so none are made or followed.
    override fun createSymbolicLink(
        link: Path,
        target: Path,
        vararg attrs: FileAttribute<*>?,
    ): Unit = throw IOException("links are not allowed")

    override fun createLink(
        link: Path,
        existing: Path,
    ): Unit = throw IOException("links are not allowed")
}
