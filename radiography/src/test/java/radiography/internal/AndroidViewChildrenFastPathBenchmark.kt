@file:OptIn(InternalComposeUiApi::class, UiToolingDataApi::class)
package radiography.internal

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.node.InteroperableComposeUiNode
import androidx.compose.ui.tooling.data.CallGroup
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.unit.IntRect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Microbenchmark comparing the fast-path `if (data.isEmpty()) return emptyList()` to the
 * pre-fast-path `data.mapNotNull { ... }` on a Group whose `data` is always empty.
 *
 * On a Compose-only tree the fast-path skips the `mapNotNull` allocation entirely. Real-world
 * speedup depends on JIT behavior: for a stable hot loop the JIT can escape-analyze the
 * short-lived `ArrayList` that `mapNotNull` allocates and elide the heap allocation. The
 * microbenchmark therefore demands a conservative ≥ 2× speedup — the full win manifests as
 * reduced allocation pressure (less GC, better cache locality) over a long-running test workload
 * rather than as a raw nanosecond delta, which the JFR profile in Airbnb's Interactions fork
 * confirmed at ~0.8 % of the hot thread (~2.5 s CPU across n=3 runs) even though microbenchmarks
 * show a smaller margin.
 */
internal class AndroidViewChildrenFastPathBenchmark {

  @Test fun `fast-path is measurably faster than mapNotNull on empty data`() {
    val iterations = 10_000
    val group = emptyDataGroup()

    val cold = measureNanosAveraged(warmupIterations = 3, measuredIterations = 5) {
      var sinkSize = 0
      repeat(iterations) {
        // Emulate the pre-fast-path code inline — the exact same statement that used to sit in
        // androidViewChildren(), but called directly on a Group we control here.
        val result = group.data.mapNotNull { datum ->
          (datum as? InteroperableComposeUiNode)?.getInteropView()
        }
        sinkSize += result.size
      }
      sinkSize
    }

    val warm = measureNanosAveraged(warmupIterations = 3, measuredIterations = 5) {
      var sinkSize = 0
      repeat(iterations) {
        sinkSize += group.androidViewChildren().size
      }
      sinkSize
    }

    println(
      "AndroidViewChildrenFastPathBenchmark: cold=${cold}ns/batch warm=${warm}ns/batch " +
        "speedup=${cold.toDouble() / warm}x (batch = $iterations calls)"
    )
    assertThat(cold.toDouble() / warm).isAtLeast(2.0)
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
