package maestro.orchestra.workspace

import maestro.orchestra.error.SyntaxError
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Resolves the monorepo-relative `app/<package>/<flows|scripts>/<rest>` aliases used by `runFlow`,
 * `runScript` and component image paths.
 *
 * The alias names a file in a *sibling* package of the flow that references it, so resolving it
 * means finding the checkout root. This is done by walking up from the referencing flow and taking
 * the FIRST ancestor that actually contains the requested file. Two consequences worth knowing:
 *
 * - The checkout can be named and located anything: a clone named `app`, a clone named anything
 *   else, a git worktree, or a per-task workspace directory.
 * - Nearest-ancestor-wins means a worktree nested inside another checkout resolves against ITSELF.
 *   Deriving the root from the path string instead (splitting at the first `/app/` segment) picked
 *   the outer checkout, which silently ran the wrong copy of a shared flow.
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
     * or null when no ancestor of [flowPath] contains it.
     */
    fun resolveAlias(flowPath: Path, requestedPath: String): Path? {
        val parts = requestedPath.split("/").filter { it.isNotEmpty() }
        if (parts.size < 4) {
            throw SyntaxError(
                "Invalid shared path \"$requestedPath\": expected " +
                    "$ALIAS_ROOT/<package>/<flows|scripts>/<file>"
            )
        }

        val (_, packageName, scriptOrFlow) = parts
        val rest = parts.drop(3).joinToString("/")

        return findInAncestors(flowPath, "$PACKAGES_DIR/$packageName/$SHARED_DIR/$scriptOrFlow/$rest")
    }

    /**
     * Resolves a path stated relative to the checkout root — component images use this, since they
     * are addressed from the root rather than through the `app/` alias.
     */
    fun resolveFromCheckoutRoot(flowPath: Path, relativePath: String): Path? =
        findInAncestors(flowPath, relativePath)

    /** Ancestors of the referencing flow, nearest first, for error messages. */
    fun searchedRoots(flowPath: Path): List<Path> = ancestorsOf(flowPath).toList()

    private fun findInAncestors(flowPath: Path, relativePath: String): Path? = ancestorsOf(flowPath)
        .map { it.resolve(relativePath) }
        .firstOrNull { it.exists() }

    private fun ancestorsOf(flowPath: Path): Sequence<Path> {
        val start = flowPath.toAbsolutePath().normalize().parent ?: return emptySequence()
        return generateSequence(start) { it.parent }
    }
}
