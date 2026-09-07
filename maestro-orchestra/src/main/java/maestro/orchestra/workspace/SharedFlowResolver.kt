package maestro.orchestra.workspace

import maestro.orchestra.error.SyntaxError
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Resolves the monorepo-relative `app/<package>/<flows|scripts>/<rest>` aliases used by `runFlow`,
 * `runScript` and component image paths.
 *
 * The alias names a file in a *sibling* package of the flow that references it, so resolving it
 * means finding the checkout root. That root is the nearest ancestor of the referencing flow
 * holding a `packages/` directory, which makes resolution independent of what the checkout is
 * called or where it lives: a clone named `app`, a clone named anything else, a git worktree, or a
 * per-task workspace directory all work.
 *
 * Resolution stops at that root and never continues into an enclosing checkout. A worktree nested
 * inside another checkout therefore resolves against ITSELF, and a shared file the worktree is
 * missing is an error rather than a silent fallback to the outer checkout's copy. Deriving the root
 * from the path string instead (splitting at the first `/app/` segment) picked the outer checkout,
 * which silently ran the wrong copy of a shared flow.
 */
object SharedFlowResolver {

    /** Alias marker: the first segment of a monorepo-relative path. */
    const val ALIAS_ROOT = "app"

    private const val PACKAGES_DIR = "packages"
    private const val SHARED_DIR = "maestro/shared"

    fun isAlias(requestedPath: String) =
        requestedPath.split("/").firstOrNull() == ALIAS_ROOT

    /**
     * `app/<package>/<flows|scripts>/<rest>` -> `<checkout>/packages/<package>/maestro/shared/<flows|scripts>/<rest>`,
     * or null when the file is absent from the checkout or [flowPath] has no checkout root above it.
     */
    fun resolveAlias(flowPath: Path, requestedPath: String): Path? =
        aliasTarget(flowPath, requestedPath)?.takeIf { it.exists() }

    /**
     * The single path an alias designates, whether or not anything is there — so a caller can name
     * the expected location when the file is missing. Null when [flowPath] has no checkout root
     * above it.
     */
    fun aliasTarget(flowPath: Path, requestedPath: String): Path? {
        val parts = requestedPath.split("/").filter { it.isNotEmpty() }
        if (parts.size < 4) {
            throw SyntaxError(
                "Invalid shared path \"$requestedPath\": expected " +
                    "$ALIAS_ROOT/<package>/<flows|scripts>/<file>"
            )
        }

        val (_, packageName, scriptOrFlow) = parts
        val rest = parts.drop(3).joinToString("/")

        return findCheckoutRoot(flowPath)
            ?.resolve("$PACKAGES_DIR/$packageName/$SHARED_DIR/$scriptOrFlow/$rest")
    }

    /**
     * Resolves a path stated relative to the checkout root — component images use this, since they
     * are addressed from the root rather than through the `app/` alias.
     */
    fun resolveFromCheckoutRoot(flowPath: Path, relativePath: String): Path? =
        findCheckoutRoot(flowPath)?.resolve(relativePath)?.takeIf { it.exists() }

    /**
     * The checkout [flowPath] belongs to: its nearest ancestor holding a `packages/` directory.
     * Null when there is none, which means the flow is not inside a monorepo checkout at all.
     */
    fun findCheckoutRoot(flowPath: Path): Path? = ancestorsOf(flowPath)
        .firstOrNull { it.resolve(PACKAGES_DIR).isDirectory() }

    private fun ancestorsOf(flowPath: Path): Sequence<Path> {
        val start = flowPath.toAbsolutePath().normalize().parent ?: return emptySequence()
        return generateSequence(start) { it.parent }
    }
}
