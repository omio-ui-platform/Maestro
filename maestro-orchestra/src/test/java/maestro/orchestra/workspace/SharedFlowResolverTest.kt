package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.error.SyntaxError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SharedFlowResolverTest {

    private val alias = "app/fe-utils/flows/initial-setup.yaml"

    /**
     * Creates a checkout at [root] holding both a shared flow (tagged with its own root, so a test
     * can tell WHICH checkout was resolved) and a test flow that references it via the alias.
     */
    private fun checkout(root: Path): Path {
        root.resolve("packages/fe-utils/maestro/shared/flows").createDirectories()
        root.resolve("packages/fe-utils/maestro/shared/flows/initial-setup.yaml")
            .writeText("owner: $root")

        val flowDir = root.resolve("packages/conversion-e2e/maestro/tests/app")
        flowDir.createDirectories()
        val flow = flowDir.resolve("some-test.yaml")
        flow.writeText("appId: com.example\n---\n- runFlow: $alias\n")
        return flow
    }

    private fun ownerOf(resolved: Path?) =
        resolved?.let { Path.of(it.toString()).toFile().readText().removePrefix("owner: ") }

    @Test
    fun `resolves from a checkout directory named app`(@TempDir tmp: Path) {
        val root = tmp.resolve("Development/omio.github/app")

        val resolved = SharedFlowResolver.resolveAlias(checkout(root), alias)

        assertThat(ownerOf(resolved)).isEqualTo(root.toString())
    }

    @Test
    fun `resolves from a checkout directory named anything else`(@TempDir tmp: Path) {
        val root = tmp.resolve("src/omio-app")

        val resolved = SharedFlowResolver.resolveAlias(checkout(root), alias)

        assertThat(ownerOf(resolved)).isEqualTo(root.toString())
    }

    @Test
    fun `resolves from a worktree beside a checkout named app`(@TempDir tmp: Path) {
        checkout(tmp.resolve("Development/omio.github/app"))
        val worktree = tmp.resolve("Development/omio.github/rn-pay-fix")

        val resolved = SharedFlowResolver.resolveAlias(checkout(worktree), alias)

        assertThat(ownerOf(resolved)).isEqualTo(worktree.toString())
    }

    @Test
    fun `resolves from a per-task workspace under a directory named app`(@TempDir tmp: Path) {
        val workspace = tmp.resolve("conductor/workspaces/app/chicago")

        val resolved = SharedFlowResolver.resolveAlias(checkout(workspace), alias)

        assertThat(ownerOf(resolved)).isEqualTo(workspace.toString())
    }

    @Test
    fun `a worktree nested in a checkout resolves against itself, not the parent`(@TempDir tmp: Path) {
        // Regression guard: deriving the root by splitting the flow path at the first `/app/`
        // segment produced the OUTER checkout here. That path exists, so the wrong copy of the
        // shared flow ran with no error at all.
        val outer = tmp.resolve("Development/omio.github/app")
        checkout(outer)
        val nested = outer.resolve(".worktrees/rn-pay-fix")

        val resolved = SharedFlowResolver.resolveAlias(checkout(nested), alias)

        assertThat(ownerOf(resolved)).isEqualTo(nested.toString())
    }

    @Test
    fun `does not fall back to an outer checkout when the nearest one lacks the file`(
        @TempDir tmp: Path,
    ) {
        // Resolution must stop at the worktree. Continuing into the outer checkout would run the
        // parent branch's copy of the shared flow — the same silent cross-checkout leak that
        // splitting the path at `/app/` caused.
        val outer = tmp.resolve("Development/omio.github/app")
        checkout(outer)
        // A worktree that has its own test flow but no copy of the shared flow.
        val flowDir = outer.resolve(".worktrees/partial/packages/conversion-e2e/maestro/tests/app")
        flowDir.createDirectories()
        val flow = flowDir.resolve("some-test.yaml")
        flow.writeText("appId: com.example\n---\n- runFlow: $alias\n")

        assertThat(SharedFlowResolver.resolveAlias(flow, alias)).isNull()
        // ...and the alias points inside the worktree, so an error can name that one path.
        assertThat(SharedFlowResolver.aliasTarget(flow, alias))
            .isEqualTo(
                outer.resolve(
                    ".worktrees/partial/packages/fe-utils/maestro/shared/flows/initial-setup.yaml"
                )
            )
    }

    @Test
    fun `returns null when no ancestor holds the shared file`(@TempDir tmp: Path) {
        val flowDir = tmp.resolve("nowhere/packages/conversion-e2e/maestro/tests/app")
        flowDir.createDirectories()
        val flow = flowDir.resolve("some-test.yaml")
        flow.writeText("appId: com.example\n---\n- runFlow: $alias\n")

        assertThat(SharedFlowResolver.resolveAlias(flow, alias)).isNull()
    }

    @Test
    fun `resolves a shared file nested in subdirectories`(@TempDir tmp: Path) {
        // The previous implementation joined the trailing segments with Kotlin's default `", "`
        // separator, so any nested shared file resolved to `sub, nested.yaml`.
        val root = tmp.resolve("app")
        val flow = checkout(root)
        val nestedDir = root.resolve("packages/fe-utils/maestro/shared/flows/sub")
        nestedDir.createDirectories()
        nestedDir.resolve("nested.yaml").writeText("owner: $root")

        val resolved = SharedFlowResolver.resolveAlias(flow, "app/fe-utils/flows/sub/nested.yaml")

        assertThat(resolved).isEqualTo(nestedDir.resolve("nested.yaml"))
    }

    @Test
    fun `resolves scripts as well as flows`(@TempDir tmp: Path) {
        val root = tmp.resolve("app")
        val flow = checkout(root)
        val scripts = root.resolve("packages/fe-utils/maestro/shared/scripts")
        scripts.createDirectories()
        scripts.resolve("helper.js").writeText("// helper")

        val resolved = SharedFlowResolver.resolveAlias(flow, "app/fe-utils/scripts/helper.js")

        assertThat(resolved).isEqualTo(scripts.resolve("helper.js"))
    }

    @Test
    fun `reports a syntax error for a truncated alias instead of crashing`(@TempDir tmp: Path) {
        val flow = checkout(tmp.resolve("app"))

        // Indexing the segments directly used to raise IndexOutOfBoundsException here.
        val error = assertThrows<SyntaxError> { SharedFlowResolver.resolveAlias(flow, "app/fe-utils") }

        assertThat(error.message).contains("app/<package>/<flows|scripts>/<file>")
    }

    @Test
    fun `recognises only the app alias marker`() {
        assertThat(SharedFlowResolver.isAlias("app/fe-utils/flows/x.yaml")).isTrue()
        assertThat(SharedFlowResolver.isAlias("subflow.yaml")).isFalse()
        assertThat(SharedFlowResolver.isAlias("../shared/x.yaml")).isFalse()
        assertThat(SharedFlowResolver.isAlias("/abs/x.yaml")).isFalse()
        assertThat(SharedFlowResolver.isAlias("apps/x.yaml")).isFalse()
    }

    @Test
    fun `resolves a checkout-root-relative path for component images`(@TempDir tmp: Path) {
        val root = tmp.resolve("conductor/workspaces/app/chicago")
        val flow = checkout(root)
        val images = root.resolve("packages/fe-utils/maestro/images")
        images.createDirectories()
        images.resolve("logo.png").writeText("png")

        val resolved = SharedFlowResolver.resolveFromCheckoutRoot(
            flow,
            "packages/fe-utils/maestro/images/logo.png",
        )

        assertThat(resolved).isEqualTo(images.resolve("logo.png"))
    }

    @Test
    fun `returns null for a checkout-root-relative path no ancestor holds`(@TempDir tmp: Path) {
        val flow = checkout(tmp.resolve("app"))

        assertThat(SharedFlowResolver.resolveFromCheckoutRoot(flow, "nope/missing.png")).isNull()
    }

    @Test
    fun `finds the checkout root as the nearest ancestor holding packages`(@TempDir tmp: Path) {
        val root = tmp.resolve("a/b")
        val flow = checkout(root)

        assertThat(SharedFlowResolver.findCheckoutRoot(flow)).isEqualTo(root)
    }

    @Test
    fun `finds the nested checkout root, not the enclosing one`(@TempDir tmp: Path) {
        val outer = tmp.resolve("Development/omio.github/app")
        checkout(outer)
        val nested = outer.resolve(".worktrees/rn-pay-fix")

        assertThat(SharedFlowResolver.findCheckoutRoot(checkout(nested))).isEqualTo(nested)
    }

    @Test
    fun `stops at the filesystem root when the flow is not in a checkout`(@TempDir tmp: Path) {
        // No `packages/` anywhere above, so the walk terminates instead of probing to `/` and
        // reporting every ancestor up to $HOME as a candidate.
        val flowDir = tmp.resolve("loose/flows")
        flowDir.createDirectories()
        val flow = flowDir.resolve("some-test.yaml")
        flow.writeText("appId: com.example\n---\n- runFlow: $alias\n")

        assertThat(SharedFlowResolver.findCheckoutRoot(flow)).isNull()
        assertThat(SharedFlowResolver.aliasTarget(flow, alias)).isNull()
        assertThat(SharedFlowResolver.resolveAlias(flow, alias)).isNull()
    }
}
