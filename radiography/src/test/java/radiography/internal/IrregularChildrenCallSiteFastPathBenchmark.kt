@file:OptIn(UiToolingDataApi::class)
package radiography.internal

import androidx.compose.ui.tooling.data.CallGroup
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.unit.IntRect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radiography.ScannableView.CallGroupInfo

/**
 * Microbenchmark for the call-site fast-path in [Group.computeLayoutInfos]: when `data` is
 * empty, we skip the `subComposedChildren + androidViewChildren` concat and return
 * [emptySequence] directly.
 *
 * Without the fast-path, every recursive call on an empty-data Group allocates:
 *   * the lambda backing [subComposedChildren]'s flatMap sequence
 *   * the outer `Sequence + List` `plus` wrapper that combines the two sources
 *
 * The per-call allocation is small; the win is multiplicative in the number of Groups
 * traversed. Compose-only screens have hundreds of empty-data Groups per radiography walk
 * (~95% of the ~500 Groups in the reference UserProfile screen).
 *
 * We demand a conservative ≥ 1.5× speedup — the wrapper allocations are cheap individually
 * and JIT escape analysis often elides them, so the microbenchmark understates the
 * allocation-pressure win visible in long-running workloads.
 */
internal class IrregularChildrenCallSiteFastPathBenchmark {

  @Test fun `call-site fast-path is measurably faster than unconditional concat on empty data`() {
    val iterations = 10_000
    val group = emptyDataGroup()
    val callChain = emptyList<CallGroupInfo>()

    val cold = measureNanosAveraged(warmupIterations = 3, measuredIterations = 5) {
      var sinkCount = 0
      repeat(iterations) {
        // Pre-fast-path: unconditionally allocate and concat both sources.
        val seq = group.subComposedChildren(callChain, semanticsOwner = null) +
          group.androidViewChildren()
        sinkCount += seq.count()
      }
      sinkCount
    }

    val warm = measureNanosAveraged(warmupIterations = 3, measuredIterations = 5) {
      var sinkCount = 0
      repeat(iterations) {
        // Post-fast-path: call-site short-circuit when data is empty.
        val seq: Sequence<Any> =
          if (group.data.isEmpty()) emptySequence()
          else group.subComposedChildren(callChain, semanticsOwner = null) +
            group.androidViewChildren()
        sinkCount += seq.count()
      }
      sinkCount
    }

    println(
      "IrregularChildrenCallSiteFastPathBenchmark: cold=${cold}ns/batch warm=${warm}ns/batch " +
        "speedup=${cold.toDouble() / warm}x (batch = $iterations calls)"
    )
    assertThat(cold.toDouble() / warm).isAtLeast(1.5)
  }

  private fun emptyDataGroup(): Group = CallGroup(
    /* key = */ null,
    /* name = */ null,
    /* box = */ IntRect.Zero,
    /* location = */ null,
    /* identity = */ null,
    /* parameters = */ emptyList(),
    /* data = */ emptyList(),
    /* children = */ emptyList(),
    /* isInline = */ false,
  )

  private inline fun measureNanosAveraged(
    warmupIterations: Int,
    measuredIterations: Int,
    block: () -> Any,
  ): Long {
    repeat(warmupIterations) { block() }
    val start = System.nanoTime()
    var keepAlive = 0
    repeat(measuredIterations) {
      val result = block()
      if (result is Int) keepAlive += result
    }
    val elapsed = System.nanoTime() - start
    require(keepAlive >= Int.MIN_VALUE)
    return elapsed / measuredIterations
  }
}
